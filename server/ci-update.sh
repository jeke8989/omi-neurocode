#!/usr/bin/env bash
# Обновление сервера из GitHub Actions после пуша в main (.github/workflows/server.yml, задача deploy).
# Ключ CI лежит в /root/.ssh/authorized_keys с command="/opt/mnemo/server/ci-update.sh": другую команду
# этим ключом не выполнить. В отличие от deploy.sh ничего не спрашивает и не печатает код подключения —
# логи CI публичные. Данные и .env не трогает.
#
# Пересобирает только сервисы, чьи файлы изменились, по одному: на маленьком диске (рядом Jarvis и другие
# проекты) полная пересборка всех образов не помещается. Места не хватает — останавливается с ошибкой,
# не заполняя диск соседям.
set -Eeuo pipefail
# причина ошибки — строкой ::error:: (GitHub показывает её в аннотациях запуска; ничего секретного не пишем)
trap 'echo "::error title=Обновление сервера::Команда упала (строка $LINENO): $BASH_COMMAND"' ERR

main() {
  local DIR BRANCH OLD NEW BASE changed services f s
  DIR="$(cd "$(dirname "$0")/.." && pwd)"
  BRANCH="${MNEMO_BRANCH:-main}"
  rolled=""

  # ssh ... status — только состояние сервера (для CI после обновления), ничего не меняет
  if [ "${SSH_ORIGINAL_COMMAND:-}" = "status" ]; then
    cd "$DIR/server"
    PORT=$(grep -E '^GATEWAY_PORT=' .env 2>/dev/null | tail -1 | cut -d= -f2); PORT="${PORT:-8090}"
    diag
    return 0
  fi

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

  # что пересобирать — по файлам, изменённым с последнего удачного обновления (.deployed): если прошлое
  # обновление упало на сборке, его сервисы соберутся сейчас. Отметки нет — собираем всё один раз.
  BASE=$(cat .deployed 2>/dev/null || true)
  if [ -n "$BASE" ] && git cat-file -e "${BASE}^{commit}" 2>/dev/null; then
    changed=""
    [ "$BASE" != "$NEW" ] && changed=$(git diff --name-only "$BASE" "$NEW" 2>/dev/null || echo "server/common/")
  else
    changed="server/common/"
  fi
  echo "::notice title=Диск::свободно $(free_mb) МБ до сборки"
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

  tidy
  for s in $services; do
    check_space "$s"
    [ "$s" = gateway ] && keep_prev "$s"  # прошлую версию держим только для gateway: без него нет связи, а места мало
    docker compose build -q "$s"
    if [ "$s" = gateway ] || [ "$s" = claude ]; then wait_claude; fi
    docker compose up -d --no-deps "$s"
    tidy
  done

  say "Применяю docker-compose.yml"
  wait_claude
  docker compose up -d --remove-orphans
  tidy

  docker compose ps --format 'table {{.Service}}\t{{.Status}}'
  if ! healthy 150; then
    # в аннотации — только состояния, ресурсы и названия ошибок, не сами логи (логи CI публичные)
    echo "::error title=Gateway не ответил::после обновления сервер не отвечает — откатываю"
    diag
    if [ -n "$rolled" ]; then
      rollback
      if healthy 120; then
        echo "::error title=Откат::новая версия не запустилась, сервер работает на прошлой версии"
      else
        echo "::error title=Откат не помог::сервер не отвечает и на прошлой версии"
        diag
      fi
    fi
    echo "Gateway не ответил. Логи: docker compose logs gateway --tail 100"
    exit 1
  fi
  # шифрование данных в базе (от утечек): ключ создаётся один раз и никогда не печатается
  if ! grep -qE '^DATA_KEY=.+' .env; then
    (umask 077; printf '\nDATA_KEY=%s\n' "$(openssl rand -base64 32 2>/dev/null || head -c 32 /dev/urandom | base64)" >> .env)
    echo "::notice title=Шифрование::включено — ключ DATA_KEY создан в server/.env; храните копию .env отдельно от бэкапов базы"
    docker compose up -d   # сервисы перечитывают .env: новые данные — зашифрованы, старые зашифрует агент
    healthy 150 || { echo "::error title=Шифрование::после включения ключа сервер не ответил"; diag; exit 1; }
  fi
  drop_prev
  echo "$NEW" > .deployed
  say "Готово: $(curl -fsS "http://127.0.0.1:${PORT}/health/full" 2>/dev/null || echo '?'), свободно $(free_mb) МБ"
}

# обновление прервалось на середине (например, не собрался следующий сервис), а уже обновлённые не
# поднялись — вернуть их прошлую версию, чтобы сервер не остался лежать
on_exit() {
  local rc=$?
  if [ "$rc" != 0 ] && [ -n "${rolled:-}" ] && [ "${rolled_back:-0}" = 0 ] && ! healthy 60; then
    echo "::error title=Откат::обновление прервалось, сервер не отвечает — возвращаю прошлую версию"
    rollback
    healthy 120 && echo "::notice title=Откат::сервер работает на прошлой версии"
  fi
  return 0
}
trap on_exit EXIT

# сервер отвечает (ждём до N секунд)
healthy() {
  local i
  for i in $(seq 1 $(( $1 / 3 ))); do
    if curl -fsS -m 3 "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then return 0; fi
    sleep 3
  done
  return 1
}

# перед сборкой запомнить работающий образ (имя:prev), чтобы откатиться, если новая версия не поднимется
keep_prev() {
  local cid img id
  cid=$(docker compose ps -q "$1" 2>/dev/null | head -1 || true)
  [ -n "$cid" ] || return 0
  img=$(docker inspect -f '{{.Config.Image}}' "$cid" 2>/dev/null || true)
  id=$(docker inspect -f '{{.Image}}' "$cid" 2>/dev/null || true)
  img="${img%%:*}"
  if [ -n "$img" ] && [ -n "$id" ] && docker tag "$id" "$img:prev" 2>/dev/null; then
    rolled="$rolled $1=$img"
  fi
}

rollback() {
  local pair s img
  rolled_back=1
  for pair in $rolled; do
    s="${pair%%=*}"; img="${pair#*=}"
    say "Откат $s на прошлую версию"
    docker tag "$img:prev" "$img:latest" 2>/dev/null || continue
    docker compose up -d --no-deps --no-build "$s" || true
  done
}

drop_prev() {
  local pair
  for pair in $rolled; do docker rmi "${pair#*=}:prev" >/dev/null 2>&1 || true; done
  tidy
}

# состояние сервера строками ::notice:: — без логов и секретов
diag() {
  trap - ERR
  local ps cs ex mem
  ps=$(docker compose ps -a --format '{{.Service}}: {{.Status}}' 2>/dev/null | tr '\n' ';' || true)
  cs=$(for c in $(docker compose ps -aq 2>/dev/null || true); do
         docker inspect -f '{{.Name}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} restarts={{.RestartCount}}' "$c" 2>/dev/null || true
       done | tr '\n' ';')
  ex=$(for s in gateway stt agent db; do
         printf '%s: %s; ' "$s" "$(docker compose logs "$s" --tail 300 2>&1 \
           | grep -oE '[A-Za-z]+(Error|Exception)|No space left on device|Illegal instruction|out of memory|Killed|PANIC' \
           | sort | uniq -c | sort -rn | head -5 | awk '{print $2"x"$1}' | tr '\n' ' ' || true)"
       done)
  mem=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo 2>/dev/null || echo '?')
  echo "::notice title=Сервисы::$ps"
  echo "::notice title=Контейнеры::$cs"
  echo "::notice title=Ресурсы::диск свободно $(free_mb) МБ из $(df -Pm / | awk 'NR==2{print $2}') МБ, память доступно ${mem} МБ"
  echo "::notice title=Ошибки в логах::$ex"
  echo "::notice title=Health::$(curl -sS -m 5 "http://127.0.0.1:${PORT:-8090}/health/full" 2>&1 | head -c 400 || true)"
  # расход ИИ — только цифры по задачам и моделям, без содержимого
  local q
  q="SELECT task || ' ' || model || ' вызовов=' || count(*) || ' вход=' || coalesce(sum(prompt_tokens),0)
     || ' выход=' || coalesce(sum(completion_tokens),0) || ' $=' || round(coalesce(sum(cost),0)::numeric, 3)
     FROM llm_usage WHERE created_at > now() - interval '7 days' GROUP BY task, model ORDER BY sum(cost) DESC NULLS LAST LIMIT 15"
  echo "::notice title=ИИ за 7 дней::$(docker compose exec -T db psql -U neuro -d neuro -tAc "$q" 2>/dev/null | tr '\n' ';' || true)"
  q="SELECT to_char(created_at AT TIME ZONE 'Europe/Moscow', 'MM-DD') || ' $' || round(sum(cost)::numeric, 3)
     || ' (' || count(*) || ')' FROM llm_usage WHERE created_at > now() - interval '7 days' GROUP BY 1 ORDER BY 1"
  echo "::notice title=ИИ по дням::$(docker compose exec -T db psql -U neuro -d neuro -tAc "$q" 2>/dev/null | tr '\n' ';' || true)"
  local vol
  vol="SELECT 'разговоров=' || count(*) || ' фраз=' || (SELECT count(*) FROM segments WHERE started_at > now() - interval '7 days')
     || ' вопросов=' || (SELECT count(*) FROM questions WHERE asked_at > now() - interval '7 days')
     || ' задач=' || (SELECT count(*) FROM tasks WHERE created_at > now() - interval '7 days')
     FROM conversations WHERE started_at > now() - interval '7 days'"
  # вопросы с кнопки: как заканчивались, сколько ждали ответа (только счётчики, без текста)
  local asks lat
  asks=$(docker compose logs gateway --since 168h 2>&1 \
    | grep -oE 'ask from [^ ]+ [0-9.]+s audio, end=[a-z]+|ask answered in [0-9]+ms \([a-z_]+\)( pending)?( cancelled)?|ask failed|ask transcription failed|phone left before the answer|ask cancelled while listening' \
    | sed -E 's/ask from [^ ]+ [0-9.]+s audio, end=/конец=/; s/ask answered in [0-9]+ms \(([a-z_]+)\)/ответ-\1/; s/ /_/g' \
    | sort | uniq -c | sort -rn | awk '{print $2"x"$1}' | tr '\n' ' ' || true)
  lat=$(docker compose logs gateway --since 168h 2>&1 | grep -oE 'ask answered in [0-9]+ms' | grep -oE '[0-9]+' \
    | awk '{n++; s+=$1; if ($1>m) m=$1} END {if (n) printf "ждали в среднем %.1f с, максимум %.1f с", s/n/1000, m/1000}' || true)
  q="SELECT status || '=' || count(*) FROM agent_runs WHERE channel = 'voice' AND created_at > now() - interval '7 days'
     GROUP BY status ORDER BY 1"
  # очередь расшифровки: живой звук и запись из памяти кулона (только длительности)
  local qq
  qq="SELECT 'живое ' || round(coalesce(sum(duration_ms) FILTER (WHERE NOT offline), 0) / 60000.0, 1) || ' мин'
     || ', из памяти кулона ' || round(coalesce(sum(duration_ms) FILTER (WHERE offline), 0) / 60000.0, 1) || ' мин'
     || ', самое старое живое ждёт ' || coalesce(round(extract(epoch FROM now() - min(created_at) FILTER (WHERE NOT offline)) / 60)::text, '0') || ' мин'
     || '; за час расшифровано ' || (SELECT count(*) FROM audio_chunks WHERE status IN ('done', 'empty') AND created_at > now() - interval '1 hour')
     || ', из памяти кулона всего ' || (SELECT count(*) FROM audio_chunks WHERE offline)
     FROM audio_chunks WHERE status IN ('pending', 'processing')"
  echo "::notice title=Очередь расшифровки::$(docker compose exec -T db psql -U neuro -d neuro -tAc "$qq" 2>/dev/null | tr '\n' ' ' || true)"
  # голоса и память: сколько спрятано в фон и в архив (только счётчики)
  local qp
  qp="SELECT 'людей ' || count(*) FILTER (WHERE kind IS NULL OR kind = 'person') || ', фон: реклама/ТВ '
     || count(*) FILTER (WHERE kind = 'media') || ', люди рядом ' || count(*) FILTER (WHERE kind = 'crowd')
     || '; память: ' || (SELECT count(*) FROM memories WHERE archived_at IS NULL) || ', в архиве '
     || (SELECT count(*) FROM memories WHERE archived_at IS NOT NULL) || ', не проверено '
     || (SELECT count(*) FROM memories WHERE NOT reviewed AND source <> 'button' AND archived_at IS NULL)
     FROM people"
  echo "::notice title=Голоса и память::$(docker compose exec -T db psql -U neuro -d neuro -tAc "$qp" 2>/dev/null | tr '\n' ' ' || true)"
  echo "::notice title=Кнопка за 7 дней::$asks; $lat; запуски: $(docker compose exec -T db psql -U neuro -d neuro -tAc "$q" 2>/dev/null | tr '\n' ' ' || true)"
  # шифрование: включено ли и сколько фраз ещё лежит открытым текстом (агент шифрует их в фоне)
  q="SELECT 'открыто: фраз ' || (SELECT count(*) FROM segments WHERE text NOT LIKE 'enc_:%%')
     || ' из ' || (SELECT count(*) FROM segments)
     || ', разговоров ' || (SELECT count(*) FROM conversations WHERE title NOT LIKE 'enc_:%%')
     || ', задач ' || (SELECT count(*) FROM tasks WHERE text NOT LIKE 'enc_:%%')
     || ', памяти ' || (SELECT count(*) FROM memories WHERE text NOT LIKE 'enc_:%%')
     || ', точек ' || (SELECT count(*) FROM locations WHERE lat IS NOT NULL)
     || ', людей ' || (SELECT count(*) FROM people WHERE name NOT LIKE 'enc_:%%')"
  echo "::notice title=Шифрование::$(grep -qE '^DATA_KEY=.+' .env 2>/dev/null && echo включено || echo выключено); $(docker compose exec -T db psql -U neuro -d neuro -tAc "$q" 2>/dev/null | tr '\n' ' ' || true)"
  echo "::notice title=Объём за 7 дней::$(docker compose exec -T db psql -U neuro -d neuro -tAc "$vol" 2>/dev/null | tr '\n' ';' || true)"
  trap 'echo "::error title=Обновление сервера::Команда упала (строка $LINENO): $BASH_COMMAND"' ERR
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
    echo "::error title=Мало места на диске::свободно $(free_mb) МБ, для сборки $1 нужно ~${need} МБ"
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
