"""Задачи, для которых можно выбрать модель в приложении, и каталог рекомендованных моделей OpenRouter.

Цены — $ за 1 млн токенов (LLM) или $ за час звука (распознавание), по данным OpenRouter на 30.10.2026.
Gateway подтягивает актуальные цены LLM с openrouter.ai/api/v1/models, так что в приложении они свежие.
"""

TASKS = [
    {
        "key": "ask",
        "kind": "llm",
        "title": "Агент · быстро (голос)",
        "description": "Уровень 1: кнопка на кулоне — вопрос → ответ в наушник за пару секунд.",
        "default": "anthropic/claude-haiku-5.5",
    },
    {
        "key": "ask_stt",
        "kind": "stt",
        "title": "Распознавание вопроса",
        "description": "Короткий вопрос после нажатия кнопки. Важна скорость.",
        "default": "openai/whisper-large-v3-turbo",
    },
    {
        "key": "transcribe",
        "kind": "stt",
        "title": "Расшифровка записи",
        "description": "Весь звук с кулона. Работает, если на сервере STT_PROVIDER=openrouter.",
        "default": "openai/whisper-large-v3",
    },
    {
        "key": "summary",
        "kind": "llm",
        "title": "Итоги разговоров",
        "description": "Название, краткое содержание, задачи и факты. Длинный текст на входе — важна цена входа.",
        "default": "deepseek/deepseek-v4.1-flash",
    },
    {
        "key": "remember",
        "kind": "llm",
        "title": "«Запомни это»",
        "description": "Двойное нажатие: минута разговора → одна мысль. Хватает самой дешёвой модели.",
        "default": "z-ai/glm-5.3-flash",
    },
]

# Уровни агента (harness): своя модель на каждый уровень
TASKS += [
    {
        "key": "agent",
        "kind": "llm",
        "title": "Агент · основательно",
        "description": "Уровень 2: пользуется инструментами — поиск, задачи, память, расширения.",
        "default": "anthropic/claude-haiku-5.5",
    },
    {
        "key": "agent_deep",
        "kind": "llm",
        "title": "Агент · глубоко",
        "description": "Уровень 3: сначала план, потом многошаговая работа. Здесь стоит сильная модель.",
        "default": "google/gemini-3.7-flash",
    },
    {
        "key": "search",
        "kind": "llm",
        "title": "Веб-поиск",
        "description": "Читает результаты поиска и кратко пересказывает с источниками.",
        "default": "deepseek/deepseek-v4.1-flash",
    },
]

# Дневник жизни
TASKS += [
    {
        "key": "diary",
        "kind": "llm",
        "title": "Дневник дня",
        "description": "Собирает красивую ленту дня: история, настроение, хайлайты, места.",
        "default": "anthropic/claude-haiku-5.5",
    },
    {
        "key": "vision",
        "kind": "llm",
        "title": "Подписи к фото",
        "description": "Смотрит на фото и пишет короткую подпись для дневника.",
        "default": "google/gemini-3.5-flash-lite",
    },
    {
        "key": "image_style",
        "kind": "image",
        "title": "Стилизация фото",
        "description": "Перерисовывает фото дня в выбранном стиле. ≈ $0.08 за кадр.",
        "default": "google/gemini-3.1-flash-image",
    },
]

TASK_KEYS = {t["key"] for t in TASKS}

# price_in / price_out — $ за 1M токенов; price_hour — $ за час звука
CATALOG = [
    # --- LLM ---
    {"id": "qwen/qwen3.7-flash", "kind": "llm", "name": "Qwen 3.7 Flash", "price_in": 0.03, "price_out": 0.13,
     "badge": "Дешевле всех", "note": "Самая низкая цена. Простые задачи, русский попроще."},
    {"id": "z-ai/glm-5.3-flash", "kind": "llm", "name": "GLM 5.3 Flash", "price_in": 0.075, "price_out": 0.25,
     "badge": "Дёшево", "note": "Почти бесплатно и заметно умнее Qwen Flash. Хороша для «запомни это»."},
    {"id": "deepseek/deepseek-v4.1-flash", "kind": "llm", "name": "DeepSeek V4.1 Flash", "price_in": 0.043,
     "price_out": 1.04, "badge": "Лучшая цена/качество",
     "note": "Сильная модель с очень дешёвым входом — выгодна для итогов длинных разговоров."},
    {"id": "anthropic/claude-haiku-5.5", "kind": "llm", "name": "Claude Haiku 5.5", "price_in": 0.10,
     "price_out": 0.50, "badge": "Качество",
     "note": "Быстрая, аккуратно следует инструкциям, хороший русский. Запросы длиннее 100K токенов — в 5 раз дороже."},
    {"id": "openai/gpt-5-mini", "kind": "llm", "name": "GPT-5 mini", "price_in": 0.25, "price_out": 2.00,
     "badge": "Альтернатива", "note": "Надёжная модель OpenAI среднего уровня."},
    {"id": "google/gemini-3.7-flash", "kind": "llm", "name": "Gemini 3.7 Flash", "price_in": 0.75,
     "price_out": 3.75, "badge": "Премиум", "note": "Для важных длинных встреч, когда нужна максимальная точность."},
    {"id": "google/gemini-3.5-flash-lite", "kind": "llm", "name": "Gemini 3.5 Flash Lite", "price_in": 0.30,
     "price_out": 2.50, "badge": "Видит фото", "note": "Быстрая модель с пониманием изображений — для подписей к фото."},
    # --- генерация изображений ---
    {"id": "google/gemini-3.1-flash-image", "kind": "image", "name": "Nano Banana 2 (Gemini 3.1 Flash Image)",
     "price_in": 0.50, "price_out": 60.0, "badge": "Стилизация",
     "note": "Перерисовка фото в стиле. Около $0.08 за картинку."},
    # --- распознавание речи ---
    {"id": "openai/whisper-large-v3-turbo", "kind": "stt", "name": "Whisper Large v3 Turbo", "price_hour": 0.011,
     "badge": "Быстро и дёшево", "note": "Очень быстрый — для вопросов с кнопки."},
    {"id": "openai/whisper-large-v3", "kind": "stt", "name": "Whisper Large v3", "price_hour": 0.029,
     "badge": "Рекомендую", "note": "Лучший баланс точности русского и цены для всей записи."},
    {"id": "microsoft/mai-transcribe-2", "kind": "stt", "name": "MAI Transcribe 2", "price_hour": 0.10,
     "badge": "Альтернатива", "note": "60 языков, хорошее качество."},
    {"id": "elevenlabs/scribe-v2", "kind": "stt", "name": "ElevenLabs Scribe v2", "price_hour": 0.22,
     "badge": "Максимум точности", "note": "Один из самых точных; дороже Whisper в 7 раз."},
]


def task(key: str) -> dict | None:
    return next((t for t in TASKS if t["key"] == key), None)


def catalog_entry(model_id: str) -> dict | None:
    return next((m for m in CATALOG if m["id"] == model_id), None)
