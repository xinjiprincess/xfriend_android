"""Isolated Android UI smoke fixture. No real desktop, account or files are accessed.

Run manually with `uv run smoke_fixture.py`. The source server has an ephemeral
loopback port and only synthetic endpoints. The bridge listens on 127.0.0.1:8766.
"""

import asyncio
import os

import aiohttp
from aiohttp import web
from aiohttp.test_utils import TestServer

from bridge import create_app

PAGE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Xfriend isolated smoke test</title>
<style>body{font:18px sans-serif;padding:24px;background:#f5f6f8;color:#111827}p{padding:16px;background:white;border-radius:8px}</style>
<h2>Xfriend · 隔离测试服务</h2><p id="http">HTTP 配对会话：通过</p><p id="ws">WebSocket：连接中</p>
<p>这是测试数据，不是你的电脑工作台。</p>
<script>
const ws=new WebSocket('ws://'+location.host+'/socket');
ws.onopen=()=>ws.send('android-smoke');
ws.onmessage=e=>{document.getElementById('ws').textContent=e.data==='android-smoke'?'WebSocket 往返：通过':'WebSocket：失败'};
</script>"""


async def run():
    async def endpoint(request):
        if request.path == "/api/desktop/auth/status":
            return web.json_response(
                {
                    "enabled": True,
                    "state": "authenticated",
                    "user": {"user_id": "synthetic-user"},
                    "space_id": "synthetic-space",
                }
            )
        if request.path == "/socket":
            ws = web.WebSocketResponse()
            await ws.prepare(request)
            async for message in ws:
                if message.type == aiohttp.WSMsgType.TEXT:
                    await ws.send_str(message.data)
            return ws
        return web.Response(text=PAGE, content_type="text/html")

    source_app = web.Application()
    source_app.router.add_route("*", "/{path:.*}", endpoint)
    async with TestServer(source_app) as source:
        app = create_app(str(source.make_url("/")), "android-smoke-pairing-only")
        async with TestServer(app, port=8766):
            print(f"Synthetic fixture ready, PID={os.getpid()}, port=8766", flush=True)
            await asyncio.Event().wait()


if __name__ == "__main__":
    asyncio.run(run())
