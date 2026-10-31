"""Сколько хранить сырое аудио на сервере. Расшифровки хранятся всегда.

Выбирается в приложении (settings audio.keep_days): 0 — удалять звук сразу после расшифровки,
иначе через N дней. Пока в приложении ничего не выбрано — KEEP_AUDIO_DAYS из .env.
Без звука нельзя прослушать фразу (карточка человека, «Кто это?»), расшифровки и итоги остаются.
"""
from __future__ import annotations

import logging
from pathlib import Path

from . import config

log = logging.getLogger("neuro.retention")

KEY = "audio.keep_days"
CHOICES = (0, 1, 3, 7, 30)


def parse(value) -> int:
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return config.KEEP_AUDIO_DAYS


def keep_days(conn) -> int:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (KEY,)).fetchone()
    return parse(row["value"]) if row else config.KEEP_AUDIO_DAYS


def drop(conn, chunk_id: int, path: str):
    # сначала файл, потом ссылка: файл без строки в базе gateway вернул бы в очередь как новый
    Path(path).unlink(missing_ok=True)
    conn.execute("UPDATE audio_chunks SET path = NULL WHERE id = %s", (chunk_id,))


def after_transcribed(conn, chunk_id: int) -> bool:
    """Режим «удалять сразу»: звук куска уходит, как только кусок расшифрован (или в нём не было речи)."""
    if keep_days(conn) != 0:
        return False
    row = conn.execute(
        "SELECT path FROM audio_chunks WHERE id = %s AND path IS NOT NULL AND status IN ('done', 'empty')",
        (chunk_id,)).fetchone()
    if not row:
        return False
    drop(conn, chunk_id, row["path"])
    return True


def cleanup(conn) -> int:
    """Удаляет звук старше выбранного срока. Неудачные куски держим хотя бы сутки: их видно в диагностике."""
    days = keep_days(conn)
    old = conn.execute(
        """SELECT id, path FROM audio_chunks
           WHERE path IS NOT NULL
             AND ((status IN ('done', 'empty') AND started_at < now() - %(days)s * interval '1 day')
               OR (status = 'failed' AND started_at < now() - %(failed)s * interval '1 day'))
           LIMIT 2000""",
        {"days": days, "failed": max(days, 1)},
    ).fetchall()
    for row in old:
        drop(conn, row["id"], row["path"])
    if old:
        log.info("removed %d audio files (keep %d days)", len(old), days)
    return len(old)
