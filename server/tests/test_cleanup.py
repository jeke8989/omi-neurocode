"""Чистая расшифровка: уборка сразу после распознавания и исправление по смыслу, без переписывания."""
from datetime import datetime, timedelta, timezone

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)
from tests.test_voices import Asgi


def test_tidy_removes_recognition_junk():
    from neuro.cleanup import tidy
    assert tidy(" Субтитры сделал DimaTorzok") is None
    assert tidy("Продолжение следует...") is None
    assert tidy("[музыка]") is None
    assert tidy("Э-э...") is None
    assert tidy("Ну, э-э, давай завтра в три") == "Ну, давай завтра в три"
    assert tidy("[Музыка] Привет, как дела?") == "Привет, как дела?"
    assert tidy("да да да да да да") == "да, да"
    assert tidy("Я не знаю. Я не знаю. Я не знаю. Пойдём.") == "Я не знаю. Пойдём."
    assert tidy("Встречаемся в три у метро") == "Встречаемся в три у метро"
    assert tidy("Мама мыла раму") == "Мама мыла раму"   # «м» внутри слов не трогаем
    assert tidy("Нужно подготовить отчёт к пятнице, продолжение следует обсудить завтра с командой")


def test_acceptable_rejects_rewrites():
    from neuro.cleanup import acceptable
    assert acceptable("встретимся с дашей в пятницу в кафе пушкин", "Встретимся с Дашей в пятницу в кафе «Пушкин».")
    assert acceptable("купи молоко", "Купи молоко.")
    assert not acceptable("ну я не знаю короче посмотрим", "Пользователь сомневается и предлагает обсудить позже.")
    assert not acceptable("да", "Да, конечно, я обязательно приду на встречу завтра вечером.")


def test_conversation_cleanup_fixes_and_hides_junk(conn, monkeypatch):  # noqa: F811
    from neuro import cleanup, llm
    conn.execute("TRUNCATE conversations, segments, settings RESTART IDENTITY CASCADE")
    t = datetime.now(timezone.utc) - timedelta(minutes=30)
    cid = conn.execute("""INSERT INTO conversations (device_id, started_at, last_activity, status, title)
                          VALUES ('dev', %s, %s, 'done', 'Обед') RETURNING id""",
                       (t, t + timedelta(minutes=5))).fetchone()["id"]
    texts = ["встретимся с дашей в пятницу", "ну я не знаю короче посмотрим", "угу ага",
             "по телевизору говорят что завтра будет дождь и сильный ветер по всей области весь день"]
    ids = [conn.execute("""INSERT INTO segments (conversation_id, device_id, started_at, ended_at, text)
                           VALUES (%s, 'dev', %s, %s, %s) RETURNING id""",
                        (cid, t + timedelta(seconds=10 * i), t + timedelta(seconds=10 * i + 5), x)).fetchone()["id"]
           for i, x in enumerate(texts)]
    seen = {}

    def fake(conn_, task, system, user, **kw):
        seen["task"], seen["user"] = task, user
        return {"segments": [
            {"id": ids[0], "text": "Встретимся с Дашей в пятницу."},           # исправление — принимаем
            {"id": ids[1], "text": "Собеседник не уверен и предлагает решить позже."},  # пересказ — отбрасываем
            {"id": ids[2], "text": "", "junk": True},                           # короткий мусор — прячем
            {"id": ids[3], "text": "", "junk": True},                           # длинная фраза — не прячем
            {"id": 99999, "text": "чужая фраза"},
        ]}

    monkeypatch.setattr(llm, "configured", lambda: True)
    monkeypatch.setattr(llm, "chat_json", fake)
    res = cleanup.conversation(conn, cid)
    assert seen["task"] == "cleanup" and f"#{ids[0]}" in seen["user"]
    assert res == {"checked": 4, "changed": 1, "junk": 1}
    rows = {r["id"]: r for r in conn.execute("SELECT id, text, raw_text, junk, cleaned FROM segments").fetchall()}
    assert rows[ids[0]]["text"] == "Встретимся с Дашей в пятницу." and rows[ids[0]]["raw_text"] == texts[0]
    assert rows[ids[1]]["text"] == texts[1] and rows[ids[1]]["raw_text"] is None
    assert rows[ids[2]]["junk"] and not rows[ids[3]]["junk"]
    assert all(r["cleaned"] for r in rows.values())
    assert cleanup.conversation(conn, cid)["checked"] == 0   # второй раз модель не зовём

    client = Asgi(__import__("gateway.app", fromlist=["app"]).app)
    segs = client.get(f"/api/conversations/{cid}").json()["segments"]
    assert [s["id"] for s in segs] == [ids[0], ids[1], ids[3]]
    assert segs[0]["raw_text"] == texts[0] and segs[1]["raw_text"] is None
    # выключатель в настройках
    assert client.post("/api/features", json={"transcript_cleanup": False}).json()["transcript_cleanup"] is False
    assert not cleanup.enabled(conn)
