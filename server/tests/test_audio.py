"""Тесты аудиочасти: формат пакетов, Opus как в прошивке Omi (16 кГц, кадры 320 отсчётов), нарезка."""
import math
import struct
import tempfile
from pathlib import Path

from neuro.audio import (BYTES_PER_MS, ChunkWriter, FrameDecoder, OpusEncoder, build_batch, parse_batch,
                         recover_orphans, pcm_rms, CODEC_OPUS_FS320)


def tone(seconds, freq=440.0, amp=8000):
    n = int(16000 * seconds)
    return struct.pack(f"<{n}h", *(int(amp * math.sin(2 * math.pi * freq * i / 16000)) for i in range(n)))


def opus_frames(pcm):
    enc = OpusEncoder()
    frames = [enc.encode(pcm[i:i + 640]) for i in range(0, len(pcm) - 639, 640)]  # 320 отсчётов = 20 мс
    enc.close()
    return frames


def test_batch_roundtrip_and_decode():
    pcm = tone(1.0)
    frames = opus_frames(pcm)
    assert len(frames) == 50 and max(len(f) for f in frames) <= 160  # как CODEC_OUTPUT_MAX_BYTES в прошивке
    data = build_batch(CODEC_OPUS_FS320, 1_730_000_000_123, frames)
    b = parse_batch(data)
    assert b.codec == 21 and b.ts_ms == 1_730_000_000_123 and b.frames == frames
    assert b.batch_id == "1730000000123_a"
    dec = FrameDecoder()
    out = dec.decode(b.codec, b.frames)
    assert len(out) == len(pcm)  # 1 секунда -> 32000 байт
    assert dec.bad_frames == 0
    assert pcm_rms(out) > 3000  # тон восстановился, а не тишина
    # битый кадр не роняет декодер
    out2 = dec.decode(21, [b"\xff\xff\xff"] + frames[:2])
    assert len(out2) >= 2 * 640


def test_parse_rejects_garbage():
    import pytest
    from neuro.audio import BatchError
    for bad in (b"", b"\x01\x15", b"\x02" + b"\x00" * 9, build_batch(21, 1, [b"abc"])[:-1]):
        with pytest.raises(BatchError):
            parse_batch(bad)


def test_chunk_writer_rotation_gap_and_recovery():
    with tempfile.TemporaryDirectory() as d:
        w = ChunkWriter(d, "dev1", max_sec=2, gap_sec=3)
        t0 = 1_730_000_000_000
        done = []
        for i in range(5):  # 5 секунд подряд по 1 с
            done += w.write(t0 + i * 1000, b"\x01\x00" * 16000)
        assert [c.duration_ms for c in done] == [2000, 2000]
        assert done[1].start_ms == t0 + 2000
        assert w.open
        # пауза 10 с -> предыдущий кусок закрывается, начинается новый
        done = w.write(t0 + 15000, b"\x01\x00" * 8000)
        assert len(done) == 1 and done[0].start_ms == t0 + 4000 and done[0].duration_ms == 1000
        assert Path(done[0].path).suffix == ".pcm"
        # «падение»: незакрытый .part подбирается при старте
        w._fh.close()
        rec = recover_orphans(d)
        assert len(rec) == 1 and rec[0].start_ms == t0 + 15000 and rec[0].duration_ms == 500
        assert rec[0].device_id == "dev1"
        assert not list(Path(d).glob("*/*.part"))


def test_chunk_writer_time_jump_back():
    with tempfile.TemporaryDirectory() as d:
        w = ChunkWriter(d, "dev2", max_sec=30, gap_sec=3)
        assert w.write(2_000_000, b"\x00\x00" * 16000) == []
        done = w.write(1_000_000, b"\x00\x00" * 16000)  # время прыгнуло назад
        assert len(done) == 1 and done[0].start_ms == 2_000_000
        c = w.finalize()
        assert c.start_ms == 1_000_000 and c.duration_ms == 1000
        assert BYTES_PER_MS == 32
