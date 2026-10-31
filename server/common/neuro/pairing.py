"""Подключение приложения к серверу.

Репозиторий публичный, поэтому ключ доступа (API_TOKEN) не зашивается в APK. Три пути:

1. Первый телефон — сам: пока к серверу не подключалось ни одно приложение (нет устройств и записей),
   первое обращение к /api/pair/auto получает ключ, и этот путь сразу закрывается.
2. Следующие телефоны — с разрешения: новый телефон создаёт запрос, уже подключённый телефон
   показывает «Разрешить?», после ответа новый телефон получает ключ.
3. Запасной — код из 8 цифр (deploy.sh печатает его в конце): живёт 30 минут, действует один раз,
   сгорает после 5 неверных попыток. Новый код:  docker compose exec gateway python -m neuro.pairing
"""
from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from datetime import datetime, timedelta, timezone

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
        conn.execute("DELETE FROM settings WHERE key IN ('pair.code', 'pair.expires', 'pair.attempts')")
        return "expired"
    attempts = int(_get(conn, "pair.attempts") or 0)
    if attempts >= MAX_ATTEMPTS:
        return "locked"
    code = "".join(ch for ch in str(code) if ch.isdigit())
    if len(code) == 8 and hmac.compare_digest(_hash(code), stored):
        conn.execute("DELETE FROM settings WHERE key IN ('pair.code', 'pair.expires', 'pair.attempts')")
        mark_paired(conn)
        return "ok"
    _set(conn, "pair.attempts", str(attempts + 1))
    return "locked" if attempts + 1 >= MAX_ATTEMPTS else "bad"


def paired(conn) -> bool:
    """К серверу уже подключался телефон: был выдан ключ или пришли записи с кулона."""
    if _get(conn, "pair.done") == "1":
        return True
    return conn.execute("SELECT 1 FROM devices UNION ALL SELECT 1 FROM audio_chunks LIMIT 1").fetchone() is not None


def mark_paired(conn):
    _set(conn, "pair.done", "1")


def auto(conn) -> bool:
    """Первый телефон подключается без кода. True — ключ можно выдать (и путь закрывается)."""
    if paired(conn):
        return False
    mark_paired(conn)
    return True


REQUEST_TTL = timedelta(minutes=30)


def create_request(conn, name: str, ip: str | None) -> str | None:
    """Новый телефон просит доступ. Не больше 5 ожидающих запросов за час (защита от спама)."""
    pending = conn.execute(
        "SELECT count(*) AS n FROM pair_requests WHERE status = 'pending' AND created_at > now() - interval '1 hour'"
    ).fetchone()["n"]
    if pending >= 5:
        return None
    rid = secrets.token_urlsafe(24)
    conn.execute("INSERT INTO pair_requests (id, name, ip) VALUES (%s, %s, %s)",
                 (rid, (name or "?").strip()[:80], (ip or "")[:64]))
    return rid


def request_status(conn, rid: str) -> str:
    row = conn.execute("SELECT status, created_at FROM pair_requests WHERE id = %s", (rid,)).fetchone()
    if row is None:
        return "unknown"
    if row["status"] == "pending" and row["created_at"] < datetime.now(timezone.utc) - REQUEST_TTL:
        return "expired"
    return row["status"]


def deliver(conn, rid: str):
    """Ключ отдан новому телефону — запрос больше не нужен."""
    conn.execute("DELETE FROM pair_requests WHERE id = %s", (rid,))


def pending_requests(conn) -> list[dict]:
    return conn.execute(
        """SELECT id, name, created_at FROM pair_requests
           WHERE status = 'pending' AND created_at > now() - interval '30 minutes' ORDER BY created_at""").fetchall()


def answer_request(conn, rid: str, approve: bool) -> bool:
    res = conn.execute("UPDATE pair_requests SET status = %s WHERE id = %s AND status = 'pending'",
                       ("approved" if approve else "rejected", rid))
    return res.rowcount > 0


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
