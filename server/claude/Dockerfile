# Claude Code CLI для чата Mnemo. npm ставит нативный бинарник (postinstall), в рантайм уходит только он:
# Node внутри не нужен, база та же, что у остальных сервисов (общие слои на диске).
FROM node:22-bookworm-slim AS cli
ARG CLAUDE_CODE_VERSION=2.1.310
RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
 && cp "$(npm root -g)/@anthropic-ai/claude-code/bin/claude.exe" /claude \
 && /claude --version

FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends git openssh-client ca-certificates curl ripgrep jq \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -m -u 1000 -s /bin/bash claude \
 && mkdir -p /work && chown claude:claude /work
COPY --from=cli /claude /usr/local/bin/claude
COPY claude/service.py /app/service.py
USER claude
WORKDIR /work
ENV PYTHONUNBUFFERED=1 DISABLE_AUTOUPDATER=1 USE_BUILTIN_RIPGREP=0
EXPOSE 8700
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8700/health || exit 1
CMD ["python", "/app/service.py"]
