"""Голоса выключены (VOICES=0 по умолчанию): сервер не узнаёт говорящих, ассистент не видит «Людей по голосу»."""
import importlib

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)


def test_voices_off_by_default(conn, monkeypatch):  # noqa: F811
    from neuro import agent_core, config, voices
    from stt import worker as stt
    monkeypatch.setattr(config, "VOICES", False)
    conn.execute("INSERT INTO settings (key, value) VALUES ('voices.enabled', '1') "
                 "ON CONFLICT (key) DO UPDATE SET value = '1'")
    assert not voices.enabled(conn) and not voices.others_enabled(conn)  # настройка в приложении не включает

    cid = conn.execute("INSERT INTO conversations (device_id, started_at, last_activity) "
                       "VALUES ('d', now(), now()) RETURNING id").fetchone()["id"]
    conn.execute("UPDATE conversations SET voices_done = false WHERE id = %s", (cid,))
    stt.finalize_voices(conn)  # итоги не ждут голосов
    assert conn.execute("SELECT voices_done FROM conversations WHERE id = %s", (cid,)).fetchone()["voices_done"]

    try:
        names = {t["function"]["name"] for t in importlib.reload(agent_core).BUILTIN_TOOLS}
        assert "people" not in names and "person_conversations" not in names and "send_task" in names
    finally:
        monkeypatch.undo()
        importlib.reload(agent_core)  # остальным тестам — как было (VOICES=1 в окружении тестов)
