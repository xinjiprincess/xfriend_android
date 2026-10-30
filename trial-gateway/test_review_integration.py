"""Local HTTP integration evidence; these tests do NOT call a real model or Android."""

import asyncio
import base64
import collections
import io
import json
import re
import struct
import time
import wave

import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

import gateway
from gateway import create_app
from review_pipeline import ReviewPipeline, validate_analysis
from reviews import STORE
from test_gateway import identity
from test_reviews import analysis, answer, auth_key, good_call, spec, store_at


@pytest.mark.parametrize(
    "mode,expected_status",
    [
        ("valid", 201),
        ("missing", 503),
        ("short", 503),
        ("rejected", 503),
        ("inactive", 401),
        ("redirect", 503),
    ],
)
async def test_activation_service_bearer_http_contract(
    tmp_path, monkeypatch, caplog, mode, expected_status
):
    """Mirror activation sync_api.py over real loopback HTTP, with no remote calls."""
    _, public, lease = auth_key()
    service_secret = "synthetic-service-credential-0000000000"
    user_token = "synthetic-user-session-000000000000000"
    monkeypatch.delenv("XFRIEND_GATEWAY_INTROSPECTION_SECRET", raising=False)
    if mode != "missing":
        monkeypatch.setenv(
            "XFRIEND_GATEWAY_INTROSPECTION_SECRET",
            "too-short" if mode == "short" else service_secret,
        )
    calls = []
    redirects = []

    async def inspect(request):
        calls.append(await request.json())
        assert request.headers["Authorization"] == f"Bearer {service_secret}"
        assert calls[-1] == {"token": user_token}
        assert "X-Xfriend-Lease" not in request.headers
        assert "XFRIEND_GATEWAY_INTROSPECTION_SECRET" not in request.headers
        if mode == "rejected":
            return web.json_response({"error": "gateway_forbidden"}, status=403)
        if mode == "redirect":
            raise web.HTTPFound("/leak")
        return web.json_response(
            {
                "active": mode != "inactive",
                "user_id": "user",
                "entitled": True,
                "expires_at": int(time.time()) + 3600,
            }
        )

    async def leak(request):
        redirects.append(True)
        return web.json_response({"active": True, "user_id": "user"})

    upstream = web.Application()
    upstream.router.add_post("/api/v2/auth/introspect", inspect)
    upstream.router.add_route("*", "/leak", leak)
    async with TestServer(upstream) as activation:
        original_post = aiohttp.ClientSession.post

        def local_transport(self, url, **kwargs):
            # Substitute ONLY the trusted endpoint's transport for loopback HTTP.
            # Production HTTPS validation remains enabled and unchanged.
            if str(url) == "https://activation.example.invalid/api/v2/auth/introspect":
                assert kwargs["allow_redirects"] is False
                assert self.timeout.total == 10 and self.trust_env is False
                url = activation.make_url("/api/v2/auth/introspect")
            return original_post(self, url, **kwargs)

        monkeypatch.setattr(aiohttp.ClientSession, "post", local_transport)
        app = create_app(
            tmp_path / "q.db",
            "",
            public_key=public,
            upstream_call=good_call,
            introspection_url="https://activation.example.invalid/api/v2/auth/introspect",
            worker_enabled=False,
        )
        async with TestClient(TestServer(app)) as client:
            response = await client.post(
                "/v2/reviews",
                json=spec(),
                headers={
                    "Authorization": f"Bearer {user_token}",
                    # An untrusted client cannot fill in the missing service secret.
                    "XFRIEND_GATEWAY_INTROSPECTION_SECRET": service_secret,
                },
            )
            body = await response.text()
            assert response.status == expected_status
            assert service_secret not in body and user_token not in body
            # Signed leases remain usable even if optional service auth is broken.
            response = await client.post("/v2/reviews", json=spec(), headers=lease)
            assert response.status == (200 if mode == "valid" else 201)
    assert len(calls) == (0 if mode in {"missing", "short"} else 1)
    assert not redirects
    assert service_secret not in caplog.text and user_token not in caplog.text


async def snapshot(client, auth, rid="review"):
    response = await client.get(f"/v2/reviews/{rid}", headers=auth)
    assert response.status == 200
    return await response.json()


async def terminal(client, auth, rid="review"):
    deadline = asyncio.get_running_loop().time() + 30
    while asyncio.get_running_loop().time() < deadline:
        value = await snapshot(client, auth, rid)
        if value["state"] in {"succeeded", "failed", "cancelled"}:
            return value
        await asyncio.sleep(0.02)
    pytest.fail("local mock HTTP job did not finish")


def assert_android_artifact(value):
    """The actual Kotlin client is reviewed separately; enforce its wire preconditions."""
    assert value["state"] == "succeeded" and value["percent"] == 100
    result = value["result"]
    assert result["correctedText"].strip() and result["reportText"].strip()
    assert isinstance(result["analysisJson"], dict)
    evidence = result["analysisJson"]["evidence"]
    ids = {item["id"] for item in evidence}
    assert len(ids) == len(evidence)
    assert all(
        item["quote"].strip() and item["quote"] in result["rawTranscript"]
        for item in evidence
    )
    citations = [
        ref.strip()
        for group in re.findall(
            r"\[((?:[A-Za-z0-9_-]+)(?:,\s*[A-Za-z0-9_-]+)*)\]", result["reportText"]
        )
        for ref in group.split(",")
    ]
    assert citations and all(ref in ids for ref in citations)
    for finding in result["report"]["findings"]:
        assert all(ref in ids for ref in finding["citations"])
    for action in result["analysisJson"]["actions"]:
        assert action["evidenceIds"] and all(
            ref in ids for ref in action["evidenceIds"]
        )


async def send_sse(response, text, finish="stop", *, done=True, error=False):
    for offset in range(0, len(text), 31):
        event = {
            "choices": [
                {
                    "delta": {"content": text[offset : offset + 31]},
                    "finish_reason": None,
                }
            ]
        }
        wire = ("data: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode()
        for start in range(0, len(wire), 19):
            await response.write(wire[start : start + 19])
    event = {"choices": [{"delta": {}, "finish_reason": finish}]}
    await response.write(("data: " + json.dumps(event) + "\n\n").encode())
    if error:
        await response.write(
            b'data: {"error":{"message":"synthetic upstream failure"}}\n\n'
        )
    if done:
        await response.write(b"data: [DONE]\n\n")


async def test_local_http_long_audio_complete_review_restart_and_one_credit(
    tmp_path, monkeypatch
):
    """22.4 MB source → real upload HTTP → mock model HTTP/SSE → final/replay."""
    key, public, auth = auth_key()
    rate, seconds = 16000, 700
    # Every second has its own sample value, so the mock can check byte-for-byte
    # coverage and detect a lost tail, misaligned window, or substituted source.
    frames = b"".join(struct.pack("<h", second) * rate for second in range(seconds))
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(rate)
        output.writeframes(frames)
    raw = buffer.getvalue()
    assert len(raw) == 22_400_044 and len(raw) > 20 * 1024 * 1024
    calls = collections.Counter()
    accepted_ranges = []
    mapped_ids = set()
    token_budgets = []
    stopped = asyncio.Event()
    continue_provider = asyncio.Event()
    block_once = True
    truncate_analysis = True

    async def completion(request):
        nonlocal block_once, truncate_analysis
        body = await request.json()
        content = body["messages"][-1]["content"]
        if isinstance(content, list):
            audio = base64.b64decode(content[0]["input_audio"]["data"].split(",")[1])
            with wave.open(io.BytesIO(audio), "rb") as source:
                assert source.getframerate() == rate and source.getsampwidth() == 2
                pcm = source.readframes(source.getnframes())
            start = struct.unpack("<h", pcm[:2])[0]
            duration = len(pcm) / (rate * 2)
            end = start + duration
            assert duration <= 90
            assert pcm == frames[start * rate * 2 : int(end * rate * 2)]
            calls[start, end] += 1
            if start == 0 and end == 90:
                return web.json_response(
                    answer("not a completed transcript", "max_tokens")
                )
            if start == 44 and end == 90 and block_once:
                block_once = False
                stopped.set()
                await continue_provider.wait()
            accepted_ranges.append((start, end))
            text = f"沟通来源从{start}秒至{end:g}秒，客户预算待确认。"
            if end == seconds:
                text += "录音末尾独有承诺：TAIL700。"
            return web.json_response(answer(text))
        ids = sorted(set(re.findall(r'"(e\d+)"', content)))
        value = json.loads(content)
        if isinstance(value, dict) and "source_id" in value:
            mapped_ids.add(value["id"])
        token_budgets.append(body["max_tokens"])
        assert body["stream"] is True
        response = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
        await response.prepare(request)
        finish = "length" if truncate_analysis else "stop"
        truncate_analysis = False
        await send_sse(response, json.dumps(analysis(ids), ensure_ascii=False), finish)
        return response

    provider = web.Application(client_max_size=8 * 1024 * 1024)
    provider.router.add_post("/chat", completion)
    path = tmp_path / "durable.db"
    rid = "android-record:stable-fingerprint"
    body = spec(
        rid, [{"source_id": "audio-1", "kind": "audio", "asset": "original.wav"}]
    )
    async with TestServer(provider) as model:
        monkeypatch.setattr(gateway, "UPSTREAM", str(model.make_url("/chat")))
        app = create_app(path, "synthetic-only", public_key=public)
        async with TestClient(TestServer(app)) as client:
            created = await client.post("/v2/reviews", headers=auth, json=body)
            assert created.status == 201
            assert (await created.json())["remaining"] == 5
            upload = await client.put(
                f"/v2/reviews/{rid}/uploads/audio-1", headers=auth, data=raw
            )
            assert upload.status == 200
            started = await (
                await client.post(f"/v2/reviews/{rid}/start", headers=auth)
            ).json()
            assert started["remaining"] == 4 and started["result"] is None
            await asyncio.wait_for(stopped.wait(), 30)
            assert calls[0, 46] == 1  # first split leaf already checkpointed
        # A process/service stop must preserve that checkpoint and the same reservation.
        continue_provider.set()
        app = create_app(path, "synthetic-only", public_key=public)
        queued = app[STORE].get("user", rid)
        assert queued["state"] == "queued" and queued["remaining"] == 4
        assert app[STORE].events("user", rid, 0)[-1]["state"] == "queued"
        async with TestClient(TestServer(app)) as client:
            value = await terminal(client, auth, rid)
            assert_android_artifact(value)
            assert value["remaining"] == 4
            assert calls[0, 90] == 1 and calls[0, 46] == 1
            assert token_budgets[:2] == [4000, 8000]
            result = value["result"]
            assert "TAIL700" in result["rawTranscript"]
            assert "TAIL700" in result["correctedText"]
            assert "TAIL700" in result["evidence"][-1]["quote"]
            assert result["sourceRanges"][0]["start_seconds"] == 0
            assert result["sourceRanges"][-1]["end_seconds"] == seconds
            assert mapped_ids == {item["id"] for item in result["evidence"]}
            coverage = 0
            for start, end in sorted(set(accepted_ranges)):
                assert start <= coverage
                coverage = max(coverage, end)
            assert coverage == seconds
            events = await (
                await client.get(f"/v2/reviews/{rid}/events", headers=auth)
            ).text()
            snapshots = [
                json.loads(data)
                for data in re.findall(r"^data: (.*)$", events, re.MULTILINE)
            ]
            assert [x["last_event_id"] for x in snapshots] == list(
                range(1, value["last_event_id"] + 1)
            )
            assert all(
                x["percent"] < 100 and x["result"] is None for x in snapshots[:-1]
            )
            assert snapshots[-1] == value
            assert any(
                x["state"] == "running" and x["partial_report"] for x in snapshots
            )
            replay = await (
                await client.get(
                    f"/v2/reviews/{rid}/events",
                    headers=auth | {"Last-Event-ID": str(value["last_event_id"] - 1)},
                )
            ).text()
            assert len(re.findall(r"^id:", replay, re.MULTILINE)) == 1
            repeated = await client.post("/v2/reviews", headers=auth, json=body)
            assert (
                repeated.status == 200 and (await repeated.json())["result"] == result
            )
            for action in ("start", "resume", "cancel"):
                repeated = await (
                    await client.post(f"/v2/reviews/{rid}/{action}", headers=auth)
                ).json()
                assert repeated["state"] == "succeeded" and repeated["remaining"] == 4
            renewed = {"X-Xfriend-Lease": identity(key, space="second-device-space")}
            assert (await (await client.get("/v1/quota", headers=renewed)).json())[
                "remaining"
            ] == 4
            assert (
                app[STORE]
                .db.execute("SELECT count(*) FROM tasks WHERE state='done'")
                .fetchone()[0]
                == 1
            )


async def test_cancel_partial_upload_resume_draft_then_one_charge(tmp_path):
    _key, public, auth = auth_key()
    app = create_app(tmp_path / "q.db", "", public_key=public, upstream_call=good_call)
    async with TestClient(TestServer(app)) as client:
        body = spec(
            sources=[
                {"source_id": "first", "kind": "text"},
                {"source_id": "tail", "kind": "text"},
            ]
        )
        await client.post("/v2/reviews", headers=auth, json=body)
        await client.put(
            "/v2/reviews/review/uploads/first", headers=auth, data=b"first source"
        )
        cancelled = await (
            await client.post("/v2/reviews/review/cancel", headers=auth)
        ).json()
        assert cancelled["state"] == "cancelled" and cancelled["remaining"] == 5
        draft = await (
            await client.post("/v2/reviews/review/resume", headers=auth)
        ).json()
        assert draft["state"] == "draft" and draft["remaining"] == 5
        assert (
            await client.put(
                "/v2/reviews/review/uploads/first", headers=auth, data=b"first source"
            )
        ).status == 200
        assert (
            await client.put(
                "/v2/reviews/review/uploads/first", headers=auth, data=b"changed"
            )
        ).status == 409
        await client.put(
            "/v2/reviews/review/uploads/tail", headers=auth, data=b"full final tail"
        )
        await client.post("/v2/reviews/review/start", headers=auth)
        final = await terminal(client, auth)
        assert_android_artifact(final)
        assert final["remaining"] == 4
        assert final["result"]["rawTranscript"] == "first source\nfull final tail"


async def test_partial_upload_cancel_is_not_resumable_after_retention(tmp_path):
    _key, public, auth = auth_key()
    app = create_app(
        tmp_path / "q.db",
        "",
        public_key=public,
        upstream_call=good_call,
        worker_enabled=False,
    )
    async with TestClient(TestServer(app)) as client:
        await client.post("/v2/reviews", headers=auth, json=spec())
        await client.post("/v2/reviews/review/cancel", headers=auth)
        app[STORE].db.execute("UPDATE reviews SET terminal=?", (time.time() - 86401,))
        assert (
            await client.post("/v2/reviews/review/resume", headers=auth)
        ).status == 410
        assert (await snapshot(client, auth))["remaining"] == 5


async def test_no_findings_and_whitespace_source_tail_still_yields_usable_artifact(
    tmp_path,
):
    _key, public, auth = auth_key()

    async def uncertain(body):
        value = analysis([])
        value.update(
            summary="证据不足，暂不作业务判断",
            findings=[],
            profile_suggestions=[],
            actions=[],
        )
        value["profile"].update(summary="", needs=[])
        return answer(json.dumps(value, ensure_ascii=False))

    app = create_app(tmp_path / "q.db", "", public_key=public, upstream_call=uncertain)
    async with TestClient(TestServer(app)) as client:
        await client.post("/v2/reviews", headers=auth, json=spec(roles=["复盘"]))
        raw = "原文没有销售事实" + " " * 12000
        await client.put(
            "/v2/reviews/review/uploads/text", headers=auth, data=raw.encode()
        )
        await client.post("/v2/reviews/review/start", headers=auth)
        final = await terminal(client, auth)
        assert_android_artifact(final)
        assert final["result"]["rawTranscript"] == raw
        assert len(final["result"]["evidence"]) == 1
        assert final["result"]["report"]["findings"] == []
        assert "[e1]" in final["result"]["reportText"]


@pytest.mark.parametrize("broken", ["missing_done", "error_after_stop"])
async def test_provider_stream_error_or_missing_terminator_never_charges(
    tmp_path, monkeypatch, broken
):
    _key, public, auth = auth_key()

    async def completion(request):
        await request.json()
        response = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
        await response.prepare(request)
        await send_sse(
            response,
            json.dumps(analysis(["e1"])),
            done=broken != "missing_done",
            error=broken == "error_after_stop",
        )
        return response

    provider = web.Application()
    provider.router.add_post("/chat", completion)
    async with TestServer(provider) as model:
        monkeypatch.setattr(gateway, "UPSTREAM", str(model.make_url("/chat")))
        app = create_app(tmp_path / "q.db", "synthetic-only", public_key=public)
        async with TestClient(TestServer(app)) as client:
            await client.post("/v2/reviews", headers=auth, json=spec(roles=["复盘"]))
            await client.put(
                "/v2/reviews/review/uploads/text", headers=auth, data=b"source"
            )
            await client.post("/v2/reviews/review/start", headers=auth)
            final = await terminal(client, auth)
            assert final["state"] == "failed" and final["remaining"] == 5
            assert final["result"] is None and final["percent"] < 100
            assert "synthetic upstream failure" not in json.dumps(final)


@pytest.mark.parametrize(
    "field,value", [("summary", " "), ("summary", "带有伪造引用[e999]的报告")]
)
def test_unrenderable_or_invalid_inline_citations_rejected_before_charge(field, value):
    output = analysis(["e1"])
    output[field] = value
    with pytest.raises(ValueError):
        validate_analysis(output, {"e1"})


async def test_preupgrade_invalid_artifact_checkpoint_is_regenerated_not_charged(
    tmp_path,
):
    quota, store = store_at(tmp_path)
    store.create("user", spec(roles=["复盘"]))
    store.upload("user", "review", "text", b"original source", 0)
    store.start("user", "review")
    job = store.claim()
    source = [{"source_id": "text", "quote": "original source"}]
    store.checkpoint(*job[:3], "source/text", source)
    invalid = analysis(["e1"])
    invalid["summary"] = "旧检查点中存在错误引用[e999]"
    store.checkpoint(*job[:3], "analysis/0/0", invalid, 80)
    calls = []

    async def regenerated(body):
        calls.append(body)
        return await good_call(body)

    result = await ReviewPipeline(store, job, regenerated, "mock").run()
    assert len(calls) == 1
    assert store.checkpoint(*job[:3], "source/text") == source
    assert "e999" not in result["reportText"]
    assert_android_artifact(store.finish(*job[:3], result))
    assert quota.remaining("user") == 4
    quota.close()
