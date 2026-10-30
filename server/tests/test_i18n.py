"""Языки: настройка в приложении, перевод фраз, каталога и манифестов, язык расшифровки."""
import json

import pytest

from tests.test_pipeline import conn  # noqa: F401


def test_phrases_and_prompts():
    from neuro import i18n
    from neuro.agent_core import route, system_prompt, tool_title

    assert i18n.normalize("en-US") == "en" and i18n.normalize("de") == "ru" and i18n.normalize(None) == "ru"
    for key, variants in i18n.PHRASES.items():
        assert set(variants) == set(i18n.LANGS), key  # у каждой фразы есть все языки
    assert i18n.t("tr", "tool.web_search", query="hava") == "🔎 İnternette arıyorum: hava"
    assert i18n.t("xx", "voice.deep").startswith("Займусь")
    assert "Spanish" in i18n.write_in("es") and "по-русски" in i18n.write_in("ru")
    assert "Answer in English" in system_prompt("fast", "voice", "en")
    assert tool_title("es", "create_task", {"text": "pan"}) == "📝 Creando una tarea: pan"
    assert route("find the latest news about Omi", "chat") == "tools"
    assert route("analyze my week and compare options", "chat") == "deep"
    assert route("hava durumu nasıl", "chat") == "tools"
    assert route("investiga a fondo la competencia", "voice") == "deep"


def test_language_setting_and_stt(conn):  # noqa: F811
    from neuro import i18n
    conn.execute("DELETE FROM settings WHERE key IN ('app.language', 'stt.language')")
    assert i18n.lang(conn) == "ru"
    i18n.set_lang(conn, "tr")
    assert i18n.lang(conn) == "tr"
    assert i18n.stt_language(conn) == "tr"  # по умолчанию расшифровка следует за языком приложения
    conn.execute("INSERT INTO settings (key, value) VALUES ('stt.language', 'auto')")
    assert i18n.stt_language(conn) is None
    conn.execute("UPDATE settings SET value = 'en' WHERE key = 'stt.language'")
    assert i18n.stt_language(conn) == "en"
    conn.execute("DELETE FROM settings WHERE key IN ('app.language', 'stt.language')")


def test_catalog_and_manifest_translations():
    from neuro import extensions as ext
    from neuro.models import CATALOG, CATALOG_I18N, TASKS, TASKS_I18N, localized_model, localized_task

    for t in TASKS:
        assert set(TASKS_I18N[t["key"]]) == {"en", "tr", "es"}, t["key"]
    for m in CATALOG:
        assert set(CATALOG_I18N[m["id"]]) == {"en", "tr", "es"}, m["id"]
    assert localized_task(TASKS[0], "en")["title"] == "Agent: quick (voice)"
    assert localized_task(TASKS[0], "ru")["title"] == TASKS[0]["title"]
    assert localized_model(CATALOG[0], "es")["badge"] == "La más barata"

    reg = ext.registry()
    for entry in reg:
        m, _ = ext.load_manifest(entry)
        assert set(m.get("i18n") or {}) == {"en", "tr", "es"}, m["id"]
        pub = ext.public_manifest(m, "en")
        assert all(t["title"] for t in pub["tools"])
    w, _ = ext.load_manifest(next(e for e in reg if e["id"] == "weather"))
    assert ext.public_manifest(w, "tr")["name"] == "Hava durumu"
    assert ext.public_manifest(w, "ru")["name"] == "Погода"
    gh, _ = ext.load_manifest(next(e for e in reg if e["id"] == "github"))
    pub = ext.public_manifest(gh, "es")
    assert pub["task_sync"].endswith("GitHub Issues") and pub["settings"][0]["title"] == "Token de GitHub"


def test_locale_in_extension_urls(monkeypatch):
    from neuro import extensions as ext
    reg = ext.registry()
    wiki, _ = ext.load_manifest(next(e for e in reg if e["id"] == "wikipedia"))
    sent = {}

    class R:
        status_code = 200
        text = "{}"

        def json(self):
            return {}

    def fake_request(method, url, headers=None, json=None, timeout=None):
        sent["url"] = url
        return R()

    monkeypatch.setattr(ext.requests, "request", fake_request)
    ext.run_tool({"manifest": wiki, "settings": {}}, wiki["tools"][0], {"query": "Ankara"}, {"locale": "tr"})
    assert sent["url"].startswith("https://tr.wikipedia.org/w/api.php")
    ext.run_tool({"manifest": wiki, "settings": {}}, wiki["tools"][0], {"query": "x"}, {"locale": "evil.com/"})
    assert sent["url"].startswith("https://ru.wikipedia.org/")  # неизвестный язык -> по умолчанию

    # язык в домене разрешён, только если все варианты есть в hosts
    bad = json.loads(json.dumps(wiki))
    bad["permissions"]["hosts"] = ["ru.wikipedia.org"]
    with pytest.raises(ext.ExtensionError):
        ext.validate(bad)
    bad = json.loads(json.dumps(wiki))
    bad["tools"][0]["http"]["url"] = "https://{query}.wikipedia.org/"
    with pytest.raises(ext.ExtensionError):
        ext.validate(bad)
