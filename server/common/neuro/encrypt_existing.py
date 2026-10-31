"""Зашифровать то, что уже лежит в базе открытым текстом (один раз после появления DATA_KEY).

Идёт порциями в фоне агента: строки читаются (расшифровка при чтении — crypto.dict_row), шифруются и
записываются обратно. Уже зашифрованные значения пропускаются. Можно прервать в любой момент — продолжится
с того же места; так же подхватывается всё, что старая версия сервера успела записать во время обновления.
"""
from __future__ import annotations

import json
import logging
import time

from . import crypto

log = logging.getLogger("neuro.encrypt")

PLAIN = "({c} IS NOT NULL AND {c} <> '' AND {c} NOT LIKE 'enc_:%%')"
# таблица, ключ, тексты (случайный nonce), значения для сравнения (детерминированно), списки тем, JSON
TABLES = [
    ("segments", "id", ["text", "raw_text"], [], [], []),
    ("conversations", "id", ["title", "summary"], [], ["topics"], []),
    ("tasks", "id", ["text", "evidence", "due", "assignee"], [], [], []),
    ("memories", "id", ["text"], [], [], []),
    ("questions", "id", ["question", "answer"], [], [], []),
    ("chat_messages", "id", ["text"], [], [], []),
    ("agent_runs", "id", ["question", "answer"], [], [], []),
    ("agent_steps", "id", ["title", "detail"], [], [], []),
    ("day_events", "id", ["title", "summary"], [], ["topics"], []),
    ("diary_days", "day", ["title", "summary", "mood", "quote"], [], [], ["highlights", "places"]),
    ("people", "id", ["note"], ["name"], [], []),
    ("person_suggestions", "id", ["evidence"], ["name"], [], []),
    ("photos", "id", ["caption"], [], [], []),
    ("places", "key", ["name"], ["key"], [], []),
]
BATCH = 300
DONE_KEY = "crypto.encrypted_v2"  # v1 мог отметиться «готово» ещё до появления ключа


def _where(rand, det, lists, docs) -> str:
    parts = [PLAIN.format(c=c) for c in rand + det]
    parts += [f"EXISTS (SELECT 1 FROM unnest({c}) x WHERE x NOT LIKE 'enc_:%%')" for c in lists]
    parts += [f"({c}::text NOT LIKE '\"encj:%%' AND {c}::text NOT IN ('[]', '{{}}', 'null'))" for c in docs]
    return " OR ".join(parts)


def _table(conn, table, key, rand, det, lists, docs, limit: int) -> int:
    cols = [key] + [c for c in rand + det + lists + docs if c != key]
    # ключ строки нужен в исходном (зашифрованном или нет) виде, чтобы найти её для UPDATE
    # «k:» впереди — чтобы чтение не расшифровало ключ: для UPDATE нужен его вид в базе
    rows = conn.execute(
        f"SELECT 'k:' || {key}::text AS __k, {', '.join(cols)} FROM {table} WHERE {_where(rand, det, lists, docs)} "
        f"LIMIT %s", (limit,)).fetchall()
    for r in rows:
        sets, vals = [], []
        for c in rand:
            sets.append(f"{c} = %s")
            vals.append(crypto.seal(r[c]))
        for c in det:
            sets.append(f"{c} = %s")
            vals.append(crypto.seal_det(r[c]))
        for c in lists:
            sets.append(f"{c} = %s")
            vals.append(crypto.seal_list(r[c] or []))
        for c in docs:
            sets.append(f"{c} = %s::jsonb")
            vals.append(crypto.seal_json(r[c] if r[c] is not None else []))
        conn.execute(f"UPDATE {table} SET {', '.join(sets)} WHERE {key}::text = %s", (*vals, r["__k"][2:]))
    return len(rows)


def _coords(conn, table: str, limit: int) -> int:
    rows = conn.execute(f"SELECT id, lat, lon FROM {table} WHERE lat IS NOT NULL AND lon IS NOT NULL LIMIT %s",
                        (limit,)).fetchall()
    for r in rows:
        conn.execute(f"UPDATE {table} SET pos = %s, lat = NULL, lon = NULL WHERE id = %s",
                     (crypto.seal(f"{r['lat']:.6f},{r['lon']:.6f}"), r["id"]))
    return len(rows)


def _extensions(conn) -> int:
    n = 0
    for r in conn.execute("SELECT id, settings FROM extensions").fetchall():
        st = r["settings"] or {}
        sealed = {k: crypto.seal(str(v)) if isinstance(v, str) else v for k, v in st.items()}
        raw = conn.execute("SELECT settings::text AS s FROM extensions WHERE id = %s", (r["id"],)).fetchone()
        if any(isinstance(v, str) and v and not crypto.sealed(v) for v in json.loads(raw["s"] or "{}").values()):
            conn.execute("UPDATE extensions SET settings = %s::jsonb WHERE id = %s",
                         (json.dumps(sealed, ensure_ascii=False), r["id"]))
            n += 1
    return n


def step(conn, budget_sec: float = 20.0) -> int:
    """Одна порция (не дольше budget_sec). Возвращает, сколько строк зашифровано; 0 — всё готово."""
    if not crypto.enabled():
        return 0
    t0 = time.monotonic()
    total = 0
    for spec in TABLES:
        while time.monotonic() - t0 < budget_sec:
            n = _table(conn, *spec, limit=BATCH)
            total += n
            if n < BATCH:
                break
    for table in ("locations", "photos"):
        while time.monotonic() - t0 < budget_sec:
            n = _coords(conn, table, BATCH)
            total += n
            if n < BATCH:
                break
    total += _extensions(conn)
    if total:
        log.info("encrypted %d existing values", total)
    return total


def tick(conn):
    """Из цикла агента: пока есть открытые данные — шифруем порциями; потом проверяем раз в час."""
    if not crypto.enabled():
        return  # без ключа шифровать нечем — и «готово» не отмечаем
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (DONE_KEY,)).fetchone()
    if row and time.time() - float(row["value"] or 0) < 3600:
        return
    if step(conn) == 0:
        conn.execute(
            """INSERT INTO settings (key, value) VALUES (%s, %s)
               ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
            (DONE_KEY, str(time.time())))
