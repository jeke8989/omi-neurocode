"""Роли ассистента — «чат-расширения». Это тот же Mnemo с теми же данными (разговоры, люди, обещания, календарь)
и инструментами, но с другим характером и фокусом: коуч обещаний, тренер общения, адвокат дьявола, роаст недели…
Встроенные роли — здесь, в коде; свои роли пользователь создаёт сам (имя, эмодзи, характер) — таблица agent_roles.

Стартовые подсказки чата — под роль и из того, что накопилось: горящие обещания, ближайшие встречи, люди,
важные разговоры. Дешёвая модель составляет пул (~10 вопросов), пока данные не изменились; каждый раз
показываются случайные 3–4 из пула — подсказки всегда разные, а ИИ тратится редко.
"""
from __future__ import annotations

import hashlib
import json
import logging
import random
from datetime import datetime, timedelta, timezone

from . import crypto, i18n, llm, profile
from . import calendar as calendarlib
from .assistant import TZ

log = logging.getLogger("neuro.roles")

DEFAULT = "mnemo"
POOL_TTL = timedelta(hours=3)
POOL_KEY = "starters.{role}"
MAX_CUSTOM = 30

# name / about — ru, en, tr, es; persona — для модели (ответ всё равно на языке пользователя)
BUILTIN: dict[str, dict] = {
    "mnemo": {
        "emoji": "🧠",
        "name": {"ru": "Mnemo", "en": "Mnemo", "tr": "Mnemo", "es": "Mnemo"},
        "about": {"ru": "Ваша память: разговоры, люди, обещания", "en": "Your memory: conversations, people, promises",
                  "tr": "Hafızanız: konuşmalar, kişiler, sözler", "es": "Tu memoria: conversaciones, personas, promesas"},
        "persona": "",
        "hint": "что было, с кем говорил, что обещал, что важного, что в календаре",
    },
    "coach": {
        "emoji": "🎯",
        "name": {"ru": "Коуч обещаний", "en": "Promise coach", "tr": "Söz koçu", "es": "Coach de promesas"},
        "about": {"ru": "Что горит, план дня, довести до конца", "en": "What's urgent, today's plan, follow-through",
                  "tr": "Acil olanlar, günün planı, sonuna kadar", "es": "Lo urgente, plan del día, cumplir"},
        "persona": (
            "Ты — коуч по обещаниям: помогаешь доводить договорённости до конца. Начинай с фактов: list_tasks "
            "(просрочено, на сегодня, что ждут от пользователя и что должны ему) и calendar, если он подключён. "
            "Расставь приоритеты (просрочено → сегодня → неделя), предложи конкретный первый шаг на 15 минут; "
            "предлагай перенести срок, поставить в календарь, закрыть или написать человеку напоминание. "
            "Тон — бодрый и дружелюбный, без морали и без длинных списков."),
        "hint": "что горит, план на день, закрыть конкретное обещание, кому напомнить, что должны мне",
    },
    "talk": {
        "emoji": "🗣",
        "name": {"ru": "Тренер общения", "en": "Conversation coach", "tr": "İletişim koçu", "es": "Coach de conversación"},
        "about": {"ru": "Как вы говорите: доля речи, вопросы, перебивания",
                  "en": "How you talk: share of talk, questions, interruptions",
                  "tr": "Nasıl konuşuyorsunuz: konuşma payı, sorular, sözü kesme",
                  "es": "Cómo hablas: proporción, preguntas, interrupciones"},
        "persona": (
            "Ты — тренер общения. Разбираешь, КАК пользователь разговаривает: talk_stats (кто сколько говорил, "
            "перебивания, вопросы, монологи) и расшифровка (conversation_details). Отметь 1–2 сильные стороны и дай "
            "1–3 конкретных совета с короткими цитатами из разговора. Бережно, без оценок личности; о других людях — "
            "только то, что важно для пользователя."),
        "hint": "разбор последней встречи, как я говорил с конкретным человеком, не перебиваю ли я, задаю ли вопросы",
    },
    "brief": {
        "emoji": "🤝",
        "name": {"ru": "Бриф к встрече", "en": "Meeting brief", "tr": "Toplantı brifi", "es": "Brief de reunión"},
        "about": {"ru": "Кто это, о чём говорили, что спросить",
                  "en": "Who they are, what you discussed, what to ask",
                  "tr": "Kim, ne konuşuldu, ne sorulmalı", "es": "Quién es, de qué hablaron, qué preguntar"},
        "persona": (
            "Ты готовишь пользователя к встрече или звонку. Вызови meeting_brief (по человеку или встрече из "
            "календаря), при нужде — conversation_details. Итог — короткий бриф: кто это и как давно общаетесь, о чём "
            "говорили в прошлый раз, обещания в обе стороны, открытые вопросы, 3 вопроса, которые стоит задать, и "
            "цель встречи одной фразой. Не выдумывай того, чего нет в данных."),
        "hint": "подготовка к ближайшей встрече из календаря, к разговору с конкретным человеком, что обсудить",
    },
    "devil": {
        "emoji": "😈",
        "name": {"ru": "Адвокат дьявола", "en": "Devil's advocate", "tr": "Şeytanın avukatı", "es": "Abogado del diablo"},
        "about": {"ru": "Спорит с вашими планами, чтобы их укрепить",
                  "en": "Argues with your plans to make them stronger",
                  "tr": "Planlarınızı güçlendirmek için itiraz eder", "es": "Discute tus planes para fortalecerlos"},
        "persona": (
            "Ты — адвокат дьявола: споришь с планами и решениями пользователя, чтобы их укрепить. Найди решение или "
            "план в его разговорах (search_memory, conversation_details), назови 3 самых слабых места, риски и "
            "неудобные вопросы, и как каждое быстро проверить. Резко по сути, уважительно по форме; в конце — что "
            "бы ты сделал на его месте."),
        "hint": "покритиковать недавнее решение или план из разговоров, слабые места идеи, риски договорённости",
    },
    "roast": {
        "emoji": "🔥",
        "name": {"ru": "Роаст недели", "en": "Week roast", "tr": "Haftanın roast'u", "es": "Roast de la semana"},
        "about": {"ru": "С юмором о вашей неделе — по фактам", "en": "A funny take on your week, based on facts",
                  "tr": "Haftanıza esprili bakış, gerçeklere dayalı", "es": "Tu semana con humor, con datos"},
        "persona": (
            "Ты — комик в жанре дружеского роаста. Шутишь над неделей пользователя по реальным фактам: обещания "
            "(сколько дал, сколько закрыл, что висит), сколько и о чём говорил, встречи, где был. Шути только над "
            "пользователем и ситуациями — не над другими людьми, не про внешность, здоровье и деньги, без грубости. "
            "5–8 коротких панчей, затем один тёплый вывод и один реальный совет."),
        "hint": "роаст недели, роаст сегодняшнего дня, роаст моих обещаний, как я провёл выходные",
    },
    "detective": {
        "emoji": "🕵️",
        "name": {"ru": "Детектив", "en": "Detective", "tr": "Dedektif", "es": "Detective"},
        "about": {"ru": "Кто что сказал, кто обещал и пропал", "en": "Who said what, who promised and vanished",
                  "tr": "Kim ne dedi, kim söz verip kayboldu", "es": "Quién dijo qué, quién prometió y desapareció"},
        "persona": (
            "Ты — детектив по разговорам: восстанавливаешь, кто что сказал и когда, находишь противоречия и "
            "забытые обещания (особенно то, что обещали пользователю и не сделали). Работай по уликам: "
            "search_memory, person_conversations, conversation_details, list_tasks. Ответ — как расследование: "
            "факты с датами и ссылками, версия, что проверить дальше. Улик не выдумывай."),
        "hint": "кто мне что обещал и пропал, когда впервые обсуждали тему, что говорил человек раньше и сейчас",
    },
    "lang": {
        "emoji": "🌍",
        "name": {"ru": "Языковой тренер", "en": "Language coach", "tr": "Dil koçu", "es": "Coach de idiomas"},
        "about": {"ru": "Учит языку на фразах из вашего дня", "en": "Teaches a language with phrases from your day",
                  "tr": "Gününüzdeki cümlelerle dil öğretir", "es": "Enseña idiomas con frases de tu día"},
        "persona": (
            "Ты — языковой тренер. Учишь языку на материале дня пользователя: берёшь его реальные фразы из разговоров "
            "(recent_conversation, day_events, conversation_details) и показываешь, как сказать их на изучаемом языке "
            "(если язык не назван — по умолчанию английский; пользователь может попросить турецкий или другой). "
            "По 3–5 фраз за раз: перевод, 1–2 слова с пояснением, мини-упражнение или квиз. Коротко и живо."),
        "hint": "как сказать по-английски мои сегодняшние фразы, турецкий на фразах дня, квиз по словам недели",
    },
    "stoic": {
        "emoji": "🏛",
        "name": {"ru": "Стоик", "en": "Stoic", "tr": "Stoacı", "es": "Estoico"},
        "about": {"ru": "Спокойный взгляд на трудный день или разговор",
                  "en": "A calm look at a hard day or conversation",
                  "tr": "Zor bir güne veya konuşmaya sakin bakış", "es": "Una mirada serena a un día difícil"},
        "persona": (
            "Ты — собеседник в духе стоической философии. Помогаешь спокойно взглянуть на трудный разговор или день: "
            "что было в его власти, а что нет, что можно сделать сейчас, как это будет выглядеть через год. Опирайся "
            "на реальные события (day_events, conversation_details), без нравоучений, коротко и тепло. Это не "
            "терапия: если человеку по-настоящему тяжело — мягко предложи поговорить с близкими или специалистом."),
        "hint": "спокойно разобрать трудный разговор, что было в моей власти сегодня, взгляд со стороны на конфликт",
    },
}


def _l(d: dict, lang: str) -> str:
    return d.get(i18n.normalize(lang)) or d.get("en") or d.get("ru") or ""


def _custom_id(rid: int) -> str:
    return f"c{rid}"


def listing(conn, lang: str) -> list[dict]:
    out = [{"id": k, "emoji": r["emoji"], "name": _l(r["name"], lang), "about": _l(r["about"], lang), "custom": False}
           for k, r in BUILTIN.items()]
    for r in conn.execute("SELECT id, name, emoji, persona FROM agent_roles ORDER BY id").fetchall():
        out.append({"id": _custom_id(r["id"]), "emoji": r["emoji"] or "✨", "name": r["name"] or "…",
                    "about": " ".join((r["persona"] or "").split())[:90], "persona": r["persona"] or "", "custom": True})
    return out


def get(conn, role: str | None, lang: str = i18n.DEFAULT) -> dict:
    """Роль по id (встроенная или своя «cN»); неизвестная — Mnemo."""
    role = (role or DEFAULT).strip()
    if role in BUILTIN:
        r = BUILTIN[role]
        return {"id": role, "emoji": r["emoji"], "name": _l(r["name"], lang), "persona": r["persona"],
                "hint": r["hint"], "custom": False}
    if role.startswith("c") and role[1:].isdigit():
        row = conn.execute("SELECT id, name, emoji, persona FROM agent_roles WHERE id = %s", (int(role[1:]),)).fetchone()
        if row:
            return {"id": role, "emoji": row["emoji"] or "✨", "name": row["name"] or "…", "persona": row["persona"] or "",
                    "hint": "вопросы под характер этой роли — по разговорам, людям и обещаниям", "custom": True}
    return get(conn, DEFAULT, lang)


def exists(conn, role: str | None) -> bool:
    return get(conn, role)["id"] == (role or DEFAULT)


def _clean(name, emoji, persona) -> tuple[str, str, str]:
    name = " ".join(str(name or "").split())[:40]
    emoji = "".join(str(emoji or "").split())[:8] or "✨"
    persona = str(persona or "").strip()[:2000]
    if not name or not persona:
        raise ValueError("нужны имя и характер роли")
    return name, emoji, persona


def create(conn, name, emoji, persona) -> str:
    name, emoji, persona = _clean(name, emoji, persona)
    if conn.execute("SELECT count(*) AS n FROM agent_roles").fetchone()["n"] >= MAX_CUSTOM:
        raise ValueError(f"не больше {MAX_CUSTOM} своих ролей")
    rid = conn.execute("INSERT INTO agent_roles (name, emoji, persona) VALUES (%s, %s, %s) RETURNING id",
                       (crypto.seal(name), emoji, crypto.seal(persona))).fetchone()["id"]
    return _custom_id(rid)


def update(conn, role: str, name, emoji, persona) -> bool:
    if not (role.startswith("c") and role[1:].isdigit()):
        return False
    name, emoji, persona = _clean(name, emoji, persona)
    n = conn.execute("UPDATE agent_roles SET name = %s, emoji = %s, persona = %s, updated_at = now() WHERE id = %s",
                     (crypto.seal(name), emoji, crypto.seal(persona), int(role[1:]))).rowcount
    conn.execute("DELETE FROM settings WHERE key = %s", (POOL_KEY.format(role=role),))
    return bool(n)


def delete(conn, role: str) -> bool:
    if not (role.startswith("c") and role[1:].isdigit()):
        return False
    conn.execute("DELETE FROM settings WHERE key = %s", (POOL_KEY.format(role=role),))
    return bool(conn.execute("DELETE FROM agent_roles WHERE id = %s", (int(role[1:]),)).rowcount)


def prompt(conn, role: str | None, lang: str) -> str:
    """Добавка к системному промпту: характер и фокус роли. Данные, инструменты и правила — те же."""
    r = get(conn, role, lang)
    if not r["persona"]:
        return ""
    return (f"Сейчас ты в роли «{r['name']}» {r['emoji']}. {r['persona']}\n"
            "Данные, инструменты и правила — те же: факты бери из инструментов и не выдумывай, ссылайся на "
            "события и разговоры ссылками. Характер роли — в тоне и фокусе ответа.")


# ---------------------------------------------------------------- стартовые подсказки
def snapshot(conn) -> dict:
    """Что накопилось — коротко, только нужное для подсказок."""
    now = datetime.now(timezone.utc)
    tasks = conn.execute(
        """SELECT t.id, t.text, t.kind, t.due_at, coalesce(cp.name, t.counterpart, t.assignee) AS mate
           FROM tasks t LEFT JOIN people cp ON cp.id = t.counterpart_id
           WHERE t.status = 'open' ORDER BY coalesce(t.due_at, t.created_at + interval '30 days') LIMIT 12""").fetchall()
    overdue = [t for t in tasks if t["due_at"] and t["due_at"] < now]
    soon = [t for t in tasks if t["due_at"] and now <= t["due_at"] < now + timedelta(days=2)]
    waiting = [t for t in tasks if t["kind"] == "waiting"]
    meetings = []
    try:
        if calendarlib.status(conn)["connected"]:
            meetings = [e for e in calendarlib.events(conn, now, now + timedelta(days=2), 6) if not e["all_day"]]
    except Exception:  # noqa: BLE001 — без календаря подсказки всё равно есть
        meetings = []
    convs = conn.execute(
        """SELECT c.id, c.title, c.started_at, (c.important_at IS NOT NULL) AS important,
                  (SELECT string_agg(DISTINCT p.id::text, ',') FROM segments s JOIN people p ON p.id = s.person_id
                   WHERE s.conversation_id = c.id AND p.name IS NOT NULL AND NOT p.is_owner) AS people
           FROM conversations c
           WHERE c.started_at > now() - interval '3 days' AND c.trashed_at IS NULL AND c.title IS NOT NULL
             AND c.status = 'done' ORDER BY c.started_at DESC LIMIT 8""").fetchall()
    names = {r["id"]: r["name"] for r in conn.execute(
        """SELECT id, name FROM people WHERE name IS NOT NULL AND NOT is_owner AND NOT ignored
           AND last_seen > now() - interval '14 days' ORDER BY last_seen DESC LIMIT 8""").fetchall()}
    for c in convs:
        ids = [int(x) for x in (c["people"] or "").split(",") if x]
        c["names"] = [names[i] for i in ids if i in names]
    week = conn.execute(
        """SELECT (SELECT count(*) FROM conversations WHERE started_at > now() - interval '7 days') AS convs,
                  (SELECT count(*) FROM tasks WHERE created_at > now() - interval '7 days'
                     AND status <> 'dismissed') AS promised,
                  (SELECT count(*) FROM tasks WHERE done_at > now() - interval '7 days') AS done""").fetchone()
    return {"overdue": overdue, "soon": soon, "waiting": waiting, "open": len(tasks), "meetings": meetings,
            "convs": convs, "people": list(names.values()), "week": week}


def snapshot_text(s: dict) -> str:
    def t(x):
        return x["text"] + (f" (с: {x['mate']})" if x.get("mate") else "") + \
            (f", срок {x['due_at'].astimezone(TZ):%d.%m %H:%M}" if x.get("due_at") else "")
    lines = []
    if s["overdue"]:
        lines.append("Просрочено: " + "; ".join(t(x) for x in s["overdue"][:4]))
    if s["soon"]:
        lines.append("Срок скоро: " + "; ".join(t(x) for x in s["soon"][:4]))
    if s["waiting"]:
        lines.append("Обещали пользователю: " + "; ".join(t(x) for x in s["waiting"][:4]))
    lines.append(f"Открытых обещаний: {s['open']}")
    if s["meetings"]:
        lines.append("Встречи в календаре: " + "; ".join(
            f"{e['starts_at'].astimezone(TZ):%d.%m %H:%M} {e['title'] or ''}"
            + (f" (участники: {e['attendees']})" if e.get("attendees") else "") for e in s["meetings"][:4]))
    if s["convs"]:
        lines.append("Недавние разговоры: " + "; ".join(
            f"{c['started_at'].astimezone(TZ):%d.%m} «{c['title']}»" + (" ★" if c["important"] else "")
            + (f" с {', '.join(c['names'])}" if c["names"] else "") for c in s["convs"][:6]))
    if s["people"]:
        lines.append("Люди последних недель: " + ", ".join(s["people"][:8]))
    w = s["week"]
    lines.append(f"За неделю: разговоров {w['convs']}, обещаний {w['promised']}, закрыто {w['done']}")
    return "\n".join(lines)


def _signature(s: dict) -> str:
    """Меняется, когда меняется то, о чём стоит спросить: обещания, встречи, разговоры."""
    key = json.dumps([[x["id"] for x in s["overdue"] + s["soon"] + s["waiting"]], s["open"],
                      [str(e.get("uid")) for e in s["meetings"]], [c["id"] for c in s["convs"][:4]]])
    return hashlib.sha256(key.encode()).hexdigest()[:16]


STARTERS_SYSTEM = """Придумай стартовые вопросы для чата с ИИ-ассистентом Mnemo в роли «{name}» {emoji}.
Ассистент знает разговоры пользователя (их записывает кулон), людей, обещания и календарь.
Фокус роли: {hint}.
Вопросы — от лица пользователя, короткие (до 60 символов), конкретные: с реальными именами, обещаниями, встречами
и разговорами из данных ниже; все разные по смыслу; ничего не выдумывай. Если данных мало — общие вопросы под роль.
Верни строго JSON: {{"items": ["вопрос", ...]}} — 10 вопросов."""


def _fallback(s: dict, role: dict, lang: str) -> list[str]:
    """Без модели: из данных по шаблонам (русский / английский)."""
    ru = i18n.normalize(lang) == "ru"
    out: list[str] = []
    mate = next((x["mate"] for x in s["overdue"] + s["soon"] + s["waiting"] if x.get("mate")), None)
    person = mate or (s["people"][0] if s["people"] else None)
    meeting = s["meetings"][0]["title"] if s["meetings"] else None
    conv = s["convs"][0]["title"] if s["convs"] else None
    rid = role["id"]
    if rid == "coach":
        out += ["Что у меня горит?", "Составь план на сегодня"] if ru else ["What's urgent for me?", "Plan my day"]
        if mate:
            out.append(f"Напиши напоминание: {mate}" if ru else f"Draft a reminder to {mate}")
    elif rid == "talk":
        out += ["Разбери мой последний разговор", "Я много перебиваю?"] if ru else \
            ["Review my last conversation", "Do I interrupt a lot?"]
    elif rid == "brief":
        if meeting:
            out.append(f"Подготовь к встрече «{meeting}»" if ru else f"Brief me for «{meeting}»")
        if person:
            out.append(f"Что обсудить с {person}?" if ru else f"What should I discuss with {person}?")
    elif rid == "devil":
        out += ["Покритикуй моё последнее решение"] if ru else ["Challenge my latest decision"]
    elif rid == "roast":
        out += ["Роаст моей недели", "Роаст моих обещаний"] if ru else ["Roast my week", "Roast my promises"]
    elif rid == "detective":
        out += ["Кто мне что обещал и пропал?"] if ru else ["Who promised me something and vanished?"]
    elif rid == "lang":
        out += ["Мои фразы дня по-английски", "Квиз по словам недели"] if ru else \
            ["My phrases of the day in Turkish", "Quiz me on this week's words"]
    elif rid == "stoic":
        out += ["Помоги спокойно разобрать день"] if ru else ["Help me look calmly at my day"]
    if conv:
        out.append(f"О чём договорились в «{conv}»?" if ru else f"What did we agree on in «{conv}»?")
    if person and rid in ("mnemo", "detective", "coach"):
        out.append(f"Что я обещал {person}?" if ru else f"What did I promise {person}?")
    out += ["Что было сегодня?", "Что я обещал на этой неделе?", "С кем я говорил вчера?"] if ru else \
        ["What happened today?", "What did I promise this week?", "Who did I talk to yesterday?"]
    return list(dict.fromkeys(out))


def starters(conn, role: str | None, n: int = 4, use_llm: bool = True) -> list[str]:
    """3–4 стартовые подсказки: случайные из пула под роль. Пул пересобирается, когда изменились обещания,
    встречи или разговоры (или раз в 3 часа)."""
    lang = i18n.lang(conn)
    r = get(conn, role, lang)
    snap = snapshot(conn)
    sig = _signature(snap)
    key = POOL_KEY.format(role=r["id"])
    pool: list[str] = []
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (key,)).fetchone()
    try:
        cached = json.loads(row["value"]) if row and row["value"] else None
    except (TypeError, ValueError):
        cached = None
    try:
        fresh = bool(cached) and cached.get("sig") == sig and cached.get("lang") == lang and \
            datetime.now(timezone.utc) - datetime.fromisoformat(cached["at"]) < POOL_TTL
    except (KeyError, TypeError, ValueError):
        fresh = False
    if fresh:
        pool = [str(x) for x in cached.get("items") or []]
    elif use_llm and llm.configured():
        try:
            res = llm.chat_json(conn, "remember", STARTERS_SYSTEM.format(name=r["name"], emoji=r["emoji"], hint=r["hint"])
                                + ("\nХарактер роли: " + r["persona"][:600] if r["custom"] else "")
                                + "\n" + profile.prompt(conn) + "\n" + i18n.write_in(lang),
                                snapshot_text(snap), timeout=25, fast=True, usage="starters", temperature=0.9)
            pool = [" ".join(str(x).split()).strip(" «»\"")[:90] for x in res.get("items") or [] if str(x).strip()]
            pool = list(dict.fromkeys(p for p in pool if len(p) >= 4))[:12]
        except Exception:  # noqa: BLE001 — подсказки не должны ломать чат
            log.warning("starters for role %s failed", r["id"], exc_info=True)
            pool = []
        if pool:
            conn.execute(
                """INSERT INTO settings (key, value) VALUES (%s, %s)
                   ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                (key, crypto.seal(json.dumps({"sig": sig, "lang": lang, "at": datetime.now(timezone.utc).isoformat(),
                                              "items": pool}, ensure_ascii=False))))
    if not pool:
        pool = _fallback(snap, r, lang)
    return random.sample(pool, min(n, len(pool)))
