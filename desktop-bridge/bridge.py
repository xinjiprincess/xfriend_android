"""Opt-in, paired companion access to an already authenticated desktop.

The desktop's loopback security remains intact. This separate process owns TLS,
pairing, origin validation and account binding; stopping it revokes all sessions.
"""

import argparse
import asyncio
import hmac
import json
import secrets
import ssl
import time
from urllib.parse import urlsplit

import aiohttp
from aiohttp import web
from exporter import legacy_export

HOP_HEADERS = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "content-length",
}
CLIENT = web.AppKey("client", aiohttp.ClientSession)


def validate_source(value):
    url = urlsplit(value)
    if (
        url.scheme != "http"
        or url.hostname not in {"127.0.0.1", "localhost"}
        or url.username
        or url.password
        or url.query
        or url.fragment
        or url.path not in {"", "/"}
    ):
        raise ValueError("电脑版地址必须是本机 http://127.0.0.1:端口")
    return value.rstrip("/")


def create_app(source, code, *, secure=False):
    source = validate_source(source)
    sessions = {}
    failures = {}
    app = web.Application(client_max_size=100 * 1024 * 1024)

    async def lifecycle(app):
        async with aiohttp.ClientSession(
            auto_decompress=False,
            timeout=aiohttp.ClientTimeout(total=None, connect=10, sock_connect=10),
            trust_env=False,
        ) as client:
            app[CLIENT] = client
            yield

    app.cleanup_ctx.append(lifecycle)

    async def identity():
        async with app[CLIENT].get(
            source + "/api/desktop/auth/status",
            timeout=aiohttp.ClientTimeout(total=10),
            allow_redirects=False,
        ) as response:
            if response.status != 200:
                raise web.HTTPServiceUnavailable(text="请先在电脑版登录")
            status = await response.json()
            if not status.get("enabled") or status.get("state") != "authenticated":
                raise web.HTTPUnauthorized(text="请先在电脑版登录云账号")
            return status["user"]["user_id"], status["space_id"]

    def same_origin(request):
        return request.headers.get("Origin") == f"{request.scheme}://{request.host}"

    async def status(request):
        # Discovery is public, but never exposes user identifiers, tokens or the pairing code.
        state, result = "ready", 200
        try:
            await identity()
        except web.HTTPUnauthorized:
            state = "login_required"
        except (web.HTTPException, aiohttp.ClientError, asyncio.TimeoutError):
            state, result = "unavailable", 503
        return web.json_response(
            {
                "application": "xfriend",
                "protocol": 1,
                "state": state,
                "implementation": "external-bridge",
                "entry_path": "/chat",
                "pairing_required": True,
                "session_ttl_seconds": 8 * 3600,
                "transports": ["http", "websocket", "sse", "upload"],
            },
            status=result,
            headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"},
        )

    async def pair(request):
        if request.content_length is None or request.content_length > 2048:
            raise web.HTTPRequestEntityTooLarge(
                max_size=2048, actual_size=request.content_length or 0
            )
        # A native WebView postUrl starts at about:blank and sends Origin: null.
        # Pairing never authenticates with a pre-existing cookie: the high-entropy
        # code below is mandatory even for opaque origins. Authenticated writes
        # still require an exact same-origin header in proxy().
        if request.headers.get("Origin") not in {None, "null"} and not same_origin(
            request
        ):
            raise web.HTTPForbidden()
        now = time.monotonic()
        key = request.remote
        # Expire old buckets and bound the table, including unauthenticated callers.
        for ip in list(failures):
            if now - failures[ip][0] > 60:
                failures.pop(ip)
        if len(failures) > 1024 or failures.get(key, (now, 0))[1] >= 5:
            raise web.HTTPTooManyRequests(text="请一分钟后重试配对")
        data = await request.post()
        if not hmac.compare_digest(str(data.get("code", "")).encode(), code.encode()):
            start, count = failures.get(key, (now, 0))
            failures[key] = start, count + 1
            raise web.HTTPUnauthorized(text="配对码无效")
        who = await identity()
        for token in list(sessions):
            if sessions[token][0] <= now:
                sessions.pop(token)
        if len(sessions) >= 16:
            raise web.HTTPTooManyRequests(text="连接数已满，请重启连接桥")
        token = secrets.token_urlsafe(32)
        sessions[token] = now + 8 * 3600, who
        response = web.HTTPSeeOther("/chat")
        response.set_cookie(
            "xfriend_mobile",
            token,
            secure=secure,
            httponly=True,
            samesite="Strict",
            path="/",
        )
        response.headers["Cache-Control"] = "no-store"
        raise response

    async def proxy(request):
        native_v2 = request.path.startswith("/mobile/v2/")
        token = request.cookies.get("xfriend_mobile", "")
        session = sessions.get(token)
        if not session or session[0] <= time.monotonic():
            raise web.HTTPUnauthorized(text="连接已过期，请在手机断开后重新配对")
        if (native_v2 or request.method not in {"GET", "HEAD"}) and not same_origin(
            request
        ):
            raise web.HTTPForbidden(text="无效请求来源")
        if request.headers.get("Sec-Fetch-Site") == "cross-site":
            raise web.HTTPForbidden()
        try:
            who = await identity()
        except web.HTTPUnauthorized:
            sessions.pop(token, None)
            raise
        if who != session[1]:
            sessions.pop(token, None)
            raise web.HTTPUnauthorized(text="电脑账号或空间已切换，请重新配对")
        if request.path == "/mobile/logout":
            if request.method != "POST":
                raise web.HTTPMethodNotAllowed(request.method, ["POST"])
            sessions.pop(token, None)
            response = web.Response(text="已断开")
            response.del_cookie("xfriend_mobile")
            return response
        if request.path == "/mobile/export":
            if request.method != "GET":
                raise web.HTTPMethodNotAllowed(request.method, ["GET"])
            if not same_origin(request):
                raise web.HTTPForbidden(text="资源导出需要同源请求")
            include = request.query.get("include_secrets", "0") == "1"

            async def fetch(path, optional=False):
                async with app[CLIENT].get(
                    source + path,
                    headers={"Origin": source, "Referer": source + "/"},
                    allow_redirects=False,
                    timeout=aiohttp.ClientTimeout(total=20),
                ) as upstream:
                    if optional and upstream.status == 404:
                        return None
                    if upstream.status != 200:
                        raise web.HTTPBadGateway(text="电脑版资源导出失败，请检查权限")
                    body = bytearray()
                    async for chunk in upstream.content.iter_chunked(65536):
                        body.extend(chunk)
                        if len(body) > 4 * 1024 * 1024:
                            raise web.HTTPBadGateway(
                                text="导出资源超过 4 MB，请在电脑选择较少资源"
                            )
                    return json.loads(body)

            # New desktop versions provide a canonical full export. Old versions use a limited adapter.
            result = await fetch(
                "/api/mobile/export?include_secrets=" + ("1" if include else "0"),
                optional=True,
            )
            if result is None:
                result = await legacy_export(fetch, include)
            if (
                not isinstance(result, dict)
                or result.get("format") != "xfriend-mobile-export"
                or result.get("version") != 1
            ):
                raise web.HTTPBadGateway(text="电脑版导出格式不兼容")
            fields = {
                "experts": {"id", "name", "prompt", "skills", "knowledge"},
                "teams": {"id", "name", "members"},
                "skills": {"id", "name", "content"},
                "knowledge": {"id", "name", "content"},
                "providers": {"id", "name", "base_url", "models"}
                | ({"api_key"} if include else set()),
            }
            # Only this public export schema may cross the bridge. Never forward session/auth metadata.
            result = {
                "format": "xfriend-mobile-export",
                "version": 1,
                "source": {"user_id": who[0], "space_id": who[1]},
                "warnings": result.get("warnings", []),
                **{
                    name: [
                        {k: v for k, v in row.items() if k in allowed}
                        for row in result.get(name, [])
                    ]
                    for name, allowed in fields.items()
                },
            }
            if await identity() != session[1]:
                sessions.pop(token, None)
                raise web.HTTPUnauthorized(text="账号已切换，请重新配对")
            encoded = json.dumps(result, ensure_ascii=False).encode()
            if len(encoded) > 4 * 1024 * 1024:
                raise web.HTTPBadGateway(text="导出包超过 4 MB，请在电脑版选择较少资源")
            return web.Response(
                body=encoded,
                content_type="application/json",
                headers={
                    "Cache-Control": "no-store",
                    "X-Content-Type-Options": "nosniff",
                },
            )
        if request.path.startswith("/api/desktop/") and request.path not in {
            "/api/desktop/auth/status",
            "/api/desktop/startup",
        }:
            raise web.HTTPForbidden(text="账号切换及退出请在电脑操作")
        # Host is generated from source by aiohttp. Browser credentials never go to PC.
        headers = {
            k: v
            for k, v in request.headers.items()
            if k.lower()
            not in HOP_HEADERS
            | {
                "host",
                "cookie",
                "authorization",
                "origin",
                "referer",
                "accept-encoding",
                "sec-fetch-site",
                "sec-websocket-key",
                "sec-websocket-version",
                "sec-websocket-extensions",
                "sec-websocket-protocol",
                "forwarded",
                "x-forwarded-for",
                "x-forwarded-host",
                "x-forwarded-proto",
                "x-real-ip",
                "x-xfriend-cloud-bridge",
            }
        }
        headers.update(
            {
                "Origin": source,
                "Referer": source + "/",
                "Accept-Encoding": "identity",
                "Sec-Fetch-Site": "same-origin",
            }
        )
        # Native APIs retain pair-cookie identity. The Go loopback proxy supplies
        # the local Octop JWT; a phone cloud bearer must never be forwarded.
        target_path = request.raw_path
        if native_v2:
            target_path = "/api" + request.path
            query = request.query.copy()
            query.popall("token", None)
            query.popall("access_token", None)
            if query:
                from urllib.parse import urlencode

                target_path += "?" + urlencode(list(query.items()))
        target = source + target_path
        if request.headers.get("Upgrade", "").lower() == "websocket":
            if not same_origin(request):
                raise web.HTTPForbidden()
            async with app[CLIENT].ws_connect(
                target, headers=headers, max_msg_size=16 * 1024 * 1024
            ) as upstream:
                downstream = web.WebSocketResponse(max_msg_size=16 * 1024 * 1024)
                await downstream.prepare(request)

                async def relay(src, dst):
                    async for msg in src:
                        if msg.type == aiohttp.WSMsgType.TEXT:
                            await dst.send_str(msg.data)
                        elif msg.type == aiohttp.WSMsgType.BINARY:
                            await dst.send_bytes(msg.data)
                        elif msg.type == aiohttp.WSMsgType.ERROR:
                            break

                async def guard():
                    while True:
                        await asyncio.sleep(2)
                        if token not in sessions or session[0] <= time.monotonic():
                            return
                        try:
                            if await identity() != session[1]:
                                return
                        except (
                            web.HTTPException,
                            aiohttp.ClientError,
                            asyncio.TimeoutError,
                        ):
                            return

                tasks = [
                    asyncio.create_task(relay(upstream, downstream)),
                    asyncio.create_task(relay(downstream, upstream)),
                    asyncio.create_task(guard()),
                ]
                try:
                    await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                finally:
                    for task in tasks:
                        task.cancel()
                    await asyncio.gather(*tasks, return_exceptions=True)
                    await downstream.close()
                return downstream
        async with app[CLIENT].request(
            request.method,
            target,
            headers=headers,
            data=request.content.iter_chunked(65536) if request.can_read_body else None,
            allow_redirects=False,
        ) as upstream:
            out = {
                k: v
                for k, v in upstream.headers.items()
                if k.lower() not in HOP_HEADERS | {"set-cookie", "cache-control"}
            }
            if "Location" in out and out["Location"].startswith(source + "/"):
                out["Location"] = out["Location"][len(source) :]
            out["Cache-Control"] = "no-store"
            response = web.StreamResponse(status=upstream.status, headers=out)
            await response.prepare(request)
            checked_at = time.monotonic()
            async for chunk in upstream.content.iter_chunked(65536):
                now = time.monotonic()
                if token not in sessions or session[0] <= now:
                    break
                if now - checked_at >= 2:
                    try:
                        if await identity() != session[1]:
                            break
                    except (
                        web.HTTPException,
                        aiohttp.ClientError,
                        asyncio.TimeoutError,
                    ):
                        break
                    checked_at = now
                await response.write(chunk)
            await response.write_eof()
            return response

    app.router.add_get("/mobile/status", status)
    app.router.add_post("/mobile/pair", pair)
    app.router.add_route("*", "/{path:.*}", proxy)
    return app


def main():
    parser = argparse.ArgumentParser(description="Xfriend Android 电脑连接桥")
    parser.add_argument(
        "--desktop",
        required=True,
        help="已运行的 Go 桌面账号桥地址（不是 Python 端口）",
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--cert")
    parser.add_argument("--key")
    args = parser.parse_args()
    if bool(args.cert) != bool(args.key):
        parser.error("--cert 与 --key 必须一起提供")
    tls = None
    if args.cert and args.key:
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(args.cert, args.key)
    if args.host not in {"127.0.0.1", "localhost", "::1"} and tls is None:
        parser.error("非回环监听必须提供受信任的 --cert 与 --key")
    code = secrets.token_urlsafe(24)
    print(
        "手机配对码（持有者可操作当前电脑账号，请仅输入自己的手机）：", code, flush=True
    )
    print("停止此进程即撤销所有连接；电脑切换账号后须重新配对。", flush=True)
    web.run_app(
        create_app(args.desktop, code, secure=tls is not None),
        host=args.host,
        port=args.port,
        ssl_context=tls,
        access_log=None,
    )


if __name__ == "__main__":
    main()
