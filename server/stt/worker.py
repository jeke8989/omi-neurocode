"""NeuroSoft STT — расшифровка кусков аудио и сборка их в разговоры."""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path

import requests

from neuro import config, db, llm
from neuro.audio import SAMPLE_RATE, pcm_rms, pcm_to_wav

log = logging.getLogger("neuro.stt")
MAX_ATTEMPTS = 5


@dataclass
class Seg:
    start: float  # секунды от начала куска
    end: float
    speaker: int | None
    text: str


# ---------------------------------------------------------------------------
# Провайдеры расшифровки
# ---------------------------------------------------------------------------
class DeepgramSTT:
    name = "deepgram"

    def __init__(self):
        if not config.DEEPGRAM_API_KEY:
            raise RuntimeError("DEEPGRAM_API_KEY is not set")

    def transcribe(self, pcm: bytes, conn=None) -> list[Seg]:
        params = {
            "model": config.DEEPGRAM_MODEL,
            "language": config.STT_LANGUAGE,
            "punctuate": "true",
            "smart_format": "true",
            "diarize": "true",
            "utterances": "true",
        }
        r = requests.post(
            "https://api.deepgram.com/v1/listen",
            params=params,
            headers={"Authorization": f"Token {config.DEEPGRAM_API_KEY}", "Content-Type": "audio/wav"},
            data=pcm_to_wav(pcm),
            timeout=180,
        )
        if r.status_code >= 400:
            raise RuntimeError(f"deepgram {r.status_code}: {r.text[:300]}")
        results = r.json().get("results") or {}
        utts = results.get("utterances") or []
        if utts:
            return [Seg(float(u["start"]), float(u["end"]), u.get("speaker"), u.get("transcript", "")) for u in utts]
        try:
            text = results["channels"][0]["alternatives"][0].get("transcript", "")
        except (KeyError, IndexError):
            text = ""
        return [Seg(0.0, len(pcm) / 2 / SAMPLE_RATE, None, text)] if text.strip() else []


class RemoteSTT:
    """OpenAI-совместимый /audio/transcriptions: OpenRouter (Whisper Large v3 и др.), OpenAI, Groq…
    Модель выбирается в приложении (задача «Расшифровка записи»)."""

    name = "openrouter"

    def __init__(self):
        if not config.OPENAI_STT_API_KEY:
            raise RuntimeError("нет ключа: задайте OPENROUTER_API_KEY (или OPENAI_STT_API_KEY)")

    def transcribe(self, pcm: bytes, conn=None) -> list[Seg]:
        j = llm.transcribe(conn, "transcribe", pcm, verbose=True, timeout=180)
        segs = j.get("segments") or []
        if segs:
            return [Seg(float(s["start"]), float(s["end"]), s.get("speaker") if isinstance(s.get("speaker"), int)
                        else None, (s.get("text") or "").strip()) for s in segs]
        text = (j.get("text") or "").strip()
        return [Seg(0.0, len(pcm) / 2 / SAMPLE_RATE, None, text)] if text else []


class LocalWhisperSTT:
    """faster-whisper прямо на сервере. Ключи не нужны, но нагружает процессор."""

    name = "whisper"

    def __init__(self):
        from faster_whisper import WhisperModel  # тяжёлый импорт — только если выбран этот провайдер

        log.info("loading whisper model %s (%s/%s)...", config.WHISPER_MODEL, config.WHISPER_DEVICE,
                 config.WHISPER_COMPUTE)
        self.model = WhisperModel(
            config.WHISPER_MODEL,
            device=config.WHISPER_DEVICE,
            compute_type=config.WHISPER_COMPUTE,
            download_root=config.WHISPER_MODELS_DIR,
        )
        log.info("whisper model loaded")

    def transcribe(self, pcm: bytes, conn=None) -> list[Seg]:
        import numpy as np

        audio = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16).astype(np.float32) / 32768.0
        segments, _info = self.model.transcribe(
            audio,
            language=config.STT_LANGUAGE,
            vad_filter=True,
            beam_size=config.WHISPER_BEAM,
            condition_on_previous_text=False,
        )
        return [Seg(float(s.start), float(s.end), None, s.text.strip()) for s in segments]


class FakeSTT:
    """Для тестов: «распознаёт» любой звук как фиксированную фразу."""

    name = "fake"

    def transcribe(self, pcm: bytes, conn=None) -> list[Seg]:
        dur = len(pcm) / 2 / SAMPLE_RATE
        return [Seg(0.0, dur, 0, f"Тестовая расшифровка, {dur:.1f} сек. Нужно подготовить отчёт к пятнице.")]


PROVIDERS = {"openrouter": RemoteSTT, "openai": RemoteSTT, "deepgram": DeepgramSTT,
             "whisper": LocalWhisperSTT, "fake": FakeSTT}


# ---------------------------------------------------------------------------
# Работа с базой
# ---------------------------------------------------------------------------
CLAIM_SQL = """
UPDATE audio_chunks SET status = 'processing', locked_at = now(), attempts = attempts + 1
WHERE id = (
    SELECT id FROM audio_chunks
    WHERE status = 'pending' AND (not_before IS NULL OR not_before <= now())
    ORDER BY started_at
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING id, device_id, started_at, duration_ms, path, attempts
"""

REQUEUE_STUCK_SQL = """
UPDATE audio_chunks SET status = 'pending', locked_at = NULL
WHERE status = 'processing' AND locked_at < now() - interval '15 minutes'
"""

FIND_CONVERSATION_SQL = """
SELECT id FROM conversations
WHERE device_id = %(device)s
  AND started_at <= %(end)s + %(gap)s * interval '1 second'
  AND last_activity >= %(start)s - %(gap)s * interval '1 second'
ORDER BY last_activity DESC
LIMIT 1
FOR UPDATE
"""

UPDATE_CONVERSATION_SQL = """
UPDATE conversations SET
    started_at = least(started_at, %(start)s),
    last_activity = greatest(last_activity, %(end)s),
    status = 'open',
    updated_at = now()
WHERE id = %(id)s
"""

CREATE_CONVERSATION_SQL = """
INSERT INTO conversations (device_id, started_at, last_activity, status)
VALUES (%(device)s, %(start)s, %(end)s, 'open')
RETURNING id
"""

INSERT_SEGMENT_SQL = """
INSERT INTO segments (chunk_id, conversation_id, device_id, started_at, ended_at, speaker, text)
VALUES (%s, %s, %s, %s, %s, %s, %s)
"""


def store_segments(conn, chunk: dict, segs: list[Seg]) -> int:
    """Сохраняет фразы куска и привязывает их к разговору (новому или продолжающемуся)."""
    base = chunk["started_at"]
    rows = []
    for s in segs:
        start = base + timedelta(seconds=max(0.0, s.start))
        end = base + timedelta(seconds=max(s.start, s.end))
        rows.append((start, end, s.speaker, s.text.strip()))
    first, last = rows[0][0], max(r[1] for r in rows)
    params = {"device": chunk["device_id"], "start": first, "end": last, "gap": config.CONVERSATION_GAP_SEC}
    with conn.transaction():
        found = conn.execute(FIND_CONVERSATION_SQL, params).fetchone()
        if found:
            conv_id = found["id"]
            conn.execute(UPDATE_CONVERSATION_SQL, {**params, "id": conv_id})
        else:
            conv_id = conn.execute(CREATE_CONVERSATION_SQL, params).fetchone()["id"]
        for start, end, speaker, text in rows:
            conn.execute(INSERT_SEGMENT_SQL, (chunk["id"], conv_id, chunk["device_id"], start, end, speaker, text))
        conn.execute("UPDATE audio_chunks SET status = 'done', error = NULL WHERE id = %s", (chunk["id"],))
    return conv_id


def mark(conn, chunk_id: int, status: str, error: str | None = None):
    conn.execute("UPDATE audio_chunks SET status = %s, error = %s WHERE id = %s", (status, error, chunk_id))


def process(conn, stt, chunk: dict):
    path = chunk["path"]
    if not path or not Path(path).exists():
        mark(conn, chunk["id"], "failed", "audio file is missing")
        return
    pcm = Path(path).read_bytes()
    if config.SILENCE_RMS > 0 and pcm_rms(pcm) < config.SILENCE_RMS:
        mark(conn, chunk["id"], "empty")
        log.info("chunk %s: silence", chunk["id"])
        return
    t0 = time.monotonic()
    segs = [s for s in stt.transcribe(pcm, conn) if s.text and s.text.strip()]
    took = time.monotonic() - t0
    if not segs:
        mark(conn, chunk["id"], "empty")
        log.info("chunk %s: no speech (%.1fs)", chunk["id"], took)
        return
    conv_id = store_segments(conn, chunk, segs)
    log.info("chunk %s: %d segments -> conversation %s (%.1fs for %.1fs audio)",
             chunk["id"], len(segs), conv_id, took, chunk["duration_ms"] / 1000)


def main():
    db.wait_and_migrate()
    provider_cls = PROVIDERS.get(config.STT_PROVIDER)
    if provider_cls is None:
        raise SystemExit(f"unknown STT_PROVIDER={config.STT_PROVIDER!r}; use one of {', '.join(PROVIDERS)}")
    stt = provider_cls()
    log.info("stt worker started, provider=%s, language=%s", stt.name, config.STT_LANGUAGE)
    last_requeue = 0.0
    while True:
        try:
            with db.connect() as conn:
                while True:
                    if time.monotonic() - last_requeue > 60:
                        conn.execute(REQUEUE_STUCK_SQL)
                        last_requeue = time.monotonic()
                    chunk = conn.execute(CLAIM_SQL).fetchone()
                    if chunk is None:
                        time.sleep(1.5)
                        continue
                    try:
                        process(conn, stt, chunk)
                    except Exception as exc:  # noqa: BLE001
                        log.exception("chunk %s failed", chunk["id"])
                        if chunk["attempts"] >= MAX_ATTEMPTS:
                            mark(conn, chunk["id"], "failed", str(exc)[:500])
                        else:
                            conn.execute(
                                """UPDATE audio_chunks SET status = 'pending', error = %s,
                                       not_before = now() + %s * interval '1 minute'
                                   WHERE id = %s""",
                                (str(exc)[:500], chunk["attempts"], chunk["id"]),
                            )
        except Exception:  # noqa: BLE001
            log.exception("database error, retrying in 5s")
            time.sleep(5)


if __name__ == "__main__":
    main()
