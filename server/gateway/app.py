"""NeuroSoft Gateway — точка входа для приложения.

* WebSocket /ws/audio — звук и события кнопки с телефона (с подтверждением каждого пакета).
* REST /api/... — разговоры, задачи и память для экранов приложения.
"""
from __future__ import annotations

import asyncio
import hmac
import json
import logging
import re
import time
import urllib.request
from contextlib import asynccontextmanager
from datetime import date, datetime, timezone
from pathlib import Path

import anyio
import psycopg
from psycopg.rows import dict_row
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import FileResponse, JSONResponse
from starlette.routing import Route, WebSocketRoute
from starlette.websockets import WebSocket, WebSocketDisconnect

from neuro import agent_core, assistant, config, db, diary, extensions, i18n, llm
from neuro.models import CATALOG, TASK_KEYS, TASKS, localized_model, localized_task
from neuro.audio import (
    BatchError,
    ChunkWriter,
    FinishedChunk,
    FrameDecoder,
    chunk_from_path,
    parse_batch,
    recover_orphans,
)

log = logging.getLogger("neuro.gateway")

DEVICE_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
writers: dict[str, ChunkWriter] = {}
writer_locks: dict[str, asyncio.Lock] = {}


# ---------------------------------------------------------------------------
# Утилиты
# ---------------------------------------------------------------------------
async def aconnect() -> psycopg.AsyncConnection:
    return await psycopg.AsyncConnection.connect(config.DATABASE_URL, autocommit=True, row_factory=dict_row)


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
INSERT INTO audio_chunks (device_id, started_at, duration_ms, path)
VALUES (%s, to_timestamp(%s / 1000.0), %s, %s)
ON CONFLICT (path) WHERE path IS NOT NULL DO NOTHING
"""


async def register_chunks(conn, chunks: list[FinishedChunk]):
    for c in chunks:
        await conn.execute(INSERT_CHUNK, (c.device_id, c.start_ms, c.duration_ms, c.path))
        log.info("chunk ready device=%s start=%s dur=%sms", c.device_id, c.start_ms, c.duration_ms)


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

    lock = writer_locks.setdefault(device, asyncio.Lock())
    async with lock:
        w = writers.get(device)
        if w is None:
            w = writers[device] = ChunkWriter(config.AUDIO_DIR, device, config.CHUNK_MAX_SEC, config.CHUNK_GAP_SEC)
        finished = await anyio.to_thread.run_sync(w.write, batch.ts_ms, pcm)

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
                await conn.execute(
                    """INSERT INTO locations (device_id, client_id, ts, lat, lon, accuracy)
                       VALUES (%s, %s, to_timestamp(%s / 1000.0), %s, %s, %s)
                       ON CONFLICT (device_id, client_id) DO NOTHING""",
                    (device, cid, ts, float(lat), float(lon), obj.get("acc")),
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
    return Json({"ok": True, "service": "neurosoft-gateway"})


async def api_status(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        chunks = await (await conn.execute(
            "SELECT status, count(*) AS n FROM audio_chunks GROUP BY status"
        )).fetchall()
        devices = await (await conn.execute(
            "SELECT device_id, last_seen, last_audio, info FROM devices ORDER BY last_seen DESC NULLS LAST"
        )).fetchall()
        counts = await (await conn.execute(
            """SELECT (SELECT count(*) FROM conversations) AS conversations,
                      (SELECT count(*) FROM tasks WHERE status = 'open') AS open_tasks,
                      (SELECT count(*) FROM memories) AS memories"""
        )).fetchone()
    return Json({
        "chunks": {r["status"]: r["n"] for r in chunks},
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
    day = request.query_params.get("day") or None  # YYYY-MM-DD в часовом поясе владельца
    if day:
        try:
            date.fromisoformat(day)
        except ValueError:
            return Json({"error": "day: YYYY-MM-DD"}, status_code=400)
    async with await aconnect() as conn:
        rows = await (await conn.execute(
            """SELECT c.id, c.device_id, c.started_at, c.last_activity AS ended_at, c.status, c.title, c.summary,
                      c.emoji, greatest(0, extract(epoch FROM c.last_activity - c.started_at))::int AS duration_sec,
                      (SELECT count(*) FROM tasks t WHERE t.conversation_id = c.id) AS tasks_count,
                      (SELECT left(string_agg(x.text, ' '), 240) FROM
                          (SELECT s.text FROM segments s WHERE s.conversation_id = c.id
                           ORDER BY s.started_at LIMIT 6) x) AS preview
               FROM conversations c
               WHERE (%s::date IS NULL OR (c.started_at AT TIME ZONE %s)::date = %s::date)
               ORDER BY c.started_at DESC
               LIMIT %s OFFSET %s""",
            (day, config.TZ, day, limit, offset),
        )).fetchall()
    return Json({"items": rows})


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
               WHERE c.started_at > now() - interval '60 days'
               GROUP BY 1 ORDER BY 1 DESC""", (config.TZ,))).fetchall()
    return Json({"items": rows})


async def api_search(request: Request):
    """Поиск по разговорам, фразам, задачам и памяти."""
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    q = (request.query_params.get("q") or "").strip()
    if len(q) < 2:
        return Json({"conversations": [], "segments": [], "tasks": [], "memories": []})
    like = f"%{q}%"
    async with await aconnect() as conn:
        conversations = await (await conn.execute(
            """SELECT id, started_at, title, summary, emoji FROM conversations
               WHERE title ILIKE %s OR summary ILIKE %s ORDER BY started_at DESC LIMIT 20""",
            (like, like))).fetchall()
        segments = await (await conn.execute(
            """SELECT s.conversation_id, s.started_at, s.text, c.title, c.emoji FROM segments s
               LEFT JOIN conversations c ON c.id = s.conversation_id
               WHERE s.text ILIKE %s ORDER BY s.started_at DESC LIMIT 30""", (like,))).fetchall()
        tasks = await (await conn.execute(
            "SELECT id, text, status, due FROM tasks WHERE text ILIKE %s ORDER BY created_at DESC LIMIT 20",
            (like,))).fetchall()
        memories = await (await conn.execute(
            "SELECT id, text, created_at FROM memories WHERE text ILIKE %s ORDER BY created_at DESC LIMIT 20",
            (like,))).fetchall()
    return Json({"conversations": conversations, "segments": segments, "tasks": tasks, "memories": memories})


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
                      greatest(0, extract(epoch FROM last_activity - started_at))::int AS duration_sec
               FROM conversations WHERE id = %s""", (cid,)
        )).fetchone()
        if conv is None:
            return Json({"error": "not found"}, status_code=404)
        conv["segments"] = await (await conn.execute(
            """SELECT id, started_at, ended_at, speaker, text FROM segments
               WHERE conversation_id = %s ORDER BY started_at, id""", (cid,)
        )).fetchall()
        conv["tasks"] = await (await conn.execute(
            """SELECT id, text, due, assignee, target, status, source, exported_at, export_ref
               FROM tasks WHERE conversation_id = %s ORDER BY id""", (cid,)
        )).fetchall()
        conv["memories"] = await (await conn.execute(
            "SELECT id, text, source, created_at FROM memories WHERE conversation_id = %s ORDER BY id", (cid,)
        )).fetchall()
    return Json(conv)


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
            """SELECT t.id, t.conversation_id, t.text, t.due, t.assignee, t.target, t.status, t.source,
                      t.exported_at, t.export_ref, t.created_at, c.title AS conversation_title,
                      coalesce((SELECT json_agg(json_build_object(
                                    'ext', e.ext_id, 'ok', e.ok, 'ref', e.ref,
                                    'name', x.manifest->>'name', 'icon', x.manifest->>'icon'))
                                FROM task_exports e LEFT JOIN extensions x ON x.id = e.ext_id
                                WHERE e.task_id = t.id), '[]'::json) AS exports
               FROM tasks t LEFT JOIN conversations c ON c.id = t.conversation_id
               WHERE (%s = 'all' OR t.status = %s)
               ORDER BY t.created_at DESC LIMIT 300""",
            (status, status),
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
    if status not in ("open", "done"):
        return Json({"error": "status must be open or done"}, status_code=400)
    async with await aconnect() as conn:
        res = await conn.execute(
            "UPDATE tasks SET status = %s, updated_at = now() WHERE id = %s", (status, tid)
        )
    if res.rowcount == 0:
        return Json({"error": "not found"}, status_code=404)
    return Json({"id": tid, "status": status})


async def api_memories(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        rows = await (await conn.execute(
            """SELECT m.id, m.conversation_id, m.text, m.source, m.created_at, c.title AS conversation_title
               FROM memories m LEFT JOIN conversations c ON c.id = m.conversation_id
               ORDER BY m.created_at DESC LIMIT 300"""
        )).fetchall()
    return Json({"items": rows})



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
        await websocket.send_json({"type": "listening"})

        loop = asyncio.get_running_loop()
        deadline = loop.time() + config.ASK_MAX_SEC
        endpointer = assistant.Endpointer()
        pcm = bytearray()
        reason = "max"
        while True:
            remaining = deadline - loop.time()
            if remaining <= 0:
                break
            # если речь уже была и кадры перестали идти (кулон замолчал) — вопрос закончен
            wait = min(remaining, 1.5 if endpointer.started else remaining)
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
                if endpointer.feed(chunk):
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
        await websocket.send_json({"type": "heard", "reason": reason, "seconds": round(len(pcm) / 32000, 1)})
        log.info("ask from %s: %.1fs audio, end=%s", device, len(pcm) / 32000, reason)

        reply = await answer_voice(device, client_id, bytes(pcm))
        await websocket.send_text(json.dumps(reply, ensure_ascii=False))
        log.info("ask answered in %sms (%s): %s", reply.get("latency_ms"), reply.get("level"),
                 (reply.get("answer") or "")[:80])
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


VOICE_BUDGET_SEC = config.env_float("VOICE_BUDGET_SEC", 14.0)


def _run_in_thread(fn, *args):
    """Запуск в пуле потоков, который можно не дожидаться (агент продолжит работу после ответа голосом)."""
    return asyncio.get_running_loop().run_in_executor(None, fn, *args)


def _execute_run(run_id: int) -> str:
    with db.connect() as conn:
        return agent_core.execute(conn, run_id)


async def answer_voice(device: str, client_id: str | None, pcm: bytes) -> dict:
    """Вопрос с кнопки: распознать -> уровень -> агент. Долгую работу продолжаем в фоне, ответ — в чат."""
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

    level = agent_core.route(question, "voice")

    def start():
        with db.connect() as conn:
            return agent_core.start_run(conn, question, level, "voice", device)[0]

    run_id = await _run_in_thread(start)
    future = _run_in_thread(_execute_run, run_id)
    if level == "deep":
        answer, pending = i18n.t(lang, "voice.deep"), True
    else:
        try:
            answer, pending = await asyncio.wait_for(asyncio.shield(future), timeout=VOICE_BUDGET_SEC), False
        except asyncio.TimeoutError:
            answer, pending = i18n.t(lang, "voice.later"), True
    latency = int((time.monotonic() - t0) * 1000)

    def record():
        with db.connect() as conn:
            assistant.record_question(conn, device, client_id, question, answer,
                                      llm.resolve_model(conn, agent_core.LEVELS[level]["task"]), latency)

    await _run_in_thread(record)
    return {"type": "answer", "question": question, "answer": answer, "level": level, "run_id": run_id,
            "pending": pending, "latency_ms": latency}


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
        if not text:
            return Json({"error": "empty"}, status_code=400)
        if level != "auto" and level not in agent_core.LEVELS:
            return Json({"error": "level: auto | fast | tools | deep"}, status_code=400)
        if not llm.configured():
            return Json({"error": "на сервере не задан ключ модели (OPENROUTER_API_KEY)"}, status_code=400)
        level = agent_core.route(text, "chat") if level == "auto" else level

        def start():
            with db.connect() as conn:
                return agent_core.start_run(conn, text, level, "chat", None)

        run_id, message_id = await _run_in_thread(start)
        _run_in_thread(_execute_run, run_id)
        return Json({"run_id": run_id, "message_id": message_id, "level": level})

    limit = _clamp(request.query_params.get("limit"), 60, 1, 300)
    async with await aconnect() as conn:
        messages = await (await conn.execute(
            """SELECT * FROM (SELECT id, role, text, channel, run_id, created_at FROM chat_messages
               ORDER BY id DESC LIMIT %s) x ORDER BY id""", (limit,))).fetchall()
        run_ids = sorted({m["run_id"] for m in messages if m["run_id"]})
        runs = {}
        if run_ids:
            for r in await (await conn.execute(
                    "SELECT id, level, status, model FROM agent_runs WHERE id = ANY(%s)", (run_ids,))).fetchall():
                runs[r["id"]] = {**r, "steps": []}
            for st in await (await conn.execute(
                    """SELECT run_id, kind, title, detail, created_at FROM agent_steps
                       WHERE run_id = ANY(%s) ORDER BY id""", (run_ids,))).fetchall():
                runs[st["run_id"]]["steps"].append(st)
    return Json({"messages": messages, "runs": {str(k): v for k, v in runs.items()},
                 "running": any(r["status"] == "running" for r in runs.values())})


async def api_chat_clear(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    async with await aconnect() as conn:
        await conn.execute("DELETE FROM chat_messages")
    return Json({"cleared": True})


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
        installed.append({**pub, "enabled": r["enabled"], "source_url": r["source_url"],
                          "ready": all(st["filled"] for st in pub["settings"] if st["required"])})
    ids = {e["id"] for e in installed}
    available = [{k: v for k, v in extensions.localize(e, lang).items()
                  if not k.startswith("_") and k not in ("manifest", "i18n")}
                 for e in reg if e["id"] not in ids]
    return Json({"installed": installed, "available": available})


async def api_extension_install(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    try:
        body = await request.json()
    except ValueError:
        return Json({"error": "bad json"}, status_code=400)

    def load():
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
            clean = {str(k): str(v) for k, v in body["settings"].items() if v is not None}
            await conn.execute("UPDATE extensions SET settings = settings || %s::jsonb WHERE id = %s",
                               (json.dumps(clean, ensure_ascii=False), ext_id))
    return Json({"ok": True})


# ---------------------------------------------------------------------------
# Дневник жизни: лента дня, фото, настройки функций
# ---------------------------------------------------------------------------
FEATURE_KEYS = {"diary": "feature.diary", "photo_style": "feature.photo_style", "style": "diary.style",
                "photos_per_day": "diary.photos_per_day"}


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
            for name, key in FEATURE_KEYS.items():
                if name not in body:
                    continue
                v = body[name]
                if name in ("diary", "photo_style"):
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
        "photo_style": st["feature.photo_style"] == "1",
        "style": st["diary.style"],
        "photos_per_day": int(st["diary.photos_per_day"] or 3),
        "styles": [{"key": k, "title": v[0]} for k, v in diary.STYLES.items()],
        "language": i18n.normalize(st.get("app.language")),
        "stt_language": st.get("stt.language") or config.STT_LANGUAGE or "app",
        "languages": [{"code": k, "name": v["name"]} for k, v in i18n.LANGS.items()],
    })


async def api_diary(request: Request):
    if not _authorized(request.headers, request.query_params):
        return _unauthorized()
    limit = _clamp(request.query_params.get("limit"), 14, 1, 60)
    async with await aconnect() as conn:
        days = await (await conn.execute(
            """SELECT day, title, summary, mood, quote, highlights, places, stats, cover_photo_id, status,
                      generated_at, error
               FROM diary_days WHERE status <> 'pending' OR title IS NOT NULL
               ORDER BY day DESC LIMIT %s""", (limit,))).fetchall()
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
    return FileResponse(path, headers={"Cache-Control": "private, max-age=86400"})

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
        Route("/api/status", api_status),
        Route("/api/conversations", api_conversations),
        Route("/api/days", api_days),
        Route("/api/search", api_search),
        Route("/api/conversations/{id:int}", api_conversation, methods=["GET", "DELETE"]),
        Route("/api/conversations/{id:int}/reprocess", api_conversation_reprocess, methods=["POST"]),
        Route("/api/tasks", api_tasks),
        Route("/api/tasks/{id:int}", api_task_update, methods=["POST", "PATCH"]),
        Route("/api/memories", api_memories),
        Route("/api/questions", api_questions),
        Route("/api/models", api_models, methods=["GET", "POST"]),
        Route("/api/chat", api_chat, methods=["GET", "POST"]),
        Route("/api/chat/clear", api_chat_clear, methods=["POST"]),
        Route("/api/extensions", api_extensions),
        Route("/api/extensions/install", api_extension_install, methods=["POST"]),
        Route("/api/extensions/{id}", api_extension, methods=["POST", "DELETE"]),
        Route("/api/features", api_features, methods=["GET", "POST"]),
        Route("/api/diary", api_diary),
        Route("/api/diary/{day}/generate", api_diary_generate, methods=["POST"]),
        Route("/api/photos", api_photos_upload, methods=["POST"]),
        Route("/api/photos/{id:int}", api_photo, methods=["GET", "DELETE"]),
        WebSocketRoute("/ws/audio", ws_audio),
        WebSocketRoute("/ws/ask", ws_ask),
    ],
    lifespan=lifespan,
)
