"""Ответы API сжимаются (по мобильной сети лента дня и обещания грузились секундами)."""
import asyncio
import gzip
import json

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)


def _get(app, path, encoding="gzip"):
    scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "GET", "scheme": "http",
             "path": path, "raw_path": path.encode(), "query_string": b"", "root_path": "",
             "headers": [(b"authorization", b"Bearer test-token"), (b"accept-encoding", encoding.encode())],
             "client": ("test", 1), "server": ("test", 80)}
    out = {"headers": {}, "body": b""}

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(msg):
        if msg["type"] == "http.response.start":
            out["headers"] = {k.decode(): v.decode() for k, v in msg["headers"]}
        elif msg["type"] == "http.response.body":
            out["body"] += msg.get("body", b"")

    asyncio.run(app(scope, receive, send))
    return out


def test_api_responses_are_gzipped(conn):  # noqa: F811
    from gateway import app as gw
    conn.execute("DELETE FROM tasks")
    for i in range(40):
        conn.execute("INSERT INTO tasks (text, status) VALUES (%s, 'open')", (f"Длинная задача номер {i} " * 3,))
    r = _get(gw.app, "/api/tasks")
    assert r["headers"].get("content-encoding") == "gzip"
    data = json.loads(gzip.decompress(r["body"]))
    assert len(data["items"]) == 40 and len(r["body"]) < len(json.dumps(data, ensure_ascii=False).encode()) / 3
    plain = _get(gw.app, "/api/tasks", encoding="identity")  # клиент без gzip получает как раньше
    assert "content-encoding" not in plain["headers"] and len(json.loads(plain["body"])["items"]) == 40
    assert "content-encoding" not in _get(gw.app, "/health")["headers"]  # маленькие ответы не трогаем
    conn.execute("DELETE FROM tasks")
