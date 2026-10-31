"""Режимы чата на Claude Code CLI (сервис claude) по токену владельца (`claude setup-token`).

* claude      — ассистент Mnemo, только умнее: та же роль и те же данные (память, разговоры, задачи,
                люди, расширения — через MCP-инструменты gateway, см. mnemo_mcp), плюс свой веб-поиск.
                Кода и файлов не видит: встроенные инструменты — только WebSearch и WebFetch.
* claude_code — разработка: Claude работает в рабочей копии репозитория Mnemo, правит код, коммитит
                и по просьбе пушит в GitHub; после пуша в main CI сам обновляет сервер и собирает APK.

Токен хранится в settings на сервере, в приложение не отдаётся. У каждого режима своя сессия CLI
(--resume), очистка чата начинает обе заново.
"""
from __future__ import annotations

import json
import logging
import re
import secrets
import threading
from pathlib import PurePosixPath

import requests

from . import config, i18n

log = logging.getLogger("neuro.claude")

URL = (config.env("CLAUDE_URL", "http://claude:8700") or "").rstrip("/")
MCP_URL = config.env("MNEMO_MCP_URL", "http://gateway:8000/mcp")
MCP_PREFIX = "mcp__mnemo__"
LEVELS = ("claude", "claude_code")
TOKEN_KEY, MODEL_KEY = "claude.token", "claude.model"
SESSION_KEYS = {"claude": "claude.session", "claude_code": "claude.session.code"}
SESSION_KEY = SESSION_KEYS["claude"]
LABEL = "claude-code"
TOKEN_RE = re.compile(r"^[A-Za-z0-9._~+/=-]{20,500}$")
MODELS = ("", "sonnet", "opus", "haiku")  # пусто — как решит CLI


def _get(conn, key: str) -> str:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (key,)).fetchone()
    return (row["value"] if row else "") or ""


def _set(conn, key: str, value: str | None):
    if value:
        conn.execute("INSERT INTO settings (key, value) VALUES (%s, %s) "
                     "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()", (key, value))
    else:
        conn.execute("DELETE FROM settings WHERE key = %s", (key,))


def configured(conn) -> bool:
    return bool(_get(conn, TOKEN_KEY))


def set_token(conn, token: str | None):
    _set(conn, TOKEN_KEY, token)
    reset_session(conn)  # новый аккаунт — новые сессии


def set_model(conn, model: str):
    _set(conn, MODEL_KEY, model)


def reset_session(conn):
    for key in SESSION_KEYS.values():
        _set(conn, key, None)


def busy(conn) -> bool:
    return conn.execute("SELECT 1 FROM agent_runs WHERE level = ANY(%s) AND status = 'running' LIMIT 1",
                        (list(LEVELS),)).fetchone() is not None


# одноразовые токены MCP: живут, пока идёт запуск; gateway — один процесс, хватает памяти
_run_tokens: dict[str, int] = {}
_tokens_lock = threading.Lock()


def issue_run_token(run_id: int) -> str:
    token = secrets.token_urlsafe(32)
    with _tokens_lock:
        _run_tokens[token] = run_id
    return token


def run_for_token(token: str | None) -> int | None:
    with _tokens_lock:
        return _run_tokens.get(token or "")


def revoke_run_token(token: str):
    with _tokens_lock:
        _run_tokens.pop(token, None)


def service_info(timeout: float = 4) -> dict:
    try:
        r = requests.get(URL + "/info", timeout=timeout)
        r.raise_for_status()
        return {"available": True, **r.json()}
    except Exception as exc:  # noqa: BLE001
        return {"available": False, "error": str(exc)[:200]}


def status(conn) -> dict:
    token = _get(conn, TOKEN_KEY)
    return {"configured": bool(token), "token_hint": ("…" + token[-4:]) if token else None,
            "session": any(_get(conn, k) for k in SESSION_KEYS.values()), "model": _get(conn, MODEL_KEY), "models": list(MODELS),
            "service": service_info()}


def cancel():
    try:
        requests.post(URL + "/cancel", timeout=4)
    except Exception:  # noqa: BLE001
        log.warning("claude cancel failed")


def assistant_prompt(lang: str) -> str:
    from . import agent_core  # noqa: PLC0415 — agent_core сам импортирует этот модуль

    return agent_core.system_prompt("tools", "chat", lang) + (
        " Данные владельца (память, разговоры с кулона, задачи, люди, расширения) — в инструментах mnemo "
        "(mcp__mnemo__*): ищи там, прежде чем отвечать о его жизни и делах. Для свежих фактов из интернета — "
        "WebSearch. Отвечай простым текстом без заголовков и таблиц, списки — через «•»."
    )


def code_prompt(lang: str) -> str:
    return (
        "Ты работаешь как агент в чате приложения Mnemo на телефоне владельца. Текущая папка — рабочая копия "
        "репозитория Mnemo (сервер Python в server/, приложение Android в android/). " + i18n.reply_in(lang) + " "
        "Ответ показывается простым текстом: без заголовков, таблиц и длинных блоков кода, списки — через «•». "
        "Коротко скажи, что сделал и что дальше. Если просят изменить приложение или сервер: перед правкой "
        "git pull, правь аккуратно в стиле проекта, коммить с понятным русским сообщением. Пушь в main, только "
        "когда владелец явно просит выкатить («залей», «выкати», «отправь»): после пуша GitHub Actions сам "
        "прогонит тесты, обновит сервер и соберёт новый APK (Releases). Никогда не пиши в репозиторий ключи и "
        "токены: репозиторий публичный."
    )


# ---------------------------------------------------------------- события CLI -> шаги чата
def _short(text: str, n: int = 90) -> str:
    line = " ".join(str(text or "").split())
    return line[:n] + "…" if len(line) > n else line


def _file(path) -> str:
    return PurePosixPath(str(path or "")).name or str(path or "")


def tool_title(lang: str, name: str, args: dict) -> str:
    if name.startswith(MCP_PREFIX):  # инструменты Mnemo — теми же словами, что у агента Mnemo
        from . import agent_core  # noqa: PLC0415

        return agent_core.tool_title(lang, name[len(MCP_PREFIX):], args)
    if name == "Bash":
        return i18n.t(lang, "claude.bash", text=_short(args.get("description") or args.get("command"), 80))
    if name in ("Read", "NotebookRead"):
        return i18n.t(lang, "claude.read", name=_file(args.get("file_path") or args.get("notebook_path")))
    if name in ("Edit", "MultiEdit", "Write", "NotebookEdit"):
        return i18n.t(lang, "claude.edit", name=_file(args.get("file_path") or args.get("notebook_path")))
    if name in ("Grep", "Glob"):
        return i18n.t(lang, "claude.search", query=_short(args.get("pattern"), 60))
    if name == "WebSearch":
        return i18n.t(lang, "tool.web_search", query=_short(args.get("query"), 80))
    if name == "WebFetch":
        return i18n.t(lang, "claude.web", text=_short(args.get("url"), 80))
    if name in ("Task", "Agent"):
        return i18n.t(lang, "claude.task", text=_short(args.get("description") or args.get("prompt"), 80))
    if name == "TodoWrite":
        todos = args.get("todos") or []
        active = next((t.get("activeForm") or t.get("content") for t in todos if t.get("status") == "in_progress"),
                      None)
        return "🗒 " + _short(active or i18n.t(lang, "claude.plan"), 90)
    return f"⚙️ {name}"


def _result_text(content) -> str:
    if isinstance(content, list):
        return "\n".join(c.get("text", "") for c in content if isinstance(c, dict) and c.get("type") == "text")
    return str(content or "")


class Collector:
    """Превращает поток событий stream-json в шаги agent_steps и итоговый ответ."""

    def __init__(self, lang: str, step):
        self.lang, self.step = lang, step
        self.session = self.model = self.answer = self.error = None
        self.last_text = ""
        self.cost = 0.0

    def feed(self, e: dict):
        kind = e.get("type")
        if kind == "system" and e.get("subtype") == "init":
            self.session, self.model = e.get("session_id") or self.session, e.get("model") or self.model
        elif kind == "assistant":
            for c in (e.get("message") or {}).get("content") or []:
                if c.get("type") == "tool_use":
                    args = c.get("input") or {}
                    self.step("tool", tool_title(self.lang, c.get("name", ""), args),
                              json.dumps(args, ensure_ascii=False)[:4000])
                elif c.get("type") == "text" and (c.get("text") or "").strip():
                    self.last_text = c["text"].strip()
        elif kind == "user":
            for c in (e.get("message") or {}).get("content") or []:
                if isinstance(c, dict) and c.get("type") == "tool_result":
                    text = _result_text(c.get("content"))
                    self.step("result", ("⚠️ " if c.get("is_error") else "↳ ") + _short(text, 110), text[:4000])
        elif kind == "result":
            self.session = e.get("session_id") or self.session
            self.cost += float(e.get("total_cost_usd") or 0)
            if e.get("is_error") or e.get("subtype") != "success":
                self.error = "; ".join(map(str, e.get("errors") or [])) or e.get("result") or e.get("subtype")
            else:
                self.answer = (e.get("result") or "").strip() or self.last_text
        elif kind == "service_note" and e.get("note") == "session_reset":
            self.session = None
            self.step("note", i18n.t(self.lang, "claude.new_session"))
        elif kind == "service_exit" and not self.answer and not self.error:
            self.error = _short(e.get("stderr") or f"exit {e.get('code')}", 300)


def execute(conn, run_id: int, step, finish) -> str:
    """Выполняет запуск в режиме Claude. step/finish — из agent_core (шаги и итог в чат)."""
    run = conn.execute("SELECT * FROM agent_runs WHERE id = %s", (run_id,)).fetchone()
    lang = i18n.lang(conn)
    level = run["level"] if run["level"] in LEVELS else "claude"
    session_key = SESSION_KEYS[level]
    token, model = _get(conn, TOKEN_KEY), _get(conn, MODEL_KEY)
    col = Collector(lang, lambda kind, title, detail=None: step(conn, run_id, kind, title, detail))
    step(conn, run_id, "level", i18n.t(lang, "claude.level", model=i18n.t(lang, "level." + level)
                                       + (f" · {model}" if model else "")))
    if not token:
        return finish(conn, run_id, run["channel"], "failed", i18n.t(lang, "claude.no_token"))
    saved = _get(conn, session_key) or None
    prompt = run["question"]
    if level == "claude" and run.get("context"):
        from . import agent_context  # noqa: PLC0415

        about = agent_context.context_block(conn, run["context"])
        if about:  # чат открыт из события или дня: Claude сразу видит, о чём речь
            prompt = f"Я открыл чат с экрана приложения Mnemo — вот что там:\n{about[:12000]}\n\nМой вопрос: {prompt}"
    body = {"prompt": prompt, "token": token, "session_id": saved, "model": model or None}
    mcp_token = None
    if level == "claude":
        mcp_token = issue_run_token(run_id)
        body.update(mode="assistant", system=assistant_prompt(lang), tools="WebSearch,WebFetch",
                    mcp={"mcpServers": {"mnemo": {"type": "http", "url": MCP_URL,
                                                  "headers": {"Authorization": f"Bearer {mcp_token}"}}}})
    else:
        body.update(mode="code", system=code_prompt(lang))
    try:
        with requests.post(URL + "/run", json=body, stream=True, timeout=(10, 900)) as r:
            if r.status_code == 423:
                return finish(conn, run_id, run["channel"], "failed", i18n.t(lang, "claude.busy"))
            r.raise_for_status()
            for line in r.iter_lines(decode_unicode=True):
                if not line:
                    continue
                try:
                    col.feed(json.loads(line))
                except ValueError:
                    continue
                if col.session and col.session != saved:
                    saved = col.session
                    _set(conn, session_key, saved)
    except requests.RequestException as exc:
        log.warning("claude service: %s", exc)
        col.error = col.error or i18n.t(lang, "claude.unavailable")
    finally:
        if mcp_token:
            revoke_run_token(mcp_token)
    if col.model:
        conn.execute("UPDATE agent_runs SET model = %s WHERE id = %s", (col.model, run_id))
    if col.answer:
        return finish(conn, run_id, run["channel"], "done", col.answer)
    step(conn, run_id, "error", "⚠️ " + _short(col.error or "no answer", 200), col.error)
    text = col.last_text or i18n.t(lang, "claude.failed", error=_short(col.error or "", 160))
    return finish(conn, run_id, run["channel"], "failed", text)
