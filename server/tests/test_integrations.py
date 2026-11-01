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


def test_connect_notion_with_one_tap(conn, monkeypatch):  # noqa: F811
    """«Подключить Notion»: поиск адресов входа, регистрация Mnemo, вход с PKCE, токены, инструменты сервиса
    для ассистента (только когда вопрос про Notion), обновление токена, отключение."""
    import time as _time
    from urllib.parse import parse_qs, urlparse

    from neuro import connectors
    gw = _gw()
    conn.execute("DELETE FROM connections")
    seen = {"posts": [], "token_forms": []}

    class R:
        def __init__(self, status=200, data=None, headers=None, text=None):
            self.status_code, self._data = status, data
            self.headers = headers or {"Content-Type": "application/json"}
            self.text = text if text is not None else json.dumps(data or {})

        def json(self):
            return self._data

    def fake_get(url, **kw):
        if url == "https://mcp.notion.com/.well-known/oauth-protected-resource/mcp":
            return R(data={"resource": "https://mcp.notion.com/mcp", "authorization_servers": ["https://mcp.notion.com"]})
        if url == "https://mcp.notion.com/.well-known/oauth-authorization-server":
            return R(data={"authorization_endpoint": "https://mcp.notion.com/authorize",
                           "token_endpoint": "https://mcp.notion.com/token",
                           "registration_endpoint": "https://mcp.notion.com/register"})
        return R(404, {})

    def fake_post(url, json=None, data=None, headers=None, timeout=None):  # noqa: A002
        seen["posts"].append((url, json, data, headers))
        if url == "https://mcp.notion.com/register":
            return R(201, {"client_id": "cid-1"})
        if url == "https://mcp.notion.com/token":
            seen["token_forms"].append(data)
            if data["grant_type"] == "authorization_code":
                return R(data={"access_token": "at-1", "refresh_token": "rt-1", "expires_in": 3600,
                               "workspace_name": "Мой Notion"})
            return R(data={"access_token": "at-2", "refresh_token": "rt-2", "expires_in": 3600})
        if url == "https://mcp.notion.com/mcp":
            auth = (headers or {}).get("Authorization")
            if not auth:
                return R(401, {}, {"WWW-Authenticate": 'Bearer resource_metadata="https://mcp.notion.com/.well-known/oauth-protected-resource/mcp"'})
            method = (json or {}).get("method")
            if method == "initialize":
                return R(data={"jsonrpc": "2.0", "id": 1, "result": {}}, headers={"Mcp-Session-Id": "s1",
                                                                              "Content-Type": "application/json"})
            if method == "notifications/initialized":
                return R(202, {})
            if method == "tools/list":
                body = {"jsonrpc": "2.0", "id": 2, "result": {"tools": [
                    {"name": "notion-search", "description": "Search Notion", "inputSchema": {
                        "type": "object", "properties": {"query": {"type": "string"}}}},
                    {"name": "notion-create-pages", "description": "Create pages", "inputSchema": {
                        "type": "object", "properties": {"title": {"type": "string"}}}}]}}
                return R(data=None, headers={"Content-Type": "text/event-stream"},
                         text="event: message\ndata: " + __import__("json").dumps(body) + "\n\n")
            if method == "tools/call":
                return R(data={"jsonrpc": "2.0", "id": 2, "result": {"content": [{"type": "text", "text": "Страница: План"}]}})
        return R(404, {})

    monkeypatch.setattr(connectors.requests, "get", fake_get)
    monkeypatch.setattr(connectors.requests, "post", fake_post)
    client = Asgi(gw.app)
    items = client.get("/api/connectors").json()["items"]
    assert {i["id"] for i in items} >= {"notion", "linear"} and items[0]["status"] == "off"

    url = client.post("/api/connectors/notion/start", json={"server_url": "http://1.2.3.4:8090"}).json()["url"]
    q = parse_qs(urlparse(url).query)
    assert url.startswith("https://mcp.notion.com/authorize?")
    assert q["client_id"] == ["cid-1"] and q["redirect_uri"] == ["codes.neuro.mnemo://oauth"]
    assert q["code_challenge_method"] == ["S256"] and len(q["code_challenge"][0]) >= 43
    reg = next(p for p in seen["posts"] if p[0].endswith("/register"))
    assert reg[1]["token_endpoint_auth_method"] == "none" and reg[1]["redirect_uris"] == ["codes.neuro.mnemo://oauth"]

    assert client.post("/api/connectors/finish", json={"state": "bad", "code": "x"}).status_code == 400
    card = client.post("/api/connectors/finish", json={"state": q["state"][0], "code": "code-1"}).json()
    assert card["status"] == "connected" and card["account"] == "Мой Notion"
    assert card["tools"] == ["notion-search", "notion-create-pages"]
    assert (card["read"], card["write"], card["read_tools"], card["write_tools"]) == (True, True, 1, 1)
    form = seen["token_forms"][0]
    assert form["redirect_uri"] == "codes.neuro.mnemo://oauth" and form["code"] == "code-1"
    import base64 as _b64
    digest = _b64.urlsafe_b64encode(hashlib.sha256(form["code_verifier"].encode()).digest()).rstrip(b"=").decode()
    assert digest == q["code_challenge"][0]  # PKCE: сервис получает тот же секрет, что был в ссылке
    row = conn.execute("SELECT tokens::text AS t FROM connections WHERE id = 'notion'").fetchone()
    assert "at-1" not in row["t"]  # токены в базе зашифрованы

    # ассистент видит инструменты Notion, только когда вопрос про Notion
    assert connectors.tool_specs(conn, "какая погода")[0] == []
    specs, index = connectors.tool_specs(conn, "найди в notion план")
    assert specs[0]["function"]["name"] == "c_notion__notion_search"
    assert connectors.call_tool(conn, "notion", "notion-search", {"query": "план"}) == "Страница: План"

    # что можно ассистенту — выбирает пользователь: без «создавать и изменять» остаётся только поиск
    assert client.post("/api/connectors/notion", json={"write": False}).json()["ok"]
    assert [x["function"]["name"] for x in connectors.tool_specs(conn, "notion")[0]] == ["c_notion__notion_search"]
    client.post("/api/connectors/notion", json={"write": True})

    # ассистент сам подключает Notion, даже если в вопросе нет слова «Notion» (use_service), и создаёт страницу
    from neuro import agent_core, llm
    seen_tools = []

    def fake_chat(conn_, task, messages, tools=None, **kw):
        names = [t["function"]["name"] for t in tools or []]
        seen_tools.append(names)
        if len(seen_tools) == 1:
            assert "use_service" in names and "c_notion__notion_create_pages" not in names
            assert any("Notion — можно просматривать" in m["content"] for m in messages if m["role"] == "system")
            return {"tool_calls": [{"id": "1", "function": {"name": "use_service", "arguments": '{"service": "notion"}'}}]}
        if len(seen_tools) == 2:
            assert "c_notion__notion_create_pages" in names
            return {"tool_calls": [{"id": "2", "function": {"name": "c_notion__notion_create_pages",
                                                            "arguments": '{"title": "План поездки"}'}}]}
        return {"content": "Создал страницу «План поездки»."}

    monkeypatch.setattr(llm, "chat", fake_chat)
    res = agent_core.ask(conn, "сделай страницу с планом поездки", level="tools", channel="chat")
    assert res["answer"] == "Создал страницу «План поездки»." and len(seen_tools) == 3
    called = [p[1] for p in seen["posts"] if p[1] and p[1].get("method") == "tools/call"]
    assert called[-1]["params"] == {"name": "notion-create-pages", "arguments": {"title": "План поездки"}}

    # токен истёк — обновляется сам (новый refresh-токен сохраняется)
    tokens = conn.execute("SELECT tokens FROM connections WHERE id = 'notion'").fetchone()["tokens"]
    tokens["expires_at"] = _time.time() - 10
    connectors._save(conn, "notion", tokens=tokens)
    connectors.call_tool(conn, "notion", "notion-search", {"query": "x"})
    assert seen["token_forms"][-1]["grant_type"] == "refresh_token" and seen["token_forms"][-1]["refresh_token"] == "rt-1"
    assert conn.execute("SELECT tokens FROM connections WHERE id = 'notion'").fetchone()["tokens"]["refresh_token"] == "rt-2"

    # возврат кода на сервер: страница без HTML-инъекций
    page = client.get("/oauth/callback?state=<b>x</b>&code=1", auth=False)
    assert page.status_code == 200 and "<b>x</b>" not in page.content.decode()

    assert client.post("/api/connectors/notion", json={"read": True}).json()["ok"]
    assert client.delete("/api/connectors/notion").json()["ok"]
    assert next(i for i in client.get("/api/connectors").json()["items"] if i["id"] == "notion")["status"] == "off"


def test_profile_voice_and_important(conn):  # noqa: F811
    """Знакомство: имя, занятие, цели — в контекст ИИ, без слова «владелец»; голос по прочитанным фразам;
    важный разговор — звёздочкой и в списке «Важные»."""
    from datetime import datetime, timedelta, timezone

    from neuro import profile
    gw = _gw()
    client = Asgi(gw.app)
    conn.execute("DELETE FROM settings WHERE key LIKE 'profile.%%'")
    conn.execute("DELETE FROM segments")
    conn.execute("DELETE FROM people")
    p = client.post("/api/profile", json={"name": " Женя ", "about": "продакт в финтехе",
                                          "goals": ["Не забывать договорённости", ""]}).json()
    assert p["name"] == "Женя" and p["goals"] == ["Не забывать договорённости"] and p["voice"] == "none"
    text = profile.prompt(conn)
    assert "Женя" in text and "продакт в финтехе" in text and "«владелец» в текстах не используй" in text

    # прочитал фразы: окно [t0, t1]; в нём больше всех говорил «Голос 1» — это он
    t0 = datetime.now(timezone.utc) - timedelta(minutes=5)
    t1 = t0 + timedelta(seconds=40)
    voice = conn.execute("INSERT INTO people (number) VALUES (1) RETURNING id").fetchone()["id"]
    other = conn.execute("INSERT INTO people (number) VALUES (2) RETURNING id").fetchone()["id"]
    conn.execute("INSERT INTO audio_chunks (device_id, started_at, duration_ms, status) VALUES ('d', %s, 60000, 'done')",
                 (t0 - timedelta(seconds=10),))
    for pid, a, b in ((voice, 2, 30), (other, 31, 35)):
        conn.execute("""INSERT INTO segments (device_id, started_at, ended_at, text, person_id)
                        VALUES ('d', %s, %s, 'фраза', %s)""", (t0 + timedelta(seconds=a), t0 + timedelta(seconds=b), pid))
    ms = lambda d: int(d.timestamp() * 1000)  # noqa: E731
    assert client.post("/api/profile/voice", json={"from": ms(t0), "to": ms(t1)}).json()["voice"] == "waiting"
    assert profile.voice_tick(conn) == "done"
    me = conn.execute("SELECT id, name, is_owner FROM people WHERE is_owner").fetchone()
    assert me["id"] == voice and me["name"] == "Женя"
    assert client.get("/api/profile").json()["voice"] == "done"
    # в расшифровке для модели — имя, а не «владелец»
    from neuro import people as peoplelib
    seg = conn.execute(peoplelib.SEGMENT_SELECT + " WHERE s.person_id = %s LIMIT 1", (voice,)).fetchone()
    assert peoplelib.speaker_label(seg).rstrip("?") == "Женя"

    # важный разговор: звёздочка и список
    cid = conn.execute("""INSERT INTO conversations (device_id, started_at, last_activity, status, title)
                          VALUES ('d', now() - interval '1 hour', now() - interval '50 minutes', 'done', 'Созвон')
                          RETURNING id""").fetchone()["id"]
    assert client.post(f"/api/conversations/{cid}/important", json={"on": True}).json()["important"]
    assert client.get(f"/api/conversations/{cid}").json()["important"] is True
    assert [i["id"] for i in client.get("/api/important").json()["items"]] == [cid]
    client.post(f"/api/conversations/{cid}/important", json={"on": False})
    assert client.get("/api/important").json()["items"] == []


def test_service_tools_are_picked_for_the_task(conn, monkeypatch):  # noqa: F811
    """У Notion десятки инструментов: модели уходят только нужные для задачи (иначе ~30 тыс. токенов на шаг)."""
    from neuro import connectors, llm
    tools = [{"name": f"notion-tool-{i}", "description": f"Tool {i}\nlong text", "access": "read"} for i in range(30)]
    tools += [{"name": "notion-search", "description": "Search", "access": "read"},
              {"name": "notion-create-pages", "description": "Create pages", "access": "write"}]
    asked = []

    def fake_json(conn_, task, system, user, **kw):
        asked.append((task, user, kw.get("usage")))
        return {"tools": ["notion-create-pages", "notion-search", "нет такого"]}

    monkeypatch.setattr(llm, "chat_json", fake_json)
    chosen = connectors.pick_tools(conn, tools, "создай страницу с планом")
    assert [t["name"] for t in chosen] == ["notion-create-pages", "notion-search"]
    task, user, usage = asked[0]
    assert task == "remember" and usage == "tool_pick" and "создай страницу" in user and "long text" not in user
    # модель недоступна — берём базовые: поиск, чтение, создание…
    monkeypatch.setattr(llm, "chat_json", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("down")))
    fallback = [t["name"] for t in connectors.pick_tools(conn, tools, "что-то")]
    assert fallback[:2] == ["notion-search", "notion-create-pages"] and len(fallback) == connectors.TOOL_LIMIT


def _fake_vectors(monkeypatch):
    """Отпечатки без сети: по ключевым словам — «Notion»/«сводк» рядом, «кошка» далеко."""
    import numpy as np

    from neuro import embed
    axes = ["notion", "сводк", "кошк", "мама", "кофе", "переех"]

    def vec(t):
        t = t.lower()
        v = np.array([1.0 if a in t else 0.0 for a in axes] + [0.05], dtype=np.float32)
        return v / np.linalg.norm(v)

    monkeypatch.setattr(embed, "available", lambda: True)
    monkeypatch.setattr(embed, "vectors", lambda conn, texts, timeout=30: [vec(t) for t in texts])
    monkeypatch.setattr(embed, "one", lambda conn, text: vec(text))


def test_memory_recall_and_merge(conn, monkeypatch):  # noqa: F811
    """Память: поиск по смыслу (в контекст — только нужное), похожие факты объединяются (как в Mem0)."""
    from neuro import llm, memory
    from neuro.assistant import insert_memories
    conn.execute("DELETE FROM memories")
    _fake_vectors(monkeypatch)
    calls = []

    def fake_json(conn_, task, system, user, **kw):
        calls.append(kw.get("usage"))
        old_id = int(user.split("[")[1].split("]")[0])
        return {"actions": [{"fact": 0, "op": "update", "id": old_id, "text": "Женя переехал в Москву, на Арбат"}]}

    monkeypatch.setattr(llm, "chat_json", fake_json)
    insert_memories(conn, None, ["Мама любит кофе без сахара", "Женя переехал в Казань"], "agent")
    assert conn.execute("SELECT count(*) AS n FROM memories WHERE emb IS NULL").fetchone()["n"] == 0  # отпечатки сразу
    # уточнение старого факта — обновляется старая запись, а не появляется вторая
    insert_memories(conn, None, ["Женя переехал в Москву на Арбат"], "conversation")
    texts = [r["text"] for r in conn.execute("SELECT text FROM memories WHERE archived_at IS NULL ORDER BY id").fetchall()]
    assert texts == ["Мама любит кофе без сахара", "Женя переехал в Москву, на Арбат"] and calls == ["memory_update"]
    hits = memory.recall(conn, "что пьёт мама? кофе", k=3, kinds=("memory",))
    assert hits[0]["text"].startswith("Мама любит кофе")
    assert all("кошк" not in h["text"] for h in hits)

    # в приложении: поиск по смыслу и исправление записи (отпечаток пересчитается)
    client = Asgi(_gw().app)
    from urllib.parse import quote
    found = client.get("/api/memories?q=" + quote("кофе для мамы")).json()
    assert found["items"][0]["text"].startswith("Мама любит кофе") and found["active"] == 2
    mid = found["items"][0]["id"]
    assert client.post(f"/api/memories/{mid}", json={"text": "Мама пьёт кофе без сахара и с корицей"}).json()["ok"]
    row = conn.execute("SELECT text, emb, updated_at FROM memories WHERE id = %s", (mid,)).fetchone()
    assert row["text"].endswith("с корицей") and row["emb"] is None and row["updated_at"]
    assert memory.embed_tick(conn) >= 1
    assert client.post(f"/api/memories/{mid}", json={"text": " "}).status_code == 400


def test_rules_and_schedules(conn, monkeypatch):  # noqa: F811
    """Правила и расписание агента: записать, увидеть в каждом запросе, выполнить в срок, удалить по просьбе."""
    from datetime import datetime, timedelta, timezone

    from neuro import agent_core, llm, routines
    from neuro.assistant import TZ
    conn.execute("DELETE FROM agent_rules")
    conn.execute("DELETE FROM agent_schedules")
    # следующий запуск: «каждый день в 23:00», по будням, один раз
    after = datetime(2026, 11, 6, 22, 0, tzinfo=TZ)  # пятница
    assert routines.next_run("23:00", [], None, after) == datetime(2026, 11, 6, 23, 0, tzinfo=TZ)
    assert routines.next_run("09:00", [0, 1, 2, 3, 4], None, after) == datetime(2026, 11, 9, 9, 0, tzinfo=TZ)
    assert routines.parse_days("пн, пятница") == [0, 4] and routines.parse_days("daily") == []

    steps = []

    def fake_chat(conn_, task, messages, tools=None, **kw):
        steps.append(messages)
        names = [t["function"]["name"] for t in tools or []]
        assert {"remember_rule", "schedule_task", "forget_rule", "cancel_schedule"} <= set(names)
        if len(steps) == 1:
            return {"tool_calls": [
                {"id": "1", "function": {"name": "remember_rule", "arguments": '{"text": "Сводки дня — в Notion, страница Дневник"}'}},
                {"id": "2", "function": {"name": "schedule_task", "arguments": json.dumps(
                    {"title": "Сводка дня", "prompt": "Сделай сводку дня и положи в Notion", "time": "23:00"})}}]}
        return {"content": "Готово: каждый день в 23:00."}

    monkeypatch.setattr(llm, "chat", fake_chat)
    res = agent_core.ask(conn, "делай сводку в нотион каждый день в 23", level="tools", channel="chat")
    assert res["answer"].startswith("Готово")
    rule = routines.rules(conn)[0]
    sch = routines.schedules(conn)[0]
    assert rule["text"].startswith("Сводки дня") and sch["time_of_day"] == "23:00" and sch["next_run_at"]
    # в следующем запросе агент видит правило и задание с номерами
    steps.clear()
    monkeypatch.setattr(llm, "chat", lambda conn_, task, messages, tools=None, **kw: steps.append(messages) or {"content": "ок"})
    agent_core.ask(conn, "привет", level="tools", channel="chat")
    system = "\n".join(m["content"] for m in steps[0] if m["role"] == "system")
    assert f"[правило {rule['id']}]" in system and f"[задание {sch['id']}]" in system and "каждый день в 23:00" in system

    # пришло время — агент выполняет задание сам, ответ — в чат (канал schedule)
    conn.execute("UPDATE agent_schedules SET next_run_at = now() - interval '1 minute' WHERE id = %s", (sch["id"],))
    asked = []
    monkeypatch.setattr(agent_core, "ask", lambda conn_, q, **kw: asked.append((q, kw)) or {"run_id": 1, "answer": "ok"})
    assert routines.run_due(conn) == 1
    assert asked[0][0].endswith("Сделай сводку дня и положи в Notion") and asked[0][1]["channel"] == "schedule"
    again = conn.execute("SELECT next_run_at, last_status FROM agent_schedules WHERE id = %s", (sch["id"],)).fetchone()
    assert again["last_status"] == "done" and again["next_run_at"] > datetime.now(timezone.utc)
    assert routines.run_due(conn) == 0  # не повторяется

    # «убери это» — удаляется по номеру
    assert routines.forget_rule(conn, rule["id"]) and routines.cancel_schedule(conn, sch["id"])
    assert routines.prompt_block(conn) == ""
    # прошедшее время одного раза не принимается
    try:
        routines.add_schedule(conn, "x", "y", once_at=(datetime.now(TZ) - timedelta(hours=1)).strftime("%Y-%m-%d %H:%M"))
        raise AssertionError("должно быть отклонено")
    except ValueError:
        pass


def test_routines_http(conn):  # noqa: F811
    from neuro import routines
    conn.execute("DELETE FROM agent_rules")
    conn.execute("DELETE FROM agent_schedules")
    client = Asgi(_gw().app)
    rid = routines.add_rule(conn, "Итоги встреч с клиентами — в Notion")
    sid = routines.add_schedule(conn, "Сводка", "Сводка дня в Notion", "23:00", "weekdays")["id"]
    data = client.get("/api/agent/routines").json()
    assert data["rules"][0]["text"].startswith("Итоги встреч") and data["schedules"][0]["when"] == "по будням в 23:00"
    assert client.post(f"/api/agent/schedules/{sid}", json={"enabled": False}).json()["ok"]
    assert client.get("/api/agent/routines").json()["schedules"][0]["enabled"] is False
    assert client.delete(f"/api/agent/rules/{rid}").json()["ok"]
    assert client.delete(f"/api/agent/schedules/{sid}").json()["ok"]
    assert client.get("/api/agent/routines").json() == {"rules": [], "schedules": []}
    assert client.get("/api/agent/results?after=0").status_code == 200


def test_phone_calendar(conn, monkeypatch):  # noqa: F811
    """Календарь телефона: события приходят с телефона, ассистент их видит и меняет через очередь команд."""
    from datetime import datetime, timedelta, timezone

    from neuro import agent_core, calendar, llm
    from neuro.assistant import TZ
    conn.execute("DELETE FROM calendar_events")
    conn.execute("DELETE FROM calendar_commands")
    conn.execute("DELETE FROM settings WHERE key LIKE 'calendar.%%'")
    client = Asgi(_gw().app)
    assert client.get("/api/calendar").json()["connected"] is False
    day = datetime.now(TZ).replace(hour=15, minute=0, second=0, microsecond=0) + timedelta(days=1)
    ms = lambda d: int(d.timestamp() * 1000)  # noqa: E731
    events = [{"event_id": 41, "calendar_id": 3, "calendar": "Работа", "title": "Созвон с Иваном", "begin": ms(day),
               "end": ms(day + timedelta(hours=1)), "location": "Zoom", "attendees": ["Иван Петров"]},
              {"event_id": 42, "calendar_id": 3, "title": "День рождения мамы", "begin": ms(day.replace(hour=0)),
               "end": ms(day.replace(hour=0) + timedelta(days=1)), "all_day": True}]
    window = {"from": ms(day - timedelta(days=14)), "to": ms(day + timedelta(days=60))}
    r = client.post("/api/calendar/sync", json={**window, "events": events,
                                                 "calendars": [{"id": 3, "name": "Работа", "account": "me@x", "primary": True}]})
    assert r.json()["events"] == 2
    st = client.post("/api/calendar", json={"target": 3}).json()
    assert st["connected"] and st["target"] == 3 and st["calendars"][0]["name"] == "Работа"
    row = conn.execute("SELECT title::text AS t FROM calendar_events WHERE event_id = 41").fetchone()
    assert row["t"] == "Созвон с Иваном" or row["t"].startswith("enc")

    # встреча в то же время, что и разговор, — подсказка итогам
    hit = calendar.around(conn, day + timedelta(minutes=5), day + timedelta(minutes=40))
    assert hit["title"] == "Созвон с Иваном" and "Иван Петров" in hit["attendees"]

    seen = []

    def fake_chat(conn_, task, messages, tools=None, **kw):
        names = [t["function"]["name"] for t in tools or []]
        seen.append(names)
        if len(seen) == 1:
            assert {"calendar", "calendar_add", "calendar_change", "calendar_delete"} <= set(names)
            return {"tool_calls": [
                {"id": "1", "function": {"name": "calendar", "arguments": json.dumps({"from": day.date().isoformat()})}},
                {"id": "2", "function": {"name": "calendar_change", "arguments": json.dumps(
                    {"id": 41, "start": (day + timedelta(hours=2)).strftime("%Y-%m-%d %H:%M")})}},
                {"id": "3", "function": {"name": "calendar_add", "arguments": json.dumps(
                    {"title": "Отправить КП Ивану", "start": (day + timedelta(hours=4)).strftime("%Y-%m-%d %H:%M"),
                     "duration_min": 30})}}]}
        tool_out = [m["content"] for m in messages if m["role"] == "tool"]
        assert "Созвон с Иваном" in tool_out[0] and "весь день День рождения мамы" in tool_out[0]
        return {"content": "Перенёс созвон и поставил напоминание про КП."}

    monkeypatch.setattr(llm, "chat", fake_chat)
    agent_core.ask(conn, "перенеси завтрашний созвон на 17 и поставь в 19 отправить КП", level="tools", channel="chat")
    items = client.get("/api/calendar/commands").json()["items"]
    assert [i["op"] for i in items] == ["update", "create"]
    assert items[0]["payload"]["event_id"] == 41 and items[0]["payload"]["begin"] == ms(day + timedelta(hours=2))
    assert items[1]["payload"]["calendar_id"] == 3 and items[1]["payload"]["end"] - items[1]["payload"]["begin"] == 30 * 60000
    assert client.post(f"/api/calendar/commands/{items[0]['id']}", json={"ok": True, "result": "41"}).json()["ok"]
    assert [i["op"] for i in client.get("/api/calendar/commands").json()["items"]] == ["create"]

    # «только просматривать» — инструментов изменения нет
    client.post("/api/calendar", json={"write": False})
    seen.clear()
    monkeypatch.setattr(llm, "chat", lambda conn_, task, messages, tools=None, **kw: seen.append(
        [t["function"]["name"] for t in tools or []]) or {"content": "ок"})
    agent_core.ask(conn, "что завтра?", level="tools", channel="chat")
    assert "calendar" in seen[0] and "calendar_add" not in seen[0]
    # отключить — события с сервера удаляются
    assert client.delete("/api/calendar").json()["ok"]
    assert client.get("/api/calendar").json()["connected"] is False
    assert conn.execute("SELECT count(*) AS n FROM calendar_events").fetchone()["n"] == 0
