"""Подключение приложения к серверу по одноразовому коду.

Репозиторий публичный, поэтому ключ доступа (API_TOKEN) больше не зашивается в APK. На сервере
выпускается код из 8 цифр (deploy.sh печатает его в конце), пользователь вводит его в приложении
один раз, и приложение получает ключ.

Код живёт 30 минут, действует один раз и сгорает после 5 неверных попыток.

Выпустить новый код:  docker compose exec gateway python -m neuro.pairing
"""
from __future__ import annotations

import hashlib
import hmac
import secrets
import time

from . import config

TTL_SEC = 30 * 60
MAX_ATTEMPTS = 5


def _hash(code: str) -> str:
    return hashlib.sha256(("mnemo-pair:" + code).encode()).hexdigest()


def _set(conn, key: str, value: str):
    conn.execute(
        """INSERT INTO settings (key, value) VALUES (%s, %s)
           ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", (key, value))


def new_code(conn, ttl_sec: int = TTL_SEC) -> str:
    """Новый код (старый перестаёт действовать). В базе хранится только его хеш."""
    code = f"{secrets.randbelow(10 ** 8):08d}"
    _set(conn, "pair.code", _hash(code))
    _set(conn, "pair.expires", str(int(time.time()) + ttl_sec))
    _set(conn, "pair.attempts", "0")
    return code


def _get(conn, key: str) -> str | None:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (key,)).fetchone()
    return row["value"] if row else None


def redeem(conn, code: str) -> str:
    """Проверяет код. Возвращает ok | bad | expired | locked. Удачный код сразу гаснет."""
    stored = _get(conn, "pair.code")
    if not stored:
        return "expired"
    if int(_get(conn, "pair.expires") or 0) < time.time():
        conn.execute("DELETE FROM settings WHERE key LIKE 'pair.%%'")
        return "expired"
    attempts = int(_get(conn, "pair.attempts") or 0)
    if attempts >= MAX_ATTEMPTS:
        return "locked"
    code = "".join(ch for ch in str(code) if ch.isdigit())
    if len(code) == 8 and hmac.compare_digest(_hash(code), stored):
        conn.execute("DELETE FROM settings WHERE key LIKE 'pair.%%'")
        return "ok"
    _set(conn, "pair.attempts", str(attempts + 1))
    return "locked" if attempts + 1 >= MAX_ATTEMPTS else "bad"


def pretty(code: str) -> str:
    return f"{code[:4]} {code[4:]}"


def main():
    from . import db  # noqa: PLC0415

    db.wait_and_migrate()
    with db.connect() as conn:
        code = new_code(conn)
    if not config.API_TOKEN:
        print("Внимание: API_TOKEN в .env пустой — приложение не сможет подключиться.")
    print(f"Код для приложения: {pretty(code)}  (действует 30 минут, один раз)")


if __name__ == "__main__":
    main()
