"""Карта: маршрут дня, места событий и разговоров, плитки подложки через свой сервер (с кешем)."""
from datetime import datetime, timedelta

from tests.test_pipeline import conn  # noqa: F401  (фикстура базы, окружение тестов)
from tests.test_voices import Asgi

PNG = b"\x89PNG\r\n\x1a\n" + b"0" * 100


def test_day_map_places_events_and_conversation(conn, monkeypatch, tmp_path):  # noqa: F811
    from neuro import diary, geo
    conn.execute("TRUNCATE day_events, conversations, segments, locations, places RESTART IDENTITY CASCADE")
    day = diary.local_day(datetime.now(diary.TZ) - timedelta(days=1))
    t0 = diary.day_bounds(day)[0] + timedelta(hours=12)
    # 40 минут в кафе, потом дорога
    for i in range(40):
        conn.execute("INSERT INTO locations (device_id, client_id, ts, lat, lon) VALUES ('d', %s, %s, %s, %s)",
                     (f"a{i}", t0 + timedelta(minutes=i), 55.7558 + i * 1e-6, 37.6173))
    for i in range(10):
        conn.execute("INSERT INTO locations (device_id, client_id, ts, lat, lon) VALUES ('d', %s, %s, %s, %s)",
                     (f"b{i}", t0 + timedelta(minutes=41 + i), 55.76 + i * 0.002, 37.62 + i * 0.002))
    conn.execute("INSERT INTO places (key, name) VALUES ('55.756,37.617', 'Кафе «Пушкин»')")
    cid = conn.execute("""INSERT INTO conversations (device_id, started_at, last_activity, status, title, emoji)
                          VALUES ('d', %s, %s, 'done', 'Обед', '🍝') RETURNING id""",
                       (t0 + timedelta(minutes=5), t0 + timedelta(minutes=30))).fetchone()["id"]
    eid = conn.execute("""INSERT INTO day_events (day, started_at, ended_at, title, emoji)
                          VALUES (%s, %s, %s, 'Обед с Анной', '🍝') RETURNING id""",
                       (day, t0 + timedelta(minutes=5), t0 + timedelta(minutes=30))).fetchone()["id"]
    conn.execute("UPDATE conversations SET event_id = %s WHERE id = %s", (eid, cid))
    monkeypatch.setattr(diary, "place_name", lambda c, lat, lon, network=True:
                        "Кафе «Пушкин»" if abs(lat - 55.7558) < 0.001 else None)
    client = Asgi(__import__("gateway.app", fromlist=["app"]).app)
    m = client.get(f"/api/map?day={day}").json()
    assert 5 <= len(m["track"]) <= 50
    assert m["places"][0]["name"] == "Кафе «Пушкин»"
    assert m["events"][0]["id"] == eid and abs(m["events"][0]["lat"] - 55.7558) < 0.001
    detail = client.get(f"/api/conversations/{cid}").json()
    assert detail["location"]["name"] == "Кафе «Пушкин»" and abs(detail["location"]["lon"] - 37.6173) < 1e-4
    ev = client.get(f"/api/events?day={day}").json()["events"][0]
    assert abs(ev["lat"] - 55.7558) < 0.001


def test_tiles_are_proxied_and_cached(conn, monkeypatch, tmp_path):  # noqa: F811
    from neuro import geo
    monkeypatch.setattr(geo, "TILE_DIR", tmp_path)
    calls = []

    class R:
        status_code = 200
        content = PNG

    def fake_get(url, **kw):
        calls.append(url)
        return R()

    monkeypatch.setattr(geo.requests, "get", fake_get)
    monkeypatch.setattr(geo, "CARTO_KEY", "k123")
    client = Asgi(__import__("gateway.app", fromlist=["app"]).app)
    r = client.get("/api/tiles/dark/12/2476/1280.png")
    assert r.status_code == 200 and r.content == PNG and "dark_all/12/2476/1280@2x.png?key=k123" in calls[0]
    assert client.get("/api/tiles/dark/12/2476/1280.png").content == PNG and len(calls) == 1  # из кеша
    assert client.get("/api/tiles/neon/1/0/0.png").status_code == 400
    assert client.get("/api/tiles/light/2/9/0.png").status_code == 400


def test_osm_tiles_are_restyled_without_key(conn, monkeypatch, tmp_path):  # noqa: F811
    """Без ключа CARTO — плитка OpenStreetMap, перекрашенная под тему (512 px, спокойные цвета)."""
    import io

    from PIL import Image

    from neuro import geo
    monkeypatch.setattr(geo, "TILE_DIR", tmp_path)
    monkeypatch.setattr(geo, "CARTO_KEY", "")
    src = io.BytesIO()
    Image.new("RGB", (256, 256), (242, 239, 233)).save(src, "PNG")
    calls = []

    class R:
        status_code = 200
        content = src.getvalue()

    monkeypatch.setattr(geo.requests, "get", lambda url, **kw: calls.append(url) or R())
    client = Asgi(__import__("gateway.app", fromlist=["app"]).app)
    for style in ("light", "dark"):
        r = client.get(f"/api/tiles/{style}/3/4/2.png")
        img = Image.open(io.BytesIO(r.content)).convert("RGB")
        lum = sum(img.getpixel((100, 100))) / 3
        assert r.status_code == 200 and img.size == (512, 512)
        assert (lum > 230) if style == "light" else (lum < 60)
    assert all(u == "https://tile.openstreetmap.org/3/4/2.png" for u in calls) and len(calls) == 2
