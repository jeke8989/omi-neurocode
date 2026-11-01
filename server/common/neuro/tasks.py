"""Задачи из разговоров: только договорённости с участием пользователя, без дублей, с настоящим сроком.

Модель возвращает для каждой задачи вид (моя / жду от другого / напоминание), уверенность и дословную
фразу-доказательство. Сервер решает:
  * уверенность ≥ 0.7 и есть доказательство — сразу в «Задачи»;
  * 0.45–0.7 — в «Предложенные» (принять одним нажатием или смахнуть);
  * ниже — не задача (обсуждение, идея, «надо бы»).
Перед созданием модель видит открытые задачи и, если это та же самая, возвращает её номер — тогда
обновляется существующая задача, а не появляется дубль.
"""
from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from . import config, crypto

log = logging.getLogger("neuro.tasks")
TZ = ZoneInfo(config.TZ)

ACCEPT = 0.7     # сразу в задачи
SUGGEST = 0.45   # в «Предложенные»
WEEKDAYS = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]

SCHEMA = (
    '{"text": "конкретное действие, коротко", '
    '"kind": "mine — делает пользователь | waiting — пользователю должен сделать другой | reminder — пользователь попросил напомнить", '
    '"who": "кто делает: «Я» (сам пользователь) или подпись говорящего/имя", '
    '"with": "с кем договорённость: кому пользователь обещал (для mine) или кто обещал ему — имя или подпись, иначе null", '
    '"due": "срок словами, если назван, иначе null", '
    '"due_at": "срок как дата-время ISO 8601 (YYYY-MM-DDTHH:MM), если его можно вычислить, иначе null", '
    '"confidence": "0..1 — насколько это точно договорённость", '
    '"evidence": "дословная фраза из расшифровки, где договорились", '
    '"same_as": "номер открытой задачи, если это она же, иначе null", '
    '"target": "github, если это задача на разработку программы, иначе notion"}'
)

RULES = """Задачи — только договорённости о действии с участием пользователя кулона:
• пользователь сказал, что сделает («сделаю», «отправлю завтра»), или согласился на просьбу — kind=mine;
• другой человек пообещал сделать что-то для пользователя («пришлю договор до понедельника») — kind=waiting;
• пользователь попросил напомнить («напомни мне вечером…», «не дай забыть…») — kind=reminder.
НЕ задачи: обсуждение вариантов, идеи, «надо бы», «может быть», «посмотрим», рассказы о чужих делах,
вопросы без ответа, то, что уже сделано. Разговоры, где пользователь не участвует (телевизор, чужая беседа), — без задач.
Одно действие — одна задача: «посмотреть билеты» и «купить билеты» на одно событие — это одна задача
(итоговое действие). Если задача уже есть среди открытых — верни её номер в same_as, не создавай новую.
confidence: 0.9 — прямое обещание или согласие, 0.7 — ясная договорённость без деталей,
0.5 — похоже на договорённость, но не уверен, ниже — не задача. Без дословной фразы в evidence задачи нет.
due_at считай от даты разговора: «вечером» — 19:00 того же дня, «утром» — 09:00, «завтра» без времени —
10:00 завтра, день недели — ближайший такой день, 10:00. Срока нет — null."""


def context(conn, when: datetime) -> str:
    """Дата разговора и открытые задачи (чтобы модель не плодила дубли)."""
    local = when.astimezone(TZ)
    lines = [f"Разговор был: {WEEKDAYS[local.weekday()]}, {local.strftime('%Y-%m-%d %H:%M')} ({config.TZ})."]
    rows = conn.execute(
        """SELECT id, text, due FROM tasks WHERE status IN ('open', 'suggested')
           ORDER BY created_at DESC LIMIT 30""").fetchall()
    if rows:
        lines.append("Открытые задачи (номер: текст):")
        lines += [f"{r['id']}: {r['text']}" + (f" (срок: {r['due']})" if r["due"] else "") for r in rows]
    return "\n".join(lines)


def _num(v, default: float = 0.0) -> float:
    try:
        return max(0.0, min(1.0, float(v)))
    except (TypeError, ValueError):
        return default


def parse_due(v) -> datetime | None:
    if not v or str(v).strip().lower() in ("null", "none", ""):
        return None
    try:
        dt = datetime.fromisoformat(str(v).strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=TZ)
    return dt


GENERIC = re.compile(r"^(спикер|speaker|голос|voice|ses|voz|неизвестн|unknown|\?)", re.I)
OWNER_WORDS = ("владелец", "пользователь", "я", "owner", "user", "me", "ben", "yo")


def classify(conn, raw, labels: dict[str, int], owner_spoke: bool | None, source: str = "conversation") -> list[dict]:
    """Ответ модели -> задачи с решением: open | suggested (или отбрасываем)."""
    from .people import resolve  # noqa: PLC0415 — people импортирует голоса, тяжёлое не тянем зря

    if not isinstance(raw, list):
        return []
    owner = conn.execute("SELECT id, name FROM people WHERE is_owner").fetchone()
    out: list[dict] = []
    seen: set[str] = set()
    for t in raw:
        if isinstance(t, str):
            t = {"text": t, "confidence": 0.6}
        if not isinstance(t, dict):
            continue
        text = " ".join(str(t.get("text") or "").split())[:1000]
        if not text:
            continue
        key = re.sub(r"\W+", " ", text.lower()).strip()
        if key in seen:
            continue
        seen.add(key)
        conf = _num(t.get("confidence"), 0.6)
        evidence = " ".join(str(t.get("evidence") or "").split())[:300] or None
        if not evidence and source == "conversation":
            conf *= 0.6
        kind = str(t.get("kind") or "mine").lower()
        kind = kind if kind in ("mine", "waiting", "reminder") else "mine"
        who = " ".join(str(t.get("who") or t.get("assignee") or "").split()).strip(" ?")
        person_id, assignee = None, None
        if who and who.lower() in OWNER_WORDS or kind == "reminder":
            person_id = owner["id"] if owner else None
        elif who:
            person_id = resolve(conn, who, labels)
            if person_id and owner and person_id == owner["id"]:
                kind = "mine" if kind == "waiting" else kind
            elif person_id:
                row = conn.execute("SELECT name FROM people WHERE id = %s", (person_id,)).fetchone()
                assignee = row["name"] if row and row["name"] else None
            elif not GENERIC.match(who):
                assignee = who[:80]
            if person_id and owner and person_id != owner["id"] and kind == "mine":
                kind = "waiting"  # делает другой — для пользователя это «жду от…»
        if kind == "waiting" and not (assignee or person_id):
            conf = min(conf, 0.6)  # от кого ждём — непонятно
        # с кем договорённость: «вы обещали Ивану», «Иван обещал вам»
        counterpart = assignee if kind == "waiting" else None
        mate = " ".join(str(t.get("with") or "").split()).strip(" ?")
        if not counterpart and mate and mate.lower() not in OWNER_WORDS and mate.lower() not in ("null", "none"):
            pid = resolve(conn, mate, labels)
            if pid and not (owner and pid == owner["id"]):
                row = conn.execute("SELECT name FROM people WHERE id = %s", (pid,)).fetchone()
                counterpart = row["name"] if row and row["name"] else None
            elif not pid and not GENERIC.match(mate):
                counterpart = mate[:80]
        if owner_spoke is False:
            conf = min(conf, 0.65)  # пользователь в разговоре не говорил — только предложение
        if conf < SUGGEST:
            continue
        same = t.get("same_as")
        try:
            same = int(same) if same not in (None, "", "null") else None
        except (TypeError, ValueError):
            same = None
        out.append({
            "text": text, "kind": kind, "person_id": person_id, "assignee": assignee, "counterpart": counterpart,
            "due": (str(t.get("due")).strip()[:120] if t.get("due") not in (None, "", "null") else None),
            "due_at": parse_due(t.get("due_at")), "confidence": round(conf, 2), "evidence": evidence,
            "status": "open" if conf >= ACCEPT else "suggested", "same_as": same,
            "target": "github" if str(t.get("target") or "").lower() == "github" else "notion",
        })
    return out


def _key(text: str) -> str:
    return re.sub(r"\W+", " ", (text or "").lower()).strip()


def _removed(conn, conv_id: int | None) -> list[str]:
    """Задачи этого разговора, которые пользователь удалил (смахнул) — их не создаём снова."""
    if conv_id is None:
        return []
    return [_key(r["text"]) for r in conn.execute(
        "SELECT text FROM tasks WHERE conversation_id = %s AND status = 'dismissed'", (conv_id,)).fetchall()]


def save(conn, conv_id: int | None, tasks: list[dict], source: str) -> dict:
    """Новые задачи — вставить; «та же самая» (same_as) — обновить срок и уверенность существующей.
    Удалённые пользователем задачи этого разговора повторно не появляются."""
    import difflib  # noqa: PLC0415

    created, updated = 0, 0
    removed = _removed(conn, conv_id)
    for t in tasks:
        k = _key(t["text"])
        if any(k == r or difflib.SequenceMatcher(None, k, r).ratio() > 0.8 for r in removed):
            continue
        if t.get("same_as"):
            row = conn.execute("SELECT id, status FROM tasks WHERE id = %s AND status IN ('open', 'suggested')",
                               (t["same_as"],)).fetchone()
            if row:
                conn.execute(
                    """UPDATE tasks SET due = coalesce(%s, due), due_at = coalesce(%s, due_at),
                           status = CASE WHEN status = 'suggested' AND %s = 'open' THEN 'open' ELSE status END,
                           confidence = greatest(coalesce(confidence, 0), %s), updated_at = now()
                       WHERE id = %s""",
                    (crypto.seal(t["due"]), t["due_at"], t["status"], t["confidence"], row["id"]))
                updated += 1
                continue
        conn.execute(
            """INSERT INTO tasks (conversation_id, text, due, due_at, assignee, person_id, kind, confidence, evidence,
                                  target, source, status, counterpart)
               VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)""",
            (conv_id, crypto.seal(t["text"]), crypto.seal(t["due"]), t["due_at"], crypto.seal(t["assignee"]),
             t["person_id"], t["kind"], t["confidence"], crypto.seal(t["evidence"]), t["target"], source, t["status"],
             crypto.seal(t.get("counterpart"))))
        created += 1
    return {"created": created, "updated": updated}


def owner_spoke(conn, segs: list[dict]) -> bool | None:
    """Говорил ли пользователь в разговоре. None — пользователь ещё неизвестен или голоса не различались."""
    if conn.execute("SELECT 1 FROM people WHERE is_owner").fetchone() is None:
        return None
    if not any(s.get("person_id") for s in segs):
        return None
    return any(bool(s.get("person_owner")) for s in segs)


# ---------------------------------------------------------------- фраза «напомни / создай задачу»
TRIGGER = re.compile(
    r"\b(напомни|напомнить мне|создай задачу|запиши задачу|добавь задачу|поставь задачу|не дай (мне )?забыть"
    r"|remind me|create a task|add a task|make a task"
    r"|hatırlat|görev (oluştur|ekle)|recuérdame|crea (una )?tarea|añade (una )?tarea)", re.I)

QUICK_SYSTEM = f"""Пользователь кулона только что сказал фразу-команду («напомни…», «создай задачу…»). Тебе дан фрагмент
расшифровки вокруг неё. Верни строго JSON: {{"tasks": [{SCHEMA}]}}
Создай задачу только по этой команде пользователя (kind=reminder для «напомни», иначе mine). Если команда
относится к другому человеку, укажи его в who. confidence 0.9, если команда ясная.
{RULES}"""


def due_reminders(conn, limit: int = 50) -> list[dict]:
    """Задачи со сроком в ближайшие сутки — телефон ставит по ним напоминания."""
    return conn.execute(
        """SELECT id, text, due_at, kind, assignee FROM tasks
           WHERE status = 'open' AND due_at IS NOT NULL
             AND due_at BETWEEN now() - interval '1 hour' AND now() + interval '7 days'
           ORDER BY due_at LIMIT %s""", (limit,)).fetchall()
