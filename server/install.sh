#!/usr/bin/env bash
# Mnemo: установка или обновление сервера одной командой (репозиторий публичный, логин не нужен):
#   curl -fsSL https://raw.githubusercontent.com/jeke8989/omi-neurocode/main/server/install.sh | sudo bash
# Если сервер уже стоит, обновляет его в той же папке (данные и .env сохраняются).
set -euo pipefail
REPO="${MNEMO_REPO:-https://github.com/jeke8989/omi-neurocode.git}"
BRANCH="${MNEMO_BRANCH:-main}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

if ! command -v git >/dev/null 2>&1; then
  say "Устанавливаю git"
  (apt-get update -qq && apt-get install -y -qq git) || (dnf install -y git) || (yum install -y git)
fi

# Где уже стоит Mnemo: по запущенному проекту docker compose «neurosoft» или в привычных папках.
# Важно не завести вторую копию: у неё был бы другой пароль базы, и старые данные не открылись бы.
DIR="${MNEMO_DIR:-}"
if [ -z "$DIR" ] && command -v docker >/dev/null 2>&1; then
  cfg=$(docker compose ls -a --format json 2>/dev/null | tr '}' '\n' | grep -E '"Name": ?"neurosoft"' \
        | sed -nE 's/.*"ConfigFiles": ?"([^",]*).*/\1/p' | head -1 || true)
  [ -n "$cfg" ] && DIR=$(dirname "$(dirname "$cfg")")
fi
if [ -z "$DIR" ]; then
  for d in /opt/mnemo "${SUDO_USER:+/home/$SUDO_USER/omi-neurocode}" "$HOME/omi-neurocode" /root/omi-neurocode; do
    [ -n "$d" ] && [ -f "$d/server/docker-compose.yml" ] && { DIR="$d"; break; }
  done
fi
DIR="${DIR:-/opt/mnemo}"

if [ -d "$DIR/.git" ]; then
  say "Обновляю код в $DIR"
  git -C "$DIR" remote set-url origin "$REPO"   # публичный адрес, без старого токена в настройках git
  if [ -n "$(git -C "$DIR" status --porcelain --untracked-files=no)" ]; then
    git -C "$DIR" stash push -m "mnemo-install $(date +%F-%H%M)" >/dev/null
    echo "Локальные правки в коде сохранены: git -C $DIR stash list"
  fi
  git -C "$DIR" fetch --depth 1 origin "$BRANCH"
  git -C "$DIR" checkout -q -B "$BRANCH" FETCH_HEAD
else
  say "Скачиваю Mnemo в $DIR"
  git clone --depth 1 -b "$BRANCH" "$REPO" "$DIR"
fi

exec bash "$DIR/server/deploy.sh"
