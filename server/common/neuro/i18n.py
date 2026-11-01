"""Язык приложения: на нём пишутся итоги, дневник, ответы агента и голосовые фразы.

Язык выбирается в приложении и хранится в settings (app.language). Модели получают
явное указание «пиши на таком-то языке»; короткие фразы сервера переведены здесь.
"""
from __future__ import annotations

DEFAULT = "ru"
LANGS = {
    "ru": {"name": "Русский", "english": "Russian", "locale": "ru-RU"},
    "en": {"name": "English", "english": "English", "locale": "en-US"},
    "tr": {"name": "Türkçe", "english": "Turkish", "locale": "tr-TR"},
    "es": {"name": "Español", "english": "Spanish", "locale": "es-ES"},
}

_cache: dict[str, tuple[float, str]] = {}


def normalize(code: str | None) -> str:
    code = (code or "").strip().lower().replace("_", "-").split("-")[0]
    return code if code in LANGS else DEFAULT


def lang(conn) -> str:
    """Текущий язык приложения (settings app.language)."""
    try:
        row = conn.execute("SELECT value FROM settings WHERE key = 'app.language'").fetchone()
    except Exception:  # noqa: BLE001 — нет таблицы в старой базе или соединение занято
        return DEFAULT
    return normalize(row["value"] if row else None)


def set_lang(conn, code: str) -> str:
    code = normalize(code)
    conn.execute(
        "INSERT INTO settings (key, value) VALUES ('app.language', %s) "
        "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()", (code,))
    return code


STT_CHOICES = ("app", "auto", *LANGS)


def stt_choice(conn) -> str:
    """Что выбрано для расшифровки: app (как язык приложения), auto или код языка."""
    from . import config  # noqa: PLC0415 — config тянет окружение, i18n должен импортироваться без него

    try:
        row = conn.execute("SELECT value FROM settings WHERE key = 'stt.language'").fetchone() if conn else None
    except Exception:  # noqa: BLE001
        row = None
    value = (row["value"] if row else None) or config.STT_LANGUAGE or "app"
    return value if value in STT_CHOICES else "app"


def stt_language(conn) -> str | None:
    """Код языка для распознавания речи или None — пусть модель определит сама."""
    choice = stt_choice(conn)
    if choice == "auto":
        return None
    if choice == "app":
        return lang(conn) if conn else DEFAULT
    return choice


def write_in(code: str) -> str:
    """Указание модели, на каком языке писать текст для пользователя."""
    code = normalize(code)
    if code == "ru":
        return "Все тексты пиши по-русски. О пользователе — по имени или на «вы», не «владелец»."
    return (f"Write every text field in {LANGS[code]['english']} "
            f"(the instructions above are in Russian, but the output must be in {LANGS[code]['english']}). "
            "Refer to the user by name or as “you”, never as “the owner”.")


def reply_in(code: str) -> str:
    """Для агента: язык по умолчанию, но если спросили на другом — отвечать на нём."""
    code = normalize(code)
    name = LANGS[code]["english"]
    if code == "ru":
        return "Отвечай по-русски, а если пользователь обратился на другом языке — на его языке."
    return f"Answer in {name}; if the user writes or speaks in another language, answer in that language."


# Короткие фразы сервера (голос, шаги агента, ответы инструментов).
PHRASES: dict[str, dict[str, str]] = {
    "voice.deep": {
        "ru": "Займусь подробно. Готовый ответ пришлю в чат.",
        "en": "I'll dig into this. The full answer will be in the chat.",
        "tr": "Bunu ayrıntılı inceleyeceğim. Tam cevap sohbette olacak.",
        "es": "Lo voy a mirar a fondo. La respuesta completa estará en el chat.",
    },
    "voice.later": {
        "ru": "Нужно чуть больше времени — ответ будет в чате.",
        "en": "This needs a bit more time. The answer will be in the chat.",
        "tr": "Biraz daha zaman gerekiyor. Cevap sohbette olacak.",
        "es": "Necesito un poco más de tiempo. La respuesta estará en el chat.",
    },
    "voice.not_heard": {
        "ru": "Не расслышал вопрос. Нажмите кнопку и спросите ещё раз.",
        "en": "I didn't catch the question. Press the button and ask again.",
        "tr": "Soruyu duyamadım. Düğmeye basıp tekrar sorun.",
        "es": "No entendí la pregunta. Pulsa el botón y vuelve a preguntar.",
    },
    "voice.no_model": {
        "ru": "На сервере не подключена модель. Добавьте ключ OpenRouter.",
        "en": "No AI model is connected on the server. Add an OpenRouter key.",
        "tr": "Sunucuda yapay zekâ modeli bağlı değil. Bir OpenRouter anahtarı ekleyin.",
        "es": "No hay ningún modelo de IA conectado en el servidor. Añade una clave de OpenRouter.",
    },
    "voice.failed": {
        "ru": "Не получилось ответить. Попробуйте ещё раз.",
        "en": "I couldn't answer. Please try again.",
        "tr": "Cevap veremedim. Lütfen tekrar deneyin.",
        "es": "No pude responder. Inténtalo de nuevo.",
    },
    "agent.level": {
        "ru": "Уровень: {level} · {model}", "en": "Level: {level} · {model}",
        "tr": "Seviye: {level} · {model}", "es": "Nivel: {level} · {model}",
    },
    "agent.plan": {"ru": "🗺 План: {plan}", "en": "🗺 Plan: {plan}", "tr": "🗺 Plan: {plan}", "es": "🗺 Plan: {plan}"},
    "agent.no_answer": {
        "ru": "Не получилось найти ответ.", "en": "I couldn't find an answer.",
        "tr": "Bir cevap bulamadım.", "es": "No pude encontrar una respuesta.",
    },
    "agent.failed": {
        "ru": "Не получилось выполнить запрос — модель или сервис недоступны. Попробуйте ещё раз.",
        "en": "I couldn't complete the request: the model or a service is unavailable. Please try again.",
        "tr": "İstek tamamlanamadı: model ya da bir servis kullanılamıyor. Lütfen tekrar deneyin.",
        "es": "No pude completar la solicitud: el modelo o un servicio no está disponible. Inténtalo de nuevo.",
    },
    "level.fast": {"ru": "Быстро", "en": "Quick", "tr": "Hızlı", "es": "Rápido"},
    "level.tools": {"ru": "Основательно", "en": "Thorough", "tr": "Kapsamlı", "es": "A fondo"},
    "level.deep": {"ru": "Глубоко", "en": "Deep", "tr": "Derin", "es": "Profundo"},
    "level.claude": {"ru": "Claude", "en": "Claude", "tr": "Claude", "es": "Claude"},
    "level.claude_code": {"ru": "Claude · код", "en": "Claude · code", "tr": "Claude · kod", "es": "Claude · código"},
    "claude.level": {"ru": "🤖 {model}", "en": "🤖 {model}", "tr": "🤖 {model}", "es": "🤖 {model}"},
    "claude.bash": {"ru": "💻 {text}", "en": "💻 {text}", "tr": "💻 {text}", "es": "💻 {text}"},
    "claude.read": {"ru": "📖 Читаю {name}", "en": "📖 Reading {name}", "tr": "📖 {name} okunuyor",
                    "es": "📖 Leyendo {name}"},
    "claude.edit": {"ru": "✏️ Правлю {name}", "en": "✏️ Editing {name}", "tr": "✏️ {name} düzenleniyor",
                    "es": "✏️ Editando {name}"},
    "claude.search": {"ru": "🔎 Ищу в коде: {query}", "en": "🔎 Searching the code: {query}",
                      "tr": "🔎 Kodda aranıyor: {query}", "es": "🔎 Buscando en el código: {query}"},
    "claude.web": {"ru": "🌐 Открываю {text}", "en": "🌐 Opening {text}", "tr": "🌐 {text} açılıyor",
                   "es": "🌐 Abriendo {text}"},
    "claude.task": {"ru": "🧑‍💻 Подзадача: {text}", "en": "🧑‍💻 Subtask: {text}", "tr": "🧑‍💻 Alt görev: {text}",
                    "es": "🧑‍💻 Subtarea: {text}"},
    "claude.plan": {"ru": "Обновляю план", "en": "Updating the plan", "tr": "Plan güncelleniyor",
                    "es": "Actualizando el plan"},
    "claude.new_session": {"ru": "🆕 Прошлая сессия не найдена, начинаю новую",
                           "en": "🆕 The previous session is gone, starting a new one",
                           "tr": "🆕 Önceki oturum bulunamadı, yenisi başlıyor",
                           "es": "🆕 No se encontró la sesión anterior, empiezo una nueva"},
    "claude.no_token": {
        "ru": "Claude не подключён. Введите токен в Настройки → Claude Code (на компьютере: claude setup-token).",
        "en": "Claude isn't connected. Enter a token in Settings → Claude Code (on a computer: claude setup-token).",
        "tr": "Claude bağlı değil. Ayarlar → Claude Code bölümüne bir token girin (bilgisayarda: claude setup-token).",
        "es": "Claude no está conectado. Introduce un token en Ajustes → Claude Code (en el ordenador: claude setup-token).",
    },
    "claude.busy": {
        "ru": "Claude ещё работает над прошлым сообщением. Дождитесь ответа.",
        "en": "Claude is still working on the previous message. Wait for the answer.",
        "tr": "Claude hâlâ önceki mesaj üzerinde çalışıyor. Cevabı bekleyin.",
        "es": "Claude aún está trabajando en el mensaje anterior. Espera la respuesta.",
    },
    "claude.unavailable": {
        "ru": "Сервис Claude на сервере не отвечает.", "en": "The Claude service on the server isn't responding.",
        "tr": "Sunucudaki Claude servisi yanıt vermiyor.", "es": "El servicio de Claude del servidor no responde.",
    },
    "claude.failed": {
        "ru": "Claude не смог ответить: {error}", "en": "Claude couldn't answer: {error}",
        "tr": "Claude cevap veremedi: {error}", "es": "Claude no pudo responder: {error}",
    },
    "tool.web_search": {
        "ru": "🔎 Ищу в интернете: {query}", "en": "🔎 Searching the web: {query}",
        "tr": "🔎 İnternette arıyorum: {query}", "es": "🔎 Buscando en internet: {query}",
    },
    "tool.create_task": {
        "ru": "📝 Создаю задачу: {text}", "en": "📝 Creating a task: {text}",
        "tr": "📝 Görev oluşturuyorum: {text}", "es": "📝 Creando una tarea: {text}",
    },
    "tool.list_tasks": {"ru": "📋 Смотрю задачи", "en": "📋 Checking tasks", "tr": "📋 Görevlere bakıyorum",
                        "es": "📋 Revisando tareas"},
    "tool.complete_task": {
        "ru": "✅ Закрываю задачу #{id}", "en": "✅ Completing task #{id}",
        "tr": "✅ #{id} görevini kapatıyorum", "es": "✅ Cerrando la tarea #{id}",
    },
    "tool.save_memory": {
        "ru": "🧠 Запоминаю: {text}", "en": "🧠 Remembering: {text}", "tr": "🧠 Hatırlıyorum: {text}",
        "es": "🧠 Recordando: {text}",
    },
    "tool.search_memory": {
        "ru": "🗂 Ищу в памяти: {query}", "en": "🗂 Searching memory: {query}",
        "tr": "🗂 Hafızada arıyorum: {query}", "es": "🗂 Buscando en la memoria: {query}",
    },
    "tool.recent_conversation": {
        "ru": "🎙 Читаю недавний разговор", "en": "🎙 Reading the recent conversation",
        "tr": "🎙 Son konuşmayı okuyorum", "es": "🎙 Leyendo la conversación reciente",
    },
    "tool.list_conversations": {
        "ru": "💬 Смотрю разговоры", "en": "💬 Looking through conversations",
        "tr": "💬 Konuşmalara bakıyorum", "es": "💬 Revisando conversaciones",
    },
    "tool.people": {
        "ru": "👥 Смотрю, кого узнаю по голосу", "en": "👥 Checking who I know by voice",
        "tr": "👥 Sesinden tanıdığım kişilere bakıyorum", "es": "👥 Reviso a quién reconozco por la voz",
    },
    "tool.person_conversations": {
        "ru": "👤 Разговоры с: {name}", "en": "👤 Conversations with: {name}",
        "tr": "👤 Şununla konuşmalar: {name}", "es": "👤 Conversaciones con: {name}",
    },
    "tool.forget_memory": {
        "ru": "🗑 Убираю запись из памяти", "en": "🗑 Removing a memory",
        "tr": "🗑 Bir anıyı kaldırıyorum", "es": "🗑 Quitando un recuerdo",
    },
    "tool.remember_rule": {
        "ru": "📌 Запоминаю правило: {text}", "en": "📌 Saving a rule: {text}",
        "tr": "📌 Kuralı kaydediyorum: {text}", "es": "📌 Guardando una regla: {text}",
    },
    "tool.forget_rule": {
        "ru": "📌 Удаляю правило", "en": "📌 Removing a rule", "tr": "📌 Kuralı siliyorum", "es": "📌 Quitando una regla",
    },
    "tool.schedule_task": {
        "ru": "⏰ Ставлю задание по расписанию", "en": "⏰ Scheduling a task",
        "tr": "⏰ Zamanlanmış görev ekliyorum", "es": "⏰ Programando una tarea",
    },
    "tool.cancel_schedule": {
        "ru": "⏰ Убираю задание", "en": "⏰ Removing a scheduled task",
        "tr": "⏰ Zamanlanmış görevi kaldırıyorum", "es": "⏰ Quitando una tarea programada",
    },
    "tool.important_conversations": {
        "ru": "⭐ Смотрю важные разговоры", "en": "⭐ Looking at important conversations",
        "tr": "⭐ Önemli konuşmalara bakıyorum", "es": "⭐ Revisando conversaciones importantes",
    },
    "tool.day_events": {
        "ru": "📅 Смотрю события: {day}", "en": "📅 Looking at events: {day}",
        "tr": "📅 Etkinliklere bakıyorum: {day}", "es": "📅 Revisando eventos: {day}",
    },
    "tool.event_details": {
        "ru": "🗒 Открываю событие #{id}", "en": "🗒 Opening event #{id}",
        "tr": "🗒 #{id} etkinliğini açıyorum", "es": "🗒 Abriendo el evento #{id}",
    },
    "tool.conversation_details": {
        "ru": "🎙 Читаю разговор #{id}", "en": "🎙 Reading conversation #{id}",
        "tr": "🎙 #{id} konuşmasını okuyorum", "es": "🎙 Leyendo la conversación #{id}",
    },
    "tool.places": {
        "ru": "📍 Смотрю, где был: {day}", "en": "📍 Checking where you were: {day}",
        "tr": "📍 Nerede olduğuna bakıyorum: {day}", "es": "📍 Reviso dónde estuviste: {day}",
    },
    "tool.diary": {
        "ru": "📔 Открываю дневник: {day}", "en": "📔 Opening the diary: {day}",
        "tr": "📔 Günlüğü açıyorum: {day}", "es": "📔 Abriendo el diario: {day}",
    },
    "tool.ask_user": {
        "ru": "❓ Уточняю вопрос", "en": "❓ Asking you to clarify", "tr": "❓ Soruyu netleştiriyorum",
        "es": "❓ Pido una aclaración",
    },
    "agent.cancelled": {
        "ru": "Остановлено.", "en": "Stopped.", "tr": "Durduruldu.", "es": "Detenido.",
    },
    "agent.mnemo": {"ru": "Mnemo", "en": "Mnemo", "tr": "Mnemo", "es": "Mnemo"},
    "agent.gpt": {"ru": "GPT", "en": "GPT", "tr": "GPT", "es": "GPT"},
    "tool.extension": {"ru": "🧩 {name}", "en": "🧩 {name}", "tr": "🧩 {name}", "es": "🧩 {name}"},
    "memory.mark": {
        "ru": "Отметка {when} (речь рядом не распознана)", "en": "Marked at {when} (no speech recognized nearby)",
        "tr": "{when} işareti (yakında konuşma tanınmadı)", "es": "Marca de las {when} (no se reconoció voz cerca)",
    },
    "conversation.empty": {"ru": "Без содержания", "en": "No content", "tr": "İçerik yok", "es": "Sin contenido"},
}


def t(code: str, key: str, **kwargs) -> str:
    variants = PHRASES.get(key)
    if not variants:
        return key
    text = variants.get(normalize(code)) or variants[DEFAULT]
    try:
        return text.format(**kwargs)
    except (KeyError, IndexError, ValueError):
        return text


def pick(value, code: str):
    """Строка или словарь {язык: строка} -> строка на нужном языке (для каталога моделей и манифестов)."""
    if isinstance(value, dict):
        code = normalize(code)
        return value.get(code) or value.get("en") or value.get(DEFAULT) or next(iter(value.values()), "")
    return value
