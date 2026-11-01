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
from datetime import datetime, timedelta, timezone

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


# ======================================================================= память агента: поиск по смыслу
# Подход как у Mem0 (запись) + гибридный поиск (смысл + слова): в контекст модели идёт только нужное,
# а не вся память — токены не растут вместе с памятью, детали не теряются (их можно достать по ссылке).
WORD = re.compile(r"\w{3,}", re.U)
SIMILAR = 0.55        # похожие записи памяти — повод решить «добавить / обновить / не нужно»
RELEVANT = 0.30       # ниже — шум, в ответ не берём

UPDATE_SYSTEM = """Ты ведёшь долговременную память личного ассистента. Есть новые факты и похожие на них
записи, которые уже есть в памяти (с номерами). Для каждого нового факта реши:
- "add" — нового нет в памяти, добавить как есть;
- "update" — уточняет или меняет старую запись: верни id старой записи и новый текст, где объединено всё
  полезное (детали — числа, даты, имена — не теряй; устаревшее замени новым);
- "skip" — уже есть в памяти, ничего не добавляет.
Если старая запись теперь неверна и новый факт её заменяет — это "update" её id.
Верни строго JSON: {"actions": [{"fact": номер факта, "op": "add|update|skip", "id": id или null,
"text": "текст для update"}]}"""


def _stems(text: str) -> set[str]:
    """Грубые основы слов (первые 5 букв): «Notion», «ноушене» и «сводки»/«сводку» совпадают."""
    return {w[:5] for w in WORD.findall((text or "").lower())}


def _kw(q: set[str], text: str) -> float:
    if not q:
        return 0.0
    return len(q & _stems(text)) / len(q)


def recall(conn, query: str, k: int = 8, days: int | None = None,
           kinds: tuple[str, ...] = ("memory", "event", "conversation")) -> list[dict]:
    """Самое близкое по смыслу к запросу: память, события и разговоры. Отпечатки есть — по смыслу и словам,
    нет — по словам. [{"kind", "id", "at", "text", "score"}]"""
    from . import embed  # noqa: PLC0415

    qv = embed.one(conn, query) if query.strip() else None
    qw = _stems(query)
    since = (datetime.now(timezone.utc) - timedelta(days=days)) if days else datetime(2000, 1, 1, tzinfo=timezone.utc)
    items: list[dict] = []
    if "memory" in kinds:
        items += [{"kind": "memory", "id": r["id"], "at": r["created_at"], "text": r["text"], "emb": r["emb"]}
                  for r in conn.execute("""SELECT id, created_at, text, emb FROM memories WHERE archived_at IS NULL
                                           AND created_at > %s ORDER BY id DESC LIMIT 3000""", (since,)).fetchall()]
    if "event" in kinds:
        items += [{"kind": "event", "id": r["id"], "at": r["started_at"], "day": r["day"],
                   "text": f"{r['title'] or ''}: {r['summary'] or ''}", "emb": r["emb"]}
                  for r in conn.execute("""SELECT id, day, started_at, title, summary, emb FROM day_events
                                           WHERE started_at > %s ORDER BY started_at DESC LIMIT 3000""",
                                        (since,)).fetchall()]
    if "conversation" in kinds:
        items += [{"kind": "conversation", "id": r["id"], "at": r["started_at"],
                   "text": f"{r['title'] or ''}: {r['summary'] or ''}", "emb": r["emb"]}
                  for r in conn.execute("""SELECT id, started_at, title, summary, emb FROM conversations
                                           WHERE summary IS NOT NULL AND trashed_at IS NULL AND started_at > %s
                                           ORDER BY started_at DESC LIMIT 5000""", (since,)).fetchall()]
    for it in items:
        v = embed.unpack(it.pop("emb")) if qv is not None else None
        cos = float(v @ qv) if v is not None and len(v) == len(qv) else None
        kw = _kw(qw, it["text"])
        it["score"] = (cos if cos is not None else 0.0) + 0.25 * kw if qv is not None else kw
        it["relevant"] = (cos is not None and cos >= RELEVANT) or kw >= 0.34
    hits = sorted((it for it in items if it["relevant"]), key=lambda it: -it["score"])
    return [{k_: v_ for k_, v_ in it.items() if k_ != "relevant"} for it in hits[:k]]


def consolidate(conn, items: list[str], source: str) -> list[tuple[str, str | None]]:
    """Новые факты -> что записать: [(текст, отпечаток)]. Похожее на уже известное модель объединяет
    (обновляет старую запись) или пропускает — память не пухнет повторами и не хранит устаревшее."""
    from . import crypto, embed  # noqa: PLC0415

    items = clean(conn, items, source)
    if not items:
        return []
    vecs = embed.vectors(conn, items)
    if vecs is None:
        return [(t, None) for t in items]
    rows = conn.execute("SELECT id, text, emb FROM memories WHERE archived_at IS NULL AND emb IS NOT NULL "
                        "ORDER BY id DESC LIMIT 3000").fetchall()
    known = [(r["id"], r["text"], embed.unpack(r["emb"])) for r in rows]
    known = [(i, t, v) for i, t, v in known if v is not None]
    neighbors = []
    for v in vecs:
        sims = sorted(((float(kv @ v), i, t) for i, t, kv in known if len(kv) == len(v)), reverse=True)[:4]
        neighbors.append([(i, t) for s, i, t in sims if s >= SIMILAR])
    out = [(t, embed.pack(v)) for t, v, nb in zip(items, vecs, neighbors) if not nb]
    pending = [(n, t, v, nb) for n, (t, v, nb) in enumerate(zip(items, vecs, neighbors)) if nb]
    if not pending:
        return out
    prompt = "\n\n".join(f"Новый факт {n}: {t}\nПохожие в памяти:\n" + "\n".join(f"  [{i}] {old}" for i, old in nb)
                         for n, t, _, nb in pending)
    try:
        res = llm.chat_json(conn, "remember", UPDATE_SYSTEM, prompt, timeout=60, usage="memory_update")
        actions = {int(a.get("fact")): a for a in res.get("actions") or [] if isinstance(a, dict)
                   and str(a.get("fact", "")).lstrip("-").isdigit()}
    except Exception as exc:  # noqa: BLE001
        log.warning("memory update failed, adding as is: %s", exc)
        actions = {}
    for n, t, v, nb in pending:
        a = actions.get(n) or {"op": "add"}
        ids = {i for i, _ in nb}
        op = str(a.get("op") or "add")
        if op == "update" and a.get("id") in ids and str(a.get("text") or "").strip():
            text = " ".join(str(a["text"]).split())[:600]
            nv = embed.one(conn, text)
            conn.execute("UPDATE memories SET text = %s, emb = %s, updated_at = now(), reviewed = true WHERE id = %s",
                         (crypto.seal(text), embed.pack(nv) if nv is not None else None, a["id"]))
            log.info("memory %s updated", a["id"])
        elif op == "skip":
            continue
        else:
            out.append((t, embed.pack(v)))
    return out


def embed_tick(conn, batch: int = 48) -> int:
    """Агент, в фоне: отпечатки для памяти, событий и итогов разговоров, у которых их ещё нет."""
    from . import embed  # noqa: PLC0415

    if not embed.available():
        return 0
    jobs = [
        ("memories", conn.execute("SELECT id, text FROM memories WHERE emb IS NULL AND archived_at IS NULL "
                                  "ORDER BY id DESC LIMIT %s", (batch,)).fetchall(), lambda r: r["text"]),
        ("day_events", conn.execute("SELECT id, title, summary FROM day_events WHERE emb IS NULL AND summary IS NOT NULL "
                                    "ORDER BY id DESC LIMIT %s", (batch,)).fetchall(),
         lambda r: f"{r['title'] or ''}: {r['summary'] or ''}"),
        ("conversations", conn.execute("SELECT id, title, summary FROM conversations WHERE emb IS NULL "
                                       "AND summary IS NOT NULL AND status = 'done' ORDER BY id DESC LIMIT %s",
                                       (batch,)).fetchall(), lambda r: f"{r['title'] or ''}: {r['summary'] or ''}"),
    ]
    done = 0
    for table, rows, text in jobs:
        if not rows:
            continue
        vecs = embed.vectors(conn, [text(r) for r in rows])
        if vecs is None:
            return done
        for r, v in zip(rows, vecs):
            conn.execute(f"UPDATE {table} SET emb = %s WHERE id = %s", (embed.pack(v), r["id"]))  # noqa: S608
        done += len(rows)
    return done
