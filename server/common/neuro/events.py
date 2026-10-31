"""События дня: фрагменты разговоров собираются в осмысленные события, мусор — в корзину.

Кулон режет речь на фрагменты по паузам в 2 минуты, поэтому одна встреча часто превращается в 3–5
обрывков. Раз в 15 минут ассистент берёт фрагменты, закончившиеся час назад и раньше, и решает:
что из них одно событие («Обед с Анной в кафе», «Поездка в машине»), а что — мусор (шум, телевизор,
обрывки без смысла). Учитываются время, место (геолокация), люди по голосам и тема. Модель видит
только короткие итоги фрагментов, не расшифровки — это дёшево.

Ночью (после 03:00) — финальный проход по вчерашнему дню: события уточняются, задачи из разговоров
перепроверяются по строгим правилам, после этого собирается сводка дня (дневник).
Мусор лежит в корзине 7 дней, потом удаляется; его можно вернуть из приложения.
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta, timezone

from . import crypto, diary, geo, i18n, llm

log = logging.getLogger("neuro.events")

KINDS = ("встреча", "звонок", "работа", "поездка", "семья", "друзья", "покупки", "отдых", "учёба", "спорт",
         "здоровье", "другое")
GROUP_AFTER_MIN = 60     # фрагмент «отлёживается» час: вдруг разговор продолжится
TRASH_DAYS = 7

GROUP_SYSTEM = f"""Ты собираешь день владельца носимого кулона-диктофона из фрагментов разговоров в события.
Фрагменты нарезаны автоматически по паузам, поэтому одна встреча часто разбита на несколько кусков.
Верни строго JSON без пояснений:
{{"events": [{{"id": "номер существующего события или null", "title": "название события, до 6 слов",
   "emoji": "одно эмодзи", "kind": "{' | '.join(KINDS)}",
   "summary": "что было и к чему пришли, 1–3 предложения",
   "vivid": "0..1 — насколько это яркое воспоминание",
   "fragments": [номера фрагментов]}}],
 "junk": [{{"fragment": номер, "reason": "шум | телевизор | обрывок | не разговор"}}]}}
Правила:
• Событие — одна ситуация: то же место и время, те же люди или одна тема. Фрагменты подряд с разрывом
  до часа в той же обстановке — одно событие. Другое место, другие люди или другая тема — другое событие.
• Если фрагмент продолжает уже существующее событие — добавь его туда (верни id события).
• Мусор — только то, в чём нет смысла: шум, обрывки из нескольких слов, телевизор или радио, случайные
  чужие фразы. Фрагмент с задачей, договорённостью или важным фактом — не мусор.
• Каждый фрагмент — ровно в одном событии или в мусоре.
• vivid — яркость воспоминания: 0.8–1 — сильные эмоции, важное решение, новый человек или место, праздник,
  что-то необычное; 0.4–0.7 — содержательная встреча или разговор; 0.1–0.3 — рутина, бытовые мелочи."""

REVIEW_SYSTEM = """Ты перепроверяешь задачи, которые ассистент достал из разговоров за день. Строгие правила:
задача — только договорённость о действии с участием владельца (он пообещал или согласился, ему пообещали,
он попросил напомнить). Обсуждение вариантов, идеи, «надо бы», «может быть» — не задачи. Одинаковые по смыслу
задачи — дубли: оставь одну. Верни строго JSON: {"decisions": [{"id": номер, "verdict": "keep | suggest | drop",
"same_as": "номер задачи, если это дубль, иначе null"}]}"""


def _t(dt) -> str:
    return dt.astimezone(diary.TZ).strftime("%H:%M")


def _place(visits: list[dict], start, end) -> str | None:
    mid = start + (end - start) / 2
    for v in visits:
        if v["start"] - timedelta(minutes=10) <= mid <= v["end"] + timedelta(minutes=10):
            return v.get("name")
    return None


def _fragments(conn, day: date, final: bool) -> list[dict]:
    start, end = diary.day_bounds(day)
    cond = "" if final else (" AND (c.event_id IS NULL OR c.event_id IN (SELECT id FROM day_events WHERE ended_at > "
                             "now() - interval '4 hours'))")
    rows = conn.execute(
        f"""SELECT c.id, c.started_at, c.last_activity, c.title, c.summary, c.event_id, c.topics,
                   (SELECT count(*) FROM tasks t WHERE t.conversation_id = c.id AND t.status <> 'dismissed') AS tasks,
                   (SELECT count(*) FROM memories m WHERE m.conversation_id = c.id AND m.archived_at IS NULL) AS memories,
                   ARRAY(SELECT s.text FROM segments s WHERE s.conversation_id = c.id AND NOT s.junk) AS texts,
                   (SELECT array_agg(DISTINCT coalesce(p.name, CASE WHEN p.is_owner THEN 'владелец' END))
                      FROM segments s JOIN people p ON p.id = s.person_id
                      WHERE s.conversation_id = c.id AND (p.name IS NOT NULL OR p.is_owner)) AS people
            FROM conversations c
            WHERE c.started_at >= %s AND c.started_at < %s AND c.status = 'done' AND c.trashed_at IS NULL
              AND (%s OR c.last_activity < now() - %s * interval '1 minute'){cond}
            ORDER BY c.started_at""", (start, end, final, GROUP_AFTER_MIN)).fetchall()
    for r in rows:  # тексты зашифрованы в базе — слова считаем здесь
        r["words"] = sum(len((t or "").split()) for t in (r.pop("texts", None) or []))
    return rows


def _fragment_line(f: dict, place: str | None) -> str:
    people = ", ".join(p for p in (f.get("people") or []) if p)
    bits = [f"#{f['id']} {_t(f['started_at'])}–{_t(f['last_activity'])}"]
    if place:
        bits.append(f"место: {place}")
    if people:
        bits.append(f"люди: {people}")
    bits.append(f"«{f['title'] or '…'}»: {(f['summary'] or '').strip()[:300]}")
    bits.append(f"(слов {f['words']}, задач {f['tasks']}, фактов {f['memories']})")
    return " | ".join(bits)


def group_day(conn, day: date, final: bool = False) -> dict:
    """Собрать фрагменты дня в события. final — финальная ночная сборка всего дня."""
    frags = _fragments(conn, day, final)
    if not frags:
        return {"events": 0, "junk": 0}
    start, end = diary.day_bounds(day)
    points = geo.points(conn, start, end)
    vs = diary.visits(points)
    for v in vs:
        v["name"] = diary.place_name(conn, v["lat"], v["lon"])
    existing = conn.execute(
        """SELECT e.id, e.title, e.started_at, e.ended_at,
                  coalesce((SELECT array_agg(c.id ORDER BY c.started_at) FROM conversations c
                            WHERE c.event_id = e.id AND c.trashed_at IS NULL), '{}') AS fragments
           FROM day_events e WHERE e.day = %s ORDER BY e.started_at""", (day,)).fetchall()
    by_id = {f["id"]: f for f in frags}
    if llm.configured():
        lines = ["Фрагменты:"] + [_fragment_line(f, _place(vs, f["started_at"], f["last_activity"])) for f in frags]
        if existing:
            lines.append("Уже собранные события дня:")
            lines += [f"событие {e['id']} «{e['title']}» {_t(e['started_at'])}–{_t(e['ended_at'])}: фрагменты "
                      + ", ".join(f"#{x}" for x in e["fragments"]) for e in existing]
        result = llm.chat_json(conn, "summary", GROUP_SYSTEM + "\n" + i18n.write_in(i18n.lang(conn)), "\n".join(lines),
                               usage="events", fast=True)  # механическая группировка — без долгих рассуждений
    else:
        result = {"events": _by_gap(frags), "junk": []}
    return _apply(conn, day, result, by_id, {e["id"] for e in existing}, final)


def _by_gap(frags: list[dict]) -> list[dict]:
    """Без модели: подряд идущие фрагменты с разрывом до 30 минут — одно событие."""
    out: list[dict] = []
    for f in frags:
        if out and f["started_at"] - out[-1]["_end"] <= timedelta(minutes=30):
            out[-1]["fragments"].append(f["id"])
            out[-1]["_end"] = max(out[-1]["_end"], f["last_activity"])
        else:
            out.append({"id": f["event_id"], "title": f["title"], "emoji": None, "kind": "другое", "summary": None,
                        "fragments": [f["id"]], "_end": f["last_activity"]})
    return out


def _int(v) -> int | None:
    try:
        return int(str(v).lstrip("#"))
    except (TypeError, ValueError):
        return None


def _apply(conn, day: date, result: dict, by_id: dict, existing: set[int], final: bool) -> dict:
    placed: set[int] = set()
    trashed = 0
    for j in result.get("junk") or []:
        fid = _int(j.get("fragment") if isinstance(j, dict) else j)
        f = by_id.get(fid)
        if not f or f["tasks"] or f["memories"]:
            continue  # с задачей или фактом — не мусор, даже если модель решила иначе
        conn.execute("UPDATE conversations SET trashed_at = now(), junk_reason = %s, event_id = NULL WHERE id = %s",
                     (str((j.get("reason") if isinstance(j, dict) else "") or "мусор")[:60], fid))
        placed.add(fid)
        trashed += 1
    touched: set[int] = set()
    for ev in result.get("events") or []:
        if not isinstance(ev, dict):
            continue
        ids = [i for i in (_int(x) for x in ev.get("fragments") or []) if i in by_id and i not in placed]
        if not ids:
            continue
        eid = _int(ev.get("id"))
        kind = str(ev.get("kind") or "другое").strip().lower()
        kind = kind if kind in KINDS else "другое"
        title = " ".join(str(ev.get("title") or by_id[ids[0]]["title"] or "…").split())[:120]
        emoji = (str(ev.get("emoji") or "").strip() or None)
        summary = " ".join(str(ev.get("summary") or "").split())[:1000] or None
        try:
            vivid = max(0.0, min(1.0, float(ev.get("vivid"))))
        except (TypeError, ValueError):
            vivid = None
        if eid not in existing:
            eid = conn.execute(
                """INSERT INTO day_events (day, started_at, ended_at, title, emoji, kind, summary, vivid)
                   VALUES (%s, now(), now(), %s, %s, %s, %s, %s) RETURNING id""",
                (day, crypto.seal(title), emoji, kind, crypto.seal(summary), vivid)).fetchone()["id"]
        else:
            conn.execute(
                """UPDATE day_events SET title = %s, emoji = coalesce(%s, emoji), kind = %s,
                       summary = coalesce(%s, summary), vivid = coalesce(%s, vivid), updated_at = now() WHERE id = %s""",
                (crypto.seal(title), emoji, kind, crypto.seal(summary), vivid, eid))
        conn.execute("UPDATE conversations SET event_id = %s WHERE id = ANY(%s)", (eid, ids))
        placed.update(ids)
        touched.add(eid)
    # всё, что модель пропустила, — отдельным событием (ничего не теряем)
    for fid, f in by_id.items():
        if fid in placed or f["event_id"]:
            if f["event_id"]:
                touched.add(f["event_id"])
            continue
        eid = conn.execute(
            """INSERT INTO day_events (day, started_at, ended_at, title, kind, summary)
               VALUES (%s, %s, %s, %s, 'другое', %s) RETURNING id""",
            (day, f["started_at"], f["last_activity"], crypto.seal(f["title"] or "…"), crypto.seal(f["summary"]))
        ).fetchone()["id"]
        conn.execute("UPDATE conversations SET event_id = %s WHERE id = %s", (eid, fid))
        touched.add(eid)
    for eid in touched:
        refresh(conn, eid)
    conn.execute("DELETE FROM day_events e WHERE e.day = %s AND NOT EXISTS (SELECT 1 FROM conversations c "
                 "WHERE c.event_id = e.id AND c.trashed_at IS NULL)", (day,))
    if final:
        conn.execute("UPDATE day_events SET status = 'final' WHERE day = %s", (day,))
    log.info("events %s: %d touched, %d to trash", day, len(touched), trashed)
    return {"events": len(touched), "junk": trashed}


def refresh(conn, eid: int):
    """Время, люди, темы и фото события — по его фрагментам."""
    conn.execute(
        """UPDATE day_events e SET
               started_at = x.s, ended_at = x.e, topics = x.topics, people = x.people, photo_id = x.photo,
               updated_at = now()
           FROM (SELECT min(c.started_at) AS s, max(c.last_activity) AS e,
                        coalesce((SELECT array_agg(DISTINCT t) FROM conversations c2, unnest(c2.topics) t
                                  WHERE c2.event_id = %(id)s AND c2.trashed_at IS NULL), '{}') AS topics,
                        coalesce((SELECT array_agg(DISTINCT s.person_id) FROM segments s
                                  JOIN conversations c3 ON c3.id = s.conversation_id
                                  JOIN people p ON p.id = s.person_id
                                  WHERE c3.event_id = %(id)s AND c3.trashed_at IS NULL AND NOT p.ignored
                                    AND coalesce(p.kind, 'person') = 'person'), '{}') AS people,
                        (SELECT ph.id FROM photos ph WHERE ph.status IN ('analyzed', 'done')
                           AND ph.taken_at BETWEEN min(c.started_at) - interval '15 minutes'
                                               AND max(c.last_activity) + interval '15 minutes'
                         ORDER BY ph.taken_at LIMIT 1) AS photo
                 FROM conversations c WHERE c.event_id = %(id)s AND c.trashed_at IS NULL) x
           WHERE e.id = %(id)s AND x.s IS NOT NULL""", {"id": eid})


def review_tasks(conn, day: date) -> dict:
    """Ночная перепроверка задач дня: обсуждения уходят в «Предложенные» или убираются, дубли — тоже."""
    start, end = diary.day_bounds(day)
    rows = conn.execute(
        """SELECT id, text, evidence, kind, status FROM tasks
           WHERE created_at >= %s AND created_at < %s AND source = 'conversation' AND reviewed_at IS NULL
             AND status IN ('open', 'suggested') AND exported_at IS NULL
           ORDER BY id LIMIT 60""", (start, end)).fetchall()
    if not rows or not llm.configured():
        return {"reviewed": 0}
    text = "\n".join(f"{r['id']}: {r['text']} | доказательство: {r['evidence'] or '—'}" for r in rows)
    result = llm.chat_json(conn, "summary", REVIEW_SYSTEM, text, usage="review", fast=True)
    ids = {r["id"] for r in rows}
    changed = 0
    for d in result.get("decisions") or []:
        tid = _int(d.get("id")) if isinstance(d, dict) else None
        if tid not in ids:
            continue
        verdict = str(d.get("verdict") or "keep").lower()
        same = _int(d.get("same_as"))
        if same in ids and same != tid:
            verdict = "drop"
        status = {"drop": "dismissed", "suggest": "suggested"}.get(verdict)
        if status:
            conn.execute("UPDATE tasks SET status = %s, updated_at = now() WHERE id = %s AND status <> 'done'",
                         (status, tid))
            changed += 1
    conn.execute("UPDATE tasks SET reviewed_at = now() WHERE id = ANY(%s)", (list(ids),))
    return {"reviewed": len(ids), "changed": changed}


def _get(conn, key: str) -> str | None:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (key,)).fetchone()
    return row["value"] if row else None


def _set(conn, key: str, value: str):
    conn.execute(
        """INSERT INTO settings (key, value) VALUES (%s, %s)
           ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", (key, value))


def final_done(conn, day: date) -> bool:
    return _get(conn, "events.final_day") is not None and _get(conn, "events.final_day") >= day.isoformat()


def tick(conn):
    """Раз в 15 минут: собрать «отлежавшиеся» фрагменты в события; ночью — финальный разбор вчера; уборка корзины."""
    now = datetime.now(timezone.utc)
    last = _get(conn, "events.last_run")
    if last and now - datetime.fromisoformat(last) < timedelta(minutes=15):
        return
    _set(conn, "events.last_run", now.isoformat())
    days = conn.execute(
        """SELECT DISTINCT (started_at AT TIME ZONE %s)::date AS day FROM conversations
           WHERE status = 'done' AND trashed_at IS NULL AND event_id IS NULL
             AND last_activity < now() - %s * interval '1 minute' AND started_at > now() - interval '3 days'
           ORDER BY 1 LIMIT 3""", (diary.config.TZ, GROUP_AFTER_MIN)).fetchall()
    for d in days:
        try:
            group_day(conn, d["day"])
        except Exception:  # noqa: BLE001
            log.exception("events for %s failed", d["day"])
    local = now.astimezone(diary.TZ)
    yesterday = local.date() - timedelta(days=1)
    if local.hour >= 3 and not final_done(conn, yesterday):
        try:
            group_day(conn, yesterday, final=True)
            review_tasks(conn, yesterday)
        except Exception:  # noqa: BLE001
            log.exception("night pass for %s failed", yesterday)
        _set(conn, "events.final_day", yesterday.isoformat())
        # сводку вчерашнего дня — заново, по событиям
        conn.execute(
            """INSERT INTO diary_days (day, status) VALUES (%s, 'pending')
               ON CONFLICT (day) DO UPDATE SET status = 'pending'""", (yesterday,))
    conn.execute("DELETE FROM conversations WHERE trashed_at < now() - %s * interval '1 day'", (TRASH_DAYS,))

def vividness(e: dict) -> float:
    """Яркость воспоминания для карты: оценка модели (или 0.3), плюс фото, люди, факты «запомни»,
    договорённости и длительность. 0..1."""
    v = e.get("vivid")
    score = float(v) if v is not None else 0.3
    if e.get("photo_id"):
        score += 0.12
    score += min(0.2, 0.08 * len(e.get("people") or []))
    if e.get("memories"):
        score += 0.12
    if e.get("tasks"):
        score += 0.06
    score += min(0.12, (e.get("duration_sec") or 0) / 7200 * 0.12)
    return round(max(0.05, min(1.0, score)), 3)
