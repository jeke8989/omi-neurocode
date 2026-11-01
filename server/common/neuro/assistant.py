"""Ассистент: общие помощники для задач/памяти и голосовой вопрос по кнопке."""
from __future__ import annotations

import logging
import time
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from . import config, crypto, llm
from .audio import BYTES_PER_MS, pcm_rms
from .people import SEGMENT_SELECT, speaker_label

log = logging.getLogger("neuro.assistant")
TZ = ZoneInfo(config.TZ)

TASK_SCHEMA = (
    '{"text": "конкретное действие", "due": "срок, если назван, иначе null", '
    '"assignee": "кто делает: подпись говорящего или имя, если ясно, иначе null", '
    '"target": "github, если это задача на разработку программы, иначе notion"}'
)

WEEKDAYS = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]

# ---------------------------------------------------------------- общие помощники
def fmt_time(dt: datetime) -> str:
    return dt.astimezone(TZ).strftime("%H:%M:%S")


def transcript_text(segs: list[dict], limit: int | None = None) -> str:
    """Расшифровка для модели: время, кто говорит (имя, «Пользователь», «Голос N»), текст.
    Голоса с пометкой «не учитывать» (телевизор, радио) пропускаются."""
    lines = []
    for s in segs:
        if s.get("person_ignored") or s.get("junk") or s.get("person_kind") in ("media", "crowd"):
            continue  # телевизор, реклама, чужие разговоры рядом — не речь участников
        lines.append(f"[{fmt_time(s['started_at'])}] {speaker_label(s)}: {s['text']}")
    text = "\n".join(lines)
    limit = limit or config.LLM_MAX_CHARS
    if len(text) > limit:
        half = limit // 2
        text = text[:half] + "\n…(середина пропущена)…\n" + text[-half:]
    return text


def clean_tasks(raw) -> list[dict]:
    out = []
    if not isinstance(raw, list):
        return out
    for t in raw:
        if isinstance(t, str):
            t = {"text": t}
        if not isinstance(t, dict) or not str(t.get("text") or "").strip():
            continue

        def opt(key, t=t):
            v = t.get(key)
            return str(v).strip() if v not in (None, "", "null") else None

        out.append({
            "text": str(t["text"]).strip()[:1000],
            "due": opt("due"),
            "assignee": opt("assignee"),
            "target": "github" if str(t.get("target") or "").lower() == "github" else "notion",
        })
    return out


def clean_list(raw) -> list[str]:
    if not isinstance(raw, list):
        return []
    return [str(x).strip()[:2000] for x in raw if str(x or "").strip()]


def insert_tasks(conn, conv_id, tasks: list[dict], source: str):
    for t in tasks:
        conn.execute(
            """INSERT INTO tasks (conversation_id, text, due, assignee, target, source, person_id)
               VALUES (%s, %s, %s, %s, %s, %s, %s)""",
            (conv_id, crypto.seal(t["text"]), crypto.seal(t["due"]), crypto.seal(t["assignee"]), t["target"], source,
             t.get("person_id")),
        )


def insert_memories(conn, conv_id, memories: list[str], source: str):
    from . import hooks, memory  # noqa: PLC0415 — hooks тянет расширения

    # без пустого и повторов; похожее на известное — объединяется со старой записью (как в Mem0)
    for m, emb in memory.consolidate(conn, memories, source):
        row = conn.execute("INSERT INTO memories (conversation_id, text, source, emb) VALUES (%s, %s, %s, %s) "
                           "RETURNING id, created_at", (conv_id, crypto.seal(m), source, emb)).fetchone()
        hooks.emit(conn, "memory", {"id": row["id"], "text": m, "conversation_id": conv_id,
                                    "created_at": row["created_at"].isoformat(), "source": source})


# ---------------------------------------------------------------- конец вопроса по паузе
class Endpointer:
    """Определяет, что человек договорил: была речь, затем тишина ASK_END_SILENCE_MS.

    Порог громкости подстраивается под шум (быстро вниз, медленно вверх), так что работает
    и в тихой комнате, и на улице. Если речь не распознана как речь — вопрос всё равно
    закончится по максимальной длине или повторному нажатию, а решит распознавание.
    """

    FRAME = 20 * BYTES_PER_MS  # 20 мс

    def __init__(self, end_silence_ms: int | None = None, min_rms: float = 180.0):
        self.end_silence_ms = end_silence_ms or config.ASK_END_SILENCE_MS
        self.min_rms = min_rms
        self.noise: float | None = None
        self.speech_ms = 0
        self.silence_ms = 0
        self.started = False
        self._rest = b""

    def feed(self, pcm: bytes) -> bool:
        data = self._rest + pcm
        n = len(data) - len(data) % self.FRAME
        self._rest = data[n:]
        for i in range(0, n, self.FRAME):
            rms = pcm_rms(data[i:i + self.FRAME])
            if self.noise is None:
                self.noise = rms
            speech = rms > max(self.noise * 3.0, self.min_rms)
            # уровень шума: сразу вниз, вверх — медленно и только в паузах (иначе догонит голос)
            if rms < self.noise:
                self.noise = rms
            elif not speech:
                self.noise = self.noise * 0.98 + rms * 0.02
            if speech:
                self.speech_ms += 20
                self.silence_ms = 0
                if self.speech_ms >= 200:
                    self.started = True
            elif self.started:
                self.silence_ms += 20
                if self.silence_ms >= self.end_silence_ms:
                    return True
        return False


# ---------------------------------------------------------------- голосовой вопрос
def build_context(conn, device_id: str, question: str | None = None) -> str:
    now = datetime.now(timezone.utc)
    local = now.astimezone(TZ)
    parts = [f"Сейчас {WEEKDAYS[local.weekday()]}, {local.strftime('%d.%m.%Y %H:%M')}."]
    segs = conn.execute(
        SEGMENT_SELECT + " WHERE s.device_id = %s AND s.started_at > %s ORDER BY s.started_at",
        (device_id, now - timedelta(minutes=config.ASK_CONTEXT_MIN)),
    ).fetchall()
    if segs:
        parts.append("Недавний разговор (расшифровка с кулона):\n" + transcript_text(segs, limit=8000))
    tasks = conn.execute(
        "SELECT text, due FROM tasks WHERE status = 'open' ORDER BY created_at DESC LIMIT 15"
    ).fetchall()
    if tasks:
        parts.append("Открытые задачи:\n" + "\n".join(
            f"- {t['text']}" + (f" (срок: {t['due']})" if t["due"] else "") for t in tasks))
    # память: не вся подряд, а относящееся к вопросу (по смыслу) + пара свежих записей — токены не растут с памятью
    from . import memory  # noqa: PLC0415

    picked = memory.recall(conn, question, k=6, kinds=("memory",)) if question else []
    fresh = conn.execute("SELECT id, text FROM memories WHERE archived_at IS NULL ORDER BY created_at DESC LIMIT 3").fetchall()
    seen, lines = set(), []
    for m in [*picked, *fresh]:
        if m["id"] not in seen:
            seen.add(m["id"])
            lines.append(f"- [память {m['id']}] {m['text']}")
    if lines:
        parts.append("Из памяти (подходящее к вопросу; остальное — search_memory):\n" + "\n".join(lines))
    return "\n\n".join(parts)


def transcribe_question(conn, pcm: bytes) -> str:
    """Распознать вопрос, заданный кнопкой. Пустая строка — речи нет."""
    data = llm.transcribe(conn, "ask_stt", pcm, timeout=config.ASK_TIMEOUT_SEC)
    text = (data.get("text") or "").strip()
    return text if len(text) >= 2 else ""


def record_question(conn, device_id: str, client_id: str | None, question: str, answer: str, model: str | None,
                    latency_ms: int, via: str = "voice", error: str | None = None):
    conn.execute(
        """INSERT INTO questions (device_id, client_id, question, answer, model, latency_ms, via, error)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s)""",
        (device_id, client_id, crypto.seal(question), crypto.seal(answer), model, latency_ms, via, error),
    )
