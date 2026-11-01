"""Знакомство: как зовут пользователя, чем занимается, какие цели, и его голос по прочитанным фразам.

Это контекст для всех текстов ИИ: итоги разговоров, события, дневник и ассистент понимают, кто говорит и что
для человека важно, и называют его по имени (не «владелец»).

Голос: в онбординге пользователь читает вслух несколько фраз, приложение присылает окно времени [from, to].
Кулон всё это время пишет звук как обычно; когда окно расшифровано, голос, который говорил в нём больше всех,
становится голосом пользователя (с его именем).
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta, timezone

from . import crypto

log = logging.getLogger("neuro.profile")

KEYS = {"name": "profile.name", "about": "profile.about", "goals": "profile.goals"}
VOICE_KEY = "profile.voice"
WAIT_MAX = timedelta(hours=3)  # звук окна мог лежать в кулоне без связи — ждём, но не вечно


def _get(conn, key: str) -> str | None:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (key,)).fetchone()
    return row["value"] if row and row["value"] else None


def _set(conn, key: str, value: str | None, seal: bool = True):
    if value:
        conn.execute("""INSERT INTO settings (key, value) VALUES (%s, %s)
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                     (key, crypto.seal(value) if seal else value))
    else:
        conn.execute("DELETE FROM settings WHERE key = %s", (key,))


def owner(conn) -> dict | None:
    return conn.execute("SELECT id, name FROM people WHERE is_owner").fetchone()


def get(conn) -> dict:
    goals = _get(conn, KEYS["goals"])
    try:
        goals = json.loads(goals) if goals else []
    except ValueError:
        goals = [goals]
    me = owner(conn)
    voice = _voice_job(conn)
    return {
        "name": _get(conn, KEYS["name"]) or (me or {}).get("name"),
        "about": _get(conn, KEYS["about"]),
        "goals": [str(g) for g in goals if str(g).strip()],
        # none — голоса ещё нет; waiting — фразы прочитаны, ждём расшифровку; done — голос узнан; failed — не услышали
        "voice": voice.get("status") if voice else ("done" if me else "none"),
    }


def save(conn, name: str | None = None, about: str | None = None, goals: list[str] | None = None) -> dict:
    if name is not None:
        name = " ".join(name.split())[:60]
        _set(conn, KEYS["name"], name)
        me = owner(conn)
        if me and name:  # свой голос уже узнан — подписываем его этим именем
            conn.execute("UPDATE people SET name = %s, updated_at = now() WHERE id = %s",
                         (crypto.seal_det(name), me["id"]))
    if about is not None:
        _set(conn, KEYS["about"], " ".join(about.split())[:600])
    if goals is not None:
        clean = [" ".join(str(g).split())[:120] for g in goals if str(g).strip()][:12]
        _set(conn, KEYS["goals"], json.dumps(clean, ensure_ascii=False) if clean else None)
    return get(conn)


def prompt(conn) -> str:
    """Кто пользователь — для промптов. Всегда с правилом: по имени или на «вы», не «владелец»."""
    p = get(conn)
    parts = []
    if p["name"]:
        parts.append(f"Пользователь (носит кулон) — {p['name']}; в расшифровках он подписан своим именем.")
    else:
        parts.append("Пользователь носит кулон; в расшифровках он подписан «Я».")
    if p["about"]:
        parts.append(f"Чем занимается: {p['about']}.")
    if p["goals"]:
        parts.append("Что для него важно: " + "; ".join(p["goals"]) + ".")
    parts.append(f"О нём пиши по имени ({p['name']}) или на «вы»." if p["name"] else "О нём пиши на «вы».")
    parts.append("Слово «владелец» в текстах не используй.")
    return " ".join(parts)


# ---------------------------------------------------------------- голос по фразам
def _voice_job(conn) -> dict | None:
    raw = _get(conn, VOICE_KEY)
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def start_voice(conn, start: datetime, end: datetime) -> dict:
    if end <= start or end - start > timedelta(minutes=10):
        raise ValueError("окно фраз: от 1 секунды до 10 минут")
    job = {"from": start.isoformat(), "to": end.isoformat(), "status": "waiting",
           "created": datetime.now(timezone.utc).isoformat()}
    _set(conn, VOICE_KEY, json.dumps(job), seal=False)  # только время окна и статус
    return job


def voice_tick(conn) -> str | None:
    """Агент, раз в цикл: окно с фразами расшифровано — его главный голос становится голосом пользователя."""
    from . import voices  # noqa: PLC0415 — тяжёлый модуль, только когда есть задание

    job = _voice_job(conn)
    if not job or job.get("status") != "waiting":
        return None
    start, end = datetime.fromisoformat(job["from"]), datetime.fromisoformat(job["to"])
    expired = datetime.now(timezone.utc) - datetime.fromisoformat(job["created"]) > WAIT_MAX
    busy = conn.execute(
        """SELECT 1 FROM audio_chunks WHERE status IN ('pending', 'processing')
           AND started_at <= %s AND started_at + duration_ms * interval '1 millisecond' >= %s LIMIT 1""",
        (end, start)).fetchone()
    heard = conn.execute(
        """SELECT 1 FROM audio_chunks WHERE started_at <= %s
           AND started_at + duration_ms * interval '1 millisecond' >= %s LIMIT 1""", (end, start)).fetchone()
    if (busy or not heard) and not expired:
        return None  # звук ещё не дошёл или не расшифрован
    top = conn.execute(
        """SELECT person_id, sum(extract(epoch FROM least(ended_at, %s) - greatest(started_at, %s))) AS sec
           FROM segments WHERE person_id IS NOT NULL AND started_at < %s AND ended_at > %s
           GROUP BY person_id ORDER BY sec DESC LIMIT 1""", (end, start, end, start)).fetchone()
    if not top or (top["sec"] or 0) < 3:
        job["status"] = "failed"
        _set(conn, VOICE_KEY, json.dumps(job), seal=False)  # только время окна и статус
        log.info("profile voice: nothing heard in the window")
        return "failed"
    pid = top["person_id"]
    me = owner(conn)
    with conn.transaction():
        if me and me["id"] != pid:
            a = conn.execute("SELECT centroid FROM people WHERE id = %s", (pid,)).fetchone()
            b = conn.execute("SELECT centroid FROM people WHERE id = %s", (me["id"],)).fetchone()
            same = a and b and a["centroid"] and b["centroid"] and len(a["centroid"]) == len(b["centroid"]) \
                and voices.cosine(a["centroid"], b["centroid"]) >= voices.thresholds().maybe
            if same:  # тот же голос раздвоился — склеиваем
                voices.merge_people(conn, pid, me["id"])
                pid = me["id"]
            else:  # раньше «собой» был узнан чужой голос — исправляем
                conn.execute("UPDATE people SET is_owner = false WHERE id = %s", (me["id"],))
        name = _get(conn, KEYS["name"])
        conn.execute("UPDATE people SET is_owner = true, ignored = false, kind = 'person', kind_auto = false, "
                     "name = coalesce(%s, name), updated_at = now() WHERE id = %s",
                     (crypto.seal_det(name) if name else None, pid))
        voices.confirm_person(conn, pid)
        job["status"] = "done"
        _set(conn, VOICE_KEY, json.dumps(job), seal=False)  # только время окна и статус
    log.info("profile voice: person %s is the user", pid)
    return "done"
