"""«Создать интеграцию с ассистентом»: пользователь описывает словами, модель собирает manifest.json.

Модель видит формат манифеста и правила безопасности; результат проходит ту же проверку, что и манифесты
из маркетплейса (домены, подстановки, события). Устанавливается только после «Установить» в приложении:
пользователь видит, что интеграция умеет, на какие сайты ходит и какие ключи нужны.
"""
from __future__ import annotations

import json
import re

from . import extensions, i18n, llm

SYSTEM = """Ты собираешь интеграцию (плагин) для Mnemo — личного ассистента с носимым микрофоном.
Интеграция — один JSON manifest. Верни строго JSON: {"manifest": {...}, "notes": "1–3 коротких предложения:
что пользователю сделать перед включением (где взять ключ, что вставить в n8n и т. п.)"}.

Формат manifest:
{"schema": 1, "id": "латиница-цифры-дефис", "name": "Название", "icon": "одно эмодзи", "version": "1.0.0",
 "author": "Mnemo", "description": "что умеет, одно предложение",
 "permissions": {"hosts": ["api.example.com"]},
 "settings": [{"key": "api_key", "title": "API-ключ", "secret": true, "required": true, "hint": "где взять"},
              {"key": "url", "title": "Адрес", "type": "url", "required": true, "hint": "…"}],
 "tools": [{"name": "snake_case", "title": "Что делаю (для чата)", "description": "для модели: когда вызывать",
            "parameters": {"type": "object", "properties": {"text": {"type": "string", "description": "…"}},
                           "required": ["text"]},
            "http": {"method": "POST", "url": "https://api.example.com/v1/items?key={settings.api_key}",
                     "headers": {"Authorization": "Bearer {settings.api_key}"}, "body": {"content": "{text}"}}}],
 "on": {"conversation": {"title": "…", "http": {…} или "webhook": {"url": "{settings.url}"}},
        "task": {…}, "memory": {…}, "day": {…}}}

Инструменты (tools) вызывает ассистент по просьбе пользователя («добавь в Todoist…»). События (on) отправляются сами:
conversation — новый разговор (поля {title} {summary} {emoji} {started_at} {ended_at} {topics} {people} {tasks}),
task — новая задача ({text} {due} {assignee} {source} {conversation}), memory — «запомни это» ({text}),
day — итог дня ({day} {title} {summary} {mood}). Можно только tools, только on или оба.

Правила:
- Используй настоящие публичные API сервиса, которые ты знаешь точно; не выдумывай адреса.
- Каждый домен из url — в permissions.hosts. Домен нельзя собирать из подстановок.
- Ключи и токены — в settings с secret: true; в url и заголовки — через {settings.key}.
- Если пользователь хочет слать события в n8n, Make, Zapier, Home Assistant или на свой сервер — настройка
  {"key": "url", "type": "url"} и "webhook": {"url": "{settings.url}"} (адрес вводит пользователь); можно добавить
  настройку "secret" (secret: true) — тело будет подписано.
- Только https. Без webhook для инструментов к чужим серверам: предпочитай http.
- Названия и подсказки — на языке пользователя. Коротко и по делу.
- Если по описанию нельзя сделать безопасную интеграцию — верни {"manifest": null, "notes": "почему"}."""


def _slug(text: str) -> str:
    s = re.sub(r"[^a-z0-9-]+", "-", (text or "").lower()).strip("-")
    return (s or "custom")[:28]


def draft(conn, description: str, previous: dict | None = None, feedback: str | None = None) -> dict:
    """Манифест по описанию (или правка прошлого черновика). Возвращает {"manifest", "preview", "notes"}."""
    lang = i18n.lang(conn)
    user = "Что нужно пользователю:\n" + description.strip()[:2000]
    if previous:
        user += "\n\nПрошлый черновик:\n" + json.dumps(previous, ensure_ascii=False)[:8000]
    if feedback:
        user += "\n\nЧто поправить:\n" + feedback.strip()[:1000]
    last_error = None
    for _ in range(2):  # одна попытка исправить, если манифест не прошёл проверку
        prompt = user + (f"\n\nПрошлый ответ не прошёл проверку: {last_error}. Исправь." if last_error else "")
        result = llm.chat_json(conn, "agent_deep", SYSTEM + "\n" + i18n.write_in(lang), prompt, timeout=150,
                               usage="ext_builder")
        m = result.get("manifest")
        notes = str(result.get("notes") or "").strip()[:600] or None
        if not isinstance(m, dict):
            return {"manifest": None, "preview": None, "notes": notes or "Не получилось собрать интеграцию."}
        m.setdefault("schema", 1)
        m.setdefault("version", "1.0.0")
        m.setdefault("author", "Mnemo")
        m["id"] = _slug(str(m.get("id") or m.get("name") or "custom"))
        if not m["id"].startswith("my-"):
            m["id"] = ("my-" + m["id"])[:32]  # свои интеграции не перекрывают маркетплейс
        try:
            m = extensions.validate(m)
        except extensions.ExtensionError as exc:
            last_error = str(exc)
            continue
        return {"manifest": m, "preview": extensions.public_manifest(m), "notes": notes}
    return {"manifest": None, "preview": None, "notes": f"Не получилось собрать корректную интеграцию: {last_error}"}
