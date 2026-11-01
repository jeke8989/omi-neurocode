"""NeuroSoft Gateway — точка входа для приложения.

* WebSocket /ws/audio — звук и события кнопки с телефона (с подтверждением каждого пакета).
* REST /api/... — разговоры, задачи и память для экранов приложения.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
from html import escape as html_escape
import json
import logging
import re
import secrets
import time
import urllib.request
from contextlib import asynccontextmanager
from datetime import date, datetime, timezone
from pathlib import Path

import anyio
import psycopg
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import FileResponse, JSONResponse, Response
from starlette.routing import Route, WebSocketRoute
from starlette.websockets import WebSocket, WebSocketDisconnect

from neuro import (agent_context, agent_core, assistant, cleanup, connectors, config, crypto, db, diary, extensions,
                   ext_builder, geo, hooks, i18n, llm, mnemo_mcp, pairing, people, profile, promises, retention, routines, tts, voices)
from neuro import calendar as calendarlib
from neuro import memory as memorylib
from neuro import tasks as tasklib
from neuro.models import CATALOG, TASK_KEYS, TASKS, localized_model, localized_task
from neuro.audio import (
    BYTES_PER_MS,
    BatchError,
    ChunkWriter,
    FinishedChunk,
    FrameDecoder,
    chunk_from_path,
    parse_batch,
    pcm_to_wav,
    recover_orphans,
)

log = logging.getLogger("neuro.gateway")

DEVICE_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
CONTEXT_RE = re.compile(r"^(event:\d{1,12}|conversation:\d{1,12}|day:\d{4}-\d{2}-\d{2})$")
writers: dict[str, ChunkWriter] = {}
writer_locks: dict[str, asyncio.Lock] = {}


# ---------------------------------------------------------------------------
# Утилиты
# ---------------------------------------------------------------------------
async def aconnect() -> psycopg.AsyncConnection:
    return await psycopg.AsyncConnection.connect(config.DATABASE_URL, autocommit=True, row_factory=crypto.dict_row)


def _json_default(o):
    if isinstance(o, (datetime, date)):
        return o.isoformat()
    return str(o)


class Json(JSONResponse):
    def render(self, content) -> bytes:
        return json.dumps(content, ensure_ascii=False, default=_json_default).encode("utf-8")


def _token(headers, query) -> str | None:
    auth = headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip()
    return query.get("token")


def _authorized(headers, query) -> bool:
    token = _token(headers, query)
    if not config.API_TOKEN or not token:
        return False
    return hmac.compare_digest(token.encode(), config.API_TOKEN.encode())


def _unauthorized() -> Json:
    return Json({"error": "unauthorized"}, status_code=401)


def _clamp(value: str | None, default: int, lo: int, hi: int) -> int:
    try:
        return max(lo, min(hi, int(value))) if value is not None else default
    except ValueError:
        return default


INSERT_CHUNK = """
INSERT INTO audio_chunks (device_id, started_at, duration_ms, path, offline)
VALUES (%s, to_timestamp(%s / 1000.0), %s, %s, %s)
ON CONFLICT (path) WHERE path IS NOT NULL DO NOTHING
"""


async def register_chunks(conn, chunks: list[FinishedChunk]):
    for c in chunks:
        await conn.execute(INSERT_CHUNK, (c.device_id, c.start_ms, c.duration_ms, c.path, c.offline))
        log.info("chunk ready device=%s start=%s dur=%sms%s", c.device_id, c.start_ms, c.duration_ms,
                 " offline" if c.offline else "")


async def reconcile_files(recover: bool):
    """Регистрирует в базе .pcm-файлы, у которых нет записи (например, после сбоя)."""

    def scan():
        if recover:
            recover_orphans(config.AUDIO_DIR)
        return sorted(Path(config.AUDIO_DIR).glob("*/*.pcm"))

    paths = await anyio.to_thread.run_sync(scan)
    if not paths:
        return
    async with await aconnect() as conn:
        cur = await conn.execute("SELECT path FROM audio_chunks WHERE path = ANY(%s)", ([str(p) for p in paths],))
        known = {r["path"] for r in await cur.fetchall()}
        missing = [chunk_from_path(p) for p in paths if str(p) not in known]
        if missing:
            log.warning("registering %d orphan chunk files", len(missing))
            await register_chunks(conn, missing)


async def idle_finalizer():
    """Закрывает куски, в которые давно не приходил звук, и отдаёт их на расшифровку."""
    while True:
        await asyncio.sleep(2)
        try:
            finished: list[FinishedChunk] = []
            for dev, w in list(writers.items()):
                if not w.open or time.monotonic() - w.last_write < config.CHUNK_IDLE_SEC:
                    continue
                async with writer_locks[dev]:
                    if w.open and time.monotonic() - w.last_write >= config.CHUNK_IDLE_SEC:
                        c = w.finalize()
                        if c:
                            finished.append(c)
            if finished:
                async with await aconnect() as conn:
                    await register_chunks(conn, finished)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("idle finalizer failed")


async def periodic_reconcile():
    while True:
        await asyncio.sleep(600)
        try:
            await reconcile_files(recover=False)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            log.exception("reconcile failed")


# ---------------------------------------------------------------------------
# WebSocket: звук и события
# ---------------------------------------------------------------------------
async def handle_batch(conn, device: str, decoder: FrameDecoder, data: bytes) -> dict:
    try:
        batch = parse_batch(data)
    except BatchError as exc:
        return {"type": "error", "error": str(exc)}

    bid = batch.batch_id
    cur = await conn.execute(
        "SELECT 1 FROM received_batches WHERE device_id = %s AND batch_id = %s", (device, bid)
    )
    if await cur.fetchone() is not None:
        return {"type": "ack", "id": bid, "duplicate": True}

    try:
        pcm = decoder.decode(batch.codec, batch.frames)
    except BatchError as exc:
        # Неподдерживаемый кодек — подтверждаем, чтобы телефон не слал пакет вечно
        log.error("device=%s batch=%s dropped: %s", device, bid, exc)
        return {"type": "ack", "id": bid, "error": str(exc)}

    # запись из памяти кулона (сделана без телефона) — своя «лента», чтобы не рвать живой поток
    key = f"{device}#offline" if batch.offline else device
    lock = writer_locks.setdefault(key, asyncio.Lock())
    async with lock:
        w = writers.get(key)
        if w is None:
            w = writers[key] = ChunkWriter(config.AUDIO_DIR, device, config.CHUNK_MAX_SEC, config.CHUNK_GAP_SEC,
                                           offline=batch.offline)
        finished = await anyio.to_thread.run_sync(w.write, batch.ts_ms, pcm)
    if batch.offline and finished:
        log.info("device=%s: %d offline chunk(s) from the pendant memory", device, len(finished))

    await register_chunks(conn, finished)
    await conn.execute(
        "INSERT INTO received_batches (device_id, batch_id) VALUES (%s, %s) ON CONFLICT DO NOTHING", (device, bid)
    )
    await conn.execute(
        """UPDATE devices SET last_seen = now(),
               last_audio = greatest(coalesce(last_audio, to_timestamp(0)), to_timestamp(%s / 1000.0))
           WHERE device_id = %s""",
        (batch.ts_ms, device),
    )
    return {"type": "ack", "id": bid, "frames": len(batch.frames)}


async def handle_text(conn, device: str, text: str) -> dict:
    try:
        obj = json.loads(text)
    except ValueError:
        return {"type": "error", "error": "bad json"}
    kind = obj.get("type")
    now_ms = int(time.time() * 1000)

    if kind == "ping":
        return {"type": "pong", "ts": now_ms}

    if kind == "hello":
        info = obj.get("info") if isinstance(obj.get("info"), dict) else {}
        await conn.execute(
            "UPDATE devices SET info = info || %s::jsonb, last_seen = now() WHERE device_id = %s",
            (json.dumps(info, ensure_ascii=False), device),
        )
        return {"type": "hello", "server_time": now_ms}

    if kind == "event":
        cid = str(obj.get("id") or "")
        ev = str(obj.get("event") or "")
        if not cid or not ev:
            return {"type": "error", "error": "event needs id and event"}
        value = obj.get("value")
        value = int(value) if isinstance(value, (int, float)) else None
        ts = obj.get("ts")
        ts = int(ts) if isinstance(ts, (int, float)) else now_ms
        if ev == "location":
            lat, lon = obj.get("lat"), obj.get("lon")
            if isinstance(lat, (int, float)) and isinstance(lon, (int, float)) and -90 <= lat <= 90 \
                    and -180 <= lon <= 180:
                plat, plon, ppos = diary.coords(lat, lon)  # с ключом — только зашифрованная pos
                await conn.execute(
                    """INSERT INTO locations (device_id, client_id, ts, lat, lon, pos, accuracy)
                       VALUES (%s, %s, to_timestamp(%s / 1000.0), %s, %s, %s, %s)
                       ON CONFLICT (device_id, client_id) DO NOTHING""",
                    (device, cid, ts, plat, plon, ppos, obj.get("acc")),
                )
            return {"type": "ack", "id": cid}
        await conn.execute(
            """INSERT INTO events (device_id, client_id, kind, value, ts)
               VALUES (%s, %s, %s, %s, to_timestamp(%s / 1000.0))
               ON CONFLICT (device_id, client_id) DO NOTHING""",
            (device, cid, ev, value, ts),
        )
        return {"type": "ack", "id": cid}

    return {"type": "error", "error": f"unknown type {kind!r}"}


async def ws_audio(websocket: WebSocket):
    device = websocket.query_params.get("device_id", "")
    if not _authorized(websocket.headers, websocket.query_params) or not DEVICE_RE.match(device):
        await websocket.close(code=4401)
        return
    await websocket.accept()
    log.info("device %s connected", device)
    decoder = FrameDecoder()
    conn = await aconnect()
    try:
        await conn.execute(
            """INSERT INTO devices (device_id, last_seen) VALUES (%s, now())
               ON CONFLICT (device_id) DO UPDATE SET last_seen = now()""",
            (device,),
        )
        while True:
            msg = await websocket.receive()
            if msg["type"] == "websocket.disconnect":
                break
            if msg.get("bytes") is not None:
                reply = await handle_batch(conn, device, decoder, msg["bytes"])
            elif msg.get("text") is not None:
                reply = await handle_text(conn, device, msg["text"])
            else:
                continue
            await websocket.send_text(json.dumps(reply, ensure_ascii=False))
    except WebSocketDisconnect:
        pass
    except Exception:  # noqa: BLE001
        log.exception("device %s: connection error", device)
    finally:
        if decoder.bad_frames:
            log.warning("device %s: %d undecodable frames", device, decoder.bad_frames)
        decoder.close()
        await conn.close()
        log.info("device %s disconnected", device)


# ---------------------------------------------------------------------------
# REST API для приложения
# ---------------------------------------------------------------------------
async def health(request: Request):
    return Json({"ok": True, "status": "ok", "service": "neurosoft-gateway"})


async def health_full(request: Request):
    """Разбивка по сервисам для мониторинга: api, db. 200 — ok/degraded, 503 — база недоступна."""
    services = {"api": True, "db": False}
    try:
        async with await aconnect() as conn:
            await conn.execute("SELECT 1")
        services["db"] = True
    except Exception:  # noqa: BLE001
        pass
    state = "ok" if all(services.values()) else "degraded" if services["db"] else "down"
    return Json({"status": state, "services": services}, status_code=503 if state == "down" else 200)


# одноразовый код подключения: не больше 10 попыток в минуту с одного адреса
_pair_hits: dict[str, list[float]] = {}


def _pair_limited(request: Request) -> bool:
    ip = request.client.host if request.client else "?"
    now = time.monotonic()
    hits = [t for t in _pair_hits.get(ip, []) if now - t < 60]
    _pair_hits[ip] = hits + [now]
    return len(hits) >= 10


async def _pair_sync(fn, *args):
    def work():
        with db.connect() as conn:
            return fn(conn, *args)
    return await anyio.to_thread.run_sync(work)


async def api_pair_auto(request: Request):
    """Первый телефон после установки сервера получает ключ без кода (дальше — только с разрешения)."""
    if _pair_limited(request):
        return Json({"error": "too many attempts, wait a minute"}, status_code=429)
    if not config.API_TOKEN:
        return Json({"error": "API_TOKEN is empty"}, status_code=500)
    if await _pair_sync(pairing.auto):
        log.info("first app connected automatically from %s", request.client.host if request.client else "?")
        return Json({"token": config.API_TOKEN, "name": "Mnemo"})
    return Json({"error": "paired"}, status_code=409)


async def api_pair_request(request: Request):
    """Новый телефон: создать запрос (POST) или узнать ответ (GET /api/pair/request/{id})."""
    if _pair_limited(request):
        return Json({"error": "too many attempts, wait a minute"}, status_code=429)
    rid = request.path_params.get("id")
    if rid is None:
        try:
            body = await request.json()
        except ValueError:
            body = {}
        new_id = await _pair_sync(pairing.create_request, str((body or {}).get("name") or ""),
                                  request.client.host if request.client else None)
        if new_id is None:
            return Json({"error": "too many requests"}, status_code=429)
        return Json({"id": new_id, "status": "pending"})
    status = await _pair_sync(pairing.request_status, rid)
    if status == "approved" and config.API_TOKEN:
        await _pair_sync(pairing.deliver, rid)
        return Json({"status": "approved", "token": config.API_TOKEN})
    return Json({"status": status})


async def api_pair_requests(request: Request):
    """Подключённый телефон: кто просит доступ (GET) и ответ (POST /api/pair/requests/{id} {"approve"})."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    rid = request.path_params.get("id")
    if rid is None:
        return Json({"items": await _pair_sync(pairing.pending_requests)})
    try:
        body = await request.json()
    except ValueError:
        return Json({"error": "bad json"}, status_code=400)
    ok = await _pair_sync(pairing.answer_request, rid, bool((body or {}).get("approve")))
    if not ok:
        return Json({"error": "not found"}, status_code=404)
    return Json({"ok": True})


async def api_pair(request: Request):
    """Приложение меняет одноразовый код (deploy.sh или `python -m neuro.pairing`) на ключ доступа."""
    ip = request.client.host if request.client else "?"
    if _pair_limited(request):
        return Json({"error": "too many attempts, wait a minute"}, status_code=429)
    try:
        body = await request.json()
    except ValueError:
        return Json({"error": "bad json"}, status_code=400)
    code = str((body or {}).get("code") or "")

    def work():
        with db.connect() as conn:
            return pairing.redeem(conn, code)

    result = await anyio.to_thread.run_sync(work)
    if result == "ok" and config.API_TOKEN:
        log.info("app paired from %s", ip)
        return Json({"token": config.API_TOKEN, "name": "Mnemo"})
    status = {"bad": 403, "expired": 410, "locked": 423}.get(result, 500)
    return Json({"error": result if config.API_TOKEN else "API_TOKEN is empty"}, status_code=status)


async def api_status(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        chunks = await (await conn.execute(
            "SELECT status, count(*) AS n FROM audio_chunks GROUP BY status"
        )).fetchall()
        cost = await (await conn.execute(
            "SELECT coalesce(sum(cost), 0) AS c FROM llm_usage WHERE created_at > now() - interval '30 days'")).fetchone()
        # очередь расшифровки: живой звук идёт первым, запись из памяти кулона — за ним
        queue = await (await conn.execute(
            """SELECT coalesce(sum(duration_ms) FILTER (WHERE NOT offline), 0) / 1000 AS live_sec,
                      coalesce(sum(duration_ms) FILTER (WHERE offline), 0) / 1000 AS offline_sec
               FROM audio_chunks WHERE status IN ('pending', 'processing')""")).fetchone()
        devices = await (await conn.execute(
            "SELECT device_id, last_seen, last_audio, info FROM devices ORDER BY last_seen DESC NULLS LAST"
        )).fetchall()
        counts = await (await conn.execute(
            """SELECT (SELECT count(*) FROM conversations) AS conversations,
                      (SELECT count(*) FROM tasks WHERE status = 'open') AS open_tasks,
                      (SELECT count(*) FROM memories WHERE archived_at IS NULL) AS memories"""
        )).fetchone()
    return Json({
        "chunks": {r["status"]: r["n"] for r in chunks},
        "queue": {"live_sec": int(queue["live_sec"]), "offline_sec": int(queue["offline_sec"])},
        "ai_cost_30d": round(float(cost["c"] or 0), 2),
        "devices": devices,
        **counts,
        "stt_provider": config.STT_PROVIDER,
        "llm_configured": llm.configured(),
    })


async def api_conversations(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    limit = _clamp(request.query_params.get("limit"), 50, 1, 300)
    offset = _clamp(request.query_params.get("offset"), 0, 0, 1_000_000)
    day = request.query_params.get("day") or None  # YYYY-MM-DD в часовом поясе пользователя
    if day:
        try:
            date.fromisoformat(day)
        except ValueError:
            return Json({"error": "day: YYYY-MM-DD"}, status_code=400)
    async with await aconnect() as conn:
        rows = await (await conn.execute(
            """SELECT c.id, c.device_id, c.started_at, c.last_activity AS ended_at, c.status, c.title, c.summary,
                      c.emoji, c.topics, c.event_id,
                      greatest(0, extract(epoch FROM c.last_activity - c.started_at))::int AS duration_sec,
                      (SELECT count(*) FROM tasks t WHERE t.conversation_id = c.id) AS tasks_count,
                      """ + people.participants_sql("c") + """ AS people,
                      ARRAY(SELECT s.text FROM segments s WHERE s.conversation_id = c.id AND NOT s.junk
                            ORDER BY s.started_at LIMIT 6) AS preview
               FROM conversations c
               WHERE (%s::date IS NULL OR (c.started_at AT TIME ZONE %s)::date = %s::date) AND c.trashed_at IS NULL
               ORDER BY c.started_at DESC
               LIMIT %s OFFSET %s""",
            (day, config.TZ, day, limit, offset),
        )).fetchall()
    return Json({"items": previews(rows)})


async def api_days(request: Request):
    """Дни с записями за последние недели: для ленты дней на главной."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        rows = await (await conn.execute(
            """SELECT (c.started_at AT TIME ZONE %s)::date AS day, count(*) AS conversations,
                      coalesce(sum(greatest(0, extract(epoch FROM c.last_activity - c.started_at))), 0)::int
                          AS seconds
               FROM conversations c
               WHERE c.started_at > now() - interval '60 days' AND c.trashed_at IS NULL
               GROUP BY 1 ORDER BY 1 DESC""", (config.TZ,))).fetchall()
        # время собственно речи (сумма фраз), без пауз и тишины — «полезные разговоры» за день
        speech = await (await conn.execute(
            """SELECT (s.started_at AT TIME ZONE %s)::date AS day,
                      coalesce(sum(greatest(0, extract(epoch FROM s.ended_at - s.started_at))), 0)::int AS speech
               FROM segments s
               WHERE s.started_at > now() - interval '60 days'
               GROUP BY 1""", (config.TZ,))).fetchall()
    by_day = {r["day"]: r["speech"] for r in speech}
    for r in rows:
        r["speech_seconds"] = by_day.get(r["day"], 0)
    return Json({"items": rows})


async def api_search(request: Request):
    """Поиск по разговорам, фразам, задачам и памяти."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    q = (request.query_params.get("q") or "").strip()
    if len(q) < 2:
        return Json({"conversations": [], "segments": [], "tasks": [], "memories": []})
    return Json(await _sync(search_all, q))


def search_all(conn, q: str) -> dict:
    """Поиск без учёта регистра. Тексты в базе зашифрованы, поэтому сравниваем после расшифровки, а не в SQL:
    свежие записи — первыми, просматриваем порциями, пока не наберётся достаточно совпадений."""
    ql = q.lower()

    def has(*vals):
        return any(v and isinstance(v, str) and ql in v.lower() for v in vals)

    def scan(sql: str, fields: tuple, want: int, batch: int = 2000, cap: int = 60000) -> list:
        out, offset = [], 0
        while len(out) < want and offset < cap:
            rows = conn.execute(sql + " LIMIT %s OFFSET %s", (batch, offset)).fetchall()
            out += [r for r in rows if has(*(r.get(f) for f in fields))]
            if len(rows) < batch:
                break
            offset += batch
        return out[:want]

    return {
        "conversations": scan("""SELECT id, started_at, title, summary, emoji FROM conversations
                                 WHERE trashed_at IS NULL ORDER BY started_at DESC""", ("title", "summary"), 20),
        "segments": scan("""SELECT s.conversation_id, s.started_at, s.text, c.title, c.emoji FROM segments s
                            LEFT JOIN conversations c ON c.id = s.conversation_id
                            WHERE NOT s.junk ORDER BY s.started_at DESC""", ("text",), 30),
        "tasks": scan("SELECT id, text, status, due FROM tasks ORDER BY created_at DESC", ("text",), 20),
        "memories": scan("SELECT id, text, created_at FROM memories WHERE archived_at IS NULL ORDER BY created_at DESC", ("text",), 20),
    }


async def api_conversation(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    cid = request.path_params["id"]
    async with await aconnect() as conn:
        if request.method == "DELETE":
            res = await conn.execute("DELETE FROM conversations WHERE id = %s", (cid,))
            return Json({"deleted": res.rowcount})
        conv = await (await conn.execute(
            """SELECT id, device_id, started_at, last_activity AS ended_at, status, title, summary, error, emoji,
                      topics, greatest(0, extract(epoch FROM last_activity - started_at))::int AS duration_sec,
                      important_at IS NOT NULL AS important
               FROM conversations WHERE id = %s""", (cid,)
        )).fetchone()
        if conv is None:
            return Json({"error": "not found"}, status_code=404)
        conv["segments"] = await (await conn.execute(
            """SELECT s.id, s.started_at, s.ended_at, s.speaker, s.text, s.person_id, s.voice_score,
                      (s.chunk_id IS NOT NULL AND a.path IS NOT NULL) AS has_audio,
                      CASE WHEN s.raw_text IS DISTINCT FROM s.text THEN s.raw_text END AS raw_text
               FROM segments s LEFT JOIN audio_chunks a ON a.id = s.chunk_id
               WHERE s.conversation_id = %s AND NOT s.junk ORDER BY s.started_at, s.id""", (cid,)
        )).fetchall()
        conv["location"] = await _sync(geo.conversation_map, conv["started_at"], conv["ended_at"])
        match = voices.thresholds().match
        for seg in conv["segments"]:
            seg["sure"] = seg["voice_score"] is not None and seg["voice_score"] >= match
        ids = sorted({seg["person_id"] for seg in conv["segments"] if seg["person_id"]})
        conv["people"] = await (await conn.execute(
            """SELECT p.id, p.name, p.number, p.is_owner, p.ignored, p.kind, NOT """ + people.VISIBLE_SQL
            + """ AS transient FROM people p WHERE p.id = ANY(%s)""", (ids,)
        )).fetchall() if ids else []
        conv["tasks"] = await (await conn.execute(
            """SELECT id, text, due, due_at, assignee, person_id, kind, confidence, evidence, target, status, source,
                      exported_at, export_ref
               FROM tasks WHERE conversation_id = %s AND status <> 'dismissed' ORDER BY id""", (cid,)
        )).fetchall()
        conv["memories"] = await (await conn.execute(
            "SELECT id, text, source, created_at FROM memories WHERE conversation_id = %s AND archived_at IS NULL "
            "ORDER BY id", (cid,)
        )).fetchall()
    return Json(conv)


async def api_conversation_important(request: Request):
    """Звёздочка: {"on": true|false} — важный разговор (как двойное нажатие кнопки)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    cid = request.path_params["id"]
    body = await _body(request) or {}
    on = bool(body.get("on", True))
    async with await aconnect() as conn:
        res = await conn.execute(
            "UPDATE conversations SET important_at = CASE WHEN %s THEN coalesce(important_at, now()) END WHERE id = %s",
            (on, cid))
    if not res.rowcount:
        return Json({"error": "not found"}, status_code=404)
    return Json({"ok": True, "important": on})


async def api_important(request: Request):
    """Важные разговоры — по дате, новые сверху."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()

    def work(conn):
        rows = conn.execute(EVENT_FRAGMENTS_SQL + """ WHERE c.important_at IS NOT NULL AND c.trashed_at IS NULL
                                                    ORDER BY c.started_at DESC LIMIT 300""").fetchall()
        return {"items": previews(rows)}

    return Json(await _sync(work))


async def api_profile(request: Request):
    """Знакомство: GET — имя, чем занимается, цели, статус голоса; POST — сохранить (любые поля)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    if request.method == "GET":
        return Json(await _sync(profile.get))
    body = await _body(request) or {}
    goals = body.get("goals")
    if goals is not None and not isinstance(goals, list):
        return Json({"error": "goals: list"}, status_code=400)
    return Json(await _sync(lambda conn: profile.save(conn, body.get("name"), body.get("about"), goals)))


async def api_profile_voice(request: Request):
    """Прочитал фразы вслух: {"from": ms, "to": ms} — голос в этом окне станет голосом пользователя."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request) or {}
    try:
        start = datetime.fromtimestamp(int(body["from"]) / 1000, timezone.utc)
        end = datetime.fromtimestamp(int(body["to"]) / 1000, timezone.utc)
        job = await _sync(lambda conn: profile.start_voice(conn, start, end))
    except (KeyError, TypeError, ValueError) as exc:
        return Json({"error": str(exc)}, status_code=400)
    return Json({"voice": job["status"]})


async def api_calendar(request: Request):
    """Календарь телефона: GET — статус и календари; POST {"read", "write", "target"} — что можно ассистенту
    и куда добавлять встречи; DELETE — отключить (события с сервера удаляются)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    if request.method == "DELETE":
        await _sync(calendarlib.disconnect)
        return Json({"ok": True})
    if request.method == "POST":
        body = await _body(request) or {}
        target = body.get("target")
        return Json(await _sync(lambda conn: calendarlib.settings(
            conn, body.get("read"), body.get("write"), int(target) if str(target or "").lstrip("-").isdigit() else None)))
    return Json(await _sync(calendarlib.status))


async def api_calendar_sync(request: Request):
    """Телефон прислал события окна {from, to (мс), events: [...], calendars: [...]}."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request)
    if not body or not isinstance(body.get("events"), list) or "from" not in body or "to" not in body:
        return Json({"error": "events, from, to"}, status_code=400)
    try:
        n = await _sync(lambda conn: calendarlib.sync(conn, body["events"], body["from"], body["to"], body.get("calendars")))
    except (TypeError, ValueError) as exc:
        return Json({"error": str(exc)}, status_code=400)
    return Json({"ok": True, "events": n})


async def api_calendar_commands(request: Request):
    """Изменения от ассистента, которые телефон должен внести в календарь; POST /{id} {"ok", "result"} — итог."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    cid = request.path_params.get("id")
    if cid is not None:
        body = await _body(request) or {}
        ok = await _sync(lambda conn: calendarlib.complete(conn, int(cid), bool(body.get("ok")), str(body.get("result") or "")))
        return Json({"ok": ok}, status_code=200 if ok else 404)
    return Json({"items": await _sync(calendarlib.pending)})


async def api_routines(request: Request):
    """Правила и расписание агента (их ставит и убирает сам агент по просьбе в чате; здесь — посмотреть,
    выключить, удалить)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()

    def work(conn):
        return {
            "rules": [{"id": r["id"], "text": r["text"], "created_at": r["created_at"]} for r in routines.rules(conn)],
            "schedules": [{"id": x["id"], "title": x["title"], "prompt": x["prompt"], "when": routines.describe(x),
                           "next_run_at": x["next_run_at"], "enabled": x["enabled"], "last_run_at": x["last_run_at"],
                           "last_status": (x["last_status"] or "").split(":")[0] or None}
                          for x in routines.schedules(conn)],
        }

    return Json(await _sync(work))


async def api_rule(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    rid = request.path_params["id"]
    ok = await _sync(lambda conn: routines.forget_rule(conn, rid))
    return Json({"ok": ok}, status_code=200 if ok else 404)


async def api_schedule(request: Request):
    """POST {"enabled": bool}; DELETE — удалить."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    sid = request.path_params["id"]
    if request.method == "DELETE":
        ok = await _sync(lambda conn: routines.cancel_schedule(conn, sid))
    else:
        body = await _body(request) or {}
        ok = await _sync(lambda conn: routines.set_enabled(conn, sid, bool(body.get("enabled", True))))
    return Json({"ok": ok}, status_code=200 if ok else 404)


async def api_routine_results(request: Request):
    """Готовые ответы заданий по расписанию после run_id=after — приложение показывает уведомление."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    after = _clamp(request.query_params.get("after"), 0, 0, 2**62)

    def work(conn):
        return {"items": [{"run_id": r["id"], "question": r["question"], "answer": r["answer"], "status": r["status"],
                           "finished_at": r["finished_at"]} for r in routines.results(conn, after)]}

    return Json(await _sync(work))


async def api_conversation_reprocess(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    cid = request.path_params["id"]
    async with await aconnect() as conn:
        res = await conn.execute(
            "UPDATE conversations SET status = 'open', attempts = 0, error = NULL, updated_at = now() WHERE id = %s",
            (cid,),
        )
    return Json({"queued": res.rowcount})


async def api_tasks(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    status = request.query_params.get("status", "open")
    async with await aconnect() as conn:
        rows = await (await conn.execute(
            """SELECT t.id, t.conversation_id, t.text, t.due, t.due_at, t.assignee, t.person_id, t.kind, t.confidence,
                      t.evidence, t.target, t.status, t.source, t.counterpart, t.confirmed_at, t.done_at,
                      t.exported_at, t.export_ref, t.created_at, c.title AS conversation_title,
                      coalesce((SELECT json_agg(json_build_object(
                                    'ext', e.ext_id, 'ok', e.ok, 'ref', e.ref,
                                    'name', x.manifest->>'name', 'icon', x.manifest->>'icon'))
                                FROM task_exports e LEFT JOIN extensions x ON x.id = e.ext_id
                                WHERE e.task_id = t.id), '[]'::json) AS exports
               FROM tasks t LEFT JOIN conversations c ON c.id = t.conversation_id
               WHERE (%s = 'all' AND t.status <> 'dismissed' OR t.status = %s)
               ORDER BY CASE WHEN %s = 'dismissed' THEN coalesce(t.updated_at, t.created_at) ELSE t.created_at END DESC
               LIMIT 300""",  # удалённые — сначала последние удалённые
            (status, status, status),
        )).fetchall()
    return Json({"items": rows})


async def api_task_update(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    tid = request.path_params["id"]
    try:
        body = await request.json()
    except ValueError:
        return Json({"error": "bad json"}, status_code=400)
    status = body.get("status")
    if status is not None and status not in ("open", "done", "dismissed"):
        return Json({"error": "status must be open, done or dismissed"}, status_code=400)
    sets, args = [], []
    if status is not None:
        # «Да» в карточке — обещание подтверждено; выполнено — когда (для счётчика «выполнено N из M»)
        sets += ["status = %s", "confirmed_at = CASE WHEN %s = 'open' THEN coalesce(confirmed_at, now()) ELSE confirmed_at END",
                 "done_at = CASE WHEN %s = 'done' THEN coalesce(done_at, now()) WHEN %s = 'open' THEN NULL ELSE done_at END"]
        args += [status, status, status, status]
    if "due_at" in body:  # «Другой срок»: ISO-время или null — без срока
        due_at = tasklib.parse_due(body["due_at"]) if body["due_at"] else None
        if body["due_at"] and due_at is None:
            return Json({"error": "due_at: ISO 8601"}, status_code=400)
        sets += ["due_at = %s", "due = NULL"]
        args.append(due_at)
    if str(body.get("text") or "").strip():
        sets.append("text = %s")
        args.append(crypto.seal(" ".join(str(body["text"]).split())[:1000]))
    if not sets:
        return Json({"error": "nothing to change"}, status_code=400)
    async with await aconnect() as conn:
        res = await conn.execute(f"UPDATE tasks SET {', '.join(sets)}, updated_at = now() WHERE id = %s",  # noqa: S608
                                 (*args, tid))
    if res.rowcount == 0:
        return Json({"error": "not found"}, status_code=404)
    return Json({"id": tid, "status": status})


async def api_task_nudge(request: Request):
    """«Напомнить ему»: черновик короткого сообщения тому, кто обещал (пользователь отправит сам)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    tid = request.path_params["id"]

    def work(conn):
        t = conn.execute("SELECT * FROM tasks WHERE id = %s", (tid,)).fetchone()
        return promises.nudge(conn, t) if t else None

    try:
        text = await _sync(work)
    except Exception as exc:  # noqa: BLE001
        log.warning("nudge failed: %s", exc)
        return Json({"error": "llm"}, status_code=502)
    if text is None:
        return Json({"error": "not found"}, status_code=404)
    return Json({"text": text})


async def api_conversation_followup(request: Request):
    """Письмо по итогам встречи: тема и текст (пользователь правит и отправляет сам)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    cid = int(request.path_params["id"])
    try:
        return Json(await _sync(lambda conn: promises.followup(conn, cid)))
    except ValueError:
        return Json({"error": "not found"}, status_code=404)
    except Exception as exc:  # noqa: BLE001
        log.warning("followup failed: %s", exc)
        return Json({"error": "llm"}, status_code=502)


MEMORY_SELECT = """SELECT m.id, m.conversation_id, m.text, m.source, m.created_at, m.updated_at,
                          c.title AS conversation_title
                   FROM memories m LEFT JOIN conversations c ON c.id = m.conversation_id"""


async def api_memories(request: Request):
    """Память: ?archived=1 — архив (то, что модель сочла лишним; можно вернуть); ?q=… — поиск по смыслу."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    archived = request.query_params.get("archived") == "1"
    query = (request.query_params.get("q") or "").strip()[:300]

    def work(conn):
        if query:
            ids = [h["id"] for h in memorylib.recall(conn, query, k=30, kinds=("memory",))]
            found = {r["id"]: r for r in conn.execute(MEMORY_SELECT + " WHERE m.id = ANY(%s::bigint[])", (ids,)).fetchall()}
            rows = [found[i] for i in ids if i in found]
        else:
            rows = conn.execute(MEMORY_SELECT + """ WHERE (m.archived_at IS NOT NULL) = %s
                                ORDER BY coalesce(m.archived_at, m.updated_at, m.created_at) DESC LIMIT 300""",
                                (archived,)).fetchall()
        counts = conn.execute("""SELECT count(*) FILTER (WHERE archived_at IS NOT NULL) AS archived,
                                        count(*) FILTER (WHERE archived_at IS NULL) AS active FROM memories""").fetchone()
        return {"items": rows, "archived": counts["archived"], "active": counts["active"]}

    return Json(await _sync(work))


async def api_memory(request: Request):
    """Одна запись памяти: POST {"archived": true|false} — в архив / вернуть; POST {"text"} — исправить
    (отпечаток для поиска пересчитается сам); DELETE — удалить совсем."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    mid = int(request.path_params["id"])
    async with await aconnect() as conn:
        if request.method == "DELETE":
            res = await conn.execute("DELETE FROM memories WHERE id = %s", (mid,))
        else:
            body = await _body(request)
            text = " ".join(str((body or {}).get("text") or "").split())[:600]
            if body is not None and "text" in body:
                if len(text) < 2:
                    return Json({"error": "text"}, status_code=400)
                res = await conn.execute(
                    """UPDATE memories SET text = %s, emb = NULL, updated_at = now(), reviewed = true, archived_at = NULL
                       WHERE id = %s""", (crypto.seal(text), mid))
            elif body is None or "archived" not in body:
                return Json({"error": "archived"}, status_code=400)
            else:
                res = await conn.execute(
                    "UPDATE memories SET archived_at = CASE WHEN %s THEN now() ELSE NULL END WHERE id = %s",
                    (bool(body["archived"]), mid))
    if res.rowcount == 0:
        return Json({"error": "not found"}, status_code=404)
    return Json({"ok": True})



# ---------------------------------------------------------------------------
# Голосовой вопрос по кнопке: /ws/ask
#   приложение -> {"type":"start","id":..,"codec":21}, затем кадры Opus (по одному в бинарном сообщении),
#                 {"type":"stop"} при повторном нажатии
#   сервер     -> {"type":"listening"} -> {"type":"heard"} (вопрос закончен) -> {"type":"answer", ...}
# ---------------------------------------------------------------------------
async def ws_ask(websocket: WebSocket):
    device = websocket.query_params.get("device_id", "")
    if not _authorized(websocket.headers, websocket.query_params) or not DEVICE_RE.match(device):
        await websocket.close(code=4401)
        return
    await websocket.accept()
    decoder = FrameDecoder()
    try:
        first = await asyncio.wait_for(websocket.receive_text(), timeout=10)
        start = json.loads(first)
        if start.get("type") != "start":
            await websocket.send_json({"type": "error", "message": "ожидался start"})
            return
        codec = int(start.get("codec") or 21)
        client_id = str(start.get("id") or "") or None
        voice = str(start.get("voice") or "")  # нейроголос телефона: начнём озвучку ответа сразу
        try:
            speed = float(start.get("rate") or 1.0)
        except (TypeError, ValueError):
            speed = 1.0
        # press — вопрос заканчивается только повторным нажатием (паузы не обрывают), auto — по паузе
        press = str(start.get("mode") or "press") != "auto"
        await websocket.send_json({"type": "listening"})

        loop = asyncio.get_running_loop()
        deadline = loop.time() + (config.ASK_PRESS_MAX_SEC if press else config.ASK_MAX_SEC)
        endpointer = assistant.Endpointer()
        pcm = bytearray()
        reason = "max"
        while True:
            remaining = deadline - loop.time()
            if remaining <= 0:
                break
            # если речь уже была и кадры перестали идти (кулон замолчал или отключился) — вопрос закончен
            wait = min(remaining, (10.0 if press else 1.5) if endpointer.started else remaining)
            try:
                msg = await asyncio.wait_for(websocket.receive(), timeout=wait)
            except asyncio.TimeoutError:
                reason = "gap" if endpointer.started else "max"
                break
            if msg["type"] == "websocket.disconnect":
                return
            if msg.get("bytes") is not None:
                try:
                    chunk = decoder.decode(codec, [msg["bytes"]])
                except BatchError:
                    continue
                pcm += chunk
                if endpointer.feed(chunk) and not press:
                    reason = "silence"
                    break
            elif msg.get("text"):
                try:
                    kind = json.loads(msg["text"]).get("type")
                except ValueError:
                    kind = None
                if kind == "stop":
                    reason = "stop"
                    break
                if kind == "cancel":  # передумал спрашивать
                    log.info("ask cancelled while listening")
                    return
        await websocket.send_json({"type": "heard", "reason": reason, "seconds": round(len(pcm) / 32000, 1)})
        log.info("ask from %s: %.1fs audio, end=%s", device, len(pcm) / 32000, reason)

        reply = await answer_voice(device, client_id, bytes(pcm), websocket)
        if reply is None:
            log.info("ask: phone left before the answer, it goes to chat")
            return
        if voice in tts.VOICES and reply.get("answer") and not reply.get("cancelled"):
            tts.prewarm(reply["answer"], voice, i18n.normalize(str(start.get("lang") or "ru")), speed)
        await websocket.send_text(json.dumps(reply, ensure_ascii=False))
        # в журнал — только цифры (вопросы и ответы — личное)
        log.info("ask answered in %sms (%s)%s%s", reply.get("latency_ms"), reply.get("level"),
                 " pending" if reply.get("pending") else "", " cancelled" if reply.get("cancelled") else "")
    except (WebSocketDisconnect, asyncio.TimeoutError):
        pass
    except Exception:  # noqa: BLE001
        log.exception("ask failed")
        try:
            await websocket.send_json({"type": "answer", "question": "", "error": True,
                                       "answer": "На сервере ошибка, попробуйте ещё раз."})
        except Exception:  # noqa: BLE001
            pass
    finally:
        decoder.close()
        try:
            await websocket.close()
        except Exception:  # noqa: BLE001
            pass


async def api_questions(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        rows = await (await conn.execute(
            """SELECT id, asked_at, question, answer, model, latency_ms, via, error IS NOT NULL AS failed
               FROM questions ORDER BY asked_at DESC LIMIT 100"""
        )).fetchall()
    return Json({"items": rows})


# ---------------------------------------------------------------------------
# Модели: какая модель на какой задаче, каталог с живыми ценами OpenRouter, расходы
# ---------------------------------------------------------------------------
_live_models: dict = {"at": 0.0, "models": None}
MODEL_ID_RE = re.compile(r"^[A-Za-z0-9._-]+/[A-Za-z0-9._:-]+$")


def _fetch_openrouter_models() -> dict[str, dict]:
    with urllib.request.urlopen("https://openrouter.ai/api/v1/models", timeout=15) as r:
        data = json.load(r).get("data") or []
    out = {}
    for m in data:
        p = m.get("pricing") or {}
        try:
            price_in = float(p.get("prompt") or 0) * 1_000_000
            price_out = float(p.get("completion") or 0) * 1_000_000
        except (TypeError, ValueError):
            continue
        out[m["id"]] = {"id": m["id"], "name": m.get("name") or m["id"], "price_in": round(price_in, 4),
                        "price_out": round(price_out, 4), "context": m.get("context_length")}
    return out


async def live_models() -> dict[str, dict] | None:
    if not llm.is_openrouter():
        return None
    ttl = 6 * 3600 if _live_models["models"] else 3600
    if _live_models["at"] == 0.0 or time.monotonic() - _live_models["at"] > ttl:
        _live_models["at"] = time.monotonic()
        try:
            _live_models["models"] = await anyio.to_thread.run_sync(_fetch_openrouter_models)
        except Exception as exc:  # noqa: BLE001
            log.warning("cannot fetch OpenRouter prices (retry in 1h): %s", exc)
    return _live_models["models"]


async def api_models(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        if request.method == "POST":
            try:
                body = await request.json()
            except ValueError:
                return Json({"error": "bad json"}, status_code=400)
            task_key, model = str(body.get("task") or ""), str(body.get("model") or "").strip()
            if task_key not in TASK_KEYS:
                return Json({"error": "unknown task"}, status_code=400)
            if model and not MODEL_ID_RE.match(model):
                return Json({"error": "bad model id"}, status_code=400)
            if model:
                await conn.execute(
                    """INSERT INTO settings (key, value) VALUES (%s, %s)
                       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                    (f"model.{task_key}", model),
                )
            else:
                await conn.execute("DELETE FROM settings WHERE key = %s", (f"model.{task_key}",))
        rows = await (await conn.execute("SELECT key, value FROM settings WHERE key LIKE 'model.%%'")).fetchall()
        chosen = {r["key"][6:]: r["value"] for r in rows}
        lang_row = await (await conn.execute("SELECT value FROM settings WHERE key = 'app.language'")).fetchone()
        lang = i18n.normalize(lang_row["value"] if lang_row else None)
        spend = await (await conn.execute(
            """SELECT task, coalesce(sum(cost), 0) AS cost, count(*) AS calls FROM llm_usage
               WHERE created_at > now() - interval '30 days' GROUP BY task"""
        )).fetchall()
    live = await live_models()
    tasks = []
    for t in TASKS:
        model = chosen.get(t["key"]) or llm.resolve_model(None, t["key"])
        tasks.append({**localized_task(t, lang), "model": model, "custom": t["key"] in chosen})
    out = {
        "provider": "openrouter" if llm.is_openrouter() else "custom",
        "configured": llm.configured(),
        "stt_provider": config.STT_PROVIDER,
        "tasks": tasks,
        "catalog": [localized_model(m, lang) for m in llm.catalog_with_live_prices(live)],
        "spend_30d": {r["task"]: round(float(r["cost"]), 4) for r in spend},
        "spend_30d_total": round(sum(float(r["cost"]) for r in spend), 4),
        "live_prices": live is not None,
    }
    if request.query_params.get("all") and live:
        out["all"] = sorted(live.values(), key=lambda m: (m["price_in"] + m["price_out"], m["id"]))
    return Json(out)


# Голосовой вопрос ждёт полного ответа (с прогрессом и кнопкой «Стоп»); дольше — ответ придёт в чат
VOICE_WAIT_SEC = config.env_float("VOICE_WAIT_SEC", 240.0)


def _run_in_thread(fn, *args):
    """Запуск в пуле потоков, который можно не дожидаться (агент продолжит работу после ответа голосом)."""
    return asyncio.get_running_loop().run_in_executor(None, fn, *args)


def _execute_run(run_id: int) -> str:
    with db.connect() as conn:
        return agent_core.execute(conn, run_id)


async def _until_cancel(websocket) -> str:
    """Ждёт от телефона {"type":"cancel"} («Стоп» в приложении). Возвращает "cancel" или "gone" (телефон ушёл)."""
    if websocket is None:
        await asyncio.Event().wait()
    while True:
        msg = await websocket.receive()
        if msg["type"] == "websocket.disconnect":
            return "gone"
        if msg.get("text"):
            try:
                if json.loads(msg["text"]).get("type") == "cancel":
                    return "cancel"
            except ValueError:
                pass


def _new_steps(run_id: int, after: int) -> list[dict]:
    with db.connect() as conn:
        return conn.execute(
            """SELECT id, kind, title FROM agent_steps WHERE run_id = %s AND id > %s AND kind IN ('plan', 'tool', 'ask')
               ORDER BY id""", (run_id, after)).fetchall()


async def answer_voice(device: str, client_id: str | None, pcm: bytes, websocket=None) -> dict | None:
    """Вопрос с кнопки: распознать -> уровень -> агент -> ждём ответ (до VOICE_WAIT_SEC), показывая шаги.
    Телефон может остановить работу ({"type":"cancel"}). None — телефон отключился: ответ будет в чате."""
    t0 = time.monotonic()

    def current_lang():
        with db.connect() as conn:
            return i18n.lang(conn)

    lang = await _run_in_thread(current_lang)
    if len(pcm) < 16000:
        return {"type": "answer", "question": "", "answer": i18n.t(lang, "voice.not_heard")}
    if not llm.configured():
        return {"type": "answer", "question": "", "error": True, "answer": i18n.t(lang, "voice.no_model")}

    def transcribe():
        with db.connect() as conn:
            return assistant.transcribe_question(conn, pcm)

    try:
        question = await _run_in_thread(transcribe)
    except Exception:  # noqa: BLE001
        log.exception("ask transcription failed")
        return {"type": "answer", "question": "", "error": True, "answer": i18n.t(lang, "voice.failed")}
    if not question:
        return {"type": "answer", "question": "", "answer": i18n.t(lang, "voice.not_heard")}
    # вопрос по кнопке — точно голос пользователя: STT дообучит по нему отпечаток «Я»
    try:
        await anyio.to_thread.run_sync(voices.queue_owner_sample, pcm)
    except Exception:  # noqa: BLE001
        log.warning("cannot keep owner voice sample", exc_info=True)

    level = agent_core.route(question, "voice")

    def start():
        with db.connect() as conn:
            return agent_core.start_run(conn, question, level, "voice", device)[0]

    run_id = await _run_in_thread(start)
    future = _run_in_thread(_execute_run, run_id)
    if websocket is not None:
        await websocket.send_json({"type": "question", "question": question, "level": level, "run_id": run_id})
    watcher = asyncio.ensure_future(_until_cancel(websocket))
    answer, pending, cancelled, last = None, False, False, 0
    try:
        while answer is None:
            done, _ = await asyncio.wait({future, watcher}, timeout=1.0, return_when=asyncio.FIRST_COMPLETED)
            if future in done:
                answer = future.result()
                cancelled = answer == i18n.t(lang, "agent.cancelled")
            elif watcher in done:
                if watcher.result() == "gone":
                    return None  # агент доработает, ответ появится в чате
                def stop():
                    with db.connect() as conn:
                        agent_core.cancel(conn, run_id)
                await _run_in_thread(stop)
                answer, cancelled = i18n.t(lang, "agent.cancelled"), True
            elif time.monotonic() - t0 > VOICE_WAIT_SEC:
                answer, pending = i18n.t(lang, "voice.later"), True
            elif websocket is not None:
                for st in await _run_in_thread(_new_steps, run_id, last):
                    last = st["id"]
                    await websocket.send_json({"type": "progress", "kind": st["kind"], "text": st["title"]})
    finally:
        if not watcher.done():
            watcher.cancel()
    latency = int((time.monotonic() - t0) * 1000)

    def record():
        with db.connect() as conn:
            assistant.record_question(conn, device, client_id, question, answer,
                                      llm.resolve_model(conn, agent_core.LEVELS[level]["task"]), latency)

    await _run_in_thread(record)
    return {"type": "answer", "question": question, "answer": answer, "level": level, "run_id": run_id,
            "pending": pending, "cancelled": cancelled, "latency_ms": latency}


# ---------------------------------------------------------------------------
# Чат с агентом
# ---------------------------------------------------------------------------
async def api_chat(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    if request.method == "POST":
        try:
            body = await request.json()
        except ValueError:
            return Json({"error": "bad json"}, status_code=400)
        text = str(body.get("text") or "").strip()
        level = str(body.get("level") or "auto")
        agent = str(body.get("agent") or "mnemo")
        context = str(body.get("context") or "").strip() or None
        if not text:
            return Json({"error": "empty"}, status_code=400)
        if agent not in ("mnemo", "gpt", "claude"):
            return Json({"error": "agent: mnemo | gpt | claude"}, status_code=400)
        if context and not CONTEXT_RE.match(context):
            return Json({"error": "context: event:ID | conversation:ID | day:YYYY-MM-DD"}, status_code=400)
        if level != "auto" and level not in agent_core.LEVELS:
            level = "auto"  # уровень выбирается сам (старые приложения ещё присылают claude / claude_code)
        if not llm.configured():
            return Json({"error": "на сервере не задан ключ модели (OPENROUTER_API_KEY)"}, status_code=400)
        level = agent_core.route(text, "chat") if level == "auto" else level

        def start():
            with db.connect() as conn:
                return agent_core.start_run(conn, text, level, "chat", None, agent, context)

        run_id, message_id = await _run_in_thread(start)
        _run_in_thread(_execute_run, run_id)
        return Json({"run_id": run_id, "message_id": message_id, "level": level, "agent": agent})

    limit = _clamp(request.query_params.get("limit"), 60, 1, 300)
    async with await aconnect() as conn:
        messages = await (await conn.execute(
            """SELECT * FROM (SELECT id, role, text, channel, run_id, created_at FROM chat_messages
               ORDER BY id DESC LIMIT %s) x ORDER BY id""", (limit,))).fetchall()
        run_ids = sorted({m["run_id"] for m in messages if m["run_id"]})
        runs = {}
        if run_ids:
            for r in await (await conn.execute(
                    "SELECT id, level, status, model, agent, context FROM agent_runs WHERE id = ANY(%s)",
                    (run_ids,))).fetchall():
                runs[r["id"]] = {**r, "steps": []}
            for st in await (await conn.execute(
                    """SELECT run_id, kind, title, detail, created_at FROM agent_steps
                       WHERE run_id = ANY(%s) ORDER BY id""", (run_ids,))).fetchall():
                runs[st["run_id"]]["steps"].append(st)
    return Json({"messages": messages, "runs": {str(k): v for k, v in runs.items()},
                 "running": any(r["status"] == "running" for r in runs.values())})


async def api_share(request: Request):
    """«Скопировать»: всё о событии, разговоре или дне простым текстом (итог, люди, части, расшифровка)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    ref = request.query_params.get("ref", "")
    if not CONTEXT_RE.match(ref):
        return Json({"error": "ref: event:ID | conversation:ID | day:YYYY-MM-DD"}, status_code=400)

    def build():
        with db.connect() as conn:
            return agent_context.share_text(conn, ref, i18n.lang(conn))

    got = await _run_in_thread(build)
    if got is None:
        return Json({"error": "not found"}, status_code=404)
    return Json({"title": got[0], "text": got[1]})


async def api_run_cancel(request: Request):
    """Остановить запуск агента (кнопка «Стоп» в чате). Claude останавливается своим способом."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    run_id = int(request.path_params["run_id"])

    def stop():
        with db.connect() as conn:
            row = conn.execute("SELECT level, status FROM agent_runs WHERE id = %s", (run_id,)).fetchone()
            if row is None:
                return None
            return agent_core.cancel(conn, run_id)

    stopped = await _run_in_thread(stop)
    if stopped is None:
        return Json({"error": "not found"}, status_code=404)
    return Json({"stopped": bool(stopped)})


async def api_chat_clear(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        await conn.execute("DELETE FROM chat_messages")
    return Json({"cleared": True})


MCP_KEY = "mcp.token_hash"


def _mcp_personal(token: str | None) -> bool:
    """Личный ключ MCP (Claude Desktop, Cursor…): в базе только его хеш."""
    if not token:
        return False
    with db.connect() as conn:
        row = conn.execute("SELECT value FROM settings WHERE key = %s", (MCP_KEY,)).fetchone()
    want = row["value"] if row else ""
    return bool(want) and hmac.compare_digest(want, hashlib.sha256(token.encode()).hexdigest())


async def mcp_endpoint(request: Request):
    """Инструменты Mnemo по MCP (JSON-RPC): для Claude внутри Mnemo (токен запуска) и для ваших программ —
    Claude Desktop, Cursor и других (личный ключ из «Интеграций», его можно отозвать)."""
    token = _token(request.headers, {})
    if not await anyio.to_thread.run_sync(_mcp_personal, token):
        return _unauthorized()
    if request.method != "POST":
        return Json({"error": "method not allowed"}, status_code=405)  # SSE-поток не держим
    try:
        body = await request.json()
    except ValueError:
        return Json({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}}, status_code=400)
    batch = body if isinstance(body, list) else [body]
    replies = [r for r in [await _sync(mnemo_mcp.handle, m) for m in batch if isinstance(m, dict)] if r is not None]
    if not replies:
        return Response(status_code=202)
    return Json(replies if isinstance(body, list) else replies[0])


async def api_mcp(request: Request):
    """Mnemo в других программах: GET — подключён ли, POST — новый ключ (показывается один раз), DELETE — отозвать."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        if request.method == "POST":
            token = "mnemo_" + secrets.token_urlsafe(32)
            await conn.execute(
                """INSERT INTO settings (key, value) VALUES (%s, %s)
                   ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                (MCP_KEY, hashlib.sha256(token.encode()).hexdigest()))
            return Json({"enabled": True, "token": token, "path": "/mcp"})
        if request.method == "DELETE":
            await conn.execute("DELETE FROM settings WHERE key = %s", (MCP_KEY,))
            return Json({"enabled": False, "path": "/mcp"})
        row = await (await conn.execute("SELECT updated_at FROM settings WHERE key = %s", (MCP_KEY,))).fetchone()
    return Json({"enabled": row is not None, "since": row["updated_at"] if row else None, "path": "/mcp"})


# ---------------------------------------------------------------------------
# Маркетплейс расширений
# ---------------------------------------------------------------------------
async def api_extensions(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    reg = await anyio.to_thread.run_sync(extensions.registry)
    async with await aconnect() as conn:
        rows = await (await conn.execute(
            "SELECT id, manifest, enabled, settings, source_url, installed_at FROM extensions ORDER BY installed_at"
        )).fetchall()
        lang_row = await (await conn.execute("SELECT value FROM settings WHERE key = 'app.language'")).fetchone()
    lang = i18n.normalize(lang_row["value"] if lang_row else None)
    installed = []
    for r in rows:
        pub = extensions.public_manifest(r["manifest"], lang)
        filled = {k for k, v in (r["settings"] or {}).items() if v}
        for st in pub["settings"]:
            st["filled"] = st["key"] in filled
            if not st["secret"]:
                st["value"] = (r["settings"] or {}).get(st["key"], "")
        if pub["events"]:
            pub["delivery"] = await _sync(hooks.stats, r["id"])
        installed.append({**pub, "enabled": r["enabled"], "source_url": r["source_url"],
                          "ready": all(st["filled"] for st in pub["settings"] if st["required"])})
    ids = {e["id"] for e in installed}
    available = [{k: v for k, v in extensions.localize(e, lang).items()
                  if not k.startswith("_") and k not in ("manifest", "i18n")}
                 for e in reg if e["id"] not in ids and e["id"] not in connectors.CATALOG]  # Notion, Linear — одной кнопкой
    return Json({"installed": installed, "available": available})


async def api_extension_install(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    try:
        body = await request.json()
    except ValueError:
        return Json({"error": "bad json"}, status_code=400)

    def load():
        if isinstance(body.get("manifest"), dict):  # собранная с ассистентом и проверенная пользователем
            return extensions.validate(dict(body["manifest"])), "assistant"
        if body.get("manifest_url"):
            return extensions.load_manifest(str(body["manifest_url"]))
        entry = next((e for e in extensions.registry() if e["id"] == body.get("id")), None)
        if entry is None:
            raise extensions.ExtensionError("нет такого расширения в маркетплейсе")
        return extensions.load_manifest(entry)

    try:
        manifest, src = await anyio.to_thread.run_sync(load)
    except Exception as exc:  # noqa: BLE001
        return Json({"error": f"не удалось установить: {exc}"}, status_code=400)
    async with await aconnect() as conn:
        await conn.execute(
            """INSERT INTO extensions (id, manifest, source_url) VALUES (%s, %s::jsonb, %s)
               ON CONFLICT (id) DO UPDATE SET manifest = EXCLUDED.manifest, source_url = EXCLUDED.source_url""",
            (manifest["id"], json.dumps(manifest, ensure_ascii=False), src),
        )
    return Json({"installed": extensions.public_manifest(manifest)})


async def api_connectors(request: Request):
    """Подключения одной кнопкой: GET — список (Notion, Linear…) со статусом."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()

    def work(conn):
        return connectors.listing(conn, i18n.lang(conn))

    return Json({"items": await _sync(work)})


async def api_connector_start(request: Request):
    """«Подключить»: ссылка на вход в сервис (браузер). server_url — адрес сервера, как его видит телефон."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request) or {}
    cid = request.path_params["id"]
    server_url = str(body.get("server_url") or "").strip() or None

    def work(conn):
        return connectors.start(conn, cid, server_url)

    try:
        return Json({"url": await _sync(work)})
    except connectors.ConnectorError as exc:
        return Json({"error": str(exc)}, status_code=400)


async def api_connector_finish(request: Request):
    """Приложение получило код из сервиса (codes.neuro.mnemo://oauth) — меняем на токены."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request) or {}

    def work(conn):
        return connectors.finish(conn, str(body.get("state") or ""), str(body.get("code") or ""))

    try:
        return Json(await _sync(work))
    except connectors.ConnectorError as exc:
        return Json({"error": str(exc)}, status_code=400)


async def oauth_callback(request: Request):
    """Сервис вернул код на сервер (если приложение как адрес возврата не подошло). Проверка — по state."""
    q = request.query_params
    if q.get("error"):
        text = "Доступ не выдан. Вернитесь в Mnemo."
    else:
        def work(conn):
            return connectors.finish(conn, str(q.get("state") or ""), str(q.get("code") or ""))

        try:
            card = await _sync(work)
            text = f"{card['name']} подключён. Вернитесь в Mnemo."
        except connectors.ConnectorError as exc:
            text = f"Не получилось: {exc}"
    html = (f"<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
            f"<body style='font-family:sans-serif;padding:32px;font-size:20px'>{html_escape(text)}"
            f"<p><a href='{connectors.APP_REDIRECT}/done'>Открыть Mnemo</a></body>")
    return Response(html, media_type="text/html")


async def api_connector(request: Request):
    """POST {"read", "write": true|false} — что ассистенту можно в сервисе (просматривать; создавать и
    изменять); DELETE — отключить."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    cid = request.path_params["id"]
    if request.method == "DELETE":
        await _sync(lambda conn: connectors.disconnect(conn, cid))
        return Json({"ok": True})
    body = await _body(request) or {}

    def work(conn):
        row = conn.execute("SELECT settings FROM connections WHERE id = %s", (cid,)).fetchone()
        if row is None:
            return False
        st = dict(row["settings"] or {})
        for key in ("read", "write"):
            if key in body:
                st[key] = bool(body[key])
        conn.execute("UPDATE connections SET settings = %s::jsonb WHERE id = %s", (json.dumps(st), cid))
        return True

    if not await _sync(work):
        return Json({"error": "not found"}, status_code=404)
    return Json({"ok": True})


async def api_extension_draft(request: Request):
    """«Создать с ассистентом»: описание словами -> черновик манифеста (ещё не установлен)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request)
    if body is None or not str(body.get("description") or "").strip():
        return Json({"error": "description"}, status_code=400)
    if not llm.configured():
        return Json({"error": "на сервере не задан ключ модели (OPENROUTER_API_KEY)"}, status_code=400)

    def build():
        with db.connect() as conn:
            return ext_builder.draft(conn, str(body["description"]),
                                     body.get("previous") if isinstance(body.get("previous"), dict) else None,
                                     str(body.get("feedback") or "") or None)

    try:
        return Json(await _run_in_thread(build))
    except Exception as exc:  # noqa: BLE001
        log.exception("extension draft failed")
        return Json({"error": f"модель не ответила: {exc.__class__.__name__}"}, status_code=502)


async def api_extension(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    ext_id = request.path_params["id"]
    async with await aconnect() as conn:
        if request.method == "DELETE":
            res = await conn.execute("DELETE FROM extensions WHERE id = %s", (ext_id,))
            return Json({"deleted": res.rowcount})
        try:
            body = await request.json()
        except ValueError:
            return Json({"error": "bad json"}, status_code=400)
        if "enabled" in body:
            await conn.execute("UPDATE extensions SET enabled = %s WHERE id = %s", (bool(body["enabled"]), ext_id))
        if isinstance(body.get("settings"), dict):
            row = await (await conn.execute("SELECT manifest, settings FROM extensions WHERE id = %s", (ext_id,))).fetchone()
            urls = {st["key"] for st in ((row or {}).get("manifest") or {}).get("settings") or [] if st.get("type") == "url"}
            for k in urls & set(body["settings"]):
                if body["settings"][k]:
                    try:
                        extensions.check_user_url(str(body["settings"][k]).strip())
                    except extensions.ExtensionError as exc:
                        return Json({"error": str(exc)}, status_code=400)
            # ключи и токены расширений — зашифрованы в базе (каждое значение отдельно: слияние || работает)
            clean = {str(k): crypto.seal(str(v)) for k, v in body["settings"].items() if v is not None}
            await conn.execute("UPDATE extensions SET settings = settings || %s::jsonb WHERE id = %s",
                               (json.dumps(clean, ensure_ascii=False), ext_id))
            # заполнили всё нужное впервые — интеграция заработала, включаем сразу (без второго нажатия)
            if row and not extensions.is_ready(row) and "enabled" not in body:
                now = await (await conn.execute("SELECT manifest, settings FROM extensions WHERE id = %s",
                                                (ext_id,))).fetchone()
                if now and extensions.is_ready(now):
                    await conn.execute("UPDATE extensions SET enabled = true WHERE id = %s", (ext_id,))
    return Json({"ok": True})


# ---------------------------------------------------------------------------
# Дневник жизни: лента дня, фото, настройки функций
# ---------------------------------------------------------------------------
FEATURE_KEYS = {"diary": "feature.diary", "photo_ai": "feature.photo_ai", "photo_style": "feature.photo_style", "style": "diary.style",
                "photos_per_day": "diary.photos_per_day", "transcript_cleanup": cleanup.SETTING}


async def _settings_map(conn) -> dict:
    rows = await (await conn.execute("SELECT key, value FROM settings")).fetchall()
    return {**diary.DEFAULTS, **{r["key"]: r["value"] for r in rows}}


async def api_features(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        if request.method == "POST":
            try:
                body = await request.json()
            except ValueError:
                return Json({"error": "bad json"}, status_code=400)
            if "language" in body:
                code = i18n.normalize(str(body["language"]))
                await conn.execute(
                    """INSERT INTO settings (key, value) VALUES ('app.language', %s)
                       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", (code,))
                # язык расшифровки по умолчанию следует за языком приложения (а не за STT_LANGUAGE из .env)
                await conn.execute("INSERT INTO settings (key, value) VALUES ('stt.language', 'app') "
                                   "ON CONFLICT (key) DO NOTHING")
            if "stt_language" in body:
                choice = str(body["stt_language"])
                if choice not in i18n.STT_CHOICES:
                    return Json({"error": "unknown language"}, status_code=400)
                await conn.execute(
                    """INSERT INTO settings (key, value) VALUES ('stt.language', %s)
                       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", (choice,))
            if "audio_keep_days" in body:
                try:
                    days = int(body["audio_keep_days"])
                except (TypeError, ValueError):
                    days = -1
                if days not in retention.CHOICES:
                    return Json({"error": "audio_keep_days: " + " | ".join(map(str, retention.CHOICES))},
                                status_code=400)
                await conn.execute(
                    """INSERT INTO settings (key, value) VALUES (%s, %s)
                       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                    (retention.KEY, str(days)))
            for name, key in FEATURE_KEYS.items():
                if name not in body:
                    continue
                v = body[name]
                if name in ("diary", "photo_ai", "photo_style", "transcript_cleanup"):
                    v = "1" if v else "0"
                elif name == "style":
                    if v not in diary.STYLES:
                        return Json({"error": "unknown style"}, status_code=400)
                else:
                    v = str(max(0, min(int(v), 10)))
                await conn.execute(
                    """INSERT INTO settings (key, value) VALUES (%s, %s)
                       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", (key, v))
        st = await _settings_map(conn)
    return Json({
        "diary": st["feature.diary"] == "1",
        "photo_ai": st["feature.photo_ai"] == "1",
        "photo_style": st["feature.photo_style"] == "1",
        "style": st["diary.style"],
        "photos_per_day": int(st["diary.photos_per_day"] or 3),
        "styles": [{"key": k, "title": v[0]} for k, v in diary.STYLES.items()],
        "language": i18n.normalize(st.get("app.language")),
        "stt_language": st.get("stt.language") or config.STT_LANGUAGE or "app",
        "languages": [{"code": k, "name": v["name"]} for k, v in i18n.LANGS.items()],
        "audio_keep_days": retention.parse(st[retention.KEY]) if retention.KEY in st else config.KEEP_AUDIO_DAYS,
        "audio_keep_choices": list(retention.CHOICES),
        "transcript_cleanup": st.get(cleanup.SETTING, "1") != "0",
        "map_attribution": geo.ATTRIBUTION,
    })


async def api_diary(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    limit = _clamp(request.query_params.get("limit"), 14, 1, 60)
    async with await aconnect() as conn:
        days = await (await conn.execute(
            """SELECT day, title, summary, mood, quote, highlights, places, stats, cover_photo_id, status,
                      generated_at, error
               FROM diary_days WHERE title IS NOT NULL OR status = 'failed'
               ORDER BY day DESC LIMIT %s""", (limit,))).fetchall()  # пустые дни не показываем
        ids = sorted({h["photo_id"] for d in days for h in (d["highlights"] or []) if h.get("photo_id")})
        photos = {}
        if ids:
            for p in await (await conn.execute(
                    "SELECT id, taken_at, caption, styled_path IS NOT NULL AS styled, style FROM photos "
                    "WHERE id = ANY(%s)", (ids,))).fetchall():
                photos[str(p["id"])] = p
    return Json({"days": days, "photos": photos})


async def api_diary_generate(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    try:
        day = date.fromisoformat(request.path_params["day"])
    except ValueError:
        return Json({"error": "day: YYYY-MM-DD"}, status_code=400)
    async with await aconnect() as conn:
        await conn.execute(
            """INSERT INTO diary_days (day, status) VALUES (%s, 'pending')
               ON CONFLICT (day) DO UPDATE SET status = 'pending'""", (day,))
    return Json({"queued": str(day)})


async def api_photos_upload(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    data = await request.body()
    if not data or len(data) > 20 * 1024 * 1024:
        return Json({"error": "нужен JPEG до 20 МБ в теле запроса"}, status_code=400)
    if not (data[:3] == b"\xff\xd8\xff" or data[:8] == b"\x89PNG\r\n\x1a\n"):
        return Json({"error": "поддерживаются JPEG и PNG"}, status_code=400)
    q = request.query_params

    def num(name):
        try:
            return float(q.get(name)) if q.get(name) not in (None, "") else None
        except ValueError:
            return None

    taken = num("taken_at")
    taken_at = datetime.fromtimestamp(taken / 1000, tz=timezone.utc) if taken else None

    def work():
        with db.connect() as conn:
            if not diary.enabled(conn, "feature.diary"):
                return None
            return diary.save_photo(conn, data, taken_at, num("lat"), num("lon"))

    result = await anyio.to_thread.run_sync(work)
    if result is None:
        return Json({"error": "дневник выключен в настройках"}, status_code=409)
    return Json(result)


async def api_photo(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    pid = request.path_params["id"]
    async with await aconnect() as conn:
        row = await (await conn.execute("SELECT path, styled_path FROM photos WHERE id = %s", (pid,))).fetchone()
        if row and request.method == "DELETE":
            await conn.execute("DELETE FROM photos WHERE id = %s", (pid,))
            for path in (row["path"], row["styled_path"]):
                if path:
                    Path(path).unlink(missing_ok=True)
            return Json({"deleted": 1})
    if not row:
        return Json({"error": "not found"}, status_code=404)
    path = row["styled_path"] if request.query_params.get("variant") != "original" and row["styled_path"] else row["path"]
    if not Path(path).exists():
        return Json({"error": "file missing"}, status_code=404)
    data = await anyio.to_thread.run_sync(crypto.read_file, path)  # фото на диске зашифрованы
    media = "image/png" if data[:4] == b"\x89PNG" else "image/jpeg"
    return Response(data, media_type=media, headers={"Cache-Control": "private, max-age=86400"})

# ---------------------------------------------------------------------------
# Люди: голоса в разговорах, «Кто это?», «Созвездие»
# ---------------------------------------------------------------------------
def _sync(fn, *args):
    """Синхронная работа с базой (модуль people/voices) в отдельном потоке."""
    def work():
        with db.connect() as conn:
            return fn(conn, *args)
    return anyio.to_thread.run_sync(work)


async def _body(request: Request) -> dict | None:
    try:
        body = await request.json()
    except ValueError:
        return None
    return body if isinstance(body, dict) else None


async def api_people(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    return Json(await _sync(people.list_people))


async def api_people_settings(request: Request):
    """Приватность: {"enabled": узнавать голоса, "others": запоминать голоса собеседников}."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request)
    if body is None:
        return Json({"error": "bad json"}, status_code=400)
    enabled = bool(body["enabled"]) if "enabled" in body else None
    others = bool(body["others"]) if "others" in body else None
    return Json(await _sync(people.set_settings, enabled, others))


async def api_people_forget_all(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    return Json({"forgotten": await _sync(voices.forget_all)})


async def api_person(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    pid = request.path_params["id"]
    if request.method == "DELETE":
        await _sync(voices.forget_person, pid)
        return Json({"deleted": pid})
    if request.method == "POST":
        body = await _body(request)
        if body is None:
            return Json({"error": "bad json"}, status_code=400)
        p = await _sync(people.update_person, pid, body)
    else:
        p = await _sync(people.person_detail, pid)
    if p is None:
        return Json({"error": "not found"}, status_code=404)
    return Json(p)


async def api_person_merge(request: Request):
    """Это один и тот же человек: {"into": id} — голос id из пути переходит к нему."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request)
    try:
        target = int((body or {}).get("into"))
    except (TypeError, ValueError):
        return Json({"error": "into: person id"}, status_code=400)
    source = request.path_params["id"]

    def work(conn):
        found = conn.execute("SELECT count(*) AS n FROM people WHERE id = ANY(%s)", ([source, target],)).fetchone()
        if found["n"] != 2 or source == target:
            return None
        voices.merge_people(conn, source, target)
        return people.person_detail(conn, target)

    p = await _sync(work)
    if p is None:
        return Json({"error": "not found"}, status_code=404)
    return Json(p)


async def api_person_sample(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    path = voices.sample_path(request.path_params["id"])
    if not path.exists():
        return Json({"error": "no voice sample"}, status_code=404)
    data = await anyio.to_thread.run_sync(crypto.read_file, path)  # образец голоса на диске зашифрован
    return Response(data, media_type="audio/wav", headers={"Cache-Control": "private, max-age=300"})


async def api_suggestion(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request)
    if body is None or "accept" not in body:
        return Json({"error": "accept: true | false"}, status_code=400)
    res = await _sync(people.answer_suggestion, request.path_params["id"], bool(body["accept"]))
    if res is None:
        return Json({"error": "not found"}, status_code=404)
    return Json(res)


async def api_segment_person(request: Request):
    """«Кто это?» для одной фразы: {"person_id": id} | {"name": "Анна"} | {"person_id": null} — не знаю."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request)
    if body is None:
        return Json({"error": "bad json"}, status_code=400)
    pid = body.get("person_id")
    name = str(body.get("name") or "").strip() or None
    try:
        pid = int(pid) if pid is not None else None
    except (TypeError, ValueError):
        return Json({"error": "person_id: int"}, status_code=400)
    res = await _sync(people.assign_segment, request.path_params["id"], pid, name)
    if res is None:
        return Json({"error": "not found"}, status_code=404)
    return Json(res)


async def api_segment_audio(request: Request):
    """Звук одной фразы (WAV), пока исходная запись хранится на сервере (KEEP_AUDIO_DAYS)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        row = await (await conn.execute(
            """SELECT a.path, extract(epoch FROM s.started_at - a.started_at) AS off,
                      extract(epoch FROM s.ended_at - s.started_at) AS dur
               FROM segments s JOIN audio_chunks a ON a.id = s.chunk_id WHERE s.id = %s""",
            (request.path_params["id"],))).fetchone()
    if not row or not row["path"] or not Path(row["path"]).exists():
        return Json({"error": "audio is no longer stored"}, status_code=404)
    start = max(0, int((float(row["off"]) - 0.2) * 1000)) * BYTES_PER_MS
    length = int((min(float(row["dur"]), 20.0) + 0.4) * 1000) * BYTES_PER_MS

    def read():
        with open(row["path"], "rb") as f:
            f.seek(start)
            return f.read(length)

    pcm = await anyio.to_thread.run_sync(read)
    if not pcm:
        return Json({"error": "empty"}, status_code=404)
    return Response(pcm_to_wav(pcm), media_type="audio/wav", headers={"Cache-Control": "private, max-age=300"})


async def api_tts(request: Request):
    """Нейроголос: {"text", "voice": f1|m1, "lang", "speed"} -> audio/wav. 503 — модель ещё грузится
    (телефон тогда говорит системным голосом)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    body = await _body(request)
    if body is None or not str(body.get("text") or "").strip():
        return Json({"error": "text is required"}, status_code=400)
    try:
        speed = float(body.get("speed") or 1.0)
    except (TypeError, ValueError):
        speed = 1.0
    try:
        data = await anyio.to_thread.run_sync(
            tts.synthesize, str(body["text"]), str(body.get("voice") or tts.DEFAULT_VOICE),
            i18n.normalize(str(body.get("lang") or "ru")), speed)
    except tts.Unavailable:
        return Json({"error": "voice is loading"}, status_code=503)
    except ValueError:
        return Json({"error": "nothing to say"}, status_code=400)
    except Exception:  # noqa: BLE001
        log.exception("tts failed")
        return Json({"error": "tts failed"}, status_code=500)
    return Response(data, media_type="audio/wav", headers={"Cache-Control": "private, max-age=86400"})


def previews(rows: list[dict], limit: int = 240) -> list[dict]:
    """Первые фразы разговора (уже расшифрованные) -> короткое превью строкой."""
    for r in rows:
        p = r.get("preview")
        if isinstance(p, list):
            r["preview"] = " ".join(x for x in p if x)[:limit] or None
    return rows


EVENT_FRAGMENTS_SQL = """
SELECT c.id, c.event_id, c.started_at, c.last_activity AS ended_at, c.status, c.title, c.summary, c.emoji,
       greatest(0, extract(epoch FROM c.last_activity - c.started_at))::int AS duration_sec,
       (SELECT count(*) FROM tasks t WHERE t.conversation_id = c.id AND t.status <> 'dismissed') AS tasks_count,
       c.topics, c.important_at IS NOT NULL AS important, """ + people.participants_sql("c") + """ AS people,
       ARRAY(SELECT s.text FROM segments s WHERE s.conversation_id = c.id AND NOT s.junk
             ORDER BY s.started_at LIMIT 6) AS preview
FROM conversations c
"""


def _events_day(conn, day: str) -> dict:
    events = conn.execute(
        """SELECT e.id, e.day, e.started_at, e.ended_at, e.title, e.emoji, e.kind, e.summary, e.topics, e.photo_id,
                  e.status, """ + people.event_people_sql("e") + """ AS people,
                  EXISTS (SELECT 1 FROM conversations c WHERE c.event_id = e.id AND c.important_at IS NOT NULL
                          AND c.trashed_at IS NULL) AS important
           FROM day_events e WHERE e.day = %s ORDER BY e.started_at DESC""", (day,)).fetchall()
    frags = conn.execute(
        EVENT_FRAGMENTS_SQL + """ WHERE c.trashed_at IS NULL AND (c.started_at AT TIME ZONE %s)::date = %s
                                ORDER BY c.started_at DESC""", (config.TZ, day)).fetchall()
    by_event: dict = {}
    loose = []
    for f in previews(frags):
        (by_event.setdefault(f["event_id"], []) if f["event_id"] else loose).append(f)
    for e in events:
        e["fragments"] = by_event.get(e["id"], [])
    if events or loose:
        start, end = diary.day_bounds(date.fromisoformat(day))
        pts = geo.points(conn, start, end)
        if pts:
            geo.locate(events, pts)
            geo.locate(loose, pts)
    return {"day": day, "events": events, "loose": loose}


async def api_events(request: Request):
    """События дня (фрагменты, собранные в ситуации) и ещё не разобранные фрагменты."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    day = request.query_params.get("day") or date.today().isoformat()
    try:
        date.fromisoformat(day)
    except ValueError:
        return Json({"error": "day: YYYY-MM-DD"}, status_code=400)
    return Json(await _sync(_events_day, day))


async def api_map(request: Request):
    """Карта дня: маршрут, места, события и разговоры на своих местах."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    try:
        day = date.fromisoformat(request.query_params.get("day") or date.today().isoformat())
    except ValueError:
        return Json({"error": "day: YYYY-MM-DD"}, status_code=400)
    return Json(await _sync(geo.day_map, day))


async def api_tile(request: Request):
    """Подложка карты: плитка CARTO через свой сервер (с кешем)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    p = request.path_params
    try:
        data = await anyio.to_thread.run_sync(geo.tile, p["style"], int(p["z"]), int(p["x"]), int(p["y"]))
    except ValueError:
        return Json({"error": "bad tile"}, status_code=400)
    except geo.TileError:
        return Json({"error": "tile unavailable"}, status_code=502)
    return Response(data, media_type="image/png", headers={"Cache-Control": "private, max-age=2592000"})


async def api_trash(request: Request):
    """Корзина: что ассистент счёл мусором (7 дней), и возврат фрагмента обратно."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    cid = request.path_params.get("id")

    def work(conn):
        if cid is not None:
            res = conn.execute("UPDATE conversations SET trashed_at = NULL, junk_reason = NULL WHERE id = %s",
                               (cid,))
            return {"restored": res.rowcount}
        return {"items": previews(conn.execute(
            """SELECT c.id, c.started_at, c.last_activity AS ended_at, c.title, c.emoji, c.junk_reason, c.trashed_at,
                      ARRAY(SELECT s.text FROM segments s WHERE s.conversation_id = c.id
                            ORDER BY s.started_at LIMIT 4) AS preview
               FROM conversations c WHERE c.trashed_at IS NOT NULL ORDER BY c.started_at DESC LIMIT 200""").fetchall(),
            200)}

    return Json(await _sync(work))


async def api_graph(request: Request):
    """«Созвездие»: люди, темы и разговоры за N дней (по умолчанию 90)."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    days = _clamp(request.query_params.get("days"), 90, 1, 730)
    return Json(await _sync(people.graph, days))


# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app):
    if not config.API_TOKEN:
        log.error("API_TOKEN is empty — every request will be rejected. Set it in .env")
    await anyio.to_thread.run_sync(db.wait_and_migrate)
    Path(config.AUDIO_DIR).mkdir(parents=True, exist_ok=True)
    await reconcile_files(recover=True)
    async with await aconnect() as conn:
        await conn.execute(
            "UPDATE agent_runs SET status = 'failed', answer = coalesce(answer, 'Прервано перезапуском сервера') "
            "WHERE status = 'running'")
    if config.env("TTS_ENABLED", "1") == "1":
        tts.warm_up()  # нейроголос качается в фоне, пока не готов — телефон говорит системным
    background = [asyncio.create_task(idle_finalizer()), asyncio.create_task(periodic_reconcile())]
    log.info("gateway started")
    try:
        yield
    finally:
        for t in background:
            t.cancel()
        finished = [c for c in (w.finalize() for w in writers.values()) if c]
        if finished:
            try:
                async with await aconnect() as conn:
                    await register_chunks(conn, finished)
            except Exception:  # noqa: BLE001
                log.exception("could not register chunks on shutdown (will be recovered on start)")


app = Starlette(
    routes=[
        Route("/health", health),
        Route("/health/full", health_full),
        Route("/api/status", api_status),
        Route("/api/pair", api_pair, methods=["POST"]),
        Route("/api/pair/auto", api_pair_auto, methods=["POST"]),
        Route("/api/pair/request", api_pair_request, methods=["POST"]),
        Route("/api/pair/request/{id}", api_pair_request),
        Route("/api/pair/requests", api_pair_requests),
        Route("/api/pair/requests/{id}", api_pair_requests, methods=["POST"]),
        Route("/api/conversations", api_conversations),
        Route("/api/days", api_days),
        Route("/api/search", api_search),
        Route("/api/conversations/{id:int}", api_conversation, methods=["GET", "DELETE"]),
        Route("/api/conversations/{id:int}/reprocess", api_conversation_reprocess, methods=["POST"]),
        Route("/api/conversations/{id:int}/important", api_conversation_important, methods=["POST"]),
        Route("/api/important", api_important),
        Route("/api/tasks/{id:int}/nudge", api_task_nudge, methods=["POST"]),
        Route("/api/conversations/{id:int}/followup", api_conversation_followup, methods=["POST"]),
        Route("/api/agent/routines", api_routines),
        Route("/api/calendar", api_calendar, methods=["GET", "POST", "DELETE"]),
        Route("/api/calendar/sync", api_calendar_sync, methods=["POST"]),
        Route("/api/calendar/commands", api_calendar_commands),
        Route("/api/calendar/commands/{id:int}", api_calendar_commands, methods=["POST"]),
        Route("/api/agent/rules/{id:int}", api_rule, methods=["DELETE"]),
        Route("/api/agent/schedules/{id:int}", api_schedule, methods=["POST", "DELETE"]),
        Route("/api/agent/results", api_routine_results),
        Route("/api/profile", api_profile, methods=["GET", "POST"]),
        Route("/api/profile/voice", api_profile_voice, methods=["POST"]),
        Route("/api/tasks", api_tasks),
        Route("/api/tasks/{id:int}", api_task_update, methods=["POST", "PATCH"]),
        Route("/api/memories", api_memories),
        Route("/api/memories/{id:int}", api_memory, methods=["POST", "DELETE"]),
        Route("/api/questions", api_questions),
        Route("/api/models", api_models, methods=["GET", "POST"]),
        Route("/api/chat", api_chat, methods=["GET", "POST"]),
        Route("/api/chat/clear", api_chat_clear, methods=["POST"]),
        Route("/api/runs/{run_id:int}/cancel", api_run_cancel, methods=["POST"]),
        Route("/api/share", api_share),
        Route("/mcp", mcp_endpoint, methods=["GET", "POST", "DELETE"]),
        Route("/api/mcp", api_mcp, methods=["GET", "POST", "DELETE"]),
        Route("/api/extensions", api_extensions),
        Route("/api/extensions/install", api_extension_install, methods=["POST"]),
        Route("/api/extensions/draft", api_extension_draft, methods=["POST"]),
        Route("/api/connectors", api_connectors),
        Route("/api/connectors/finish", api_connector_finish, methods=["POST"]),
        Route("/api/connectors/{id}/start", api_connector_start, methods=["POST"]),
        Route("/api/connectors/{id}", api_connector, methods=["POST", "DELETE"]),
        Route("/oauth/callback", oauth_callback),
        Route("/api/extensions/{id}", api_extension, methods=["POST", "DELETE"]),
        Route("/api/features", api_features, methods=["GET", "POST"]),
        Route("/api/diary", api_diary),
        Route("/api/diary/{day}/generate", api_diary_generate, methods=["POST"]),
        Route("/api/photos", api_photos_upload, methods=["POST"]),
        Route("/api/photos/{id:int}", api_photo, methods=["GET", "DELETE"]),
        Route("/api/people", api_people),
        Route("/api/people/settings", api_people_settings, methods=["POST"]),
        Route("/api/people/forget", api_people_forget_all, methods=["POST"]),
        Route("/api/people/suggestions/{id:int}", api_suggestion, methods=["POST"]),
        Route("/api/people/{id:int}", api_person, methods=["GET", "POST", "DELETE"]),
        Route("/api/people/{id:int}/merge", api_person_merge, methods=["POST"]),
        Route("/api/people/{id:int}/sample", api_person_sample),
        Route("/api/segments/{id:int}/person", api_segment_person, methods=["POST"]),
        Route("/api/segments/{id:int}/audio", api_segment_audio),
        Route("/api/graph", api_graph),
        Route("/api/events", api_events),
        Route("/api/map", api_map),
        Route("/api/tiles/{style}/{z:int}/{x:int}/{y:int}.png", api_tile),
        Route("/api/trash", api_trash),
        Route("/api/conversations/{id:int}/restore", api_trash, methods=["POST"]),
        Route("/api/tts", api_tts, methods=["POST"]),
        WebSocketRoute("/ws/audio", ws_audio),
        WebSocketRoute("/ws/ask", ws_ask),
    ],
    lifespan=lifespan,
)
