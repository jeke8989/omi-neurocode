"""Срок хранения звука: «удалять сразу после расшифровки» и N дней — выбирается в приложении."""
import os
from datetime import datetime, timedelta, timezone

from tests.test_pipeline import AUDIO_DIR, DEVICE, conn  # noqa: F401  (фикстура базы, окружение тестов)
from tests.test_voices import Asgi


def _chunk(conn, name: str, age_days: float, status: str) -> tuple[int, str]:  # noqa: F811
    os.makedirs(os.path.join(AUDIO_DIR, DEVICE), exist_ok=True)
    path = os.path.join(AUDIO_DIR, DEVICE, f"retention-{name}.pcm")
    with open(path, "wb") as f:
        f.write(b"\x00\x00" * 1600)
    started = datetime.now(timezone.utc) - timedelta(days=age_days)
    cid = conn.execute(
        """INSERT INTO audio_chunks (device_id, started_at, duration_ms, path, status)
           VALUES (%s, %s, 100, %s, %s) RETURNING id""", (DEVICE, started, path, status)).fetchone()["id"]
    return cid, path


def _stored(conn, cid: int) -> bool:  # noqa: F811
    return conn.execute("SELECT path FROM audio_chunks WHERE id = %s", (cid,)).fetchone()["path"] is not None


def _keep(conn, days):  # noqa: F811
    if days is None:
        conn.execute("DELETE FROM settings WHERE key = 'audio.keep_days'")
    else:
        conn.execute("INSERT INTO settings (key, value) VALUES ('audio.keep_days', %s) "
                     "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", (str(days),))


def test_delete_right_after_transcription(conn, monkeypatch):  # noqa: F811
    from neuro import config, retention
    monkeypatch.setattr(config, "KEEP_AUDIO_DAYS", 3)
    done, done_path = _chunk(conn, "done", 0, "done")
    pending, _ = _chunk(conn, "pending", 0, "processing")

    _keep(conn, None)  # ничего не выбрано — как в .env, сразу не удаляем
    assert retention.keep_days(conn) == 3
    assert retention.after_transcribed(conn, done) is False and _stored(conn, done)

    _keep(conn, 0)
    assert retention.after_transcribed(conn, done) is True
    assert not _stored(conn, done) and not os.path.exists(done_path)
    assert retention.after_transcribed(conn, pending) is False and _stored(conn, pending)  # ещё не расшифрован
    _keep(conn, None)


def test_cleanup_follows_chosen_days(conn, monkeypatch):  # noqa: F811
    from agent import worker as agent
    from neuro import config
    monkeypatch.setattr(config, "KEEP_AUDIO_DAYS", 3)
    two_days, _ = _chunk(conn, "two-days", 2, "done")
    fresh_failed, _ = _chunk(conn, "fresh-failed", 0.5, "failed")

    agent.cleanup(conn)  # по умолчанию 3 дня
    assert _stored(conn, two_days)

    _keep(conn, 1)
    agent.cleanup(conn)
    assert not _stored(conn, two_days)

    _keep(conn, 0)
    agent.cleanup(conn)
    assert _stored(conn, fresh_failed)  # неудачный кусок держим хотя бы сутки
    _keep(conn, None)


def test_features_endpoint_saves_and_validates(conn):  # noqa: F811
    from gateway import app as gw
    client = Asgi(gw.app)
    r = client.get("/api/features")
    assert r.status_code == 200 and r.json()["audio_keep_choices"] == [0, 1, 3, 7, 30]
    r = client.post("/api/features", json={"audio_keep_days": 0})
    assert r.status_code == 200 and r.json()["audio_keep_days"] == 0
    for bad in (5, "abc", None, -1):
        assert client.post("/api/features", json={"audio_keep_days": bad}).status_code == 400
    assert client.get("/api/features").json()["audio_keep_days"] == 0
    _keep(conn, None)
