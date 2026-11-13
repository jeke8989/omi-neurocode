"""Задачи Mnemo в сервисах задач: SmartDesk, GitHub Issues, свой сервис по REST или вебхуку.

Сама задача никуда не уходит. Отправить её можно кнопкой «Отправить в …» в карточке обещания или
просьбой к ассистенту. Ещё в интеграции можно включить «Отправлять новые задачи автоматически»:
тогда уйдут задачи, появившиеся после включения. Дальше состояние следует за Mnemo:
  выполнили или смахнули — закрываем там; вернули в работу — открываем там;
  удалили — удаляем там (если сервис умеет), иначе закрываем.
Ошибки повторяются через 1, 2, 4… минуты (до MAX_ATTEMPTS раз) — из цикла агента (reconcile).

Манифест интеграции — блок "tasks" (подробно — marketplace/README.md):
  "tasks": {"title": "Задачи → Сервис",
            "create": {"http": {...}, "id": "task.id", "link": "task.url", "stage": "task.stage_name"},
            "close": {"http": {...}}, "reopen": {"http": {...}}, "delete": {"http": {...}}}
В шаблонах — поля задачи (FIELDS), {external_id} — id задачи там: из ответа create по пути "id",
а без пути — наш {task_uuid} (так работает SmartDesk: id задаёт отправитель). Вместо "http" можно
"webhook": {"url": "{settings.url}"} — тогда POST {"event": "task.created" | "task.closed" | …, "data": {…}}.
Старые форматы — "on_task" и событие on.task — понимаются как create без закрытия.
"""
from __future__ import annotations

import logging
from zoneinfo import ZoneInfo

import requests

from . import config, extensions, hooks, i18n

log = logging.getLogger("neuro.task_sync")
TZ = ZoneInfo(config.TZ)

ACTIONS = ("create", "close", "reopen", "delete")
MAX_ATTEMPTS = 6
LOCK_NS = 724110  # pg_advisory_lock(LOCK_NS, task_id): одну задачу не отправляют дважды параллельно
FIELDS = ["task_uuid", "external_id", "title", "text", "details", "due", "due_at", "kind", "counterpart", "source",
          "conversation", "done"]


class SyncError(ValueError):
    pass


# ---------------------------------------------------------------- какие интеграции принимают задачи
def spec(manifest: dict) -> dict | None:
    """Блок tasks манифеста или старые on_task / on.task (только создание)."""
    t = manifest.get("tasks")
    if isinstance(t, dict) and isinstance(t.get("create"), dict):
        return {"title": t.get("title"), "only_target": t.get("only_target"), "legacy": None,
                **{a: t.get(a) for a in ACTIONS}}
    if isinstance(manifest.get("on_task"), dict):
        hook = manifest["on_task"]
        return {"title": hook.get("title"), "only_target": hook.get("only_target"), "legacy": "on_task",
                "create": hook, "close": None, "reopen": None, "delete": None}
    on_task = (manifest.get("on") or {}).get("task")
    if isinstance(on_task, dict):
        return {"title": on_task.get("title"), "only_target": None, "legacy": "on.task",
                "create": on_task, "close": None, "reopen": None, "delete": None}
    return None


def describe(row: dict, lang: str = i18n.DEFAULT) -> dict | None:
    """Для приложения: куда можно отправить задачу и что интеграция умеет потом."""
    s = spec(row["manifest"])
    if s is None:
        return None
    shown = extensions.public_manifest(row["manifest"], lang)
    return {"id": row["id"], "name": shown.get("name") or row["id"], "icon": shown.get("icon"),
            "title": shown.get("task_sync") or s["title"], "auto": bool(row.get("auto_tasks_since")),
            "close": bool(s["close"]), "reopen": bool(s["reopen"]), "delete": bool(s["delete"]),
            "enabled": bool(row.get("enabled", True)), "ready": extensions.is_ready(row)}


def sinks(conn, lang: str | None = None) -> list[dict]:
    """Включённые и настроенные интеграции, которые принимают задачи."""
    lang = lang or i18n.lang(conn)
    rows = conn.execute("SELECT id, manifest, settings, enabled, auto_tasks_since FROM extensions "
                        "WHERE enabled ORDER BY installed_at").fetchall()
    return [d for d in (describe(r, lang) for r in rows) if d and d["ready"]]


# ---------------------------------------------------------------- задача -> поля для шаблонов
TASK_SQL = """
SELECT t.id, t.uuid, t.text, t.due, t.due_at, t.kind, t.status, t.source, t.evidence, t.target, t.created_at,
       coalesce(cp.name, t.counterpart) AS counterpart, c.title AS conversation
FROM tasks t LEFT JOIN conversations c ON c.id = t.conversation_id
             LEFT JOIN people cp ON cp.id = t.counterpart_id
WHERE t.id = %s"""


def fields(t: dict, lang: str = i18n.DEFAULT) -> dict:
    text = " ".join(str(t.get("text") or "").split())
    due_at = t.get("due_at")
    due = t.get("due") or (due_at.astimezone(TZ).strftime("%d.%m.%Y %H:%M") if due_at else "")
    who = t.get("counterpart") or ""
    kind = t.get("kind") or "mine"
    lines = [text] if len(text) > 200 else []
    if due:
        lines.append(i18n.t(lang, "sync.due", due=due))
    if who:
        lines.append(i18n.t(lang, "sync.waiting" if kind == "waiting" else "sync.with", name=who))
    if t.get("evidence"):
        lines.append(i18n.t(lang, "sync.evidence", text=t["evidence"]))
    if t.get("conversation"):
        lines.append(i18n.t(lang, "sync.conversation", title=t["conversation"]))
    lines.append(i18n.t(lang, "sync.footer"))
    return {
        "task_uuid": str(t["uuid"]),
        "title": text if len(text) <= 200 else text[:199].rstrip() + "…",
        "text": text,
        "details": "\n".join(lines),
        "due": due,
        "due_at": due_at.isoformat() if due_at else None,  # целиком "{due_at}" -> null, если срока нет
        "kind": kind,
        "counterpart": who,
        "source": _source(lang, t.get("source") or "conversation"),
        "conversation": t.get("conversation") or "",
        "done": t.get("status") == "done",
    }


def _source(lang: str, source: str) -> str:
    label = i18n.t(lang, "sync.source." + source)
    return source if label.startswith("sync.") else label


def pick(data, path: str | None):
    """Значение из ответа сервиса по пути «task.url» / «items.0.id»."""
    if not path:
        return None
    for part in str(path).split("."):
        if isinstance(data, dict):
            data = data.get(part)
        elif isinstance(data, list) and part.isdigit() and int(part) < len(data):
            data = data[int(part)]
        else:
            return None
    return None if data is None or isinstance(data, (dict, list)) else str(data)


# ---------------------------------------------------------------- запросы к сервису
def _call(row: dict, s: dict, action: str, args: dict) -> dict | str:
    """Действие над задачей в сервисе. Возвращает ответ (JSON или текст); ошибка — SyncError."""
    m, act = row["manifest"], s[action]
    if s["legacy"] == "on_task":
        ok, ref = extensions.call_hook(row, args)
        if not ok:
            raise SyncError(ref)
        return {"url": ref} if ref else {}
    if s["legacy"] == "on.task":
        ok, info = hooks.send(row, "task", {"id": args["task_uuid"], **args})
        if not ok:
            raise SyncError(info)
        return {}
    settings = {**(row.get("settings") or {}), "app.locale": i18n.DEFAULT}
    try:
        if act.get("webhook"):
            url = extensions.resolve_url(act["webhook"]["url"], {}, settings, m)
            event = {"create": "task.created", "close": "task.closed", "reopen": "task.reopened",
                     "delete": "task.deleted"}[action]
            r = requests.post(url, json={"event": event, "data": args}, timeout=15,
                              headers={"X-Mnemo-Event": event, "User-Agent": "Mnemo"})
        else:
            h = act["http"]
            url = extensions.resolve_url(h["url"], args, settings, m)
            headers = {k: str(v) for k, v in extensions._render(h.get("headers") or {}, args, settings).items()}
            body = extensions._render(h.get("body"), args, settings) if h.get("body") is not None else None
            r = requests.request((h.get("method") or "POST").upper(), url, headers=headers, json=body, timeout=15)
    except extensions.ExtensionError as exc:
        raise SyncError(f"заблокировано: {exc}") from exc
    except requests.RequestException as exc:
        raise SyncError(f"нет связи: {exc.__class__.__name__}") from exc
    if r.status_code >= 400:
        raise SyncError(_error_text(r))
    try:
        return r.json()
    except ValueError:
        return r.text[:500]


def _error_text(r) -> str:
    try:
        data = r.json()
        detail = data.get("detail") or data.get("error") or data.get("message") if isinstance(data, dict) else None
        if isinstance(detail, list):  # ошибки валидации FastAPI
            detail = "; ".join(str(d.get("msg") if isinstance(d, dict) else d) for d in detail)
    except ValueError:
        detail = None
    return f"{r.status_code}: {str(detail or r.text)[:280]}"


# ---------------------------------------------------------------- отправка
EXPORT_SQL = """
SELECT e.ext_id, e.ok, e.ref, e.error, e.external_id, coalesce(e.remote_state, 'open') AS state, e.stage,
       x.manifest->>'name' AS name, x.manifest->>'icon' AS icon
FROM task_exports e LEFT JOIN extensions x ON x.id = e.ext_id WHERE e.task_id = %s AND e.ext_id = %s"""


def _ext_row(conn, ext_id: str | None) -> dict:
    rows = conn.execute("SELECT id, manifest, settings, enabled, auto_tasks_since FROM extensions WHERE enabled "
                        "ORDER BY installed_at").fetchall()
    ready = [r for r in rows if spec(r["manifest"]) and extensions.is_ready(r)]
    if ext_id:
        row = next((r for r in ready if r["id"] == ext_id), None)
        if row is None:
            raise SyncError(f"интеграция {ext_id} не принимает задачи, выключена или не настроена")
        return row
    if len(ready) != 1:
        raise SyncError("укажите интеграцию: " + (", ".join(r["id"] for r in ready) or "нет подключённых"))
    return ready[0]


def send(conn, task_id: int, ext_id: str | None = None) -> dict:
    """Отправить задачу в интеграцию (кнопкой, ассистентом или автоматически). Повторно — не дублирует."""
    row = _ext_row(conn, ext_id)
    conn.execute("SELECT pg_advisory_lock(%s, %s)", (LOCK_NS, task_id))
    try:
        done = conn.execute("SELECT 1 FROM task_exports WHERE task_id = %s AND ext_id = %s AND ok",
                            (task_id, row["id"])).fetchone()
        if not done:
            _create(conn, row, task_id)
        return conn.execute(EXPORT_SQL, (task_id, row["id"])).fetchone()
    finally:
        conn.execute("SELECT pg_advisory_unlock(%s, %s)", (LOCK_NS, task_id))


def _create(conn, row: dict, task_id: int):
    t = conn.execute(TASK_SQL, (task_id,)).fetchone()
    if t is None:
        raise SyncError("такой задачи нет")
    if t["status"] != "open":
        raise SyncError("отправить можно только открытую задачу")
    s = spec(row["manifest"])
    lang = i18n.lang(conn)
    args = fields(t, lang)
    args["external_id"] = args["task_uuid"]
    try:
        data = _call(row, s, "create", args)
    except SyncError as exc:
        conn.execute(
            """INSERT INTO task_exports (task_id, ext_id, ok, error, attempts, next_at, updated_at)
               VALUES (%s, %s, false, %s, 1, now() + interval '1 minute', now())
               ON CONFLICT (task_id, ext_id) DO UPDATE SET ok = false, error = EXCLUDED.error,
                   attempts = task_exports.attempts + 1,
                   next_at = now() + power(2, task_exports.attempts) * interval '1 minute', updated_at = now()""",
            (task_id, row["id"], str(exc)[:500]))
        log.warning("task %s -> %s failed: %s", task_id, row["id"], str(exc)[:160])
        raise
    create = s["create"] if isinstance(s["create"], dict) else {}
    if create.get("id"):
        external = pick(data, create["id"])
        note = None if external else "сервис не вернул id задачи — закрыть её там не получится"
    else:
        external, note = (None, None) if s["legacy"] else (args["task_uuid"], None)
    link = pick(data, create.get("link") or ("url" if s["legacy"] == "on_task" else None))
    conn.execute(
        """INSERT INTO task_exports (task_id, ext_id, ok, ref, error, external_id, remote_state, stage, attempts,
                                     next_at, updated_at)
           VALUES (%s, %s, true, %s, %s, %s, 'open', %s, 0, NULL, now())
           ON CONFLICT (task_id, ext_id) DO UPDATE SET ok = true, ref = EXCLUDED.ref, error = EXCLUDED.error,
               external_id = EXCLUDED.external_id, remote_state = 'open', stage = EXCLUDED.stage, attempts = 0,
               next_at = NULL, updated_at = now()""",
        (task_id, row["id"], link, note, external, pick(data, create.get("stage"))))
    log.info("task %s -> %s: sent%s", task_id, row["id"], f" ({link})" if link else "")


# ---------------------------------------------------------------- цикл агента
def reconcile(conn) -> int:
    """Автоотправка новых задач, повтор неудачных и закрытие/открытие там вслед за Mnemo. Число действий."""
    done = 0
    rows = conn.execute("SELECT id, manifest, settings, enabled, auto_tasks_since FROM extensions "
                        "WHERE enabled").fetchall()
    by_id = {r["id"]: r for r in rows if spec(r["manifest"]) and extensions.is_ready(r)}
    for row in by_id.values():
        if not row["auto_tasks_since"]:
            continue
        only = spec(row["manifest"])["only_target"]
        new = conn.execute(
            """SELECT t.id FROM tasks t
               WHERE t.status = 'open' AND t.created_at >= %s AND (%s::text IS NULL OR t.target = %s)
                 AND NOT EXISTS (SELECT 1 FROM task_exports e WHERE e.task_id = t.id AND e.ext_id = %s)
               ORDER BY t.id LIMIT 10""", (row["auto_tasks_since"], only, only, row["id"])).fetchall()
        done += _try_send(conn, [r["id"] for r in new], row["id"])
    if by_id:  # отправка не удалась — повторяем, пока задача открыта
        retry = conn.execute(
            """SELECT e.task_id, e.ext_id FROM task_exports e JOIN tasks t ON t.id = e.task_id
               WHERE NOT e.ok AND t.status = 'open' AND e.attempts < %s AND e.ext_id = ANY(%s)
                 AND (e.next_at IS NULL OR e.next_at <= now()) ORDER BY e.task_id LIMIT 10""",
            (MAX_ATTEMPTS, list(by_id))).fetchall()
        for r in retry:
            done += _try_send(conn, [r["task_id"]], r["ext_id"])
    return done + _follow_state(conn, by_id)


def _try_send(conn, task_ids: list[int], ext_id: str) -> int:
    n = 0
    for tid in task_ids:
        try:
            send(conn, tid, ext_id)
            n += 1
        except SyncError:
            pass  # ошибка уже записана в task_exports; повторим позже
    return n


STATE_SQL = """
SELECT e.task_id, e.ext_id, e.external_id, coalesce(e.remote_state, 'open') AS state, e.attempts, t.status
FROM task_exports e LEFT JOIN tasks t ON t.id = e.task_id
WHERE e.ok AND e.ext_id = ANY(%(ext)s) AND e.attempts < %(max)s AND (e.next_at IS NULL OR e.next_at <= now())
  AND (t.id IS NULL
       OR (t.status IN ('done', 'dismissed') AND coalesce(e.remote_state, 'open') = 'open')
       OR (t.status = 'open' AND e.remote_state = 'closed'))
ORDER BY e.task_id LIMIT 20"""


def _follow_state(conn, by_id: dict) -> int:
    if not by_id:
        return 0
    n = 0
    lang = i18n.lang(conn)
    for e in conn.execute(STATE_SQL, {"ext": list(by_id), "max": MAX_ATTEMPTS}).fetchall():
        row = by_id[e["ext_id"]]
        s = spec(row["manifest"])
        deleted = e["status"] is None
        if deleted:
            action = "delete" if s["delete"] else ("close" if s["close"] and e["state"] == "open" else None)
        elif e["status"] == "open":
            action = "reopen" if s["reopen"] else None
        else:
            action = "close" if s["close"] else None
        if action is None or not e["external_id"]:
            if deleted:  # задачи нет, а там сделать нечего — забываем выгрузку
                conn.execute("DELETE FROM task_exports WHERE task_id = %s AND ext_id = %s", (e["task_id"], e["ext_id"]))
            else:  # сервис этого не умеет: запоминаем, чтобы не проверять каждый круг
                conn.execute("UPDATE task_exports SET remote_state = %s, updated_at = now() "
                             "WHERE task_id = %s AND ext_id = %s",
                             ("open" if e["status"] == "open" else "closed", e["task_id"], e["ext_id"]))
            continue
        t = conn.execute(TASK_SQL, (e["task_id"],)).fetchone()
        args = fields(t, lang) if t else {k: None for k in FIELDS}
        args.update(task_uuid=args.get("task_uuid") or e["external_id"], external_id=e["external_id"],
                    done=action == "close")
        try:
            _call(row, s, action, args)
        except SyncError as exc:
            conn.execute(
                """UPDATE task_exports SET error = %s, attempts = attempts + 1,
                       next_at = now() + power(2, attempts) * interval '1 minute', updated_at = now()
                   WHERE task_id = %s AND ext_id = %s""", (str(exc)[:500], e["task_id"], e["ext_id"]))
            log.warning("task %s %s in %s failed: %s", e["task_id"], action, e["ext_id"], str(exc)[:160])
            continue
        if deleted:
            conn.execute("DELETE FROM task_exports WHERE task_id = %s AND ext_id = %s", (e["task_id"], e["ext_id"]))
        else:
            conn.execute(
                """UPDATE task_exports SET remote_state = %s, error = NULL, attempts = 0, next_at = NULL,
                       updated_at = now() WHERE task_id = %s AND ext_id = %s""",
                ("open" if action == "reopen" else "closed", e["task_id"], e["ext_id"]))
        log.info("task %s %s in %s", e["task_id"], action, e["ext_id"])
        n += 1
    return n
