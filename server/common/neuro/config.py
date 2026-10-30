"""Настройки сервисов NeuroSoft. Всё читается из переменных окружения (.env)."""
import os


def env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    return value if value not in (None, "") else default


def env_int(name: str, default: int) -> int:
    value = env(name)
    return int(value) if value is not None else default


def env_float(name: str, default: float) -> float:
    value = env(name)
    return float(value) if value is not None else default


def env_bool(name: str, default: bool) -> bool:
    value = env(name)
    if value is None:
        return default
    return value.strip().lower() in ("1", "true", "yes", "on", "да")


# --- База и файлы ---
DATABASE_URL = env("DATABASE_URL", "postgresql://neuro:neuro@db:5432/neuro")
AUDIO_DIR = env("AUDIO_DIR", "/data/audio")
SAMPLE_RATE = 16000
TZ = env("TZ", "Europe/Moscow")

# --- Доступ ---
API_TOKEN = env("API_TOKEN", "")

# --- Нарезка аудио на куски (gateway) ---
CHUNK_MAX_SEC = env_int("CHUNK_MAX_SEC", 30)        # максимальная длина куска для расшифровки
CHUNK_GAP_SEC = env_float("CHUNK_GAP_SEC", 3.0)     # разрыв во времени, после которого начинается новый кусок
CHUNK_IDLE_SEC = env_int("CHUNK_IDLE_SEC", 8)       # закрыть кусок, если звук не приходит N секунд

# --- Разговоры ---
CONVERSATION_GAP_SEC = env_int("CONVERSATION_GAP_SEC", 120)    # пауза, которая разделяет разговоры
CONVERSATION_IDLE_SEC = env_int("CONVERSATION_IDLE_SEC", 180)  # через сколько тишины разговор считается законченным

# --- OpenRouter: один ключ на LLM и распознавание речи ---
OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
OPENROUTER_API_KEY = env("OPENROUTER_API_KEY", "")

# --- Расшифровка (stt) ---
# openrouter — Whisper и др. через OpenRouter (рекомендуется), whisper — локально на сервере,
# deepgram — Deepgram напрямую, openai — любой OpenAI-совместимый /audio/transcriptions, fake — для тестов
STT_PROVIDER = (env("STT_PROVIDER", "openrouter" if OPENROUTER_API_KEY else "whisper") or "whisper").lower()
# Язык расшифровки: пусто — как язык приложения (меняется в приложении), "auto" — определять самому
STT_LANGUAGE = env("STT_LANGUAGE", "")
SILENCE_RMS = env_int("SILENCE_RMS", 0)  # 0 = не пропускать тишину; иначе порог громкости
# Кусок без речи (тишина, ровный шум в машине) не отправляем в распознавание: дешевле и без «галлюцинаций»
# Whisper вроде «Продолжение следует…». Меньше SPEECH_MIN_MS мс речи — пропускаем. 0 — выключить.
SPEECH_MIN_MS = env_int("SPEECH_MIN_MS", 0 if STT_PROVIDER == "fake" else 300)
DEEPGRAM_API_KEY = env("DEEPGRAM_API_KEY", "")
DEEPGRAM_MODEL = env("DEEPGRAM_MODEL", "nova-2")
# Куда слать /audio/transcriptions (и для записи при STT_PROVIDER=openrouter/openai, и для вопросов с кнопки)
OPENAI_STT_BASE_URL = env("OPENAI_STT_BASE_URL", OPENROUTER_BASE_URL if OPENROUTER_API_KEY else "https://api.openai.com/v1")
OPENAI_STT_API_KEY = env("OPENAI_STT_API_KEY", OPENROUTER_API_KEY)
OPENAI_STT_MODEL = env("OPENAI_STT_MODEL", "")  # пусто = модель из настроек приложения
WHISPER_MODEL = env("WHISPER_MODEL", "small")
WHISPER_DEVICE = env("WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE = env("WHISPER_COMPUTE", "int8")
WHISPER_BEAM = env_int("WHISPER_BEAM", 1)
WHISPER_MODELS_DIR = env("WHISPER_MODELS_DIR", "/models")

# --- Агент / LLM ---
LLM_BASE_URL = env("LLM_BASE_URL", OPENROUTER_BASE_URL)
LLM_API_KEY = env("LLM_API_KEY", OPENROUTER_API_KEY)
LLM_MODEL = env("LLM_MODEL", "")  # если задано — модель по умолчанию для всех задач вместо рекомендованных
LLM_JSON_MODE = env_bool("LLM_JSON_MODE", True)
LLM_MAX_CHARS = env_int("LLM_MAX_CHARS", 60000)
REMEMBER_WINDOW_SEC = env_int("REMEMBER_WINDOW_SEC", 60)  # двойное нажатие: сколько секунд назад брать
COMMAND_WINDOW_SEC = env_int("COMMAND_WINDOW_SEC", 20)    # одиночное нажатие: сколько секунд после — команда агенту
EVENT_SETTLE_SEC = env_int("EVENT_SETTLE_SEC", 60)        # ждать, пока звук вокруг нажатия дойдёт и расшифруется
KEEP_AUDIO_DAYS = env_int("KEEP_AUDIO_DAYS", 3)

# --- Голосовой вопрос по кнопке ---
ASK_MAX_SEC = env_float("ASK_MAX_SEC", 15.0)           # максимум длины вопроса
ASK_END_SILENCE_MS = env_int("ASK_END_SILENCE_MS", 1200)  # пауза, после которой вопрос считается законченным
ASK_CONTEXT_MIN = env_int("ASK_CONTEXT_MIN", 15)       # сколько минут недавнего разговора давать ассистенту
ASK_TIMEOUT_SEC = env_int("ASK_TIMEOUT_SEC", 25)

# --- Экспорт задач ---
AUTO_EXPORT = env_bool("AUTO_EXPORT", False)
NOTION_TOKEN = env("NOTION_TOKEN", "")
NOTION_DATABASE_ID = env("NOTION_DATABASE_ID", "")
NOTION_TITLE_PROPERTY = env("NOTION_TITLE_PROPERTY", "Name")
GITHUB_TOKEN = env("GITHUB_TOKEN", "")
GITHUB_REPO = env("GITHUB_REPO", "")  # owner/repo
