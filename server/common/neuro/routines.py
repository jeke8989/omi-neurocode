"""Постоянные правила и расписание агента — то, что пользователь сказал один раз и что действует дальше.

Правила: «итоги встреч с клиентами клади в Notion, страница Клиенты», «по-английски отвечай коротко».
Агент записывает их сам (remember_rule), видит в каждом запросе и удаляет по просьбе (forget_rule).

Расписание: «каждый день в 23:00 делай сводку дня в Notion», «по пятницам в 18:00 — итоги недели»,
«завтра в 9 напомни позвонить маме». Время — в часовом поясе пользователя. В срок агент выполняет задание
сам (с теми же инструментами, что и в чате), ответ приходит в чат и уведомлением.

Всё хранится в базе этого пользователя (у каждого — свой сервер и своя база с ключом), тексты зашифрованы.
"""
from __future__ import annotations

import logging
import re
from datetime import date, datetime, timedelta, timezone

from . import crypto
from .assistant import TZ

log = logging.getLogger("neuro.routines")

DAYS = {"mon": 0, "tue": 1, "wed": 2, "thu": 3, "fri": 4, "sat": 5, "sun": 6,
        "пн": 0, "вт": 1, "ср": 2, "чт": 3, "пт": 4, "сб": 5, "вс": 6,
        "пон": 0, "вто": 1, "сре": 2, "чет": 3, "пят": 4, "суб": 5, "вос": 6}
DAY_NAMES = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
MAX_RULES = 50
MAX_SCHEDULES = 30


# ---------------------------------------------------------------- правила
def rules(conn) -> list[dict]:
    return conn.execute("SELECT id, text, created_at FROM agent_rules ORDER BY id").fetchall()


def add_rule(conn, text: str) -> int:
    text = " ".join(str(text or "").split())[:500]
    if len(text) < 3:
        raise ValueError("пустое правило")
    if len(rules(conn)) >= MAX_RULES:
        raise ValueError(f"правил уже {MAX_RULES} — удалите ненужные")
    return conn.execute("INSERT INTO agent_rules (text) VALUES (%s) RETURNING id", (crypto.seal(text),)).fetchone()["id"]


def forget_rule(conn, rule_id: int) -> bool:
    return conn.execute("DELETE FROM agent_rules WHERE id = %s", (rule_id,)).rowcount > 0


# ---------------------------------------------------------------- расписание
def _hhmm(value: str | None) -> tuple[int, int] | None:
    m = re.fullmatch(r"\s*(\d{1,2})[:.](\d{2})\s*", str(value or ""))
    if not m:
        return None
    h, mi = int(m.group(1)), int(m.group(2))
    return (h, mi) if 0 <= h < 24 and 0 <= mi < 60 else None


def parse_days(value) -> list[int]:
    """«daily», ["mon","fri"], [0,4], «пн,ср» -> номера дней (0 — понедельник); пусто — каждый день."""
    if value in (None, "", "daily", "every day", "ежедневно", "каждый день"):
        return []
    if value in ("weekdays", "будни"):
        return [0, 1, 2, 3, 4]
    if value in ("weekends", "выходные"):
        return [5, 6]
    items = value if isinstance(value, list) else re.split(r"[,\s]+", str(value))
    out = set()
    for it in items:
        if isinstance(it, int) or str(it).isdigit():
            if 0 <= int(it) <= 6:
                out.add(int(it))
        elif str(it).strip().lower()[:3] in DAYS or str(it).strip().lower()[:2] in DAYS:
            key = str(it).strip().lower()
            out.add(DAYS.get(key[:3], DAYS.get(key[:2])))
    return sorted(out)


def next_run(time_of_day: str | None, weekdays: list[int], once_at: datetime | None,
             after: datetime) -> datetime | None:
    """Следующий запуск после after (в часовом поясе пользователя)."""
    if once_at is not None:
        return once_at if once_at > after else None
    hm = _hhmm(time_of_day)
    if hm is None:
        return None
    local = after.astimezone(TZ)
    for i in range(0, 8):
        d: date = local.date() + timedelta(days=i)
        at = datetime(d.year, d.month, d.day, hm[0], hm[1], tzinfo=TZ)
        if weekdays and at.weekday() not in weekdays:
            continue
        if at > after:
            return at.astimezone(timezone.utc)
    return None


def describe(s: dict) -> str:
    """«каждый день в 23:00», «пн, пт в 18:00», «02.11 в 09:00»."""
    if s.get("once_at"):
        return s["once_at"].astimezone(TZ).strftime("%d.%m в %H:%M")
    days = s.get("weekdays") or []
    when = "каждый день" if not days else ("по будням" if days == [0, 1, 2, 3, 4] else
                                          ", ".join(DAY_NAMES[d] for d in days))
    return f"{when} в {s.get('time_of_day')}"


def add_schedule(conn, title: str, prompt: str, time_of_day: str | None = None, days=None,
                 once_at: str | None = None) -> dict:
    title = " ".join(str(title or "").split())[:120]
    prompt = " ".join(str(prompt or "").split())[:2000]
    if not prompt:
        raise ValueError("нет задания")
    once = None
    if once_at:
        try:
            once = datetime.fromisoformat(str(once_at).replace(" ", "T"))
        except ValueError as exc:
            raise ValueError("once_at: YYYY-MM-DD HH:MM") from exc
        once = once.replace(tzinfo=TZ) if once.tzinfo is None else once
    elif _hhmm(time_of_day) is None:
        raise ValueError("time: ЧЧ:ММ")
    weekdays = parse_days(days)
    if once is None:
        h, m = _hhmm(time_of_day)
        time_of_day = f"{h:02d}:{m:02d}"
    nxt = next_run(time_of_day, weekdays, once, datetime.now(timezone.utc))
    if nxt is None:
        raise ValueError("это время уже прошло")
    if conn.execute("SELECT count(*) AS n FROM agent_schedules").fetchone()["n"] >= MAX_SCHEDULES:
        raise ValueError(f"заданий уже {MAX_SCHEDULES} — удалите ненужные")
    row = conn.execute(
        """INSERT INTO agent_schedules (title, prompt, time_of_day, weekdays, once_at, next_run_at)
           VALUES (%s, %s, %s, %s::int[], %s, %s) RETURNING *""",
        (crypto.seal(title or prompt[:60]), crypto.seal(prompt), None if once else time_of_day, weekdays, once, nxt),
    ).fetchone()
    return row


def schedules(conn) -> list[dict]:
    return conn.execute("SELECT * FROM agent_schedules ORDER BY enabled DESC, next_run_at NULLS LAST, id").fetchall()


def cancel_schedule(conn, schedule_id: int) -> bool:
    return conn.execute("DELETE FROM agent_schedules WHERE id = %s", (schedule_id,)).rowcount > 0


def set_enabled(conn, schedule_id: int, on: bool) -> bool:
    row = conn.execute("SELECT * FROM agent_schedules WHERE id = %s", (schedule_id,)).fetchone()
    if row is None:
        return False
    nxt = next_run(row["time_of_day"], row["weekdays"], row["once_at"], datetime.now(timezone.utc)) if on else None
    conn.execute("UPDATE agent_schedules SET enabled = %s, next_run_at = %s WHERE id = %s",
                 (on and nxt is not None, nxt, schedule_id))
    return True


def run_due(conn, limit: int = 3) -> int:
    """Агент, раз в цикл: подошло время — выполнить задание. Следующий срок ставим до запуска (не повторится,
    даже если запуск упадёт)."""
    from . import agent_core  # noqa: PLC0415 — агент тянет много всего

    now = datetime.now(timezone.utc)
    due = conn.execute("""SELECT * FROM agent_schedules WHERE enabled AND next_run_at <= %s
                          ORDER BY next_run_at LIMIT %s""", (now, limit)).fetchall()
    for s in due:
        if now - s["next_run_at"] > timedelta(hours=6):  # сервер был выключен — старое не догоняем
            nxt = next_run(s["time_of_day"], s["weekdays"], s["once_at"], now)
            conn.execute("UPDATE agent_schedules SET next_run_at = %s, enabled = %s, last_status = 'missed' WHERE id = %s",
                         (nxt, nxt is not None, s["id"]))
            continue
        nxt = next_run(s["time_of_day"], s["weekdays"], s["once_at"], now)
        conn.execute("""UPDATE agent_schedules SET next_run_at = %s, enabled = %s, last_run_at = now(),
                        last_status = 'running' WHERE id = %s""", (nxt, nxt is not None, s["id"]))
        question = f"⏰ {s['title']}: {s['prompt']}"
        try:
            res = agent_core.ask(conn, question, level="tools", channel="schedule")
            status = "done"
            conn.execute("UPDATE agent_schedules SET last_status = %s, last_run_id = %s WHERE id = %s",
                         (status, res["run_id"], s["id"]))
        except Exception as exc:  # noqa: BLE001
            log.exception("schedule %s failed", s["id"])
            conn.execute("UPDATE agent_schedules SET last_status = %s WHERE id = %s", ("failed: " + str(exc)[:200], s["id"]))
    return len(due)


def results(conn, after_run: int = 0, limit: int = 10) -> list[dict]:
    """Готовые ответы заданий по расписанию (для уведомлений в приложении)."""
    return conn.execute(
        """SELECT r.id, r.question, r.answer, r.status, r.finished_at FROM agent_runs r
           WHERE r.channel = 'schedule' AND r.id > %s AND r.finished_at IS NOT NULL
           ORDER BY r.id LIMIT %s""", (after_run, limit)).fetchall()


# ---------------------------------------------------------------- для агента
def prompt_block(conn) -> str:
    """Короткий блок в каждый запрос агента: правила и расписание (с номерами — чтобы удалять по просьбе)."""
    parts = []
    rs = rules(conn)
    if rs:
        parts.append("Постоянные правила пользователя (выполняй их, когда они к месту):\n"
                     + "\n".join(f"- [правило {r['id']}] {r['text']}" for r in rs))
    ss = [s for s in schedules(conn) if s["enabled"]]
    if ss:
        parts.append("Задания по расписанию:\n" + "\n".join(
            f"- [задание {s['id']}] {describe(s)}: {s['prompt']}" for s in ss))
    return "\n".join(parts)
