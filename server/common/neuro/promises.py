"""Обещания: черновики сообщений, чтобы договорённость дошла до конца.

- nudge — «Иван обещал прислать договор до пятницы»: вежливое короткое напоминание ему от имени пользователя;
- followup — письмо по итогам встречи: спасибо, о чём договорились (кто, что, к какому сроку), следующие шаги.
Черновик пользователь видит, правит и сам отправляет (почта, мессенджер) — от его имени ничего не уходит.
"""
from __future__ import annotations

from . import i18n, llm, profile
from .assistant import TZ, transcript_text

NUDGE_SYSTEM = """Напиши короткое дружелюбное сообщение в мессенджер от имени пользователя человеку, который
ему что-то пообещал: напомни, о чём договорились, и спроси, как дела с этим. 1–3 предложения, без
официоза и без давления, обращение по имени, если оно известно. Только текст сообщения."""

FOLLOWUP_SYSTEM = """Напиши письмо по итогам встречи от имени пользователя участникам: короткое спасибо, о чём
договорились — списком «кто — что — к какому сроку» (только реальные договорённости из расшифровки и списка),
следующий шаг. Тон — деловой, тёплый, без воды; не выдумывай фактов, сроков и имён. Верни строго JSON:
{"subject": "тема письма", "body": "текст письма"}"""


def nudge(conn, task: dict) -> str:
    who = task.get("counterpart") or task.get("assignee") or ""
    user = (f"Кому: {who or 'собеседнику'}\nЧто обещал: {task['text']}\n"
            + (f"Срок: {task['due_at'].astimezone(TZ):%d.%m %H:%M}\n" if task.get("due_at") else
               f"Срок: {task['due']}\n" if task.get("due") else "")
            + (f"Как договорились (цитата): {task['evidence']}\n" if task.get("evidence") else ""))
    msg = llm.chat(conn, "remember", [
        {"role": "system", "content": NUDGE_SYSTEM + "\n" + profile.prompt(conn) + "\n" + i18n.write_in(i18n.lang(conn))},
        {"role": "user", "content": user}], timeout=40)
    return (msg.get("content") or "").strip().strip('"«»')


def followup(conn, conv_id: int) -> dict:
    from .people import SEGMENT_SELECT  # noqa: PLC0415

    conv = conn.execute("SELECT id, started_at, title, summary FROM conversations WHERE id = %s", (conv_id,)).fetchone()
    if conv is None:
        raise ValueError("нет такого разговора")
    segs = conn.execute(SEGMENT_SELECT + " WHERE s.conversation_id = %s AND NOT s.junk ORDER BY s.started_at",
                        (conv_id,)).fetchall()
    tasks = conn.execute("""SELECT text, kind, due, due_at, assignee, counterpart FROM tasks
                            WHERE conversation_id = %s AND status IN ('open', 'done')""", (conv_id,)).fetchall()
    lines = []
    for t in tasks:
        who = "я" if t["kind"] in ("mine", "reminder", "meeting") else (t["assignee"] or t["counterpart"] or "собеседник")
        when = f"{t['due_at'].astimezone(TZ):%d.%m}" if t["due_at"] else (t["due"] or "без срока")
        lines.append(f"- {who}: {t['text']} ({when})")
    user = (f"Встреча: {conv['title'] or ''}, {conv['started_at'].astimezone(TZ):%d.%m.%Y}\n"
            f"Итог: {conv['summary'] or ''}\nДоговорённости:\n" + ("\n".join(lines) or "- (не найдены)")
            + "\n\nРасшифровка:\n" + transcript_text(segs, limit=12000))
    res = llm.chat_json(conn, "summary", FOLLOWUP_SYSTEM + "\n" + profile.prompt(conn) + "\n"
                        + i18n.write_in(i18n.lang(conn)), user, timeout=90, usage="followup")
    return {"subject": str(res.get("subject") or conv["title"] or "").strip()[:200],
            "body": str(res.get("body") or "").strip()[:6000]}


def context(conn, task_id: int, around: int = 3) -> dict | None:
    """Откуда обещание: разговор (название, когда), несколько фраз вокруг фразы-доказательства — кто что сказал,
    и итог разговора. Для карточки обещания и уведомлений."""
    from .people import SEGMENT_SELECT, speaker_label  # noqa: PLC0415
    from .tasks import _evidence_segment  # noqa: PLC0415

    t = conn.execute("SELECT id, conversation_id, evidence, created_at FROM tasks WHERE id = %s", (task_id,)).fetchone()
    if t is None:
        return None
    out: dict = {"conversation": None, "lines": [], "said_by": None}
    if not t["conversation_id"]:
        return out
    conv = conn.execute("""SELECT id, title, summary, started_at, last_activity FROM conversations
                           WHERE id = %s AND trashed_at IS NULL""", (t["conversation_id"],)).fetchone()
    if conv is None:
        return out
    out["conversation"] = {"id": conv["id"], "title": conv["title"], "summary": (conv["summary"] or "")[:600],
                           "started_at": conv["started_at"].isoformat(), "ended_at": conv["last_activity"].isoformat()}
    segs = [s for s in conn.execute(SEGMENT_SELECT + " WHERE s.conversation_id = %s ORDER BY s.started_at, s.id",
                                    (conv["id"],)).fetchall() if not s["junk"]]
    hit = _evidence_segment(t["evidence"], segs) if t["evidence"] and segs else None
    if hit is None:
        return out
    i = next(k for k, s in enumerate(segs) if s["id"] == hit["id"])
    for s in segs[max(0, i - around): i + around + 1]:
        out["lines"].append({"id": s["id"], "who": speaker_label(s), "text": s["text"],
                             "at": s["started_at"].isoformat(), "hit": s["id"] == hit["id"]})
    out["said_by"] = speaker_label(hit)
    return out
