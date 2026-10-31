"""Агент Mnemo с уровнями работы (harness).

Уровень 1 «быстро»       — голос с кулона: ответ за секунды, до 3 вызовов инструментов.
Уровень 2 «основательно» — инструменты: поиск, задачи, память, разговоры, расширения (до 8 шагов).
Уровень 3 «глубоко»      — сначала план, потом многошаговая работа на сильной модели (до 16 шагов).

Каждый шаг пишется в agent_steps коротко («🔎 Ищу: …») — приложение показывает их в чате.
"""
from __future__ import annotations

import json
import logging
import re
import time
from datetime import datetime, timedelta, timezone

from . import agent_context, claude_agent, config, crypto, extensions, i18n, llm, people
from .assistant import TZ, WEEKDAYS, build_context, clean_tasks, insert_memories, insert_tasks, transcript_text

log = logging.getLogger("neuro.agent_core")

LEVELS = {
    "fast": {"title": "Быстро", "task": "ask", "max_steps": 3, "reasoning": "low", "plan": False},
    "tools": {"title": "Основательно", "task": "agent", "max_steps": 8, "reasoning": "medium", "plan": False},
    "deep": {"title": "Глубоко", "task": "agent_deep", "max_steps": 16, "reasoning": "high", "plan": True},
}

# Подсказки для автовыбора уровня: русский, английский, турецкий, испанский.
DEEP_WORDS = re.compile(
    r"\b(глубок|подробн|исследу|проанализ|анализ|сравни|разбер|составь план|стратеги|отч[её]т о|обзор|изучи"
    r"|in[- ]depth|research|analy[sz]|compare|comparison|strategy|deep dive|report on|overview of|study"
    r"|derinlemesine|araştır|analiz|karşılaştır|strateji|rapor"
    r"|a fondo|investiga|anali[zc]|compara|estrategia|informe sobre|resumen detallado)", re.I)
TOOL_WORDS = re.compile(
    r"\b(найди|поищи|загугли|узнай|посмотри|новост|курс|погод|созда|добав|напомн|запомни|задач|"
    r"что (я|мы) (говорил|обсуждал)|о ч[её]м (я|мы)|отправ|напиши в|с кем|кто (говорил|сказал|был)|что (сказал|говорил)"
    r"|find|search|look up|google|news|weather|exchange rate|create|add|remind|remember|task|todo|send"
    r"|what did (i|we) (say|discuss)|who (said|was)|with whom|talk(ed)? (to|with)"
    r"|bul|ara|haber|hava durumu|kur|oluştur|ekle|hatırlat|hatırla|görev|gönder|kim(inle)? (söyledi|dedi|konuştu)"
    r"|busca|encuentra|noticias|clima|tiempo|tipo de cambio|crea|añade|recuérda|recuerda|tarea|envía"
    r"|quién (dijo|era)|con quién)", re.I)


LIFE_WORDS = re.compile(
    r"\b(вчера|сегодня|позавчера|утром|вечером|недел|в (понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье)"
    r"|я |мы |мне |мой |моя |мои |наш|где (я|мы) был|о ч[её]м|что было|кто был|договорил|обещал|обсуждал"
    r"|yesterday|today|this morning|last week|did i|did we|where was i|what happened"
    r"|dün|bugün|ayer|hoy)", re.I)


def route(text: str, channel: str) -> str:
    """Автовыбор уровня без лишнего запроса к модели."""
    if DEEP_WORDS.search(text):
        return "deep"
    if channel == "voice":
        return "fast"  # инструменты доступны и на быстром уровне
    if channel != "voice" and LIFE_WORDS.search(text):
        return "tools"  # вопросы о жизни владельца — с инструментами (события, люди, расшифровки)
    return "tools" if TOOL_WORDS.search(text) or len(text) > 160 else "fast"


# ---------------------------------------------------------------- встроенные инструменты
def _fn(name, description, props=None, required=None):
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object", "properties": props or {}, "required": required or []}}}


BUILTIN_TOOLS = [
    _fn("web_search", "Поиск в интернете: свежие факты, новости, цены, погода, справки. Возвращает выжимку с источниками.",
        {"query": {"type": "string", "description": "поисковый запрос"}}, ["query"]),
    _fn("create_task", "Создать задачу владельцу.",
        {"text": {"type": "string"}, "due": {"type": "string", "description": "срок словами, если назван"},
         "assignee": {"type": "string"}}, ["text"]),
    _fn("list_tasks", "Список задач владельца.",
        {"status": {"type": "string", "enum": ["open", "done", "all"]}}),
    _fn("complete_task", "Отметить задачу выполненной по её id.", {"id": {"type": "integer"}}, ["id"]),
    _fn("save_memory", "Запомнить важный факт или договорённость надолго.", {"text": {"type": "string"}}, ["text"]),
    _fn("search_memory", "Искать в памяти, итогах и расшифровках прошлых разговоров с кулона.",
        {"query": {"type": "string"}, "days": {"type": "integer", "description": "за сколько дней, по умолчанию 30"}},
        ["query"]),
    _fn("recent_conversation", "Расшифровка того, что кулон записал за последние N минут.",
        {"minutes": {"type": "integer", "description": "по умолчанию 15"}}),
    _fn("list_conversations", "Список разговоров с итогами за последние дни.",
        {"days": {"type": "integer", "description": "по умолчанию 1"}}),
    _fn("people", "Люди, которых кулон узнаёт по голосу: имена, сколько и когда разговаривали с владельцем."),
    _fn("person_conversations", "Разговоры и задачи с конкретным человеком (по имени или подписи «Голос N»).",
        {"name": {"type": "string"}, "days": {"type": "integer", "description": "за сколько дней, по умолчанию 60"}},
        ["name"]),
    _fn("day_events", "События дня по порядку: время, название, с кем, итог. Для вопросов «что было вчера/в пятницу».",
        {"day": {"type": "string", "description": "YYYY-MM-DD, «сегодня» или «вчера»"}}, ["day"]),
    _fn("event_details", "Всё о событии: итог, люди, темы, место, части и расшифровка. id — из day_events.",
        {"id": {"type": "integer"}}, ["id"]),
    _fn("conversation_details", "Всё о разговоре: итог, люди, задачи и расшифровка. id — из списков разговоров.",
        {"id": {"type": "integer"}}, ["id"]),
    _fn("places", "Где был владелец в этот день (по геолокации) и сколько времени.",
        {"day": {"type": "string", "description": "YYYY-MM-DD, «сегодня» или «вчера»"}}, ["day"]),
    _fn("diary", "Сводка дня из дневника: история дня, настроение, главные моменты.",
        {"day": {"type": "string", "description": "YYYY-MM-DD, «сегодня» или «вчера»"}}, ["day"]),
    _fn("ask_user", "Задать владельцу один короткий уточняющий вопрос, когда без ответа нельзя ответить точно "
        "(подходит несколько событий или людей, неясен период). Не спрашивай то, что можно найти инструментами.",
        {"question": {"type": "string", "description": "вопрос, при возможности с 2–4 вариантами"}}, ["question"]),
]

AGENTS = {
    "mnemo": None,          # модель — по уровню (Быстро / Основательно / Глубоко)
    "gpt": "agent_gpt",     # тот же агент Mnemo, но все шаги — на модели GPT (выбирается в «Моделях»)
}


class Cancelled(Exception):
    pass


def cancel(conn, run_id: int) -> bool:
    """Остановить запуск: агент заметит на следующем шаге и ответит «Остановлено»."""
    res = conn.execute("UPDATE agent_runs SET status = 'cancelled' WHERE id = %s AND status = 'running'", (run_id,))
    return bool(res.rowcount)


def _check(conn, run_id: int):
    row = conn.execute("SELECT status FROM agent_runs WHERE id = %s", (run_id,)).fetchone()
    if row and row["status"] == "cancelled":
        raise Cancelled()


def tool_title(lang: str, name: str, args: dict) -> str:
    """Короткая строка шага для чата: «🔎 Ищу в интернете: …» на языке приложения."""
    if "tool." + name not in i18n.PHRASES:
        return f"⚙️ {name}"
    return i18n.t(lang, "tool." + name, query=args.get("query", ""), text=args.get("text", ""), id=args.get("id"),
                  name=args.get("name", ""), day=args.get("day", ""))


def web_search(conn, query: str) -> str:
    system = ("Найди в интернете ответ на запрос и кратко изложи главное (до 8 предложений). "
              "В конце перечисли 2–5 источников в формате «Источник: адрес». " + i18n.write_in(i18n.lang(conn)))
    messages = [{"role": "system", "content": system}, {"role": "user", "content": query}]
    try:
        msg = llm.chat(conn, "search", messages, tools=[{"type": "openrouter:web_search"}], timeout=60)
    except RuntimeError as exc:
        if "400" not in str(exc):
            raise
        msg = llm.chat(conn, "search", messages, extra={"plugins": [{"id": "web", "max_results": 5}]}, timeout=60)
    text = (msg.get("content") or "").strip()
    urls = [a.get("url_citation", {}).get("url") or a.get("url") for a in msg.get("annotations") or []]
    urls = [u for u in dict.fromkeys(urls) if u]
    if urls and "Источник" not in text:
        text += "\n" + "\n".join(f"Источник: {u}" for u in urls[:5])
    return text or "Ничего не найдено."


def run_builtin(conn, name: str, args: dict, device_id: str | None) -> str:
    if name == "web_search":
        return web_search(conn, str(args.get("query") or ""))
    if name == "create_task":
        t = clean_tasks([args])
        insert_tasks(conn, None, t, "agent")
        return f"Задача создана: {t[0]['text']}" if t else "Пустая задача не создана."
    if name == "list_tasks":
        status = args.get("status") or "open"
        rows = conn.execute(
            """SELECT id, text, due, status FROM tasks WHERE (%s = 'all' OR status = %s)
               ORDER BY created_at DESC LIMIT 30""", (status, status)).fetchall()
        return "\n".join(f"#{r['id']} [{r['status']}] {r['text']}" + (f" (срок: {r['due']})" if r["due"] else "")
                         for r in rows) or "Задач нет."
    if name == "complete_task":
        res = conn.execute("UPDATE tasks SET status = 'done', updated_at = now() WHERE id = %s", (int(args["id"]),))
        return "Готово." if res.rowcount else "Такой задачи нет."
    if name == "save_memory":
        insert_memories(conn, None, [str(args.get("text") or "").strip()], "agent")
        return "Запомнил."
    if name == "search_memory":
        # тексты в базе зашифрованы — ищем после расшифровки (без учёта регистра)
        ql = str(args.get("query") or "").strip().lower()
        since = datetime.now(timezone.utc) - timedelta(days=int(args.get("days") or 30))

        def has(*vals):
            return bool(ql) and any(v and ql in v.lower() for v in vals)
        mems = [m for m in conn.execute(
            "SELECT created_at, text FROM memories WHERE archived_at IS NULL ORDER BY created_at DESC LIMIT 3000").fetchall() if has(m["text"])][:10]
        convs = [c for c in conn.execute(
            """SELECT started_at, title, summary FROM conversations WHERE started_at > %s
               ORDER BY started_at DESC LIMIT 3000""", (since,)).fetchall() if has(c["title"], c["summary"])][:10]
        segs = [s for s in conn.execute(
            people.SEGMENT_SELECT + """ WHERE s.started_at > %s AND NOT s.junk
                                       ORDER BY s.started_at DESC LIMIT 20000""", (since,)).fetchall()
                if has(s["text"])][:15]
        evs = [e for e in conn.execute(
            """SELECT id, started_at, title, summary, topics FROM day_events WHERE started_at > %s
               ORDER BY started_at DESC LIMIT 2000""", (since,)).fetchall()
               if has(e["title"], e["summary"], " ".join(e["topics"] or []))][:10]
        out = [f"[событие #{e['id']} {_d(e['started_at'])}] {e['title']}: {e['summary'] or ''}" for e in evs]
        out += [f"[память {_d(m['created_at'])}] {m['text']}" for m in mems]
        out += [f"[разговор {_d(c['started_at'])}] {c['title']}: {c['summary'] or ''}" for c in convs]
        out += [f"[фраза {_d(s['started_at'])}] {people.speaker_label(s)}: {s['text']}" for s in segs]
        return "\n".join(out) or "Ничего не найдено. Попробуй другие слова (поиск по точному совпадению)."
    if name == "recent_conversation":
        minutes = max(1, min(int(args.get("minutes") or 15), 240))
        rows = conn.execute(
            people.SEGMENT_SELECT + """ WHERE s.started_at > %s AND (%s::text IS NULL OR s.device_id = %s)
                                       ORDER BY s.started_at""",
            (datetime.now(timezone.utc) - timedelta(minutes=minutes), device_id, device_id)).fetchall()
        return transcript_text(rows, limit=12000) or "За это время записи нет."
    if name == "list_conversations":
        days = max(1, min(int(args.get("days") or 1), 30))
        rows = conn.execute(
            """SELECT id, started_at, title, summary FROM conversations WHERE started_at > %s
               ORDER BY started_at DESC LIMIT 30""",
            (datetime.now(timezone.utc) - timedelta(days=days),)).fetchall()
        return "\n".join(f"#{r['id']} {_d(r['started_at'])} {r['title'] or '…'}: {r['summary'] or ''}"
                         for r in rows) or "Разговоров нет."
    if name == "people":
        return people.people_overview(conn)
    if name == "person_conversations":
        return people.person_conversations(conn, str(args.get("name") or ""), int(args.get("days") or 60))
    if name == "day_events":
        return agent_context.day_overview(conn, agent_context.parse_day(args.get("day")))
    if name == "event_details":
        return agent_context.event_details(conn, int(args.get("id") or 0))
    if name == "conversation_details":
        return agent_context.conversation_details(conn, int(args.get("id") or 0))
    if name == "places":
        return agent_context.places_day(conn, agent_context.parse_day(args.get("day")))
    if name == "diary":
        return agent_context.diary_day(conn, agent_context.parse_day(args.get("day")))
    return f"Неизвестный инструмент {name}"


def _d(dt):
    return dt.astimezone(TZ).strftime("%d.%m %H:%M")


def _t(dt):
    return dt.astimezone(TZ).strftime("%H:%M")


# ---------------------------------------------------------------- запуск
def system_prompt(level: str, channel: str, lang: str = i18n.DEFAULT) -> str:
    now = datetime.now(timezone.utc).astimezone(TZ)
    style = (
        "Ответ прочитает синтезатор речи в наушнике: 1–3 коротких предложения, без markdown, списков, эмодзи и "
        "ссылок; числа — как произносят." if channel == "voice" else
        "Ответ показывается в чате приложения: пиши ясно и по делу, можно короткие абзацы и списки с «•», без "
        "заголовков и таблиц. Источники из поиска перечисли в конце."
    )
    depth = {
        "fast": "Отвечай сразу. Инструменты — только если без них ответ будет неверным.",
        "tools": "Пользуйся инструментами, когда нужны свежие данные, задачи, память или расширения.",
        "deep": "Работай основательно: следуй плану, проверяй факты в нескольких источниках, сделай вывод.",
    }[level]
    return (
        f"Ты — Mnemo, личный ИИ-агент владельца. Он носит кулон-микрофон: у тебя есть его день, собранный в "
        f"события, расшифровки разговоров, люди (узнаны по голосу), места, задачи, память и дневник. "
        f"Сейчас {WEEKDAYS[now.weekday()]}, {now.strftime('%d.%m.%Y %H:%M')}. {i18n.reply_in(lang)} "
        f"Как работать: вопросы о его жизни (что было, с кем говорил, о чём договорились, где был, что обещал) — "
        f"сначала найди данные инструментами: day_events и event_details для дней и событий, search_memory для "
        f"поиска по словам, person_conversations для людей, conversation_details для расшифровки, places и diary. "
        f"Относительные даты («вчера», «в пятницу») переводи в конкретный день. В ответе опирайся на найденное и "
        f"называй, откуда это (событие, время, человек). Если вопрос можно понять по-разному и от этого зависит "
        f"ответ — задай один короткий уточняющий вопрос инструментом ask_user (лучше с вариантами); то, что можно "
        f"найти самому, не уточняй. Расшифровки автоматические и могут содержать ошибки; говорящие подписаны "
        f"«Владелец», по имени или «Голос N». {depth} {style} "
        f"Никогда не выдумывай факты; если не нашёл — так и скажи и предложи, что сделать."
    )


def step(conn, run_id: int, kind: str, title: str, detail: str | None = None):
    conn.execute(
        "INSERT INTO agent_steps (run_id, kind, title, detail) VALUES (%s, %s, %s, %s)",
        (run_id, kind, crypto.seal(title[:300]), crypto.seal((detail or "")[:4000] or None)),
    )


def start_run(conn, question: str, level: str, channel: str, device_id: str | None, agent: str = "mnemo",
              context: str | None = None) -> tuple[int, int]:
    """Создаёт сообщение пользователя и запуск. Возвращает (run_id, message_id).
    level: fast | tools | deep — агент Mnemo, claude | claude_code — Claude Code CLI (claude_agent).
    agent: mnemo | gpt (тот же агент на модели GPT). context: «event:12» и т. п. — о чём этот разговор."""
    if level in claude_agent.LEVELS:
        model, agent = claude_agent.LABEL, "claude"
    elif AGENTS.get(agent):
        model = llm.resolve_model(conn, AGENTS[agent])
    else:
        model, agent = llm.resolve_model(conn, LEVELS[level]["task"]), "mnemo"
    run = conn.execute(
        """INSERT INTO agent_runs (level, question, channel, device_id, model, agent, context)
           VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING id""",
        (level, crypto.seal(question), channel, device_id, model, agent, context),
    ).fetchone()["id"]
    msg = conn.execute(
        "INSERT INTO chat_messages (role, text, channel, run_id) VALUES ('user', %s, %s, %s) RETURNING id",
        (crypto.seal(question), channel, run),
    ).fetchone()["id"]
    return run, msg


def history(conn, before_run: int, limit: int = 8) -> list[dict]:
    rows = conn.execute(
        """SELECT role, text FROM chat_messages WHERE run_id < %s OR run_id IS NULL
           ORDER BY id DESC LIMIT %s""", (before_run, limit)).fetchall()
    return [{"role": r["role"], "content": r["text"]} for r in reversed(rows)]


def finish(conn, run_id: int, channel: str, status: str, answer: str) -> str:
    """Итог запуска: статус и ответ в agent_runs, ответ — сообщением в чат."""
    row = conn.execute("SELECT status FROM agent_runs WHERE id = %s", (run_id,)).fetchone()
    if row and row["status"] == "cancelled" and status != "cancelled":
        status, answer = "cancelled", i18n.t(i18n.lang(conn), "agent.cancelled")  # остановили, пока думал
    conn.execute("UPDATE agent_runs SET status = %s, answer = %s, finished_at = now() WHERE id = %s",
                 (status, crypto.seal(answer), run_id))
    conn.execute("INSERT INTO chat_messages (role, text, channel, run_id) VALUES ('assistant', %s, %s, %s)",
                 (crypto.seal(answer), channel, run_id))
    return answer


def execute(conn, run_id: int) -> str:
    """Выполняет запуск до конца. Безопасно вызывать в отдельном потоке со своим соединением."""
    run = conn.execute("SELECT * FROM agent_runs WHERE id = %s", (run_id,)).fetchone()
    level, channel, device_id = run["level"], run["channel"], run["device_id"]
    if level in claude_agent.LEVELS:
        try:
            return claude_agent.execute(conn, run_id, step, finish)
        except Exception:  # noqa: BLE001
            log.exception("claude run %s failed", run_id)
            return finish(conn, run_id, channel, "failed", i18n.t(i18n.lang(conn), "agent.failed"))
    cfg = LEVELS[level]
    model = run["model"]
    lang = i18n.lang(conn)
    step(conn, run_id, "level", i18n.t(lang, "agent.level", level=i18n.t(lang, "level." + level),
                                       model=model.split("/")[-1]))
    try:
        ext_rows = conn.execute("SELECT id, manifest, settings FROM extensions WHERE enabled").fetchall()
        ext_specs, ext_index = extensions.tool_specs(ext_rows)
        tools = BUILTIN_TOOLS + ext_specs

        context = build_context(conn, device_id) if device_id else ""
        messages = [{"role": "system", "content": system_prompt(level, channel, lang)}]
        if context:
            messages.append({"role": "system", "content": "Контекст:\n" + context})
        # шпаргалка: что было сегодня и вчера (подробности — инструментами)
        messages.append({"role": "system", "content": "Что есть в приложении:\n" + agent_context.overview(conn)})
        about = agent_context.context_block(conn, run.get("context"))
        if about:
            messages.append({"role": "system", "content": "Владелец открыл чат из этого места приложения — вопросы, "
                             "скорее всего, о нём:\n" + about})
        messages += history(conn, run_id)
        messages.append({"role": "user", "content": run["question"]})

        if cfg["plan"]:
            plan_msg = llm.chat(conn, cfg["task"], messages + [{"role": "user", "content":
                                "Составь короткий план работы из 2–5 шагов. Только нумерованный список."}],
                                reasoning=cfg["reasoning"], model=model)
            plan = (plan_msg.get("content") or "").strip()
            if plan:
                step(conn, run_id, "plan", i18n.t(lang, "agent.plan", plan=" → ".join(
                    re.sub(r"^\s*\d+[.)]\s*", "", p).strip() for p in plan.splitlines() if p.strip())[:280]), plan)
                messages.append({"role": "assistant", "content": plan})
                messages.append({"role": "user", "content": "Выполни план и дай итоговый ответ."})

        answer = None
        status = "done"
        for _ in range(cfg["max_steps"]):
            _check(conn, run_id)
            msg = llm.chat(conn, cfg["task"], messages, tools=tools, reasoning=cfg["reasoning"], model=model,
                           timeout=config.ASK_TIMEOUT_SEC if level == "fast" else 120)
            calls = msg.get("tool_calls") or []
            if not calls:
                answer = (msg.get("content") or "").strip()
                break
            messages.append({"role": "assistant", "content": msg.get("content") or "", "tool_calls": calls})
            for call in calls:
                name = call.get("function", {}).get("name", "")
                try:
                    args = json.loads(call.get("function", {}).get("arguments") or "{}") or {}
                except ValueError:
                    args = {}
                if name == "ask_user":
                    # уточнение: вопрос уходит владельцу ответом, его ответ — следующее сообщение в чате
                    answer = str(args.get("question") or "").strip()
                    step(conn, run_id, "ask", i18n.t(lang, "tool.ask_user"))
                    break
                _check(conn, run_id)
                if name in ext_index:
                    row, tool = ext_index[name]
                    shown = extensions.localize(row["manifest"], lang)
                    title = next((t.get("title") for t in shown["tools"] if t["name"] == tool["name"]), None)
                    step(conn, run_id, "tool", f"{shown.get('icon') or '🧩'} {shown['name']}: {title or tool['name']}",
                         json.dumps(args, ensure_ascii=False))
                    result = extensions.run_tool(row, tool, args, {"device_id": device_id, "locale": lang})
                else:
                    title = tool_title(lang, name, args)
                    step(conn, run_id, "tool", title, json.dumps(args, ensure_ascii=False))
                    try:
                        result = run_builtin(conn, name, args, device_id)
                    except Exception as exc:  # noqa: BLE001
                        log.exception("tool %s failed", name)
                        result = f"Ошибка инструмента: {exc}"
                step(conn, run_id, "result", _short(result), result)
                messages.append({"role": "tool", "tool_call_id": call.get("id", name), "content": result})
            if answer:
                status = "needs_input"
                break
        if not answer:
            msg = llm.chat(conn, cfg["task"], messages + [{"role": "user", "content":
                           "Шаги закончились. Дай лучший ответ по тому, что уже известно."}], model=model)
            answer = (msg.get("content") or "").strip() or i18n.t(lang, "agent.no_answer")
    except Cancelled:
        answer, status = i18n.t(lang, "agent.cancelled"), "cancelled"
    except Exception as exc:  # noqa: BLE001
        log.exception("run %s failed", run_id)
        step(conn, run_id, "error", "⚠️ Ошибка: " + str(exc)[:200])
        answer, status = i18n.t(i18n.lang(conn), "agent.failed"), "failed"
    return finish(conn, run_id, channel, status, answer)


def _short(text: str, n: int = 110) -> str:
    line = " ".join((text or "").split())
    return "↳ " + (line[:n] + "…" if len(line) > n else line)


def ask(conn, question: str, level: str = "auto", channel: str = "chat", device_id: str | None = None,
        agent: str = "mnemo", context: str | None = None) -> dict:
    level = route(question, channel) if level == "auto" else level
    if level not in LEVELS:
        level = "fast"
    run_id, _ = start_run(conn, question, level, channel, device_id, agent, context)
    t0 = time.monotonic()
    answer = execute(conn, run_id)
    return {"run_id": run_id, "level": level, "answer": answer, "latency_ms": int((time.monotonic() - t0) * 1000)}
