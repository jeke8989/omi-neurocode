"""Шифрование данных в базе — от утечек: дамп или бэкап базы, доступ к ней в обход приложения.

Ключ — DATA_KEY в server/.env (32 байта в base64; создаётся сам при обновлении сервера). В базе лежат
только зашифрованные значения: расшифровка — в коде сервера, при чтении строки (dict_row ниже), поэтому
запросы и остальной код работают с обычным текстом.

Виды значений (префикс в начале строки):
  enc1: — AES-256-GCM со случайным nonce: тексты (расшифровки, итоги, задачи, вопросы и ответы);
  encd: — AES-SIV, детерминированное: одинаковые значения дают одинаковый шифротекст — для имён, тем и
          ключей мест, по которым база сравнивает и группирует (=, DISTINCT). Раскрывает только «равно ли»;
  encj: — JSON-документ целиком (хайлайты и места дня).
Файлы (фото, образцы голоса) — тот же AES-GCM с заголовком MNEF1.
Без ключа сервер пишет как раньше, открытым текстом; зашифрованное без ключа не читается — поэтому ключ
нужно хранить вместе с бэкапом .env, отдельно от бэкапа базы.
"""
from __future__ import annotations

import base64
import json
import logging
import os
from pathlib import Path

log = logging.getLogger("neuro.crypto")

PREFIX_R = "enc1:"
PREFIX_D = "encd:"
PREFIX_J = "encj:"
PREFIXES = (PREFIX_R, PREFIX_D, PREFIX_J)
FILE_MAGIC = b"MNEF1"
_AAD = b"mnemo-v1"

_state: dict = {}


def _load():
    if "ready" in _state:
        return _state.get("gcm"), _state.get("siv")
    _state["ready"] = True
    raw = (os.environ.get("DATA_KEY") or "").strip()
    if not raw:
        return None, None
    try:
        key = base64.b64decode(raw + "=" * (-len(raw) % 4)) if len(raw) != 64 else bytes.fromhex(raw)
    except ValueError:
        key = b""
    if len(key) < 32:
        log.error("DATA_KEY must be 32 bytes (base64) — data is stored unencrypted")
        return None, None
    from cryptography.hazmat.primitives import hashes  # noqa: PLC0415
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM, AESSIV  # noqa: PLC0415
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF  # noqa: PLC0415

    def derive(info: bytes, n: int) -> bytes:
        return HKDF(algorithm=hashes.SHA256(), length=n, salt=b"mnemo", info=info).derive(key[:32])

    _state["gcm"] = AESGCM(derive(b"mnemo-random", 32))
    _state["siv"] = AESSIV(derive(b"mnemo-deterministic", 64))
    return _state["gcm"], _state["siv"]


def reset():
    """Для тестов: перечитать DATA_KEY."""
    _state.clear()


def enabled() -> bool:
    return _load()[0] is not None


def _b64(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")


def sealed(v) -> bool:
    return isinstance(v, str) and v.startswith(PREFIXES)


def seal(v: str | None) -> str | None:
    """Текст -> enc1:… (случайный nonce)."""
    gcm, _ = _load()
    if gcm is None or not v or sealed(v):
        return v
    nonce = os.urandom(12)
    return PREFIX_R + _b64(nonce + gcm.encrypt(nonce, str(v).encode("utf-8"), _AAD))


def seal_det(v: str | None) -> str | None:
    """Текст -> encd:… (одинаковый текст — одинаковый шифротекст: для имён, тем, ключей)."""
    _, siv = _load()
    if siv is None or not v or sealed(v):
        return v
    return PREFIX_D + _b64(siv.encrypt(str(v).encode("utf-8"), [_AAD]))


def seal_list(items) -> list | None:
    """Список тем и т. п. — каждое значение детерминированно (DISTINCT и сравнение в базе работают)."""
    if items is None:
        return None
    return [seal_det(x) for x in items]


def seal_json(obj) -> str:
    """JSON-документ для колонки jsonb: вернуть JSON-текст (строку encj:… или сам документ без ключа)."""
    gcm, _ = _load()
    text = json.dumps(obj, ensure_ascii=False, default=str)
    if gcm is None:
        return text
    nonce = os.urandom(12)
    return json.dumps(PREFIX_J + _b64(nonce + gcm.encrypt(nonce, text.encode("utf-8"), _AAD)))


def unseal(v):
    if not isinstance(v, str) or not v.startswith(PREFIXES):
        return v
    gcm, siv = _load()
    if gcm is None:
        return v  # без ключа не прочитать — отдаём как есть
    try:
        raw = base64.b64decode(v[5:])
        if v.startswith(PREFIX_D):
            return siv.decrypt(raw, [_AAD]).decode("utf-8")
        text = gcm.decrypt(raw[:12], raw[12:], _AAD).decode("utf-8")
        return json.loads(text) if v.startswith(PREFIX_J) else text
    except Exception:  # noqa: BLE001 — обрезанное значение (left(...) в SQL) или чужой ключ
        return v


def unseal_any(v):
    """Расшифровать строку, список или JSON-объект (вложенные значения тоже)."""
    if isinstance(v, str):
        return unseal(v) if v.startswith(PREFIXES) else v
    if isinstance(v, list):
        return [unseal_any(x) for x in v]
    if isinstance(v, dict):
        return {k: unseal_any(x) for k, x in v.items()}
    return v


def unseal_row(row: dict) -> dict:
    for k, v in row.items():
        if isinstance(v, (str, list, dict)):
            row[k] = unseal_any(v)
    return row


def dict_row(cursor):
    """Строки результата как dict, уже расшифрованные (вместо psycopg.rows.dict_row)."""
    from psycopg.rows import dict_row as plain  # noqa: PLC0415

    make = plain(cursor)

    def row(values):
        return unseal_row(make(values))
    return row


dict_row.unseal_row = unseal_row  # для локальной замены psycopg в тестах


# ---------------------------------------------------------------- файлы
def seal_bytes(data: bytes) -> bytes:
    gcm, _ = _load()
    if gcm is None or data.startswith(FILE_MAGIC):
        return data
    nonce = os.urandom(12)
    return FILE_MAGIC + nonce + gcm.encrypt(nonce, data, _AAD)


def unseal_bytes(data: bytes) -> bytes:
    if not data.startswith(FILE_MAGIC):
        return data
    gcm, _ = _load()
    if gcm is None:
        raise ValueError("file is encrypted and DATA_KEY is not set")
    n = len(FILE_MAGIC)
    return gcm.decrypt(data[n:n + 12], data[n + 12:], _AAD)


def write_file(path, data: bytes):
    p = Path(path)
    tmp = p.with_name(p.name + ".part")
    tmp.write_bytes(seal_bytes(data))
    tmp.replace(p)


def read_file(path) -> bytes:
    return unseal_bytes(Path(path).read_bytes())
