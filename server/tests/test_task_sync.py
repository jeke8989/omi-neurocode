"""Задачи в сервисах задач: только кнопкой или по переключателю; выполнили, смахнули или удалили — закрываем там."""
import json
from datetime import datetime

import pytest

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)
from tests.test_voices import Asgi

TOKEN = "tt_live_" + "A" * 43  # синтетический


class Resp:
    def __init__(self, status=200, data=None):
        self.status_code, self._data = status, data if data is not None else {}
        self.text = json.dumps(self._data)

    def json(self):
        return self._data


class Service:
    """Подмена requests.request: записывает вызовы, отвечает как SmartDesk или GitHub."""

    def __init__(self):
        self.calls, self.fail = [], 0

    def __call__(self, method, url, headers=None, json=None, timeout=None):  # noqa: A002
        self.calls.append((method, url, json, headers))
        if self.fail:
            self.fail -= 1
            return Resp(503, {"detail": "maintenance"})
        if "api.github.com" in url:
            return Resp(201, {"number": 42, "html_url": "https://github.com/o/r/issues/42"})
        return Resp(201, {"created": True, "task": {"url": "https://app.smart-desk.app/t/1", "stage_name": "Входящие"}})


@pytest.fixture()
def svc(conn, monkeypatch):  # noqa: F811
    from neuro import extensions as ext
    from neuro import task_sync
    conn.execute("DELETE FROM extensions")
    conn.execute("DELETE FROM task_exports")
    conn.execute("DELETE FROM tasks")
    reg = {e["id"]: e for e in ext.registry()}
    for ext_id, settings in (("smartdesk", {"api_token": TOKEN}),
                             ("github", {"token": "gh_" + "a" * 20, "owner": "o", "repo": "r"})):
        m, src = ext.load_manifest(reg[ext_id])
        conn.execute("INSERT INTO extensions (id, manifest, settings, source_url, installed_at) "
                     "VALUES (%s, %s::jsonb, %s::jsonb, %s, now() - interval '1 hour')",
                     (ext_id, json.dumps(m, ensure_ascii=False), json.dumps(settings), src))
    s = Service()
    monkeypatch.setattr(task_sync.requests, "request", s)
    return s


def _task(conn, text="Отправить договор Анне", status="open", **kw):  # noqa: F811
    cols = {"text": text, "status": status, "source": "conversation", "target": "notion", **kw}
    return conn.execute(f"INSERT INTO tasks ({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))}) RETURNING id",
                        list(cols.values())).fetchone()["id"]


def _export(conn, tid, ext_id="smartdesk"):  # noqa: F811
    return conn.execute("SELECT * FROM task_exports WHERE task_id = %s AND ext_id = %s", (tid, ext_id)).fetchone()


def _settle(conn):  # noqa: F811
    """Повторы — без ожидания минут."""
    conn.execute("UPDATE task_exports SET next_at = NULL")


def test_nothing_goes_out_by_itself(conn, svc):  # noqa: F811
    from agent import worker
    _task(conn)
    worker.sync_task_integrations(conn)
    assert svc.calls == [] and conn.execute("SELECT count(*) AS n FROM task_exports").fetchone()["n"] == 0


def test_send_then_close_reopen_and_delete_in_smartdesk(conn, svc):  # noqa: F811
    from neuro import task_sync
    tid = _task(conn, counterpart="Анна", evidence="договор пришлю завтра", due_at="2026-11-14T10:00:00+03:00")
    uuid = str(conn.execute("SELECT uuid FROM tasks WHERE id = %s", (tid,)).fetchone()["uuid"])

    e = task_sync.send(conn, tid, "smartdesk")
    method, url, body, headers = svc.calls[-1]
    assert (method, url) == ("POST", "https://app.smart-desk.app/api/v1/integration/tasks")
    assert headers["Authorization"] == f"Bearer {TOKEN}"
    assert body["external_id"] == uuid and body["title"] == "Отправить договор Анне" and body["tags"] == ["mnemo"]
    assert datetime.fromisoformat(body["deadline"]) == datetime.fromisoformat("2026-11-14T10:00:00+03:00")
    assert body["type"] == "mine"
    assert "С кем: Анна" in body["description"] and "«договор пришлю завтра»" in body["description"]
    assert e["ok"] and e["ref"] == "https://app.smart-desk.app/t/1" and e["stage"] == "Входящие"
    assert e["external_id"] == uuid and e["state"] == "open"

    task_sync.send(conn, tid, "smartdesk")  # повторно — без дубля
    assert len(svc.calls) == 1

    conn.execute("UPDATE tasks SET status = 'done' WHERE id = %s", (tid,))
    assert task_sync.reconcile(conn) == 1
    assert svc.calls[-1][1].endswith("/integration/tasks/complete") and svc.calls[-1][2] == {"external_id": uuid}
    assert _export(conn, tid)["remote_state"] == "closed"
    task_sync.reconcile(conn)
    assert len(svc.calls) == 2  # уже закрыта — больше не трогаем

    conn.execute("UPDATE tasks SET status = 'open' WHERE id = %s", (tid,))
    task_sync.reconcile(conn)
    assert svc.calls[-1][1].endswith("/integration/tasks/reopen") and _export(conn, tid)["remote_state"] == "open"

    conn.execute("UPDATE tasks SET status = 'dismissed' WHERE id = %s", (tid,))  # смахнули — закрываем
    task_sync.reconcile(conn)
    assert svc.calls[-1][1].endswith("/complete")

    conn.execute("UPDATE tasks SET status = 'open' WHERE id = %s", (tid,))
    task_sync.reconcile(conn)
    conn.execute("DELETE FROM tasks WHERE id = %s", (tid,))  # удалили — удаления у SmartDesk нет, закрываем
    task_sync.reconcile(conn)
    assert svc.calls[-1][1].endswith("/complete") and _export(conn, tid) is None


def test_github_id_from_response_and_close_by_number(conn, svc):  # noqa: F811
    from neuro import task_sync
    tid = _task(conn, "Починить синхронизацию", target="github")
    e = task_sync.send(conn, tid, "github")
    assert e["external_id"] == "42" and e["ref"] == "https://github.com/o/r/issues/42"
    conn.execute("UPDATE tasks SET status = 'done' WHERE id = %s", (tid,))
    task_sync.reconcile(conn)
    method, url, body, _ = svc.calls[-1]
    assert (method, url, body) == ("PATCH", "https://api.github.com/repos/o/r/issues/42", {"state": "closed"})


def test_failures_retry_and_auto_sends_only_new_tasks(conn, svc):  # noqa: F811
    from neuro import task_sync
    old = _task(conn, "Старая задача")
    svc.fail = 1
    with pytest.raises(task_sync.SyncError, match="503: maintenance"):
        task_sync.send(conn, old, "smartdesk")
    assert not _export(conn, old)["ok"] and _export(conn, old)["error"] == "503: maintenance"
    _settle(conn)
    task_sync.reconcile(conn)  # неудачную отправку повторяем сами
    assert _export(conn, old)["ok"]

    conn.execute("UPDATE extensions SET auto_tasks_since = now() WHERE id = 'smartdesk'")
    before = _task(conn, "До включения")
    conn.execute("UPDATE tasks SET created_at = now() - interval '1 day' WHERE id = %s", (before,))
    new = _task(conn, "После включения")
    suggested = _task(conn, "На согласовании", status="suggested")
    task_sync.reconcile(conn)
    assert _export(conn, new)["ok"] and _export(conn, before) is None and _export(conn, suggested) is None
    assert _export(conn, new, "github") is None  # GitHub без автоотправки

    svc.fail = 1  # закрыть не получилось — ошибка видна, повторяем
    conn.execute("UPDATE tasks SET status = 'done' WHERE id = %s", (new,))
    task_sync.reconcile(conn)
    assert _export(conn, new)["remote_state"] == "open" and _export(conn, new)["attempts"] == 1
    _settle(conn)
    task_sync.reconcile(conn)
    assert _export(conn, new)["remote_state"] == "closed" and _export(conn, new)["error"] is None

    with pytest.raises(task_sync.SyncError, match="только открытую"):
        task_sync.send(conn, new, "github")


def test_reprocess_keeps_sent_tasks(conn, svc):  # noqa: F811
    """Пересчёт разговора удаляет его открытые задачи — но не те, что уже в сервисе (иначе закрылись бы там)."""
    from neuro import task_sync
    cid = conn.execute("INSERT INTO conversations (device_id, started_at, last_activity) "
                       "VALUES ('d', now(), now()) RETURNING id").fetchone()["id"]
    sent, kept = _task(conn, "Отправленная", conversation_id=cid), _task(conn, "Неотправленная", conversation_id=cid)
    task_sync.send(conn, sent, "smartdesk")
    conn.execute("""DELETE FROM tasks WHERE conversation_id = %s AND source = 'conversation'
                    AND status IN ('open', 'suggested') AND exported_at IS NULL
                    AND NOT EXISTS (SELECT 1 FROM task_exports e WHERE e.task_id = tasks.id AND e.ok)""", (cid,))
    left = {r["id"] for r in conn.execute("SELECT id FROM tasks WHERE conversation_id = %s", (cid,)).fetchall()}
    assert left == {sent} and kept not in left


def test_api_send_sinks_and_auto_toggle(conn, svc):  # noqa: F811
    from gateway import app as gw
    client = Asgi(gw.app)
    sinks = client.get("/api/task-sinks").json()["items"]
    sd = next(s for s in sinks if s["id"] == "smartdesk")
    assert sd["close"] and sd["reopen"] and not sd["delete"] and not sd["auto"] and sd["name"] == "SmartDesk"

    tid = _task(conn)
    r = client.post(f"/api/tasks/{tid}/send", json={"ext": "smartdesk"})
    assert r.status_code == 200 and r.json()["export"]["ok"]
    item = next(t for t in client.get("/api/tasks?status=open").json()["items"] if t["id"] == tid)
    exp = item["exports"][0]
    assert exp["ext"] == "smartdesk" and exp["state"] == "open" and exp["can_close"] and exp["stage"] == "Входящие"
    assert TOKEN not in json.dumps(item)

    svc.fail = 1
    other = _task(conn, "Ещё одна")
    r = client.post(f"/api/tasks/{other}/send", json={"ext": "smartdesk"})
    assert r.status_code == 200 and r.json()["error"] == "503: maintenance" and not r.json()["export"]["ok"]
    assert client.post(f"/api/tasks/{other}/send", json={"ext": "nope"}).status_code == 400

    client.post("/api/extensions/smartdesk", json={"auto_tasks": True})
    installed = {e["id"]: e for e in client.get("/api/extensions").json()["installed"]}
    assert installed["smartdesk"]["task_sink"]["auto"] is True
    assert installed["github"]["task_sink"]["title"] == "Задачи на разработку → GitHub Issues"
    client.post("/api/extensions/smartdesk", json={"auto_tasks": False})
    assert conn.execute("SELECT auto_tasks_since FROM extensions WHERE id = 'smartdesk'").fetchone()["auto_tasks_since"] is None

    client.delete("/api/extensions/smartdesk")
    assert conn.execute("SELECT count(*) AS n FROM task_exports WHERE ext_id = 'smartdesk'").fetchone()["n"] == 0


def test_manifest_validation_for_tasks_block():
    from neuro import extensions as ext
    base = {"schema": 1, "id": "tracker", "name": "T", "version": "1", "description": "d",
            "permissions": {"hosts": ["t.example.com"]}}
    ok = {**base, "tasks": {"create": {"http": {"url": "https://t.example.com/tasks"}, "id": "data.id"},
                            "close": {"http": {"method": "PATCH", "url": "https://t.example.com/tasks/{external_id}"}}}}
    assert ext.validate(json.loads(json.dumps(ok)))["tasks"]["close"]
    for bad, msg in (({"close": {"http": {"url": "https://t.example.com/x"}}}, "tasks.create"),
                     ({"create": {"http": {"url": "https://evil.example.org/x"}}}, "permissions.hosts"),
                     ({"create": {"http": {"url": "https://t.example.com/x"}, "id": 5}}, "tasks.create.id"),
                     ({"create": {"http": {"url": "https://t.example.com/x"}},
                       "reopen": {"http": {"url": "https://t.example.com/x"}, "webhook": {"url": "{settings.u}"}}},
                      "tasks.reopen")):
        with pytest.raises(ext.ExtensionError, match=msg):
            ext.validate({**base, "tasks": bad})


def test_agent_tool_sends_task(conn, svc):  # noqa: F811
    from neuro import agent_core
    tid = _task(conn)
    out = agent_core.run_builtin(conn, "send_task", {"id": tid}, None)
    assert out.startswith("Не отправлено: укажите интеграцию")  # их две — нужно назвать
    out = agent_core.run_builtin(conn, "send_task", {"id": tid, "integration": "smartdesk"}, None)
    assert out == "Отправлено в SmartDesk: https://app.smart-desk.app/t/1"


def test_sync_runs_in_own_loop_not_after_summaries(monkeypatch):
    """Регрессия: автоотправка ждала итогов разговоров (3–4 минуты). Теперь — свой поток каждые несколько секунд."""
    import threading
    from agent import worker
    calls, stop = [], threading.Event()

    def fake_reconcile(conn):
        calls.append(1)
        if len(calls) >= 2:
            stop.set()  # два круга подряд — поток живёт сам по себе, останавливаем
    monkeypatch.setattr(worker.task_sync, "reconcile", fake_reconcile)
    monkeypatch.setattr(worker, "TASK_SYNC_SEC", 0.01)
    th = threading.Thread(target=worker.task_sync_loop, args=(stop,), daemon=True)
    th.start()
    th.join(timeout=10)
    assert len(calls) == 2 and not th.is_alive()
    src = open(worker.__file__, encoding="utf-8").read()
    assert "sync_task_integrations(conn)\n" not in src.split("def main():", 1)[1]  # в главном цикле её больше нет
