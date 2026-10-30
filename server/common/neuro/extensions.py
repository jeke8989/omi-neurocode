"""Расширения (маркетплейс): манифест, проверка, установка и выполнение инструментов.

Расширение = manifest.json. Каждый инструмент — либо декларативный HTTP-запрос (`http`), либо
вызов своего сервера разработчика (`webhook`). Так можно опубликовать интеграцию без своего бэкенда.
Формат описан в marketplace/README.md.
"""
from __future__ import annotations

import json
import logging
import re
from pathlib import Path
from urllib.parse import quote, urljoin, urlparse

import requests

from . import config

log = logging.getLogger("neuro.extensions")

ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,31}$")
TOOL_RE = re.compile(r"^[a-z0-9_]{1,32}$")
PLACEHOLDER = re.compile(r"\{([a-zA-Z0-9_.]+)\}")
BUILTIN_DIR = Path(__file__).resolve().parents[2] / "marketplace"
ALLOW_HTTP = config.env_bool("ALLOW_HTTP_EXTENSIONS", False)
MARKETPLACE_URL = config.env("MARKETPLACE_URL", "")
MAX_RESULT = 6000


class ExtensionError(ValueError):
    pass


# ---------------------------------------------------------------- проверка манифеста
def validate(m: dict) -> dict:
    if not isinstance(m, dict):
        raise ExtensionError("манифест должен быть JSON-объектом")
    if m.get("schema", 1) != 1:
        raise ExtensionError("поддерживается schema = 1")
    if not ID_RE.match(str(m.get("id", ""))):
        raise ExtensionError("id: латиница, цифры и дефис, 2–32 символа")
    for key in ("name", "version", "description"):
        if not str(m.get(key) or "").strip():
            raise ExtensionError(f"нет поля {key}")
    hosts = (m.get("permissions") or {}).get("hosts") or []
    if not isinstance(hosts, list) or not all(isinstance(h, str) and h for h in hosts):
        raise ExtensionError("permissions.hosts — список доменов")
    tools = m.get("tools")
    if not isinstance(tools, list) or not tools:
        raise ExtensionError("нужен хотя бы один инструмент в tools")
    names = set()
    for t in tools:
        if not TOOL_RE.match(str(t.get("name", ""))):
            raise ExtensionError(f"имя инструмента {t.get('name')!r}: a-z, 0-9, _")
        if t["name"] in names:
            raise ExtensionError(f"повтор инструмента {t['name']}")
        names.add(t["name"])
        if not str(t.get("description") or "").strip():
            raise ExtensionError(f"{t['name']}: нет description")
        params = t.get("parameters") or {"type": "object", "properties": {}}
        if params.get("type") != "object":
            raise ExtensionError(f"{t['name']}: parameters должен быть JSON Schema с type=object")
        t["parameters"] = params
        if bool(t.get("http")) == bool(t.get("webhook")):
            raise ExtensionError(f"{t['name']}: нужен ровно один из http или webhook")
        url = (t.get("http") or t.get("webhook") or {}).get("url", "")
        _check_host(_static_host(url), hosts)
    hook = m.get("on_task")
    if hook is not None:
        if not isinstance(hook, dict) or bool(hook.get("http")) == bool(hook.get("webhook")):
            raise ExtensionError("on_task: нужен ровно один из http или webhook")
        _check_host(_static_host((hook.get("http") or hook.get("webhook"))["url"]), hosts)
    for s in m.get("settings") or []:
        if not re.match(r"^[a-z0-9_]{1,32}$", str(s.get("key", ""))):
            raise ExtensionError("settings[].key: a-z, 0-9, _")
    return m


def _static_host(url_template: str) -> str:
    # хост не должен зависеть от аргументов модели — иначе её можно увести на чужой адрес
    parsed = urlparse(url_template)
    if PLACEHOLDER.search(parsed.netloc or ""):
        raise ExtensionError("домен в url не может содержать подстановки")
    if parsed.scheme not in ("https", "http") or (parsed.scheme == "http" and not ALLOW_HTTP):
        raise ExtensionError("разрешены только https-адреса")
    return parsed.hostname or ""


def _check_host(host: str, hosts: list[str]):
    if host not in hosts:
        raise ExtensionError(f"домен {host} не указан в permissions.hosts")


# ---------------------------------------------------------------- маркетплейс
def _read_json(source: str, base: str | None = None) -> dict:
    if base and not re.match(r"^https?://", source):
        source = urljoin(base, source) if re.match(r"^https?://", base) else str(Path(base).parent / source)
    if re.match(r"^https?://", source):
        r = requests.get(source, timeout=15)
        r.raise_for_status()
        return r.json()
    return json.loads(Path(source).read_text(encoding="utf-8"))


def registry() -> list[dict]:
    """Каталог: встроенный (server/marketplace) + внешний MARKETPLACE_URL, если задан."""
    items: dict[str, dict] = {}
    sources = []
    local_index = BUILTIN_DIR / "index.json"
    if local_index.exists():
        sources.append(str(local_index))
    if MARKETPLACE_URL:
        sources.append(MARKETPLACE_URL)
    for src in sources:
        try:
            index = _read_json(src)
        except Exception as exc:  # noqa: BLE001
            log.warning("marketplace %s unavailable: %s", src, exc)
            continue
        for e in index.get("extensions") or []:
            if ID_RE.match(str(e.get("id", ""))) and e.get("manifest"):
                items[e["id"]] = {**e, "_base": src}
    return sorted(items.values(), key=lambda e: (not e.get("verified"), e.get("name", e["id"])))


def load_manifest(entry_or_url) -> tuple[dict, str]:
    if isinstance(entry_or_url, dict):
        src = entry_or_url["manifest"]
        base = entry_or_url.get("_base")
        if base and not re.match(r"^https?://", src):
            src = urljoin(base, src) if re.match(r"^https?://", base) else str(Path(base).parent / src)
    else:
        src = str(entry_or_url)
        if not re.match(r"^https://", src):
            raise ExtensionError("ссылка на манифест должна начинаться с https://")
    return validate(_read_json(src)), src


def public_manifest(m: dict) -> dict:
    """Что показываем в приложении (без внутренностей запросов)."""
    return {
        "id": m["id"], "name": m["name"], "version": m["version"], "description": m["description"],
        "author": m.get("author"), "icon": m.get("icon"), "homepage": m.get("homepage"),
        "hosts": (m.get("permissions") or {}).get("hosts") or [],
        "tools": [{"name": t["name"], "title": t.get("title") or t["name"], "description": t["description"]}
                  for t in m["tools"]],
        "task_sync": (m.get("on_task") or {}).get("title"),
        "settings": [{"key": s["key"], "title": s.get("title") or s["key"], "secret": bool(s.get("secret")),
                      "required": bool(s.get("required")), "hint": s.get("hint")} for s in m.get("settings") or []],
    }


# ---------------------------------------------------------------- инструменты для агента
def tool_name(ext_id: str, tool: str) -> str:
    return f"x_{ext_id.replace('-', '_')}__{tool}"[:64]


def tool_specs(rows: list[dict]) -> tuple[list[dict], dict[str, tuple[dict, dict]]]:
    specs, index = [], {}
    for row in rows:
        m = row["manifest"]
        missing = [s["key"] for s in m.get("settings") or [] if s.get("required") and
                   not (row.get("settings") or {}).get(s["key"])]
        if missing:
            continue  # не настроено — агенту не показываем
        for t in m["tools"]:
            name = tool_name(m["id"], t["name"])
            specs.append({"type": "function", "function": {
                "name": name,
                "description": f"[{m['name']}] {t['description']}",
                "parameters": t["parameters"],
            }})
            index[name] = (row, t)
    return specs, index


def _render(value, args: dict, settings: dict, url_mode: bool = False):
    if isinstance(value, dict):
        return {(_render(k, args, settings) if isinstance(k, str) else k): _render(v, args, settings)
                for k, v in value.items()}
    if isinstance(value, list):
        return [_render(v, args, settings) for v in value]
    if not isinstance(value, str):
        return value
    whole = PLACEHOLDER.fullmatch(value)
    if whole and not url_mode:  # "{amount}" целиком -> сохраняем тип (число, bool)
        key = whole.group(1)
        return settings.get(key[9:], "") if key.startswith("settings.") else args.get(key, "")

    def sub(mt):
        key = mt.group(1)
        v = settings.get(key[9:], "") if key.startswith("settings.") else args.get(key, "")
        v = "" if v is None else str(v)
        return quote(v, safe=":@") if url_mode else v

    return PLACEHOLDER.sub(sub, value)


def is_ready(row: dict) -> bool:
    return all((row.get("settings") or {}).get(s["key"]) for s in row["manifest"].get("settings") or []
               if s.get("required"))


def call_hook(row: dict, args: dict) -> tuple[bool, str]:
    """Хук on_task: отправить новую задачу во внешний сервис. Возвращает (успех, ссылка/ответ или ошибка)."""
    hook = {"name": "on_task", **row["manifest"]["on_task"]}
    text = run_tool(row, hook, args, {"event": "task_created"})
    failed = text.startswith(("Ошибка", "Запрос заблокирован", "Сервис недоступен"))
    if failed:
        return False, text[:500]
    ref = ""
    try:
        data = json.loads(text)
        if isinstance(data, dict):
            ref = data.get("html_url") or data.get("url") or ""
            if not ref and isinstance(data.get("result"), dict):
                ref = str(data["result"].get("message_id") or "")
    except ValueError:
        pass
    return True, str(ref)[:500]


def run_tool(row: dict, tool: dict, args: dict, context: dict | None = None) -> str:
    m = row["manifest"]
    settings = row.get("settings") or {}
    hosts = (m.get("permissions") or {}).get("hosts") or []
    try:
        if tool.get("http"):
            h = tool["http"]
            url = _render(h["url"], args, settings, url_mode=True)
            _check_host(_static_host(url), hosts)
            method = (h.get("method") or "GET").upper()
            headers = {k: str(v) for k, v in _render(h.get("headers") or {}, args, settings).items()}
            body = _render(h.get("body"), args, settings) if h.get("body") is not None else None
            r = requests.request(method, url, headers=headers, json=body, timeout=15)
        else:
            url = tool["webhook"]["url"]
            _check_host(_static_host(url), hosts)
            r = requests.post(url, json={"tool": tool["name"], "arguments": args, "settings": settings,
                                         "context": context or {}}, timeout=20)
        text = r.text
        if r.status_code >= 400:
            return f"Ошибка {r.status_code}: {text[:500]}"
        try:
            data = r.json()
            if tool.get("webhook") and isinstance(data, dict) and "result" in data:
                data = data["result"]
            text = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
        except ValueError:
            pass
        return text[:MAX_RESULT]
    except ExtensionError as exc:
        return f"Запрос заблокирован: {exc}"
    except requests.RequestException as exc:
        return f"Сервис недоступен: {exc.__class__.__name__}"
