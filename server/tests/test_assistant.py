"""Кнопка: определение конца вопроса и ответ на голосовой вопрос."""
import math
import os
import random
import struct

import pytest

from tests.test_pipeline import DEVICE, conn  # noqa: F401  (фикстура базы)


def noise(seconds, amp=60):
    rnd = random.Random(1)
    n = int(16000 * seconds)
    return struct.pack(f"<{n}h", *(int(rnd.uniform(-amp, amp)) for _ in range(n)))


def voice(seconds, amp=5000):
    n = int(16000 * seconds)
    return struct.pack(f"<{n}h", *(int(amp * math.sin(2 * math.pi * 220 * i / 16000) *
                                      (0.6 + 0.4 * math.sin(2 * math.pi * 3 * i / 16000))) for i in range(n)))


def feed_until_end(ep, pcm, step=640):
    for i in range(0, len(pcm), step):
        if ep.feed(pcm[i:i + step]):
            return i + step
    return None


def test_endpointer_detects_end_of_question():
    from neuro.assistant import Endpointer
    ep = Endpointer(end_silence_ms=1200)
    audio = noise(0.4) + voice(2.0) + noise(3.0)
    end = feed_until_end(ep, audio)
    assert ep.started
    end_s = end / 32000
    assert 3.5 <= end_s <= 3.8, end_s  # 0.4 + 2.0 речи + ~1.2 тишины


def test_endpointer_waits_while_speaking_and_ignores_noise():
    from neuro.assistant import Endpointer
    ep = Endpointer(end_silence_ms=1200)
    # пауза 0.6 с внутри фразы не обрывает вопрос
    assert feed_until_end(ep, noise(0.3) + voice(1.0) + noise(0.6) + voice(1.0)) is None
    # один шум без речи — не «начало вопроса»
    ep2 = Endpointer()
    assert feed_until_end(ep2, noise(3.0, amp=120)) is None and not ep2.started


def test_voice_question_through_agent(conn, monkeypatch):  # noqa: F811
    """Кнопка: звук -> распознавание -> агент быстрого уровня -> ответ для озвучки + запись в чат и историю."""
    import asyncio
    from gateway import app as gw
    from neuro import llm
    monkeypatch.setattr(llm, "configured", lambda: True)
    monkeypatch.setattr(llm, "transcribe", lambda conn_, task, pcm, **kw:
                        {"text": "Создай задачу позвонить Ивану завтра"} if task == "ask_stt" else {})

    def fake_chat(conn_, task, messages, tools=None, reasoning=None, **kw):
        assert task == "ask" and reasoning == "low"
        assert "синтезатор речи" in messages[0]["content"]
        if not any(m["role"] == "tool" for m in messages):
            return {"content": "", "tool_calls": [{"id": "1", "type": "function", "function": {
                "name": "create_task", "arguments": '{"text": "Позвонить Ивану", "due": "завтра"}'}}]}
        return {"content": "Хорошо, создал задачу позвонить Ивану завтра."}

    monkeypatch.setattr(llm, "chat", fake_chat)
    reply = asyncio.run(gw.answer_voice(DEVICE, "q1", voice(2.0)))
    assert reply["answer"].startswith("Хорошо") and reply["level"] == "fast" and not reply["pending"]
    q = conn.execute("SELECT question, answer, via FROM questions WHERE client_id = 'q1'").fetchone()
    assert q["via"] == "voice" and q["question"].startswith("Создай задачу")
    t = conn.execute("SELECT source, due FROM tasks WHERE text = 'Позвонить Ивану'").fetchone()
    assert t["source"] == "agent" and t["due"] == "завтра"
    chans = [m["channel"] for m in conn.execute("SELECT channel FROM chat_messages ORDER BY id").fetchall()]
    assert chans[-2:] == ["voice", "voice"]

    # глубокий вопрос: сразу короткий ответ голосом, работа продолжается в фоне
    monkeypatch.setattr(llm, "transcribe", lambda *a, **k: {"text": "подробно проанализируй мои встречи"})
    monkeypatch.setattr(llm, "chat", lambda *a, **k: {"content": "Готово"})
    reply = asyncio.run(gw.answer_voice(DEVICE, "q2", voice(2.0)))
    assert reply["level"] == "deep" and reply["pending"] and "в чат" in reply["answer"]

    # пустое распознавание -> просьба повторить
    monkeypatch.setattr(llm, "transcribe", lambda *a, **k: {"text": " "})
    assert "повторите" in asyncio.run(gw.answer_voice(DEVICE, "q3", voice(1.0)))["answer"]


def test_model_choice_and_usage(conn, monkeypatch):  # noqa: F811
    from neuro import llm
    assert llm.resolve_model(conn, "summary") == "deepseek/deepseek-v4.1-flash"
    conn.execute("INSERT INTO settings (key, value) VALUES ('model.summary', 'qwen/qwen3.7-flash') "
                 "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")
    assert llm.resolve_model(conn, "summary") == "qwen/qwen3.7-flash"
    llm.record_usage(conn, "summary", "qwen/qwen3.7-flash", {"prompt_tokens": 1_000_000, "completion_tokens": 0})
    llm.record_usage(conn, "ask_stt", "openai/whisper-large-v3-turbo", {}, audio_seconds=3600)
    llm.record_usage(conn, "ask", "anthropic/claude-haiku-5.5", {"prompt_tokens": 10, "cost": 0.5})
    rows = {r["task"]: r["cost"] for r in conn.execute("SELECT task, cost FROM llm_usage").fetchall()}
    assert rows["summary"] == pytest.approx(0.03) and rows["ask_stt"] == pytest.approx(0.011)
    assert rows["ask"] == pytest.approx(0.5)
    conn.execute("DELETE FROM settings")
