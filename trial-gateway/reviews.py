"""Authenticated v2 HTTP adapter and persistent in-process worker."""

import asyncio
import contextlib
import json
import os
import re

import aiohttp
from aiohttp import web

from review_pipeline import ReviewPipeline, validate_media
from review_store import TERMINAL, LostLease, ReviewStore

STORE = web.AppKey("review_store", object)
AUTH = web.AppKey("review_auth", object)
IDENTIFIER = re.compile(r"[A-Za-z0-9_.:-]{1,150}\Z")
REQUEST_ID = re.compile(r"[A-Za-z0-9_.:-]{1,200}\Z")


def validate_spec(body):
    if not isinstance(body, dict) or set(body) - {
        "request_id",
        "roles",
        "prompt",
        "sources",
    }:
        raise web.HTTPBadRequest(text="invalid_review")
    rid = body.get("request_id")
    roles = body.get("roles", ["沟通复盘"])
    prompt = body.get("prompt", "")
    sources = body.get("sources")
    if not isinstance(rid, str) or not REQUEST_ID.fullmatch(rid) or rid in {".", ".."}:
        raise web.HTTPBadRequest(text="invalid_request_id")
    if (
        not isinstance(roles, list)
        or not 1 <= len(roles) <= 8
        or any(not isinstance(x, str) or not 1 <= len(x) <= 4500 for x in roles)
    ):
        raise web.HTTPBadRequest(text="invalid_roles")
    if not isinstance(prompt, str) or len(prompt) > 24000:
        raise web.HTTPBadRequest(text="invalid_prompt")
    if not isinstance(sources, list) or not 1 <= len(sources) <= 100:
        raise web.HTTPBadRequest(text="invalid_sources")
    ids = set()
    for source in sources:
        if not isinstance(source, dict) or set(source) - {
            "source_id",
            "kind",
            "asset",
            "region",
        }:
            raise web.HTTPBadRequest(text="invalid_source")
        sid = source.get("source_id")
        if (
            not isinstance(sid, str)
            or not IDENTIFIER.fullmatch(sid)
            or sid in {".", ".."}
            or sid in ids
            or source.get("kind") not in {"audio", "text", "image"}
        ):
            raise web.HTTPBadRequest(text="invalid_source")
        ids.add(sid)
        if "asset" in source and (
            not isinstance(source["asset"], str) or not 1 <= len(source["asset"]) <= 250
        ):
            raise web.HTTPBadRequest(text="invalid_asset")
        if "region" in source:
            region = source["region"]
            if (
                not isinstance(region, list)
                or len(region) != 4
                or any(type(x) is not int or x < 0 for x in region)
                or not region[2]
                or not region[3]
            ):
                raise web.HTTPBadRequest(text="invalid_region")
    return {"request_id": rid, "roles": roles, "prompt": prompt, "sources": sources}


def install(
    app,
    quota,
    db_path,
    verify,
    public_key,
    call_key,
    model,
    introspection_url=None,
    introspection_call=None,
    worker_enabled=True,
):
    store = ReviewStore(quota, db_path)
    app[STORE] = store
    endpoint = introspection_url or os.getenv("XFRIEND_SESSION_INTROSPECTION_URL")
    service_secret = os.getenv("XFRIEND_GATEWAY_INTROSPECTION_SECRET", "")
    if endpoint and (not endpoint.startswith("https://") or "@" in endpoint):
        raise ValueError("Introspection requires a trusted HTTPS endpoint")

    async def identity(request):
        lease_header = request.headers.get("X-Xfriend-Lease")
        lease_user = verify(lease_header, public_key) if lease_header else None
        authorization = request.headers.get("Authorization")
        if authorization is None:
            if lease_user is None:
                raise web.HTTPUnauthorized(text="authentication_required")
            return lease_user
        if (
            not authorization.startswith("Bearer ")
            or not 1 <= len(authorization[7:]) <= 12000
        ):
            raise web.HTTPUnauthorized(text="invalid_session")
        if not endpoint and not introspection_call:
            raise web.HTTPUnauthorized(text="session_auth_unavailable")
        try:
            if introspection_call:
                result = await asyncio.wait_for(
                    introspection_call(authorization[7:]), 10
                )
            else:
                # Activation sync_api: service Bearer header, user token in JSON.
                # Never accept a service credential supplied by the mobile client.
                if (
                    not 32 <= len(service_secret) <= 200
                    or not service_secret.isascii()
                    or any(not 33 <= ord(char) <= 126 for char in service_secret)
                ):
                    raise ValueError()
                if not 20 <= len(authorization[7:]) <= 200:
                    raise web.HTTPUnauthorized(text="invalid_session")
                async with aiohttp.ClientSession(
                    timeout=aiohttp.ClientTimeout(total=10), trust_env=False
                ) as client:
                    async with client.post(
                        endpoint,
                        headers={"Authorization": f"Bearer {service_secret}"},
                        json={"token": authorization[7:]},
                        allow_redirects=False,
                    ) as response:
                        # 401/403 here reject OUR credential, not the user's token.
                        # Invalid user sessions are returned as active:false.
                        if response.status != 200:
                            raise ValueError()
                        raw = bytearray()
                        async for chunk in response.content.iter_chunked(8192):
                            raw.extend(chunk)
                            if len(raw) > 65536:
                                raise ValueError()
                        result = json.loads(raw)
        except web.HTTPUnauthorized:
            raise
        except Exception:
            raise web.HTTPServiceUnavailable(
                text="session_verification_unavailable"
            ) from None
        uid = result.get("user_id") if isinstance(result, dict) else None
        if (
            not isinstance(result, dict)
            or result.get("active") is not True
            or not isinstance(uid, str)
            or not 1 <= len(uid) <= 128
            or (lease_user and lease_user != uid)
        ):
            raise web.HTTPUnauthorized(text="invalid_session")
        return uid

    app[AUTH] = identity

    def reply(value, status=200):
        return web.json_response(
            value,
            status=status,
            headers={"Cache-Control": "no-store"},
            dumps=lambda data: json.dumps(data, ensure_ascii=False),
        )

    async def create(request):
        uid = await identity(request)
        try:
            spec = validate_spec(await request.json())
        except (json.JSONDecodeError, UnicodeError):
            raise web.HTTPBadRequest(text="invalid_review") from None
        value, created = await asyncio.to_thread(store.create, uid, spec)
        return reply(value, 201 if created else 200)

    async def upload(request):
        uid = await identity(request)
        rid, sid = request.match_info["rid"], request.match_info["sid"]
        source = await asyncio.to_thread(store.source, uid, rid, sid)
        limit = {"audio": 128, "image": 10, "text": 2}[source["kind"]] * 1024 * 1024
        data = bytearray()
        async for chunk in request.content.iter_chunked(65536):
            data.extend(chunk)
            if len(data) > limit:
                raise web.HTTPRequestEntityTooLarge(
                    max_size=limit, actual_size=len(data)
                )
        raw = bytes(data)
        duration = await asyncio.to_thread(validate_media, source["kind"], raw)
        return reply(
            await asyncio.to_thread(store.upload, uid, rid, sid, raw, duration)
        )

    async def get(request):
        return reply(
            await asyncio.to_thread(
                store.get, await identity(request), request.match_info["rid"]
            )
        )

    async def change(request):
        uid, rid = await identity(request), request.match_info["rid"]
        action = request.match_info["action"]
        if action == "cancel":
            value = await asyncio.to_thread(store.cancel, uid, rid)
        else:
            value = await asyncio.to_thread(store.start, uid, rid, action == "resume")
        return reply(value)

    async def events(request):
        uid, rid = await identity(request), request.match_info["rid"]
        snapshot = await asyncio.to_thread(store.get, uid, rid)
        try:
            cursor = int(
                request.headers.get("Last-Event-ID", request.query.get("after", "0"))
            )
            if not 0 <= cursor <= snapshot["last_event_id"]:
                raise ValueError()
        except ValueError:
            raise web.HTTPBadRequest(text="invalid_event_cursor") from None
        response = web.StreamResponse(
            headers={
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-store",
                "X-Accel-Buffering": "no",
            }
        )
        await response.prepare(request)
        # Bounded connection lifetime also rechecks expiring auth on reconnect.
        for _ in range(120):
            batch = await asyncio.to_thread(store.events, uid, rid, cursor)
            for value in batch:
                cursor = value["last_event_id"]
                await response.write(
                    f"id: {cursor}\nevent: review\ndata: {json.dumps(value, ensure_ascii=False)}\n\n".encode()
                )
            current = await asyncio.to_thread(store.get, uid, rid)
            if current["state"] in TERMINAL and cursor >= current["last_event_id"]:
                break
            if len(batch) == 100:
                continue
            await response.write(b": keepalive\n\n")
            await asyncio.sleep(1)
        return response

    async def run_job(job):
        uid, rid, token, _ = job

        async def heartbeat():
            while True:
                await asyncio.sleep(15)
                await asyncio.to_thread(store.heartbeat, uid, rid, token)

        processing = asyncio.create_task(
            ReviewPipeline(store, job, app[call_key], model).run()
        )
        beating = asyncio.create_task(heartbeat())
        try:
            done, _ = await asyncio.wait(
                {processing, beating}, return_when=asyncio.FIRST_COMPLETED
            )
            if beating in done:
                await beating
            result = await processing
            await asyncio.to_thread(store.finish, uid, rid, token, result)
        except LostLease:
            pass
        except asyncio.CancelledError:
            await asyncio.shield(asyncio.to_thread(store.release, uid, rid, token))
            raise
        except Exception:
            with contextlib.suppress(LostLease):
                await asyncio.to_thread(store.fail, uid, rid, token)
        finally:
            processing.cancel()
            beating.cancel()
            await asyncio.gather(processing, beating, return_exceptions=True)

    async def worker():
        while True:
            job = await asyncio.to_thread(store.claim)
            if job:
                await run_job(job)
            else:
                await asyncio.sleep(0.2)

    async def janitor():
        while True:
            await asyncio.to_thread(store.cleanup)
            await asyncio.sleep(30)

    async def lifecycle(_):
        tasks = [asyncio.create_task(janitor())]
        if worker_enabled:
            tasks.append(asyncio.create_task(worker()))
        try:
            yield
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    app.cleanup_ctx.append(lifecycle)
    app.router.add_post("/v2/reviews", create)
    app.router.add_put("/v2/reviews/{rid}/uploads/{sid}", upload)
    app.router.add_get("/v2/reviews/{rid}", get)
    app.router.add_get("/v2/reviews/{rid}/events", events)
    app.router.add_post(r"/v2/reviews/{rid}/{action:start|cancel|resume}", change)
