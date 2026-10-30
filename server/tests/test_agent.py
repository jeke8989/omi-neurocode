"""Агент с уровнями, инструменты, расширения маркетплейса и дневник дня."""
import json
from datetime import datetime, timedelta, timezone

import pytest

from tests.test_pipeline import DEVICE, conn  # noqa: F401


def test_route_levels():
    from neuro.agent_core import route
    assert route("сколько будет 2+2", "voice") == "fast"
    assert route("найди свежие новости про Omi", "chat") == "tools"
    assert route("проанализируй мои разговоры за неделю и составь план", "chat") == "deep"
    assert route("подробно разбери конкурентов", "voice") == "deep"


def test_extension_manifests_and_security(monkeypatch):
    from neuro import extensions as ext
    reg = ext.registry()
    assert {e["id"] for e in reg} >= {"weather", "currency", "wikipedia", "telegram"}
    m, _ = ext.load_manifest(next(e for e in reg if e["id"] == "telegram"))
    assert ext.public_manifest(m)["settings"][0]["secret"] is True

    bad = json.loads(json.dumps(m))
    bad["tools"][0]["http"]["url"] = "https://{host}/x"
    with pytest.raises(ext.ExtensionError):
        ext.validate(bad)
    bad["tools"][0]["http"]["url"] = "https://evil.example.com/steal"
    with pytest.raises(ext.ExtensionError):
        ext.validate(bad)

    sent = {}

    class R:
        status_code = 200
        text = '{"ok": true}'

        def json(self):
            return {"ok": True}

    def fake_request(method, url, headers=None, json=None, timeout=None):
        sent.update(method=method, url=url, body=json)
        return R()

    monkeypatch.setattr(ext.requests, "request", fake_request)
    row = {"manifest": m, "settings": {"bot_token": "123:ABC", "chat_id": "42"}}
    out = ext.run_tool(row, m["tools"][0], {"text": "Привет & пока"})
    assert out == '{"ok": true}'
    assert sent["url"] == "https://api.telegram.org/bot123:ABC/sendMessage"
    assert sent["body"] == {"chat_id": "42", "text": "Привет & пока"}

    w, _ = ext.load_manifest(next(e for e in reg if e["id"] == "weather"))
    ext.run_tool({"manifest": w, "settings": {}}, w["tools"][0], {"city": "Нижний Новгород/../x"})
    assert sent["url"].startswith("https://geocoding-api.open-meteo.com/v1/search?name=%D0%9D")
    assert "/../" not in sent["url"]

    # не настроенное расширение агенту не показывается
    specs, index = ext.tool_specs([{"id": "telegram", "manifest": m, "settings": {}}])
    assert specs == [] and index == {}
    specs, index = ext.tool_specs([row | {"id": "telegram"}])
    assert specs[0]["function"]["name"] == "x_telegram__send_message"


def test_agent_tool_loop_and_steps(conn, monkeypatch):  # noqa: F811
    from neuro import agent_core, llm
    conn.execute("DELETE FROM extensions")
    calls = []

    def fake_chat(conn_, task, messages, tools=None, reasoning=None, timeout=120, extra=None, model=None):
        calls.append((task, bool(tools), reasoning))
        if task == "search":
            return {"content": "Omi — открытый носимый ИИ-кулон.",
                    "annotations": [{"type": "url_citation", "url_citation": {"url": "https://omi.me"}}]}
        tool_msgs = [m for m in messages if m["role"] == "tool"]
        if not tool_msgs:
            return {"content": "", "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "web_search", "arguments": '{"query": "Omi"}'}},
                {"id": "c2", "type": "function",
                 "function": {"name": "create_task", "arguments": '{"text": "Прочитать про Omi", "due": "завтра"}'}},
            ]}
        assert "Источник: https://omi.me" in tool_msgs[0]["content"]
        return {"content": "Omi — открытый кулон. Задачу прочитать про него создал."}

    monkeypatch.setattr(llm, "chat", fake_chat)
    res = agent_core.ask(conn, "найди, что такое Omi, и создай задачу почитать", device_id=DEVICE)
    assert res["level"] == "tools" and res["answer"].startswith("Omi")
    steps = [s["title"] for s in conn.execute(
        "SELECT title FROM agent_steps WHERE run_id = %s ORDER BY id", (res["run_id"],)).fetchall()]
    assert steps[0].startswith("Уровень: Основательно")
    assert "🔎 Ищу в интернете: Omi" in steps and "📝 Создаю задачу: Прочитать про Omi" in steps
    assert conn.execute("SELECT source FROM tasks WHERE text = 'Прочитать про Omi'").fetchone()["source"] == "agent"
    msgs = conn.execute("SELECT role, text FROM chat_messages ORDER BY id").fetchall()
    assert [m["role"] for m in msgs][-2:] == ["user", "assistant"]
    run = conn.execute("SELECT status FROM agent_runs WHERE id = %s", (res["run_id"],)).fetchone()
    assert run["status"] == "done"
    assert ("agent", True, "medium") in calls

    # глубокий уровень сначала строит план
    plan_seen = []

    def deep_chat(conn_, task, messages, tools=None, reasoning=None, **kw):
        if tools is None and "план" in messages[-1]["content"]:
            plan_seen.append(task)
            return {"content": "1. Собрать факты\n2. Сделать вывод"}
        return {"content": "Итог готов."}

    monkeypatch.setattr(llm, "chat", deep_chat)
    res = agent_core.ask(conn, "проанализируй мою неделю", channel="chat")
    assert res["level"] == "deep" and plan_seen == ["agent_deep"]
    titles = [s["title"] for s in conn.execute(
        "SELECT title FROM agent_steps WHERE run_id = %s ORDER BY id", (res["run_id"],)).fetchall()]
    assert titles[1] == "🗺 План: Собрать факты → Сделать вывод"

    # ошибка модели -> понятный ответ и статус failed
    monkeypatch.setattr(llm, "chat", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("LLM 500")))
    res = agent_core.ask(conn, "привет")
    assert "Не получилось" in res["answer"]


def test_diary_picks_photos_for_events(conn, monkeypatch, tmp_path):  # noqa: F811
    from neuro import diary, llm
    monkeypatch.setattr(diary, "PHOTO_DIR", tmp_path)
    monkeypatch.setattr(diary, "place_name", lambda conn_, lat, lon: "Кофейня на Истикляль")
    for t in ("photos", "diary_days", "locations", "conversations"):
        conn.execute(f"DELETE FROM {t}")
    day = diary.local_day(datetime.now(timezone.utc)) - timedelta(days=1)
    start, _ = diary.day_bounds(day)
    t10 = start + timedelta(hours=10)
    conn.execute("""INSERT INTO conversations (device_id, started_at, last_activity, status, title, summary)
                    VALUES (%s, %s, %s, 'done', 'Встреча с Анной', 'Обсудили запуск NeuroSoft')""",
                 (DEVICE, t10, t10 + timedelta(minutes=40)))
    for i in range(4):  # 30 минут в одном месте
        conn.execute("INSERT INTO locations (device_id, client_id, ts, lat, lon) VALUES (%s, %s, %s, %s, %s)",
                     (DEVICE, f"l{i}", t10 + timedelta(minutes=10 * i), 41.0340, 28.9770 + i * 0.0001))
    jpeg = b"\xff\xd8\xff" + b"0" * 100
    on_event = diary.save_photo(conn, jpeg, t10 + timedelta(minutes=20), None, None)["id"]
    random_pic = diary.save_photo(conn, jpeg + b"1", start + timedelta(hours=3), None, None)["id"]
    screenshot = diary.save_photo(conn, jpeg + b"2", t10 + timedelta(minutes=5), None, None)["id"]
    assert diary.save_photo(conn, jpeg, None, None, None)["duplicate"]

    answers = {on_event: {"caption": "Кофе и ноутбук", "kind": "moment", "score": 8},
               random_pic: {"caption": "Тёмный кадр", "kind": "moment", "score": 7},
               screenshot: {"caption": "Скриншот почты", "kind": "screenshot", "score": 2}}
    current = {}
    monkeypatch.setattr(diary, "_prepare_image", lambda path: (b"jpeg", {}))

    def fake_chat(conn_, task, messages, **kw):
        assert task == "vision"
        return {"content": json.dumps(answers[current["id"]], ensure_ascii=False)}

    monkeypatch.setattr(llm, "chat", fake_chat)
    for p in conn.execute("SELECT * FROM photos ORDER BY id").fetchall():
        current["id"] = p["id"]
        diary.analyze_photo(conn, p)
    st = {r["id"]: r["status"] for r in conn.execute("SELECT id, status FROM photos").fetchall()}
    assert st[screenshot] == "skipped" and st[on_event] == "analyzed"

    m = diary.material(conn, day)
    linked = {p["id"]: p["event"] for p in m["photos"]}
    assert linked[on_event] == "разговор «Встреча с Анной»" and linked[random_pic] is None
    text = diary.material_text(m, 3)
    assert f"photo_id={on_event}" in text and f"photo_id={random_pic}" not in text
    assert "Кофейня на Истикляль" in text

    def fake_json(conn_, task, system, user, **kw):
        assert task == "diary"
        return {"title": "День запуска", "mood": "🚀 драйв", "summary": "Ты встретился с Анной.",
                "quote": "", "highlights": [
                    {"time": "10:00", "emoji": "☕", "title": "Встреча с Анной", "text": "Обсудили запуск.",
                     "kind": "conversation", "photo_id": on_event},
                    {"time": "03:00", "emoji": "🌙", "title": "Ночь", "text": "…", "kind": "photo",
                     "photo_id": random_pic}]}

    monkeypatch.setattr(llm, "chat_json", fake_json)
    assert diary.generate_day(conn, day)
    row = conn.execute("SELECT * FROM diary_days WHERE day = %s", (day,)).fetchone()
    assert row["title"] == "День запуска" and row["cover_photo_id"] == on_event
    assert [h["photo_id"] for h in row["highlights"]] == [on_event, None]  # не привязанное к событию фото отброшено
    assert row["places"][0]["name"] == "Кофейня на Истикляль"
    assert conn.execute("SELECT status FROM photos WHERE id = %s", (on_event,)).fetchone()["status"] == "done"
    assert not diary.generate_day(conn, day)  # без изменений — не пересчитываем


def test_task_feed_goes_to_integrations(conn, monkeypatch):  # noqa: F811
    from agent import worker
    from neuro import extensions as ext
    conn.execute("DELETE FROM extensions")
    conn.execute("DELETE FROM tasks")
    reg = {e["id"]: e for e in ext.registry()}
    for ext_id, settings in (("notion", {"token": "secret_x", "database_id": "db1", "title_property": "Название"}),
                             ("github", {"token": "gh", "owner": "neuro-codes", "repo": "neurosoft"})):
        m, src = ext.load_manifest(reg[ext_id])
        conn.execute("INSERT INTO extensions (id, manifest, settings, source_url, installed_at) "
                     "VALUES (%s, %s::jsonb, %s::jsonb, %s, now() - interval '1 hour')",
                     (ext_id, json.dumps(m, ensure_ascii=False), json.dumps(settings), src))
    conn.execute("INSERT INTO tasks (text, due, source, target) VALUES ('Подготовить отчёт', 'пятница', "
                 "'conversation', 'notion'), ('Починить синхронизацию', NULL, 'agent', 'github')")
    sent = []

    class R:
        status_code = 200

        def __init__(self, url):
            self.text = json.dumps({"url": "https://notion.so/p1"} if "notion" in url
                                   else {"html_url": "https://github.com/neuro-codes/neurosoft/issues/7"})

        def json(self):
            return json.loads(self.text)

    def fake_request(method, url, headers=None, json=None, timeout=None):
        sent.append((url, headers, json))
        return R(url)

    monkeypatch.setattr(ext.requests, "request", fake_request)
    worker.sync_task_integrations(conn)
    worker.sync_task_integrations(conn)  # повторно не отправляет
    notion = [s for s in sent if "notion" in s[0]]
    github = [s for s in sent if "github" in s[0]]
    assert len(notion) == 2 and len(github) == 1  # в GitHub — только задачи на разработку
    assert notion[0][1]["Authorization"] == "Bearer secret_x"
    assert "Название" in notion[0][2]["properties"]  # имя колонки из настроек подставилось в ключ
    assert github[0][0] == "https://api.github.com/repos/neuro-codes/neurosoft/issues"
    assert github[0][2]["title"] == "Починить синхронизацию"
    refs = {r["ext_id"]: r["ref"] for r in conn.execute("SELECT ext_id, ref FROM task_exports WHERE ok").fetchall()}
    assert refs["github"].endswith("/issues/7")
