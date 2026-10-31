"""Нет обращений к неопределённым именам (например, модулю, который забыли импортировать).

Так однажды упал сервер: gateway вызывал tts.warm_up() без импорта tts. В тестах нейроголос выключен,
и ошибка проявилась только на сервере при старте. Эта проверка ловит такое без запуска кода.
"""
import ast
import builtins
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FILES = sorted([*ROOT.glob("common/neuro/*.py"), *ROOT.glob("gateway/*.py"), *ROOT.glob("stt/*.py"),
                *ROOT.glob("agent/*.py"), *ROOT.glob("claude/*.py")])


def undefined(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    known = set(dir(builtins)) | {"__file__", "__name__"}
    for n in ast.walk(tree):
        if isinstance(n, (ast.Import, ast.ImportFrom)):
            known |= {(a.asname or a.name).split(".")[0] for a in n.names}
        elif isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            known.add(n.name)
        elif isinstance(n, ast.arg):
            known.add(n.arg)
        elif isinstance(n, ast.Name) and isinstance(n.ctx, (ast.Store, ast.Del)):
            known.add(n.id)
        elif isinstance(n, ast.ExceptHandler) and n.name:
            known.add(n.name)
    return [f"{path.relative_to(ROOT)}:{n.lineno} {n.id}" for n in ast.walk(tree)
            if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Load) and n.id not in known]


def test_no_undefined_names():
    assert FILES
    problems = [p for f in FILES for p in undefined(f)]
    assert not problems, "неопределённые имена: " + ", ".join(problems)
