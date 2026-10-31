"""Аудио: формат пакетов от приложения, декодер Opus (libopus через ctypes), WAV, нарезка на куски."""
from __future__ import annotations

import ctypes
import ctypes.util
import io
import os
import struct
import time
import wave
from dataclasses import dataclass
from pathlib import Path

SAMPLE_RATE = 16000
BYTES_PER_MS = SAMPLE_RATE * 2 // 1000  # 32 байта PCM на миллисекунду

# Кодеки, которые сообщает кулон (характеристика 19B10002). Omi CV1 на прошивке 3.x — 21.
CODEC_PCM16 = 0
CODEC_PCM8 = 1
CODEC_OPUS = 20
CODEC_OPUS_FS320 = 21
OPUS_CODECS = (CODEC_OPUS, CODEC_OPUS_FS320)

# ---------------------------------------------------------------------------
# Формат пакета приложение -> сервер (одно бинарное сообщение WebSocket):
#   u8  версия (=1)
#   u8  кодек кулона
#   u64 время захвата первого кадра, мс unix (little-endian) — оно же id пакета
#   далее кадры: u16 длина (LE) + байты кадра Opus
# Версия 2 — то же, но звук записан кулоном без телефона и забран из его памяти позже: он ложится на своё
# время отдельно от живого потока (свой ChunkWriter), id пакета — «<время>_o».
# ---------------------------------------------------------------------------
BATCH_VERSION = 1
BATCH_VERSION_OFFLINE = 2
_HEADER = struct.Struct("<BBQ")
_LEN = struct.Struct("<H")


class BatchError(ValueError):
    pass


@dataclass
class Batch:
    codec: int
    ts_ms: int
    frames: list[bytes]
    offline: bool = False

    @property
    def batch_id(self) -> str:
        return f"{self.ts_ms}_o" if self.offline else f"{self.ts_ms}_a"


def parse_batch(data: bytes) -> Batch:
    if len(data) < _HEADER.size:
        raise BatchError("batch too short")
    version, codec, ts_ms = _HEADER.unpack_from(data, 0)
    if version not in (BATCH_VERSION, BATCH_VERSION_OFFLINE):
        raise BatchError(f"unsupported batch version {version}")
    frames: list[bytes] = []
    off = _HEADER.size
    while off < len(data):
        if off + _LEN.size > len(data):
            raise BatchError("truncated frame header")
        (n,) = _LEN.unpack_from(data, off)
        off += _LEN.size
        if off + n > len(data):
            raise BatchError("truncated frame")
        frames.append(bytes(data[off:off + n]))
        off += n
    return Batch(codec=codec, ts_ms=ts_ms, frames=frames, offline=version == BATCH_VERSION_OFFLINE)


def build_batch(codec: int, ts_ms: int, frames: list[bytes], offline: bool = False) -> bytes:
    out = bytearray(_HEADER.pack(BATCH_VERSION_OFFLINE if offline else BATCH_VERSION, codec, ts_ms))
    for f in frames:
        out += _LEN.pack(len(f))
        out += f
    return bytes(out)


# ---------------------------------------------------------------------------
# libopus
# ---------------------------------------------------------------------------
_lib = None
OPUS_APPLICATION_RESTRICTED_LOWDELAY = 2051
_MAX_FRAME_SAMPLES = 1920  # 120 мс при 16 кГц — максимум для Opus


class OpusError(RuntimeError):
    pass


def _opus():
    global _lib
    if _lib is not None:
        return _lib
    name = ctypes.util.find_library("opus") or "libopus.so.0"
    lib = ctypes.CDLL(name)
    lib.opus_decoder_create.restype = ctypes.c_void_p
    lib.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
    lib.opus_decode.restype = ctypes.c_int
    lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32,
                                ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
    lib.opus_decoder_destroy.restype = None
    lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
    lib.opus_encoder_create.restype = ctypes.c_void_p
    lib.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
    lib.opus_encode.restype = ctypes.c_int32
    lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.c_int,
                                ctypes.c_char_p, ctypes.c_int32]
    lib.opus_encoder_destroy.restype = None
    lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
    _lib = lib
    return lib


class OpusDecoder:
    def __init__(self, rate: int = SAMPLE_RATE, channels: int = 1):
        lib = _opus()
        err = ctypes.c_int(0)
        self._st = lib.opus_decoder_create(rate, channels, ctypes.byref(err))
        if err.value != 0 or not self._st:
            raise OpusError(f"opus_decoder_create failed: {err.value}")
        self._buf = (ctypes.c_int16 * _MAX_FRAME_SAMPLES)()

    def decode(self, packet: bytes) -> bytes:
        n = _opus().opus_decode(self._st, packet, len(packet), self._buf, _MAX_FRAME_SAMPLES, 0)
        if n < 0:
            raise OpusError(f"opus_decode error {n}")
        return ctypes.string_at(self._buf, n * 2)

    def close(self):
        if self._st:
            _opus().opus_decoder_destroy(self._st)
            self._st = None

    def __del__(self):  # pragma: no cover
        try:
            self.close()
        except Exception:  # noqa: BLE001
            pass


class OpusEncoder:
    """Нужен только для тестов: кодирует PCM так же, как прошивка кулона."""

    def __init__(self, rate: int = SAMPLE_RATE, channels: int = 1):
        lib = _opus()
        err = ctypes.c_int(0)
        self._st = lib.opus_encoder_create(rate, channels, OPUS_APPLICATION_RESTRICTED_LOWDELAY, ctypes.byref(err))
        if err.value != 0 or not self._st:
            raise OpusError(f"opus_encoder_create failed: {err.value}")

    def encode(self, pcm: bytes) -> bytes:
        samples = len(pcm) // 2
        buf = (ctypes.c_int16 * samples).from_buffer_copy(pcm)
        out = ctypes.create_string_buffer(4000)
        n = _opus().opus_encode(self._st, buf, samples, out, 4000)
        if n < 0:
            raise OpusError(f"opus_encode error {n}")
        return out.raw[:n]

    def close(self):
        if self._st:
            _opus().opus_encoder_destroy(self._st)
            self._st = None


class FrameDecoder:
    """Декодирует кадры одного соединения. Хранит отдельный декодер на каждый кодек."""

    def __init__(self):
        self._opus: OpusDecoder | None = None
        self.bad_frames = 0

    def decode(self, codec: int, frames: list[bytes]) -> bytes:
        if codec in OPUS_CODECS:
            if self._opus is None:
                self._opus = OpusDecoder()
            out = bytearray()
            for f in frames:
                try:
                    out += self._opus.decode(f)
                except OpusError:
                    self.bad_frames += 1
            return bytes(out)
        if codec == CODEC_PCM16:
            return b"".join(frames)
        if codec == CODEC_PCM8:
            # 8 кГц -> 16 кГц простым повтором отсчётов
            raw = b"".join(frames)
            out = bytearray()
            for i in range(0, len(raw) - 1, 2):
                out += raw[i:i + 2] * 2
            return bytes(out)
        raise BatchError(f"unsupported codec {codec}")

    def close(self):
        if self._opus:
            self._opus.close()
            self._opus = None


# ---------------------------------------------------------------------------
# WAV и громкость
# ---------------------------------------------------------------------------
def pcm_to_wav(pcm: bytes, rate: int = SAMPLE_RATE) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()


def speech_ms(pcm: bytes, min_rms: float = 180.0, ratio: float = 2.5) -> int:
    """Сколько миллисекунд в куске похоже на речь.

    Кадры по 20 мс; фон — 10-й перцентиль громкости куска, речь — кадры громче фона в ratio раз
    и не тише min_rms. Ровный шум (машина, кондиционер, тишина) речью не считается.
    """
    import numpy as np

    frame = 20 * SAMPLE_RATE // 1000  # отсчётов в 20 мс
    n = len(pcm) // 2 // frame
    if n == 0:
        return 0
    a = np.frombuffer(pcm[: n * frame * 2], dtype=np.int16).astype(np.float64).reshape(n, frame)
    rms = np.sqrt((a * a).mean(axis=1))
    noise = float(np.percentile(rms, 10))
    return int((rms > max(noise * ratio, min_rms)).sum()) * 20


def voice_ms(pcm: bytes) -> int:
    """Сколько миллисекунд речи в куске — по нейросетевому детектору речи Silero (входит в faster-whisper).

    Громкость плохо отличает речь от шума: в кафе или когда собеседник говорит тише владельца кулона
    его речь не набирает нужной громкости, и кусок раньше выбрасывался целиком. Silero слышит именно
    голос. Если faster-whisper не установлен (тесты, облачная расшифровка без него) — мягкий порог по громкости.
    """
    try:
        import numpy as np
        from faster_whisper.vad import VadOptions, get_speech_timestamps
    except Exception:  # noqa: BLE001
        return speech_ms(pcm, min_rms=120.0, ratio=1.8)
    audio = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16).astype(np.float32) / 32768.0
    try:
        stamps = get_speech_timestamps(audio, VadOptions(threshold=0.35, min_speech_duration_ms=200,
                                                         min_silence_duration_ms=300, speech_pad_ms=0))
    except Exception:  # noqa: BLE001 — другая версия faster-whisper
        return speech_ms(pcm, min_rms=120.0, ratio=1.8)
    return int(sum(t["end"] - t["start"] for t in stamps) * 1000 / SAMPLE_RATE)


def pcm_rms(pcm: bytes) -> float:
    import numpy as np

    if len(pcm) < 2:
        return 0.0
    a = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16).astype(np.float64)
    return float(np.sqrt(np.mean(a * a)))


# ---------------------------------------------------------------------------
# Нарезка потока на куски для расшифровки
# ---------------------------------------------------------------------------
@dataclass
class FinishedChunk:
    device_id: str
    start_ms: int
    duration_ms: int
    path: str


class ChunkWriter:
    """Пишет PCM одного кулона в файлы по CHUNK_MAX_SEC секунд.

    Файл сначала пишется как <start_ms>.pcm.part, при закрытии переименовывается в .pcm.
    Если сервис упадёт, при старте незакрытые .part подбираются recover_orphans().
    """

    def __init__(self, audio_dir: str, device_id: str, max_sec: float, gap_sec: float):
        self.dir = Path(audio_dir) / device_id
        self.dir.mkdir(parents=True, exist_ok=True)
        self.device_id = device_id
        self.max_bytes = int(max_sec * 1000) * BYTES_PER_MS
        self.gap_ms = int(gap_sec * 1000)
        self._fh = None
        self._path: Path | None = None
        self._start_ms = 0
        self._bytes = 0
        self.last_write = 0.0

    @property
    def open(self) -> bool:
        return self._fh is not None

    def _expected_next_ms(self) -> int:
        return self._start_ms + self._bytes // BYTES_PER_MS

    def write(self, ts_ms: int, pcm: bytes) -> list[FinishedChunk]:
        """Добавить звук, захваченный начиная с ts_ms. Возвращает куски, которые закрылись."""
        done: list[FinishedChunk] = []
        if not pcm:
            return done
        if self._fh is not None:
            expected = self._expected_next_ms()
            # Новый кусок при паузе в потоке или если время прыгнуло назад (например, после смены часов)
            if ts_ms > expected + self.gap_ms or ts_ms < expected - self.gap_ms:
                c = self.finalize()
                if c:
                    done.append(c)
        pos = 0
        while pos < len(pcm):
            if self._fh is None:
                start = ts_ms + (pos // BYTES_PER_MS)
                self._open(start)
            room = self.max_bytes - self._bytes
            piece = pcm[pos:pos + room]
            self._fh.write(piece)
            self._bytes += len(piece)
            pos += len(piece)
            if self._bytes >= self.max_bytes:
                c = self.finalize()
                if c:
                    done.append(c)
        if self._fh is not None:
            self._fh.flush()
            os.fsync(self._fh.fileno())
        self.last_write = time.monotonic()
        return done

    def _open(self, start_ms: int):
        self._start_ms = start_ms
        self._bytes = 0
        self._path = self.dir / f"{start_ms}.pcm.part"
        # если файл с таким именем уже есть (повтор), дописываем в новый
        n = 1
        while self._path.exists() or self._path.with_suffix("").exists():
            self._path = self.dir / f"{start_ms}-{n}.pcm.part"
            n += 1
        self._fh = open(self._path, "wb")

    def finalize(self) -> FinishedChunk | None:
        if self._fh is None:
            return None
        self._fh.close()
        self._fh = None
        part = self._path
        final = part.with_suffix("")  # убираем .part
        if self._bytes == 0:
            part.unlink(missing_ok=True)
            return None
        part.rename(final)
        return FinishedChunk(self.device_id, self._start_ms, self._bytes // BYTES_PER_MS, str(final))


def recover_orphans(audio_dir: str) -> list[FinishedChunk]:
    """Находит .pcm.part, оставшиеся после падения, и закрывает их."""
    out: list[FinishedChunk] = []
    root = Path(audio_dir)
    if not root.exists():
        return out
    for part in root.glob("*/*.pcm.part"):
        final = part.with_suffix("")
        size = part.stat().st_size
        if size == 0:
            part.unlink(missing_ok=True)
            continue
        part.rename(final)
        out.append(chunk_from_path(final))
    return out


def chunk_from_path(path: Path) -> FinishedChunk:
    stem = path.name.split(".")[0]
    start_ms = int(stem.split("-")[0])
    return FinishedChunk(path.parent.name, start_ms, path.stat().st_size // BYTES_PER_MS, str(path))
