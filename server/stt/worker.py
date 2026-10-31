"""NeuroSoft STT — расшифровка кусков аудио и сборка их в разговоры."""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path

import requests

from neuro import cleanup, config, db, i18n, llm, retention, voices
from neuro.audio import SAMPLE_RATE, pcm_rms, pcm_to_wav, voice_ms

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
            "language": i18n.stt_language(conn) or "multi",
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


# Мусор распознавания (фразы из субтитров, [музыка], зацикливания, «э-э») — в neuro.cleanup.
is_hallucination = cleanup.is_hallucination


def tidy_segs(segs: list[Seg]) -> list[Seg]:
    """Сразу после распознавания: убрать мусор из фраз, пустые и «субтитры сделал…» — выбросить."""
    out = []
    for s in segs:
        text = cleanup.tidy(s.text or "")
        if text:
            out.append(Seg(s.start, s.end, s.speaker, text))
    return out


class RemoteSTT:
    """OpenAI-совместимый /audio/transcriptions: OpenRouter (Whisper Large v3 и др.), OpenAI, Groq…
    Модель выбирается в приложении (задача «Расшифровка записи»)."""

    name = "openrouter"

    def __init__(self):
        if not config.OPENAI_STT_API_KEY:
            raise RuntimeError("нет ключа: задайте OPENROUTER_API_KEY (или OPENAI_STT_API_KEY)")

    def transcribe(self, pcm: bytes, conn=None) -> list[Seg]:
        j = llm.transcribe(conn, "transcribe", pcm, verbose=True, timeout=180)
        # сегменты, где модель сама считает, что речи нет (критерий OpenAI): пропускаем
        segs = [s for s in j.get("segments") or []
                if not (float(s.get("no_speech_prob") or 0) > 0.6 and float(s.get("avg_logprob") or 0) < -1.0)
                and float(s.get("compression_ratio") or 0) < 2.4]
        if j.get("segments"):
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
            language=i18n.stt_language(conn),
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

# Кусок не расшифровался 5 раз подряд (сеть, лимит провайдера) — не теряем его: раз в час ещё попытка,
# всего до 12, пока звук хранится на сервере.
RETRY_FAILED_SQL = """
UPDATE audio_chunks SET status = 'pending', not_before = NULL
WHERE status = 'failed' AND path IS NOT NULL AND attempts < 12
  AND coalesce(error, '') NOT LIKE 'audio file is missing%%'
  AND locked_at < now() - interval '1 hour'
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
    voices_done = false,
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
RETURNING id
"""


def store_segments(conn, chunk: dict, segs: list[Seg]) -> tuple[int, list[int]]:
    """Сохраняет фразы куска и привязывает их к разговору (новому или продолжающемуся).
    Возвращает (id разговора, id фраз по порядку)."""
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
        ids = [conn.execute(INSERT_SEGMENT_SQL, (chunk["id"], conv_id, chunk["device_id"], start, end, speaker,
                                                 text)).fetchone()["id"]
               for start, end, speaker, text in rows]
        conn.execute("UPDATE audio_chunks SET status = 'done', error = NULL WHERE id = %s", (chunk["id"],))
    return conv_id, ids


# ---------------------------------------------------------------------------
# Кто говорит
# ---------------------------------------------------------------------------
MAX_TURN_CALLS = 12  # если расшифровка пришла без разметки по времени — не больше стольких отдельных запросов


def _sec(samples: int) -> float:
    return samples / SAMPLE_RATE


def untimed(segs: list[Seg], pcm: bytes) -> bool:
    """Провайдер вернул весь кусок одной фразой без времени — по ней не понять, где чей голос."""
    dur = len(pcm) / 2 / SAMPLE_RATE
    return len(segs) == 1 and segs[0].start <= 0.05 and segs[0].end >= dur - 0.3


def transcribe_turns(conn, stt, pcm: bytes, turns: list[voices.Turn]) -> list[Seg]:
    """Расшифровка по репликам: каждая реплика отдельно (с полями 0.15 с), чтобы знать, кто что сказал."""
    pad = int(0.15 * SAMPLE_RATE) * 2
    out: list[Seg] = []
    for t in turns:
        a, b = max(0, t.start * 2 - pad), min(len(pcm), t.end * 2 + pad)
        for s in tidy_segs(stt.transcribe(pcm[a:b], conn)):
            out.append(Seg(_sec(t.start), _sec(t.end), t.local, s.text))
    return out


def label_segments(segs: list[Seg], turns: list[voices.Turn]) -> list[int | None]:
    """Каждой фразе — голос реплики, с которой она больше всего пересекается по времени."""
    if not turns:
        return [None] * len(segs)
    if len({t.local for t in turns}) == 1:
        return [turns[0].local] * len(segs)
    out: list[int | None] = []
    for s in segs:
        overlap: dict[int, float] = {}
        for t in turns:
            o = min(s.end, _sec(t.end)) - max(s.start, _sec(t.start))
            if o > 0:
                overlap[t.local] = overlap.get(t.local, 0.0) + o
        if overlap:
            out.append(max(overlap, key=overlap.get))
            continue
        near = min(turns, key=lambda t: max(0.0, s.start - _sec(t.end), _sec(t.start) - s.end))
        gap = max(0.0, s.start - _sec(near.end), _sec(near.start) - s.end)
        out.append(near.local if gap <= 2.0 else None)
    return out


def attach_people(conn, pcm: bytes, segs: list[Seg], seg_ids: list[int], labels: list[int | None],
                  turns: list[voices.Turn], local_voices: list[voices.LocalVoice]) -> int:
    """Подписывает фразы людьми; возвращает число подписанных фраз."""
    mapping = voices.identify(conn, local_voices, pcm, turns)
    done = 0
    for sid, seg, local in zip(seg_ids, segs, labels):
        pid, score = mapping.get(local, (None, 0.0)) if local is not None else (None, 0.0)
        if local is None:
            continue
        # отпечаток храним и у неузнанных фраз: по ним после разговора решается, кто это
        emb = voices.embed_span(pcm, seg.start, seg.end)
        conn.execute(
            """UPDATE segments SET person_id = %s, voice_score = %s, embedding = %s, embed_backend = %s
               WHERE id = %s""",
            (pid, round(score, 3) if pid else None, voices.vec(emb) if emb is not None else None,
             voices.backend_name() if emb is not None else None, sid))
        done += 1 if pid else 0
    return done


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
    if config.SPEECH_MIN_MS > 0 and voice_ms(pcm) < config.SPEECH_MIN_MS:
        mark(conn, chunk["id"], "empty")
        log.info("chunk %s: no speech (%.0fs of quiet or steady noise), skipped", chunk["id"], len(pcm) / 2 / SAMPLE_RATE)
        return
    t0 = time.monotonic()
    turns: list[voices.Turn] = []
    local_voices: list[voices.LocalVoice] = []
    if voices.enabled(conn):
        try:
            turns, local_voices = voices.split_turns(pcm)
        except voices.Unavailable:
            pass
        except Exception:  # noqa: BLE001 — голоса не должны ломать расшифровку
            log.exception("chunk %s: voice split failed", chunk["id"])
    segs = tidy_segs(stt.transcribe(pcm, conn))
    if len(local_voices) >= 2 and 2 <= len(turns) <= MAX_TURN_CALLS and untimed(segs, pcm):
        segs = transcribe_turns(conn, stt, pcm, turns) or segs
    took = time.monotonic() - t0
    if not segs:
        mark(conn, chunk["id"], "empty")
        log.info("chunk %s: no speech (%.1fs)", chunk["id"], took)
        return
    labels = label_segments(segs, turns)
    if turns:  # иначе остаётся разметка провайдера (Deepgram)
        for s, local in zip(segs, labels):
            s.speaker = local
    conv_id, seg_ids = store_segments(conn, chunk, segs)
    named = 0
    if local_voices:
        try:
            named = attach_people(conn, pcm, segs, seg_ids, labels, turns, local_voices)
        except Exception:  # noqa: BLE001
            log.exception("chunk %s: speaker identification failed", chunk["id"])
    log.info("chunk %s: %d segments, %d voices (%d signed) -> conversation %s (%.1fs for %.1fs audio)",
             chunk["id"], len(segs), len(local_voices), named, conv_id, took, chunk["duration_ms"] / 1000)


def finalize_voices(conn):
    """Разговор закончился — решаем, кто в нём говорил, по всем его фразам (до итогов агента)."""
    if not voices.enabled(conn):
        conn.execute("UPDATE conversations SET voices_done = true WHERE NOT voices_done")
        return
    for conv_id in voices.pending_conversations(conn, max(60, config.CONVERSATION_IDLE_SEC - 30)):
        try:
            res = voices.finalize_conversation(conn, conv_id)
            log.info("conversation %s: voices %d, signed %d, new %d", conv_id, res["voices"], res["named"],
                     res["new"])
        except voices.Unavailable:
            return
        except Exception:  # noqa: BLE001
            log.exception("conversation %s: voices failed", conv_id)
            conn.execute("UPDATE conversations SET voices_done = true WHERE id = %s", (conv_id,))


def main():
    db.wait_and_migrate()
    provider_cls = PROVIDERS.get(config.STT_PROVIDER)
    if provider_cls is None:
        raise SystemExit(f"unknown STT_PROVIDER={config.STT_PROVIDER!r}; use one of {', '.join(PROVIDERS)}")
    stt = provider_cls()
    log.info("stt worker started, provider=%s, language=%s", stt.name, config.STT_LANGUAGE or "app")
    voices.warm_up()
    migrated = False
    last_requeue = 0.0
    last_consolidate = time.monotonic()
    while True:
        try:
            with db.connect() as conn:
                while True:
                    if time.monotonic() - last_requeue > 60:
                        conn.execute(REQUEUE_STUCK_SQL)
                        conn.execute(RETRY_FAILED_SQL)
                        last_requeue = time.monotonic()
                    if not migrated and voices.ready():
                        try:
                            voices.migrate_backend(conn)
                            migrated = True
                        except voices.Unavailable:
                            pass
                        except Exception:  # noqa: BLE001
                            log.exception("voices: model migration failed")
                            migrated = True
                    try:
                        if voices.enroll_owner(conn):
                            log.info("voices: owner voice updated from button questions")
                    except Exception:  # noqa: BLE001
                        log.exception("voices: owner enrollment failed")
                    finalize_voices(conn)
                    if time.monotonic() - last_consolidate > 3600:
                        last_consolidate = time.monotonic()
                        try:
                            res = voices.dedupe(conn)
                            if res["merged"] or res["suggested"]:
                                log.info("voices: merged %d duplicate voices, %d suggestions", res["merged"],
                                         res["suggested"])
                        except voices.Unavailable:
                            pass
                        except Exception:  # noqa: BLE001
                            log.exception("voices: dedupe failed")
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
                        continue
                    try:
                        retention.after_transcribed(conn, chunk["id"])
                    except Exception:  # noqa: BLE001 — звук удалит уборка агента
                        log.exception("chunk %s: could not remove audio", chunk["id"])
        except Exception:  # noqa: BLE001
            log.exception("database error, retrying in 5s")
            time.sleep(5)


if __name__ == "__main__":
    main()
