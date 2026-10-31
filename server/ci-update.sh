#!/usr/bin/env bash
# Обновление сервера из GitHub Actions после пуша в main (.github/workflows/server.yml, задача deploy).
# Ключ CI лежит в /root/.ssh/authorized_keys с command="/opt/mnemo/server/ci-update.sh": другую команду
# этим ключом не выполнить. В отличие от deploy.sh ничего не спрашивает и не печатает код подключения —
# логи CI публичные. Данные и .env не трогает.
#
# Пересобирает только сервисы, чьи файлы изменились, по одному: на маленьком диске (рядом Jarvis и другие
# проекты) полная пересборка всех образов не помещается. Места не хватает — останавливается с ошибкой,
# не заполняя диск соседям.
set -euo pipefail

main() {
  local DIR BRANCH OLD NEW changed services f s
  DIR="$(cd "$(dirname "$0")/.." && pwd)"
  BRANCH="${MNEMO_BRANCH:-main}"

  cd "$DIR"
  OLD=$(git rev-parse HEAD)
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    git stash push -q -m "ci-update $(date +%F-%H%M)"
    echo "Локальные правки в коде убраны в git stash"
  fi
  git fetch -q --depth 1 origin "$BRANCH"
  git checkout -q -B "$BRANCH" FETCH_HEAD
  NEW=$(git rev-parse HEAD)
  say "Код: $(git log -1 --format='%h %s')"

  cd server
  [ -f .env ] || { echo "Нет server/.env — сначала установка: sudo bash server/deploy.sh"; exit 1; }
  set -a; . ./.env; set +a
  PORT="${GATEWAY_PORT:-8090}"

  # что пересобирать — по изменённым файлам; ручной запуск без новых коммитов ничего не собирает
  changed=""
  [ "$OLD" != "$NEW" ] && changed=$(git diff --name-only "$OLD" "$NEW" 2>/dev/null || echo "server/common/")
  services=""
  while read -r f; do
    case "$f" in
      server/common/*|server/marketplace/*) add gateway; add stt; add agent ;;
      server/gateway/*) add gateway ;;
      server/stt/*) add stt ;;
      server/agent/*) add agent ;;
      server/claude/*) add claude ;;
    esac
  done <<< "$changed"
  say "Пересобираю: ${services:- ничего (код образов не менялся)}"

  for s in $services; do
    check_space "$s"
    docker compose build -q "$s"
    if [ "$s" = gateway ] || [ "$s" = claude ]; then wait_claude; fi
    docker compose up -d --no-deps "$s"
    tidy
  done

  say "Применяю docker-compose.yml"
  wait_claude
  docker compose up -d --remove-orphans
  tidy

  local ok=0
  for _ in $(seq 1 45); do
    if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then ok=1; break; fi
    sleep 2
  done
  docker compose ps --format 'table {{.Service}}\t{{.Status}}'
  [ "$ok" = 1 ] || { echo "Gateway не ответил. Логи: docker compose logs gateway --tail 100"; exit 1; }
  say "Готово: $(curl -fsS "http://127.0.0.1:${PORT}/health/full" 2>/dev/null || echo '?'), свободно $(free_mb) МБ"
}

say() { printf '\n==> %s\n' "$*"; }
add() { case " $services " in *" $1 "*) ;; *) services="${services:+$services }$1" ;; esac; }
free_mb() { df -Pm / | awk 'NR==2{print $4}'; }

# на сборку нужно примерно 1.3 размера текущего образа + запас; иначе — стоп, а не полный диск
check_space() {
  local img size need
  img=$(docker compose images -q "$1" 2>/dev/null | head -1)
  size=$(docker image inspect -f '{{.Size}}' "$img" 2>/dev/null || echo 0)
  need=$(( size / 1048576 * 13 / 10 + 300 ))
  if [ "$(free_mb)" -lt "$need" ]; then
    echo "Мало места на диске: свободно $(free_mb) МБ, для сборки $1 нужно ~${need} МБ."
    echo "Освободите место или перенесите Mnemo на сервер с диском побольше. Работающие контейнеры не тронуты."
    exit 1
  fi
}

# старые слои после пересборки; на маленьком диске (< 40 ГБ) кэш сборки тоже не держим
tidy() {
  docker image prune -f >/dev/null 2>&1 || true
  if [ "$(df -Pm / | awk 'NR==2{print $2}')" -lt 40000 ]; then docker builder prune -af >/dev/null 2>&1 || true; fi
}

# Claude мог сам запушить это обновление и ещё дописывает ответ — не обрываем его (до 5 минут)
wait_claude() {
  local i n
  for i in $(seq 1 60); do
    n=$(docker compose exec -T db psql -U neuro -d neuro -tAc \
        "SELECT count(*) FROM agent_runs WHERE level IN ('claude', 'claude_code') AND status = 'running'" 2>/dev/null || echo 0)
    [ "${n:-0}" = 0 ] && return 0
    [ "$i" = 1 ] && echo "Claude ещё отвечает в чате — жду"
    sleep 5
  done
}

# весь скрипт прочитан до запуска: git checkout выше подменяет этот файл на ходу
main "$@"
exit
