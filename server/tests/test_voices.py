"""Голоса: кто говорит в разговоре, «Кто это?», подсказки имён, «Созвездие».

Тесты идут на запасном MFCC-отпечатке (SPEAKER_BACKEND=spectral) и синтетических «голосах»:
разная высота и форманты. Качество настоящей модели проверяется отдельно на записях людей.
"""
import os
import tempfile

import numpy as np
import pytest

from tests.test_pipeline import DEVICE, conn  # noqa: F401  (фикстура базы, окружение тестов)

SR = 16000
ANNA = dict(f0=210, formants=(800, 1200, 2900))
OWNER = dict(f0=110, formants=(500, 1500, 2500))
BORIS = dict(f0=150, formants=(350, 2200, 3000))


def synth(f0, formants, sec, seed=0, gain=6000):
    """Синтетический «голос»: гармоники с формантами, слоги 4 Гц, лёгкий шум."""
    rng = np.random.default_rng(seed)
    t = np.arange(int(sec * SR)) / SR
    phase = 2 * np.pi * np.cumsum(f0 * (1 + 0.03 * np.sin(2 * np.pi * 5 * t))) / SR
    x = np.zeros_like(t)
    for k in range(1, 40):
        fk = k * f0
        if fk > 7500:
            break
        amp = sum(np.exp(-((fk - f) ** 2) / (2 * 120 ** 2)) for f in formants) + 0.05
        x += amp / k ** 0.5 * np.sin(k * phase)
    x = x * (0.55 + 0.45 * np.sin(2 * np.pi * 4 * t + rng.uniform(0, 6)) ** 2) + 0.02 * rng.standard_normal(len(t))
    return (x / np.max(np.abs(x)) * gain).astype(np.int16)


def silence(sec):
    return (np.random.default_rng(7).standard_normal(int(sec * SR)) * 20).astype(np.int16)


def dialog(*parts):
    """[(голос, секунды), ...] с паузами 0.8 с -> (pcm, [(начало, конец)])"""
    pcm, spans, t = [], [], 0.0
    for i, (v, sec) in enumerate(parts):
        pcm.append(synth(**v, sec=sec, seed=i))
        spans.append((t, t + sec))
        pcm.append(silence(0.8))
        t += sec + 0.8
    return np.concatenate(pcm).tobytes(), spans


@pytest.fixture()
def clean(conn):  # noqa: F811
    conn.execute("TRUNCATE people, voiceprints, person_suggestions, audio_chunks, conversations, segments, tasks "
                 "RESTART IDENTITY CASCADE")
    conn.execute("DELETE FROM settings WHERE key LIKE 'voices.%%'")
    yield conn


class Timed:
    """Распознавание с разметкой по времени (как Whisper): одна фраза на реплику."""
    name = "timed"

    def __init__(self, spans):
        self.spans = spans

    def transcribe(self, pcm, conn=None):
        from stt.worker import Seg
        return [Seg(a + 0.05, b - 0.05, None, f"фраза номер {i + 1}, тут что-то сказали") for i, (a, b) in
                enumerate(self.spans)]


class Untimed:
    """Распознавание без времени: весь кусок одной строкой."""
    name = "untimed"
    calls = 0

    def transcribe(self, pcm, conn=None):
        from stt.worker import Seg
        Untimed.calls += 1
        return [Seg(0.0, len(pcm) / 2 / SR, None, f"реплика {Untimed.calls}")]


def run_chunk(conn, pcm, stt, minutes_ago=30, finalize=True):  # noqa: F811
    from stt import worker
    path = tempfile.mktemp(suffix=".pcm", dir=os.environ["AUDIO_DIR"])
    with open(path, "wb") as f:
        f.write(pcm)
    chunk = conn.execute(
        """INSERT INTO audio_chunks (device_id, started_at, duration_ms, path, status)
           VALUES (%s, now() - %s * interval '1 minute', %s, %s, 'processing')
           RETURNING id, device_id, started_at, duration_ms, path, attempts""",
        (DEVICE, minutes_ago, len(pcm) // 32, path)).fetchone()
    worker.process(conn, stt, chunk)
    if finalize:
        from neuro import voices
        conv = conn.execute("SELECT conversation_id FROM segments WHERE chunk_id = %s LIMIT 1", (chunk["id"],)).fetchone()
        if conv:
            voices.finalize_conversation(conn, conv["conversation_id"])
    return conn.execute(
        "SELECT id, speaker, person_id, voice_score, text, embedding IS NOT NULL AS has_emb FROM segments "
        "WHERE chunk_id = %s ORDER BY started_at, id", (chunk["id"],)).fetchall()


def test_split_turns_finds_voices_and_turns():
    from neuro import voices
    pcm, spans = dialog((OWNER, 3.0), (ANNA, 2.5), (OWNER, 2.0))
    turns, local = voices.split_turns(pcm)
    assert len(local) == 2
    assert [t.local for t in turns] == [0, 1, 0]
    for t, (a, b) in zip(turns, spans):
        assert abs(t.start / SR - a) < 0.3 and abs(t.end / SR - b) < 0.3


def test_short_reply_sticks_to_nearest_voice():
    from neuro import voices
    pcm, _ = dialog((OWNER, 3.0), (ANNA, 0.6), (ANNA, 2.5))
    turns, local = voices.split_turns(pcm)
    assert len(local) == 2
    assert [t.local for t in turns] == [0, 1]  # короткое «Да.» Анны склеилось с её репликой


def test_conversation_gets_people_and_learns(clean):
    conn = clean
    pcm, spans = dialog((OWNER, 3.0), (ANNA, 2.5), (OWNER, 2.0), (ANNA, 2.0))
    rows = run_chunk(conn, pcm, Timed(spans))
    assert len(rows) == 4
    owner_id, anna_id = rows[0]["person_id"], rows[1]["person_id"]
    assert owner_id and anna_id and owner_id != anna_id
    assert [r["person_id"] for r in rows] == [owner_id, anna_id, owner_id, anna_id]
    assert all(r["has_emb"] for r in rows)
    people = conn.execute("SELECT id, number, name FROM people ORDER BY id").fetchall()
    assert [p["number"] for p in people] == [1, 2]

    # второй разговор с теми же голосами: новых людей нет, фразы подписаны теми же
    pcm2, spans2 = dialog((ANNA, 2.5), (OWNER, 3.0))
    rows2 = run_chunk(conn, pcm2, Timed(spans2), minutes_ago=10)
    assert [r["person_id"] for r in rows2] == [anna_id, owner_id]
    assert conn.execute("SELECT count(*) AS n FROM people").fetchone()["n"] == 2
    # голосовые записи для «Кто это?» сохранены
    from neuro import voices
    assert voices.sample_path(owner_id).exists() and voices.sample_path(anna_id).exists()


def test_untimed_provider_is_asked_per_turn(clean):
    conn = clean
    Untimed.calls = 0
    pcm, _ = dialog((OWNER, 3.0), (BORIS, 2.5), (OWNER, 2.0))
    rows = run_chunk(conn, pcm, Untimed())
    assert Untimed.calls == 1 + 3  # весь кусок, потом каждая реплика отдельно
    assert len(rows) == 3
    assert rows[0]["person_id"] == rows[2]["person_id"] != rows[1]["person_id"]


def test_voices_switched_off(clean):
    conn = clean
    from neuro import people
    people.set_settings(conn, enabled=False)
    pcm, spans = dialog((OWNER, 3.0), (ANNA, 2.5))
    rows = run_chunk(conn, pcm, Timed(spans))
    assert len(rows) == 2 and all(r["person_id"] is None for r in rows)
    assert conn.execute("SELECT count(*) AS n FROM people").fetchone()["n"] == 0


def test_owner_names_merge_forget_and_privacy(clean):
    conn = clean
    from neuro import people, voices
    pcm, spans = dialog((OWNER, 3.0), (ANNA, 2.5), (BORIS, 2.5))
    rows = run_chunk(conn, pcm, Timed(spans))
    owner_id, anna_id, boris_id = (r["person_id"] for r in rows)

    p = people.update_person(conn, owner_id, {"is_owner": True})
    assert p["is_owner"]
    p = people.update_person(conn, anna_id, {"name": "Анна"})
    assert p["name"] == "Анна" and p["same_name"] is None
    assert conn.execute("SELECT bool_and(confirmed) AS ok FROM voiceprints WHERE person_id = %s",
                        (anna_id,)).fetchone()["ok"]

    listing = people.list_people(conn)
    assert [x["id"] for x in listing["people"]][0] == owner_id  # владелец первым
    assert listing["settings"] == {"enabled": True, "others": True}

    # «Кто это?» для одной фразы: фраза Бориса — на самом деле Анна
    res = people.assign_segment(conn, rows[2]["id"], person_id=anna_id)
    assert res["person_id"] == anna_id
    assert conn.execute("SELECT person_id FROM segments WHERE id = %s", (rows[2]["id"],)).fetchone()["person_id"] \
        == anna_id
    # новая фраза — новым человеком по имени
    res = people.assign_segment(conn, rows[2]["id"], name="Вера")
    vera = conn.execute("SELECT id, name, centroid IS NOT NULL AS voiced FROM people WHERE id = %s",
                        (people.by_name(conn, "Вера")[0],)).fetchone()
    assert res["person_id"] == vera["id"] and vera["voiced"]

    # объединить: Вера — это Борис
    voices.merge_people(conn, vera["id"], boris_id)
    merged = conn.execute("SELECT name FROM people WHERE id = %s", (boris_id,)).fetchone()
    assert merged["name"] == "Вера"
    assert conn.execute("SELECT count(*) AS n FROM people WHERE id = %s", (vera["id"],)).fetchone()["n"] == 0

    # «не запоминать собеседников»: безымянных забываем, названных и владельца — нет
    anon = voices.new_unknown(conn, np.ones(40, dtype=np.float32), 3.0, 100.0)
    people.set_settings(conn, others=False)
    left = {r["id"] for r in conn.execute("SELECT id FROM people").fetchall()}
    assert anon["id"] not in left and {owner_id, anna_id, boris_id} <= left

    # забыть человека: подписи фраз и отпечатки снимаются
    voices.forget_person(conn, anna_id)
    assert conn.execute("SELECT count(*) AS n FROM segments WHERE person_id = %s", (anna_id,)).fetchone()["n"] == 0
    assert conn.execute("SELECT count(*) AS n FROM voiceprints WHERE person_id = %s", (anna_id,)).fetchone()["n"] == 0
    assert not voices.sample_path(anna_id).exists()

    # забыть всё
    assert voices.forget_all(conn) == 2
    assert conn.execute("SELECT count(*) AS n FROM segments WHERE embedding IS NOT NULL").fetchone()["n"] == 0


def test_consolidate_joins_duplicate_unknown_voices(clean):
    conn = clean
    from neuro import voices
    m = voices.model()
    a = voices.new_unknown(conn, m.embed(synth(**ANNA, sec=3, seed=1)), 3.0, 500)
    b = voices.new_unknown(conn, m.embed(synth(**ANNA, sec=3, seed=2)), 3.0, 500)
    c = voices.new_unknown(conn, m.embed(synth(**BORIS, sec=3, seed=3)), 3.0, 500)
    assert voices.consolidate(conn) == 1
    left = [r["id"] for r in conn.execute("SELECT id FROM people ORDER BY id").fetchall()]
    assert c["id"] in left and len(left) == 2 and (a["id"] in left) != (b["id"] in left)


def test_summary_names_tasks_topics_and_suggestions(clean, monkeypatch):
    conn = clean
    from agent import worker as agent
    from neuro import llm, people
    pcm, spans = dialog((OWNER, 3.0), (ANNA, 2.5))
    rows = run_chunk(conn, pcm, Timed(spans), minutes_ago=30)
    owner_id, anna_id = rows[0]["person_id"], rows[1]["person_id"]
    people.update_person(conn, owner_id, {"is_owner": True, "name": "Женя"})
    conv_id = conn.execute("SELECT conversation_id FROM segments WHERE id = %s", (rows[0]["id"],)).fetchone()[
        "conversation_id"]
    seen = {}

    def fake_json(conn_, task, system, user, **kw):
        seen["system"], seen["user"] = system, user
        return {"title": "Отчёт к пятнице", "emoji": "📊", "summary": "Договорились об отчёте.",
                "tasks": [{"text": "Подготовить отчёт", "due": "пятница", "who": "Голос 2", "kind": "mine",
                           "confidence": 0.9, "evidence": "Анна, сделаешь? — Да, к пятнице", "target": "notion"}],
                "memories": [], "topics": ["Отчёт", "сроки", "отчёт"],
                "speakers": [{"voice": "Голос 2", "name": "Анна", "evidence": "Анна, сделаешь?"}]}

    monkeypatch.setattr(llm, "configured", lambda: True)
    monkeypatch.setattr(llm, "chat_json", fake_json)
    conn.execute("UPDATE conversations SET status = 'processing' WHERE id = %s", (conv_id,))
    agent.summarize_one(conn, conv_id)
    # голос Анны пока звучал один раз — подсказку не показываем
    assert people.list_people(conn)["suggestions"] == []
    pcm2, spans2 = dialog((ANNA, 2.5), (OWNER, 3.0))
    rows2 = run_chunk(conn, pcm2, Timed(spans2), minutes_ago=10)
    assert rows2[0]["person_id"] == anna_id  # второй разговор — тот же голос

    assert "Владелец (Женя):" in seen["user"] and "Голос 2:" in seen["user"]
    assert '"speakers"' in seen["system"] and '"topics"' in seen["system"]
    conv = conn.execute("SELECT topics FROM conversations WHERE id = %s", (conv_id,)).fetchone()
    assert conv["topics"] == ["отчёт", "сроки"]
    task = conn.execute("SELECT assignee, person_id, kind, status FROM tasks WHERE conversation_id = %s",
                        (conv_id,)).fetchone()
    assert task["person_id"] == anna_id and task["kind"] == "waiting" and task["status"] == "open"
    sug = people.list_people(conn)["suggestions"]
    assert len(sug) == 1 and sug[0]["person_id"] == anna_id and sug[0]["name"] == "Анна"

    # повторный пересчёт итогов не дублирует подсказку
    agent.summarize_one(conn, conv_id)
    assert len(people.list_people(conn)["suggestions"]) == 1

    res = people.answer_suggestion(conn, sug[0]["id"], True)
    assert res["status"] == "accepted" and not res["merged"]
    assert conn.execute("SELECT name FROM people WHERE id = %s", (anna_id,)).fetchone()["name"] == "Анна"
    assert people.list_people(conn)["suggestions"] == []

    # теперь в расшифровке — имя
    segs = conn.execute(people.SEGMENT_SELECT + " WHERE s.conversation_id = %s ORDER BY s.started_at",
                        (conv_id,)).fetchall()
    from neuro.assistant import transcript_text
    assert "Анна:" in transcript_text(segs)

    # агент знает людей
    from neuro import agent_core
    overview = agent_core.run_builtin(conn, "people", {}, None)
    assert "Анна" in overview and "Владелец (Женя)" in overview
    about = agent_core.run_builtin(conn, "person_conversations", {"name": "анна"}, None)
    assert "Отчёт к пятнице" in about and "Подготовить отчёт" in about

    # граф: люди, темы, разговор со связями
    g = people.graph(conn, 30)
    assert {p["id"] for p in g["people"]} == {owner_id, anna_id}
    assert g["topics"][0]["name"] in ("отчёт", "сроки")
    assert sorted(g["conversations"][0]["people"]) == sorted([owner_id, anna_id])


class Asgi:
    """Мини-клиент ASGI без httpx: запрос прямо в приложение Starlette (без lifespan)."""

    def __init__(self, app, token="test-token"):
        self.app, self.token = app, token

    def request(self, method, path, json_body=None, auth=True):
        import asyncio
        import json as _json
        from urllib.parse import urlsplit
        url = urlsplit(path)
        body = _json.dumps(json_body).encode() if json_body is not None else b""
        headers = [(b"content-type", b"application/json")]
        if auth:
            headers.append((b"authorization", f"Bearer {self.token}".encode()))
        scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": method,
                 "scheme": "http", "path": url.path, "raw_path": url.path.encode(), "query_string": url.query.encode(),
                 "root_path": "", "headers": headers, "client": ("test", 1), "server": ("test", 80)}
        out = {"status": None, "body": b""}
        sent = False

        async def receive():
            nonlocal sent
            if not sent:
                sent = True
                return {"type": "http.request", "body": body, "more_body": False}
            await asyncio.sleep(3600)

        async def send(msg):
            if msg["type"] == "http.response.start":
                out["status"] = msg["status"]
            elif msg["type"] == "http.response.body":
                out["body"] += msg.get("body", b"")

        asyncio.run(self.app(scope, receive, send))
        return Resp(out["status"], out["body"])

    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, json=None, **kw):
        return self.request("POST", path, json_body=json if json is not None else {}, **kw)

    def delete(self, path, **kw):
        return self.request("DELETE", path, **kw)


class Resp:
    def __init__(self, status, content):
        self.status_code, self.content = status, content

    def json(self):
        import json as _json
        return _json.loads(self.content)


def test_people_http_api(clean):
    conn = clean
    from gateway import app as gw
    pcm, spans = dialog((OWNER, 3.0), (ANNA, 2.5))
    rows = run_chunk(conn, pcm, Timed(spans), minutes_ago=5)
    owner_id, anna_id = rows[0]["person_id"], rows[1]["person_id"]
    client = Asgi(gw.app)
    r = client.get("/api/people")
    assert r.status_code == 200 and r.json()["people"] == []  # голоса прозвучали один раз — пока не показываем
    assert client.get("/api/people", auth=False).status_code == 401

    r = client.post(f"/api/people/{anna_id}", json={"name": "Анна"})
    assert r.status_code == 200 and r.json()["name"] == "Анна"
    r = client.get(f"/api/people/{anna_id}/sample")
    assert r.status_code == 200 and r.content[:4] == b"RIFF"

    conv_id = conn.execute("SELECT conversation_id FROM segments WHERE id = %s", (rows[0]["id"],)).fetchone()[
        "conversation_id"]
    conv = client.get(f"/api/conversations/{conv_id}").json()
    assert [s["person_id"] for s in conv["segments"]] == [owner_id, anna_id]
    assert {p["id"] for p in conv["people"]} == {owner_id, anna_id}
    assert conv["segments"][0]["sure"] is True and conv["segments"][0]["has_audio"] is True

    assert {p["id"]: p["transient"] for p in conv["people"]} == {owner_id: True, anna_id: False}
    lst = client.get("/api/conversations").json()["items"]
    assert {p["id"] for p in lst[0]["people"]} == {anna_id}  # единичный безымянный голос в ленте не показываем

    r = client.get(f"/api/segments/{rows[1]['id']}/audio")
    assert r.status_code == 200 and r.content[:4] == b"RIFF" and len(r.content) > 32000

    r = client.post(f"/api/segments/{rows[1]['id']}/person", json={"person_id": None})
    assert r.json()["person_id"] is None

    r = client.post("/api/people/settings", json={"others": False})
    assert r.json() == {"enabled": True, "others": False}

    r = client.get("/api/graph?days=7")
    assert r.status_code == 200 and "conversations" in r.json()

    r = client.post(f"/api/people/{anna_id}/merge", json={"into": owner_id})
    assert r.status_code == 200 and r.json()["id"] == owner_id

    r = client.delete(f"/api/people/{owner_id}")
    assert r.status_code == 200
    assert client.get(f"/api/people/{owner_id}").status_code == 404
    assert client.post("/api/people/forget").json() == {"forgotten": 0}


def test_button_question_teaches_owner_voice(clean):
    conn = clean
    from neuro import voices
    for f in voices.OWNER_DIR.glob("*.pcm") if voices.OWNER_DIR.exists() else []:
        f.unlink()
    # был разговор: голоса безымянные
    pcm, spans = dialog((OWNER, 3.0), (ANNA, 2.5))
    rows = run_chunk(conn, pcm, Timed(spans))
    owner_voice = rows[0]["person_id"]
    # вопрос по кнопке голосом владельца -> этот человек становится «Я»
    voices.queue_owner_sample(synth(**OWNER, sec=3, seed=11).tobytes())
    assert voices.enroll_owner(conn) == 1
    owner = conn.execute("SELECT id FROM people WHERE is_owner").fetchone()
    assert owner["id"] == owner_voice
    # чужой голос по кнопке не портит «Я», когда отпечаток уже устоялся
    for i in range(3):
        voices.queue_owner_sample(synth(**OWNER, sec=3, seed=20 + i).tobytes())
    voices.enroll_owner(conn)
    before = conn.execute("SELECT count(*) AS n FROM voiceprints WHERE person_id = %s", (owner_voice,)).fetchone()["n"]
    voices.queue_owner_sample(synth(**BORIS, sec=3, seed=30).tobytes())
    voices.enroll_owner(conn)
    after = conn.execute("SELECT count(*) AS n FROM voiceprints WHERE person_id = %s", (owner_voice,)).fetchone()["n"]
    assert after == before


def test_short_voice_does_not_become_a_person(clean, monkeypatch):
    """Голос, сказавший пару слов, не заводит «Голос N» (раньше так и плодились дубли)."""
    conn = clean
    from neuro import voices
    monkeypatch.setattr(voices, "MIN_NEW_PERSON_SEC", 8.0)
    pcm, spans = dialog((OWNER, 5.0), (ANNA, 2.5), (OWNER, 4.0))
    rows = run_chunk(conn, pcm, Timed(spans))
    assert rows[0]["person_id"] and rows[0]["person_id"] == rows[2]["person_id"]
    assert rows[1]["person_id"] is None  # Анна говорила 2.5 с — пока без подписи
    assert conn.execute("SELECT count(*) AS n FROM people").fetchone()["n"] == 1


def test_whole_conversation_decides_and_dedupe_suggests(clean):
    """Голоса решаются по всему разговору; похожий на названного — подсказка, безымянные дубли склеиваются."""
    conn = clean
    from neuro import people, voices
    pcm, spans = dialog((OWNER, 3.0), (ANNA, 2.5))
    rows = run_chunk(conn, pcm, Timed(spans), minutes_ago=40)
    anna = rows[1]["person_id"]
    people.update_person(conn, anna, {"name": "Анна"})
    # дубль Анны: отдельный безымянный человек с тем же голосом
    m = voices.model()
    dup = voices.new_unknown(conn, m.embed(synth(**ANNA, sec=3, seed=41)), 3.0, 100.0)
    assert voices.dedupe(conn)["suggested"] == 0  # дубль ни разу не звучал в разговорах — не спрашиваем
    conv_ids = [r["id"] for r in conn.execute("SELECT id FROM conversations ORDER BY id").fetchall()]
    for i, cid in enumerate([conv_ids[0], conv_ids[0]]):
        conn.execute("""INSERT INTO conversations (device_id, started_at, last_activity, status)
                        VALUES ('x', now(), now(), 'done') RETURNING id""")
    extra = [r["id"] for r in conn.execute("SELECT id FROM conversations ORDER BY id DESC LIMIT 2").fetchall()]
    for cid in extra:
        conn.execute("""INSERT INTO segments (conversation_id, device_id, started_at, ended_at, text, person_id)
                        VALUES (%s, 'x', now(), now(), 'привет', %s)""", (cid, dup["id"]))
    res = voices.dedupe(conn)
    assert res["suggested"] == 1
    sug = people.list_people(conn)["suggestions"]
    assert sug[0]["person_id"] == dup["id"] and sug[0]["name"] == "Анна"
    assert people.answer_suggestion(conn, sug[0]["id"], True)["merged"] is True
    assert conn.execute("SELECT count(*) AS n FROM people WHERE id = %s", (dup["id"],)).fetchone()["n"] == 0
    # два безымянных одинаковых — склеиваются сами
    a = voices.new_unknown(conn, m.embed(synth(**BORIS, sec=3, seed=51)), 3.0, 100.0)
    b = voices.new_unknown(conn, m.embed(synth(**BORIS, sec=3, seed=52)), 3.0, 100.0)
    assert voices.dedupe(conn)["merged"] == 1
    left = {r["id"] for r in conn.execute("SELECT id FROM people").fetchall()}
    assert (a["id"] in left) != (b["id"] in left)
