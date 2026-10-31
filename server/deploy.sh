#!/usr/bin/env bash
# Установка и обновление сервера Mnemo. Запускать на сервере из папки server/:
#   sudo bash deploy.sh
# или одной командой с нуля (сама скачает код): см. install.sh
# Повторный запуск безопасен: данные сохраняются, контейнеры пересобираются из свежего кода.
set -euo pipefail
cd "$(dirname "$0")"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
# вопросы задаём с терминала: скрипт может прийти через «curl | bash», тогда stdin занят
ask() { local answer=""; if [ -r /dev/tty ]; then read -r -p "$1" answer < /dev/tty || true; fi; printf '%s' "$answer"; }
setenv() { # setenv KEY VALUE — заменить или дописать строку в .env
  if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi
}

# 1. Docker
if ! command -v docker >/dev/null 2>&1; then
  say "Устанавливаю Docker"
  curl -fsSL https://get.docker.com | sh
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Нужен плагин docker compose v2 (apt install docker-compose-plugin)"; exit 1
fi

# 2. .env: пароль базы и ключ доступа приложения создаются здесь и в репозиторий не попадают
PROPS=../neurosoft.properties
prop() { [ -f "$PROPS" ] && grep -E "^$1\s*=" "$PROPS" | head -1 | cut -d= -f2- | tr -d '\r ' ; }
if [ ! -f .env ]; then
  say "Создаю .env"
  cp .env.example .env
  url_port=$(prop server.url | sed -nE 's#.*:([0-9]+)/?$#\1#p')
  [ -n "$url_port" ] && setenv GATEWAY_PORT "$url_port"
  setenv POSTGRES_PASSWORD "$(openssl rand -hex 16)"
  setenv API_TOKEN ""
  chmod 600 .env
fi
# Ключ, который раньше лежал в репозитории (он стал публичным), заменяем на новый случайный.
LEAKED_SHA=abc8f6f55e4418fae706f6bd780402f00e4f2d18dcdf78ea63734a31b1f76bf8
current=$(grep -E '^API_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '\r')
if [ -z "$current" ] || [ "$(printf '%s' "$current" | sha256sum | cut -d' ' -f1)" = "$LEAKED_SHA" ]; then
  if [ -n "$current" ]; then say "Меняю ключ доступа: старый был в публичном репозитории"; ROTATED=1; fi
  setenv API_TOKEN "$(openssl rand -hex 24)"
fi
set -a; . ./.env; set +a
PORT="${GATEWAY_PORT:-8090}"

if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  key=$(ask "Вставьте ключ OpenRouter (sk-or-...), Enter — пропустить: ")
  if [ -n "$key" ]; then
    setenv OPENROUTER_API_KEY "$key"
    OPENROUTER_API_KEY="$key"
  else
    echo "Без ключа запись сохраняется, но ассистент, итоги и дневник не работают. Ключ можно добавить в .env позже."
    setenv STT_PROVIDER whisper
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
for i in $(seq 1 45); do
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
[ "${ROTATED:-0}" = 1 ] && echo "Ключ доступа новый: старые сборки приложения больше не подключатся, это нормально."
echo
echo "Подключите приложение: Mnemo -> «Подключить сервер» -> адрес http://${IP}:${PORT} и код:"
docker compose exec -T gateway python -m neuro.pairing | sed 's/^/    /'
echo "Новый код, если этот истёк:  docker compose exec gateway python -m neuro.pairing"
echo
key=$(docker compose exec -T claude cat /home/claude/.ssh/id_ed25519.pub 2>/dev/null || true)
if [ -n "$key" ]; then
  echo "Claude в чате: добавьте этот ключ в GitHub (Settings -> Deploy keys, Allow write access), чтобы он мог пушить:"
  echo "    $key"
  echo
fi
echo "Логи:      docker compose logs -f --tail 50"
echo "Остановить: docker compose down   (данные сохранятся)"
