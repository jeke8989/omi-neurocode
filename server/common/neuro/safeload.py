"""Безопасная загрузка тяжёлых моделей (нейроголос, отпечатки голоса).

Модель сначала запускается на пробу в отдельном процессе. Если она не подходит серверу — процессор без
нужных инструкций, не хватает памяти или места на диске, — падает проба, а не весь сервис: ответы звучат
голосом телефона, голоса временно не различаются, а запись, расшифровка и приложение работают как обычно.
"""
from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
from pathlib import Path

log = logging.getLogger("neuro.safeload")

SIGNALS = {-4: "SIGILL: процессор не поддерживает модель", -6: "SIGABRT", -9: "SIGKILL: не хватило памяти",
           -11: "SIGSEGV"}


def free_mb(path) -> int:
    """Свободное место на диске, где лежит (или появится) путь."""
    p = Path(path)
    while not p.exists() and p != p.parent:
        p = p.parent
    try:
        return shutil.disk_usage(p).free // (1 << 20)
    except OSError:
        return 1 << 30


def mem_available_mb() -> int:
    """Доступная память сервера (MemAvailable). Не прочитать — считаем, что хватает."""
    try:
        with open("/proc/meminfo", encoding="ascii") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) // 1024
    except (OSError, ValueError):
        pass
    return 1 << 30


def shortage(path, disk_mb: int, mem_mb: int) -> str | None:
    """Почему модель сейчас не загрузить (мало места или памяти), или None — можно."""
    disk = free_mb(path)
    if disk < disk_mb:
        return f"not enough disk: {disk} MB free, need {disk_mb} MB"
    mem = mem_available_mb()
    if mem < mem_mb:
        return f"not enough memory: {mem} MB available, need {mem_mb} MB"
    return None


def probe(code: str, timeout: int = 300) -> tuple[bool, str]:
    """Выполнить код в отдельном процессе Python. (успех, причина неудачи)."""
    env = dict(os.environ, PYTHONPATH=os.pathsep.join(p for p in sys.path if p), NEURO_PROBE="1")
    try:
        r = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False, f"timeout after {timeout} s"
    except OSError as exc:
        return False, str(exc)
    if r.returncode == 0:
        return True, ""
    tail = (r.stderr or "").strip().splitlines()[-1:] or [""]
    return False, f"exit {r.returncode} {SIGNALS.get(r.returncode, '')} {tail[0][:300]}".strip()
