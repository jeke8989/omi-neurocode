"""Чистая расшифровка: мусор убирается сразу, ошибки распознавания исправляются, когда разговор собран.

1. Сразу после распознавания (tidy, без модели): «субтитры сделал…» и другие фразы, которые Whisper
   «слышит» в тишине, пометки вроде [музыка], повторы-зацикливания («да да да да да»), одни междометия.
2. Когда разговор закончился (conversation, с моделью «Исправление расшифровки»): ошибки в словах
   и именах — по смыслу всего разговора и списку людей, пунктуация, слова-паразиты; бессмысленные
   обрывки и чужая речь (телевизор) помечаются мусором и не показываются.
Исходный текст каждой фразы сохраняется (segments.raw_text) — его видно в приложении.
Модель не может переписать смысл: правка, слишком непохожая на исходную фразу или заметно длиннее неё,
отбрасывается.
"""
from __future__ import annotations

import difflib
import logging
import re

from . import crypto, i18n, llm

log = logging.getLogger("neuro.cleanup")

SETTING = "transcript.cleanup"
BATCH_CHARS = 5000

# Фразы, которые Whisper «слышит» в тишине и шуме: из субтитров, на которых его учили.
HALLUCINATIONS = (
    "продолжение следует", "субтитры сделал", "субтитры создавал", "субтитры подогнал", "редактор субтитров",
    "корректор а.", "спасибо за просмотр", "подписывайтесь на канал", "dimatorzok", "игорь негода",
    "ставьте лайки", "субтитры сделаны сообществом", "субтитры: ", "смотрите в следующем выпуске",
    "thank you for watching", "thanks for watching", "subtitles by", "amara.org", "please subscribe",
    "like and subscribe", "transcribed by",
    "izlediğiniz için teşekkürler", "altyazı", "abone olmayı unutmayın",
    "gracias por ver", "subtítulos realizados por", "suscríbete", "subtítulos por la comunidad",
)

NOTES = re.compile(r"\[[^\]]{0,40}\]|\((?:[^)]{0,30}(?:музык|смех|аплодисмент|шум|music|laugh|applause|noise|müzik|"
                   r"música|risas)[^)]{0,30})\)|[♪♫]+|\*[^*]{0,30}(?:музык|music)[^*]{0,30}\*", re.I)
FILLER = re.compile(r"(?<![\w-])(?:э+|э-э+|ээ+м*|эм+|м+|мм+|хм+|а-а+|uh+|um+|hmm+|erm|eh)(?![\w-])[,.…]?\s*", re.I)
WORD_LOOP = re.compile(r"\b(\w+)(?:[\s,.!?…-]+\1\b){3,}", re.I)  # «да да да да да» -> «да, да»


def is_hallucination(text: str) -> bool:
    t = " ".join(text.lower().split()).strip(" .,!?…-—«»\"'")
    if not t:
        return True
    return any(p in t and len(t) <= len(p) + 20 for p in HALLUCINATIONS)


def _dedupe_sentences(text: str) -> str:
    """Whisper иногда зацикливается: одно и то же предложение 3–10 раз подряд — оставляем одно."""
    parts = re.split(r"(?<=[.!?…])\s+", text)
    out: list[str] = []
    for p in parts:
        key = re.sub(r"\W+", " ", p.lower()).strip()
        if out and key and key == re.sub(r"\W+", " ", out[-1].lower()).strip():
            continue
        out.append(p)
    return " ".join(out)


def tidy(text: str) -> str | None:
    """Быстрая уборка одной фразы сразу после распознавания. None — фраза целиком мусор."""
    if not text or is_hallucination(text):
        return None
    t = NOTES.sub(" ", text)
    t = WORD_LOOP.sub(lambda m: f"{m.group(1)}, {m.group(1)}", t)
    t = _dedupe_sentences(" ".join(t.split()))
    t = FILLER.sub("", t)
    t = re.sub(r"\s+([,.!?…])", r"\1", " ".join(t.split())).strip(" ,")
    t = re.sub(r"^[,.…\-—\s]+", "", t)
    if not re.search(r"\w", t) or is_hallucination(t):
        return None
    return t[:1].upper() + t[1:] if t[:1].islower() and text.strip()[:1].isupper() else t


# ---------------------------------------------------------------- исправление по смыслу
CLEAN_SYSTEM = """Ты — редактор автоматической расшифровки разговора, записанного носимым кулоном-диктофоном.
Распознавание речи ошибается: путает похожие по звучанию слова, коверкает имена и термины, не ставит
знаки препинания, вставляет «э-э», повторы и обрывки. Исправь фразы так, как их на самом деле сказали.
Можно: исправить неверно услышанные слова по смыслу разговора, имена — по списку людей, пунктуацию и
заглавные буквы, убрать слова-паразиты и повторы от запинок.
Нельзя: пересказывать, сокращать содержательные фразы, менять смысл, добавлять что-то от себя, переводить
на другой язык, делать речь литературной — разговорный стиль сохраняется.
Если не уверен, как исправить слово, — оставь как есть.
junk=true — только если фраза явно не речь участников: шум, бессмысленный обрывок из 1–3 слов,
реплика из телевизора, видео или песни.
Фразы с пометкой «контекст» не исправляй — они только для понимания.
Верни строго JSON: {"segments": [{"id": номер, "text": "исправленная фраза", "junk": false}]} —
только фразы, которые нужно изменить или убрать. Если всё правильно — {"segments": []}."""


def enabled(conn) -> bool:
    row = conn.execute("SELECT value FROM settings WHERE key = %s", (SETTING,)).fetchone()
    return (row["value"] if row else "1") != "0"


def _similar(a: str, b: str) -> float:
    return difflib.SequenceMatcher(None, a.lower(), b.lower()).ratio()


def acceptable(old: str, new: str) -> bool:
    """Правка похожа на исправление, а не на переписывание."""
    if not new.strip():
        return False
    if len(new) > len(old) * 1.6 + 12:
        return False  # модель дописала своё
    if len(old) >= 12 and _similar(old, new) < 0.5:
        return False
    return True


def _names(conn) -> list[str]:
    rows = conn.execute(
        """SELECT name FROM people WHERE name IS NOT NULL AND NOT ignored
           ORDER BY is_owner DESC, last_seen DESC NULLS LAST LIMIT 40""").fetchall()
    return [r["name"] for r in rows]


def _label(s: dict) -> str:
    from .people import speaker_label  # noqa: PLC0415 — people тянет голоса, не грузим их без нужды

    try:
        return speaker_label(s)
    except Exception:  # noqa: BLE001
        return "Говорящий"


def conversation(conn, conv_id: int) -> dict:
    """Исправить ещё не проверенные фразы разговора. Вызывается перед итогами разговора."""
    from .people import SEGMENT_SELECT  # noqa: PLC0415

    if not llm.configured() or not enabled(conn):
        return {"checked": 0, "changed": 0, "junk": 0}
    segs = conn.execute(SEGMENT_SELECT + " WHERE s.conversation_id = %s ORDER BY s.started_at, s.id",
                        (conv_id,)).fetchall()
    todo = [s for s in segs if not s.get("cleaned") and not s.get("junk")]
    if not todo:
        return {"checked": 0, "changed": 0, "junk": 0}
    names = _names(conn)
    head = "Люди, которых знает пользователь: " + ", ".join(names) + "." if names else ""
    lang = i18n.lang(conn)
    changed = junk = 0
    done_ids: list[int] = []
    batches: list[list[dict]] = [[]]
    size = 0
    for s in todo:
        if size > BATCH_CHARS and batches[-1]:
            batches.append([])
            size = 0
        batches[-1].append(s)
        size += len(s["text"]) + 20
    by_id = {s["id"]: s for s in segs}
    order = [s["id"] for s in segs]
    for batch in batches:
        first = order.index(batch[0]["id"])
        context = [by_id[i] for i in order[max(0, first - 4):first] if by_id[i]["id"] not in {b["id"] for b in batch}]
        lines = [head] if head else []
        lines += [f"[контекст] {_label(s)}: {s['text']}" for s in context if not s.get("junk")]
        lines += [f"#{s['id']} {_label(s)}: {s['text']}" for s in batch]
        try:
            result = llm.chat_json(conn, "cleanup", CLEAN_SYSTEM + "\n" + i18n.write_in(lang), "\n".join(lines),
                                   fast=True)  # правка текста — без долгих рассуждений (платный выход меньше)
        except Exception:  # noqa: BLE001 — без исправления расшифровка остаётся как была
            log.exception("conversation %s: transcript cleanup failed", conv_id)
            break
        ids = {s["id"] for s in batch}
        for item in result.get("segments") or []:
            if not isinstance(item, dict):
                continue
            try:
                sid = int(str(item.get("id")).lstrip("#"))
            except (TypeError, ValueError):
                continue
            if sid not in ids:
                continue
            old = by_id[sid]["text"]
            if item.get("junk") in (True, "true", 1) and len(old.split()) <= 12:
                conn.execute("UPDATE segments SET junk = true, raw_text = coalesce(raw_text, text) WHERE id = %s",
                             (sid,))
                junk += 1
                continue
            new = " ".join(str(item.get("text") or "").split())
            if new and new != old and acceptable(old, new):
                conn.execute("UPDATE segments SET raw_text = coalesce(raw_text, text), text = %s WHERE id = %s",
                             (crypto.seal(new[:4000]), sid))
                changed += 1
        done_ids += list(ids)
    if done_ids:
        conn.execute("UPDATE segments SET cleaned = true WHERE id = ANY(%s)", (done_ids,))
    log.info("conversation %s: transcript checked %d, fixed %d, junk %d", conv_id, len(done_ids), changed, junk)
    return {"checked": len(done_ids), "changed": changed, "junk": junk}
