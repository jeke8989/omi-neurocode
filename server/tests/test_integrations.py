"""Интеграции: вебхук на события, сборка плагина с ассистентом, личный ключ MCP; память без мусора."""
import hashlib
import hmac
import json

from tests.test_pipeline import conn  # noqa: F401
from tests.test_voices import Asgi


def _gw():
    return __import__("gateway.app", fromlist=["app"])


def test_webhook_events_signed_and_retried(conn, monkeypatch):  # noqa: F811
    from neuro import extensions, hooks
    gw = _gw()
    conn.execute("DELETE FROM extensions")
    client = Asgi(gw.app)
    assert client.post("/api/extensions/install", json={"id": "webhook"}).status_code == 200
    # адрес — только https
    assert client.post("/api/extensions/webhook", json={"settings": {"url": "ftp://x"}}).status_code == 400
    client.post("/api/extensions/webhook", json={"settings": {"url": "https://n8n.example.org/hook/abc",
                                                                 "secret": "s3cret"}})
    listed = client.get("/api/extensions").json()["installed"][0]
    assert listed["ready"] and {e["event"] for e in listed["events"]} == set(hooks.EVENTS)
    assert all(e["to_user_url"] for e in listed["events"]) and "s3cret" not in json.dumps(listed)

    hooks.emit(conn, "conversation", {"id": 7, "title": "Обед", "summary": "Договорились о поездке."})
    sent = []

    class R:
        status_code = 200
        text = "ok"

    def fake_post(url, data=None, headers=None, timeout=None):
        sent.append((url, data, headers))
        return R()

    monkeypatch.setattr(hooks.requests, "post", fake_post)
    assert hooks.deliver(conn) == 1
    url, body, headers = sent[0]
    assert url == "https://n8n.example.org/hook/abc" and headers["X-Mnemo-Event"] == "conversation"
    assert headers["X-Mnemo-Signature"] == "sha256=" + hmac.new(b"s3cret", body, hashlib.sha256).hexdigest()
    payload = json.loads(body)
    assert payload["data"]["title"] == "Обед" and "s3cret" not in body.decode()  # настройки в тело не идут

    # сервис лёг — событие остаётся в очереди с повтором
    class Bad:
        status_code = 502
        text = "bad gateway"

    monkeypatch.setattr(hooks.requests, "post", lambda *a, **k: Bad())
    hooks.emit(conn, "memory", {"id": 1, "text": "Кристина не ест мясо"})
    assert hooks.deliver(conn) == 0
    row = conn.execute("SELECT status, attempts FROM hook_events WHERE event = 'memory'").fetchone()
    assert row["status"] == "pending" and row["attempts"] == 1
    assert hooks.stats(conn, "webhook")["sent"] == 1

    # чужой домен в обычном манифесте по-прежнему запрещён
    bad = {"schema": 1, "id": "x", "name": "x", "version": "1", "description": "x", "permissions": {"hosts": []},
           "on": {"task": {"http": {"url": "https://evil.example.com/"}}}}
    try:
        extensions.validate(bad)
        raise AssertionError("должно быть запрещено")
    except extensions.ExtensionError:
        pass


def test_build_integration_with_assistant(conn, monkeypatch):  # noqa: F811
    from neuro import llm
    gw = _gw()
    conn.execute("DELETE FROM extensions")
    calls = []

    def fake_json(conn_, task, system, user, **kw):
        calls.append(user)
        m = {"name": "Todoist", "icon": "✅", "description": "Задачи в Todoist",
             "permissions": {"hosts": ["api.todoist.com"]},
             "settings": [{"key": "token", "title": "Токен", "secret": True, "required": True}],
             "tools": [{"name": "add_task", "title": "Добавляю в Todoist", "description": "Добавить задачу",
                        "parameters": {"type": "object", "properties": {"text": {"type": "string"}}},
                        "http": {"method": "POST", "url": "https://api.todoist.com/rest/v2/tasks",
                                 "headers": {"Authorization": "Bearer {settings.token}"}, "body": {"content": "{text}"}}}],
             "on": {"task": {"http": {"method": "POST", "url": "https://api.todoist.com/rest/v2/tasks",
                                      "headers": {"Authorization": "Bearer {settings.token}"},
                                      "body": {"content": "{text}"}}}}}
        if len(calls) == 1:
            m["permissions"]["hosts"] = []  # первая попытка с ошибкой — модель исправит
        return {"manifest": m, "notes": "Возьмите токен в настройках Todoist → Интеграции."}

    monkeypatch.setattr(llm, "configured", lambda: True)
    monkeypatch.setattr(llm, "chat_json", fake_json)
    client = Asgi(gw.app)
    draft = client.post("/api/extensions/draft", json={"description": "Хочу, чтобы задачи уходили в Todoist"}).json()
    assert len(calls) == 2 and "не прошёл проверку" in calls[1]
    assert draft["manifest"]["id"] == "my-todoist" and draft["preview"]["hosts"] == ["api.todoist.com"]
    assert draft["notes"].startswith("Возьмите токен")
    r = client.post("/api/extensions/install", json={"manifest": draft["manifest"]})
    assert r.status_code == 200
    row = conn.execute("SELECT source_url FROM extensions WHERE id = 'my-todoist'").fetchone()
    assert row["source_url"] == "assistant"
    assert client.post("/api/extensions/draft", json={}).status_code == 400


def test_personal_mcp_key(conn):  # noqa: F811
    gw = _gw()
    client = Asgi(gw.app)
    client.delete("/api/mcp")
    assert client.get("/api/mcp").json()["enabled"] is False
    rpc = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
    assert Asgi(gw.app, token="mnemo_wrong").post("/mcp", json=rpc).status_code == 401
    token = client.post("/api/mcp").json()["token"]
    assert token.startswith("mnemo_") and client.get("/api/mcp").json()["enabled"] is True
    tools = Asgi(gw.app, token=token).post("/mcp", json=rpc).json()["result"]["tools"]
    names = {t["name"] for t in tools}
    assert "create_task" in names and "day_events" in names and "ask_user" not in names
    assert token not in json.dumps(client.get("/api/mcp").json())  # ключ показывается только один раз
    client.delete("/api/mcp")
    assert Asgi(gw.app, token=token).post("/mcp", json=rpc).status_code == 401


def test_memory_without_junk(conn, monkeypatch):  # noqa: F811
    from neuro import llm, memory
    from neuro.assistant import insert_memories
    conn.execute("DELETE FROM memories")
    conn.execute("DELETE FROM settings WHERE key = %s", (memory.REVIEW_KEY,))
    insert_memories(conn, None, ["Кристина не ест мясо."], "conversation")
    insert_memories(conn, None, ["Кристина не ест мясо", "Ок", "Отпуск в Питере — с 20 декабря."], "conversation")
    texts = [r["text"] for r in conn.execute("SELECT text FROM memories ORDER BY id").fetchall()]
    assert texts == ["Кристина не ест мясо.", "Отпуск в Питере — с 20 декабря."]  # без повтора и обрывков
    insert_memories(conn, None, ["Кристина не ест мясо"], "button")  # кнопку записываем всегда
    assert conn.execute("SELECT count(*) AS n FROM memories").fetchone()["n"] == 3

    # разовая перепроверка: мусор — в архив, а не удаляется
    conn.execute("INSERT INTO memories (text, source) VALUES ('Владелец спросил про погоду', 'conversation')")
    monkeypatch.setattr(llm, "configured", lambda: True)
    monkeypatch.setattr(llm, "chat_json", lambda *a, **k: {"keep": [1, 2]})
    assert memory.review_tick(conn) == 3
    assert memory.review_tick(conn) == 0 and memory.review_tick(conn) == 0
    client = Asgi(_gw().app)
    active = client.get("/api/memories").json()
    assert [m["text"] for m in active["items"]][-1] == "Кристина не ест мясо." and active["archived"] == 1
    gone = client.get("/api/memories?archived=1").json()["items"]
    assert [m["text"] for m in gone] == ["Владелец спросил про погоду"]
    assert client.post(f"/api/memories/{gone[0]['id']}", json={"archived": False}).json()["ok"]
    assert client.get("/api/memories").json()["archived"] == 0
    assert client.delete(f"/api/memories/{gone[0]['id']}").json()["ok"]
    assert client.delete(f"/api/memories/{gone[0]['id']}").status_code == 404
