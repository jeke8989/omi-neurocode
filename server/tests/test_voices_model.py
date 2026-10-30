"""Настоящая модель голосов (WeSpeaker) на записях живых людей.

Запускается, когда есть модель и записи (в CI их скачивает отдельный шаг):
  SPEAKER_MODEL_DIR=/путь/к/модели  SPEAKER_TEST_DATA=/путь/к/sr-data  pytest tests/test_voices_model.py
Записи: https://github.com/csukuangfj/sr-data (тестовые данные sherpa-onnx).
"""
import os
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

DATA = Path(os.environ.get("SPEAKER_TEST_DATA", ""))
MODEL_DIR = Path(os.environ.get("SPEAKER_MODEL_DIR", ""))

pytestmark = pytest.mark.skipif(
    not (DATA.is_dir() and (DATA / "test").is_dir() and MODEL_DIR.is_dir() and any(MODEL_DIR.glob("*.onnx"))),
    reason="нет модели голосов или тестовых записей")

# Отдельный процесс: остальные тесты работают на запасном отпечатке, а модель выбирается при импорте.
SCRIPT = textwrap.dedent("""
    import os, sys, wave
    import numpy as np
    os.environ["SPEAKER_BACKEND"] = "wespeaker"
    from neuro import voices
    data = sys.argv[1]

    def load(name, d="test"):
        with wave.open(f"{data}/{d}/{name}.wav") as f:
            return np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)

    m, th = voices.model(), voices.thresholds()
    people = {"fangjun": ["fangjun-sr-1", "fangjun-sr-2", "fangjun-sr-3"],
              "leijun": ["leijun-sr-1", "leijun-sr-2"], "liudehua": ["liudehua-sr-1", "liudehua-sr-2"]}
    centroids = {}
    for who, files in people.items():
        c = np.mean([m.embed(load(f, "enroll")) for f in files], axis=0)
        centroids[who] = c / np.linalg.norm(c)
    tests = {"fangjun": ["fangjun-test-sr-1", "fangjun-test-sr-2"],
             "leijun": ["leijun-test-sr-1", "leijun-test-sr-2", "leijun-test-sr-3"],
             "liudehua": ["liudehua-test-sr-1", "liudehua-test-sr-2"]}
    for who, files in tests.items():
        for f in files:
            e = m.embed(load(f))
            sims = {p: voices.cosine(e, c) for p, c in centroids.items()}
            best = max(sims, key=sims.get)
            assert best == who and sims[who] >= th.match, (f, sims)
            assert all(s < th.maybe for p, s in sims.items() if p != who), (f, sims)

    sil = np.zeros(16000, dtype=np.int16)
    parts = [load("fangjun-test-sr-1")[:-2000], load("leijun-test-sr-1")[3000:], load("liudehua-test-sr-1"),
             sil, load("fangjun-test-sr-2")]
    turns, local = voices.split_turns(np.concatenate(parts).tobytes())
    order = [t.local for t in turns]
    dedup = [x for i, x in enumerate(order) if i == 0 or order[i - 1] != x]
    assert len(local) == 3 and dedup == [0, 1, 2, 0], [(t.start / 16000, t.end / 16000, t.local) for t in turns]
    print("ok")
""")


def test_real_voices_are_told_apart():
    env = {**os.environ, "SPEAKER_MODEL_DIR": str(MODEL_DIR)}
    root = Path(__file__).resolve().parents[1]
    env["PYTHONPATH"] = os.pathsep.join([str(root / "common"), str(root), env.get("PYTHONPATH", "")])
    p = subprocess.run([sys.executable, "-c", SCRIPT, str(DATA)], capture_output=True, text=True, env=env,
                       timeout=300)
    assert p.returncode == 0 and "ok" in p.stdout, p.stdout + p.stderr
