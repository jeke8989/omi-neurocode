"""Что агент знает о жизни владельца: события дней, подробности события и разговора, места, дневник.

Инструменты агента и «обзор» для начала каждого запуска: агент сразу видит, какие события были сегодня и
вчера, и может открыть любое подробно — с людьми, темами и расшифровкой. Тексты в базе зашифрованы;
здесь они уже расшифрованы (crypto.dict_row).
"""
from __future__ import annotations

import re
from datetime import date, datetime, timedelta, timezone

from . import diary, people
from .assistant import TZ, WEEKDAYS, transcript_text


def _t(dt) -> str:
    return dt.astimezone(TZ).strftime("%H:%M")


def _d(dt) -> str:
    return dt.astimezone(TZ).strftime("%d.%m %H:%M")


def parse_day(value) -> date:
    """«сегодня», «вчера», «позавчера», YYYY-MM-DD или DD.MM — в дату (по местному времени)."""
    today = datetime.now(timezone.utc).astimezone(TZ).date()
    s = str(value or "").strip().lower()
    if not s or s in ("today", "сегодня", "bugün", "hoy"):
        return today
    if s in ("yesterday", "вчера", "dün", "ayer"):
        return today - timedelta(days=1)
    if s in ("позавчера", "day before yesterday"):
        return today - timedelta(days=2)
    try:
        return date.fromisoformat(s[:10])
    except ValueError:
        pass
    m = re.fullmatch(r"(\d{1,2})\.(\d{1,2})(?:\.(\d{2,4}))?", s)
    if m:
        year = int(m.group(3)) if m.group(3) else today.year
        year = year + 2000 if year < 100 else year
        try:
            return date(year, int(m.group(2)), int(m.group(1)))
        except ValueError:
            pass
    return today


def _names(items) -> str:
    out = []
    for p in items or []:
        if p.get("is_owner"):
            continue
        out.append(p.get("name") or f"Голос {p.get('number')}")
    return ", ".join(out)


def day_overview(conn, day: date, with_summaries: bool = True) -> str:
    """События дня по порядку: время, название, люди, итог; и ещё не собранные разговоры."""
    start, end = diary.day_bounds(day)
    events = conn.execute(
        """SELECT e.id, e.started_at, e.ended_at, e.title, e.emoji, e.summary, e.topics, """
        + people.event_people_sql("e") + """ AS people,
                  (SELECT count(*) FROM conversations c WHERE c.event_id = e.id AND c.trashed_at IS NULL) AS parts
           FROM day_events e WHERE e.day = %s ORDER BY e.started_at""", (day,)).fetchall()
    loose = conn.execute(
        """SELECT c.id, c.started_at, c.last_activity, c.title, c.summary FROM conversations c
           WHERE c.started_at >= %s AND c.started_at < %s AND c.event_id IS NULL AND c.trashed_at IS NULL
           ORDER BY c.started_at""", (start, end)).fetchall()
    lines = []
    for e in events:
        if not e["parts"]:
            continue
        line = f"• событие #{e['id']} {_t(e['started_at'])}–{_t(e['ended_at'])} {e['emoji'] or ''} {e['title'] or '…'}"
        who = _names(e["people"])
        if who:
            line += f" (с кем: {who})"
        if with_summaries and e["summary"]:
            line += f" — {e['summary']}"
        lines.append(line)
    for c in loose:
        line = f"• разговор #{c['id']} {_t(c['started_at'])}–{_t(c['last_activity'])} {c['title'] or '…'}"
        if with_summaries and c["summary"]:
            line += f" — {c['summary']}"
        lines.append(line)
    head = f"{WEEKDAYS[day.weekday()]}, {day.strftime('%d.%m.%Y')}"
    return head + ":\n" + ("\n".join(lines) if lines else "записей нет")


def event_details(conn, eid: int, transcript_chars: int = 6000) -> str:
    e = conn.execute(
        """SELECT e.id, e.day, e.started_at, e.ended_at, e.title, e.emoji, e.kind, e.summary, e.topics, """
        + people.event_people_sql("e") + """ AS people FROM day_events e WHERE e.id = %s""", (eid,)).fetchone()
    if e is None:
        return f"События #{eid} нет."
    parts = conn.execute(
        """SELECT id, started_at, last_activity, title, summary FROM conversations
           WHERE event_id = %s AND trashed_at IS NULL ORDER BY started_at""", (eid,)).fetchall()
    out = [f"Событие #{e['id']}: {e['emoji'] or ''} {e['title'] or '…'}",
           f"Когда: {e['started_at'].astimezone(TZ):%d.%m.%Y}, {_t(e['started_at'])}–{_t(e['ended_at'])}"]
    if e["summary"]:
        out.append(f"Итог: {e['summary']}")
    who = _names(e["people"])
    if who:
        out.append(f"Люди: {who}")
    if e["topics"]:
        out.append("Темы: " + ", ".join(e["topics"]))
    place = _place(conn, e["started_at"], e["ended_at"])
    if place:
        out.append(f"Место: {place}")
    out.append("Части события:")
    out += [f"• разговор #{p['id']} {_t(p['started_at'])}–{_t(p['last_activity'])} {p['title'] or '…'}"
            + (f" — {p['summary']}" if p["summary"] else "") for p in parts]
    ids = [p["id"] for p in parts]
    if ids and transcript_chars:
        segs = conn.execute(people.SEGMENT_SELECT + " WHERE s.conversation_id = ANY(%s) AND NOT s.junk "
                            "ORDER BY s.started_at, s.id", (ids,)).fetchall()
        if segs:
            out.append("Расшифровка (автоматическая, возможны ошибки):\n" + transcript_text(segs, limit=transcript_chars))
    return "\n".join(out)


def conversation_details(conn, cid: int, transcript_chars: int = 8000) -> str:
    c = conn.execute(
        """SELECT c.id, c.started_at, c.last_activity, c.title, c.summary, c.topics, c.event_id, """
        + people.participants_sql("c") + """ AS people FROM conversations c WHERE c.id = %s""", (cid,)).fetchone()
    if c is None:
        return f"Разговора #{cid} нет."
    out = [f"Разговор #{c['id']}: {c['title'] or '…'}", f"Когда: {_d(c['started_at'])}–{_t(c['last_activity'])}"]
    if c["summary"]:
        out.append(f"Итог: {c['summary']}")
    who = _names(c["people"])
    if who:
        out.append(f"Люди: {who}")
    if c["topics"]:
        out.append("Темы: " + ", ".join(c["topics"]))
    if c["event_id"]:
        out.append(f"Часть события #{c['event_id']}")
    tasks = conn.execute("SELECT text, due, status FROM tasks WHERE conversation_id = %s AND status <> 'dismissed'",
                         (cid,)).fetchall()
    if tasks:
        out.append("Задачи: " + "; ".join(t["text"] + (f" (срок: {t['due']})" if t["due"] else "") for t in tasks))
    segs = conn.execute(people.SEGMENT_SELECT + " WHERE s.conversation_id = %s AND NOT s.junk ORDER BY s.started_at, s.id",
                        (cid,)).fetchall()
    if segs and transcript_chars:
        out.append("Расшифровка (автоматическая, возможны ошибки):\n" + transcript_text(segs, limit=transcript_chars))
    return "\n".join(out)


def _place(conn, start, end) -> str | None:
    from . import geo  # noqa: PLC0415 — geo импортирует diary

    pts = geo.points(conn, start - timedelta(minutes=10), end + timedelta(minutes=10))
    at = geo.where(pts, start, end) if pts else None
    return diary.place_name(conn, at[0], at[1], network=False) if at else None


def places_day(conn, day: date) -> str:
    from . import geo  # noqa: PLC0415

    m = geo.day_map(conn, day)
    if not m["places"]:
        return "Геолокации за этот день нет."
    return "Где был:\n" + "\n".join(
        f"• {p['from'].astimezone(TZ):%H:%M}–{p['to'].astimezone(TZ):%H:%M} {p['name'] or 'место без названия'}"
        for p in m["places"])


def diary_day(conn, day: date) -> str:
    d = conn.execute("SELECT title, summary, mood, quote, highlights FROM diary_days WHERE day = %s AND status = 'done'",
                     (day,)).fetchone()
    if d is None:
        return "Сводки за этот день ещё нет."
    out = [f"{d['title'] or ''} {d['mood'] or ''}".strip(), d["summary"] or ""]
    for h in d["highlights"] or []:
        if isinstance(h, dict):
            out.append(f"• {h.get('time', '')} {h.get('title', '')}: {h.get('text', '')}")
    if d["quote"]:
        out.append(f"Цитата дня: {d['quote']}")
    return "\n".join(x for x in out if x)


def overview(conn) -> str:
    """Короткая шпаргалка в начало каждого запуска: что было сегодня и вчера, задачи, люди."""
    today = datetime.now(timezone.utc).astimezone(TZ).date()
    parts = [day_overview(conn, today, with_summaries=False), day_overview(conn, today - timedelta(days=1),
                                                                          with_summaries=False)]
    n = conn.execute("SELECT count(*) AS n FROM tasks WHERE status = 'open'").fetchone()["n"]
    parts.append(f"Открытых задач: {n}.")
    named = [r["name"] for r in conn.execute(
        """SELECT name FROM people WHERE name IS NOT NULL AND NOT ignored
           ORDER BY last_seen DESC NULLS LAST LIMIT 12""").fetchall()]
    if named:
        parts.append("Люди, которых кулон узнаёт по голосу: " + ", ".join(named) + ".")
    text = "\n".join(parts)
    return text[:3000]


def context_block(conn, ref: str | None) -> str:
    """Контекст, с которым владелец открыл чат: «event:12», «conversation:5», «day:2026-10-31»."""
    if not ref or ":" not in ref:
        return ""
    kind, _, value = ref.partition(":")
    try:
        if kind == "event":
            return event_details(conn, int(value), transcript_chars=12000)
        if kind == "conversation":
            return conversation_details(conn, int(value), transcript_chars=12000)
        if kind == "day":
            d = parse_day(value)
            return "\n\n".join(x for x in (day_overview(conn, d), diary_day(conn, d)) if x)
    except (ValueError, TypeError):
        return ""
    return ""
