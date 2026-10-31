"""Сквозной тест сервера без сети: пакеты Opus -> gateway -> куски -> STT -> разговор -> агент.

Нужна пустая тестовая база Postgres:  TEST_DATABASE_URL=postgresql://... pytest tests/test_pipeline.py
(в CI база поднимается сервисом postgres в .github/workflows/server.yml).
"""
import asyncio
import json
import os
import tempfile
import time
from datetime import datetime, timedelta, timezone

import pytest

AUDIO_DIR = tempfile.mkdtemp(prefix="neuro-audio-")
os.environ.update({
    "DATABASE_URL": os.environ.get("TEST_DATABASE_URL", "postgresql://postgres@127.0.0.1:54329/neuro"),
    "AUDIO_DIR": AUDIO_DIR,
    "API_TOKEN": "test-token",
    "STT_PROVIDER": "fake",
    "CHUNK_MAX_SEC": "30",
    "LLM_API_KEY": "",
    "LLM_MODEL": "",
    "TZ": "Europe/Moscow",
    "SPEAKER_BACKEND": "spectral",
    "SPEAKER_MIN_NEW_SEC": "2",  # синтетические диалоги короткие; в жизни — 8 с
    "VOICE_DIR": tempfile.mkdtemp(prefix="neuro-voices-"),
})

from neuro import db  # noqa: E402
from neuro.audio import CODEC_OPUS_FS320, build_batch  # noqa: E402
from tests.test_audio import opus_frames, tone  # noqa: E402

DEVICE = "AA-BB-CC-DD-EE-FF"


@pytest.fixture(scope="module")
def conn():
    db.wait_and_migrate()
    c = db.connect()
    c.execute("TRUNCATE devices, audio_chunks, conversations, segments, tasks, memories, events, "
              "received_batches, settings, llm_usage, questions, chat_messages, agent_runs, agent_steps, extensions, "
              "locations, places, photos, diary_days, task_exports, people, voiceprints, person_suggestions "
              "RESTART IDENTITY CASCADE")
    yield c
    c.close()


def run(coro):
    return asyncio.run(coro)


def test_full_pipeline(conn, monkeypatch):
    from gateway import app as gw
    from stt import worker as stt
    from agent import worker as agent
    from neuro import llm

    now_ms = int(time.time() * 1000)
    t0 = now_ms - 10 * 60 * 1000  # «запись» 10 минут назад, чтобы разговор уже считался законченным
    frames = opus_frames(tone(1.0))

    async def gateway_part():
        aconn = await gw.aconnect()
        await aconn.execute("INSERT INTO devices (device_id) VALUES (%s) ON CONFLICT DO NOTHING", (DEVICE,))
        from neuro.audio import FrameDecoder
        dec = FrameDecoder()
        acks = []
        for i in range(3):
            acks.append(await gw.handle_batch(aconn, DEVICE, dec, build_batch(CODEC_OPUS_FS320, t0 + i * 1000, frames)))
        dup = await gw.handle_batch(aconn, DEVICE, dec, build_batch(CODEC_OPUS_FS320, t0, frames))
        bad = await gw.handle_batch(aconn, DEVICE, dec, b"\x01")
        ev_cmd = await gw.handle_text(aconn, DEVICE, json.dumps(
            {"type": "event", "id": f"{t0 + 500}_e", "event": "button", "value": 1, "ts": t0 + 500}))
        ev_rem = await gw.handle_text(aconn, DEVICE, json.dumps(
            {"type": "event", "id": f"{t0 + 2900}_e", "event": "button", "value": 2, "ts": t0 + 2900}))
        ev_dup = await gw.handle_text(aconn, DEVICE, json.dumps(
            {"type": "event", "id": f"{t0 + 2900}_e", "event": "button", "value": 2, "ts": t0 + 2900}))
        hello = await gw.handle_text(aconn, DEVICE, json.dumps({"type": "hello", "info": {"battery": 80}}))
        # закрываем кусок, как это сделал бы idle_finalizer
        chunk = gw.writers[DEVICE].finalize()
        await gw.register_chunks(aconn, [chunk])
        # повторная регистрация того же файла (reconcile) не создаёт дубль
        await gw.reconcile_files(recover=False)
        await aconn.close()
        return acks, dup, bad, ev_cmd, ev_rem, ev_dup, hello

    acks, dup, bad, ev_cmd, ev_rem, ev_dup, hello = run(gateway_part())
    assert [a["id"] for a in acks] == [f"{t0 + i * 1000}_a" for i in range(3)]
    assert all(a["type"] == "ack" and a["frames"] == 50 for a in acks)
    assert dup == {"type": "ack", "id": f"{t0}_a", "duplicate": True}
    assert bad["type"] == "error"
    assert ev_cmd == {"type": "ack", "id": f"{t0 + 500}_e"} and ev_rem["type"] == "ack" and ev_dup["type"] == "ack"
    assert hello["type"] == "hello"

    chunks = conn.execute("SELECT * FROM audio_chunks").fetchall()
    assert len(chunks) == 1 and chunks[0]["duration_ms"] == 3000 and chunks[0]["status"] == "pending"
    assert conn.execute("SELECT count(*) AS n FROM events").fetchone()["n"] == 2
    assert conn.execute("SELECT info FROM devices").fetchone()["info"]["battery"] == 80

    # --- STT ---
    fake = stt.FakeSTT()
    claimed = conn.execute(stt.CLAIM_SQL).fetchone()
    assert claimed["id"] == chunks[0]["id"] and claimed["attempts"] == 1
    assert conn.execute(stt.CLAIM_SQL).fetchone() is None  # больше нечего брать
    stt.process(conn, fake, claimed)
    conv = conn.execute("SELECT * FROM conversations").fetchone()
    assert conv["status"] == "open"
    seg = conn.execute("SELECT * FROM segments").fetchone()
    assert "Тестовая расшифровка" in seg["text"] and seg["conversation_id"] == conv["id"]
    assert abs((seg["started_at"] - datetime.fromtimestamp(t0 / 1000, timezone.utc)).total_seconds()) < 0.01

    # второй кусок через 30 с — тот же разговор; через 10 минут — новый
    def add_chunk(start_ms):
        path = os.path.join(AUDIO_DIR, DEVICE, f"{start_ms}.pcm")
        with open(path, "wb") as f:
            f.write(b"\x00\x00" * 16000)
        conn.execute(gw.INSERT_CHUNK, (DEVICE, start_ms, 1000, path))
        stt.process(conn, fake, conn.execute(stt.CLAIM_SQL).fetchone())

    add_chunk(t0 + 33_000)
    assert conn.execute("SELECT count(*) AS n FROM conversations").fetchone()["n"] == 1
    add_chunk(t0 + 120_000)
    convs = conn.execute("SELECT id, started_at, last_activity FROM conversations ORDER BY id").fetchall()
    assert len(convs) == 1  # 2 минуты от конца — ещё в пределах CONVERSATION_GAP_SEC=120

    add_chunk(t0 + 8 * 60 * 1000)
    assert conn.execute("SELECT count(*) AS n FROM conversations").fetchone()["n"] == 2

    # --- Агент без LLM: заголовок из первых слов ---
    conn.execute("UPDATE conversations SET last_activity = now() - interval '9 minutes'")
    agent.summarize_conversations(conn)  # голоса разговора ещё не решены — итоги ждут
    assert conn.execute("SELECT count(*) AS n FROM conversations WHERE status = 'done'").fetchone()["n"] == 0
    stt.finalize_voices(conn)  # STT решил, кто говорил (тоны — без голосов)
    assert conn.execute("SELECT bool_and(voices_done) AS ok FROM conversations").fetchone()["ok"]
    agent.summarize_conversations(conn)
    rows = conn.execute("SELECT status, title, summary FROM conversations ORDER BY id").fetchall()
    assert [r["status"] for r in rows] == ["done", "done"]
    assert rows[0]["title"].startswith("Тестовая расшифровка") and rows[0]["summary"] is None

    # --- Агент с LLM (подменяем ответ модели) ---
    monkeypatch.setattr(llm, "configured", lambda: True)

    def fake_llm(conn_, task, system, user, **kw):
        assert task in ("summary", "remember", "ask")
        assert "Спикер 1:" in user or task == "ask"
        if system.startswith(agent.SUMMARY_SYSTEM):
            assert "по-русски" in system  # язык приложения по умолчанию
            return {"title": "Отчёт к пятнице", "summary": "Договорились подготовить отчёт.",
                    "tasks": [{"text": "Подготовить отчёт", "due": "пятница", "who": "Владелец", "kind": "mine",
                               "confidence": 0.9, "evidence": "подготовлю отчёт к пятнице", "target": "notion"},
                              {"text": "Починить синхронизацию", "due": "null", "target": "github", "confidence": 0.8,
                               "evidence": "починю синхронизацию"},
                              # обсуждение, а не договорённость — не задача
                              {"text": "Посмотреть билеты", "confidence": 0.3, "evidence": "может, посмотрим билеты"},
                              # дубль в том же ответе — одна задача
                              {"text": "Подготовить  отчёт", "confidence": 0.9, "evidence": "подготовлю отчёт"},
                              # сомнительно — в «Предложенные»
                              {"text": "Позвонить в банк", "confidence": 0.55, "evidence": "надо бы в банк позвонить"},
                              {"text": ""}],
                    "memories": ["Отчёт нужен к пятнице"]}
        if system.startswith(agent.REMEMBER_SYSTEM):
            return {"memory": "Отчёт к пятнице", "tasks": []}
        return {"answer": "Создать задачу по отчёту", "tasks": [{"text": "Задача из команды"}], "memories": []}

    monkeypatch.setattr(llm, "chat_json", fake_llm)

    def fake_agent_chat(conn_, task, messages, tools=None, **kw):  # офлайн-вопрос идёт через агента
        if not any(m["role"] == "tool" for m in messages):
            return {"content": "", "tool_calls": [{"id": "t1", "type": "function", "function": {
                "name": "create_task", "arguments": '{"text": "Задача из команды"}'}}]}
        return {"content": "Создать задачу по отчёту"}

    monkeypatch.setattr(llm, "chat", fake_agent_chat)
    first = convs[0]["id"]
    conn.execute(f"UPDATE conversations SET status = 'open', updated_at = now() - interval '1 hour' WHERE id = {first}")
    agent.summarize_conversations(conn)
    c1 = conn.execute(f"SELECT * FROM conversations WHERE id = {first}").fetchone()
    assert c1["status"] == "done" and c1["title"] == "Отчёт к пятнице"
    tasks = conn.execute("SELECT text, due, target, source FROM tasks WHERE status = 'open' ORDER BY id").fetchall()
    assert [(t["text"], t["due"], t["target"]) for t in tasks] == [
        ("Подготовить отчёт", "пятница", "notion"), ("Починить синхронизацию", None, "github")]
    assert [r["text"] for r in conn.execute("SELECT text FROM tasks WHERE status = 'suggested'").fetchall()] == [
        "Позвонить в банк"]
    # повторный пересчёт не дублирует задачи
    conn.execute(f"UPDATE conversations SET status = 'open', updated_at = now() - interval '1 hour' WHERE id = {first}")
    agent.summarize_conversations(conn)
    assert conn.execute("SELECT count(*) AS n FROM tasks").fetchone()["n"] == 3  # 2 задачи + 1 предложенная

    # --- Кнопка: события ещё «свежие» — не трогаем; потом обрабатываем ---
    agent.process_events(conn)
    assert conn.execute("SELECT count(*) AS n FROM events WHERE status = 'pending'").fetchone()["n"] == 2
    conn.execute("UPDATE events SET created_at = now() - interval '5 minutes'")
    agent.process_events(conn)
    evs = conn.execute("SELECT value, status, result FROM events ORDER BY value").fetchall()
    assert [(e["value"], e["status"]) for e in evs] == [(1, "done"), (2, "done")]
    assert evs[0]["result"] == "Создать задачу по отчёту" and evs[1]["result"] == "Отчёт к пятнице"
    assert conn.execute("SELECT count(*) AS n FROM memories WHERE source = 'button'").fetchone()["n"] == 1
    assert conn.execute("SELECT count(*) AS n FROM tasks WHERE source = 'agent'").fetchone()["n"] == 1
    assert conn.execute("SELECT via FROM questions").fetchone()["via"] == "offline"

    # --- Экспорт (подменяем HTTP) ---
    monkeypatch.setattr(agent.config, "AUTO_EXPORT", True)
    monkeypatch.setattr(agent.config, "NOTION_TOKEN", "x")
    monkeypatch.setattr(agent.config, "NOTION_DATABASE_ID", "db")
    sent = []

    class Resp:
        status_code = 200

        def __init__(self, url):
            self.url = url

        def json(self):
            return {"url": "https://notion.so/page", "html_url": "https://github.com/x/y/issues/1"}

    monkeypatch.setattr(agent.requests, "post", lambda url, **k: (sent.append((url, k["json"])), Resp(url))[1])
    agent.export_tasks(conn)
    assert len(sent) == 3 and all("notion.com" in u for u, _ in sent)  # GitHub не настроен -> всё в Notion
    # предложенная задача никуда не уходит, пока её не приняли
    assert conn.execute("SELECT count(*) AS n FROM tasks WHERE exported_at IS NULL AND status = 'open'"
                        ).fetchone()["n"] == 0
    assert conn.execute("SELECT exported_at FROM tasks WHERE status = 'suggested'").fetchone()["exported_at"] is None

    # --- Уборка старого аудио ---
    monkeypatch.setattr(agent.config, "KEEP_AUDIO_DAYS", 0)
    agent.cleanup(conn)
    assert conn.execute("SELECT count(*) AS n FROM audio_chunks WHERE path IS NOT NULL").fetchone()["n"] == 0
    assert not any(f.endswith(".pcm") for _, _, fs in os.walk(AUDIO_DIR) for f in fs)
