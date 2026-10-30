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
  "on_task": {
    "title": "Отправлять новые задачи в сервис",
    "only_target": "github",
    "http": {"method": "POST", "url": "https://...", "body": {"title": "{text}"}}
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
| `on_task` | интеграция задач: вызывается для каждой новой задачи (`only_target: "github"` — только для задач на разработку) |

### Подстановки

- `{имя}` — аргумент инструмента. Для `on_task` доступны `{text}`, `{due}`, `{assignee}`, `{source}`, `{conversation}`.
- `{settings.ключ}` — настройка пользователя.
- В `url` значения кодируются автоматически. Если значение в `body` — подстановка целиком (`"{amount}"`), его тип сохраняется: число остаётся числом.
- Подстановки работают и в ключах `body`, например `{"{settings.title_property}": ...}`.
- `{app.locale}` — язык приложения (`ru`, `en`, `tr`, `es`), например `language={app.locale}`.
- Домен в `url` не может содержать подстановок: так модель нельзя увести на чужой адрес. Единственное исключение — `{app.locale}` (`https://{app.locale}.wikipedia.org/…`), и тогда в `permissions.hosts` должны быть все варианты домена.

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
