"""События Mnemo во внешние сервисы: новый разговор, задача, «запомни это», итог дня.

Интеграция подписывается на события полем `on` в manifest.json:
    "on": {"conversation": {"title": "…", "webhook": {"url": "{settings.url}"}},
           "task": {"http": {"method": "POST", "url": "https://api.todoist.com/…", "body": {"content": "{text}"}}}}
- webhook — POST {"event", "data", "sent_at"} на адрес; если в настройках есть `secret`, тело подписывается:
  заголовок X-Mnemo-Signature: sha256=<HMAC-SHA256(secret, тело)>. Настройки (токены) в тело не попадают.
- http — тот же декларативный запрос, что у инструментов; подстановки — поля события ({title}, {summary}, …).

События сначала пишутся в очередь (hook_events, содержимое зашифровано), а агент отправляет их с повторами:
нет сети или сервис лежит — событие не потеряется.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
from datetime import datetime, timezone

import requests

from . import crypto, extensions, i18n

log = logging.getLogger("neuro.hooks")

EVENTS = ("conversation", "task", "memory", "day")
MAX_ATTEMPTS = 6
# Поля событий (подстановки для http): одинаковые для всех интеграций
FIELDS = {
    "conversation": ["id", "title", "summary", "emoji", "started_at", "ended_at", "topics", "people", "tasks"],
    "task": ["id", "text", "due", "assignee", "source", "conversation"],
    "memory": ["id", "text", "conversation_id", "created_at"],
    "day": ["day", "title", "summary", "mood"],
}


def subscribers(conn, event: str) -> list[dict]:
    rows = conn.execute("SELECT id, manifest, settings FROM extensions WHERE enabled").fetchall()
    return [r for r in rows if (r["manifest"].get("on") or {}).get(event) and extensions.is_ready(r)]


def emit(conn, event: str, data: dict):
    """Положить событие в очередь для всех подписанных интеграций. Ничего не отправляет само."""
    for r in subscribers(conn, event):
        conn.execute("INSERT INTO hook_events (ext_id, event, payload) VALUES (%s, %s, %s::jsonb)",
                     (r["id"], event, crypto.seal_json(data)))


def flat(data: dict) -> dict:
    """Для подстановок в http-запросах: списки — строкой через запятую."""
    return {k: ", ".join(map(str, v)) if isinstance(v, list) else ("" if v is None else v) for k, v in data.items()}


def send(row: dict, event: str, data: dict) -> tuple[bool, str]:
    """Отправить одно событие. Возвращает (успех, ответ или ошибка)."""
    m = row["manifest"]
    spec = (m.get("on") or {}).get(event)
    if not spec:
        return False, "интеграция больше не подписана на это событие"
    settings = {**(row.get("settings") or {}), "app.locale": i18n.DEFAULT}
    try:
        if spec.get("webhook"):
            url = extensions.resolve_url(spec["webhook"]["url"], {}, settings, m)
            body = json.dumps({"event": event, "data": data, "sent_at": datetime.now(timezone.utc).isoformat()},
                              ensure_ascii=False, default=str).encode()
            headers = {"Content-Type": "application/json", "X-Mnemo-Event": event, "User-Agent": "Mnemo"}
            secret = str(settings.get("secret") or "")
            if secret:
                headers["X-Mnemo-Signature"] = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
            r = requests.post(url, data=body, headers=headers, timeout=15)
        else:
            h = spec["http"]
            args = flat(data)
            url = extensions.resolve_url(h["url"], args, settings, m)
            headers = {k: str(v) for k, v in extensions._render(h.get("headers") or {}, args, settings).items()}
            payload = extensions._render(h.get("body"), args, settings) if h.get("body") is not None else None
            r = requests.request((h.get("method") or "POST").upper(), url, headers=headers, json=payload, timeout=15)
        if r.status_code >= 400:
            return False, f"{r.status_code}: {r.text[:300]}"
        return True, r.text[:300]
    except extensions.ExtensionError as exc:
        return False, f"заблокировано: {exc}"
    except requests.RequestException as exc:
        return False, f"нет связи: {exc.__class__.__name__}"


def deliver(conn, limit: int = 20) -> int:
    """Из цикла агента: отправить накопившиеся события (повторы — через 1, 2, 4… минуты)."""
    rows = conn.execute(
        """SELECT h.id, h.ext_id, h.event, h.payload, h.attempts, e.manifest, e.settings
           FROM hook_events h JOIN extensions e ON e.id = h.ext_id
           WHERE h.status = 'pending' AND e.enabled AND (h.next_at IS NULL OR h.next_at <= now())
           ORDER BY h.id LIMIT %s""", (limit,)).fetchall()
    sent = 0
    for r in rows:
        data = r["payload"] if isinstance(r["payload"], dict) else {}
        ok, info = send(r, r["event"], data)
        if ok:
            conn.execute("UPDATE hook_events SET status = 'done', sent_at = now(), error = NULL WHERE id = %s",
                         (r["id"],))
            sent += 1
            continue
        attempts = r["attempts"] + 1
        conn.execute(
            """UPDATE hook_events SET attempts = %s, error = %s,
                   status = CASE WHEN %s >= %s THEN 'failed' ELSE 'pending' END,
                   next_at = now() + power(2, %s) * interval '1 minute' WHERE id = %s""",
            (attempts, crypto.seal(info[:300]), attempts, MAX_ATTEMPTS, attempts - 1, r["id"]))
        log.warning("event %s -> %s failed (%d): %s", r["event"], r["ext_id"], attempts, info[:120])
    conn.execute("DELETE FROM hook_events WHERE status = 'done' AND sent_at < now() - interval '7 days'")
    return sent


def stats(conn, ext_id: str) -> dict:
    """Для экрана интеграции: сколько событий ушло, сколько ждёт, последняя ошибка."""
    row = conn.execute(
        """SELECT count(*) FILTER (WHERE status = 'done') AS sent, count(*) FILTER (WHERE status = 'pending') AS waiting,
                  count(*) FILTER (WHERE status = 'failed') AS failed, max(sent_at) AS last_sent
           FROM hook_events WHERE ext_id = %s""", (ext_id,)).fetchone()
    err = conn.execute(
        """SELECT error FROM hook_events WHERE ext_id = %s AND error IS NOT NULL ORDER BY id DESC LIMIT 1""",
        (ext_id,)).fetchone()
    return {**row, "error": err["error"] if err else None}
