"""Память: долгие факты о жизни пользователя — без мусора.

Откуда берутся записи:
- button — двойное нажатие «Запомни это» (пользователь сам попросил) — сохраняются всегда;
- conversation — модель заметила в разговоре что-то, что пригодится через недели (предпочтения, планы,
  договорённости, важное о людях); разовые события остаются в итогах и дневнике;
- agent — ассистент запомнил по просьбе в чате.
Повтор того, что уже есть в памяти, не записывается. Записи, которые модель при перепроверке сочла мусором,
не удаляются, а уходят в архив (archived_at) — их можно вернуть.
"""
from __future__ import annotations

import difflib
import logging
import re

from . import llm

log = logging.getLogger("neuro.memory")

RULES = """В "memories" — только то, что пригодится пользователю через недели и месяцы: его предпочтения и привычки,
планы и договорённости с датами, важные факты о близких и коллегах (день рождения, аллергия, где работает),
решения. Не пиши: разовые события дня (они уже в итоге), вопросы пользователя, пересказ разговора, очевидное,
рекламу, новости, телевизор и чужие разговоры рядом. Каждый факт — одно самостоятельное предложение с именами.
Обычно в разговоре таких фактов нет — тогда пустой список; больше двух — почти никогда."""

REVIEW_KEY = "memory.reviewed_v1"
REVIEW_SYSTEM = """Ниже записи «памяти» личного ассистента, собранные автоматически из разговоров пользователя.
Оставь только долгие полезные факты: предпочтения, привычки, планы и договорённости, важное о людях, решения.
Убери разовые события дня, пересказы, вопросы, очевидное, обрывки, рекламу и новости, а также повторы
(из повторяющихся оставь одну, самую полную). Верни строго JSON: {"keep": [номера записей, которые оставить]}."""


def _norm(text: str) -> str:
    return re.sub(r"\W+", " ", (text or "").lower()).strip()


def duplicate(text: str, existing: list[str]) -> bool:
    n = _norm(text)
    if not n:
        return True
    for e in existing:
        m = _norm(e)
        if n == m or n in m or difflib.SequenceMatcher(None, n, m).ratio() >= 0.82:
            return True
    return False


def recent_texts(conn, limit: int = 500) -> list[str]:
    return [r["text"] for r in conn.execute(
        "SELECT text FROM memories WHERE archived_at IS NULL ORDER BY id DESC LIMIT %s", (limit,)).fetchall()]


def clean(conn, items: list[str], source: str) -> list[str]:
    """Что из предложенного действительно стоит записать: без пустого и без повторов (кнопку — всегда)."""
    out: list[str] = []
    existing = recent_texts(conn) if source != "button" else []
    for t in items:
        t = " ".join(str(t or "").split())[:500]
        if len(t) < 8 and source != "button":
            continue
        if source != "button" and duplicate(t, existing + out):
            continue
        out.append(t)
    return out[:3] if source == "conversation" else out


def review_tick(conn, batch: int = 60) -> int:
    """Один раз: перепроверить уже накопленные автоматические записи; мусор — в архив. Возвращает, сколько
    записей проверено (0 — всё проверено)."""
    if not llm.configured() or conn.execute("SELECT 1 FROM settings WHERE key = %s", (REVIEW_KEY,)).fetchone():
        return 0
    rows = conn.execute(
        """SELECT id, text FROM memories WHERE source <> 'button' AND archived_at IS NULL AND NOT reviewed
           ORDER BY id LIMIT %s""", (batch,)).fetchall()
    if not rows:
        conn.execute("INSERT INTO settings (key, value) VALUES (%s, 'done') ON CONFLICT (key) DO NOTHING", (REVIEW_KEY,))
        return 0
    listing = "\n".join(f"{i + 1}. {r['text']}" for i, r in enumerate(rows))
    try:
        result = llm.chat_json(conn, "summary", REVIEW_SYSTEM, listing, fast=True, usage="memory_review")
    except Exception:  # noqa: BLE001 — попробуем в следующий раз
        log.exception("memory review failed")
        return 0
    keep = set()
    for k in (result.get("keep") if "keep" in result else result.get("items")) or []:
        try:
            keep.add(int(k))
        except (TypeError, ValueError):
            continue
    if not keep and len(rows) > 5:
        # модель ответила пусто на большой список — скорее сбой, чем «всё мусор»: не трогаем
        conn.execute("UPDATE memories SET reviewed = true WHERE id = ANY(%s)", ([r["id"] for r in rows],))
        return len(rows)
    archived = [r["id"] for i, r in enumerate(rows) if i + 1 not in keep]
    if archived:
        conn.execute("UPDATE memories SET archived_at = now() WHERE id = ANY(%s)", (archived,))
    conn.execute("UPDATE memories SET reviewed = true WHERE id = ANY(%s)", ([r["id"] for r in rows],))
    log.info("memory review: kept %d of %d, archived %d", len(rows) - len(archived), len(rows), len(archived))
    return len(rows)
