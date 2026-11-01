"""Календарь — через календарь телефона: Google, Samsung, Outlook, Яндекс — всё, что синхронизировано в телефон.

Подключение — одно разрешение на телефоне, без входа в Google и ключей.
- Телефон присылает события окна «2 недели назад — 2 месяца вперёд» (POST /api/calendar/sync) и список
  календарей, куда можно писать.
- Ассистент видит расписание (calendar_events) и просит изменения — добавить, перенести, удалить встречу.
  Изменения ставятся в очередь команд, телефон выполняет их в календаре сам (обычно за полминуты) и
  присылает свежие события.
- Встреча из календаря в то же время, что и разговор, подсказывает итогам название и участников.
Тексты событий в базе зашифрованы. Что ассистенту можно — «просматривать», «добавлять и изменять» — решает
пользователь.
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta, timezone

from . import crypto
from .assistant import TZ, WEEKDAYS

log = logging.getLogger("neuro.calendar")

ON_KEY = "calendar.on"
SYNC_KEY = "calendar.synced_at"
CALENDARS_KEY = "calendar.calendars"
TARGET_KEY = "calendar.target"
READ_KEY = "calendar.read"
WRITE_KEY = "calendar.write"
STALE = timedelta(days=3)  # телефон давно не присылал — считаем, что календарь не подключён


def _get(conn, key: str) -> str | None:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (key,)).fetchone()
    return row["value"] if row else None


def _set(conn, key: str, value: str | None):
    if value is None:
        conn.execute("DELETE FROM settings WHERE key = %s", (key,))
    else:
        conn.execute("""INSERT INTO settings (key, value) VALUES (%s, %s)
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", (key, value))


def status(conn) -> dict:
    synced = _get(conn, SYNC_KEY)
    synced_at = synced if isinstance(synced, datetime) else datetime.fromisoformat(str(synced)) if synced else None
    synced = synced_at.isoformat() if synced_at else None
    connected = _get(conn, ON_KEY) == "1" and synced_at is not None and datetime.now(timezone.utc) - synced_at < STALE
    try:
        calendars = json.loads(crypto.unseal(_get(conn, CALENDARS_KEY)) or "[]")
    except (TypeError, ValueError):
        calendars = []
    target = _get(conn, TARGET_KEY)
    return {
        "connected": connected, "synced_at": synced, "calendars": calendars,
        "target": int(target) if target and target.lstrip("-").isdigit() else None,
        "read": _get(conn, READ_KEY) != "0", "write": _get(conn, WRITE_KEY) != "0",
        "events": conn.execute("SELECT count(*) AS n FROM calendar_events").fetchone()["n"],
        "pending": conn.execute("SELECT count(*) AS n FROM calendar_commands WHERE status = 'pending'").fetchone()["n"],
    }


def settings(conn, read: bool | None = None, write: bool | None = None, target: int | None = None) -> dict:
    if read is not None:
        _set(conn, READ_KEY, "1" if read else "0")
    if write is not None:
        _set(conn, WRITE_KEY, "1" if write else "0")
    if target is not None:
        _set(conn, TARGET_KEY, str(int(target)))
    return status(conn)


def _ts(value) -> datetime:
    """Миллисекунды эпохи или ISO -> время с поясом."""
    if isinstance(value, (int, float)) or str(value).lstrip("-").isdigit():
        return datetime.fromtimestamp(int(value) / 1000, timezone.utc)
    dt = datetime.fromisoformat(str(value))
    return dt if dt.tzinfo else dt.replace(tzinfo=TZ)


def sync(conn, events: list[dict], window_from, window_to, calendars: list[dict] | None = None) -> int:
    """Телефон прислал события окна: окно заменяется целиком (удалённые в календаре исчезают и здесь)."""
    start, end = _ts(window_from), _ts(window_to)
    rows = []
    for e in events[:3000]:
        try:
            s, f = _ts(e["begin"]), _ts(e["end"])
            eid = int(e["event_id"])
        except (KeyError, TypeError, ValueError):
            continue
        title = " ".join(str(e.get("title") or "").split())[:300]
        rows.append((f"{eid}:{int(s.timestamp())}", eid, e.get("calendar_id"), crypto.seal(str(e.get("calendar") or "")[:120]),
                     crypto.seal(title), s, max(f, s), bool(e.get("all_day")),
                     crypto.seal(" ".join(str(e.get("location") or "").split())[:300] or None),
                     crypto.seal(str(e.get("notes") or "").strip()[:1000] or None),
                     crypto.seal(", ".join(str(a) for a in (e.get("attendees") or [])[:20])[:600] or None)))
    with conn.transaction():
        conn.execute("DELETE FROM calendar_events WHERE starts_at >= %s AND starts_at < %s", (start, end))
        for r in rows:
            conn.execute(
                """INSERT INTO calendar_events (uid, event_id, calendar_id, calendar, title, starts_at, ends_at, all_day,
                                                location, notes, attendees, synced_at)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())
                   ON CONFLICT (uid) DO UPDATE SET title = EXCLUDED.title, ends_at = EXCLUDED.ends_at,
                       calendar = EXCLUDED.calendar, location = EXCLUDED.location, notes = EXCLUDED.notes,
                       attendees = EXCLUDED.attendees, all_day = EXCLUDED.all_day, synced_at = now()""", r)
        _set(conn, ON_KEY, "1")
        _set(conn, SYNC_KEY, datetime.now(timezone.utc).isoformat())
        if calendars is not None:
            clean = [{"id": int(c["id"]), "name": str(c.get("name") or "")[:120], "account": str(c.get("account") or "")[:120],
                      "primary": bool(c.get("primary"))} for c in calendars[:50] if str(c.get("id", "")).lstrip("-").isdigit()]
            _set(conn, CALENDARS_KEY, crypto.seal(json.dumps(clean, ensure_ascii=False)))
    return len(rows)


def disconnect(conn):
    with conn.transaction():
        conn.execute("DELETE FROM calendar_events")
        conn.execute("UPDATE calendar_commands SET status = 'cancelled' WHERE status = 'pending'")
        for key in (ON_KEY, SYNC_KEY, CALENDARS_KEY):
            _set(conn, key, None)


# ---------------------------------------------------------------- чтение
def events(conn, start: datetime, end: datetime, limit: int = 200) -> list[dict]:
    return conn.execute(
        """SELECT uid, event_id, calendar, title, starts_at, ends_at, all_day, location, notes, attendees
           FROM calendar_events WHERE starts_at < %s AND ends_at > %s ORDER BY starts_at LIMIT %s""",
        (end, start, limit)).fetchall()


def line(e: dict, with_day: bool = False) -> str:
    s, f = e["starts_at"].astimezone(TZ), e["ends_at"].astimezone(TZ)
    when = ("весь день" if e["all_day"] else f"{s:%H:%M}–{f:%H:%M}")
    if with_day:
        when = f"{WEEKDAYS[s.weekday()]} {s:%d.%m} {when}"
    extra = "; ".join(x for x in (e.get("location"), e.get("attendees") and "участники: " + e["attendees"]) if x)
    return f"[встреча {e['event_id']}] {when} {e['title'] or '(без названия)'}" + (f" ({extra})" if extra else "")


def overview(conn, start: datetime, end: datetime) -> str:
    rows = events(conn, start, end)
    multi = (end - start) > timedelta(days=1)
    return "\n".join(line(e, with_day=multi) for e in rows) or "В календаре на это время ничего нет."


def around(conn, start: datetime, end: datetime) -> dict | None:
    """Встреча из календаря, которая идёт в это время (для итогов разговора)."""
    rows = [e for e in events(conn, start - timedelta(minutes=10), end + timedelta(minutes=10), 10) if not e["all_day"]]
    if not rows:
        return None

    def overlap(e):
        return (min(e["ends_at"], end) - max(e["starts_at"], start)).total_seconds()
    best = max(rows, key=overlap)
    return best if overlap(best) > 0 else None


# ---------------------------------------------------------------- изменения (выполняет телефон)
def enqueue(conn, op: str, payload: dict) -> int:
    if op not in ("create", "update", "delete"):
        raise ValueError("op")
    return conn.execute("INSERT INTO calendar_commands (op, payload) VALUES (%s, %s) RETURNING id",
                        (op, crypto.seal(json.dumps(payload, ensure_ascii=False)))).fetchone()["id"]


def pending(conn) -> list[dict]:
    rows = conn.execute("SELECT id, op, payload, created_at FROM calendar_commands WHERE status = 'pending' "
                        "ORDER BY id LIMIT 20").fetchall()
    out = []
    for r in rows:
        try:
            payload = json.loads(r["payload"])
        except (TypeError, ValueError):
            payload = {}
        out.append({"id": r["id"], "op": r["op"], "payload": payload})
    # старые команды (телефон долго не был на связи) не выполняем внезапно через сутки
    conn.execute("UPDATE calendar_commands SET status = 'expired' WHERE status = 'pending' "
                 "AND created_at < now() - interval '1 day'")
    return out


def complete(conn, command_id: int, ok: bool, result: str | None = None) -> bool:
    return conn.execute("UPDATE calendar_commands SET status = %s, result = %s, done_at = now() WHERE id = %s",
                        ("done" if ok else "failed", crypto.seal((result or "")[:300] or None), command_id)).rowcount > 0


def parse_when(value: str | None) -> datetime | None:
    """«2026-11-02 15:00» / ISO -> время в часовом поясе пользователя."""
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(str(value).strip().replace(" ", "T"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=TZ)
