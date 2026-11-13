# Маркетплейс расширений Mnemo

Расширение даёт агенту новые возможности: инструменты, которые он вызывает сам («какая погода в Стамбуле?»), и интеграции задач, когда новая задача из ленты автоматически уходит в сервис. Для расширения не нужен свой сервер: достаточно одного файла `manifest.json`, в котором описаны HTTP-запросы.

## Как установить

**Приложение → Настройки → Расширения.** Там есть:
- **Маркетплейс** — проверенные расширения из `index.json`;
- **Своё расширение** — установка по https-ссылке на `manifest.json`.

## Формат manifest.json

```json
{
  "schema": 1,
  "id": "weather",
  "name": "Погода",
  "icon": "☀️",
  "version": "1.0.0",
  "author": "Ваше имя",
  "description": "Что умеет — показывается в приложении",
  "permissions": {"hosts": ["api.open-meteo.com"]},
  "settings": [
    {"key": "api_key", "title": "API-ключ", "secret": true, "required": true, "hint": "Где взять"}
  ],
  "tools": [
    {
      "name": "forecast",
      "title": "Смотрю прогноз",
      "description": "Для модели: когда и зачем вызывать инструмент",
      "parameters": {"type": "object", "properties": {"latitude": {"type": "number"}}, "required": ["latitude"]},
      "http": {"method": "GET", "url": "https://api.open-meteo.com/v1/forecast?latitude={latitude}"}
    }
  ],
  "tasks": {
    "title": "Задачи → Мой трекер",
    "create": {"http": {"method": "POST", "url": "https://tracker.example.com/api/tasks",
                        "headers": {"Authorization": "Bearer {settings.api_key}"},
                        "body": {"external_id": "{task_uuid}", "title": "{title}", "description": "{details}",
                                 "deadline": "{due_at}"}},
               "id": "data.id", "link": "data.url", "stage": "data.status"},
    "close":  {"http": {"method": "PATCH", "url": "https://tracker.example.com/api/tasks/{external_id}",
                        "headers": {"Authorization": "Bearer {settings.api_key}"}, "body": {"done": true}}},
    "reopen": {"http": {"method": "PATCH", "url": "https://tracker.example.com/api/tasks/{external_id}",
                        "headers": {"Authorization": "Bearer {settings.api_key}"}, "body": {"done": false}}},
    "delete": {"http": {"method": "DELETE", "url": "https://tracker.example.com/api/tasks/{external_id}",
                        "headers": {"Authorization": "Bearer {settings.api_key}"}}}
  }
}
```

| Поле | Смысл |
|---|---|
| `id` | латиница, цифры, дефис; 2–32 символа |
| `permissions.hosts` | **единственные** домены, куда расширение может ходить. Запрос на другой домен блокируется |
| `settings` | что вводит пользователь в приложении. `secret: true` — значение никогда не возвращается в приложение |
| `tools[].parameters` | JSON Schema аргументов, которые заполняет модель |
| `tools[].http` | декларативный запрос: `method`, `url`, `headers`, `body` |
| `tools[].webhook` | вместо `http`: `{"url": "https://ваш-сервер/run"}`. Сервер Mnemo пришлёт POST `{"tool", "arguments", "settings", "context"}` и ждёт `{"result": ...}` |
| `tasks` | сервис задач — см. «Задачи в сервисе задач» ниже. Старый `on_task` понимается как `tasks.create` без закрытия |

### Подстановки

- `{имя}` — аргумент инструмента. Для `tasks` — поля задачи (ниже).
- `{settings.ключ}` — настройка пользователя.
- В `url` значения кодируются автоматически. Если значение в `body` — подстановка целиком (`"{amount}"`), его тип сохраняется: число остаётся числом.
- Подстановки работают и в ключах `body`, например `{"{settings.title_property}": ...}`.
- `{app.locale}` — язык приложения (`ru`, `en`, `tr`, `es`), например `language={app.locale}`.
- Домен в `url` не может содержать подстановок: так модель нельзя увести на чужой адрес. Единственное исключение — `{app.locale}` (`https://{app.locale}.wikipedia.org/…`), и тогда в `permissions.hosts` должны быть все варианты домена.

### Задачи в сервисе задач (`tasks`)

Задача сама никуда не уходит. Пользователь отправляет её кнопкой «Отправить в …» в карточке обещания или просит
ассистента. В настройках интеграции есть переключатель «Отправлять новые задачи автоматически» (по умолчанию
выключен): после включения уходят только задачи, которые появились позже. Дальше Mnemo ведёт задачу там сам:

| В Mnemo | Что вызывается |
|---|---|
| выполнена | `close` |
| смахнута («не задача») | `close` |
| снова открыта | `reopen` |
| удалена | `delete`, а если его нет — `close` |

`create` обязателен, остальное — по желанию (без `close` задача там останется открытой). Ошибка — повтор через
1, 2, 4… минуты, до 6 раз; ошибка видна под задачей в приложении. Повторная отправка той же задачи не создаёт дубль.

**Чей id.** Есть два способа, выбирайте по API сервиса:
- сервис принимает id отправителя (SmartDesk, многие трекеры) — передайте `{task_uuid}` в `create`, а в
  `close`/`reopen`/`delete` используйте `{external_id}` — это тот же id;
- id выдаёт сервис (GitHub — номер issue) — укажите `create.id`: путь к нему в ответе (`number`, `data.id`,
  `items.0.id`); тогда `{external_id}` — этот id.

`create.link` — путь к ссылке на задачу в ответе (приложение откроет её), `create.stage` — к этапу или статусу.
`only_target: "github"` — автоматически отправлять только задачи на разработку.

**Поля задачи для шаблонов:**

| Поле | Что это |
|---|---|
| `{task_uuid}` | постоянный id задачи в Mnemo (UUID) |
| `{external_id}` | id задачи в сервисе (в `close`, `reopen`, `delete`) |
| `{title}` | текст задачи, до 200 символов |
| `{text}` | текст целиком |
| `{details}` | описание: срок, с кем, дословная фраза договорённости, разговор, «— из Mnemo» |
| `{due}` | срок словами или датой (`14.11.2026 10:00`), пусто — без срока |
| `{due_at}` | срок ISO 8601; целиком `"{due_at}"` без срока даёт `null` |
| `{kind}` | `mine` — обещал я, `waiting` — жду от другого, `reminder`, `meeting` |
| `{counterpart}` | с кем договорённость |
| `{source}`, `{conversation}` | откуда задача и название разговора |
| `{done}` | `true` в `close`, иначе `false` |

Вместо `http` в любом действии можно `"webhook": {"url": "{settings.url}"}` (n8n, Make, свой сервер): придёт
POST `{"event": "task.created" | "task.closed" | "task.reopened" | "task.deleted", "data": {поля задачи}}`.
В ответ на `task.created` можно вернуть JSON с id и ссылкой — пути те же, `create.id` и `create.link`.

Готовые примеры: `extensions/smartdesk` (id отправителя) и `extensions/github` (id выдаёт сервис).

### Переводы

Приложение работает на русском, английском, турецком и испанском. Основные поля пишите на русском, а переводы добавьте в `i18n`; чего нет в переводе, останется как в основном поле:

```json
"i18n": {
  "en": {"name": "Weather", "description": "…", "tools": {"forecast": "Checking the forecast"},
         "task_sync": "…", "settings": {"api_key": {"title": "API key", "hint": "…"}}},
  "tr": {"…": "…"},
  "es": {"…": "…"}
}
```
В `index.json` у записи можно указать такой же `i18n` с `name` и `description`.

Ответ сервиса (JSON или текст, до 6000 символов) передаётся модели, она пересказывает его пользователю.

## Как опубликовать в маркетплейсе

1. Положите `extensions/<id>/manifest.json` в этот каталог (или разместите манифест на своём https-хостинге).
2. Добавьте запись в `index.json`:
   ```json
   {"id": "my-ext", "name": "…", "icon": "…", "author": "…", "version": "1.0.0",
    "description": "…", "tags": ["…"], "manifest": "extensions/my-ext/manifest.json"}
   ```
3. Откройте pull request. После проверки (домены, безопасность, качество описаний) расширение получит отметку `verified`.

Чтобы подключить внешний каталог (например, корпоративный), задайте в `.env` сервера `MARKETPLACE_URL=https://…/index.json`. Он объединится со встроенным.

## Проверка перед публикацией

```bash
cd server
PYTHONPATH=common python -c "import json,sys; from neuro import extensions as e; \
  print(e.validate(json.load(open(sys.argv[1])))['id'], 'ok')" marketplace/extensions/my-ext/manifest.json
```
