"""Задачи: только договорённости, без дублей, «напомни…» — сразу, только голосом владельца."""
from datetime import datetime, timedelta, timezone

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)


def _seg(conn, text, person_id=None, seconds_ago=120, device="dev"):  # noqa: F811
    t = datetime.now(timezone.utc) - timedelta(seconds=seconds_ago)
    return conn.execute(
        """INSERT INTO segments (device_id, started_at, ended_at, text, person_id)
           VALUES (%s, %s, %s, %s, %s) RETURNING id""", (device, t, t + timedelta(seconds=3), text, person_id)
    ).fetchone()["id"]


def test_reminder_phrase_creates_task_only_for_owner(conn, monkeypatch):  # noqa: F811
    from agent import worker as agent
    from neuro import llm
    conn.execute("TRUNCATE tasks, segments, people, voiceprints RESTART IDENTITY CASCADE")
    owner = conn.execute("INSERT INTO people (number, is_owner) VALUES (1, true) RETURNING id").fetchone()["id"]
    other = conn.execute("INSERT INTO people (number, name) VALUES (2, 'Боря') RETURNING id").fetchone()["id"]
    calls = []

    def fake(conn_, task, system, user, **kw):
        calls.append(user)
        assert "Открытые задачи" in user or "Разговор был" in user
        return {"tasks": [{"text": "Позвонить Ивану", "kind": "reminder", "who": "Владелец",
                           "due": "завтра в 10", "due_at": "2030-01-02T10:00", "confidence": 0.9,
                           "evidence": "напомни мне завтра в 10 позвонить Ивану"}]}

    monkeypatch.setattr(llm, "configured", lambda: True)
    monkeypatch.setattr(llm, "chat_json", fake)
    _seg(conn, "Напомни мне, пожалуйста, завтра в 10 позвонить Ивану", owner, seconds_ago=200)
    _seg(conn, "Напомни ему про отчёт", other, seconds_ago=150)       # чужой голос — не наша задача
    _seg(conn, "Создай задачу купить билеты", owner, seconds_ago=10)  # слишком свежая — ждём продолжения
    agent.quick_tasks(conn)
    rows = conn.execute("SELECT text, kind, person_id, due_at, source, status FROM tasks").fetchall()
    assert len(calls) == 1 and len(rows) == 1
    t = rows[0]
    assert t["kind"] == "reminder" and t["person_id"] == owner and t["source"] == "voice" and t["status"] == "open"
    assert t["due_at"].year == 2030
    # повтор — та же задача (модель вернула номер), а не дубль
    tid = conn.execute("SELECT id FROM tasks").fetchone()["id"]

    def again(conn_, task, system, user, **kw):
        assert f"{tid}: Позвонить Ивану" in user
        return {"tasks": [{"text": "Позвонить Ивану", "kind": "reminder", "confidence": 0.9, "same_as": tid,
                           "evidence": "напомни позвонить Ивану", "due_at": "2030-01-02T11:00"}]}

    monkeypatch.setattr(llm, "chat_json", again)
    for r in conn.execute("SELECT id, text FROM segments").fetchall():  # тексты зашифрованы — фильтр здесь
        if r["text"].startswith("Создай"):
            conn.execute("UPDATE segments SET started_at = started_at - interval '1 minute' WHERE id = %s", (r["id"],))
    _seg(conn, "Напомни позвонить Ивану", owner, seconds_ago=100)
    agent.quick_tasks(conn)
    assert conn.execute("SELECT count(*) AS n FROM tasks").fetchone()["n"] == 1


def test_assignee_labels_never_leak(conn):  # noqa: F811
    from neuro import tasks
    conn.execute("TRUNCATE tasks, people RESTART IDENTITY CASCADE")
    out = tasks.classify(conn, [
        {"text": "Купить билеты", "who": "Спикер 1", "confidence": 0.9, "evidence": "куплю билеты"},
        {"text": "Посмотреть сайт", "who": "Владелец", "confidence": 0.9, "evidence": "посмотрю сайт"},
        {"text": "Может, сходить в кино", "confidence": 0.3, "evidence": "может сходим"},
    ], {}, None)
    assert [t["text"] for t in out] == ["Купить билеты", "Посмотреть сайт"]
    assert all(t["assignee"] is None for t in out)  # «Спикер 1» и «Владелец» — служебные подписи


def test_removed_task_is_not_created_again(conn):  # noqa: F811
    from neuro import tasks as tasklib
    conn.execute("TRUNCATE conversations, tasks RESTART IDENTITY CASCADE")
    cid = conn.execute("""INSERT INTO conversations (device_id, started_at, last_activity, status)
                          VALUES ('d', now(), now(), 'done') RETURNING id""").fetchone()["id"]
    conn.execute("""INSERT INTO tasks (conversation_id, text, status, source)
                    VALUES (%s, 'Купить билеты на концерт', 'dismissed', 'conversation')""", (cid,))
    base = {"kind": "mine", "person_id": None, "assignee": None, "due": None, "due_at": None, "confidence": 0.9,
            "evidence": "куплю", "status": "open", "same_as": None, "target": "notion"}
    res = tasklib.save(conn, cid, [{**base, "text": "Купить билеты на концерт."},
                                   {**base, "text": "Позвонить Анне"}], "conversation")
    assert res == {"created": 1, "updated": 0}
    texts = [r["text"] for r in conn.execute("SELECT text FROM tasks WHERE status = 'open'").fetchall()]
    assert texts == ["Позвонить Анне"]
