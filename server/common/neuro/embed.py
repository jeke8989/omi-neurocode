"""Смысловые отпечатки текстов (эмбеддинги) для памяти агента: поиск по смыслу, а не по точным словам.

Модель — через тот же OpenRouter (OpenAI-совместимый /embeddings); по умолчанию text-embedding-3-small
с 512 измерениями: ~$0.02 за миллион токенов, вся память пользователя — доли цента. В базе отпечатки
хранятся зашифрованными (по ним можно частично восстановить текст), сравниваются в памяти процесса.
Нет ключа или сервис недоступен — память работает по словам, без отпечатков.
"""
from __future__ import annotations

import base64
import logging
import time

import numpy as np
import requests

from . import config, crypto, llm

log = logging.getLogger("neuro.embed")

MODEL = config.env("EMBED_MODEL", "openai/text-embedding-3-small")
DIM = int(config.env("EMBED_DIM", "512") or 512)
PRICE_PER_M = 0.02
_pause_until = 0.0  # сервис отказал — не долбим его каждую секунду


def available() -> bool:
    return llm.configured() and time.time() >= _pause_until


def vectors(conn, texts: list[str], timeout: float = 30) -> list[np.ndarray] | None:
    """Нормированные отпечатки текстов (в том же порядке) или None, если сейчас недоступно."""
    global _pause_until
    texts = [" ".join(str(t or "").split())[:4000] or "-" for t in texts]
    if not texts:
        return []
    if not available():
        return None
    body = {"model": MODEL, "input": texts}
    if DIM and "text-embedding-3" in MODEL:
        body["dimensions"] = DIM
    try:
        r = requests.post(config.LLM_BASE_URL.rstrip("/") + "/embeddings", headers=llm._headers(), json=body,  # noqa: SLF001
                          timeout=timeout)
        if r.status_code >= 400:
            raise RuntimeError(f"{r.status_code}: {r.text[:200]}")
        data = r.json()
        rows = sorted(data.get("data") or [], key=lambda d: d.get("index", 0))
        out = [np.asarray(d["embedding"], dtype=np.float32) for d in rows]
        if len(out) != len(texts):
            raise RuntimeError("embeddings: wrong count")
    except Exception as exc:  # noqa: BLE001
        _pause_until = time.time() + 300
        log.warning("embeddings unavailable for 5 min: %s", exc)
        return None
    usage = dict(data.get("usage") or {})
    if usage.get("cost") is None and usage.get("prompt_tokens"):
        usage["cost"] = usage["prompt_tokens"] * PRICE_PER_M / 1_000_000
    llm.record_usage(conn, "embed", MODEL, usage)
    return [v / (np.linalg.norm(v) + 1e-9) for v in out]


def one(conn, text: str) -> np.ndarray | None:
    v = vectors(conn, [text])
    return v[0] if v else None


def pack(v: np.ndarray) -> str:
    """Отпечаток -> зашифрованная строка для базы (float16: 1 КБ на 512 измерений)."""
    return crypto.seal(base64.b64encode(np.asarray(v, dtype=np.float16).tobytes()).decode())


def unpack(s: str | None) -> np.ndarray | None:
    """Строка из базы (dict_row уже расшифровал) -> отпечаток."""
    if not s:
        return None
    try:
        raw = crypto.unseal(s) if crypto.sealed(s) else s
        return np.frombuffer(base64.b64decode(raw), dtype=np.float16).astype(np.float32)
    except Exception:  # noqa: BLE001
        return None
