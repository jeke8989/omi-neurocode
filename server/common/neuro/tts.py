"""Нейроголос Mnemo на сервере: Supertonic 3 через sherpa-onnx (бесплатно, на процессоре, звук никуда не уходит).

Один голосовой движок понимает русский, английский, турецкий и испанский — все языки приложения.
Голоса на выбор (номер говорящего в модели): f1 — женский, m1 — мужской.
Модель (~130 МБ) скачивается сама при первом запросе в /models/tts. Готовые фразы кешируются на диске:
частые ответы («Запомнил») звучат мгновенно.
"""
from __future__ import annotations

import hashlib
import io
import logging
import os
import tarfile
import threading
import time
import wave
from concurrent.futures import Future
from pathlib import Path

import numpy as np
import requests

from . import config, crypto, safeload

log = logging.getLogger("neuro.tts")

MODEL_NAME = config.env("TTS_MODEL", "sherpa-onnx-supertonic-3-tts-int8-2026-05-11")
MODEL_URL = config.env(
    "TTS_MODEL_URL", f"https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{MODEL_NAME}.tar.bz2")
MODEL_DIR = Path(config.env("TTS_DIR", "/models/tts"))
CACHE_DIR = MODEL_DIR / "cache"
THREADS = int(config.env("TTS_THREADS", "2"))
STEPS = int(config.env("TTS_STEPS", "8"))

# голос -> номер говорящего в модели (0–4 женские, 5–9 мужские)
VOICES = {"f1": 0, "m1": 5}
DEFAULT_VOICE = "f1"
LANGS = {"ru", "en", "tr", "es", "de", "fr", "it", "pt", "pl", "uk"}
MAX_CHARS = 1200
MIN_DISK_MB = int(config.env("TTS_MIN_DISK_MB", "600"))   # скачать и распаковать модель (~260 МБ) с запасом
MIN_MEM_MB = int(config.env("TTS_MIN_MEM_MB", "700"))     # модель в памяти — около 300 МБ, оставляем соседям
WARMUP_DELAY = float(config.env("TTS_WARMUP_DELAY", "45"))  # сначала сервер поднимается, потом голос

_lock = threading.Lock()       # модель одна, генерация по очереди
_load_lock = threading.Lock()
_engine = None
_failed_at = 0.0
_inflight: dict[str, Future] = {}
_inflight_lock = threading.Lock()


class Unavailable(RuntimeError):
    pass


def _download():
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    target = MODEL_DIR / MODEL_NAME
    if (target / "tts.json").exists():
        return target
    log.info("downloading TTS model %s", MODEL_URL)
    tmp = MODEL_DIR / (MODEL_NAME + ".tar.bz2.part")
    with requests.get(MODEL_URL, stream=True, timeout=300) as r:
        r.raise_for_status()
        with open(tmp, "wb") as f:
            for block in r.iter_content(1 << 20):
                f.write(block)
    with tarfile.open(tmp, "r:bz2") as tar:
        tar.extractall(MODEL_DIR, filter="data")
    tmp.unlink(missing_ok=True)
    return target


def _create():
    import sherpa_onnx  # noqa: PLC0415 — тяжёлый импорт только когда нужен голос

    d = MODEL_DIR / MODEL_NAME
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
                duration_predictor=str(d / "duration_predictor.int8.onnx"),
                text_encoder=str(d / "text_encoder.int8.onnx"),
                vector_estimator=str(d / "vector_estimator.int8.onnx"),
                vocoder=str(d / "vocoder.int8.onnx"),
                tts_json=str(d / "tts.json"),
                unicode_indexer=str(d / "unicode_indexer.bin"),
                voice_style=str(d / "voice.bin"),
            ),
            num_threads=THREADS,
            provider="cpu",
        ),
    )
    if not cfg.validate():
        raise RuntimeError("bad TTS config")
    return sherpa_onnx.OfflineTts(cfg)


def _probe_body():
    """Выполняется в отдельном процессе: загрузить модель и сказать короткую фразу."""
    import sherpa_onnx  # noqa: PLC0415

    gen = sherpa_onnx.GenerationConfig()
    gen.sid = VOICES[DEFAULT_VOICE]
    gen.num_steps = 2
    gen.extra["lang"] = "ru"
    audio = _create().generate("Проверка голоса.", gen)
    if not len(audio.samples):
        raise SystemExit(3)


_retry_sec = 600.0


def engine():
    """Модель загружается один раз, после пробного запуска в отдельном процессе (safeload): если она не
    подходит серверу, падает проба, а не весь сервер. Пока голоса нет — ответы звучат голосом телефона."""
    global _engine, _failed_at, _retry_sec
    if _engine is not None:
        return _engine
    with _load_lock:
        if _engine is not None:
            return _engine
        if _failed_at and time.monotonic() - _failed_at < _retry_sec:
            raise Unavailable("tts model is not available")
        _retry_sec = 600.0  # сеть недоступна и т. п. — повтор через 10 минут
        try:
            downloaded = (MODEL_DIR / MODEL_NAME / "tts.json").exists()
            short = safeload.shortage(MODEL_DIR, 150 if downloaded else MIN_DISK_MB, MIN_MEM_MB)
            if short:
                _retry_sec = 1800.0
                raise RuntimeError(short)
            _download()
            if os.environ.get("NEURO_PROBE") != "1":
                ok, why = safeload.probe("from neuro import tts; tts._probe_body()", timeout=300)
                if not ok:
                    _retry_sec = 6 * 3600.0  # модель не подходит этому серверу — не мучаем его каждые 10 минут
                    raise RuntimeError(f"TTS probe failed: {why}")
            _engine = _create()
            log.info("TTS ready: %s", MODEL_NAME)
            return _engine
        except Exception as exc:  # noqa: BLE001
            _failed_at = time.monotonic()
            log.warning("TTS unavailable, answers use the phone voice: %s", exc)
            raise Unavailable(str(exc)) from exc


def warm_up():
    def run():
        time.sleep(WARMUP_DELAY)
        try:
            engine()
        except Unavailable:
            pass
    threading.Thread(target=run, name="tts-warmup", daemon=True).start()


def _key(text: str, voice: str, lang: str, speed: float) -> str:
    raw = f"{MODEL_NAME}|{voice}|{lang}|{speed:.2f}|{STEPS}|{text}"
    return hashlib.sha1(raw.encode()).hexdigest()


def to_wav(samples, rate: int) -> bytes:
    """float [-1, 1] -> WAV 16 бит. 44.1 кГц ужимаем вдвое: для голоса в наушнике хватает, файл вдвое меньше."""
    x = np.asarray(samples, dtype=np.float32)
    if rate >= 32000 and len(x) > 4:
        x = np.convolve(x, [0.25, 0.5, 0.25], mode="same")[::2]
        rate //= 2
    pcm = (np.clip(x, -1.0, 1.0) * 32767).astype(np.int16)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()


def _generate(text: str, voice: str, lang: str, speed: float) -> bytes:
    import sherpa_onnx  # noqa: PLC0415

    tts = engine()
    gen = sherpa_onnx.GenerationConfig()
    gen.sid = VOICES.get(voice, VOICES[DEFAULT_VOICE])
    gen.num_steps = STEPS
    gen.speed = speed
    gen.extra["lang"] = lang
    with _lock:
        audio = tts.generate(text, gen)
    if not len(audio.samples):
        raise RuntimeError("empty audio")
    return to_wav(audio.samples, audio.sample_rate)


def clean(text: str) -> str:
    """Убираем то, что синтезатор прочитал бы вслух как мусор: markdown, ссылки, эмодзи."""
    import re  # noqa: PLC0415

    t = re.sub(r"https?://\S+", "", text or "")
    t = re.sub(r"[*_`#>\[\]|~]", "", t)
    t = "".join(ch for ch in t if ch.isalnum() or ch.isspace() or ch in ".,!?;:-—–«»\"'()%+/№°")
    return " ".join(t.split())[:MAX_CHARS]


def synthesize(text: str, voice: str = DEFAULT_VOICE, lang: str = "ru", speed: float = 1.0) -> bytes:
    """WAV для фразы: из кеша, из уже идущей генерации или новой генерацией."""
    text = clean(text)
    if not text:
        raise ValueError("empty text")
    voice = voice if voice in VOICES else DEFAULT_VOICE
    lang = lang if lang in LANGS else "en"
    speed = max(0.6, min(1.6, float(speed or 1.0)))
    key = _key(text, voice, lang, speed)
    path = CACHE_DIR / f"{key}.wav"
    if path.exists():
        return crypto.read_file(path)
    with _inflight_lock:
        fut = _inflight.get(key)
        mine = fut is None
        if mine:
            fut = Future()
            _inflight[key] = fut
    if not mine:
        return fut.result(timeout=60)
    try:
        data = _generate(text, voice, lang, speed)
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        crypto.write_file(path, data)  # озвученные ответы на диске зашифрованы
        _trim_cache()
        fut.set_result(data)
        return data
    except Exception as exc:  # noqa: BLE001
        fut.set_exception(exc)
        raise
    finally:
        with _inflight_lock:
            _inflight.pop(key, None)


def prewarm(text: str, voice: str, lang: str, speed: float):
    """Начать озвучку ответа, пока телефон ещё не попросил звук (ответ звучит раньше)."""
    def run():
        try:
            synthesize(text, voice, lang, speed)
        except Exception:  # noqa: BLE001
            pass
    threading.Thread(target=run, name="tts-prewarm", daemon=True).start()


def _trim_cache(keep: int = 400):
    files = sorted(CACHE_DIR.glob("*.wav"), key=lambda p: p.stat().st_mtime)
    for f in files[:-keep]:
        f.unlink(missing_ok=True)
