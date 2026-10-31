"""Карта: маршрут дня, места и где проходили разговоры; подложка карты через свой сервер.

Подложка — плитки OpenStreetMap, которые сервер перекрашивает в спокойные светлые или тёмные тона под тему
приложения. Если в .env задан CARTO_KEY (бесплатный ключ: carto.com/basemaps/apikey) — берутся готовые
плитки CARTO Positron / Dark Matter, они чётче. Телефон получает плитки только у своего сервера, а сервер
хранит их у себя: поставщик карты не видит телефон, а однажды открытые места открываются мгновенно.
"""
from __future__ import annotations

import io
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
CARTO_KEY = config.env("CARTO_KEY", "")
CACHE_VERSION = "v2"  # v1 — плитки CARTO без ключа («API KEY REQUIRED»), их не показываем
ATTRIBUTION = "© OpenStreetMap © CARTO" if CARTO_KEY else "© OpenStreetMap"
UA = "Mnemo/1.0 (personal server; +https://github.com/jeke8989/omi-neurocode)"
MAX_ZOOM = 19
# Перекраска OSM под тему приложения (как CARTO Positron / Dark Matter): подписи, фон (от зданий до дорог),
# вода и парки — своими спокойными цветами
PALETTE = {
    "light": {"ink": "#5f6673", "low": "#e2e4e9", "high": "#ffffff", "water": "#d5dce5", "park": "#e6ece4", "gamma": 2.2},
    "dark": {"ink": "#aeb5c1", "low": "#0f1218", "high": "#363d4a", "water": "#0b0e13", "park": "#18201b", "gamma": 4.0},
}
NEAR = timedelta(minutes=5)


_trimmed = False  # прежние версии кеша убираются при первой новой плитке


class TileError(RuntimeError):
    pass


def tile_path(style: str, z: int, x: int, y: int) -> Path:
    """Имя файла плитки — хеш (с ключом), а не координаты: по кешу не видно, где вы бывали."""
    import hashlib  # noqa: PLC0415

    salt = (crypto.seal_det("tiles") or "") if crypto.enabled() else ""
    source = "carto" if CARTO_KEY else "osm"
    h = hashlib.sha256(f"{salt}|{source}|{style}/{z}/{x}/{y}".encode()).hexdigest()
    return TILE_DIR / CACHE_VERSION / h[:2] / f"{h}.png"


def restyle(png: bytes, style: str) -> bytes:
    """Плитка OSM (256 px, яркая) → спокойная плитка 512 px в цветах темы приложения."""
    import numpy as np  # noqa: PLC0415
    from PIL import Image, ImageFilter  # noqa: PLC0415

    pal = PALETTE.get(style) or PALETTE["light"]

    def rgb(h: str):
        return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], dtype=np.float32)

    a = np.asarray(Image.open(io.BytesIO(png)).convert("RGB"), dtype=np.float32)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    lum = 0.299 * r + 0.587 * g + 0.114 * b
    t = np.clip((lum - 150) / 105, 0, 1)[..., None] ** pal["gamma"]  # здания → земля → дороги
    out = rgb(pal["low"]) + (rgb(pal["high"]) - rgb(pal["low"])) * t
    water = ((b - r > 25) & (b > 140) & (lum > 165))[..., None]
    green = ((g - r > 25) & (g - b > 15))[..., None]
    road = ((r > 200) & (r - b > 35) & (lum > 165))[..., None]  # крупные улицы OSM — жёлтые, оранжевые, розовые
    out = np.where(road, rgb(pal["high"]), out)
    out = np.where(water, rgb(pal["water"]), out)
    out = np.where(green, out * 0.3 + rgb(pal["park"]) * 0.7, out)
    ink = np.clip((180 - lum) / 100, 0, 1)[..., None] ** 0.7  # подписи и границы
    out = out + (rgb(pal["ink"]) - out) * ink
    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGB")
    img = img.resize((512, 512), Image.LANCZOS).filter(ImageFilter.UnsharpMask(radius=1.2, percent=60, threshold=2))
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def _fetch(url: str) -> bytes:
    try:
        r = requests.get(url, timeout=10, headers={"User-Agent": UA})
    except requests.RequestException as exc:
        raise TileError(str(exc)) from exc
    if r.status_code != 200 or not r.content.startswith(b"\x89PNG"):
        raise TileError(f"tile {r.status_code}")
    return r.content


def tile(style: str, z: int, x: int, y: int) -> bytes:
    """Плитка 512×512 из кеша, от CARTO (если есть ключ) или перекрашенная плитка OpenStreetMap."""
    if style not in STYLES or not 0 <= z <= MAX_ZOOM or not (0 <= x < 2 ** z and 0 <= y < 2 ** z):
        raise ValueError("bad tile")
    path = tile_path(style, z, x, y)
    if path.exists():
        return path.read_bytes()
    if CARTO_KEY:
        data = _fetch(f"https://basemaps.cartocdn.com/{STYLES[style]}/{z}/{x}/{y}@2x.png?key={CARTO_KEY}")
    else:
        data = restyle(_fetch(f"https://tile.openstreetmap.org/{z}/{x}/{y}.png"), style)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".part")
    tmp.write_bytes(data)
    tmp.replace(path)
    global _trimmed
    if not _trimmed or random.random() < 0.01:
        _trimmed = True
        trim_cache()
    return data


def trim_cache():
    """Кеш плиток — не больше TILE_CACHE_MB: самые давние удаляются; прежние версии кеша — сразу."""
    for old in TILE_DIR.glob("*"):
        if old.name != CACHE_VERSION and old.is_dir():
            for f in old.rglob("*.png"):
                f.unlink(missing_ok=True)
    files = [(p.stat().st_mtime, p.stat().st_size, p) for p in TILE_DIR.glob(f"{CACHE_VERSION}/*/*.png")]
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
