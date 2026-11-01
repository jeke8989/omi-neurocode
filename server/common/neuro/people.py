"""Люди в разговорах: подписи говорящих, подсказки имён, экран «Люди» и «Созвездие».

Голосовая часть (отпечатки, узнавание) — в voices.py; здесь — то, что видит пользователь и ассистент.
Все функции синхронные (обычное соединение psycopg); gateway вызывает их в потоке.
"""
from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta, timezone

from . import crypto, voices

log = logging.getLogger("neuro.people")

# Фраза вместе с тем, кто её сказал (для расшифровки в подсказках модели)
SEGMENT_SELECT = """
SELECT s.id, s.started_at, s.ended_at, s.speaker, s.text, s.conversation_id, s.person_id, s.voice_score,
       s.junk, s.cleaned, s.raw_text,
       p.name AS person_name, p.is_owner AS person_owner, p.number AS person_number, p.ignored AS person_ignored,
       p.kind AS person_kind
FROM segments s LEFT JOIN people p ON p.id = s.person_id
"""

OWNER_LABEL = "Я"  # пользователь без имени; с именем — подписан именем (не «пользователь»)
VOICE_LABEL = "Голос"


def proper(name: str | None) -> str | None:
    """Имена — всегда с большой буквы: «папа» -> «Папа», «иван петров» -> «Иван Петров»."""
    if not name:
        return name
    return " ".join(w[:1].upper() + w[1:] for w in str(name).split(" "))


def capitalize_names_once(conn) -> int:
    """Один раз: уже записанные имена людей и собеседников в обещаниях — с большой буквы."""
    if conn.execute("SELECT 1 FROM settings WHERE key = 'people.names_proper_v1'").fetchone():
        return 0
    n = 0
    for r in conn.execute("SELECT id, name FROM people WHERE name IS NOT NULL").fetchall():
        if r["name"] and proper(r["name"]) != r["name"]:
            conn.execute("UPDATE people SET name = %s WHERE id = %s", (crypto.seal_det(proper(r["name"])), r["id"]))
            n += 1
    for r in conn.execute("SELECT id, assignee, counterpart FROM tasks WHERE assignee IS NOT NULL OR counterpart IS NOT NULL").fetchall():
        a, cp = proper(r["assignee"]), proper(r["counterpart"])
        if a != r["assignee"] or cp != r["counterpart"]:
            conn.execute("UPDATE tasks SET assignee = %s, counterpart = %s WHERE id = %s",
                         (crypto.seal(a), crypto.seal(cp), r["id"]))
            n += 1
    conn.execute("INSERT INTO settings (key, value) VALUES ('people.names_proper_v1', '1') ON CONFLICT DO NOTHING")
    return n


def sure(score) -> bool:
    return score is not None and float(score) >= voices.thresholds().match


def speaker_label(s: dict) -> str:
    """Подпись говорящего в расшифровке для модели. «Анна?» — узнана неуверенно."""
    if s.get("person_id"):
        if s.get("person_owner"):
            base = s.get("person_name") or OWNER_LABEL
        else:
            base = s.get("person_name") or f"{VOICE_LABEL} {s.get('person_number')}"
        return base if sure(s.get("voice_score")) else base + "?"
    if s.get("speaker") is not None:
        return f"Спикер {s['speaker'] + 1}"
    return "Неизвестный"


def labels_map(segs: list[dict]) -> dict[str, int]:
    """Подпись -> id человека (для разбора ответа модели: исполнители задач, имена голосов)."""
    out: dict[str, int] = {}
    for s in segs:
        if s.get("person_id"):
            out[speaker_label(s).rstrip("?").lower()] = s["person_id"]
            if s.get("person_owner"):
                for word in ("я", "владелец", "пользователь", "me", "owner"):
                    out[word] = s["person_id"]
                if s.get("person_name"):
                    out[s["person_name"].lower()] = s["person_id"]
    return out


def by_name(conn, name: str) -> list[int]:
    """Люди с таким именем (без учёта регистра). Имена в базе зашифрованы — сравниваем здесь, а не в SQL."""
    key = " ".join((name or "").split()).lower()
    if not key:
        return []
    return [r["id"] for r in conn.execute("SELECT id, name FROM people WHERE name IS NOT NULL ORDER BY id").fetchall()
            if " ".join((r["name"] or "").split()).lower() == key]


def resolve(conn, who: str | None, labels: dict[str, int]) -> int | None:
    """Кто это: подпись из расшифровки, имя известного человека или «я/пользователь»."""
    if not who:
        return None
    key = who.strip().rstrip("?").lower()
    if key in labels:
        return labels[key]
    if key in ("я", "владелец", "пользователь", "me", "owner", "user", "ben", "yo"):
        row = conn.execute("SELECT id FROM people WHERE is_owner").fetchone()
        return row["id"] if row else None
    m = re.fullmatch(r"(?:голос|voice|ses|voz)\s*(\d+)", key)
    if m:
        row = conn.execute("SELECT id FROM people WHERE number = %s", (int(m.group(1)),)).fetchone()
        return row["id"] if row else None
    rows = by_name(conn, key)
    return rows[0] if len(rows) == 1 else None


# ---------------------------------------------------------------- итоги разговора
SUMMARY_PEOPLE_RULES = """Говорящие подписаны так: пользователь (носит кулон) — своим именем или «Я», если имени нет;
имя — человек, узнанный по голосу; «Голос N» — незнакомый голос; знак «?» — узнан неуверенно; «Спикер N» — голос без отпечатка.
Кулон слышит всё вокруг: рекламу, объявления, телевизор, радио, видео, песни и чужие разговоры рядом. Итог, задачи
и факты — только из речи людей, которые участвуют в разговоре с пользователем; фон не пересказывай.
В задачах исполнителя (assignee) указывай подписью из расшифровки или именем, если оно звучит в разговоре.
В "speakers" — для подписей «Голос N»:
 kind: "person" — участник разговора; "media" — реклама, объявление, диктор, телевизор, радио, видео, песня;
 "crowd" — посторонние рядом, не разговаривают с пользователем;
 name — имя участника, если оно ясно следует из разговора; sure = true, только если к нему прямо обратились
 по имени или он представился (цитата в evidence). Сомневаешься в имени — не пиши. Нечего сказать — пустой список."""

SUMMARY_PEOPLE_FIELDS = (
    '"topics": ["2–4 темы разговора, по 1–3 слова, с маленькой буквы"],\n'
    ' "speakers": [{"voice": "Голос N", "kind": "person | media | crowd", "name": "имя или пусто", "sure": false,'
    ' "evidence": "короткая цитата, откуда это ясно"}]'
)
KINDS = ("media", "crowd")


def _kind_by_user(conn, pid: int) -> bool:
    """Пользователь сам сказал «это человек» — модель больше не прячет этот голос."""
    row = conn.execute("SELECT kind_auto, kind FROM people WHERE id = %s", (pid,)).fetchone()
    return bool(row and row["kind"] == "person")


def clean_topics(raw) -> list[str]:
    if not isinstance(raw, list):
        return []
    out: list[str] = []
    for t in raw:
        t = " ".join(str(t or "").split()).strip(" .,;:#\"'«»").lower()[:40]
        if t and t not in out:
            out.append(t)
    return out[:4]


def apply_summary(conn, conv_id: int, segs: list[dict], result: dict, tasks: list[dict]) -> list[str]:
    """Темы, подсказки имён и исполнители задач из ответа модели. Возвращает темы."""
    topics = clean_topics(result.get("topics"))
    labels = labels_map(segs)
    for t in tasks:
        t["person_id"] = resolve(conn, t.get("assignee"), labels)
        if t["person_id"] and t.get("assignee"):
            row = conn.execute("SELECT name, is_owner FROM people WHERE id = %s", (t["person_id"],)).fetchone()
            if row and row["name"]:
                t["assignee"] = row["name"]
    for sp in result.get("speakers") or []:
        if not isinstance(sp, dict):
            continue
        name = proper(" ".join(str(sp.get("name") or "").split()).strip(" .,«»\"'")[:60])
        pid = resolve(conn, str(sp.get("voice") or ""), labels)
        if not pid:
            continue
        person = conn.execute("SELECT name, is_owner, kind, kind_auto FROM people WHERE id = %s", (pid,)).fetchone()
        if not person or person["name"]:
            continue
        kind = str(sp.get("kind") or "person").strip().lower()
        if kind in KINDS and not person["is_owner"] and (person["kind"] is None and not _kind_by_user(conn, pid)):
            # реклама, телевизор, чужие разговоры: голос прячется из людей, расшифровки и итогов
            conn.execute("UPDATE people SET kind = %s, kind_auto = true, updated_at = now() WHERE id = %s", (kind, pid))
            log.info("conversation %s: voice of person %s is background (%s)", conv_id, pid, kind)
            continue
        if kind == "person" and person["kind"] and person["kind_auto"]:
            conn.execute("UPDATE people SET kind = NULL, kind_auto = false WHERE id = %s", (pid,))  # всё-таки человек
        if not name or len(name) < 2:
            continue
        evidence = str(sp.get("evidence") or "").strip()[:300] or None
        if sp.get("sure") in (True, "true", 1) and evidence and not by_name(conn, name) and not person["is_owner"]:
            # прямо обратились по имени или представился — подписываем сразу (переименовать можно в карточке)
            conn.execute("UPDATE people SET name = %s, updated_at = now() WHERE id = %s", (crypto.seal_det(name), pid))
            conn.execute(
                """INSERT INTO person_suggestions (person_id, name, conversation_id, evidence, status)
                   VALUES (%s, %s, %s, %s, 'accepted')""", (pid, crypto.seal_det(name), conv_id, crypto.seal(evidence)))
            log.info("conversation %s: voice of person %s named from context", conv_id, pid)
            continue
        seen = [r for r in conn.execute(
            """SELECT name FROM person_suggestions WHERE person_id = %s AND status IN ('pending', 'rejected')""",
            (pid,)).fetchall() if (r["name"] or "").lower() == name.lower()]
        if seen:
            continue
        conn.execute(
            """INSERT INTO person_suggestions (person_id, name, conversation_id, evidence)
               VALUES (%s, %s, %s, %s)""",
            (pid, crypto.seal_det(name), conv_id, crypto.seal(str(sp.get("evidence") or "").strip()[:300] or None)))
        log.info("conversation %s: voice of person %s may be %s", conv_id, pid, name)
    return topics


# ---------------------------------------------------------------- для приложения
PERSON_COLUMNS = """p.id, p.name, p.is_owner, p.ignored, p.kind, p.number, p.samples, p.speech_sec, p.note,
       p.first_seen, p.last_seen,
       (SELECT count(DISTINCT s.conversation_id) FROM segments s WHERE s.person_id = p.id) AS conversations"""

# Голос, услышанный только в одном разговоре, — «Собеседник»: не показываем в «Людях», «Созвездии» и
# подсказках, пока он не встретится снова (или пока пользователь сам его не назовёт).
RECURRING = 2
VISIBLE_SQL = f"""(p.name IS NOT NULL OR p.is_owner OR (coalesce(p.kind, 'person') = 'person' AND
    (SELECT count(DISTINCT s.conversation_id) FROM segments s WHERE s.person_id = p.id) >= {RECURRING}))"""


def transient(p: dict) -> bool:
    return not p.get("name") and not p.get("is_owner") and (p.get("conversations") or 0) < RECURRING


def _with_sample(p: dict | None) -> dict | None:
    if p is not None:
        p["has_sample"] = voices.sample_path(p["id"]).exists()
    return p


def settings(conn) -> dict:
    return {"enabled": voices.enabled(conn), "others": voices.others_enabled(conn)}


def set_settings(conn, enabled: bool | None = None, others: bool | None = None) -> dict:
    for key, value in (("voices.enabled", enabled), ("voices.others", others)):
        if value is None:
            continue
        conn.execute(
            """INSERT INTO settings (key, value) VALUES (%s, %s)
               ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
            (key, "1" if value else "0"))
    if others is False and voices.has_owner(conn):
        voices.purge_unnamed(conn)
    return settings(conn)


def list_people(conn) -> dict:
    people = conn.execute(
        f"""SELECT {PERSON_COLUMNS} FROM people p WHERE {VISIBLE_SQL}
            ORDER BY p.is_owner DESC, p.ignored, (p.name IS NULL), p.last_seen DESC NULLS LAST""").fetchall()
    for p in people:
        _with_sample(p)
    suggestions = conn.execute(
        f"""SELECT g.id, g.person_id, coalesce(t.name, g.name) AS name, g.evidence, g.conversation_id,
                  c.title AS conversation_title, p.number, p.is_owner, g.target_id, t.is_owner AS target_owner
           FROM person_suggestions g JOIN people p ON p.id = g.person_id
           LEFT JOIN people t ON t.id = g.target_id
           LEFT JOIN conversations c ON c.id = g.conversation_id
           WHERE g.status = 'pending' AND p.name IS NULL AND NOT p.is_owner AND coalesce(p.kind, 'person') = 'person'
             AND (SELECT count(DISTINCT s.conversation_id) FROM segments s WHERE s.person_id = p.id) >= {RECURRING}
           ORDER BY g.id DESC LIMIT 20""").fetchall()
    owner = voices.suggest_owner(conn)
    return {"people": people, "suggestions": suggestions, "owner_candidate": _with_sample(owner),
            "settings": settings(conn)}


def person_detail(conn, pid: int) -> dict | None:
    p = conn.execute(f"SELECT {PERSON_COLUMNS} FROM people p WHERE p.id = %s", (pid,)).fetchone()
    if p is None:
        return None
    _with_sample(p)
    p["recent"] = conn.execute(
        """SELECT c.id, c.title, c.emoji, c.started_at, c.topics,
                  greatest(0, extract(epoch FROM c.last_activity - c.started_at))::int AS duration_sec,
                  sum(greatest(0, extract(epoch FROM s.ended_at - s.started_at)))::int AS speech_sec
           FROM segments s JOIN conversations c ON c.id = s.conversation_id
           WHERE s.person_id = %s
           GROUP BY c.id ORDER BY c.started_at DESC LIMIT 30""", (pid,)).fetchall()
    topics: dict[str, int] = {}
    for c in p["recent"]:
        for t in c.get("topics") or []:
            topics[t] = topics.get(t, 0) + 1
    p["topics"] = [t for t, _ in sorted(topics.items(), key=lambda kv: -kv[1])[:8]]
    p["tasks"] = conn.execute(
        """SELECT id, text, due, status, conversation_id FROM tasks
           WHERE person_id = %s ORDER BY status, created_at DESC LIMIT 30""", (pid,)).fetchall()
    p["quotes"] = [q for q in conn.execute(
        """SELECT id, text, started_at, conversation_id FROM segments
           WHERE person_id = %s AND NOT junk ORDER BY started_at DESC LIMIT 40""", (pid,)).fetchall()
        if len(q["text"] or "") >= 20][:5]
    p["together"] = conn.execute(
        """SELECT o.id, o.name, o.number, o.is_owner, count(DISTINCT s2.conversation_id) AS conversations
           FROM segments s1 JOIN segments s2 ON s2.conversation_id = s1.conversation_id
           JOIN people o ON o.id = s2.person_id
           WHERE s1.person_id = %s AND s2.person_id <> %s AND NOT o.ignored AND coalesce(o.kind, 'person') = 'person'
             AND (o.name IS NOT NULL OR o.is_owner OR (SELECT count(DISTINCT x.conversation_id) FROM segments x
                                                      WHERE x.person_id = o.id) >= 2)
           GROUP BY o.id ORDER BY conversations DESC LIMIT 8""", (pid, pid)).fetchall()
    p["suggestions"] = conn.execute(
        "SELECT id, name, evidence FROM person_suggestions WHERE person_id = %s AND status = 'pending'",
        (pid,)).fetchall()
    return p


def update_person(conn, pid: int, body: dict) -> dict | None:
    """Имя, «это я», «не учитывать», заметка. Имя подтверждает отпечатки голоса."""
    if conn.execute("SELECT 1 FROM people WHERE id = %s", (pid,)).fetchone() is None:
        return None
    same_name = None
    with conn.transaction():
        if "name" in body:
            name = proper(" ".join(str(body.get("name") or "").split())[:60]) or None
            conn.execute("UPDATE people SET name = %s, updated_at = now() WHERE id = %s",
                         (crypto.seal_det(name), pid))
            if name:
                voices.confirm_person(conn, pid)
                for g in conn.execute("SELECT id, name FROM person_suggestions WHERE person_id = %s AND status = 'pending'",
                                      (pid,)).fetchall():
                    conn.execute("UPDATE person_suggestions SET status = %s WHERE id = %s",
                                 ("accepted" if (g["name"] or "").lower() == name.lower() else "rejected", g["id"]))
                other = [i for i in by_name(conn, name) if i != pid]
                same_name = other[0] if other else None
        if body.get("is_owner") is True:
            conn.execute("UPDATE people SET is_owner = false WHERE is_owner AND id <> %s", (pid,))
            conn.execute("UPDATE people SET is_owner = true, ignored = false, updated_at = now() WHERE id = %s",
                         (pid,))
            voices.confirm_person(conn, pid)
        elif body.get("is_owner") is False:
            conn.execute("UPDATE people SET is_owner = false, updated_at = now() WHERE id = %s", (pid,))
        if "ignored" in body:
            conn.execute("UPDATE people SET ignored = %s, updated_at = now() WHERE id = %s",
                         (bool(body["ignored"]), pid))
            if not body["ignored"]:  # «учитывать» — значит, это человек, а не фон
                conn.execute("UPDATE people SET kind = 'person', kind_auto = false WHERE id = %s", (pid,))
        if "kind" in body and body["kind"] in (None, "person", "media", "crowd"):
            conn.execute("UPDATE people SET kind = %s, kind_auto = false, updated_at = now() WHERE id = %s",
                         (body["kind"], pid))
        if "note" in body:
            conn.execute("UPDATE people SET note = %s WHERE id = %s",
                         (crypto.seal(str(body.get("note") or "").strip()[:500] or None), pid))
    if body.get("is_owner") is True and not voices.others_enabled(conn):
        voices.purge_unnamed(conn)
    p = person_detail(conn, pid)
    if p is not None:
        p["same_name"] = same_name
    return p


def create_named(conn, name: str) -> dict:
    """Новый человек по имени (без голоса): «Кто это?» -> «Новый человек»."""
    number = conn.execute("SELECT coalesce(max(number), 0) + 1 AS n FROM people").fetchone()["n"]
    return conn.execute(
        """INSERT INTO people (name, number, first_seen, last_seen) VALUES (%s, %s, now(), now())
           RETURNING id, name, number, is_owner""", (crypto.seal_det(proper(name.strip()[:60])), number)).fetchone()


def assign_segment(conn, segment_id: int, person_id: int | None = None, name: str | None = None) -> dict | None:
    """«Кто это?» для одной фразы: человек из списка, новый по имени или «не знаю» (снять подпись)."""
    if conn.execute("SELECT 1 FROM segments WHERE id = %s", (segment_id,)).fetchone() is None:
        return None
    if person_id is None and name:
        existing = by_name(conn, name)
        person_id = existing[0] if existing else create_named(conn, name)["id"]
    if person_id is None:
        old = conn.execute("SELECT person_id FROM segments WHERE id = %s", (segment_id,)).fetchone()
        conn.execute("UPDATE segments SET person_id = NULL, voice_score = NULL WHERE id = %s", (segment_id,))
        conn.execute("DELETE FROM voiceprints WHERE segment_id = %s", (segment_id,))
        if old and old["person_id"]:
            voices.recompute_centroid(conn, old["person_id"])
        return {"segment_id": segment_id, "person_id": None}
    if conn.execute("SELECT 1 FROM people WHERE id = %s", (person_id,)).fetchone() is None:
        return None
    voices.assign_segment(conn, segment_id, person_id)
    return {"segment_id": segment_id, "person_id": person_id}


def answer_suggestion(conn, sid: int, accept: bool) -> dict | None:
    g = conn.execute("SELECT id, person_id, name, target_id FROM person_suggestions WHERE id = %s", (sid,)).fetchone()
    if g is None:
        return None
    if not accept:
        conn.execute("UPDATE person_suggestions SET status = 'rejected' WHERE id = %s", (sid,))
        return {"id": sid, "status": "rejected"}
    if g["target_id"]:
        # «Голос 7 похож на Анну» — да: это она, голоса объединяются
        voices.merge_people(conn, g["person_id"], g["target_id"])
        conn.execute("UPDATE person_suggestions SET status = 'accepted' WHERE id = %s", (sid,))
        return {"id": sid, "status": "accepted", "person_id": g["target_id"], "merged": True}
    same = [i for i in by_name(conn, g["name"] or "") if i != g["person_id"]]
    if same:
        # такой человек уже есть — значит, это он же другим голосовым отпечатком: объединяем
        voices.merge_people(conn, g["person_id"], same[0])
        conn.execute("UPDATE person_suggestions SET status = 'accepted' WHERE id = %s", (sid,))
        return {"id": sid, "status": "accepted", "person_id": same[0], "merged": True}
    update_person(conn, g["person_id"], {"name": g["name"]})
    return {"id": sid, "status": "accepted", "person_id": g["person_id"], "merged": False}


def participants_sql(alias: str = "c") -> str:
    """Подзапрос: кто говорил в разговоре (до 6 человек, кто больше говорил — первым)."""
    return f"""coalesce((SELECT json_agg(json_build_object('id', x.id, 'name', x.name, 'number', x.number,
                                                     'is_owner', x.is_owner) ORDER BY x.sec DESC)
               FROM (SELECT p.id, p.name, p.number, p.is_owner,
                            sum(extract(epoch FROM s.ended_at - s.started_at)) AS sec
                     FROM segments s JOIN people p ON p.id = s.person_id
                     WHERE s.conversation_id = {alias}.id AND NOT p.ignored AND {VISIBLE_SQL}
                     GROUP BY p.id ORDER BY sec DESC LIMIT 6) x), '[]'::json)"""


def event_people_sql(alias: str = "e") -> str:
    """Люди события (видимые: названные, пользователь или встречавшиеся не один раз)."""
    return f"""coalesce((SELECT json_agg(json_build_object('id', p.id, 'name', p.name, 'number', p.number,
                                                     'is_owner', p.is_owner))
               FROM people p WHERE p.id = ANY({alias}.people) AND NOT p.ignored AND {VISIBLE_SQL}), '[]'::json)"""


def graph(conn, days: int = 90, tz: str = "UTC") -> dict:
    """Данные для «Созвездия»: люди, темы, разговоры со связями и фото. Раскладку строит приложение."""
    since = datetime.now(timezone.utc) - timedelta(days=days)
    convs = conn.execute(
        f"""SELECT c.id, c.title, c.emoji, c.started_at, c.last_activity AS ended_at, c.topics,
                   greatest(0, extract(epoch FROM c.last_activity - c.started_at))::int AS duration_sec,
                   coalesce((SELECT array_agg(DISTINCT s.person_id) FROM segments s JOIN people p ON p.id = s.person_id
                             WHERE s.conversation_id = c.id AND NOT p.ignored AND {VISIBLE_SQL}), '{{}}') AS people,
                   (SELECT ph.id FROM photos ph
                    WHERE ph.taken_at BETWEEN c.started_at - interval '15 minutes'
                                          AND c.last_activity + interval '15 minutes'
                      AND ph.status IN ('analyzed', 'done')
                    ORDER BY ph.taken_at LIMIT 1) AS photo_id
            FROM conversations c
            WHERE c.started_at > %s AND c.title IS NOT NULL AND c.trashed_at IS NULL
            ORDER BY c.started_at LIMIT 400""", (since,)).fetchall()
    ids = sorted({pid for c in convs for pid in (c["people"] or [])})
    people = conn.execute(
        f"SELECT {PERSON_COLUMNS} FROM people p WHERE p.id = ANY(%s) OR p.is_owner", (ids,)).fetchall() \
        if ids else conn.execute(f"SELECT {PERSON_COLUMNS} FROM people p WHERE p.is_owner").fetchall()
    topics: dict[str, dict] = {}
    for c in convs:
        c["topics"] = c.get("topics") or []
        c["people"] = c.get("people") or []
        for t in c["topics"]:
            item = topics.setdefault(t, {"name": t, "count": 0, "first_at": c["started_at"]})
            item["count"] += 1
            item["last_at"] = c["started_at"]
    events = conn.execute(
        f"""SELECT e.id, e.day, e.title, e.emoji, e.kind, e.started_at, e.ended_at, e.topics, e.photo_id, e.vivid,
                   e.summary,
                   (SELECT count(*) FROM memories m JOIN conversations c ON c.id = m.conversation_id AND m.archived_at IS NULL
                     WHERE c.event_id = e.id) AS memories,
                   (SELECT count(*) FROM tasks t JOIN conversations c ON c.id = t.conversation_id
                     WHERE c.event_id = e.id AND t.status <> 'dismissed') AS tasks,
                   greatest(0, extract(epoch FROM e.ended_at - e.started_at))::int AS duration_sec,
                   coalesce((SELECT array_agg(p.id) FROM people p WHERE p.id = ANY(e.people) AND NOT p.ignored
                             AND {VISIBLE_SQL}), '{{}}') AS people,
                   (SELECT min(c.id) FROM conversations c WHERE c.event_id = e.id AND c.trashed_at IS NULL) AS first_conversation,
                   (SELECT count(*) FROM conversations c WHERE c.event_id = e.id AND c.trashed_at IS NULL) AS fragments
            FROM day_events e WHERE e.started_at > %s ORDER BY e.started_at LIMIT 400""", (since,)).fetchall()
    from .events import vividness  # noqa: PLC0415 — events импортирует diary, не тянем его при загрузке
    for e in events:
        e["vivid"] = vividness(e)
    return {"people": people, "topics": sorted(topics.values(), key=lambda t: -t["count"])[:60],
            "conversations": convs, "events": events, "since": since}


# ---------------------------------------------------------------- для агента
def people_overview(conn) -> str:
    rows = conn.execute(
        f"""SELECT {PERSON_COLUMNS} FROM people p WHERE NOT p.ignored AND {VISIBLE_SQL}
            ORDER BY p.is_owner DESC, (p.name IS NULL), p.last_seen DESC NULLS LAST LIMIT 40""").fetchall()
    if not rows:
        return "Кулон пока не различает голоса (никого не запомнил)."
    out = []
    for r in rows:
        who = f"{r['name'] or 'Я'} — сам пользователь" if r["is_owner"] else (r["name"] or f"Голос {r['number']}")
        last = r["last_seen"].strftime("%d.%m.%Y") if r["last_seen"] else "—"
        out.append(f"- {who}: разговоров {r['conversations']}, говорил(а) {int(r['speech_sec'] // 60)} мин, "
                   f"последний раз {last}" + (f"; заметка: {r['note']}" if r.get("note") else ""))
    return "Люди, которых кулон узнаёт по голосу:\n" + "\n".join(out)


def person_conversations(conn, who: str, days: int = 60) -> str:
    pid = resolve(conn, who, {})
    if pid is None:
        part = who.strip().lower()
        rows = [r["id"] for r in conn.execute("SELECT id, name FROM people WHERE name IS NOT NULL").fetchall()
                if part and part in (r["name"] or "").lower()]
        pid = rows[0] if len(rows) == 1 else None
    if pid is None:
        return f"Не знаю человека «{who}» по голосу. Посмотри список людей инструментом people."
    since = datetime.now(timezone.utc) - timedelta(days=max(1, min(days, 365)))
    convs = conn.execute(
        """SELECT DISTINCT c.id, c.started_at, c.title, c.summary FROM conversations c
           JOIN segments s ON s.conversation_id = c.id
           WHERE s.person_id = %s AND c.started_at > %s ORDER BY c.started_at DESC LIMIT 15""",
        (pid, since)).fetchall()
    tasks = conn.execute("SELECT text, due, status FROM tasks WHERE person_id = %s ORDER BY created_at DESC LIMIT 15",
                         (pid,)).fetchall()
    out = [f"[{c['started_at'].strftime('%d.%m %H:%M')}] {c['title'] or '…'}: {c['summary'] or ''}" for c in convs]
    if tasks:
        out.append("Задачи с этим человеком:")
        out += [f"- [{t['status']}] {t['text']}" + (f" (срок: {t['due']})" if t["due"] else "") for t in tasks]
    return "\n".join(out) or "Разговоров с этим человеком за это время нет."
