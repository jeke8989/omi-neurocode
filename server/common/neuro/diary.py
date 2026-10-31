"""Дневник жизни: места по геолокации, фото дня, ежедневная лента с хайлайтами.

Фото не попадают в ленту «все подряд»:
  1) каждое фото дёшево анализирует модель «Подписи к фото»: что на нём, это момент или скриншот/документ,
     насколько интересно (0–10) — мусор отсеивается сразу;
  2) фото привязывается к событию дня по времени и месту (разговор, место, задача);
  3) модель дневника сама выбирает фото только к конкретным хайлайтам, не больше N в день;
  4) стилизуются (и показываются) только выбранные.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import logging
import math
from datetime import date, datetime, time as dtime, timedelta, timezone
from pathlib import Path

import requests

from . import config, crypto, i18n, llm
from .assistant import TZ, WEEKDAYS

log = logging.getLogger("neuro.diary")
PHOTO_DIR = Path(config.env("PHOTO_DIR", "/data/photos"))

STYLES = {
    "watercolor": ("Акварель", "Redraw this photo as a soft, airy watercolor illustration with gentle washes of color."),
    "film": ("Киноплёнка", "Make this photo look like a warm 35mm film shot: natural grain, soft highlights, nostalgic tones."),
    "illustration": ("Иллюстрация", "Redraw this photo as a clean modern editorial illustration with flat shapes and soft gradients."),
    "oil": ("Масло", "Redraw this photo as an impressionist oil painting with visible brush strokes."),
    "comic": ("Комикс", "Redraw this photo as a vivid comic book panel with bold ink lines and halftone shading."),
    "sketch": ("Скетч", "Redraw this photo as a delicate pencil sketch with light hatching."),
}
STYLE_SUFFIX = (" Keep the composition, people, places and objects recognizable. Do not add any text, letters,"
                " watermarks or logos.")

# стилизация фото выключена: ~$0.07 за картинку при почти нулевой пользе (дневник показывает оригиналы)
DEFAULTS = {"feature.diary": "1", "feature.photo_ai": "1", "feature.photo_style": "0", "diary.style": "watercolor",
            "diary.photos_per_day": "3"}


# ---------------------------------------------------------------- настройки
def get_setting(conn, key: str) -> str:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (key,)).fetchone()
    return row["value"] if row else DEFAULTS.get(key, "")


def enabled(conn, key: str) -> bool:
    return get_setting(conn, key) == "1"


def day_bounds(day: date) -> tuple[datetime, datetime]:
    start = datetime.combine(day, dtime.min, tzinfo=TZ)
    return start, start + timedelta(days=1)


def local_day(dt: datetime) -> date:
    return dt.astimezone(TZ).date()


# ---------------------------------------------------------------- места
def _dist_m(a, b) -> float:
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def visits(points: list[dict], radius_m: float = 200, min_minutes: float = 10) -> list[dict]:
    """Склеивает точки в «визиты»: где человек провёл не меньше min_minutes в радиусе radius_m."""
    out, cur = [], None
    for p in sorted(points, key=lambda x: x["ts"]):
        if cur and _dist_m((cur["lat"], cur["lon"]), (p["lat"], p["lon"])) <= radius_m:
            cur["end"] = p["ts"]
            cur["n"] += 1
            # центр — среднее
            cur["lat"] += (p["lat"] - cur["lat"]) / cur["n"]
            cur["lon"] += (p["lon"] - cur["lon"]) / cur["n"]
            continue
        if cur:
            out.append(cur)
        cur = {"start": p["ts"], "end": p["ts"], "lat": p["lat"], "lon": p["lon"], "n": 1}
    if cur:
        out.append(cur)
    return [v for v in out if (v["end"] - v["start"]).total_seconds() >= min_minutes * 60]


def place_name(conn, lat: float, lon: float, network: bool = True) -> str | None:
    """Название места (кеш в places; нет — спрашиваем OpenStreetMap, если network)."""
    lang = i18n.lang(conn)
    key = f"{lat:.3f},{lon:.3f}" if lang == "ru" else f"{lang}:{lat:.3f},{lon:.3f}"
    row = conn.execute("SELECT name FROM places WHERE key = %s", (crypto.seal_det(key),)).fetchone()
    if row:
        return row["name"]
    if not network:
        return None
    name = None
    try:
        r = requests.get(
            "https://nominatim.openstreetmap.org/reverse",
            params={"lat": lat, "lon": lon, "format": "jsonv2", "accept-language": lang, "zoom": 17},
            headers={"User-Agent": "Mnemo-diary/1.0 (personal diary)"}, timeout=10,
        )
        if r.ok:
            j = r.json()
            addr = j.get("address") or {}
            name = j.get("name") or ", ".join(x for x in (addr.get("road"), addr.get("suburb") or
                                                            addr.get("city_district"), addr.get("city")) if x)
    except requests.RequestException:
        return None
    conn.execute("INSERT INTO places (key, name) VALUES (%s, %s) ON CONFLICT (key) DO NOTHING",
                 (crypto.seal_det(key), crypto.seal(name)))
    return name


def coords(lat, lon) -> tuple:
    """Координаты для записи: при шифровании — только зашифрованная строка pos (lat, lon пустые)."""
    if lat is None or lon is None:
        return None, None, None
    if crypto.enabled():
        return None, None, crypto.seal(f"{float(lat):.6f},{float(lon):.6f}")
    return lat, lon, None


def photo_coords(p: dict) -> dict:
    """Строка фото из базы -> lat/lon (расшифровав pos)."""
    if p.get("lat") is None and p.get("pos"):
        try:
            p["lat"], p["lon"] = (float(x) for x in str(p["pos"]).split(","))
        except ValueError:
            pass
    return p


# ---------------------------------------------------------------- фото
def save_photo(conn, data: bytes, taken_at: datetime | None, lat: float | None, lon: float | None) -> dict:
    sha = hashlib.sha256(data).hexdigest()
    existing = conn.execute("SELECT id FROM photos WHERE sha256 = %s", (sha,)).fetchone()
    if existing:
        return {"id": existing["id"], "duplicate": True}
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    path = PHOTO_DIR / f"{sha[:32]}.jpg"
    crypto.write_file(path, data)
    plat, plon, ppos = coords(lat, lon)
    row = conn.execute(
        """INSERT INTO photos (sha256, taken_at, lat, lon, pos, path) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id""",
        (sha, taken_at or datetime.now(timezone.utc), plat, plon, ppos, str(path)),
    ).fetchone()
    return {"id": row["id"], "duplicate": False}


def _prepare_image(path: str) -> tuple[bytes, dict]:
    """Уменьшает фото до 1280 px, поворачивает по EXIF, достаёт дату и GPS из EXIF, если есть."""
    meta: dict = {}
    try:
        from PIL import Image, ImageOps

        img = Image.open(io.BytesIO(crypto.read_file(path)))
        exif = img.getexif()
        dt = exif.get(36867) or exif.get(306)  # DateTimeOriginal / DateTime
        if dt:
            try:
                meta["taken_at"] = datetime.strptime(str(dt), "%Y:%m:%d %H:%M:%S").replace(tzinfo=TZ)
            except ValueError:
                pass
        gps = exif.get_ifd(0x8825) if hasattr(exif, "get_ifd") else {}
        if gps and 2 in gps and 4 in gps:
            def deg(v, ref):
                d = float(v[0]) + float(v[1]) / 60 + float(v[2]) / 3600
                return -d if ref in ("S", "W") else d
            meta["lat"], meta["lon"] = deg(gps[2], gps.get(1, "N")), deg(gps[4], gps.get(3, "E"))
        img = ImageOps.exif_transpose(img).convert("RGB")
        img.thumbnail((1280, 1280))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=85)
        return buf.getvalue(), meta
    except ImportError:
        return crypto.read_file(path), meta


def _data_url(jpeg: bytes) -> str:
    return "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()


ANALYZE_PROMPT = """Это фото с телефона владельца. Реши, годится ли оно для личного дневника дня.
Верни строго JSON: {"caption": "что происходит на фото, одна живая фраза",
"kind": "moment | screenshot | document | receipt | other",
"score": число 0–10 — насколько это интересный момент дня (люди, места, еда, события выше; случайные кадры ниже)}"""


def accept_photo_without_ai(conn, photo: dict):
    """Режим без ИИ: фото не отправляется модели (0 токенов). Уменьшаем, берём время и место из EXIF,
    дальше оно привязывается к событиям дня только по времени и месту."""
    jpeg, meta = _prepare_image(photo["path"])
    crypto.write_file(photo["path"], jpeg)
    plat, plon, ppos = coords(meta.get("lat"), meta.get("lon"))
    conn.execute(
        """UPDATE photos SET caption = NULL, status = 'analyzed', error = NULL,
               taken_at = coalesce(%s, taken_at), lat = coalesce(lat, %s), lon = coalesce(lon, %s),
               pos = coalesce(pos, %s)
           WHERE id = %s""",
        (meta.get("taken_at"), plat, plon, ppos, photo["id"]),
    )


def analyze_photo(conn, photo: dict):
    jpeg, meta = _prepare_image(photo["path"])
    crypto.write_file(photo["path"], jpeg)  # храним уже уменьшенную копию
    updates = {}
    if meta.get("taken_at"):
        updates["taken_at"] = meta["taken_at"]
    if photo.get("lat") is None and not photo.get("pos") and meta.get("lat") is not None:
        updates["lat"], updates["lon"], updates["pos"] = coords(meta["lat"], meta["lon"])
    msg = llm.chat(conn, "vision", [{"role": "user", "content": [
        {"type": "text", "text": ANALYZE_PROMPT + "\n" + i18n.write_in(i18n.lang(conn))},
        {"type": "image_url", "image_url": {"url": _data_url(jpeg)}},
    ]}], timeout=60)
    result = llm.parse_json(msg.get("content") or "{}")
    kind = str(result.get("kind") or "other").split("|")[0].strip()
    score = float(result.get("score") or 0)
    status = "analyzed" if kind == "moment" and score >= 4 else "skipped"
    conn.execute(
        """UPDATE photos SET caption = %s, status = %s, error = NULL,
               taken_at = coalesce(%s, taken_at), lat = coalesce(%s, lat), lon = coalesce(%s, lon),
               pos = coalesce(%s, pos)
           WHERE id = %s""",
        (crypto.seal(f"{str(result.get('caption') or '').strip()[:300]} (оценка {score:g})"), status,
         updates.get("taken_at"), updates.get("lat"), updates.get("lon"), updates.get("pos"), photo["id"]),
    )


def style_photo(conn, photo: dict, style: str):
    title, prompt = STYLES.get(style, STYLES["watercolor"])
    jpeg = crypto.read_file(photo["path"])
    msg = llm.chat(conn, "image_style", [{"role": "user", "content": [
        {"type": "text", "text": prompt + STYLE_SUFFIX},
        {"type": "image_url", "image_url": {"url": _data_url(jpeg)}},
    ]}], extra={"modalities": ["image", "text"]}, timeout=120)
    images = msg.get("images") or []
    url = (images[0].get("image_url") or {}).get("url", "") if images else ""
    if not url.startswith("data:image"):
        raise RuntimeError("модель не вернула изображение")
    raw = base64.b64decode(url.split(",", 1)[1])
    out = Path(photo["path"]).with_name(Path(photo["path"]).stem + f"-{style}.png")
    crypto.write_file(out, raw)
    conn.execute("UPDATE photos SET styled_path = %s, style = %s WHERE id = %s", (str(out), style, photo["id"]))


# ---------------------------------------------------------------- материал дня
def material(conn, day: date) -> dict:
    start, end = day_bounds(day)
    convs = conn.execute(
        """SELECT id, started_at, last_activity, title, summary, event_id FROM conversations
           WHERE started_at >= %s AND started_at < %s AND status = 'done' AND trashed_at IS NULL
           ORDER BY started_at""",
        (start, end)).fetchall()
    events = conn.execute(
        """SELECT e.id, e.started_at, e.ended_at, e.title, e.emoji, e.kind, e.summary,
                  coalesce((SELECT array_agg(coalesce(p.name, CASE WHEN p.is_owner THEN 'я' END))
                            FROM people p WHERE p.id = ANY(e.people) AND (p.name IS NOT NULL OR p.is_owner)), '{}') AS names
           FROM day_events e WHERE e.day = %s ORDER BY e.started_at""", (day,)).fetchall()
    tasks = conn.execute(
        "SELECT text, due, created_at FROM tasks WHERE created_at >= %s AND created_at < %s ORDER BY created_at",
        (start, end)).fetchall()
    memories = conn.execute(
        "SELECT text, created_at FROM memories WHERE created_at >= %s AND created_at < %s AND archived_at IS NULL "
        "ORDER BY created_at",
        (start, end)).fetchall()
    questions = conn.execute(
        """SELECT question, answer, asked_at FROM questions WHERE asked_at >= %s AND asked_at < %s
           AND question <> '' ORDER BY asked_at""", (start, end)).fetchall()
    from .geo import points as geo_points  # noqa: PLC0415 — geo импортирует diary

    points = geo_points(conn, start, end)
    photos = [photo_coords(p) for p in conn.execute(
        """SELECT id, taken_at, lat, lon, pos, caption FROM photos WHERE taken_at >= %s AND taken_at < %s
           AND status IN ('analyzed', 'done') ORDER BY taken_at""", (start, end)).fetchall()]
    vs = visits(points)
    for v in vs:
        v["name"] = place_name(conn, v["lat"], v["lon"])
    # привязываем фото к событию: разговор в пределах ±30 минут или визит, в который сделан кадр
    for p in photos:
        p["event"] = None
        for c in convs:
            if c["started_at"] - timedelta(minutes=30) <= p["taken_at"] <= c["last_activity"] + timedelta(minutes=30):
                p["event"] = f"разговор «{c['title']}»"
                break
        if p["event"] is None:
            for v in vs:
                if v["start"] - timedelta(minutes=15) <= p["taken_at"] <= v["end"] + timedelta(minutes=15):
                    p["event"] = f"место {v['name'] or 'без названия'}"
                    break
    return {"day": day, "conversations": convs, "events": events, "tasks": tasks, "memories": memories,
            "questions": questions, "visits": vs, "photos": photos, "points": len(points)}


def _t(dt) -> str:
    return dt.astimezone(TZ).strftime("%H:%M")


def material_text(m: dict, max_photos: int) -> str:
    d: date = m["day"]
    lines = [f"День: {WEEKDAYS[d.weekday()]}, {d.strftime('%d.%m.%Y')}"]
    if m["visits"]:
        lines.append("Места:")
        lines += [f"- {_t(v['start'])}–{_t(v['end'])} {v['name'] or 'место без названия'}" for v in m["visits"]]
    if m.get("events"):
        # события дня — уже собранные из фрагментов, по ним сводка получается связной
        lines.append("События:")
        lines += [f"- {_t(e['started_at'])}–{_t(e['ended_at'])} {e['emoji'] or ''} «{e['title']}» ({e['kind']})"
                  + (f", с кем: {', '.join(n for n in e['names'] if n)}" if e.get("names") else "")
                  + f": {e['summary'] or ''}" for e in m["events"]]
    loose = [c for c in m["conversations"] if not c.get("event_id")] if m.get("events") else m["conversations"]
    if loose:
        lines.append("Разговоры:")
        lines += [f"- {_t(c['started_at'])}–{_t(c['last_activity'])} «{c['title']}»: {c['summary'] or ''}"
                  for c in loose]
    if m["questions"]:
        lines.append("Вопросы ассистенту:")
        lines += [f"- {_t(q['asked_at'])} {q['question']} → {q['answer']}" for q in m["questions"]]
    if m["tasks"]:
        lines.append("Новые задачи:")
        lines += [f"- {_t(t['created_at'])} {t['text']}" for t in m["tasks"]]
    if m["memories"]:
        lines.append("Запомнил:")
        lines += [f"- {_t(x['created_at'])} {x['text']}" for x in m["memories"]]
    linked = [p for p in m["photos"] if p["event"]]
    if linked:
        lines.append(f"Фото (можно прикрепить к хайлайтам не больше {max_photos}, только к своему событию):")
        lines += [f"- photo_id={p['id']} {_t(p['taken_at'])} [{p['event']}] {p['caption'] or '(фото без подписи)'}" for p in linked]
    return "\n".join(lines)


DIARY_SYSTEM = """Ты ведёшь личный дневник владельца кулона Mnemo. По данным его дня собери красивую ленту.
Верни строго JSON:
{"title": "заголовок дня, до 6 слов",
 "emoji": "одно эмодзи — символ дня",
 "mood": "настроение дня: эмодзи и одно слово",
 "summary": "тёплый короткий рассказ о дне на «ты», 3–6 предложений",
 "quote": "яркая фраза дня дословно из данных или пустая строка",
 "highlights": [{"time": "ЧЧ:ММ", "emoji": "…", "title": "коротко", "text": "1–2 предложения",
                 "kind": "conversation | place | photo | task | memory | question", "photo_id": число или null}]}
Правила: 3–8 хайлайтов по времени; только реальные события из данных, ничего не выдумывай;
photo_id ставь, только если фото относится именно к этому событию; пиши живо и без пафоса."""


def generate_day(conn, day: date, force: bool = False) -> bool:
    max_photos = int(get_setting(conn, "diary.photos_per_day") or 3)
    m = material(conn, day)
    text = material_text(m, max_photos)
    lang = i18n.lang(conn)
    has_content = any(m[k] for k in ("conversations", "questions", "tasks", "memories", "visits", "photos"))
    content_hash = hashlib.sha256((lang + "\n" + text).encode()).hexdigest()[:32]
    row = conn.execute("SELECT content_hash, status FROM diary_days WHERE day = %s", (day,)).fetchone()
    if not has_content:
        return False
    if row and row["content_hash"] == content_hash and row["status"] == "done" and not force:
        conn.execute("UPDATE diary_days SET generated_at = now() WHERE day = %s", (day,))
        return False
    result = llm.chat_json(conn, "diary", DIARY_SYSTEM + "\n" + i18n.write_in(lang), text)
    photo_ids = {p["id"] for p in m["photos"] if p["event"]}
    highlights, used = [], []
    for h in result.get("highlights") or []:
        if not isinstance(h, dict) or not str(h.get("title") or "").strip():
            continue
        pid = h.get("photo_id")
        pid = int(pid) if isinstance(pid, (int, float)) and int(pid) in photo_ids and len(used) < max_photos else None
        if pid:
            used.append(pid)
        highlights.append({"time": str(h.get("time") or "")[:5], "emoji": str(h.get("emoji") or "✨")[:4],
                           "title": str(h["title"])[:120], "text": str(h.get("text") or "")[:600],
                           "kind": str(h.get("kind") or ""), "photo_id": pid})
    places = [{"name": v["name"], "from": _t(v["start"]), "to": _t(v["end"]), "lat": round(v["lat"], 5),
               "lon": round(v["lon"], 5)} for v in m["visits"]]
    minutes = int(sum((c["last_activity"] - c["started_at"]).total_seconds() for c in m["conversations"]) // 60)
    stats = {"conversations": len(m["conversations"]), "minutes": minutes, "questions": len(m["questions"]),
             "tasks": len(m["tasks"]), "places": len(m["visits"]), "photos": len(used)}
    conn.execute(
        """INSERT INTO diary_days (day, title, summary, mood, quote, highlights, places, stats, cover_photo_id,
                                   status, content_hash, generated_at, error)
           VALUES (%s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s::jsonb, %s, 'done', %s, now(), NULL)
           ON CONFLICT (day) DO UPDATE SET title = EXCLUDED.title, summary = EXCLUDED.summary, mood = EXCLUDED.mood,
               quote = EXCLUDED.quote, highlights = EXCLUDED.highlights, places = EXCLUDED.places,
               stats = EXCLUDED.stats, cover_photo_id = EXCLUDED.cover_photo_id, status = 'done',
               content_hash = EXCLUDED.content_hash, generated_at = now(), error = NULL""",
        (day, crypto.seal(str(result.get("title") or "")[:120]), crypto.seal(str(result.get("summary") or "")[:3000]),
         crypto.seal((str(result.get("emoji") or "").strip()[:8] + " " + str(result.get("mood") or "")).strip()[:40]),
         crypto.seal(str(result.get("quote") or "")[:300]),
         crypto.seal_json(highlights), crypto.seal_json(places),
         json.dumps(stats), used[0] if used else None, content_hash),
    )
    try:  # интеграции, подписанные на «итог дня»
        from . import hooks  # noqa: PLC0415

        hooks.emit(conn, "day", {"day": day.isoformat(), "title": str(result.get("title") or "")[:120],
                                 "summary": str(result.get("summary") or "")[:3000],
                                 "mood": str(result.get("mood") or "")[:40]})
    except Exception:  # noqa: BLE001
        log.exception("day %s: hook event failed", day)
    if used:  # стилизуем только выбранные к событиям фото
        conn.execute("UPDATE photos SET status = 'done' WHERE id = ANY(%s)", (used,))
    log.info("diary %s: %d highlights, %d photos", day, len(highlights), len(used))
    return True


# ---------------------------------------------------------------- работа по расписанию (агент)
def tick(conn):
    if not enabled(conn, "feature.diary"):
        return
    # 1) новые фото: с ИИ — подпись и отбор (дёшево), без ИИ — только время и место (бесплатно)
    use_ai = enabled(conn, "feature.photo_ai")
    for p in conn.execute("SELECT * FROM photos WHERE status = 'pending' ORDER BY id LIMIT %s",
                          (3 if use_ai else 20,)).fetchall():
        try:
            if use_ai:
                analyze_photo(conn, p)
            else:
                accept_photo_without_ai(conn, p)
        except Exception as exc:  # noqa: BLE001
            log.warning("photo %s analyze failed: %s", p["id"], exc)
            conn.execute("UPDATE photos SET status = 'failed', error = %s WHERE id = %s", (str(exc)[:300], p["id"]))
    # 2) стилизация только выбранных дневником — по одному за проход
    if enabled(conn, "feature.photo_style"):
        style = get_setting(conn, "diary.style")
        p = conn.execute("""SELECT * FROM photos WHERE status = 'done' AND styled_path IS NULL AND error IS NULL
                            ORDER BY taken_at DESC LIMIT 1""").fetchone()
        if p:
            try:
                style_photo(conn, p, style)
            except Exception as exc:  # noqa: BLE001
                log.warning("photo %s style failed: %s", p["id"], exc)
                conn.execute("UPDATE photos SET error = %s WHERE id = %s", (str(exc)[:300], p["id"]))
    # 3) лента дня: запрошенные вручную, сегодня — не чаще раза в 2 часа, вчера — финальная версия
    now = datetime.now(timezone.utc)
    today = local_day(now)
    for row in conn.execute("SELECT day FROM diary_days WHERE status = 'pending' LIMIT 2").fetchall():
        _safe_generate(conn, row["day"], force=True)
    t = conn.execute("SELECT generated_at FROM diary_days WHERE day = %s", (today,)).fetchone()
    if not t or (t["generated_at"] and now - t["generated_at"] > timedelta(hours=2)):
        _safe_generate(conn, today)
    y = today - timedelta(days=1)
    yrow = conn.execute("SELECT generated_at FROM diary_days WHERE day = %s", (y,)).fetchone()
    # финальная сводка вчера — после ночного разбора событий (он в 03:00 сам ставит её в очередь)
    if (not yrow or (yrow["generated_at"] and yrow["generated_at"] < day_bounds(today)[0])) \
            and now.astimezone(TZ).hour >= 5:
        _safe_generate(conn, y)


def _safe_generate(conn, day: date, force: bool = False):
    try:
        if not generate_day(conn, day, force=force):
            # дня ещё нет (ни разговоров, ни мест, ни фото) — пустую карточку не заводим
            conn.execute("DELETE FROM diary_days WHERE day = %s AND title IS NULL", (day,))
            conn.execute("UPDATE diary_days SET status = 'done' WHERE day = %s AND status = 'pending'", (day,))
    except Exception as exc:  # noqa: BLE001
        log.exception("diary %s failed", day)
        conn.execute(
            """INSERT INTO diary_days (day, status, error, generated_at) VALUES (%s, 'failed', %s, now())
               ON CONFLICT (day) DO UPDATE SET status = 'failed', error = EXCLUDED.error,
                   generated_at = now()""", (day, str(exc)[:300]))
