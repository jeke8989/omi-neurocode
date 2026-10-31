"""Подключение приложения по одноразовому коду (ключ доступа больше не зашит в APK)."""
import time

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)
from tests.test_voices import Asgi


def test_code_works_once_and_locks_after_wrong_attempts(conn):  # noqa: F811
    from neuro import pairing
    code = pairing.new_code(conn)
    assert len(code) == 8 and code.isdigit()
    assert pairing.redeem(conn, "00000000" if code != "00000000" else "11111111") == "bad"
    assert pairing.redeem(conn, pairing.pretty(code)) == "ok"   # пробел внутри не мешает
    assert pairing.redeem(conn, code) == "expired"              # второй раз — нет

    code = pairing.new_code(conn)
    wrong = "12345678" if code != "12345678" else "87654321"
    results = [pairing.redeem(conn, wrong) for _ in range(5)]
    assert results[-1] == "locked"
    assert pairing.redeem(conn, code) == "locked"               # даже верный код после 5 ошибок

    code = pairing.new_code(conn, ttl_sec=-1)
    assert pairing.redeem(conn, code) == "expired"


def test_pair_endpoint_returns_token(conn):  # noqa: F811
    from gateway import app as gw
    from neuro import pairing
    gw._pair_hits.clear()
    client = Asgi(gw.app)
    code = pairing.new_code(conn)
    r = client.post("/api/pair", json={"code": "99999999" if code != "99999999" else "88888888"}, auth=False)
    assert r.status_code == 403
    r = client.post("/api/pair", json={"code": code}, auth=False)
    assert r.status_code == 200 and r.json()["token"] == "test-token"
    r = client.post("/api/pair", json={"code": code}, auth=False)
    assert r.status_code == 410
    # не больше 10 попыток в минуту с одного адреса
    for _ in range(10):
        client.post("/api/pair", json={"code": "1"}, auth=False)
    assert client.post("/api/pair", json={"code": "1"}, auth=False).status_code == 429
    gw._pair_hits.clear()


def test_first_phone_connects_automatically_then_others_ask(conn):  # noqa: F811
    from gateway import app as gw
    conn.execute("TRUNCATE devices, audio_chunks, pair_requests RESTART IDENTITY CASCADE")
    conn.execute("DELETE FROM settings WHERE key LIKE 'pair.%%'")
    gw._pair_hits.clear()
    client = Asgi(gw.app)
    r = client.post("/api/pair/auto", json={"name": "Pixel"}, auth=False)
    assert r.status_code == 200 and r.json()["token"] == "test-token"
    # второй — уже нет: только запрос с подтверждением на первом телефоне
    assert client.post("/api/pair/auto", json={"name": "Чужой"}, auth=False).status_code == 409
    rid = client.post("/api/pair/request", json={"name": "Samsung"}, auth=False).json()["id"]
    assert client.get(f"/api/pair/request/{rid}", auth=False).json() == {"status": "pending"}
    assert client.get("/api/pair/requests", auth=False).status_code == 401
    items = client.get("/api/pair/requests").json()["items"]
    assert [i["name"] for i in items] == ["Samsung"]
    assert client.post(f"/api/pair/requests/{rid}", json={"approve": True}).json() == {"ok": True}
    r = client.get(f"/api/pair/request/{rid}", auth=False).json()
    assert r == {"status": "approved", "token": "test-token"}
    assert client.get(f"/api/pair/request/{rid}", auth=False).json() == {"status": "unknown"}  # ключ отдан один раз
    rid2 = client.post("/api/pair/request", json={"name": "X"}, auth=False).json()["id"]
    client.post(f"/api/pair/requests/{rid2}", json={"approve": False})
    assert client.get(f"/api/pair/request/{rid2}", auth=False).json() == {"status": "rejected"}
    gw._pair_hits.clear()


def test_server_with_recordings_is_not_open(conn):  # noqa: F811
    from neuro import pairing
    conn.execute("DELETE FROM settings WHERE key LIKE 'pair.%%'")
    conn.execute("INSERT INTO devices (device_id) VALUES ('AA') ON CONFLICT DO NOTHING")
    assert pairing.auto(conn) is False
