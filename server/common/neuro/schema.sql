-- Схема NeuroSoft. Выполняется при старте каждого сервиса (идемпотентно).

CREATE TABLE IF NOT EXISTS devices (
    device_id   TEXT PRIMARY KEY,
    last_seen   TIMESTAMPTZ,
    last_audio  TIMESTAMPTZ,
    info        JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- Куски аудио (PCM 16 кГц, моно, 16 бит) для расшифровки
CREATE TABLE IF NOT EXISTS audio_chunks (
    id           BIGSERIAL PRIMARY KEY,
    device_id    TEXT NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    duration_ms  INTEGER NOT NULL,
    path         TEXT,
    status       TEXT NOT NULL DEFAULT 'pending',  -- pending | processing | done | empty | failed
    attempts     INTEGER NOT NULL DEFAULT 0,
    not_before   TIMESTAMPTZ,
    locked_at    TIMESTAMPTZ,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS audio_chunks_status_idx ON audio_chunks (status, started_at);
CREATE INDEX IF NOT EXISTS audio_chunks_device_idx ON audio_chunks (device_id, started_at);
CREATE UNIQUE INDEX IF NOT EXISTS audio_chunks_path_idx ON audio_chunks (path) WHERE path IS NOT NULL;

CREATE TABLE IF NOT EXISTS conversations (
    id             BIGSERIAL PRIMARY KEY,
    device_id      TEXT NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    last_activity  TIMESTAMPTZ NOT NULL,
    status         TEXT NOT NULL DEFAULT 'open',  -- open | processing | done | failed
    title          TEXT,
    summary        TEXT,
    attempts       INTEGER NOT NULL DEFAULT 0,
    error          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS conversations_device_idx ON conversations (device_id, last_activity DESC);
CREATE INDEX IF NOT EXISTS conversations_status_idx ON conversations (status, last_activity);
CREATE INDEX IF NOT EXISTS conversations_started_idx ON conversations (started_at DESC);

CREATE TABLE IF NOT EXISTS segments (
    id               BIGSERIAL PRIMARY KEY,
    chunk_id         BIGINT REFERENCES audio_chunks(id) ON DELETE SET NULL,
    conversation_id  BIGINT REFERENCES conversations(id) ON DELETE CASCADE,
    device_id        TEXT NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ NOT NULL,
    speaker          INTEGER,
    text             TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS segments_conv_idx ON segments (conversation_id, started_at);
CREATE INDEX IF NOT EXISTS segments_device_time_idx ON segments (device_id, started_at);

CREATE TABLE IF NOT EXISTS tasks (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  BIGINT REFERENCES conversations(id) ON DELETE SET NULL,
    text             TEXT NOT NULL,
    due              TEXT,
    assignee         TEXT,
    target           TEXT NOT NULL DEFAULT 'notion',     -- notion | github
    source           TEXT NOT NULL DEFAULT 'conversation', -- conversation | button
    status           TEXT NOT NULL DEFAULT 'open',       -- open | done
    exported_at      TIMESTAMPTZ,
    export_ref       TEXT,
    export_error     TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS tasks_status_idx ON tasks (status, created_at DESC);

CREATE TABLE IF NOT EXISTS memories (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  BIGINT REFERENCES conversations(id) ON DELETE SET NULL,
    text             TEXT NOT NULL,
    source           TEXT NOT NULL DEFAULT 'conversation', -- conversation | button
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS memories_created_idx ON memories (created_at DESC);

-- События с кулона (кнопка)
CREATE TABLE IF NOT EXISTS events (
    id          BIGSERIAL PRIMARY KEY,
    device_id   TEXT NOT NULL,
    client_id   TEXT NOT NULL,
    kind        TEXT NOT NULL,
    value       INTEGER,
    ts          TIMESTAMPTZ NOT NULL,
    status      TEXT NOT NULL DEFAULT 'pending',  -- pending | done | failed
    result      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (device_id, client_id)
);
CREATE INDEX IF NOT EXISTS events_status_idx ON events (status, created_at);

-- Защита от повторной загрузки одного и того же пакета с телефона
CREATE TABLE IF NOT EXISTS received_batches (
    device_id    TEXT NOT NULL,
    batch_id     TEXT NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, batch_id)
);

-- Настройки, которые меняются из приложения (например, model.summary = deepseek/deepseek-v4.1-flash)
CREATE TABLE IF NOT EXISTS settings (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Учёт расходов на модели
CREATE TABLE IF NOT EXISTS llm_usage (
    id                 BIGSERIAL PRIMARY KEY,
    task               TEXT NOT NULL,
    model              TEXT NOT NULL,
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    audio_seconds      REAL,
    cost               DOUBLE PRECISION,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS llm_usage_created_idx ON llm_usage (created_at);

-- Голосовые вопросы по кнопке
CREATE TABLE IF NOT EXISTS questions (
    id          BIGSERIAL PRIMARY KEY,
    device_id   TEXT NOT NULL,
    client_id   TEXT,
    asked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    question    TEXT,
    answer      TEXT,
    model       TEXT,
    latency_ms  INTEGER,
    via         TEXT NOT NULL DEFAULT 'voice',  -- voice | offline
    error       TEXT
);
CREATE INDEX IF NOT EXISTS questions_asked_idx ON questions (asked_at DESC);

-- Чат с агентом (текст из приложения и голосовые вопросы с кулона)
CREATE TABLE IF NOT EXISTS chat_messages (
    id          BIGSERIAL PRIMARY KEY,
    role        TEXT NOT NULL,                  -- user | assistant
    text        TEXT NOT NULL,
    channel     TEXT NOT NULL DEFAULT 'chat',   -- chat | voice
    run_id      BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS chat_messages_id_idx ON chat_messages (id DESC);

-- Запуски агента и их шаги (показываются в чате коротко)
CREATE TABLE IF NOT EXISTS agent_runs (
    id           BIGSERIAL PRIMARY KEY,
    level        TEXT NOT NULL,                  -- fast | tools | deep
    status       TEXT NOT NULL DEFAULT 'running', -- running | done | failed
    question     TEXT NOT NULL,
    answer       TEXT,
    model        TEXT,
    channel      TEXT NOT NULL DEFAULT 'chat',
    device_id    TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS agent_steps (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL,   -- level | plan | tool | result | error
    title       TEXT NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS agent_steps_run_idx ON agent_steps (run_id, id);

-- Установленные расширения из маркетплейса
CREATE TABLE IF NOT EXISTS extensions (
    id            TEXT PRIMARY KEY,
    manifest      JSONB NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    settings      JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_url    TEXT,
    installed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Дневник жизни: геолокация, фото, ежедневная лента с хайлайтами
CREATE TABLE IF NOT EXISTS locations (
    id         BIGSERIAL PRIMARY KEY,
    device_id  TEXT NOT NULL,
    client_id  TEXT NOT NULL,
    ts         TIMESTAMPTZ NOT NULL,
    lat        DOUBLE PRECISION NOT NULL,
    lon        DOUBLE PRECISION NOT NULL,
    accuracy   REAL,
    UNIQUE (device_id, client_id)
);
CREATE INDEX IF NOT EXISTS locations_ts_idx ON locations (ts);

CREATE TABLE IF NOT EXISTS places (
    key         TEXT PRIMARY KEY,   -- округлённые координаты
    name        TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS photos (
    id           BIGSERIAL PRIMARY KEY,
    sha256       TEXT UNIQUE NOT NULL,
    taken_at     TIMESTAMPTZ NOT NULL,
    lat          DOUBLE PRECISION,
    lon          DOUBLE PRECISION,
    path         TEXT NOT NULL,
    styled_path  TEXT,
    style        TEXT,
    caption      TEXT,
    status       TEXT NOT NULL DEFAULT 'pending',  -- pending | done | failed
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS photos_taken_idx ON photos (taken_at);

CREATE TABLE IF NOT EXISTS diary_days (
    day             DATE PRIMARY KEY,
    title           TEXT,
    summary         TEXT,
    mood            TEXT,
    quote           TEXT,
    highlights      JSONB NOT NULL DEFAULT '[]'::jsonb,
    places          JSONB NOT NULL DEFAULT '[]'::jsonb,
    stats           JSONB NOT NULL DEFAULT '{}'::jsonb,
    cover_photo_id  BIGINT,
    status          TEXT NOT NULL DEFAULT 'pending',  -- pending | done | failed
    content_hash    TEXT,
    generated_at    TIMESTAMPTZ,
    error           TEXT
);

-- Куда выгружена задача (интеграции из маркетплейса с хуком on_task)
CREATE TABLE IF NOT EXISTS task_exports (
    task_id     BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    ext_id      TEXT NOT NULL,
    ok          BOOLEAN NOT NULL,
    ref         TEXT,
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, ext_id)
);

-- Эмодзи разговора для ленты (выбирает агент)
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS emoji TEXT;

-- Темы разговора (2–4 коротких метки) — для «Созвездия»
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS topics TEXT[] NOT NULL DEFAULT '{}';

-- Голоса: люди, которых узнаёт кулон. Имени нет — показывается «Голос N».
CREATE TABLE IF NOT EXISTS people (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT,
    is_owner    BOOLEAN NOT NULL DEFAULT false,
    ignored     BOOLEAN NOT NULL DEFAULT false,   -- «не учитывать» (телевизор, радио, случайный прохожий)
    number      INTEGER NOT NULL,
    centroid    REAL[],                           -- средний отпечаток голоса
    samples     INTEGER NOT NULL DEFAULT 0,
    speech_sec  REAL NOT NULL DEFAULT 0,
    loudness    REAL,
    note        TEXT,
    first_seen  TIMESTAMPTZ,
    last_seen   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS people_owner_idx ON people (is_owner) WHERE is_owner;

CREATE TABLE IF NOT EXISTS voiceprints (
    id          BIGSERIAL PRIMARY KEY,
    person_id   BIGINT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    embedding   REAL[] NOT NULL,
    seconds     REAL NOT NULL,
    backend     TEXT NOT NULL,
    confirmed   BOOLEAN NOT NULL DEFAULT false,
    segment_id  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS voiceprints_person_idx ON voiceprints (person_id, confirmed, id DESC);

-- Подсказки имён от ассистента: «Голос 3 — это, похоже, Анна» (пользователь подтверждает)
CREATE TABLE IF NOT EXISTS person_suggestions (
    id               BIGSERIAL PRIMARY KEY,
    person_id        BIGINT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    conversation_id  BIGINT REFERENCES conversations(id) ON DELETE SET NULL,
    evidence         TEXT,
    status           TEXT NOT NULL DEFAULT 'pending',  -- pending | accepted | rejected
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS person_suggestions_idx ON person_suggestions (status, person_id);

ALTER TABLE segments ADD COLUMN IF NOT EXISTS person_id BIGINT REFERENCES people(id) ON DELETE SET NULL;
ALTER TABLE segments ADD COLUMN IF NOT EXISTS voice_score REAL;
ALTER TABLE segments ADD COLUMN IF NOT EXISTS embedding REAL[];
CREATE INDEX IF NOT EXISTS segments_person_idx ON segments (person_id, started_at);

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS person_id BIGINT REFERENCES people(id) ON DELETE SET NULL;

-- Запросы на подключение нового телефона: подтверждаются на уже подключённом телефоне
CREATE TABLE IF NOT EXISTS pair_requests (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    ip          TEXT,
    status      TEXT NOT NULL DEFAULT 'pending',  -- pending | approved | rejected
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Голоса: модель отпечатков (отпечатки разных моделей несравнимы), решение по всему разговору
ALTER TABLE people ADD COLUMN IF NOT EXISTS backend TEXT;
UPDATE people SET backend = 'wespeaker_en_voxceleb_resnet34_LM' WHERE backend IS NULL AND centroid IS NOT NULL
    AND EXISTS (SELECT 1 FROM voiceprints v WHERE v.person_id = people.id AND v.backend = 'wespeaker');
UPDATE voiceprints SET backend = 'wespeaker_en_voxceleb_resnet34_LM' WHERE backend = 'wespeaker';
ALTER TABLE segments ADD COLUMN IF NOT EXISTS embed_backend TEXT;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS voices_done BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE person_suggestions ADD COLUMN IF NOT EXISTS target_id BIGINT REFERENCES people(id) ON DELETE CASCADE;

-- Задачи: вид (моя / жду от / напоминание), уверенность и фраза-доказательство, настоящий срок
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'mine';
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS confidence REAL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS evidence TEXT;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS due_at TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
-- фразы, проверенные на «напомни / создай задачу»
ALTER TABLE segments ADD COLUMN IF NOT EXISTS task_checked BOOLEAN NOT NULL DEFAULT false;
UPDATE segments SET task_checked = true WHERE NOT task_checked AND started_at < now() - interval '1 day';

-- События дня: фрагменты разговоров, собранные в одну ситуацию; мусор — в корзину на 7 дней
CREATE TABLE IF NOT EXISTS day_events (
    id          BIGSERIAL PRIMARY KEY,
    day         DATE NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    ended_at    TIMESTAMPTZ NOT NULL,
    title       TEXT,
    emoji       TEXT,
    kind        TEXT NOT NULL DEFAULT 'другое',
    summary     TEXT,
    people      BIGINT[] NOT NULL DEFAULT '{}',
    topics      TEXT[] NOT NULL DEFAULT '{}',
    photo_id    BIGINT,
    status      TEXT NOT NULL DEFAULT 'open',   -- open | final
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS day_events_day_idx ON day_events (day, started_at);
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS event_id BIGINT REFERENCES day_events(id) ON DELETE SET NULL;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS trashed_at TIMESTAMPTZ;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS junk_reason TEXT;
CREATE INDEX IF NOT EXISTS conversations_event_idx ON conversations (event_id);

-- Чистая расшифровка: исходный текст фразы, мусор (не показывается), проверена ли моделью
ALTER TABLE segments ADD COLUMN IF NOT EXISTS raw_text TEXT;
ALTER TABLE segments ADD COLUMN IF NOT EXISTS junk BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE segments ADD COLUMN IF NOT EXISTS cleaned BOOLEAN NOT NULL DEFAULT false;
UPDATE segments SET cleaned = true WHERE NOT cleaned AND started_at < now() - interval '1 day';

-- Шифрование от утечек (crypto.py): координаты — только зашифрованной строкой pos
ALTER TABLE locations ADD COLUMN IF NOT EXISTS pos TEXT;
ALTER TABLE locations ALTER COLUMN lat DROP NOT NULL;
ALTER TABLE locations ALTER COLUMN lon DROP NOT NULL;
ALTER TABLE photos ADD COLUMN IF NOT EXISTS pos TEXT;
