"""NeuroSoft Agent — итоги разговоров, задачи, память, кнопка на кулоне, экспорт и уборка."""
from __future__ import annotations

import logging
import time
from collections import Counter
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import requests

from neuro import config, db, llm
from neuro import agent_core, diary, events, extensions, i18n, people, retention
from neuro import tasks as tasklib
from neuro.assistant import (TASK_SCHEMA, TZ, clean_list, clean_tasks, insert_memories, insert_tasks,
                             record_question, transcript_text)

log = logging.getLogger("neuro.agent")
MAX_ATTEMPTS = 3

SUMMARY_SYSTEM = f"""Ты — ассистент Mnemo. Тебе дают расшифровку разговора, записанного носимым микрофоном владельца.
Расшифровка автоматическая и может содержать ошибки распознавания.
Верни строго JSON-объект без пояснений и без markdown:
{{"title": "название разговора, до 8 слов",
 "emoji": "одно эмодзи, которое лучше всего передаёт тему разговора",
 "summary": "о чём говорили и к чему пришли, 2–6 предложений; называй людей по именам, если они известны",
 "tasks": [{tasklib.SCHEMA}],
 "memories": ["важные факты, решения и договорённости, которые стоит помнить долго"],
 {people.SUMMARY_PEOPLE_FIELDS}}}
{tasklib.RULES}
Нет задач — пустой список. Если запись пустая или это шум и обрывки, title = "-", summary = "", списки пустые.
{people.SUMMARY_PEOPLE_RULES}"""

REMEMBER_SYSTEM = f"""Владелец кулона дважды нажал кнопку «запомни это». Тебе дан фрагмент расшифровки перед нажатием
(может содержать ошибки распознавания). Верни строго JSON:
{{"memory": "что именно стоит запомнить, 1–2 предложения", "tasks": [{tasklib.SCHEMA}]}}
{tasklib.RULES}"""


def first_words(segs: list[dict], n: int = 7, lang: str = i18n.DEFAULT) -> str:
    words = " ".join(s["text"] for s in segs).split()
    title = " ".join(words[:n])
    return (title + "…") if len(words) > n else (title or i18n.t(lang, "conversation.empty"))


# ---------------------------------------------------------------------------
# 1. Итоги разговоров
# ---------------------------------------------------------------------------
READY_CONVERSATIONS_SQL = """
SELECT c.id, c.attempts FROM conversations c
WHERE c.status = 'open'
  AND c.last_activity < now() - %(idle)s * interval '1 second'
  AND c.updated_at < now() - c.attempts * interval '2 minutes'
  -- итоги — после того как STT решил, кто говорил (имена в итогах), но не дольше 10 минут
  AND (c.voices_done OR c.last_activity < now() - interval '10 minutes')
  AND NOT EXISTS (
      SELECT 1 FROM audio_chunks a
      WHERE a.device_id = c.device_id AND a.status IN ('pending', 'processing')
        AND a.started_at <= c.last_activity + %(gap)s * interval '1 second')
ORDER BY c.last_activity
LIMIT 5
"""


def summarize_conversations(conn):
    rows = conn.execute(
        READY_CONVERSATIONS_SQL, {"idle": config.CONVERSATION_IDLE_SEC, "gap": config.CONVERSATION_GAP_SEC}
    ).fetchall()
    for row in rows:
        claimed = conn.execute(
            """UPDATE conversations SET status = 'processing', attempts = attempts + 1, updated_at = now()
               WHERE id = %s AND status = 'open' RETURNING attempts""",
            (row["id"],),
        ).fetchone()
        if not claimed:
            continue
        try:
            summarize_one(conn, row["id"])
        except Exception as exc:  # noqa: BLE001
            log.exception("conversation %s: summary failed", row["id"])
            status = "failed" if claimed["attempts"] >= MAX_ATTEMPTS else "open"
            conn.execute(
                """UPDATE conversations SET status = CASE WHEN status = 'processing' THEN %s ELSE status END,
                       error = %s, updated_at = now() WHERE id = %s""",
                (status, str(exc)[:500], row["id"]),
            )


def summarize_one(conn, conv_id: int):
    segs = conn.execute(
        people.SEGMENT_SELECT + " WHERE s.conversation_id = %s ORDER BY s.started_at, s.id", (conv_id,)
    ).fetchall()
    tasks: list[dict] = []
    memories: list[str] = []
    topics: list[str] = []
    summary = None
    emoji = "💬"
    text_len = sum(len(s["text"]) for s in segs if not s.get("person_ignored"))
    lang = i18n.lang(conn)
    if not segs:
        title = i18n.t(lang, "conversation.empty")
    elif llm.configured() and text_len >= 40:
        result = llm.chat_json(conn, "summary", SUMMARY_SYSTEM + "\n" + i18n.write_in(lang),
                               tasklib.context(conn, segs[0]["started_at"]) + "\n\n" + transcript_text(segs))
        title = str(result.get("title") or "").strip()
        if not title or title == "-":
            title = i18n.t(lang, "conversation.empty") if not result.get("summary") else first_words(segs, lang=lang)
        title = title[:200]
        emoji = str(result.get("emoji") or "💬").strip()[:8] or "💬"
        summary = str(result.get("summary") or "").strip() or None
        memories = clean_list(result.get("memories"))
        try:
            topics = people.apply_summary(conn, conv_id, segs, result, [])
        except Exception:  # noqa: BLE001 — люди и темы не должны мешать итогам
            log.exception("conversation %s: people/topics failed", conv_id)
        tasks = tasklib.classify(conn, result.get("tasks"), people.labels_map(segs), tasklib.owner_spoke(conn, segs))
    else:
        title = first_words(segs, lang=lang)

    with conn.transaction():
        conn.execute(
            """DELETE FROM tasks WHERE conversation_id = %s AND source = 'conversation'
                   AND status IN ('open', 'suggested') AND exported_at IS NULL""",
            (conv_id,),
        )
        conn.execute("DELETE FROM memories WHERE conversation_id = %s AND source = 'conversation'", (conv_id,))
        tasklib.save(conn, conv_id, tasks, "conversation")
        insert_memories(conn, conv_id, memories, "conversation")
        # Если пока мы думали, пришли новые фразы, STT вернул статус 'open' — тогда разговор пересчитаем позже.
        conn.execute(
            """UPDATE conversations SET title = %s, summary = %s, emoji = %s, topics = %s, error = NULL,
                   updated_at = now(), status = CASE WHEN status = 'processing' THEN 'done' ELSE status END
               WHERE id = %s""",
            (title, summary, emoji, topics, conv_id),
        )
    log.info("conversation %s: '%s', %d tasks, %d memories", conv_id, title, len(tasks), len(memories))


# ---------------------------------------------------------------------------
# 2. Кнопка на кулоне
#    1 = одиночное нажатие, если голосовой вопрос не удалось задать сразу -> ответ позже
#    2 = двойное нажатие   -> «запомни это»: последние REMEMBER_WINDOW_SEC секунд
# ---------------------------------------------------------------------------
BUTTON_COMMAND = 1
BUTTON_REMEMBER = 2


def process_events(conn):
    rows = conn.execute(
        """SELECT id, device_id, kind, value, ts, created_at FROM events
           WHERE status = 'pending' AND created_at < now() - %s * interval '1 second'
           ORDER BY ts LIMIT 20""",
        (config.EVENT_SETTLE_SEC,),
    ).fetchall()
    now = datetime.now(timezone.utc)
    for ev in rows:
        try:
            if ev["kind"] != "button" or ev["value"] not in (BUTTON_COMMAND, BUTTON_REMEMBER):
                set_event(conn, ev["id"], "done", "ignored")
                continue
            if ev["value"] == BUTTON_COMMAND:
                start, end = ev["ts"], ev["ts"] + timedelta(seconds=config.COMMAND_WINDOW_SEC)
                if ev["created_at"] > now - timedelta(seconds=config.EVENT_SETTLE_SEC + config.COMMAND_WINDOW_SEC):
                    continue  # ждём, пока команда договорится и расшифруется
            else:
                start, end = ev["ts"] - timedelta(seconds=config.REMEMBER_WINDOW_SEC), ev["ts"] + timedelta(seconds=3)
            waiting = conn.execute(
                """SELECT 1 FROM audio_chunks WHERE device_id = %s AND status IN ('pending', 'processing')
                   AND started_at <= %s LIMIT 1""",
                (ev["device_id"], end),
            ).fetchone()
            if waiting and ev["created_at"] > now - timedelta(minutes=30):
                continue
            segs = conn.execute(
                people.SEGMENT_SELECT + """ WHERE s.device_id = %s AND s.ended_at >= %s AND s.started_at <= %s
                                           ORDER BY s.started_at""",
                (ev["device_id"], start, end),
            ).fetchall()
            if ev["value"] == BUTTON_REMEMBER:
                handle_remember(conn, ev, segs)
            else:
                handle_command(conn, ev, segs)
        except Exception as exc:  # noqa: BLE001
            log.exception("event %s failed", ev["id"])
            set_event(conn, ev["id"], "failed", str(exc)[:500])


def set_event(conn, event_id: int, status: str, result: str | None):
    conn.execute("UPDATE events SET status = %s, result = %s WHERE id = %s", (status, result, event_id))


def _conversation_of(segs: list[dict]):
    ids = [s["conversation_id"] for s in segs if s["conversation_id"]]
    return Counter(ids).most_common(1)[0][0] if ids else None


def handle_remember(conn, ev: dict, segs: list[dict]):
    conv_id = _conversation_of(segs)
    when = ev["ts"].astimezone(TZ).strftime("%d.%m %H:%M")
    tasks: list[dict] = []
    if not segs:
        memory = i18n.t(i18n.lang(conn), "memory.mark", when=when)
    elif llm.configured():
        result = llm.chat_json(conn, "remember", REMEMBER_SYSTEM + "\n" + i18n.write_in(i18n.lang(conn)),
                               tasklib.context(conn, ev["ts"]) + "\n\n" + transcript_text(segs))
        memory = str(result.get("memory") or "").strip() or " ".join(s["text"] for s in segs)
        tasks = tasklib.classify(conn, result.get("tasks"), people.labels_map(segs), None, source="button")
    else:
        memory = " ".join(s["text"] for s in segs)
    with conn.transaction():
        insert_memories(conn, conv_id, [f"{memory}"], "button")
        tasklib.save(conn, conv_id, tasks, "button")
        set_event(conn, ev["id"], "done", memory[:500])
    log.info("remember @%s: %s", when, memory[:120])


def handle_command(conn, ev: dict, segs: list[dict]):
    """Одно нажатие, когда телефон не смог задать вопрос сразу (не было сети): отвечаем позже,
    ответ и созданные задачи появятся в приложении."""
    if not segs:
        set_event(conn, ev["id"], "done", "вопрос не распознан")
        return
    spoken = " ".join(s["text"] for s in segs)
    if llm.configured():
        result = agent_core.ask(conn, spoken, level="tools", channel="chat", device_id=ev["device_id"])
        reply = result["answer"]
        record_question(conn, ev["device_id"], None, spoken, reply, None, result["latency_ms"], via="offline")
    else:
        insert_tasks(conn, None, [{"text": spoken[:1000], "due": None, "assignee": None, "target": "notion"}],
                     "button")
        reply = spoken
    set_event(conn, ev["id"], "done", reply[:500])
    log.info("offline question: %s -> %s", spoken[:80], reply[:80])


# ---------------------------------------------------------------------------
# 2б. «Напомни… / создай задачу…» в любом разговоре — задача через минуту, не дожидаясь конца разговора
# ---------------------------------------------------------------------------
def quick_tasks(conn):
    rows = conn.execute(
        people.SEGMENT_SELECT + """ WHERE NOT s.task_checked AND s.started_at < now() - interval '45 seconds'
                                   ORDER BY s.started_at LIMIT 300""").fetchall()
    if not rows:
        return
    conn.execute("UPDATE segments SET task_checked = true WHERE id = ANY(%s)", ([r["id"] for r in rows],))
    if not llm.configured():
        return
    owner_known = conn.execute("SELECT 1 FROM people WHERE is_owner").fetchone() is not None
    done_until = None
    for seg in rows:
        if not tasklib.TRIGGER.search(seg["text"] or ""):
            continue
        # только голос владельца: чужое «напомни» (собеседник, телевизор) задачей не становится
        if owner_known and seg["person_id"] and not seg["person_owner"]:
            continue
        if done_until and seg["started_at"] <= done_until:
            continue
        window = conn.execute(
            people.SEGMENT_SELECT + """ WHERE s.device_id = (SELECT device_id FROM segments WHERE id = %s)
                                       AND s.started_at BETWEEN %s AND %s ORDER BY s.started_at""",
            (seg["id"], seg["started_at"] - timedelta(seconds=60), seg["started_at"] + timedelta(seconds=45))).fetchall()
        done_until = seg["started_at"] + timedelta(seconds=45)
        try:
            result = llm.chat_json(conn, "remember", tasklib.QUICK_SYSTEM + "\n" + i18n.write_in(i18n.lang(conn)),
                                   tasklib.context(conn, seg["started_at"]) + "\n\n" + transcript_text(window))
        except Exception:  # noqa: BLE001
            log.exception("quick task from segment %s failed", seg["id"])
            continue
        found = tasklib.classify(conn, result.get("tasks"), people.labels_map(window), None, source="voice")
        res = tasklib.save(conn, seg["conversation_id"], found, "voice")
        log.info("quick task from «%s»: %s", (seg["text"] or "")[:60], res)


# ---------------------------------------------------------------------------
# 3. Экспорт задач в Notion / GitHub (включается AUTO_EXPORT=true)
# ---------------------------------------------------------------------------
def notion_ready() -> bool:
    return bool(config.NOTION_TOKEN and config.NOTION_DATABASE_ID)


def github_ready() -> bool:
    return bool(config.GITHUB_TOKEN and config.GITHUB_REPO)


def task_details(t: dict) -> str:
    parts = []
    if t.get("due"):
        parts.append(f"Срок: {t['due']}")
    if t.get("assignee"):
        parts.append(f"Исполнитель: {t['assignee']}")
    if t.get("conversation_title"):
        parts.append(f"Из разговора: {t['conversation_title']}")
    parts.append("Mnemo")
    return "\n".join(parts)


def export_notion(t: dict) -> str:
    r = requests.post(
        "https://api.notion.com/v1/pages",
        headers={"Authorization": f"Bearer {config.NOTION_TOKEN}", "Notion-Version": "2022-06-28"},
        json={
            "parent": {"database_id": config.NOTION_DATABASE_ID},
            "properties": {config.NOTION_TITLE_PROPERTY: {"title": [{"text": {"content": t["text"][:1900]}}]}},
            "children": [{
                "object": "block", "type": "paragraph",
                "paragraph": {"rich_text": [{"type": "text", "text": {"content": task_details(t)}}]},
            }],
        },
        timeout=30,
    )
    if r.status_code >= 400:
        raise RuntimeError(f"notion {r.status_code}: {r.text[:300]}")
    return r.json().get("url", "")


def export_github(t: dict) -> str:
    r = requests.post(
        f"https://api.github.com/repos/{config.GITHUB_REPO}/issues",
        headers={"Authorization": f"Bearer {config.GITHUB_TOKEN}", "Accept": "application/vnd.github+json"},
        json={"title": t["text"][:250], "body": task_details(t)},
        timeout=30,
    )
    if r.status_code >= 400:
        raise RuntimeError(f"github {r.status_code}: {r.text[:300]}")
    return r.json().get("html_url", "")


def export_tasks(conn):
    if not config.AUTO_EXPORT or not (notion_ready() or github_ready()):
        return
    rows = conn.execute(
        """SELECT t.id, t.text, t.due, t.assignee, t.target, c.title AS conversation_title
           FROM tasks t LEFT JOIN conversations c ON c.id = t.conversation_id
           WHERE t.exported_at IS NULL AND t.export_error IS NULL AND t.status = 'open'
             AND (%s OR t.target = 'github')
           ORDER BY t.id LIMIT 20""",
        (notion_ready(),),
    ).fetchall()
    for t in rows:
        try:
            ref = export_github(t) if (t["target"] == "github" and github_ready()) else export_notion(t)
            conn.execute("UPDATE tasks SET exported_at = now(), export_ref = %s WHERE id = %s", (ref, t["id"]))
            log.info("task %s exported: %s", t["id"], ref)
        except Exception as exc:  # noqa: BLE001
            log.warning("task %s export failed: %s", t["id"], exc)
            conn.execute("UPDATE tasks SET export_error = %s WHERE id = %s", (str(exc)[:500], t["id"]))


def sync_task_integrations(conn):
    """Лента задач -> подключённые сервисы. Каждая новая задача уходит во все включённые расширения
    с хуком on_task (Notion, GitHub Issues, Telegram и любые другие из маркетплейса)."""
    rows = conn.execute("SELECT id, manifest, settings, installed_at FROM extensions WHERE enabled").fetchall()
    sinks = [r for r in rows if r["manifest"].get("on_task") and extensions.is_ready(r)]
    if not sinks:
        return
    for sink in sinks:
        tasks = conn.execute(
            """SELECT t.id, t.text, t.due, t.assignee, t.source, c.title AS conversation
               FROM tasks t LEFT JOIN conversations c ON c.id = t.conversation_id
               WHERE t.status = 'open' AND t.created_at >= %s
                 AND (%s::text IS NULL OR t.target = %s)
                 AND NOT EXISTS (SELECT 1 FROM task_exports e WHERE e.task_id = t.id AND e.ext_id = %s)
               ORDER BY t.id LIMIT 10""",
            (sink["installed_at"], sink["manifest"]["on_task"].get("only_target"),
             sink["manifest"]["on_task"].get("only_target"), sink["id"])).fetchall()
        for t in tasks:
            args = {"text": t["text"], "due": t["due"] or "", "assignee": t["assignee"] or "",
                    "source": {"conversation": "из разговора", "button": "с кнопки", "agent": "от агента",
                               "voice": "голосом"}.get(t["source"], t["source"]),
                    "conversation": t["conversation"] or ""}
            ok, ref = extensions.call_hook(sink, args)
            conn.execute(
                """INSERT INTO task_exports (task_id, ext_id, ok, ref, error) VALUES (%s, %s, %s, %s, %s)
                   ON CONFLICT (task_id, ext_id) DO NOTHING""",
                (t["id"], sink["id"], ok, ref if ok else None, None if ok else ref))
            log.info("task %s -> %s: %s", t["id"], sink["id"], "ok" if ok else ref[:100])


# ---------------------------------------------------------------------------
# 4. Уборка
# ---------------------------------------------------------------------------
def cleanup(conn):
    conn.execute("DELETE FROM received_batches WHERE received_at < now() - interval '7 days'")
    retention.cleanup(conn)  # срок хранения звука выбирается в приложении


def main():
    db.wait_and_migrate()
    log.info("agent started, llm=%s, auto_export=%s (notion=%s, github=%s)",
             config.LLM_BASE_URL if llm.configured() else "not configured",
             config.AUTO_EXPORT, notion_ready(), github_ready())
    last_cleanup = 0.0
    while True:
        try:
            with db.connect() as conn:
                while True:
                    quick_tasks(conn)
                    summarize_conversations(conn)
                    process_events(conn)
                    export_tasks(conn)
                    sync_task_integrations(conn)
                    try:
                        events.tick(conn)  # фрагменты -> события, мусор -> корзина, ночью — разбор дня
                    except Exception:  # noqa: BLE001
                        log.exception("events tick failed")
                    if llm.configured():
                        diary.tick(conn)
                    if time.monotonic() - last_cleanup > 600:
                        cleanup(conn)
                        last_cleanup = time.monotonic()
                    time.sleep(10)
        except Exception:  # noqa: BLE001
            log.exception("agent loop error, retrying in 10s")
            time.sleep(10)


if __name__ == "__main__":
    main()
