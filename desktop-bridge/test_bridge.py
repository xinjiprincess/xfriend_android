import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from bridge import create_app, validate_source


@pytest.fixture
async def connected():
    state = {"user": "owner", "space": "personal", "authenticated": True}

    async def desktop(request):
        if request.path == "/api/desktop/auth/status":
            return web.json_response(
                {
                    "enabled": True,
                    "state": "authenticated"
                    if state["authenticated"]
                    else "logged_out",
                    "user": {"user_id": state["user"]},
                    "space_id": state["space"],
                }
            )
        if request.path == "/socket":
            ws = web.WebSocketResponse()
            await ws.prepare(request)
            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    await ws.send_str(msg.data)
                elif msg.type == aiohttp.WSMsgType.BINARY:
                    await ws.send_bytes(msg.data)
            return ws
        if request.path == "/upload":
            return web.Response(body=await request.read())
        if request.path == "/stream":
            response = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
            await response.prepare(request)
            await response.write(b"data: first\n\n")
            await response.write(b"data: second\n\n")
            await response.write_eof()
            return response
        return web.json_response(
            {
                "path": request.path,
                "origin": request.headers.get("Origin"),
                "auth": request.headers.get("Authorization"),
                "cookie": request.headers.get("Cookie"),
                "forwarded": request.headers.get("Forwarded"),
                "forwarded_host": request.headers.get("X-Forwarded-Host"),
            }
        )

    original = web.Application()
    original.router.add_route("*", "/{tail:.*}", desktop)
    async with TestServer(original) as source:
        app = create_app(str(source.make_url("/")), "test-pairing-secret")
        async with TestClient(
            TestServer(app), cookie_jar=aiohttp.CookieJar(unsafe=True)
        ) as client:
            yield client, state, str(source.make_url("/")).rstrip("/")


async def pair(client):
    response = await client.post(
        "/mobile/pair", data={"code": "test-pairing-secret"}, allow_redirects=False
    )
    assert response.status == 303
    assert response.cookies["xfriend_mobile"]["httponly"]
    assert response.cookies["xfriend_mobile"]["samesite"] == "Strict"


async def test_public_discovery_does_not_expose_account(connected):
    client, state, _ = connected
    response = await client.get("/mobile/status")
    status = await response.json()
    assert response.status == 200
    assert status["application"] == "xfriend"
    assert status["protocol"] == 1
    assert status["state"] == "ready"
    assert status["entry_path"] == "/chat"
    assert response.headers["Cache-Control"] == "no-store"
    assert (
        not {"user", "user_id", "space_id", "code", "access_token", "refresh_token"}
        & status.keys()
    )
    state["authenticated"] = False
    response = await client.get("/mobile/status")
    assert (await response.json())["state"] == "login_required"


async def test_discovery_never_grants_access(connected):
    client, _, _ = connected
    assert (await client.get("/mobile/status")).status == 200
    assert (await client.get("/chat")).status == 401


async def test_native_v2_requires_pair_and_origin_and_rewrites(connected):
    client, state, source = connected
    path = "/mobile/v2/runs/run-1/events?after_seq=17&token=cloud&access_token=cloud"
    assert (await client.get(path)).status == 401
    await pair(client)
    assert (await client.get(path)).status == 403
    origin = str(client.make_url("/")).rstrip("/")
    response = await client.get(
        path, headers={"Origin": origin, "Authorization": "Bearer phone-cloud"}
    )
    assert response.status == 200
    body = await response.json()
    assert body["path"] == "/api/mobile/v2/runs/run-1/events"
    assert body["auth"] is None and body["cookie"] is None
    assert body["origin"] == source
    state["space"] = "changed-space"
    assert (await client.get(path, headers={"Origin": origin})).status == 401


async def test_native_v2_create_keeps_cookie_identity_not_cloud_bearer(connected):
    client, _, _ = connected
    await pair(client)
    origin = str(client.make_url("/")).rstrip("/")
    response = await client.post(
        "/mobile/v2/runs",
        json={"resource_id": "host-agent"},
        headers={"Origin": origin, "Authorization": "Bearer phone-cloud"},
    )
    assert response.status == 200
    assert (await response.json())["path"] == "/api/mobile/v2/runs"


async def test_unauthenticated_and_wrong_code_denied(connected):
    client, _, _ = connected
    assert (await client.get("/api/agents")).status == 401
    assert (await client.post("/mobile/pair", data={"code": "wrong"})).status == 401
    assert (await client.get("/chat")).status == 401


async def test_native_webview_opaque_origin_still_requires_pair_code(connected):
    client, _, _ = connected
    assert (
        await client.post(
            "/mobile/pair", data={"code": "wrong"}, headers={"Origin": "null"}
        )
    ).status == 401
    response = await client.post(
        "/mobile/pair",
        data={"code": "test-pairing-secret"},
        headers={"Origin": "null"},
        allow_redirects=False,
    )
    assert response.status == 303
    assert (
        await client.post("/api/agents", json={}, headers={"Origin": "null"})
    ).status == 403


async def test_same_data_and_no_credentials_forwarded(connected):
    client, _, source = connected
    await pair(client)
    response = await client.get(
        "/api/agents",
        headers={
            "Authorization": "Bearer attacker",
            "Forwarded": "host=attacker.example",
            "X-Forwarded-Host": "attacker.example",
        },
    )
    body = await response.json()
    assert body == {
        "path": "/api/agents",
        "origin": source,
        "auth": None,
        "cookie": None,
        "forwarded": None,
        "forwarded_host": None,
    }
    assert response.headers["Cache-Control"] == "no-store"


async def test_account_switch_revokes_session(connected):
    client, state, _ = connected
    await pair(client)
    state["user"] = "another"
    assert (await client.get("/chat")).status == 401
    state["user"] = "owner"
    assert (await client.get("/chat")).status == 401


async def test_state_changes_require_origin_and_cannot_logout_pc(connected):
    client, _, _ = connected
    await pair(client)
    assert (await client.post("/api/agents", json={})).status == 403
    origin = str(client.make_url("/")).rstrip("/")
    assert (
        await client.post(
            "/api/desktop/auth/logout", json={}, headers={"Origin": origin}
        )
    ).status == 403
    assert (
        await client.post("/api/agents", json={}, headers={"Origin": origin})
    ).status == 200


async def test_websocket_chat_and_desktop_binary_frames(connected):
    client, _, _ = connected
    await pair(client)
    origin = str(client.make_url("/")).rstrip("/")
    async with client.ws_connect("/socket", headers={"Origin": origin}) as ws:
        await ws.send_str('{"type":"message","text":"hello"}')
        assert (await ws.receive()).data == '{"type":"message","text":"hello"}'
        await ws.send_bytes(b"desktop-frame")
        assert (await ws.receive()).data == b"desktop-frame"


async def test_rate_limit_and_pair_origin(connected):
    client, _, _ = connected
    assert (
        await client.post(
            "/mobile/pair",
            data={"code": "test-pairing-secret"},
            headers={"Origin": "https://evil.invalid"},
        )
    ).status == 403
    for _ in range(5):
        assert (await client.post("/mobile/pair", data={"code": "wrong"})).status == 401
    assert (
        await client.post("/mobile/pair", data={"code": "test-pairing-secret"})
    ).status == 429


async def test_open_websocket_closes_when_pc_logs_out(connected):
    client, state, _ = connected
    await pair(client)
    origin = str(client.make_url("/")).rstrip("/")
    async with client.ws_connect("/socket", headers={"Origin": origin}) as ws:
        state["authenticated"] = False
        message = await ws.receive(timeout=5)
        assert message.type in {aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED}


async def test_logged_out_pc_invalidates_cookie(connected):
    client, state, _ = connected
    await pair(client)
    state["authenticated"] = False
    assert (await client.get("/chat")).status == 401
    state["authenticated"] = True
    assert (await client.get("/chat")).status == 401


async def test_upload_and_sse_body_preserved(connected):
    client, _, _ = connected
    await pair(client)
    origin = str(client.make_url("/")).rstrip("/")
    body = b"knowledge-document" * 10000
    response = await client.post("/upload", data=body, headers={"Origin": origin})
    assert await response.read() == body
    response = await client.get("/stream")
    assert response.headers["Content-Type"] == "text/event-stream"
    assert await response.read() == b"data: first\n\ndata: second\n\n"


@pytest.mark.parametrize(
    "source",
    [
        "https://example.com",
        "http://127.0.0.1@evil.invalid",
        "http://localhost/x",
        "http://localhost?x=y",
    ],
)
def test_source_must_be_loopback(source):
    with pytest.raises(ValueError):
        validate_source(source)
