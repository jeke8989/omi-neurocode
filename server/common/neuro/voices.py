"""Голоса: кто говорит в разговоре.

1. Кусок записи делится на отрезки речи по паузам (VAD по громкости).
2. Для каждого отрезка ≥ 1 с считается «отпечаток голоса» — вектор из модели WeSpeaker ResNet34
   (ONNX, работает на процессоре сервера, бесплатно, звук никуда не уходит).
3. Соседние отрезки одного голоса склеиваются в реплики; голоса куска сравниваются с людьми:
   похож — подписываем имя, нет — заводим «Голос N». Подтверждённые имена дообучают узнавание.

Если модель недоступна, работает простой спектральный отпечаток — хуже, но пайплайн не ломается.
"""
from __future__ import annotations

import logging
import threading
import time
import wave
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import requests

from . import config
from .audio import SAMPLE_RATE

log = logging.getLogger("neuro.voices")

MODEL_URL = config.env(
    "SPEAKER_MODEL_URL",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"
    "wespeaker_en_voxceleb_resnet34_LM.onnx")
MODEL_DIR = Path(config.env("SPEAKER_MODEL_DIR", "/models/speakers"))
VOICE_DIR = Path(config.env("VOICE_DIR", "/data/voices"))
BACKEND = config.env("SPEAKER_BACKEND", "wespeaker")  # wespeaker | spectral

FRAME = 20 * SAMPLE_RATE // 1000  # 20 мс
MIN_EMBED_SEC = 1.0               # короче — отпечаток ненадёжен
MIN_NEW_VOICE_SEC = 2.0           # новый «Голос N» заводим только по уверенному отрезку


@dataclass
class Thresholds:
    same: float      # два отрезка одного куска — один голос
    match: float     # голос = известный человек
    maybe: float     # похоже, но не уверены («Анна?»)
    learn: float     # достаточно уверенно, чтобы дообучить отпечаток
    merge: float     # два неизвестных голоса — один и тот же


THRESHOLDS = {
    # подобрано на записях разных людей: один человек 0.50–0.77, разные — не выше 0.36
    "wespeaker": Thresholds(same=0.45, match=0.50, maybe=0.40, learn=0.60, merge=0.62),
    # запасной MFCC-отпечаток (тесты): один голос ≥ 0.998, разные синтетические — не выше 0.95
    "spectral": Thresholds(same=0.975, match=0.975, maybe=0.96, learn=0.985, merge=0.99),
}


# ======================================================================= признаки (kaldi fbank)
def _mel_banks(num_bins: int = 80, n_fft: int = 512, low: float = 20.0, high: float = SAMPLE_RATE / 2) -> np.ndarray:
    def mel(f):
        return 1127.0 * np.log(1.0 + np.asarray(f) / 700.0)

    lo, hi = mel(low), mel(high)
    delta = (hi - lo) / (num_bins + 1)
    centers = lo + delta * np.arange(num_bins + 2)
    fft_mel = mel(np.arange(n_fft // 2) * SAMPLE_RATE / n_fft)
    banks = np.zeros((num_bins, n_fft // 2 + 1))
    for b in range(num_bins):
        left, center, right = centers[b], centers[b + 1], centers[b + 2]
        up = (fft_mel - left) / (center - left)
        down = (right - fft_mel) / (right - center)
        banks[b, : n_fft // 2] = np.maximum(0.0, np.minimum(up, down))
    return banks


_BANKS = _mel_banks()
_WINDOW = 0.54 - 0.46 * np.cos(2 * np.pi * np.arange(400) / 399)  # hamming, как в WeSpeaker


def fbank(samples: np.ndarray) -> np.ndarray:
    """80 лог-мел-фильтров, окно 25 мс, шаг 10 мс (совместимо с kaldi.fbank), с вычитанием среднего."""
    x = samples.astype(np.float64)
    if len(x) < 400:
        return np.zeros((0, 80), dtype=np.float32)
    n = 1 + (len(x) - 400) // 160
    idx = np.arange(400)[None, :] + 160 * np.arange(n)[:, None]
    frames = x[idx]
    frames = frames - frames.mean(axis=1, keepdims=True)
    frames = np.concatenate([frames[:, :1] * (1 - 0.97), frames[:, 1:] - 0.97 * frames[:, :-1]], axis=1)
    frames = frames * _WINDOW
    spec = np.abs(np.fft.rfft(frames, n=512)) ** 2
    feats = np.log(np.maximum(spec @ _BANKS.T, np.finfo(np.float32).eps))
    feats = feats - feats.mean(axis=0, keepdims=True)
    return feats.astype(np.float32)


# ======================================================================= отпечаток голоса
class _WeSpeaker:
    name = "wespeaker"

    def __init__(self):
        import onnxruntime as ort  # noqa: PLC0415 — тяжёлый импорт только в STT

        path = MODEL_DIR / Path(MODEL_URL).name
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            log.info("downloading speaker model %s", MODEL_URL)
            tmp = path.with_suffix(".part")
            with requests.get(MODEL_URL, stream=True, timeout=120) as r:
                r.raise_for_status()
                with open(tmp, "wb") as f:
                    for block in r.iter_content(1 << 20):
                        f.write(block)
            tmp.rename(path)
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = int(config.env("SPEAKER_THREADS", "2"))
        self.session = ort.InferenceSession(str(path), opts, providers=["CPUExecutionProvider"])
        self.input = self.session.get_inputs()[0].name

    def embed(self, pcm16: np.ndarray) -> np.ndarray:
        feats = fbank(pcm16.astype(np.float32))  # модель ждёт сэмплы в масштабе int16
        out = self.session.run(None, {self.input: feats[None]})[0][0]
        return out / (np.linalg.norm(out) + 1e-9)


class _Spectral:
    """Запасной отпечаток без нейросети: средние и разброс MFCC. Для тестов и слабых серверов —
    различает голоса заметно хуже модели."""

    name = "spectral"
    _DCT = np.cos(np.pi / 80 * (np.arange(80)[None, :] + 0.5) * np.arange(1, 21)[:, None])

    def embed(self, pcm16: np.ndarray) -> np.ndarray:
        feats = fbank(pcm16.astype(np.float32))
        if len(feats) == 0:
            return np.zeros(40, dtype=np.float32)
        mfcc = feats @ self._DCT.T  # c1..c20 (без c0 — громкости)
        mfcc = mfcc + feats.mean(axis=0) @ self._DCT.T  # fbank вычел среднее — возвращаем тембр
        v = np.concatenate([mfcc.mean(axis=0), mfcc.std(axis=0) * 0.5])
        return (v / (np.linalg.norm(v) + 1e-9)).astype(np.float32)


_lock = threading.Lock()
_model = None
_failed_at = 0.0


class Unavailable(RuntimeError):
    """Модель голосов пока не загрузилась — кусок расшифровывается без подписей, повторим позже."""


def model():
    """Модель отпечатков (загружается один раз). Отпечатки разных моделей несравнимы, поэтому
    при ошибке загрузки не подменяем модель, а пробуем снова через 10 минут."""
    global _model, _failed_at
    if _model is not None:
        return _model
    if not _lock.acquire(blocking=False):
        raise Unavailable("speaker model is loading")  # качается в фоне — этот кусок без подписей
    try:
        if _model is None:
            if _failed_at and time.monotonic() - _failed_at < 600:
                raise Unavailable("speaker model is not loaded yet")
            try:
                _model = _WeSpeaker() if BACKEND == "wespeaker" else _Spectral()
            except Exception as exc:  # noqa: BLE001
                _failed_at = time.monotonic()
                log.exception("speaker model unavailable, voices are not recognized for now")
                raise Unavailable(str(exc)) from exc
        return _model
    finally:
        _lock.release()


def warm_up():
    """Загрузить модель в фоне при старте STT, чтобы первый кусок не ждал скачивания."""
    def run():
        try:
            log.info("voices: %s model ready", model().name)
        except Unavailable:
            pass
    threading.Thread(target=run, name="voices-warmup", daemon=True).start()


def backend_name() -> str:
    return "wespeaker" if BACKEND == "wespeaker" else "spectral"


def thresholds() -> Thresholds:
    return THRESHOLDS[backend_name()]


def cosine(a, b) -> float:
    a = np.asarray(a, dtype=np.float32)
    b = np.asarray(b, dtype=np.float32)
    return float(a @ b / ((np.linalg.norm(a) * np.linalg.norm(b)) + 1e-9))


# ======================================================================= отрезки речи и реплики
def speech_regions(pcm16: np.ndarray, min_rms: float = 180.0, ratio: float = 2.5,
                   gap_ms: int = 350, min_ms: int = 400) -> list[tuple[int, int]]:
    """Отрезки речи (в сэмплах): кадры громче фона, паузы короче gap_ms не разрывают отрезок."""
    n = len(pcm16) // FRAME
    if n == 0:
        return []
    a = pcm16[: n * FRAME].astype(np.float64).reshape(n, FRAME)
    rms = np.sqrt((a * a).mean(axis=1))
    loud = rms > max(float(np.percentile(rms, 10)) * ratio, min_rms)
    regions, start, quiet = [], None, 0
    for i, on in enumerate(loud):
        if on:
            if start is None:
                start = i
            quiet = 0
        elif start is not None:
            quiet += 1
            if quiet * 20 >= gap_ms:
                regions.append((start, i - quiet + 1))
                start, quiet = None, 0
    if start is not None:
        regions.append((start, n - quiet))
    return [(s * FRAME, e * FRAME) for s, e in regions if (e - s) * 20 >= min_ms]


@dataclass
class Turn:
    """Реплика одного голоса внутри куска."""
    start: int                       # сэмпл
    end: int
    local: int                       # номер голоса внутри куска
    loudness: float = 0.0
    embedding: np.ndarray | None = None


@dataclass
class LocalVoice:
    index: int
    embeddings: list = field(default_factory=list)
    seconds: float = 0.0
    loudness: float = 0.0

    @property
    def centroid(self) -> np.ndarray:
        c = np.mean(self.embeddings, axis=0)
        return c / (np.linalg.norm(c) + 1e-9)


WINDOW_SEC = 3.0   # длинный отрезок режем на окна: вдруг собеседник вступил без паузы


def _norm(v: np.ndarray) -> np.ndarray:
    return v / (np.linalg.norm(v) + 1e-9)


def _subregions(m, th, x: np.ndarray, s: int, e: int) -> list[tuple[int, int, np.ndarray]]:
    """Отрезок речи -> куски одного голоса с отпечатками. Короткий отрезок — целиком,
    длинный — окнами по 3 с; соседние окна с непохожими голосами разделяются."""
    win = int(WINDOW_SEC * SAMPLE_RATE)
    if e - s <= 2 * win:
        return [(s, e, m.embed(x[s:e]))]
    bounds = list(range(s, e, win))
    if e - bounds[-1] < win // 2:
        bounds.pop()
    bounds.append(e)
    parts: list[list] = []  # [start, end, [embeddings]]
    for a, b in zip(bounds, bounds[1:]):
        emb = m.embed(x[a:b])
        if parts and cosine(emb, _norm(np.mean(parts[-1][2], axis=0))) >= th.same:
            parts[-1][1] = b
            parts[-1][2].append(emb)
        else:
            parts.append([a, b, [emb]])
    return [(a, b, _norm(np.mean(embs, axis=0))) for a, b, embs in parts]


def split_turns(pcm: bytes) -> tuple[list[Turn], list[LocalVoice]]:
    """Делит кусок на реплики и голоса.

    Отрезки ≥ 1 с получают отпечаток и голос (похож на уже встреченный в куске — тот же, иначе новый).
    Короткие («Да.», «Ага») — к более похожему из соседних голосов, а совсем короткие — к ближайшему по времени.
    Соседние отрезки одного голоса с паузой < 1.5 с склеиваются в реплику."""
    x = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16)
    regions = speech_regions(x)
    if not regions:
        return [], []
    m, th = model(), thresholds()
    voices: list[LocalVoice] = []
    labelled: list[list] = []  # [start, end, local | None, embedding | None, loudness]
    for rs, re_ in regions:
        if (re_ - rs) / SAMPLE_RATE < MIN_EMBED_SEC:
            seg = x[rs:re_].astype(np.float64)
            labelled.append([rs, re_, None, None, float(np.sqrt(np.mean(seg * seg)))])
            continue
        for s, e, emb in _subregions(m, th, x, rs, re_):
            seg = x[s:e].astype(np.float64)
            loud = float(np.sqrt(np.mean(seg * seg)))
            best, best_sim = None, -1.0
            for v in voices:
                sim = cosine(emb, v.centroid)
                if sim > best_sim:
                    best, best_sim = v, sim
            if best is None or best_sim < th.same:
                best = LocalVoice(len(voices))
                voices.append(best)
            best.embeddings.append(emb)
            best.seconds += (e - s) / SAMPLE_RATE
            best.loudness = max(best.loudness, loud)
            labelled.append([s, e, best.index, emb, loud])
    if not voices:
        return [], []
    for i, r in enumerate(labelled):
        if r[2] is not None:
            continue
        prev = next((labelled[j] for j in range(i - 1, -1, -1) if labelled[j][2] is not None), None)
        nxt = next((labelled[j] for j in range(i + 1, len(labelled)) if labelled[j][2] is not None), None)
        cands = [c for c in (prev, nxt) if c is not None]
        if len(cands) == 2 and cands[0][2] != cands[1][2] and (r[1] - r[0]) >= SAMPLE_RATE // 2:
            emb = m.embed(x[r[0]:r[1]])
            r[2] = max(cands, key=lambda c: cosine(emb, voices[c[2]].centroid))[2]
        else:
            r[2] = min(cands, key=lambda c: max(0, r[0] - c[1], c[0] - r[1]))[2]
    turns: list[Turn] = []
    for s, e, local, emb, loud in labelled:
        if turns and turns[-1].local == local and s - turns[-1].end < SAMPLE_RATE * 1.5:
            turns[-1].end = e
            turns[-1].loudness = max(turns[-1].loudness, loud)
        else:
            turns.append(Turn(s, e, local, loud, emb))
    return turns, voices


# ======================================================================= люди и отпечатки
def vec(v) -> list[float]:
    return [round(float(t), 5) for t in v]


def people_vectors(conn) -> list[dict]:
    return conn.execute(
        "SELECT id, name, is_owner, ignored, centroid FROM people WHERE centroid IS NOT NULL").fetchall()


def best_person(conn, emb: np.ndarray) -> tuple[dict | None, float]:
    best, best_sim = None, -1.0
    for p in people_vectors(conn):
        if len(p["centroid"]) != len(emb):
            continue  # отпечаток другой модели
        sim = cosine(emb, p["centroid"])
        if sim > best_sim:
            best, best_sim = p, sim
    return best, best_sim


def recompute_centroid(conn, person_id: int):
    rows = conn.execute(
        """SELECT embedding FROM voiceprints WHERE person_id = %s AND backend = %s
           ORDER BY confirmed DESC, id DESC LIMIT 60""", (person_id, backend_name())).fetchall()
    if not rows:
        conn.execute("UPDATE people SET centroid = NULL, samples = 0 WHERE id = %s", (person_id,))
        return
    c = np.mean([np.asarray(r["embedding"], dtype=np.float32) for r in rows], axis=0)
    c = c / (np.linalg.norm(c) + 1e-9)
    conn.execute("UPDATE people SET centroid = %s, samples = %s, updated_at = now() WHERE id = %s",
                 (vec(c), len(rows), person_id))


def add_voiceprint(conn, person_id: int, emb: np.ndarray, seconds: float, confirmed: bool = False,
                   segment_id: int | None = None):
    conn.execute(
        """INSERT INTO voiceprints (person_id, embedding, seconds, backend, confirmed, segment_id)
           VALUES (%s, %s, %s, %s, %s, %s)""",
        (person_id, vec(emb), round(seconds, 2), backend_name(), confirmed, segment_id))
    # храним не больше 200 отпечатков на человека: старые неподтверждённые уходят первыми
    conn.execute(
        """DELETE FROM voiceprints WHERE id IN (
               SELECT id FROM voiceprints WHERE person_id = %s
               ORDER BY confirmed DESC, id DESC OFFSET 200)""", (person_id,))
    recompute_centroid(conn, person_id)


# ======================================================================= настройки приватности
def _setting(conn, key: str, default: str = "1") -> str:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (key,)).fetchone()
    return row["value"] if row else default


def enabled(conn) -> bool:
    """«Узнавать голоса» — главный выключатель: без него кусок просто расшифровывается."""
    return _setting(conn, "voices.enabled") == "1"


def others_enabled(conn) -> bool:
    """«Запоминать голоса собеседников»: выключено — узнаём только владельца и тех, кого он назвал."""
    return _setting(conn, "voices.others") == "1"


def has_owner(conn) -> bool:
    return conn.execute("SELECT 1 FROM people WHERE is_owner").fetchone() is not None


def purge_unnamed(conn) -> int:
    """Забыть все безымянные голоса (кроме владельца). Возвращает, сколько забыто."""
    rows = conn.execute("SELECT id FROM people WHERE name IS NULL AND NOT is_owner").fetchall()
    for r in rows:
        forget_person(conn, r["id"])
    return len(rows)


# ======================================================================= узнавание
def new_unknown(conn, emb: np.ndarray, seconds: float, loudness: float) -> dict:
    number = conn.execute("SELECT coalesce(max(number), 0) + 1 AS n FROM people").fetchone()["n"]
    p = conn.execute(
        """INSERT INTO people (number, centroid, samples, speech_sec, loudness, first_seen, last_seen)
           VALUES (%s, %s, 0, 0, %s, now(), now()) RETURNING id, name, is_owner, ignored, centroid""",
        (number, vec(emb), loudness)).fetchone()
    add_voiceprint(conn, p["id"], emb, seconds)
    return p


def save_sample(person_id: int, pcm16: np.ndarray):
    """Короткая запись голоса (до 6 с) — чтобы узнать человека на слух в «Кто это?»."""
    VOICE_DIR.mkdir(parents=True, exist_ok=True)
    clip = pcm16[: SAMPLE_RATE * 6]
    tmp = VOICE_DIR / f"{person_id}.part"
    with wave.open(str(tmp), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(clip.astype(np.int16).tobytes())
    tmp.replace(sample_path(person_id))


def sample_path(person_id: int) -> Path:
    return VOICE_DIR / f"{person_id}.wav"


def identify(conn, voices: list[LocalVoice], pcm: bytes, turns: list[Turn]) -> dict[int, tuple[int | None, float]]:
    """Голоса куска -> люди. Возвращает {локальный номер: (person_id или None, сходство)}.

    Похож на известного (≥ maybe) — подписываем им; уверенно похож и говорил ≥ 2 с — дообучаем.
    Незнакомый голос, говоривший ≥ 2 с, становится «Голосом N» — если разрешено запоминать собеседников
    (или владелец ещё не найден: иначе его не из кого будет выбрать)."""
    th = thresholds()
    x = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16)
    allow_new = others_enabled(conn) or not has_owner(conn)
    out: dict[int, tuple[int | None, float]] = {}
    for v in voices:
        emb = v.centroid
        person, sim = best_person(conn, emb)
        longest = max((t for t in turns if t.local == v.index), key=lambda t: t.end - t.start, default=None)
        if person is not None and sim >= th.maybe:
            pid = person["id"]
            if sim >= th.learn and v.seconds >= MIN_NEW_VOICE_SEC:
                add_voiceprint(conn, pid, emb, v.seconds)
        elif v.seconds >= MIN_NEW_VOICE_SEC and allow_new:
            person = new_unknown(conn, emb, v.seconds, v.loudness)
            pid, sim = person["id"], 1.0
        else:
            out[v.index] = (None, max(sim, 0.0))
            continue
        if longest is not None and (longest.end - longest.start) >= 2 * SAMPLE_RATE \
                and not sample_path(pid).exists():
            try:
                save_sample(pid, x[longest.start:longest.end])
            except OSError:
                log.warning("cannot save voice sample for person %s", pid)
        conn.execute(
            """UPDATE people SET speech_sec = speech_sec + %s, last_seen = now(),
                   first_seen = coalesce(first_seen, now()),
                   loudness = CASE WHEN loudness IS NULL THEN %s ELSE loudness * 0.9 + %s * 0.1 END
               WHERE id = %s""", (round(v.seconds, 1), v.loudness, v.loudness, pid))
        out[v.index] = (pid, sim)
    return out


# ======================================================================= голос владельца по кнопке
OWNER_DIR = VOICE_DIR / "owner"


def queue_owner_sample(pcm: bytes):
    """Gateway: звук вопроса по кнопке (это всегда владелец) — в очередь на дообучение (в gateway нет модели)."""
    if len(pcm) < SAMPLE_RATE * 2 * 2:  # меньше 2 с — мало
        return
    OWNER_DIR.mkdir(parents=True, exist_ok=True)
    files = sorted(OWNER_DIR.glob("*.pcm"))
    for old in files[:-20]:  # очередь не растёт, если STT долго не работал
        old.unlink(missing_ok=True)
    tmp = OWNER_DIR / f"{time.time_ns()}.part"
    tmp.write_bytes(pcm[: SAMPLE_RATE * 2 * 30])
    tmp.replace(tmp.with_suffix(".pcm"))


def owner_embedding(pcm: bytes) -> tuple[np.ndarray | None, float]:
    """Отпечаток по речевым отрезкам вопроса (паузы и шум отбрасываются)."""
    x = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16)
    regions = speech_regions(x, min_ms=300)
    speech = np.concatenate([x[s:e] for s, e in regions]) if regions else np.zeros(0, dtype=np.int16)
    if len(speech) < SAMPLE_RATE * 1.5 and len(x) and float(np.sqrt(np.mean(x.astype(np.float64) ** 2))) > 300:
        speech = x  # говорили без пауз: фон не отличить от речи, берём весь вопрос
    sec = len(speech) / SAMPLE_RATE
    if sec < 1.5:
        return None, sec
    return model().embed(speech), sec


def enroll_owner(conn) -> int:
    """STT: дообучить «Я» по вопросам с кнопки. Владельца ещё нет — им становится похожий голос
    или новый человек. Возвращает число учтённых вопросов."""
    if not OWNER_DIR.exists():
        return 0
    done = 0
    for f in sorted(OWNER_DIR.glob("*.pcm"))[:10]:
        try:
            pcm = f.read_bytes()
            emb, sec = owner_embedding(pcm)
            if emb is not None and enabled(conn):
                owner = conn.execute("SELECT id FROM people WHERE is_owner").fetchone()
                if owner is None:
                    person, sim = best_person(conn, emb)
                    if person is not None and sim >= thresholds().match:
                        pid = person["id"]
                    else:
                        number = conn.execute("SELECT coalesce(max(number), 0) + 1 AS n FROM people").fetchone()["n"]
                        pid = conn.execute(
                            """INSERT INTO people (number, first_seen, last_seen) VALUES (%s, now(), now())
                               RETURNING id""", (number,)).fetchone()["id"]
                    conn.execute("UPDATE people SET is_owner = true, ignored = false, updated_at = now() WHERE id = %s",
                                 (pid,))
                    confirm_person(conn, pid)
                    log.info("voices: owner found by button question (person %s)", pid)
                else:
                    pid = owner["id"]
                    row = conn.execute("SELECT centroid, samples FROM people WHERE id = %s", (pid,)).fetchone()
                    # кнопку мог нажать и кто-то другой: чужой голос «Я» не портит
                    if row["centroid"] and row["samples"] >= 3 and len(row["centroid"]) == len(emb) \
                            and cosine(emb, row["centroid"]) < thresholds().maybe:
                        f.unlink(missing_ok=True)
                        continue
                add_voiceprint(conn, pid, emb, sec, confirmed=True)
                if not sample_path(pid).exists():
                    x = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16)
                    save_sample(pid, x)
                done += 1
        except Unavailable:
            break
        except Exception:  # noqa: BLE001
            log.exception("voices: owner sample %s failed", f.name)
        f.unlink(missing_ok=True)
    return done


def embed_span(pcm: bytes, start: float, end: float) -> np.ndarray | None:
    """Отпечаток отдельной фразы (для правки «эта фраза — другого человека»)."""
    if end - start < MIN_EMBED_SEC:
        return None
    x = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16)
    a = int(max(0.0, start) * SAMPLE_RATE)
    b = int(min(end, start + 12.0) * SAMPLE_RATE)
    if b - a < MIN_EMBED_SEC * SAMPLE_RATE:
        return None
    return model().embed(x[a:b])


# ======================================================================= правки пользователя
def merge_people(conn, source: int, target: int):
    """Два голоса — один человек: переносим отпечатки и фразы."""
    if source == target:
        return
    with conn.transaction():
        conn.execute("UPDATE voiceprints SET person_id = %s WHERE person_id = %s", (target, source))
        conn.execute("UPDATE segments SET person_id = %s WHERE person_id = %s", (target, source))
        conn.execute("UPDATE tasks SET person_id = %s WHERE person_id = %s", (target, source))
        conn.execute("UPDATE person_suggestions SET person_id = %s WHERE person_id = %s", (target, source))
        src = conn.execute("SELECT speech_sec, first_seen, last_seen, name, is_owner FROM people WHERE id = %s",
                           (source,)).fetchone()
        if src:
            conn.execute("DELETE FROM people WHERE id = %s", (source,))
            conn.execute(
                """UPDATE people SET speech_sec = speech_sec + %s,
                       first_seen = least(first_seen, %s), last_seen = greatest(last_seen, %s),
                       name = coalesce(name, %s), is_owner = is_owner OR %s, updated_at = now() WHERE id = %s""",
                (src["speech_sec"], src["first_seen"], src["last_seen"], src["name"], src["is_owner"], target))
        recompute_centroid(conn, target)
    old = sample_path(source)
    try:
        if old.exists() and not sample_path(target).exists():
            old.replace(sample_path(target))
        elif old.exists():
            old.unlink()
    except OSError:
        log.warning("cannot move voice sample %s -> %s", source, target)


def consolidate(conn) -> int:
    """Склеивает голоса, которые оказались одним человеком (по мере накопления отпечатков).
    Двух названных разными именами людей не склеивает никогда."""
    th = thresholds()
    people = conn.execute(
        """SELECT id, name, is_owner, centroid, speech_sec FROM people
           WHERE centroid IS NOT NULL ORDER BY is_owner DESC, (name IS NOT NULL) DESC, speech_sec DESC"""
    ).fetchall()
    gone: set[int] = set()
    for i, a in enumerate(people):
        if a["id"] in gone:
            continue
        for b in people[i + 1:]:
            if b["id"] in gone or len(a["centroid"]) != len(b["centroid"]):
                continue
            if (a["name"] or a["is_owner"]) and (b["name"] or b["is_owner"]):
                continue
            if cosine(a["centroid"], b["centroid"]) >= th.merge:
                merge_people(conn, b["id"], a["id"])  # a — названный или говоривший дольше
                gone.add(b["id"])
    return len(gone)


def assign_segment(conn, segment_id: int, person_id: int):
    """Правка вручную: эта фраза — этого человека. Её отпечаток уходит к нему как подтверждённый."""
    row = conn.execute("SELECT embedding, person_id, started_at, ended_at FROM segments WHERE id = %s",
                       (segment_id,)).fetchone()
    if row is None:
        return
    conn.execute("UPDATE segments SET person_id = %s, voice_score = 1 WHERE id = %s", (person_id, segment_id))
    conn.execute("DELETE FROM voiceprints WHERE segment_id = %s", (segment_id,))
    if row["embedding"]:
        sec = max(1.0, (row["ended_at"] - row["started_at"]).total_seconds())
        add_voiceprint(conn, person_id, np.asarray(row["embedding"], dtype=np.float32), sec, confirmed=True,
                       segment_id=segment_id)
    if row["person_id"] and row["person_id"] != person_id:
        recompute_centroid(conn, row["person_id"])


def confirm_person(conn, person_id: int):
    """Имя подтверждено пользователем: отпечатки человека становятся подтверждёнными."""
    conn.execute("UPDATE voiceprints SET confirmed = true WHERE person_id = %s", (person_id,))


def suggest_owner(conn) -> dict | None:
    """«Это вы?»: владелец кулона говорит громче всех (микрофон у него на груди) и есть почти везде."""
    if has_owner(conn):
        return None
    return conn.execute(
        """SELECT p.id, p.number, p.speech_sec, p.loudness,
                  (SELECT count(DISTINCT s.conversation_id) FROM segments s WHERE s.person_id = p.id) AS conversations
           FROM people p
           WHERE p.name IS NULL AND NOT p.ignored AND p.speech_sec >= 60
           ORDER BY coalesce(p.loudness, 0) * sqrt(p.speech_sec) DESC LIMIT 1""").fetchone()


def forget_person(conn, person_id: int):
    """Забыть голос: отпечатки, запись голоса и подписи фраз. Сами фразы остаются без подписи."""
    conn.execute("UPDATE segments SET person_id = NULL, voice_score = NULL, embedding = NULL WHERE person_id = %s",
                 (person_id,))
    conn.execute("DELETE FROM people WHERE id = %s", (person_id,))
    try:
        sample_path(person_id).unlink(missing_ok=True)
    except OSError:
        log.warning("cannot delete voice sample %s", person_id)


def forget_all(conn) -> int:
    """Забыть все голоса, включая владельца."""
    rows = conn.execute("SELECT id FROM people").fetchall()
    conn.execute("UPDATE segments SET person_id = NULL, voice_score = NULL, embedding = NULL "
                 "WHERE person_id IS NOT NULL OR embedding IS NOT NULL")
    conn.execute("DELETE FROM people")
    for r in rows:
        try:
            sample_path(r["id"]).unlink(missing_ok=True)
        except OSError:
            pass
    return len(rows)


def label(p: dict | None, owner_word: str = "Я", voice_word: str = "Голос") -> str:
    """Подпись говорящего: имя, «Я» для владельца, иначе «Голос N»."""
    if not p or p.get("number") is None and not p.get("name") and not p.get("is_owner"):
        return "?"
    if p.get("is_owner"):
        return p.get("name") or owner_word
    return p.get("name") or f"{voice_word} {p.get('number')}"
