#!/usr/bin/env bash
# Установка / обновление сервера NeuroSoft. Запускать на сервере из папки server/:
#   cd neurosoft/server && sudo bash deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# 1. Docker
if ! command -v docker >/dev/null 2>&1; then
  say "Устанавливаю Docker"
  curl -fsSL https://get.docker.com | sh
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Нужен плагин docker compose v2 (apt install docker-compose-plugin)"; exit 1
fi

# 2. .env с паролем и токеном. Токен берём из ../neurosoft.properties — тот же зашит в приложение.
PROPS=../neurosoft.properties
prop() { [ -f "$PROPS" ] && grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2- | tr -d '\r'; }
if [ ! -f .env ]; then
  say "Создаю .env"
  cp .env.example .env
  token=$(prop api.token)
  [ -n "$token" ] || token=$(openssl rand -hex 24)
  url_port=$(prop server.url | sed -nE 's#.*:([0-9]+)/?$#\1#p')
  [ -n "$url_port" ] && sed -i "s/^GATEWAY_PORT=.*/GATEWAY_PORT=${url_port}/" .env
  dbpass=$(openssl rand -hex 16)
  sed -i "s/^API_TOKEN=.*/API_TOKEN=${token}/" .env
  sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=${dbpass}/" .env
  chmod 600 .env
fi
# если токен в neurosoft.properties сменился — обновляем .env, иначе приложение не подключится
ptoken=$(prop api.token)
if [ -n "$ptoken" ] && ! grep -q "^API_TOKEN=${ptoken}$" .env; then
  sed -i "s/^API_TOKEN=.*/API_TOKEN=${ptoken}/" .env
fi
set -a; . ./.env; set +a
PORT="${GATEWAY_PORT:-8090}"

if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  read -r -p "Вставьте ключ OpenRouter (sk-or-...), Enter — пропустить: " key || true
  if [ -n "${key:-}" ]; then
    sed -i "s|^OPENROUTER_API_KEY=.*|OPENROUTER_API_KEY=${key}|" .env
    OPENROUTER_API_KEY="$key"
  else
    echo "Без ключа запись сохраняется, но ассистент, итоги и дневник не работают. Ключ можно добавить в .env позже."
    sed -i "s|^STT_PROVIDER=.*|STT_PROVIDER=whisper|" .env
  fi
fi

# 3. Порт не должен быть занят чужим сервисом (например, Jarvis)
if ss -ltn "( sport = :${PORT} )" 2>/dev/null | grep -q LISTEN; then
  if ! docker compose ps --status running gateway 2>/dev/null | grep -q gateway; then
    echo "Порт ${PORT} уже занят другим сервисом. Поменяйте GATEWAY_PORT в .env и запустите снова."
    ss -ltnp "( sport = :${PORT} )" || true
    exit 1
  fi
fi

# 4. Сборка и запуск
say "Собираю и запускаю контейнеры (первый раз 3–10 минут)"
docker compose up -d --build

# 5. Файрвол
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow "${PORT}/tcp" >/dev/null && echo "ufw: открыт порт ${PORT}/tcp"
fi

# 6. Проверка
say "Проверяю gateway"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done
docker compose ps
if [ "${ok:-0}" != 1 ]; then
  echo "Gateway не ответил. Логи: docker compose logs gateway --tail 100"; exit 1
fi

IP=$(curl -fsS https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')
say "Готово"
echo "Сервер работает: http://${IP}:${PORT}"
echo "Приложение подключится само — адрес и ключ уже зашиты в APK (neurosoft.properties)."
echo
echo "Логи:      docker compose logs -f --tail 50"
echo "Остановить: docker compose down   (данные сохранятся)"
