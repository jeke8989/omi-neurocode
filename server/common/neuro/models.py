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


# ---------------------------------------------------------------- переводы для приложения (ru — в полях выше)
TASKS_I18N = {
    "ask": {
        "en": ("Agent: quick (voice)", "Level 1: press the pendant button, ask, hear the answer in a couple of seconds."),
        "tr": ("Ajan: hızlı (ses)", "Seviye 1: kolye düğmesine basın, sorun, cevabı birkaç saniyede kulaklıkta duyun."),
        "es": ("Agente: rápido (voz)", "Nivel 1: pulsa el botón del colgante, pregunta y escucha la respuesta en segundos."),
    },
    "ask_stt": {
        "en": ("Question recognition", "The short question after a button press. Speed matters."),
        "tr": ("Soru tanıma", "Düğmeye bastıktan sonraki kısa soru. Hız önemli."),
        "es": ("Reconocimiento de la pregunta", "La pregunta corta tras pulsar el botón. Importa la velocidad."),
    },
    "transcribe": {
        "en": ("Recording transcription", "All audio from the pendant. Used when the server runs STT_PROVIDER=openrouter."),
        "tr": ("Kayıt transkripsiyonu", "Kolyedeki tüm ses. Sunucuda STT_PROVIDER=openrouter ise kullanılır."),
        "es": ("Transcripción de grabaciones", "Todo el audio del colgante. Se usa si el servidor tiene STT_PROVIDER=openrouter."),
    },
    "summary": {
        "en": ("Conversation summaries", "Title, summary, tasks and facts. Long input, so input price matters."),
        "tr": ("Konuşma özetleri", "Başlık, özet, görevler ve bilgiler. Girdi uzun, bu yüzden girdi fiyatı önemli."),
        "es": ("Resúmenes de conversaciones", "Título, resumen, tareas y datos. La entrada es larga: importa su precio."),
    },
    "remember": {
        "en": ("“Remember this”", "Double press: a minute of conversation becomes one note. The cheapest model is enough."),
        "tr": ("“Bunu hatırla”", "Çift basış: bir dakikalık konuşma tek bir nota dönüşür. En ucuz model yeterli."),
        "es": ("«Recuerda esto»", "Doble pulsación: un minuto de conversación se convierte en una nota. Basta el modelo más barato."),
    },
    "agent": {
        "en": ("Agent: thorough", "Level 2: uses tools — search, tasks, memory, extensions."),
        "tr": ("Ajan: kapsamlı", "Seviye 2: araçları kullanır — arama, görevler, hafıza, eklentiler."),
        "es": ("Agente: a fondo", "Nivel 2: usa herramientas — búsqueda, tareas, memoria, extensiones."),
    },
    "agent_deep": {
        "en": ("Agent: deep", "Level 3: plans first, then works in many steps. Worth a strong model."),
        "tr": ("Ajan: derin", "Seviye 3: önce plan yapar, sonra çok adımlı çalışır. Güçlü bir model önerilir."),
        "es": ("Agente: profundo", "Nivel 3: primero planifica y luego trabaja en varios pasos. Conviene un modelo potente."),
    },
    "search": {
        "en": ("Web search", "Reads search results and retells them briefly with sources."),
        "tr": ("Web araması", "Arama sonuçlarını okur ve kaynaklarla kısaca özetler."),
        "es": ("Búsqueda web", "Lee los resultados y los resume brevemente con fuentes."),
    },
    "diary": {
        "en": ("Daily diary", "Builds the day's story: mood, highlights, places."),
        "tr": ("Günlük", "Günün hikâyesini oluşturur: ruh hâli, öne çıkanlar, yerler."),
        "es": ("Diario del día", "Arma la historia del día: estado de ánimo, momentos destacados, lugares."),
    },
    "vision": {
        "en": ("Photo captions", "Looks at a photo and writes a short caption for the diary."),
        "tr": ("Fotoğraf açıklamaları", "Fotoğrafa bakar ve günlük için kısa bir açıklama yazar."),
        "es": ("Pies de foto", "Mira la foto y escribe un pie breve para el diario."),
    },
    "image_style": {
        "en": ("Photo styling", "Redraws the day's photos in the chosen style. About $0.08 per image."),
        "tr": ("Fotoğraf stilizasyonu", "Günün fotoğraflarını seçilen stilde yeniden çizer. Görsel başına yaklaşık $0.08."),
        "es": ("Estilizado de fotos", "Redibuja las fotos del día en el estilo elegido. Unos $0.08 por imagen."),
    },
}

CATALOG_I18N = {
    "qwen/qwen3.7-flash": {
        "en": ("Cheapest", "Lowest price. Simple tasks; weaker Russian."),
        "tr": ("En ucuz", "En düşük fiyat. Basit görevler için."),
        "es": ("La más barata", "El precio más bajo. Para tareas simples."),
    },
    "z-ai/glm-5.3-flash": {
        "en": ("Cheap", "Almost free and noticeably smarter than Qwen Flash. Good for “remember this”."),
        "tr": ("Ucuz", "Neredeyse bedava ve Qwen Flash'tan belirgin şekilde akıllı. “Bunu hatırla” için iyi."),
        "es": ("Barata", "Casi gratis y bastante más lista que Qwen Flash. Buena para «recuerda esto»."),
    },
    "deepseek/deepseek-v4.1-flash": {
        "en": ("Best value", "A strong model with very cheap input, ideal for summarizing long conversations."),
        "tr": ("En iyi fiyat/performans", "Girdisi çok ucuz güçlü bir model; uzun konuşmaları özetlemek için ideal."),
        "es": ("Mejor relación calidad-precio", "Modelo potente con entrada muy barata, ideal para resumir conversaciones largas."),
    },
    "anthropic/claude-haiku-5.5": {
        "en": ("Quality", "Fast and follows instructions carefully. Requests over 100K tokens cost 5× more."),
        "tr": ("Kalite", "Hızlı ve talimatlara özenle uyar. 100K token üzeri istekler 5 kat pahalı."),
        "es": ("Calidad", "Rápida y sigue las instrucciones con cuidado. Las peticiones de más de 100K tokens cuestan 5 veces más."),
    },
    "openai/gpt-5-mini": {
        "en": ("Alternative", "A reliable mid-tier OpenAI model."),
        "tr": ("Alternatif", "OpenAI'nin güvenilir orta seviye modeli."),
        "es": ("Alternativa", "Un modelo fiable de gama media de OpenAI."),
    },
    "google/gemini-3.7-flash": {
        "en": ("Premium", "For important long meetings when you need maximum accuracy."),
        "tr": ("Premium", "En yüksek doğruluk gereken önemli uzun toplantılar için."),
        "es": ("Premium", "Para reuniones largas e importantes que exigen la máxima precisión."),
    },
    "google/gemini-3.5-flash-lite": {
        "en": ("Sees photos", "A fast model that understands images, for photo captions."),
        "tr": ("Fotoğrafları görür", "Görselleri anlayan hızlı model; fotoğraf açıklamaları için."),
        "es": ("Ve fotos", "Modelo rápido que entiende imágenes, para los pies de foto."),
    },
    "google/gemini-3.1-flash-image": {
        "en": ("Styling", "Redraws photos in a style. About $0.08 per image."),
        "tr": ("Stilizasyon", "Fotoğrafları bir stilde yeniden çizer. Görsel başına yaklaşık $0.08."),
        "es": ("Estilizado", "Redibuja fotos en un estilo. Unos $0.08 por imagen."),
    },
    "openai/whisper-large-v3-turbo": {
        "en": ("Fast and cheap", "Very fast, for button questions."),
        "tr": ("Hızlı ve ucuz", "Çok hızlı; düğme soruları için."),
        "es": ("Rápido y barato", "Muy rápido, para las preguntas con el botón."),
    },
    "openai/whisper-large-v3": {
        "en": ("Recommended", "The best balance of accuracy and price for the whole recording."),
        "tr": ("Önerilen", "Tüm kayıt için doğruluk ve fiyatın en iyi dengesi."),
        "es": ("Recomendado", "El mejor equilibrio entre precisión y precio para toda la grabación."),
    },
    "microsoft/mai-transcribe-2": {
        "en": ("Alternative", "60 languages, good quality."),
        "tr": ("Alternatif", "60 dil, iyi kalite."),
        "es": ("Alternativa", "60 idiomas, buena calidad."),
    },
    "elevenlabs/scribe-v2": {
        "en": ("Maximum accuracy", "One of the most accurate; 7× the price of Whisper."),
        "tr": ("En yüksek doğruluk", "En doğru modellerden biri; Whisper'ın 7 katı fiyat."),
        "es": ("Máxima precisión", "De los más precisos; cuesta 7 veces más que Whisper."),
    },
}


def localized_task(t: dict, lang: str) -> dict:
    tr = TASKS_I18N.get(t["key"], {}).get(lang)
    return {**t, "title": tr[0], "description": tr[1]} if tr else dict(t)


def localized_model(m: dict, lang: str) -> dict:
    tr = CATALOG_I18N.get(m["id"], {}).get(lang)
    return {**m, "badge": tr[0], "note": tr[1]} if tr else dict(m)
