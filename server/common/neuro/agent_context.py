"""Что агент знает о жизни пользователя: события дней, подробности события и разговора, места, дневник.

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
    out = head + ":\n" + ("\n".join(lines) if lines else "записей нет")
    from . import calendar  # noqa: PLC0415
    st = calendar.status(conn)
    if st["connected"] and st["read"]:
        cal = calendar.events(conn, start, end)
        if cal:
            out += "\nПо календарю:\n" + "\n".join("• " + calendar.line(e) for e in cal)
    return out


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


def now_block(conn) -> str:
    """«Что сейчас»: горящие обещания, ближайшие встречи, последний разговор — чтобы на «что у меня сегодня»
    и «что горит» ассистент отвечал сразу, без лишних шагов."""
    from . import calendar as calendarlib  # noqa: PLC0415

    now = datetime.now(timezone.utc)
    lines = []
    rows = conn.execute(
        """SELECT t.id, t.text, t.kind, t.due_at, coalesce(cp.name, t.counterpart, t.assignee) AS mate
           FROM tasks t LEFT JOIN people cp ON cp.id = t.counterpart_id
           WHERE t.status = 'open' AND t.due_at IS NOT NULL AND t.due_at < %s
           ORDER BY t.due_at LIMIT 8""", (now + timedelta(days=2),)).fetchall()

    def task(t):
        who = ("обещали мне" + (f" ({t['mate']})" if t["mate"] else "")) if t["kind"] == "waiting" else \
            ("напоминание" if t["kind"] == "reminder" else "встреча" + (f" с {t['mate']}" if t["mate"] else "")
             if t["kind"] == "meeting" else "я обещал" + (f" {t['mate']}" if t["mate"] else ""))
        return f"[{t['text']}](task:{t['id']}) — {who}, срок {_d(t['due_at'])}"
    late = [t for t in rows if t["due_at"] < now]
    soon = [t for t in rows if t["due_at"] >= now]
    if late:
        lines.append("Просрочено: " + "; ".join(task(t) for t in late))
    if soon:
        lines.append("Срок в ближайшие 2 дня: " + "; ".join(task(t) for t in soon))
    try:
        if calendarlib.status(conn)["connected"]:
            ev = [e for e in calendarlib.events(conn, now, now + timedelta(days=2), 5) if not e["all_day"]]
            if ev:
                lines.append("Ближайшие встречи: " + "; ".join(calendarlib.line(e, with_day=True) for e in ev))
    except Exception:  # noqa: BLE001 — без календаря обзор всё равно полезен
        pass
    last = conn.execute(
        """SELECT id, title, started_at, last_activity FROM conversations WHERE trashed_at IS NULL
           ORDER BY last_activity DESC LIMIT 1""").fetchone()
    if last:
        ago = int((now - last["last_activity"]).total_seconds() // 60)
        lines.append(f"Последний разговор: [{last['title'] or '…'}](conversation:{last['id']}), закончился "
                     f"{ago} мин назад" if ago < 180 else
                     f"Последний разговор: [{last['title'] or '…'}](conversation:{last['id']}) {_d(last['started_at'])}")
    return "\n".join(lines)


def overview(conn) -> str:
    """Короткая шпаргалка в начало каждого запуска: что сейчас, что было сегодня и вчера, задачи, люди."""
    today = datetime.now(timezone.utc).astimezone(TZ).date()
    parts = [now_block(conn), day_overview(conn, today, with_summaries=False),
             day_overview(conn, today - timedelta(days=1), with_summaries=False)]
    n = conn.execute("SELECT count(*) AS n FROM tasks WHERE status = 'open'").fetchone()["n"]
    parts.append(f"Открытых задач: {n}.")
    named = [r["name"] for r in conn.execute(
        """SELECT name FROM people WHERE name IS NOT NULL AND NOT ignored
           ORDER BY last_seen DESC NULLS LAST LIMIT 12""").fetchall()]
    if named:
        parts.append("Люди, которых кулон узнаёт по голосу: " + ", ".join(named) + ".")
    text = "\n".join(p for p in parts if p)
    return text[:3500]


# ---------------------------------------------------------------- как пользователь разговаривает
SENTENCE_END = re.compile(r"[.!?…]\s*$")


def talk_stats(conn, conversation_id: int | None = None, day: date | None = None) -> str:
    """Разбор разговора по таймингам фраз: кто сколько говорил, сколько реплик, самый длинный монолог, вопросы,
    перебивания (реплика другого началась, пока предыдущая не закончилась или сразу после оборванной фразы).
    Без id и дня — последний разговор, где говорили хотя бы двое."""
    if conversation_id:
        ids = [conversation_id]
    elif day:
        start, end = diary.day_bounds(day)
        ids = [r["id"] for r in conn.execute(
            """SELECT id FROM conversations WHERE started_at >= %s AND started_at < %s AND trashed_at IS NULL
               ORDER BY started_at""", (start, end)).fetchall()]
    else:
        row = conn.execute(
            """SELECT c.id FROM conversations c WHERE c.trashed_at IS NULL AND c.started_at > now() - interval '7 days'
                 AND (SELECT count(DISTINCT s.person_id) FROM segments s WHERE s.conversation_id = c.id) >= 2
               ORDER BY c.started_at DESC LIMIT 1""").fetchone()
        ids = [row["id"]] if row else []
    if not ids:
        return "Подходящего разговора нет."
    segs = conn.execute(people.SEGMENT_SELECT + " WHERE s.conversation_id = ANY(%s) AND NOT s.junk "
                        "ORDER BY s.started_at, s.id", (ids,)).fetchall()
    segs = [s for s in segs if not s.get("person_ignored") and (s.get("person_kind") or "person") == "person"]
    if not segs:
        return "В разговоре нет распознанной речи."
    stats: dict[str, dict] = {}
    prev = None
    run_who, run_sec = None, 0.0
    for s in segs:
        who = people.speaker_label(s).rstrip("?")  # «Анна?» (не уверены) — тот же человек
        sec = max(0.0, (s["ended_at"] - s["started_at"]).total_seconds())
        st = stats.setdefault(who, {"sec": 0.0, "turns": 0, "words": 0, "questions": 0, "interrupts": 0,
                                    "longest": 0.0})
        st["sec"] += sec
        st["words"] += len((s["text"] or "").split())
        st["questions"] += (s["text"] or "").count("?")
        if prev is None or people.speaker_label(prev).rstrip("?") != who:
            st["turns"] += 1
            if prev is not None:
                gap = (s["started_at"] - prev["ended_at"]).total_seconds()
                if gap < 0 or gap < 0.3 and not SENTENCE_END.search(prev["text"] or ""):
                    st["interrupts"] += 1
            run_who, run_sec = who, 0.0
        run_sec += sec
        if run_who == who:
            st["longest"] = max(st["longest"], run_sec)
        prev = s
    total = sum(v["sec"] for v in stats.values()) or 1.0
    convs = conn.execute("SELECT id, title, started_at, last_activity FROM conversations WHERE id = ANY(%s) "
                         "ORDER BY started_at", (ids,)).fetchall()
    head = "; ".join(f"[{c['title'] or '…'}](conversation:{c['id']}) {_d(c['started_at'])}–{_t(c['last_activity'])}"
                     for c in convs[:6])
    out = [f"Разговор: {head}" if len(convs) == 1 else f"Разговоры ({len(convs)}): {head}"]
    for who, v in sorted(stats.items(), key=lambda x: -x[1]["sec"]):
        wpm = round(v["words"] / (v["sec"] / 60)) if v["sec"] >= 20 else None
        out.append(f"{who}: {round(100 * v['sec'] / total)}% речи ({round(v['sec'] / 60, 1)} мин), реплик "
                   f"{v['turns']}, самый длинный монолог {round(v['longest'])} с, вопросов {v['questions']}, "
                   f"перебил(а) {v['interrupts']}" + (f", темп {wpm} слов/мин" if wpm else ""))
    out.append("Тайминги автоматические: перебивания и вопросы — оценка, а не точный счёт.")
    return "\n".join(out)


# ---------------------------------------------------------------- бриф к встрече
def meeting_brief(conn, name: str | None = None, event_id: int | None = None) -> str:
    """Всё к встрече: встреча из календаря (если есть), и по каждому знакомому участнику — когда и о чём говорили,
    обещания в обе стороны."""
    from . import calendar as calendarlib  # noqa: PLC0415

    out = []
    names: list[str] = [name] if name else []
    if event_id:
        e = conn.execute("""SELECT event_id, title, starts_at, ends_at, all_day, location, notes, attendees, calendar
                            FROM calendar_events WHERE event_id = %s ORDER BY starts_at LIMIT 1""",
                         (event_id,)).fetchone()
        if e:
            out.append("Встреча: " + calendarlib.line(e, with_day=True))
            if e.get("notes"):
                out.append("Описание: " + " ".join(e["notes"].split())[:500])
            names += [x.strip() for x in (e.get("attendees") or "").split(",") if x.strip()]
            names += [w for w in re.findall(r"[А-ЯЁA-Z][а-яёa-z]{2,}", e["title"] or "")]
    known = {r["id"]: r for r in conn.execute(
        "SELECT id, name, last_seen FROM people WHERE name IS NOT NULL AND NOT is_owner").fetchall()}
    picked: list[dict] = []
    for n in names:
        key = n.lower().strip()
        for p in known.values():
            pn = (p["name"] or "").lower()
            if key and (pn == key or key in pn.split() or pn in key.split()) and p not in picked:
                picked.append(p)
    if not picked and name:
        out.append(f"Человека «{name}» нет среди узнанных по голосу — ищу по словам.")
    for p in picked[:3]:
        convs = conn.execute(
            """SELECT c.id, c.started_at, c.title, c.summary FROM conversations c
               WHERE c.trashed_at IS NULL AND EXISTS (SELECT 1 FROM segments s WHERE s.conversation_id = c.id
                                                      AND s.person_id = %s)
               ORDER BY c.started_at DESC LIMIT 5""", (p["id"],)).fetchall()
        total = conn.execute("SELECT count(DISTINCT conversation_id) AS n FROM segments WHERE person_id = %s",
                             (p["id"],)).fetchone()["n"]
        out.append(f"\n{p['name']}: разговоров всего {total}" +
                   (f", последний {_d(convs[0]['started_at'])}" if convs else ""))
        out += [f"- [{c['title'] or '…'}](conversation:{c['id']}) {_d(c['started_at'])}: "
                + " ".join((c["summary"] or "").split())[:300] for c in convs]
        tasks = conn.execute(
            """SELECT id, text, kind, status, due_at FROM tasks
               WHERE (counterpart_id = %s OR person_id = %s) AND status IN ('open', 'suggested')
               ORDER BY coalesce(due_at, created_at) LIMIT 10""", (p["id"], p["id"])).fetchall()
        if tasks:
            out.append("Обещания:")
            out += [f"- [{t['text']}](task:{t['id']}) — " + ("обещал(а) пользователю" if t["kind"] == "waiting"
                                                            else "обещал пользователь")
                    + (f", срок {_d(t['due_at'])}" if t["due_at"] else "") for t in tasks]
    if name and not picked:
        from . import memory as memorylib  # noqa: PLC0415
        hits = memorylib.recall(conn, name, k=6)
        out += [f"- [{h['kind']} {h['id']} {_d(h['at'])}] {h['text']}" for h in hits]
    return "\n".join(out) or "Нечего собрать: укажите человека или встречу из календаря."


def context_block(conn, ref: str | None) -> str:
    """Контекст, с которым пользователь открыл чат: «event:12», «conversation:5», «day:2026-10-31»."""
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


# ---------------------------------------------------------------- «Скопировать» в приложении
LABELS = {
    "ru": {"summary": "Итог", "people": "Люди", "topics": "Темы", "place": "Место", "parts": "Части",
           "tasks": "Задачи", "transcript": "Расшифровка", "diary": "Дневник"},
    "en": {"summary": "Summary", "people": "People", "topics": "Topics", "place": "Place", "parts": "Parts",
           "tasks": "Tasks", "transcript": "Transcript", "diary": "Diary"},
    "tr": {"summary": "Özet", "people": "Kişiler", "topics": "Konular", "place": "Yer", "parts": "Bölümler",
           "tasks": "Görevler", "transcript": "Transkript", "diary": "Günlük"},
    "es": {"summary": "Resumen", "people": "Personas", "topics": "Temas", "place": "Lugar", "parts": "Partes",
           "tasks": "Tareas", "transcript": "Transcripción", "diary": "Diario"},
}


def _transcript(conn, conv_ids: list[int], limit: int) -> str:
    if not conv_ids:
        return ""
    segs = conn.execute(people.SEGMENT_SELECT + " WHERE s.conversation_id = ANY(%s) AND NOT s.junk "
                        "ORDER BY s.started_at, s.id", (conv_ids,)).fetchall()
    return transcript_text(segs, limit=limit) if segs else ""


def share_text(conn, ref: str, lang: str = "ru") -> tuple[str, str] | None:
    """Вся информация об событии, разговоре или дне — простым текстом для буфера обмена: (заголовок, текст)."""
    from . import i18n  # noqa: PLC0415

    L = LABELS.get(i18n.normalize(lang), LABELS["ru"])
    kind, _, value = (ref or "").partition(":")
    try:
        if kind == "event":
            e = conn.execute(
                """SELECT e.id, e.started_at, e.ended_at, e.title, e.emoji, e.summary, e.topics, """
                + people.event_people_sql("e") + """ AS people FROM day_events e WHERE e.id = %s""",
                (int(value),)).fetchone()
            if e is None:
                return None
            title = " ".join(x for x in (e["emoji"], e["title"]) if x) or "…"
            out = [title, f"{e['started_at'].astimezone(TZ):%d.%m.%Y}, {_t(e['started_at'])}–{_t(e['ended_at'])}"]
            place = _place(conn, e["started_at"], e["ended_at"])
            if place:
                out.append(f"{L['place']}: {place}")
            if e["summary"]:
                out += ["", f"{L['summary']}: {e['summary']}"]
            who = _names(e["people"])
            if who:
                out.append(f"{L['people']}: {who}")
            if e["topics"]:
                out.append(f"{L['topics']}: " + ", ".join(e["topics"]))
            parts = conn.execute(
                """SELECT id, started_at, last_activity, title, summary FROM conversations
                   WHERE event_id = %s AND trashed_at IS NULL ORDER BY started_at""", (e["id"],)).fetchall()
            if parts:
                out += ["", f"{L['parts']}:"]
                out += [f"• {_t(p['started_at'])}–{_t(p['last_activity'])} {p['title'] or '…'}"
                        + (f" — {p['summary']}" if p["summary"] else "") for p in parts]
            text = _transcript(conn, [p["id"] for p in parts], 60000)
            if text:
                out += ["", f"{L['transcript']}:", text]
            return title, "\n".join(out)
        if kind == "conversation":
            c = conn.execute(
                """SELECT c.id, c.started_at, c.last_activity, c.title, c.emoji, c.summary, c.topics, """
                + people.participants_sql("c") + """ AS people FROM conversations c WHERE c.id = %s""",
                (int(value),)).fetchone()
            if c is None:
                return None
            title = " ".join(x for x in (c.get("emoji"), c["title"]) if x) or "…"
            out = [title, f"{_d(c['started_at'])}–{_t(c['last_activity'])}"]
            if c["summary"]:
                out += ["", f"{L['summary']}: {c['summary']}"]
            who = _names(c["people"])
            if who:
                out.append(f"{L['people']}: {who}")
            if c["topics"]:
                out.append(f"{L['topics']}: " + ", ".join(c["topics"]))
            tasks = conn.execute("SELECT text, due FROM tasks WHERE conversation_id = %s AND status <> 'dismissed'",
                                 (c["id"],)).fetchall()
            if tasks:
                out += ["", f"{L['tasks']}:"] + [f"• {t['text']}" + (f" ({t['due']})" if t["due"] else "") for t in tasks]
            text = _transcript(conn, [c["id"]], 60000)
            if text:
                out += ["", f"{L['transcript']}:", text]
            return title, "\n".join(out)
        if kind == "day":
            d = parse_day(value)
            title = f"{WEEKDAYS[d.weekday()]}, {d:%d.%m.%Y}"
            diary = diary_day(conn, d)
            parts = [title]
            if not diary.startswith("Сводки"):
                parts += ["", f"{L['diary']}:", diary]
            parts += ["", day_overview(conn, d).split(":\n", 1)[-1]]
            return title, "\n".join(parts)
    except (ValueError, TypeError):
        return None
    return None
