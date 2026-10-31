"""Mnemo Claude — Claude Code CLI в отдельном контейнере для чата приложения.

Gateway шлёт сюда вопрос и токен владельца (claude setup-token), сервис запускает
`claude -p ... --output-format stream-json` и отдаёт события построчно (NDJSON). Два режима:
assistant — ассистент Mnemo: пустая папка, встроенные инструменты из "tools" (WebSearch, WebFetch),
данные — только через MCP gateway ("mcp", одноразовый токен запуска); code — рабочая копия
репозитория Mnemo, все инструменты, git push своим deploy-ключом. Ключей сервера (OpenRouter,
база, API_TOKEN) в контейнере нет.

  GET  /health  — живой ли сервис
  GET  /info    — версия CLI, репозиторий, публичная часть deploy-ключа
  POST /run     — {"prompt", "token", "mode", "session_id"?, "system"?, "model"?, "tools"?, "mcp"?} -> NDJSON
  POST /cancel  — остановить текущий запуск
"""
from __future__ import annotations

import json
import logging
import os
import signal
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

log = logging.getLogger("mnemo.claude")

PORT = int(os.environ.get("CLAUDE_PORT", "8700"))
WORK = Path(os.environ.get("CLAUDE_WORKDIR", "/work"))
REPO_URL = os.environ.get("CLAUDE_REPO_URL", "https://github.com/jeke8989/omi-neurocode.git")
PUSH_URL = os.environ.get("CLAUDE_PUSH_URL", "git@github.com:jeke8989/omi-neurocode.git")
BRANCH = os.environ.get("CLAUDE_BRANCH", "main")
MAX_SEC = int(os.environ.get("CLAUDE_MAX_MIN", "30")) * 60
HOME = Path.home()
ASSISTANT_DIR = HOME / "assistant"  # пустая папка: ассистенту файлы не нужны
KEY = HOME / ".ssh" / "id_ed25519"
REPO = WORK / REPO_URL.rstrip("/").rsplit("/", 1)[-1].removesuffix(".git")

_lock = threading.Lock()
_current: dict = {"proc": None}


def sh(*args, cwd=None, timeout=120) -> subprocess.CompletedProcess:
    return subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=timeout)


def version() -> str:
    try:
        return sh("claude", "--version", timeout=20).stdout.strip()
    except Exception:  # noqa: BLE001
        return ""


def setup():
    """Deploy-ключ, git и рабочая копия. Повторный запуск ничего не ломает."""
    KEY.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not KEY.exists():
        sh("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "mnemo-claude", "-f", str(KEY))
        log.info("deploy key created: %s", KEY.with_suffix(".pub").read_text().strip())
    sh("git", "config", "--global", "user.name", os.environ.get("CLAUDE_GIT_NAME", "Claude (Mnemo)"))
    sh("git", "config", "--global", "user.email", os.environ.get("CLAUDE_GIT_EMAIL", "claude@mnemo.local"))
    sh("git", "config", "--global", "core.sshCommand",
       f"ssh -i {KEY} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new")
    WORK.mkdir(parents=True, exist_ok=True)
    if not (REPO / ".git").exists():
        r = sh("git", "clone", "-q", "-b", BRANCH, REPO_URL, str(REPO), timeout=600)
        log.info("clone %s: %s", REPO_URL, "ok" if r.returncode == 0 else r.stderr.strip()[:300])
    if (REPO / ".git").exists():
        sh("git", "remote", "set-url", "--push", "origin", PUSH_URL, cwd=REPO)


def repo_info() -> dict:
    if not (REPO / ".git").exists():
        return {"repo": None}
    head = sh("git", "log", "-1", "--format=%h %s", cwd=REPO).stdout.strip()
    branch = sh("git", "rev-parse", "--abbrev-ref", "HEAD", cwd=REPO).stdout.strip()
    dirty = bool(sh("git", "status", "--porcelain", cwd=REPO).stdout.strip())
    return {"repo": REPO_URL, "path": str(REPO), "branch": branch, "head": head, "dirty": dirty}


def sync_repo():
    """Перед запуском подтягиваем свежий main, если в копии нет незакоммиченной работы."""
    info = repo_info()
    if info.get("repo") and not info["dirty"] and info["branch"] == BRANCH:
        r = sh("git", "pull", "-q", "--ff-only", "origin", BRANCH, cwd=REPO, timeout=120)
        if r.returncode:
            log.warning("git pull: %s", r.stderr.strip()[:300])


def command(body: dict, resume: bool) -> list[str]:
    cmd = ["claude", "-p", str(body["prompt"]), "--output-format", "stream-json", "--verbose",
           "--permission-mode", "bypassPermissions"]
    if resume and body.get("session_id"):
        cmd += ["--resume", str(body["session_id"])]
    if body.get("model"):
        cmd += ["--model", str(body["model"])]
    if body.get("system"):
        cmd += ["--append-system-prompt", str(body["system"])]
    if body.get("mode") == "assistant":
        cmd += ["--tools", str(body.get("tools") or ""), "--strict-mcp-config"]
        if body.get("mcp"):
            cmd += ["--mcp-config", json.dumps(body["mcp"])]
    return cmd


def env_for(token: str) -> dict:
    keep = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "LANG", "LC_ALL", "TZ", "USER", "TERM")}
    return {**keep, "CLAUDE_CODE_OAUTH_TOKEN": token, "DISABLE_AUTOUPDATER": "1", "CI": "1"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"  # ответ без длины: читаем до закрытия соединения

    def log_message(self, fmt, *args):  # noqa: D401 — тише стандартного журнала
        log.debug(fmt, *args)

    def _json(self, code: int, data: dict):
        raw = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"status": "ok", "services": {"claude": bool(version()),
                                                                  "repo": (REPO / ".git").exists()}})
        if self.path == "/info":
            pub = KEY.with_suffix(".pub")
            return self._json(200, {"version": version(), "busy": _lock.locked(), **repo_info(),
                                    "deploy_key": pub.read_text().strip() if pub.exists() else None})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/cancel":
            proc = _current["proc"]
            if proc and proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
            return self._json(200, {"cancelled": bool(proc)})
        if self.path != "/run":
            return self._json(404, {"error": "not found"})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        except ValueError:
            return self._json(400, {"error": "bad json"})
        if not body.get("prompt") or not body.get("token"):
            return self._json(400, {"error": "prompt and token are required"})
        if not _lock.acquire(blocking=False):
            return self._json(423, {"error": "busy"})
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            if body.get("mode") != "assistant":
                sync_repo()
            code, text, lost = self._run(body, resume=True)
            if lost:  # сессию стёрли (новый том, переустановка) — начинаем новую
                self._emit({"type": "service_note", "note": "session_reset"})
                code, text, _ = self._run(body, resume=False)
            self._emit({"type": "service_exit", "code": code, "stderr": text[-2000:]})
        except (BrokenPipeError, ConnectionResetError):
            log.warning("gateway went away, stopping run")
            proc = _current["proc"]
            if proc and proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
        finally:
            _lock.release()

    def _emit(self, event: dict):
        self.wfile.write((json.dumps(event, ensure_ascii=False) + "\n").encode())
        self.wfile.flush()

    def _run(self, body: dict, resume: bool) -> tuple[int, str, bool]:
        """Запускает CLI и пересылает его события. Третье значение — сессия для --resume не найдена."""
        if body.get("mode") == "assistant":
            ASSISTANT_DIR.mkdir(parents=True, exist_ok=True)
            cwd = ASSISTANT_DIR
        else:
            cwd = REPO if (REPO / ".git").exists() else WORK
        proc = subprocess.Popen(command(body, resume), cwd=cwd, env=env_for(str(body["token"])),
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, bufsize=1, start_new_session=True)
        _current["proc"] = proc
        timer = threading.Timer(MAX_SEC, lambda: proc.poll() is None and os.killpg(proc.pid, signal.SIGTERM))
        timer.start()
        err: list[str] = []
        reader = threading.Thread(target=lambda: err.extend(proc.stderr), daemon=True)
        reader.start()
        started = time.monotonic()
        lost = False
        try:
            for line in proc.stdout:
                line = line.strip()
                if resume and body.get("session_id") and "No conversation found with session ID" in line:
                    lost = True  # это событие не пересылаем: запуск повторится без --resume
                    continue
                if line:
                    self.wfile.write((line + "\n").encode())
                    self.wfile.flush()
            proc.wait()
        finally:
            timer.cancel()
            _current["proc"] = None
        reader.join(timeout=2)
        stderr = "".join(err)
        if time.monotonic() - started >= MAX_SEC:
            stderr += f"\nstopped after {MAX_SEC // 60} min"
        log.info("claude exited %s in %.0fs", proc.returncode, time.monotonic() - started)
        return proc.returncode, stderr, lost


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    setup()
    log.info("claude service on :%s, %s, repo %s", PORT, version() or "CLI missing", repo_info().get("head"))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
