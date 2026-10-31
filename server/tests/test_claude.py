"""Режим чата «Claude»: токен, события Claude Code CLI -> шаги чата, сервис claude с поддельным CLI."""
import json
import os
import stat
import sys
import threading
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)
from tests.test_voices import Asgi

TOKEN = "sk-ant-oat01-" + "A" * 40  # синтетический
SESSION = "11111111-2222-4333-8444-555555555555"

EVENTS = [
    {"type": "system", "subtype": "init", "session_id": SESSION, "model": "claude-sonnet-test"},
    {"type": "assistant", "message": {"content": [
        {"type": "thinking", "thinking": "…"},
        {"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": "git pull", "description": "Обновляю код"}},
    ]}},
    {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "t1", "content": "Already up to date."}]}},
    {"type": "assistant", "message": {"content": [
        {"type": "tool_use", "id": "t2", "name": "Edit", "input": {"file_path": "/work/omi-neurocode/server/x.py"}}]}},
    {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "t2", "is_error": True,
                                              "content": [{"type": "text", "text": "file not found"}]}]}},
    {"type": "assistant", "message": {"content": [{"type": "text", "text": "Готово, запушил."}]}},
    {"type": "result", "subtype": "success", "is_error": False, "result": "Готово, запушил.",
     "session_id": SESSION, "total_cost_usd": 0.01},
]


def _clean(conn):  # noqa: F811
    conn.execute("DELETE FROM settings WHERE key LIKE 'claude.%%'")
    conn.execute("TRUNCATE chat_messages, agent_runs, agent_steps RESTART IDENTITY CASCADE")


class FakeStream:
    def __init__(self, events, status=200):
        self.status_code, self.lines = status, [json.dumps(e, ensure_ascii=False) for e in events]

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(self.status_code)

    def iter_lines(self, decode_unicode=True):
        yield from self.lines


def test_collector_turns_events_into_steps():
    from neuro import claude_agent
    steps = []
    col = claude_agent.Collector("ru", lambda kind, title, detail=None: steps.append((kind, title)))
    for e in EVENTS:
        col.feed(e)
    assert col.session == SESSION and col.model == "claude-sonnet-test"
    assert col.answer == "Готово, запушил." and col.error is None
    assert steps == [("tool", "💻 Обновляю код"), ("result", "↳ Already up to date."),
                     ("tool", "✏️ Правлю x.py"), ("result", "⚠️ file not found")]
    assert claude_agent.tool_title("en", "Grep", {"pattern": "def main"}) == "🔎 Searching the code: def main"
    assert claude_agent.tool_title("ru", "Mystery", {}) == "⚙️ Mystery"


def test_claude_run_through_chat(conn, monkeypatch):  # noqa: F811
    from gateway import app as gw
    from neuro import agent_core, claude_agent
    _clean(conn)
    client = Asgi(gw.app)

    # без токена режим не запускается
    assert client.post("/api/chat", json={"text": "привет", "level": "claude"}).json()["error"] == "claude_token"

    assert client.post("/api/claude", json={"token": "short"}).status_code == 400
    monkeypatch.setattr(claude_agent, "service_info", lambda timeout=4: {"available": False})
    info = client.post("/api/claude", json={"token": TOKEN, "model": "sonnet"}).json()
    assert info["configured"] and info["token_hint"] == "…AAAA" and info["model"] == "sonnet"
    assert TOKEN not in json.dumps(client.get("/api/claude").json())  # токен обратно не отдаётся
    assert TOKEN not in json.dumps(client.get("/api/features").json())

    sent = {}

    def fake_post(url, json=None, stream=False, timeout=None):  # noqa: A002
        sent.update(url=url, body=json)
        return FakeStream(EVENTS)

    monkeypatch.setattr(claude_agent.requests, "post", fake_post)
    run_id, _ = agent_core.start_run(conn, "почини x.py и залей", "claude", "chat", None)
    assert agent_core.execute(conn, run_id) == "Готово, запушил."
    body = sent["body"]
    assert sent["url"].endswith("/run") and body["token"] == TOKEN and body["model"] == "sonnet"
    assert body["session_id"] is None and "Mnemo" in body["system"]
    # ассистент: без кода и файлов, данные Mnemo — через MCP с токеном только этого запуска
    assert body["mode"] == "assistant" and body["tools"] == "WebSearch,WebFetch"
    mcp_auth = body["mcp"]["mcpServers"]["mnemo"]["headers"]["Authorization"]
    assert mcp_auth.startswith("Bearer ") and claude_agent.run_for_token(mcp_auth[7:]) is None  # отозван
    run = conn.execute("SELECT status, model, answer FROM agent_runs WHERE id = %s", (run_id,)).fetchone()
    assert (run["status"], run["model"]) == ("done", "claude-sonnet-test")
    assert conn.execute("SELECT value FROM settings WHERE key = 'claude.session'").fetchone()["value"] == SESSION

    # следующее сообщение продолжает ту же сессию; режим «код» — своя сессия и без MCP
    run2, _ = agent_core.start_run(conn, "а тесты?", "claude", "chat", None)
    agent_core.execute(conn, run2)
    assert sent["body"]["session_id"] == SESSION
    assert client.post("/api/chat", json={"text": "x", "level": "bogus"}).status_code == 400
    assert client.post("/api/chat", json={"text": "x", "agent": "bard"}).status_code == 400
    assert client.post("/api/chat", json={"text": "x", "context": "event:1;drop"}).status_code == 400
    assert client.post("/api/runs/999999/cancel").status_code == 404
    run_code, _ = agent_core.start_run(conn, "почини тест", "claude_code", "chat", None)
    agent_core.execute(conn, run_code)
    assert sent["body"]["mode"] == "code" and "mcp" not in sent["body"] and sent["body"]["session_id"] is None
    assert "git pull" in sent["body"]["system"]
    assert conn.execute("SELECT value FROM settings WHERE key = 'claude.session.code'").fetchone()["value"] == SESSION

    # сервис занят или упал — понятный ответ, запуск failed
    monkeypatch.setattr(claude_agent.requests, "post", lambda *a, **k: FakeStream([], status=423))
    run3, _ = agent_core.start_run(conn, "ещё", "claude", "chat", None)
    assert "ещё работает" in agent_core.execute(conn, run3)

    chat = client.get("/api/chat").json()
    assert [m["role"] for m in chat["messages"]] == ["user", "assistant"] * 4
    assert chat["runs"][str(run_id)]["level"] == "claude"

    # очистка чата начинает новую сессию, удаление токена выключает режим
    client.post("/api/chat/clear")
    assert client.get("/api/claude").json()["session"] is False  # обе сессии сброшены
    assert client.delete("/api/claude").json()["configured"] is False
    _clean(conn)


def test_mcp_gives_mnemo_tools_only_during_a_run(conn):  # noqa: F811
    from gateway import app as gw
    from neuro import claude_agent
    _clean(conn)
    conn.execute("DELETE FROM tasks")
    anon = Asgi(gw.app, token="nope")
    rpc = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}}
    assert anon.post("/mcp", json=rpc, auth=True).status_code == 401  # без токена запуска — нельзя

    token = claude_agent.issue_run_token(42)
    client = Asgi(gw.app, token=token)
    init = client.post("/mcp", json=rpc).json()
    assert init["result"]["serverInfo"]["name"] == "mnemo" and init["result"]["protocolVersion"] == "2025-06-18"
    assert client.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"}).status_code == 202
    names = {t["name"] for t in client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
             .json()["result"]["tools"]}
    assert {"search_memory", "create_task", "people"} <= names and "web_search" not in names
    done = client.post("/mcp", json={"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
        "name": "create_task", "arguments": {"text": "Позвонить Анне"}}}).json()["result"]
    assert done["isError"] is False and "Позвонить Анне" in done["content"][0]["text"]
    assert conn.execute("SELECT text, source FROM tasks").fetchone() == {"text": "Позвонить Анне", "source": "agent"}
    bad = client.post("/mcp", json={"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                                    "params": {"name": "rm_rf", "arguments": {}}}).json()["result"]
    assert bad["isError"] is True
    assert client.post("/mcp", json={"jsonrpc": "2.0", "id": 5, "method": "nope"}).json()["error"]["code"] == -32601

    claude_agent.revoke_run_token(token)
    assert client.post("/mcp", json=rpc).status_code == 401  # запуск закончился — доступа нет
    conn.execute("DELETE FROM tasks")


def test_service_command_by_mode():
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "claude"))
    import service  # noqa: PLC0415
    sys.path.remove(str(Path(__file__).resolve().parents[1] / "claude"))
    mcp = {"mcpServers": {"mnemo": {"type": "http", "url": "http://gateway:8000/mcp"}}}
    a = service.command({"prompt": "hi", "mode": "assistant", "tools": "WebSearch,WebFetch", "mcp": mcp}, False)
    assert a[a.index("--tools") + 1] == "WebSearch,WebFetch" and "--strict-mcp-config" in a
    assert json.loads(a[a.index("--mcp-config") + 1]) == mcp
    c = service.command({"prompt": "hi", "mode": "code", "session_id": SESSION}, True)
    assert "--tools" not in c and "--mcp-config" not in c and c[c.index("--resume") + 1] == SESSION


def _fake_cli(tmp: Path) -> Path:
    """Поддельный `claude`: с --resume на чужую сессию — ошибка как у настоящего CLI, иначе — ответ."""
    bin_dir = tmp / "bin"
    bin_dir.mkdir()
    script = bin_dir / "claude"
    script.write_text(f"""#!{sys.executable}
import json, os, sys
args = sys.argv[1:]
if "--version" in args:
    print("9.9.9 (Claude Code)"); sys.exit(0)
assert os.environ.get("CLAUDE_CODE_OAUTH_TOKEN"), "token must come from env"
assert "OPENROUTER_API_KEY" not in os.environ
if "--resume" in args:
    print(json.dumps({{"type": "result", "subtype": "error_during_execution", "is_error": True,
                      "errors": ["No conversation found with session ID: " + args[args.index("--resume") + 1]]}}))
    sys.exit(1)
print(json.dumps({{"type": "system", "subtype": "init", "session_id": "new-session"}}))
print(json.dumps({{"type": "result", "subtype": "success", "result": "ok: " + args[args.index("-p") + 1]}}))
""")
    script.chmod(script.stat().st_mode | stat.S_IEXEC)
    return bin_dir


def test_service_retries_without_lost_session(tmp_path, monkeypatch):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "claude"))
    import service  # noqa: PLC0415 — server/claude/service.py
    monkeypatch.setenv("PATH", str(_fake_cli(tmp_path)) + os.pathsep + os.environ["PATH"])
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-v1-" + "a" * 24)  # не должен попасть в CLI
    monkeypatch.setattr(service, "WORK", tmp_path)
    monkeypatch.setattr(service, "REPO", tmp_path / "no-repo")
    monkeypatch.setattr(service, "KEY", tmp_path / "ssh" / "id_ed25519")
    srv = ThreadingHTTPServer(("127.0.0.1", 0), service.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    try:
        assert json.load(urllib.request.urlopen(base + "/info"))["version"].startswith("9.9.9")
        req = urllib.request.Request(base + "/run", method="POST", headers={"Content-Type": "application/json"},
                                     data=json.dumps({"prompt": "привет", "token": "t" * 30,
                                                      "session_id": "old-session"}).encode())
        events = [json.loads(line) for line in urllib.request.urlopen(req, timeout=20).read().splitlines() if line]
    finally:
        srv.shutdown()
        sys.path.remove(str(Path(__file__).resolve().parents[1] / "claude"))
    kinds = [e["type"] for e in events]
    assert kinds == ["service_note", "system", "result", "service_exit"]  # ошибка «нет сессии» не пересылается
    assert events[2]["result"] == "ok: привет" and events[-1]["code"] == 0
