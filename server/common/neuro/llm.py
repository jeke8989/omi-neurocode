"""Модели: выбор под задачу, вызов LLM (OpenAI-совместимый /chat/completions), распознавание речи
(OpenAI-совместимый /audio/transcriptions — OpenRouter, OpenAI, Groq…) и учёт расходов."""
from __future__ import annotations

import json
import logging
import re
import time

import requests

from . import config, i18n
from .audio import SAMPLE_RATE, pcm_to_wav
from .models import CATALOG, catalog_entry, task as task_info

log = logging.getLogger("neuro.llm")


def configured() -> bool:
    return bool(config.LLM_API_KEY)


def is_openrouter(base_url: str | None = None) -> bool:
    return "openrouter.ai" in (base_url or config.LLM_BASE_URL)


# ---------------------------------------------------------------- выбор модели
def resolve_model(conn, task: str) -> str:
    """Модель для задачи: выбор в приложении -> переменная LLM_MODEL_<TASK> -> LLM_MODEL -> рекомендованная."""
    if conn is not None:
        row = conn.execute("SELECT value FROM settings WHERE key = %s", (f"model.{task}",)).fetchone()
        if row and row["value"]:
            return row["value"]
    env_model = config.env(f"LLM_MODEL_{task.upper()}")
    if env_model:
        return env_model
    info = task_info(task)
    if info and info["kind"] == "llm" and config.LLM_MODEL:
        return config.LLM_MODEL
    if info and info["kind"] == "stt" and config.OPENAI_STT_MODEL:
        return config.OPENAI_STT_MODEL
    return info["default"] if info else config.LLM_MODEL


def record_usage(conn, task: str, model: str, usage: dict | None, audio_seconds: float | None = None):
    if conn is None:
        return
    usage = usage or {}
    prompt = usage.get("prompt_tokens") or usage.get("input_tokens")
    completion = usage.get("completion_tokens") or usage.get("output_tokens")
    cost = usage.get("cost")
    if cost is None:  # провайдер не вернул стоимость — оцениваем по каталогу
        entry = catalog_entry(model)
        if entry and entry.get("price_in") is not None and (prompt or completion):
            cost = ((prompt or 0) * entry["price_in"] + (completion or 0) * entry["price_out"]) / 1_000_000
        elif entry and entry.get("price_hour") is not None and audio_seconds:
            cost = audio_seconds / 3600 * entry["price_hour"]
    try:
        conn.execute(
            """INSERT INTO llm_usage (task, model, prompt_tokens, completion_tokens, audio_seconds, cost)
               VALUES (%s, %s, %s, %s, %s, %s)""",
            (task, model, prompt, completion, audio_seconds, cost),
        )
    except Exception:  # noqa: BLE001 — учёт расходов не должен ломать основную работу
        log.exception("cannot record usage")


# ---------------------------------------------------------------- LLM
def parse_json(content: str) -> dict:
    content = re.sub(r"^\s*```(?:json)?\s*|\s*```\s*$", "", content.strip())
    try:
        return json.loads(content)
    except ValueError:
        start, end = content.find("{"), content.rfind("}")
        if start >= 0 and end > start:
            return json.loads(content[start:end + 1])
        raise


def _headers() -> dict:
    h = {"Authorization": f"Bearer {config.LLM_API_KEY}"}
    if is_openrouter():
        h["X-Title"] = "Mnemo"
        h["HTTP-Referer"] = "https://neuro.codes"
    return h


def chat_json(conn, task: str, system: str, user: str, timeout: float = 180, fast: bool = False,
              usage: str | None = None) -> dict:
    """Запрос к модели задачи; ответ — JSON-объект. fast=True просит минимум «размышлений» (для голоса).
    usage — под каким именем записать расход (например, events: модель «итогов», но учёт отдельно)."""
    model = resolve_model(conn, task)
    body: dict = {
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0.2,
    }
    if is_openrouter():
        body["usage"] = {"include": True}
        if fast:
            body["reasoning"] = {"effort": "low"}
    url = config.LLM_BASE_URL.rstrip("/") + "/chat/completions"
    json_mode = config.LLM_JSON_MODE
    t0 = time.monotonic()
    for _ in range(2):
        payload = dict(body)
        if json_mode:
            payload["response_format"] = {"type": "json_object"}
        r = requests.post(url, headers=_headers(), json=payload, timeout=timeout)
        if r.status_code == 400 and json_mode:
            log.warning("%s rejected response_format, retrying without it", model)
            json_mode = False
            continue
        if r.status_code >= 400:
            raise RuntimeError(f"LLM {model} {r.status_code}: {r.text[:300]}")
        data = r.json()
        if "choices" not in data:
            raise RuntimeError(f"LLM {model}: unexpected response {str(data)[:300]}")
        record_usage(conn, usage or task, model, data.get("usage"))
        content = data["choices"][0]["message"].get("content") or ""
        log.info("%s via %s: %.1fs", task, model, time.monotonic() - t0)
        return parse_json(content)
    raise RuntimeError("LLM request failed")


def chat(conn, task: str, messages: list[dict], tools: list[dict] | None = None, reasoning: str | None = None,
         timeout: float = 120, extra: dict | None = None, model: str | None = None) -> dict:
    """Один шаг диалога с моделью (с инструментами). Возвращает message из ответа."""
    model = model or resolve_model(conn, task)
    body: dict = {"model": model, "messages": messages, "temperature": 0.3}
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    if is_openrouter():
        body["usage"] = {"include": True}
        if reasoning:
            body["reasoning"] = {"effort": reasoning}
    if extra:
        body.update(extra)
    r = requests.post(config.LLM_BASE_URL.rstrip("/") + "/chat/completions", headers=_headers(), json=body,
                      timeout=timeout)
    if r.status_code >= 400:
        raise RuntimeError(f"LLM {model} {r.status_code}: {r.text[:300]}")
    data = r.json()
    if not data.get("choices"):
        raise RuntimeError(f"LLM {model}: unexpected response {str(data)[:300]}")
    record_usage(conn, task, model, data.get("usage"))
    return data["choices"][0].get("message") or {}


# ---------------------------------------------------------------- распознавание речи
def transcribe(conn, task: str, pcm: bytes, verbose: bool = False, timeout: float = 120) -> dict:
    """OpenAI-совместимое распознавание. Возвращает JSON ответа ({"text": ..., "segments": [...]})."""
    model = resolve_model(conn, task)
    want_segments = verbose and "whisper" in model
    data = {"model": model, "response_format": "verbose_json" if want_segments else "json"}
    language = i18n.stt_language(conn)
    if language:
        data["language"] = language
    r = requests.post(
        config.OPENAI_STT_BASE_URL.rstrip("/") + "/audio/transcriptions",
        headers={"Authorization": f"Bearer {config.OPENAI_STT_API_KEY}"},
        files={"file": ("audio.wav", pcm_to_wav(pcm), "audio/wav")},
        data=data,
        timeout=timeout,
    )
    if r.status_code >= 400:
        raise RuntimeError(f"STT {model} {r.status_code}: {r.text[:300]}")
    data = r.json()
    record_usage(conn, task, model, data.get("usage"), audio_seconds=len(pcm) / 2 / SAMPLE_RATE)
    return data


def catalog_with_live_prices(live: dict[str, dict] | None) -> list[dict]:
    out = []
    for m in CATALOG:
        m = dict(m)
        lv = (live or {}).get(m["id"])
        if lv and m["kind"] == "llm" and lv.get("price_in") is not None:
            m["price_in"], m["price_out"] = lv["price_in"], lv["price_out"]
            m["live"] = True
        out.append(m)
    return out
