"""Нейроголос: кеш, параллельные запросы, WAV; настоящая модель — если разрешено скачать (TTS_REAL=1)."""
import io
import os
import threading
import wave

import numpy as np
import pytest

from tests.test_pipeline import conn  # noqa: F401  (окружение тестов)


def test_cache_and_inflight(tmp_path, monkeypatch):
    from neuro import tts
    monkeypatch.setattr(tts, "CACHE_DIR", tmp_path)
    calls = []

    def fake(text, voice, lang, speed):
        calls.append((text, voice, lang))
        threading.Event().wait(0.2)
        return tts.to_wav(np.sin(np.arange(44100) / 20.0) * 0.3, 44100)

    monkeypatch.setattr(tts, "_generate", fake)
    out = []
    ts = [threading.Thread(target=lambda: out.append(tts.synthesize("Привет, **мир**! https://x.y", "m1", "ru")))
          for _ in range(3)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert len(calls) == 1 and calls[0] == ("Привет, мир!", "m1", "ru")  # одна генерация на три запроса
    assert len(set(out)) == 1
    tts.synthesize("Привет, мир!", "m1", "ru")
    assert len(calls) == 1  # из кеша
    with wave.open(io.BytesIO(out[0])) as w:
        assert w.getframerate() == 22050 and w.getnchannels() == 1
    with pytest.raises(ValueError):
        tts.synthesize("🙂 **", "f1", "ru")


@pytest.mark.skipif(os.environ.get("TTS_REAL") != "1", reason="настоящая модель качается только в CI (TTS_REAL=1)")
def test_real_voices(tmp_path, monkeypatch):
    from neuro import tts
    for voice in tts.VOICES:
        for lang, text in (("ru", "Напомню завтра в десять позвонить Ивану."), ("en", "I will remind you tomorrow.")):
            data = tts.synthesize(text, voice, lang)
            with wave.open(io.BytesIO(data)) as w:
                sec = w.getnframes() / w.getframerate()
            assert 1.0 < sec < 8.0, (voice, lang, sec)
