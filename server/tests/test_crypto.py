"""Шифрование данных в базе: в базе только шифротекст, код видит обычный текст, старое шифруется само."""
from datetime import datetime, timedelta, timezone

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов; DATA_KEY задан)
from tests.test_voices import Asgi


def test_seal_roundtrip_and_kinds(tmp_path):
    from neuro import crypto
    assert crypto.enabled()
    a, b = crypto.seal("Купить молоко"), crypto.seal("Купить молоко")
    assert a.startswith("enc1:") and a != b and crypto.unseal(a) == "Купить молоко"
    d1, d2 = crypto.seal_det("Анна"), crypto.seal_det("Анна")
    assert d1.startswith("encd:") and d1 == d2 and crypto.unseal(d1) == "Анна"   # можно сравнивать в SQL
    assert crypto.seal(a) == a and crypto.seal("") == "" and crypto.seal(None) is None
    assert crypto.unseal(a[:20]) == a[:20]          # обрезанное значение не ломает чтение
    import json
    doc = json.loads(crypto.seal_json([{"title": "Обед"}]))
    assert doc.startswith("encj:") and crypto.unseal(doc) == [{"title": "Обед"}]
    assert crypto.unseal_any({"x": [a, d1], "n": 1}) == {"x": ["Купить молоко", "Анна"], "n": 1}
    f = tmp_path / "photo.jpg"
    crypto.write_file(f, b"\xff\xd8 jpeg")
    assert f.read_bytes().startswith(b"MNEF1") and crypto.read_file(f) == b"\xff\xd8 jpeg"


def test_database_holds_only_ciphertext_and_old_rows_get_encrypted(conn):  # noqa: F811
    from neuro import crypto, diary, encrypt_existing
    conn.execute("TRUNCATE conversations, segments, tasks, memories, people, places, locations, settings "
                 "RESTART IDENTITY CASCADE")
    t = datetime.now(timezone.utc) - timedelta(hours=1)
    cid = conn.execute("""INSERT INTO conversations (device_id, started_at, last_activity, status, title, topics)
                          VALUES ('d', %s, %s, 'done', 'Встреча с Анной', ARRAY['отпуск', 'билеты']) RETURNING id""",
                       (t, t + timedelta(minutes=5))).fetchone()["id"]
    # «старые» открытые данные — как до включения шифрования
    conn.execute("INSERT INTO segments (conversation_id, device_id, started_at, ended_at, text) "
                 "VALUES (%s, 'd', %s, %s, 'Давай купим билеты в Стамбул')", (cid, t, t))
    conn.execute("INSERT INTO tasks (conversation_id, text, status, source) VALUES (%s, 'Купить билеты', 'open', 'conversation')",
                 (cid,))
    conn.execute("INSERT INTO people (name, number, note) VALUES ('Анна', 1, 'коллега')")
    conn.execute("INSERT INTO places (key, name) VALUES ('55.756,37.617', 'Кафе')")
    conn.execute("INSERT INTO locations (device_id, client_id, ts, lat, lon) VALUES ('d', 'x', %s, 55.7558, 37.6173)", (t,))
    while encrypt_existing.step(conn):
        pass
    raw = conn.execute(
        """SELECT (SELECT text LIKE 'enc1:%%' FROM segments LIMIT 1) AS seg,
                  (SELECT title LIKE 'enc1:%%' FROM conversations LIMIT 1) AS title,
                  (SELECT bool_and(x LIKE 'encd:%%') FROM conversations, unnest(topics) x) AS topics,
                  (SELECT text LIKE 'enc1:%%' FROM tasks LIMIT 1) AS task,
                  (SELECT name LIKE 'encd:%%' AND note LIKE 'enc1:%%' FROM people LIMIT 1) AS person,
                  (SELECT key LIKE 'encd:%%' AND name LIKE 'enc1:%%' FROM places LIMIT 1) AS place,
                  (SELECT lat IS NULL AND pos LIKE 'enc1:%%' FROM locations LIMIT 1) AS loc""").fetchone()
    assert all(raw.values()), raw
    # код по-прежнему видит обычный текст
    assert conn.execute("SELECT title, topics FROM conversations").fetchone() == {
        "title": "Встреча с Анной", "topics": ["отпуск", "билеты"]}
    assert diary.place_name(conn, 55.7558, 37.6173, network=False) == "Кафе"
    from neuro import geo, people
    assert people.by_name(conn, "анна") and geo.points(conn, t - timedelta(minutes=1), t + timedelta(minutes=1))[0]["lat"] == 55.7558
    # новое пишется сразу зашифрованным, поиск — без учёта регистра
    from stt.worker import Seg, store_segments  # noqa: F401
    client = Asgi(__import__("gateway.app", fromlist=["app"]).app)
    found = client.get("/api/search?q=" + __import__("urllib.parse").parse.quote("СТАМБУЛ")).json()
    assert [s["text"] for s in found["segments"]] == ["Давай купим билеты в Стамбул"]
    assert [x["text"] for x in found["tasks"]] == []
    assert crypto.enabled()
