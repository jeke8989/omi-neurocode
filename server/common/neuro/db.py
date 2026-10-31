"""Подключение к Postgres и создание схемы."""
import logging
import time
from pathlib import Path

from . import config

log = logging.getLogger("neuro.db")

SCHEMA_LOCK_ID = 724001
SCHEMA_SQL = (Path(__file__).parent / "schema.sql").read_text(encoding="utf-8")


def connect():
    import psycopg

    from .crypto import dict_row  # строки уже расшифрованы (шифрование от утечек — crypto.py)

    return psycopg.connect(config.DATABASE_URL, autocommit=True, row_factory=dict_row)


def wait_and_migrate(timeout_sec: int = 120):
    """Ждём, пока база поднимется, и создаём таблицы. Безопасно при параллельном запуске сервисов."""
    deadline = time.monotonic() + timeout_sec
    while True:
        try:
            with connect() as conn:
                conn.execute("SELECT pg_advisory_lock(%s)", (SCHEMA_LOCK_ID,))
                try:
                    conn.execute(SCHEMA_SQL)
                finally:
                    conn.execute("SELECT pg_advisory_unlock(%s)", (SCHEMA_LOCK_ID,))
            log.info("database ready")
            return
        except Exception as exc:  # noqa: BLE001
            if time.monotonic() > deadline:
                raise
            log.warning("database not ready yet: %s", exc)
            time.sleep(2)
