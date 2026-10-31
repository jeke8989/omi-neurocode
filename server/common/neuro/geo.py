"""Карта: маршрут дня, места и где проходили разговоры; подложка карты через свой сервер.

Подложка — растровые плитки CARTO (светлая Positron и тёмная Dark Matter, данные © OpenStreetMap).
Телефон берёт их у своего сервера, а сервер — у CARTO и хранит у себя: так поставщик карты не видит
телефон, а однажды открытые места потом открываются мгновенно и без лишних запросов.
"""
from __future__ import annotations

import logging
import random
import statistics
from datetime import date, datetime, timedelta
from pathlib import Path

import requests

from . import config, crypto, diary

log = logging.getLogger("neuro.geo")

TILE_DIR = Path(config.env("TILE_DIR", "/data/tiles"))
TILE_CACHE_MB = int(config.env("TILE_CACHE_MB", "300"))
STYLES = {"light": "light_all", "dark": "dark_all"}
MAX_ZOOM = 19
NEAR = timedelta(minutes=5)


class TileError(RuntimeError):
    pass


def tile_path(style: str, z: int, x: int, y: int) -> Path:
    """Имя файла плитки — хеш (с ключом), а не координаты: по кешу не видно, где вы бывали."""
    import hashlib  # noqa: PLC0415

    salt = (crypto.seal_det("tiles") or "") if crypto.enabled() else ""
    h = hashlib.sha256(f"{salt}|{style}/{z}/{x}/{y}".encode()).hexdigest()
    return TILE_DIR / h[:2] / f"{h}.png"


def tile(style: str, z: int, x: int, y: int) -> bytes:
    """Плитка 512×512 (@2x) из кеша или от CARTO."""
    if style not in STYLES or not 0 <= z <= MAX_ZOOM or not (0 <= x < 2 ** z and 0 <= y < 2 ** z):
        raise ValueError("bad tile")
    path = tile_path(style, z, x, y)
    if path.exists():
        return path.read_bytes()
    url = f"https://{'abcd'[(x + y) % 4]}.basemaps.cartocdn.com/{STYLES[style]}/{z}/{x}/{y}@2x.png"
    try:
        r = requests.get(url, timeout=10, headers={"User-Agent": "Mnemo/1.0 (personal server)"})
    except requests.RequestException as exc:
        raise TileError(str(exc)) from exc
    if r.status_code != 200 or not r.content.startswith(b"\x89PNG"):
        raise TileError(f"tile {r.status_code}")
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".part")
    tmp.write_bytes(r.content)
    tmp.replace(path)
    if random.random() < 0.01:
        trim_cache()
    return r.content


def trim_cache():
    """Кеш плиток — не больше TILE_CACHE_MB: самые давние удаляются."""
    for old in TILE_DIR.glob("*/*/*/*.png"):  # прежняя раскладка (по координатам) — убрать
        old.unlink(missing_ok=True)
    files = [(p.stat().st_mtime, p.stat().st_size, p) for p in TILE_DIR.glob("*/*.png")]
    total = sum(f[1] for f in files)
    limit = TILE_CACHE_MB << 20
    for _, size, p in sorted(files):
        if total <= limit:
            break
        p.unlink(missing_ok=True)
        total -= size


# ---------------------------------------------------------------- где что было
def points(conn, start: datetime, end: datetime) -> list[dict]:
    """Точки геолокации за время. Координаты в базе зашифрованы (pos = «lat,lon») — расшифрованы при чтении."""
    out = []
    for r in conn.execute("SELECT ts, lat, lon, pos FROM locations WHERE ts >= %s AND ts < %s ORDER BY ts",
                          (start, end)).fetchall():
        lat, lon = r["lat"], r["lon"]
        if lat is None and r.get("pos"):
            try:
                lat, lon = (float(x) for x in str(r["pos"]).split(","))
            except ValueError:
                continue
        if lat is not None and lon is not None:
            out.append({"ts": r["ts"], "lat": lat, "lon": lon})
    return out


def pos(lat: float, lon: float) -> str | None:
    """Координаты для записи в базу: зашифрованная строка «lat,lon»."""
    return crypto.seal(f"{lat:.6f},{lon:.6f}")


def where(pts: list[dict], start: datetime, end: datetime) -> tuple[float, float] | None:
    """Где были с start до end: середина точек за это время (±5 минут); нет — ближайшая точка в пределах получаса."""
    near = [p for p in pts if start - NEAR <= p["ts"] <= end + NEAR]
    if near:
        return statistics.median(p["lat"] for p in near), statistics.median(p["lon"] for p in near)
    mid = start + (end - start) / 2
    best = min(pts, key=lambda p: abs(p["ts"] - mid), default=None)
    if best and abs(best["ts"] - mid) <= timedelta(minutes=30):
        return best["lat"], best["lon"]
    return None


def track(pts: list[dict], limit: int = 400) -> list[list[float]]:
    """Маршрут для карты: без топтания на месте (точки ближе 15 м выкидываем) и не больше limit точек."""
    out: list[dict] = []
    for p in pts:
        if out and diary._dist_m((out[-1]["lat"], out[-1]["lon"]), (p["lat"], p["lon"])) < 15:
            continue
        out.append(p)
    step = max(1, len(out) // limit + (1 if len(out) % limit else 0))
    kept = out[::step]
    if out and kept[-1] is not out[-1]:
        kept.append(out[-1])
    return [[round(p["lat"], 6), round(p["lon"], 6)] for p in kept]


def locate(rows: list[dict], pts: list[dict], start_key: str = "started_at", end_key: str = "ended_at"):
    """Проставить lat/lon каждой строке (событию, фрагменту) по точкам дня."""
    for r in rows:
        at = where(pts, r[start_key], r.get(end_key) or r[start_key]) if pts else None
        r["lat"], r["lon"] = (round(at[0], 6), round(at[1], 6)) if at else (None, None)
    return rows


def day_map(conn, day: date) -> dict:
    """Карта дня: маршрут, места, где побывали (с названиями), события и разговоры на своих местах."""
    start, end = diary.day_bounds(day)
    pts = points(conn, start, end)
    places = []
    asked = 0
    for v in diary.visits(pts):
        name = diary.place_name(conn, v["lat"], v["lon"], network=False)
        if name is None and asked < 3:  # новые места — не больше трёх запросов к OpenStreetMap за раз
            asked += 1
            name = diary.place_name(conn, v["lat"], v["lon"])
        places.append({"name": name, "lat": round(v["lat"], 6),
                       "lon": round(v["lon"], 6), "from": v["start"], "to": v["end"]})
    events = conn.execute(
        """SELECT e.id, e.title, e.emoji, e.started_at, e.ended_at,
                  (SELECT count(*) FROM conversations c WHERE c.event_id = e.id AND c.trashed_at IS NULL) AS fragments,
                  (SELECT min(c.id) FROM conversations c WHERE c.event_id = e.id AND c.trashed_at IS NULL)
                      AS first_conversation
           FROM day_events e WHERE e.day = %s ORDER BY e.started_at""", (day,)).fetchall()
    loose = conn.execute(
        """SELECT c.id, c.title, c.emoji, c.started_at, c.last_activity AS ended_at FROM conversations c
           WHERE c.started_at >= %s AND c.started_at < %s AND c.event_id IS NULL AND c.trashed_at IS NULL
           ORDER BY c.started_at""", (start, end)).fetchall()
    return {"day": day.isoformat(), "track": track(pts), "places": places,
            "events": [e for e in locate(events, pts) if e["lat"] is not None and e["fragments"]],
            "conversations": [c for c in locate(loose, pts) if c["lat"] is not None]}


def conversation_map(conn, started_at: datetime, ended_at: datetime) -> dict | None:
    """Где был разговор: точка и маршрут (если разговор шёл на ходу)."""
    pts = points(conn, started_at - NEAR, ended_at + NEAR)
    if not pts:
        pts = points(conn, started_at - timedelta(minutes=30), ended_at + timedelta(minutes=30))
    at = where(pts, started_at, ended_at) if pts else None
    if not at:
        return None
    inside = [p for p in pts if started_at - NEAR <= p["ts"] <= ended_at + NEAR]
    return {"lat": round(at[0], 6), "lon": round(at[1], 6), "track": track(inside, 120) if len(inside) > 1 else [],
            "name": diary.place_name(conn, at[0], at[1], network=False)}
