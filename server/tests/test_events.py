"""События дня: фрагменты -> события, мусор -> корзина (кроме фрагментов с задачами), ночная перепроверка задач."""
from datetime import datetime, timedelta, timezone

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)
from tests.test_voices import Asgi


def _conv(conn, start, minutes, title, words="обсудили отчёт и сроки сдачи", tasks=0):  # noqa: F811
    cid = conn.execute(
        """INSERT INTO conversations (device_id, started_at, last_activity, status, title, summary)
           VALUES ('dev', %s, %s, 'done', %s, %s) RETURNING id""",
        (start, start + timedelta(minutes=minutes), title, title + ".")).fetchone()["id"]
    conn.execute("""INSERT INTO segments (conversation_id, device_id, started_at, ended_at, text)
                    VALUES (%s, 'dev', %s, %s, %s)""", (cid, start, start + timedelta(minutes=1), words))
    for i in range(tasks):
        conn.execute("""INSERT INTO tasks (conversation_id, text, status, source, evidence)
                        VALUES (%s, %s, 'open', 'conversation', 'сделаю')""", (cid, f"задача {i}"))
    return cid


def test_fragments_become_events_and_junk_goes_to_trash(conn, monkeypatch):  # noqa: F811
    from neuro import diary, events, llm
    conn.execute("TRUNCATE day_events, conversations, segments, tasks, settings RESTART IDENTITY CASCADE")
    day = diary.local_day(datetime.now(timezone.utc) - timedelta(days=1))
    base = diary.day_bounds(day)[0] + timedelta(hours=12)
    a = _conv(conn, base, 10, "Обед: начало")
    b = _conv(conn, base + timedelta(minutes=14), 12, "Обед: про отпуск")
    noise = _conv(conn, base + timedelta(minutes=40), 1, "Шум", words="а")
    tv = _conv(conn, base + timedelta(minutes=50), 2, "Телевизор", tasks=1)  # с задачей — не мусор
    lost = _conv(conn, base + timedelta(hours=3), 5, "Звонок маме")

    def fake(conn_, task, system, user, **kw):
        assert task == "summary" and "#%d" % a in user and "задач 1" in user
        return {"events": [{"id": None, "title": "Обед с Анной", "emoji": "🍝", "kind": "встреча", "vivid": 0.85,
                            "summary": "Обедали и обсудили отпуск.", "fragments": [a, b]}],
                "junk": [{"fragment": noise, "reason": "шум"}, {"fragment": tv, "reason": "телевизор"}]}

    monkeypatch.setattr(llm, "configured", lambda: True)
    monkeypatch.setattr(llm, "chat_json", fake)
    res = events.group_day(conn, day)
    assert res["junk"] == 1
    rows = {r["id"]: r for r in conn.execute("SELECT id, event_id, trashed_at FROM conversations").fetchall()}
    assert rows[a]["event_id"] == rows[b]["event_id"] is not None
    assert rows[noise]["trashed_at"] is not None and rows[tv]["trashed_at"] is None
    assert rows[lost]["event_id"] and rows[lost]["event_id"] != rows[a]["event_id"]  # пропущенный — отдельным событием
    ev = conn.execute("SELECT * FROM day_events WHERE id = %s", (rows[a]["event_id"],)).fetchone()
    assert ev["title"] == "Обед с Анной" and ev["started_at"] == base
    assert ev["ended_at"] == base + timedelta(minutes=26)

    client = Asgi(__import__("gateway.app", fromlist=["app"]).app)
    data = client.get(f"/api/events?day={day}").json()
    titles = [e["title"] for e in data["events"]]
    assert "Обед с Анной" in titles and "Звонок маме" in titles
    lunch = next(e for e in data["events"] if e["title"] == "Обед с Анной")
    assert sorted(f["id"] for f in lunch["fragments"]) == sorted([a, b])
    # карта воспоминаний: яркость — оценка модели плюс признаки (длительность и т. п.)
    vivid = {e["title"]: e["vivid"] for e in client.get("/api/graph?days=30").json()["events"]}
    assert vivid["Обед с Анной"] >= 0.85 > vivid["Звонок маме"] >= 0.3
    trash = client.get("/api/trash").json()["items"]
    assert [t["id"] for t in trash] == [noise] and trash[0]["junk_reason"] == "шум"
    assert client.post(f"/api/conversations/{noise}/restore", json={}).json() == {"restored": 1}
    assert client.get("/api/trash").json()["items"] == []


def test_night_review_moves_discussions_out_of_tasks(conn, monkeypatch):  # noqa: F811
    from neuro import diary, events, llm
    conn.execute("TRUNCATE day_events, conversations, segments, tasks RESTART IDENTITY CASCADE")
    day = diary.local_day(datetime.now(timezone.utc))
    c = _conv(conn, datetime.now(timezone.utc) - timedelta(hours=2), 5, "Билеты")
    ids = [conn.execute("""INSERT INTO tasks (conversation_id, text, status, source, evidence)
                           VALUES (%s, %s, 'open', 'conversation', %s) RETURNING id""", (c, t, e)).fetchone()["id"]
           for t, e in (("Купить билеты", "давай куплю билеты"), ("Посмотреть билеты на сайте", "может, посмотрим"),
                        ("Купить билеты на концерт", "куплю билеты"))]

    def fake(conn_, task, system, user, **kw):
        return {"decisions": [{"id": ids[0], "verdict": "keep"}, {"id": ids[1], "verdict": "drop"},
                              {"id": ids[2], "verdict": "keep", "same_as": ids[0]}]}

    monkeypatch.setattr(llm, "configured", lambda: True)
    monkeypatch.setattr(llm, "chat_json", fake)
    res = events.review_tasks(conn, day)
    assert res["reviewed"] == 3
    st = {r["id"]: r["status"] for r in conn.execute("SELECT id, status FROM tasks").fetchall()}
    assert st == {ids[0]: "open", ids[1]: "dismissed", ids[2]: "dismissed"}
    assert events.review_tasks(conn, day)["reviewed"] == 0  # второй раз не трогаем
