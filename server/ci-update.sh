#!/usr/bin/env bash
# Обновление сервера из GitHub Actions после пуша в main (.github/workflows/server.yml, задача deploy).
# Ключ CI лежит в /root/.ssh/authorized_keys с command="/opt/mnemo/server/ci-update.sh": другую команду
# этим ключом не выполнить. В отличие от deploy.sh ничего не спрашивает и не печатает код подключения —
# логи CI публичные. Данные и .env не трогает.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
BRANCH="${MNEMO_BRANCH:-main}"
say() { printf '\n==> %s\n' "$*"; }

cd "$DIR"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  git stash push -q -m "ci-update $(date +%F-%H%M)"
  echo "Локальные правки в коде убраны в git stash"
fi
git fetch -q --depth 1 origin "$BRANCH"
git checkout -q -B "$BRANCH" FETCH_HEAD
say "Код: $(git log -1 --format='%h %s')"

cd server
[ -f .env ] || { echo "Нет server/.env — сначала установка: sudo bash server/deploy.sh"; exit 1; }
set -a; . ./.env; set +a
PORT="${GATEWAY_PORT:-8090}"

say "Собираю образы"
docker compose build -q

# Claude мог сам запушить это обновление и ещё дописывает ответ — не обрываем его (до 5 минут)
for i in $(seq 1 60); do
  n=$(docker compose exec -T db psql -U neuro -d neuro -tAc \
      "SELECT count(*) FROM agent_runs WHERE level = 'claude' AND status = 'running'" 2>/dev/null || echo 0)
  [ "${n:-0}" = 0 ] && break
  [ "$i" = 1 ] && echo "Claude ещё отвечает в чате — жду"
  sleep 5
done

say "Перезапускаю контейнеры"
docker compose up -d --remove-orphans

for i in $(seq 1 45); do
  if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
docker image prune -f >/dev/null || true   # старые слои после пересборки: диск у VPS небольшой
[ "${ok:-0}" = 1 ] || { echo "Gateway не ответил. Логи: docker compose logs gateway --tail 100"; exit 1; }
say "Готово: $(curl -fsS "http://127.0.0.1:${PORT}/health/full" 2>/dev/null || echo '?')"
