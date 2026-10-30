"""E2E: настоящий WebSocket к запущенному docker compose (STT_PROVIDER=fake, CONVERSATION_IDLE_SEC=5).

    E2E_URL=http://127.0.0.1:8090 E2E_TOKEN=... python tests/e2e_ws.py
"""
import asyncio
import json
import os
import sys
import time
from pathlib import Path

import requests
from websockets.asyncio.client import connect

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "common"), str(ROOT)]

from neuro.audio import CODEC_OPUS_FS320, build_batch  # noqa: E402
from tests.test_audio import opus_frames, tone  # noqa: E402

URL = os.environ.get("E2E_URL", "http://127.0.0.1:8090")
TOKEN = os.environ["E2E_TOKEN"]
H = {"Authorization": f"Bearer {TOKEN}"}


async def send_audio():
    frames = opus_frames(tone(1.0))
    t0 = int(time.time() * 1000) - 5 * 60 * 1000
    ws_url = URL.replace("http", "ws", 1) + "/ws/audio?device_id=e2e-device"
    async with connect(ws_url, additional_headers=H) as ws:
        await ws.send(json.dumps({"type": "hello", "info": {"app": "e2e"}}))
        print("hello:", await ws.recv())
        for i in range(3):
            await ws.send(build_batch(CODEC_OPUS_FS320, t0 + i * 1000, frames))
            ack = json.loads(await ws.recv())
            assert ack == {"type": "ack", "id": f"{t0 + i * 1000}_a", "frames": 50}, ack
        await ws.send(json.dumps({"type": "event", "id": f"{t0 + 2000}_e", "event": "button", "value": 2,
                                  "ts": t0 + 2000}))
        assert json.loads(await ws.recv())["type"] == "ack"
    print("audio sent")

    # без токена соединение должно отклоняться
    try:
        async with connect(ws_url):
            raise AssertionError("connected without token")
    except Exception as exc:  # noqa: BLE001
        assert "403" in str(exc) or "4401" in str(exc), exc
    print("unauthorized rejected")


def wait_conversation():
    deadline = time.time() + 120
    while time.time() < deadline:
        items = requests.get(URL + "/api/conversations", headers=H, timeout=10).json()["items"]
        if items and items[0]["status"] == "done":
            conv = requests.get(f"{URL}/api/conversations/{items[0]['id']}", headers=H, timeout=10).json()
            print("conversation:", conv["title"], "|", conv["segments"][0]["text"])
            assert "Тестовая расшифровка" in conv["segments"][0]["text"]
            return
        time.sleep(3)
    raise AssertionError("conversation was not processed in time: " + json.dumps(
        requests.get(URL + "/api/status", headers=H, timeout=10).json(), ensure_ascii=False))


if __name__ == "__main__":
    assert requests.get(URL + "/health", timeout=10).json()["ok"]
    asyncio.run(send_audio())
    wait_conversation()
    print("E2E OK")
