"""Подключения «одной кнопкой»: Notion, Linear и другие сервисы с официальным MCP-сервером и входом через OAuth.

Как это работает (MCP Authorization, OAuth 2.1):
1. «Подключить» в приложении -> сервер находит у сервиса адрес входа (Protected Resource Metadata ->
   Authorization Server Metadata) и сам регистрирует Mnemo как клиента (Dynamic Client Registration) —
   ничего заводить в кабинете разработчика не нужно.
2. Пользователь входит в сервис в браузере и разрешает доступ (PKCE S256, state).
3. Сервис возвращает код в приложение (codes.neuro.mnemo://oauth) или на сервер (/oauth/callback);
   сервер меняет код на токены и хранит их зашифрованными. Токены обновляются сами.
4. Инструменты сервиса (поиск, страницы, задачи…) становятся инструментами ассистента. Чтобы не тратить
   токены, ассистент видит их, только когда вопрос про этот сервис.
"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import re
import secrets
import time
from urllib.parse import urlencode, urlparse

import requests

from . import crypto

log = logging.getLogger("neuro.connectors")

APP_REDIRECT = "codes.neuro.mnemo://oauth"
PROTOCOL = "2025-06-18"
UA = "Mnemo/1.0"

CATALOG = {
    "notion": {
        "name": "Notion", "icon": "🗒", "mcp": "https://mcp.notion.com/mcp",
        "description": {"ru": "Поиск по вашему Notion, страницы и задачи", "en": "Search your Notion, pages and tasks",
                        "tr": "Notion'da arama, sayfalar ve görevler", "es": "Busca en tu Notion, páginas y tareas"},
        "keywords": ["notion", "ноушн", "ношн", "ношен", "нотион", "ноушен", "ноушин", "ноцион"],
    },
    # CRM с официальным MCP-сервером и входом без регистрации приложения
    "attio": {
        "name": "Attio", "icon": "🤝", "mcp": "https://mcp.attio.com/mcp",
        "description": {"ru": "CRM: контакты, сделки, заметки о встречах", "en": "CRM: contacts, deals, meeting notes",
                        "tr": "CRM: kişiler, anlaşmalar, toplantı notları", "es": "CRM: contactos, tratos, notas de reuniones"},
        "keywords": ["attio", "аттио", "crm", "црм", "срм", "сделк", "клиент"],
    },
    "pipedrive": {
        "name": "Pipedrive", "icon": "💼", "mcp": "https://mcp.pipedrive.ai/mcp",
        "description": {"ru": "CRM: сделки, контакты, дела", "en": "CRM: deals, contacts, activities",
                        "tr": "CRM: anlaşmalar, kişiler, etkinlikler", "es": "CRM: tratos, contactos, actividades"},
        "keywords": ["pipedrive", "пайпдрайв", "crm", "црм", "срм", "сделк", "клиент"],
    },
    "linear": {
        "name": "Linear", "icon": "📐", "mcp": "https://mcp.linear.app/mcp",
        "description": {"ru": "Задачи и проекты в Linear", "en": "Issues and projects in Linear",
                        "tr": "Linear'da görevler ve projeler", "es": "Tareas y proyectos en Linear"},
        "keywords": ["linear", "линеар", "линир"],
    },
}


class ConnectorError(RuntimeError):
    pass


# Что может ассистент в сервисе — выбирает пользователь: «просматривать» (поиск, чтение) и «создавать и изменять».
READ_NAME = re.compile(r"(search|fetch|get|list|read|query|find|view|retrieve|lookup|whoami|self)", re.I)


def tool_access(t: dict) -> str:
    """read | write: по подсказкам сервиса (readOnlyHint), иначе по имени (search, get, list… — чтение)."""
    if t.get("access") in ("read", "write"):
        return t["access"]
    hints = t.get("annotations") or {}
    if hints.get("readOnlyHint") is True:
        return "read"
    if hints.get("readOnlyHint") is False or hints.get("destructiveHint") is True:
        return "write"
    return "read" if READ_NAME.search(t.get("name") or "") else "write"


def allowed(settings: dict | None, access: str) -> bool:
    return (settings or {}).get(access, True) is not False


# ---------------------------------------------------------------- хранение
def _row(conn, cid: str) -> dict | None:
    return conn.execute("SELECT * FROM connections WHERE id = %s", (cid,)).fetchone()


def _save(conn, cid: str, **fields):
    fields = {k: (crypto.seal_json(v) if k in ("client", "tokens") else crypto.seal(v) if k == "account" else v)
              for k, v in fields.items()}
    cols = ", ".join(fields)
    marks = ", ".join("%s::jsonb" if k in ("client", "tokens", "tools", "settings") else "%s" for k in fields)
    upd = ", ".join(f"{k} = EXCLUDED.{k}" for k in fields)
    vals = [json.dumps(v, ensure_ascii=False) if k in ("tools", "settings") else v for k, v in fields.items()]
    conn.execute(f"""INSERT INTO connections (id, {cols}) VALUES (%s, {marks})
                     ON CONFLICT (id) DO UPDATE SET {upd}, updated_at = now()""", (cid, *vals))


def listing(conn, lang: str = "ru") -> list[dict]:
    rows = {r["id"]: r for r in conn.execute("SELECT * FROM connections").fetchall()}
    out = []
    for cid, c in CATALOG.items():
        r = rows.get(cid) or {}
        out.append({
            "id": cid, "name": c["name"], "icon": c["icon"],
            "description": c["description"].get(lang) or c["description"]["ru"],
            # «pending» — вход начат, но не закончен (закрыли браузер): для пользователя это «не подключено»
            "status": r.get("status") if r.get("status") in ("connected", "error") else "off",
            "account": r.get("account"), "error": r.get("error"),
            "tools": [t.get("title") or t["name"] for t in (r.get("tools") or [])],
            # выбор пользователя: что ассистенту можно в этом сервисе (по умолчанию — всё)
            "read": allowed(r.get("settings"), "read"), "write": allowed(r.get("settings"), "write"),
            "read_tools": sum(1 for t in r.get("tools") or [] if tool_access(t) == "read"),
            "write_tools": sum(1 for t in r.get("tools") or [] if tool_access(t) == "write"),
        })
    return out


# ---------------------------------------------------------------- OAuth
def _get_json(url: str) -> dict | None:
    try:
        r = requests.get(url, timeout=12, headers={"User-Agent": UA, "Accept": "application/json"})
        if r.status_code == 200:
            return r.json()
    except (requests.RequestException, ValueError):
        pass
    return None


def discover(mcp_url: str) -> dict:
    """Адреса входа сервиса: authorization/token/registration endpoints."""
    u = urlparse(mcp_url)
    origin = f"{u.scheme}://{u.netloc}"
    meta_url = None
    try:  # MCP-сервер без токена отвечает 401 и сам говорит, где метаданные
        r = requests.post(mcp_url, json={"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
                          headers={"User-Agent": UA, "Accept": "application/json, text/event-stream"}, timeout=12)
        m = re.search(r'resource_metadata="([^"]+)"', r.headers.get("WWW-Authenticate", ""))
        meta_url = m.group(1) if m else None
    except requests.RequestException:
        pass
    prm = None
    for url in [meta_url, f"{origin}/.well-known/oauth-protected-resource{u.path}",
                f"{mcp_url.rstrip('/')}/.well-known/oauth-protected-resource",
                f"{origin}/.well-known/oauth-protected-resource"]:
        if url and (prm := _get_json(url)):
            break
    issuers = (prm or {}).get("authorization_servers") or [origin]
    issuer = issuers[0].rstrip("/")
    iu = urlparse(issuer)
    meta = None
    for url in [f"{iu.scheme}://{iu.netloc}/.well-known/oauth-authorization-server{iu.path}",
                f"{issuer}/.well-known/oauth-authorization-server",
                f"{iu.scheme}://{iu.netloc}/.well-known/openid-configuration{iu.path}"]:
        if (meta := _get_json(url)) and meta.get("authorization_endpoint"):
            break
    if not meta or not meta.get("authorization_endpoint") or not meta.get("token_endpoint"):
        raise ConnectorError("сервис не сообщил адреса входа (OAuth)")
    return {"authorization_endpoint": meta["authorization_endpoint"], "token_endpoint": meta["token_endpoint"],
            "registration_endpoint": meta.get("registration_endpoint"), "scopes": meta.get("scopes_supported") or [],
            "resource": (prm or {}).get("resource") or mcp_url}


def _register(endpoints: dict, redirects: list[str]) -> dict | None:
    if not endpoints.get("registration_endpoint"):
        raise ConnectorError("сервис не разрешает подключение без регистрации приложения")
    try:
        r = requests.post(endpoints["registration_endpoint"], timeout=15, headers={"User-Agent": UA}, json={
            "client_name": "Mnemo", "redirect_uris": redirects, "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"], "token_endpoint_auth_method": "none"})
    except requests.RequestException as exc:
        raise ConnectorError(f"нет связи с сервисом: {exc.__class__.__name__}") from exc
    if r.status_code >= 400:
        log.info("registration with %s refused: %s", redirects, r.text[:200])
        return None
    data = r.json()
    return {"client_id": data["client_id"], "client_secret": data.get("client_secret"), "redirects": redirects}


def start(conn, cid: str, server_url: str | None) -> str:
    """Ссылка на вход в сервис. Сначала пробуем вернуть код прямо в приложение (codes.neuro.mnemo://oauth),
    если сервис так не умеет — на сервер Mnemo (/oauth/callback)."""
    c = CATALOG.get(cid)
    if c is None:
        raise ConnectorError("нет такого подключения")
    row = _row(conn, cid) or {}
    client = row.get("client") if isinstance(row.get("client"), dict) else None
    endpoints = (client or {}).get("endpoints") or discover(c["mcp"])
    if not client or client.get("redirects") is None:
        client = None
        options = [[APP_REDIRECT]]
        if server_url:
            options.append([server_url.rstrip("/") + "/oauth/callback"])
        for redirects in options:
            client = _register(endpoints, redirects)
            if client:
                break
        if not client:
            raise ConnectorError("сервис не принял адрес возврата")
        client["endpoints"] = endpoints
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(24)
    redirect = client["redirects"][0]
    conn.execute("DELETE FROM oauth_states WHERE created_at < now() - interval '1 hour'")
    conn.execute("INSERT INTO oauth_states (state, connector, verifier, redirect_uri) VALUES (%s, %s, %s, %s)",
                 (state, cid, crypto.seal(verifier), redirect))
    _save(conn, cid, client=client, status=row.get("status") if row.get("status") == "connected" else "pending")
    params = {"response_type": "code", "client_id": client["client_id"], "redirect_uri": redirect, "state": state,
              "code_challenge": challenge, "code_challenge_method": "S256", "resource": endpoints["resource"],
              "prompt": "consent"}
    return endpoints["authorization_endpoint"] + ("&" if "?" in endpoints["authorization_endpoint"] else "?") \
        + urlencode(params)


def _token_request(endpoints: dict, client: dict, data: dict) -> dict:
    data = {**data, "client_id": client["client_id"]}
    if client.get("client_secret"):
        data["client_secret"] = client["client_secret"]
    try:
        r = requests.post(endpoints["token_endpoint"], data=data, timeout=20,
                          headers={"User-Agent": UA, "Accept": "application/json"})
    except requests.RequestException as exc:
        raise ConnectorError(f"нет связи с сервисом: {exc.__class__.__name__}") from exc
    if r.status_code >= 400:
        raise ConnectorError(f"вход не удался: {r.text[:200]}")
    t = r.json()
    return {"access_token": t["access_token"], "refresh_token": t.get("refresh_token"),
            "expires_at": time.time() + float(t.get("expires_in") or 3600), "raw_ids": {
                k: t.get(k) for k in ("workspace_name", "workspace_id", "user_id") if t.get(k)}}


def finish(conn, state: str, code: str) -> dict:
    """Код из сервиса -> токены -> список инструментов. Возвращает карточку подключения."""
    st = conn.execute("SELECT connector, verifier, redirect_uri FROM oauth_states WHERE state = %s", (state,)).fetchone()
    if st is None:
        raise ConnectorError("вход устарел — нажмите «Подключить» ещё раз")
    conn.execute("DELETE FROM oauth_states WHERE state = %s", (state,))
    cid = st["connector"]
    row = _row(conn, cid) or {}
    client = row.get("client") or {}
    endpoints = client.get("endpoints") or discover(CATALOG[cid]["mcp"])
    tokens = _token_request(endpoints, client, {
        "grant_type": "authorization_code", "code": code, "redirect_uri": st["redirect_uri"],
        "code_verifier": st["verifier"], "resource": endpoints["resource"]})
    account = tokens.pop("raw_ids", {}).get("workspace_name")
    _save(conn, cid, tokens=tokens, status="connected", account=account, error=None,
          settings={}, connected_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    try:
        refresh_tools(conn, cid)
    except Exception as exc:  # noqa: BLE001 — вход прошёл; инструменты подтянем при первом вопросе
        log.warning("%s: tools not loaded yet: %s", cid, exc)
    return next(x for x in listing(conn) if x["id"] == cid)


def disconnect(conn, cid: str):
    conn.execute("DELETE FROM connections WHERE id = %s", (cid,))


def _access_token(conn, cid: str) -> str:
    """Действующий токен; истекает — обновляем (по одному за раз: обновление меняет refresh-токен)."""
    with conn.transaction():
        conn.execute("SELECT pg_advisory_xact_lock(hashtext(%s))", ("connector:" + cid,))
        row = _row(conn, cid)
        if not row or row.get("status") != "connected" or not row.get("tokens"):
            raise ConnectorError("не подключено")
        tokens, client = row["tokens"], row["client"] or {}
        if tokens.get("expires_at", 0) - 120 > time.time():
            return tokens["access_token"]
        if not tokens.get("refresh_token"):
            raise ConnectorError("вход истёк — подключите заново")
        try:
            fresh = _token_request(client["endpoints"], client, {
                "grant_type": "refresh_token", "refresh_token": tokens["refresh_token"],
                "resource": client["endpoints"]["resource"]})
        except ConnectorError as exc:
            if "invalid_grant" in str(exc):
                _save(conn, cid, status="error", error="вход истёк — подключите заново")
            raise
        fresh.pop("raw_ids", None)
        fresh["refresh_token"] = fresh.get("refresh_token") or tokens["refresh_token"]
        _save(conn, cid, tokens=fresh)
        return fresh["access_token"]


# ---------------------------------------------------------------- MCP-клиент
def _rpc(conn, cid: str, method: str, params: dict | None = None, timeout: float = 60) -> dict:
    url = CATALOG[cid]["mcp"]
    token = _access_token(conn, cid)
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json, text/event-stream",
               "Content-Type": "application/json", "MCP-Protocol-Version": PROTOCOL, "User-Agent": UA}
    session = None

    def post(body: dict) -> requests.Response:
        h = dict(headers)
        if session:
            h["Mcp-Session-Id"] = session
        return requests.post(url, json=body, headers=h, timeout=timeout)

    try:
        init = post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": PROTOCOL, "capabilities": {}, "clientInfo": {"name": "Mnemo", "version": "1.0"}}})
        if init.status_code == 401:
            _save(conn, cid, status="error", error="доступ отозван — подключите заново")
            raise ConnectorError("доступ отозван — подключите заново")
        session = init.headers.get("Mcp-Session-Id")
        post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        r = post({"jsonrpc": "2.0", "id": 2, "method": method, "params": params or {}})
    except requests.RequestException as exc:
        raise ConnectorError(f"сервис недоступен: {exc.__class__.__name__}") from exc
    if r.status_code >= 400:
        raise ConnectorError(f"сервис ответил {r.status_code}")
    msg = _parse(r)
    if msg.get("error"):
        raise ConnectorError(str(msg["error"].get("message") or msg["error"])[:300])
    return msg.get("result") or {}


def _parse(r: requests.Response) -> dict:
    """Ответ MCP: JSON или поток событий (SSE) — берём сообщение с ответом на запрос."""
    if "text/event-stream" in r.headers.get("Content-Type", ""):
        for line in r.text.splitlines():
            if line.startswith("data:"):
                try:
                    msg = json.loads(line[5:].strip())
                except ValueError:
                    continue
                if isinstance(msg, dict) and ("result" in msg or "error" in msg):
                    return msg
        return {}
    try:
        return r.json()
    except ValueError:
        return {}


def refresh_tools(conn, cid: str) -> list[dict]:
    result = _rpc(conn, cid, "tools/list")
    tools = [{"name": t["name"], "title": (t.get("annotations") or {}).get("title") or t.get("title") or t["name"],
              "description": (t.get("description") or "")[:700], "access": tool_access(t),
              "schema": t.get("inputSchema") or {"type": "object", "properties": {}}}
             for t in result.get("tools") or [] if t.get("name")]
    _save(conn, cid, tools=tools)
    return tools


def call_tool(conn, cid: str, name: str, args: dict) -> str:
    result = _rpc(conn, cid, "tools/call", {"name": name, "arguments": args}, timeout=90)
    parts = [c.get("text") or json.dumps(c, ensure_ascii=False) for c in result.get("content") or []
             if isinstance(c, dict)]
    text = "\n".join(p for p in parts if p) or json.dumps(result.get("structuredContent") or {}, ensure_ascii=False)
    return ("Ошибка: " if result.get("isError") else "") + text[:8000]


# ---------------------------------------------------------------- для ассистента
def tool_name(cid: str, name: str) -> str:
    return f"c_{cid}__{re.sub(r'[^a-zA-Z0-9_]', '_', name)}"[:64]


def services(conn) -> list[dict]:
    """Подключённые сервисы и что ассистенту в них можно — для подсказки ассистенту."""
    out = []
    for r in conn.execute("SELECT id, settings FROM connections WHERE status = 'connected' ORDER BY id").fetchall():
        c = CATALOG.get(r["id"])
        if c:
            out.append({"id": r["id"], "name": c["name"], "icon": c["icon"], "keywords": c["keywords"],
                        "read": allowed(r["settings"], "read"), "write": allowed(r["settings"], "write")})
    return out


def mentioned(text: str | None) -> set[str]:
    """Какие сервисы упомянуты в тексте («найди в ноушене…»)."""
    q = (text or "").lower()
    return {cid for cid, c in CATALOG.items() if any(k in q for k in c["keywords"])}


TOOL_LIMIT = 8  # у Notion ~70 инструментов: все сразу — ~30 тыс. токенов на каждый шаг ассистента
PICK_SYSTEM = ("Выбери инструменты сервиса, которые нужны для задачи пользователя. Верни строго JSON "
               '{"tools": ["имя", ...]} — до 8 имён точно как в списке, самые нужные первыми. Нужно создать или '
               "изменить — бери и инструменты поиска/чтения, чтобы найти место; только найти или прочитать — только их.")
BASE_RANK = ("search", "fetch", "create", "update", "comment", "get", "list")


def _rank(t: dict) -> int:
    name = t["name"].lower()
    return next((i for i, k in enumerate(BASE_RANK) if k in name), len(BASE_RANK))


def _brief(t: dict) -> str:
    lines = (t.get("description") or "").strip().splitlines()
    return f"{t['name']}: {lines[0][:140] if lines else ''}"


def pick_tools(conn, tools: list[dict], need: str) -> list[dict]:
    """Нужные для задачи инструменты (до TOOL_LIMIT): выбирает самая дешёвая модель по коротким описаниям."""
    if len(tools) <= TOOL_LIMIT:
        return tools
    from . import llm  # noqa: PLC0415

    names: list[str] = []
    try:
        res = llm.chat_json(conn, "remember", PICK_SYSTEM, f"Задача: {(need or '')[:1500]}\n\nИнструменты:\n"
                            + "\n".join(_brief(t) for t in tools), timeout=40, fast=True, usage="tool_pick")
        names = [str(n) for n in res.get("tools") or [] if n]
    except Exception as exc:  # noqa: BLE001
        log.warning("tool pick failed: %s", exc)
    by_name = {t["name"]: t for t in tools}
    chosen = [by_name[n] for n in dict.fromkeys(names) if n in by_name][:TOOL_LIMIT]
    return chosen or sorted(tools, key=_rank)[:TOOL_LIMIT]


def tool_specs(conn, question: str | None = None, only: str | None = None,
               need: str | None = None) -> tuple[list[dict], dict]:
    """Инструменты подключённых сервисов. question — показываем только сервисы, о которых спрашивают
    (иначе каждый вопрос тянул бы десятки чужих описаний и стоил дороже). Действия, которые пользователь
    выключил («просматривать», «создавать и изменять»), ассистенту не показываем вовсе. Инструментов
    много — только нужные для задачи (need или вопрос)."""
    wanted = mentioned(question)
    specs, index = [], {}
    for r in conn.execute("SELECT id, tools, settings FROM connections WHERE status = 'connected'").fetchall():
        c = CATALOG.get(r["id"])
        if not c or (only and r["id"] != only):
            continue
        if question is not None and not only and r["id"] not in wanted:
            continue
        tools = r["tools"] or []
        if not tools:  # при входе список не подтянулся (сервис был занят) — берём сейчас
            try:
                tools = refresh_tools(conn, r["id"])
            except Exception as exc:  # noqa: BLE001
                log.warning("%s: tools unavailable: %s", r["id"], exc)
        tools = pick_tools(conn, [t for t in tools if allowed(r["settings"], tool_access(t))], need or question or "")
        for t in tools:
            schema = t.get("schema") if isinstance(t.get("schema"), dict) else {}
            if schema.get("type") != "object":
                schema = {"type": "object", "properties": {}}
            name = tool_name(r["id"], t["name"])
            specs.append({"type": "function", "function": {
                "name": name, "description": f"[{c['name']}] {t.get('description') or t['name']}"[:1024],
                "parameters": schema}})
            index[name] = (r["id"], t["name"], f"{c['icon']} {c['name']}: {t.get('title') or t['name']}")
    return specs, index
