"""Инструменты Mnemo по протоколу MCP (Streamable HTTP, ответы JSON, без SSE) — для ваших программ:
Claude Desktop, Cursor и других. Доступ — по личному ключу из «Интеграций» (в базе только его хеш).
Набор — как у агента Mnemo: память, события, разговоры, задачи, люди и включённые расширения. Веб-поиск
не отдаём: у этих программ он свой.
"""
from __future__ import annotations

import logging

from . import agent_core, extensions, i18n

log = logging.getLogger("neuro.mcp")

PROTOCOL = "2025-06-18"
SKIP = {"web_search", "ask_user"}  # веб-поиск у программ свой; уточняющий вопрос задают сами ответом


def _tools(conn) -> tuple[list[dict], dict]:
    rows = conn.execute("SELECT id, manifest, settings FROM extensions WHERE enabled").fetchall()
    ext_specs, index = extensions.tool_specs(rows)
    out = []
    for spec in agent_core.BUILTIN_TOOLS + ext_specs:
        f = spec["function"]
        if f["name"] in SKIP:
            continue
        out.append({"name": f["name"], "description": f.get("description") or "",
                    "inputSchema": f.get("parameters") or {"type": "object", "properties": {}}})
    return out, index


def call(conn, name: str, args: dict) -> str:
    tools, index = _tools(conn)
    if name in index:
        row, tool = index[name]
        return extensions.run_tool(row, tool, args, {"device_id": None, "locale": i18n.lang(conn)})
    if name not in {t["name"] for t in tools}:
        raise ValueError(f"нет такого инструмента: {name}")
    return agent_core.run_builtin(conn, name, args, None)


def handle(conn, msg: dict) -> dict | None:
    """Одно сообщение JSON-RPC -> ответ (None для уведомлений)."""
    mid, method = msg.get("id"), msg.get("method")
    if mid is None:
        return None
    params = msg.get("params") or {}
    try:
        if method == "initialize":
            result = {"protocolVersion": params.get("protocolVersion") or PROTOCOL,
                      "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": "mnemo", "version": "1.0"}}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": _tools(conn)[0]}
        elif method == "tools/call":
            args = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
            try:
                text, failed = call(conn, str(params.get("name") or ""), args), False
            except Exception as exc:  # noqa: BLE001 — ошибку инструмента видит модель, а не протокол
                log.warning("mcp tool %s failed: %s", params.get("name"), exc)
                text, failed = f"Ошибка инструмента: {exc}", True
            result = {"content": [{"type": "text", "text": text}], "isError": failed}
        else:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"method not found: {method}"}}
    except Exception as exc:  # noqa: BLE001
        log.exception("mcp %s failed", method)
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32603, "message": str(exc)[:300]}}
    return {"jsonrpc": "2.0", "id": mid, "result": result}
