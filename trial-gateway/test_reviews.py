import asyncio
import base64
import concurrent.futures
import io
import itertools
import json
import re
import time
import wave

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from PIL import Image

from gateway import Quota, create_app
from review_pipeline import (
    ReviewPipeline,
    audio_info,
    merge_overlap,
    streamed_summary,
    validate_analysis,
    validate_media,
)
from review_store import LostLease, ReviewStore
from reviews import STORE, validate_spec
from test_gateway import encoded, identity, task


def spec(rid="review", sources=None, roles=None):
    return validate_spec(
        {
            "request_id": rid,
            "roles": roles or ["需求", "风险"],
            "sources": sources or [{"source_id": "text", "kind": "text"}],
        }
    )


def analysis(ids):
    return {
        "summary": "预算待确认",
        "findings": [{"text": "先核实预算", "citations": ids}],
        "uncertainties": ["预算未知"],
        "profile_suggestions": [{"text": "确认需求", "citations": ids}],
        "profile": {
            "summary": "需求明确",
            "preferences": [],
            "needs": ["确认预算"],
            "budget": "",
            "timing": "",
            "decisionRole": "",
            "grade": "",
            "evidenceIds": ids,
        },
        "actions": [
            {
                "title": "确认预算",
                "dueDate": None,
                "priority": "medium",
                "evidenceIds": ids,
            }
        ],
        "probability": {
            "low": None,
            "high": None,
            "reason": "证据不足",
            "evidenceIds": [],
        },
    }


def answer(content, reason="stop"):
    return {"choices": [{"finish_reason": reason, "message": {"content": content}}]}


async def good_call(body):
    content = body["messages"][-1]["content"]
    if isinstance(content, list):
        return answer("客户需要先确认预算")
    ids = sorted(set(re.findall(r'"(e\d+)"', content)))
    return answer(json.dumps(analysis(ids), ensure_ascii=False))


def wav(seconds, rate=8000):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(1)
        output.setframerate(rate)
        output.writeframes(b"\x80" * int(seconds * rate))
    return buffer.getvalue()


def store_at(tmp_path):
    path = tmp_path / "q.db"
    quota = Quota(path)
    return quota, ReviewStore(quota, path)


def ready(store, rid="review", user="user"):
    store.create(user, spec(rid))
    store.upload(user, rid, "text", "客户预算待确认".encode(), 0)
    store.start(user, rid)


def test_durable_reservation_checkpoint_fencing_and_atomic_final(tmp_path):
    quota, store = store_at(tmp_path)
    ready(store)
    job = store.claim()
    store.checkpoint(*job[:3], "asr/0", {"text": "已转写"}, 25)
    quota.close()
    quota, store = store_at(tmp_path)
    assert quota.remaining("user") == 4
    assert store.claim() is None
    quota.db.execute("UPDATE reviews SET lease_until=0")
    resumed = store.claim()
    assert resumed[2] != job[2]
    assert store.checkpoint(*resumed[:3], "asr/0") == {"text": "已转写"}
    with pytest.raises(LostLease):
        store.finish(*job[:3], {"report": "stale"})
    assert store.finish(*resumed[:3], {"report": "persisted"})["percent"] == 100
    assert store.cancel("user", "review")["state"] == "succeeded"
    assert store.start("user", "review", True)["result"]["report"] == "persisted"
    assert quota.remaining("user") == 4
    quota.close()


def test_final_and_charge_roll_back_together(tmp_path, monkeypatch):
    quota, store = store_at(tmp_path)
    ready(store)
    job = store.claim()

    def fail(*args):
        raise RuntimeError("simulated failed persistence")

    monkeypatch.setattr(store, "event", fail)
    with pytest.raises(RuntimeError):
        store.finish(*job[:3], {"report": "never committed"})
    assert store.get("user", "review")["state"] == "running"
    assert store.get("user", "review")["result"] is None
    assert quota.db.execute("SELECT state FROM tasks").fetchone()[0] == "pending"
    quota.close()


def test_cancel_fences_old_worker_resume_one_credit_cleanup(tmp_path):
    quota, store = store_at(tmp_path)
    ready(store)
    job = store.claim()
    store.checkpoint(*job[:3], "part", {"quote": "saved"})
    assert store.cancel("user", "review")["remaining"] == 5
    with pytest.raises(LostLease):
        store.finish(*job[:3], {"report": "late"})
    assert store.start("user", "review", True)["remaining"] == 4
    second = store.claim()
    assert store.checkpoint(*second[:3], "part") == {"quote": "saved"}
    store.cancel("user", "review")
    store.cleanup(time.time() + 86401)
    assert list(store.media.iterdir()) == []
    with pytest.raises(web.HTTPGone):
        store.start("user", "review", True)
    assert quota.remaining("user") == 5
    quota.close()


def test_five_account_credits_shared_with_v1_concurrent_starts(tmp_path):
    quota, store = store_at(tmp_path)
    quota.reserve("user", "v1", "hash")
    quota.complete("user", "v1", {"report": "done"})
    for i in range(8):
        store.create("user", spec(str(i)))
        store.upload("user", str(i), "text", b"hello", 0)

    def start(i):
        try:
            store.start("user", str(i))
            return True
        except web.HTTPPaymentRequired:
            return False

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(start, range(8))) == 4
    assert quota.remaining("user") == 0
    quota.close()
    quota, store = store_at(tmp_path)
    assert quota.remaining("user") == 0
    assert quota.remaining("other") == 5
    quota.close()


def test_decoded_duration_and_malformed_wav(tmp_path):
    assert validate_media("audio", wav(90)) == 90
    with pytest.raises(web.HTTPBadRequest):
        validate_media("audio", wav(1)[:-100])
    with pytest.raises(web.HTTPBadRequest):
        validate_media("audio", b"RIFFxxxxWAVE")
    quota, store = store_at(tmp_path)
    store.create(
        "user",
        spec(
            sources=[
                {"source_id": "a", "kind": "audio"},
                {"source_id": "b", "kind": "audio"},
            ]
        ),
    )
    raw = wav(1801)
    duration = validate_media("audio", raw)
    store.upload("user", "review", "a", raw, duration)
    with pytest.raises(web.HTTPRequestEntityTooLarge):
        store.upload("user", "review", "b", raw, duration)
    assert quota.remaining("user") == 5
    quota.close()


async def wait_final(client, headers, rid="review"):
    for _ in range(200):
        response = await client.get(f"/v2/reviews/{rid}", headers=headers)
        value = await response.json()
        if value["state"] in {"succeeded", "failed"}:
            return value
        await asyncio.sleep(0.02)
    pytest.fail("worker did not finish")


def auth_key():
    key = Ed25519PrivateKey.generate()
    public = encoded(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
    return key, public, {"X-Xfriend-Lease": identity(key)}


async def test_http_full_text_tail_partial_replay_owner_and_idempotence(tmp_path):
    key, public, auth = auth_key()
    calls = []

    async def call(body):
        calls.append(body)
        return await good_call(body)

    app = create_app(tmp_path / "q.db", "", public_key=public, upstream_call=call)
    async with TestClient(TestServer(app)) as client:
        response = await client.post("/v2/reviews", headers=auth, json=spec())
        assert response.status == 201
        assert (
            await client.post("/v2/reviews", headers=auth, json=spec())
        ).status == 200
        assert (
            await client.post("/v2/reviews", headers=auth, json=spec(roles=["changed"]))
        ).status == 409
        assert (
            await client.post("/v2/reviews/review/start", headers=auth)
        ).status == 409
        raw = ("完整内容。" * 2500 + "最后一句不可丢失").encode()
        assert (
            await client.put("/v2/reviews/review/uploads/text", headers=auth, data=raw)
        ).status == 200
        assert (
            await client.put("/v2/reviews/review/uploads/text", headers=auth, data=raw)
        ).status == 200
        assert (
            await client.put(
                "/v2/reviews/review/uploads/text", headers=auth, data=b"other"
            )
        ).status == 409
        other = {"X-Xfriend-Lease": identity(key, user="other")}
        for path in ("", "/events"):
            assert (
                await client.get("/v2/reviews/review" + path, headers=other)
            ).status == 404
        for action in ("start", "cancel", "resume"):
            assert (
                await client.post("/v2/reviews/review/" + action, headers=other)
            ).status == 404
        assert (
            await client.post("/v2/reviews/review/start", headers=auth)
        ).status == 200
        result = await wait_final(client, auth)
        assert result["state"] == "succeeded", result
        assert result["percent"] == 100 and result["remaining"] == 4
        assert result["result"]["rawTranscript"] == raw.decode()
        assert "最后一句不可丢失" in json.dumps(calls, ensure_ascii=False)
        assert "最后一句不可丢失" in result["result"]["evidence"][-1]["quote"]
        assert result["partial_report"]
        events = await (
            await client.get("/v2/reviews/review/events?after=0", headers=auth)
        ).text()
        seq = [int(x) for x in re.findall(r"^id: (\d+)$", events, re.M)]
        assert seq == list(range(1, result["last_event_id"] + 1))
        replay = await (
            await client.get(
                "/v2/reviews/review/events?after=0",
                headers=auth | {"Last-Event-ID": str(seq[-2])},
            )
        ).text()
        assert re.findall(r"^id: (\d+)$", replay, re.M) == [str(seq[-1])]
        values = [json.loads(x) for x in re.findall(r"^data: (.*)$", events, re.M)]
        assert all(x["percent"] < 100 for x in values[:-1])
        assert [x["percent"] for x in values] == sorted(x["percent"] for x in values)
        assert all("stage" not in x and "segments" not in x for x in values)
        assert (
            await client.get("/v2/reviews/review/events?after=-1", headers=auth)
        ).status == 400
        assert (
            await (await client.post("/v2/reviews/review/cancel", headers=auth)).json()
        )["state"] == "succeeded"
        renewed = {"X-Xfriend-Lease": identity(key, space="new-space")}
        assert (await (await client.get("/v1/quota", headers=renewed)).json())[
            "remaining"
        ] == 4


async def test_audio_split_retry_checkpoint_and_overlap(tmp_path):
    quota, store = store_at(tmp_path)
    source = spec(sources=[{"source_id": "audio", "kind": "audio"}], roles=["复盘"])
    store.create("user", source)
    raw = wav(181)
    store.upload("user", "review", "audio", raw, validate_media("audio", raw))
    store.start("user", "review")
    job = store.claim()
    durations = []

    async def call(body):
        if body["model"] == "mimo-v2.5-asr":
            data = body["messages"][0]["content"][0]["input_audio"]["data"].split(",")[
                1
            ]
            duration = audio_info(base64.b64decode(data))[2]
            durations.append(duration)
            return answer("相邻原文确认预算", "length" if duration > 50 else "stop")
        return await good_call(body)

    result = await ReviewPipeline(store, job, call, "test").run()
    assert max(durations) <= 90 and 90 in durations and any(x < 50 for x in durations)
    ranges = result["sourceRanges"]
    assert ranges[0]["start_seconds"] == 0 and ranges[-1]["end_seconds"] == 181
    assert all(
        a["end_seconds"] - b["start_seconds"] == 2
        for a, b in itertools.pairwise(ranges)
    )
    assert result["rawTranscript"].count("相邻原文确认预算") == len(ranges)
    assert result["correctedText"] == "相邻原文确认预算"
    assert all(x["validated"] for x in result["evidence"])
    before = len(durations)
    assert await ReviewPipeline(store, job, call, "test").run() == result
    assert len(durations) == before
    assert quota.remaining("user") == 4
    quota.close()


async def test_image_batch_truncation_split_and_asset_citations(tmp_path):
    quota, store = store_at(tmp_path)
    source = spec(
        sources=[
            {"source_id": "one.jpg", "kind": "image", "region": [0, 100, 40, 256]},
            {"source_id": "two.jpg", "kind": "image"},
        ],
        roles=["复盘"],
    )
    store.create("user", source)
    buffer = io.BytesIO()
    Image.new("RGB", (40, 256)).save(buffer, "JPEG")
    raw = buffer.getvalue()
    assert validate_media("image", raw) == 0
    for sid in ("one.jpg", "two.jpg"):
        store.upload("user", "review", sid, raw, 0)
    store.start("user", "review")

    async def call(body):
        content = body["messages"][-1]["content"]
        if isinstance(content, list):
            data = base64.b64decode(content[0]["image_url"]["url"].split(",")[1])
            with Image.open(io.BytesIO(data)) as image:
                return answer("图中原文", "length" if image.height > 128 else "stop")
        return await good_call(body)

    result = await ReviewPipeline(store, store.claim(), call, "test").run()
    assert {x["asset"] for x in result["evidence"]} == {"one.jpg", "two.jpg"}
    assert result["evidence"][0]["region"] == [0, 100, 40, 128]
    assert result["evidence"][1]["region"] == [0, 228, 40, 128]
    quota.close()


async def test_upstream_failure_and_invalid_citations_do_not_charge(tmp_path):
    key, public, auth = auth_key()
    valid = False

    async def invalid(body):
        if valid:
            return await good_call(body)
        return answer(json.dumps(analysis(["nonexistent-evidence"])))

    app = create_app(tmp_path / "q.db", "", public_key=public, upstream_call=invalid)
    async with TestClient(TestServer(app)) as client:
        await client.post("/v2/reviews", headers=auth, json=spec(roles=["复盘"]))
        await client.put(
            "/v2/reviews/review/uploads/text", headers=auth, data="原文".encode()
        )
        await client.post("/v2/reviews/review/start", headers=auth)
        value = await wait_final(client, auth)
        assert value["state"] == "failed" and value["remaining"] == 5
        assert value["result"] is None and value["percent"] < 100
        assert value["error"] == "review_unavailable"
        assert "nonexistent" not in json.dumps(value)
        valid = True
        assert (
            await client.post("/v2/reviews/review/resume", headers=auth)
        ).status == 200
        value = await wait_final(client, auth)
        assert value["state"] == "succeeded" and value["remaining"] == 4


async def test_bearer_introspection_and_lease_account_agreement(tmp_path):
    key, public, auth = auth_key()

    async def inspect(token):
        if token == "unavailable":
            raise OSError("secret provider details")
        return {"active": token == "valid", "user_id": "user"}

    app = create_app(
        tmp_path / "q.db",
        "",
        public_key=public,
        upstream_call=good_call,
        introspection_call=inspect,
        worker_enabled=False,
    )
    async with TestClient(TestServer(app)) as client:
        bearer = {"Authorization": "Bearer valid"}
        assert (
            await client.post("/v2/reviews", headers=bearer, json=spec())
        ).status == 201
        assert (await client.get("/v2/reviews/review", headers=auth)).status == 200
        assert (
            await client.get(
                "/v2/reviews/review",
                headers=bearer | {"X-Xfriend-Lease": identity(key, user="other")},
            )
        ).status == 401
        assert (
            await client.get(
                "/v2/reviews/review", headers={"Authorization": "Bearer revoked"}
            )
        ).status == 401
        response = await client.get(
            "/v2/reviews/review", headers={"Authorization": "Bearer unavailable"}
        )
        assert response.status == 503 and "secret" not in await response.text()


async def test_v1_compat_tts_and_truncated_response_refund(tmp_path):
    key, public, auth = auth_key()

    async def upstream(body):
        if body["model"].endswith("tts"):
            return {"choices": [{"message": {"audio": {"data": "wav-data"}}}]}
        return answer("incomplete", "length")

    async with TestClient(
        TestServer(
            create_app(tmp_path / "q.db", "", public_key=public, upstream_call=upstream)
        )
    ) as client:
        response = await client.post(
            "/v1/tasks",
            headers=auth,
            json={"request_id": "tts", "operation": "speak", "text": "你好"},
        )
        assert response.status == 200 and (await response.json())["audio"] == "wav-data"
        assert (await client.post("/v1/tasks", headers=auth, json=task())).status == 502
        assert (await (await client.get("/v1/quota", headers=auth)).json())[
            "remaining"
        ] == 4


@pytest.mark.parametrize(
    "mutation",
    [
        {"sources": []},
        {"request_id": "../x"},
        {"request_id": ".."},
        {"roles": []},
        {"sources": [{"source_id": "a", "kind": "audio", "duration": 1}]},
        {"sources": [{"source_id": "a", "kind": "url"}]},
        {"sources": [{"source_id": "a", "kind": "image", "region": [-1, 0, 1, 1]}]},
    ],
)
def test_invalid_specs(mutation):
    with pytest.raises(web.HTTPBadRequest):
        validate_spec(spec() | mutation)


def test_boundary_correction_and_analysis_validation():
    assert (
        merge_overlap("前文我们确认预算", "确认预算然后签约")
        == "前文我们确认预算然后签约"
    )
    assert merge_overlap("前文", "后文") == "前文\n后文"
    with pytest.raises(ValueError):
        validate_analysis(analysis(["invented"]), {"e1"})


async def test_streamed_summary_persisted_before_final_then_cancel_refunds(tmp_path):
    key, public, auth = auth_key()
    preview_sent = asyncio.Event()
    unblock = asyncio.Event()

    async def upstream(body):
        return await good_call(body)

    async def stream(body, delta):
        await delta('{"summary":"尚未完成的报告')
        preview_sent.set()
        await unblock.wait()
        return await good_call(body)

    upstream.stream = stream
    app = create_app(tmp_path / "q.db", "", public_key=public, upstream_call=upstream)
    async with TestClient(TestServer(app)) as client:
        await client.post("/v2/reviews", headers=auth, json=spec(roles=["复盘"]))
        await client.put(
            "/v2/reviews/review/uploads/text", headers=auth, data=b"source"
        )
        await client.post("/v2/reviews/review/start", headers=auth)
        await asyncio.wait_for(preview_sent.wait(), 3)
        value = await (await client.get("/v2/reviews/review", headers=auth)).json()
        assert value["state"] == "running" and value["percent"] < 100
        assert value["partial_report"] == "尚未完成的报告" and value["result"] is None
        events = app[STORE].events("user", "review", 0)
        assert events[-1]["partial_report"] == value["partial_report"]
        await client.post("/v2/reviews/review/cancel", headers=auth)
        unblock.set()
        await asyncio.sleep(0.05)
        value = await (await client.get("/v2/reviews/review", headers=auth)).json()
        assert value["state"] == "cancelled" and value["remaining"] == 5
        assert value["result"] is None


@pytest.mark.parametrize(
    "prefix,expected",
    [
        ('{"summary":"hello', "hello"),
        ('{"summary":"hello\\nworld', "hello\nworld"),
        ('{"summary":"hello\\u4e', "hello"),
        ('{"summary":"hello","findings":[', "hello"),
    ],
)
def test_stream_summary_incomplete_json(prefix, expected):
    assert streamed_summary(prefix) == expected


async def test_interrupted_worker_shutdown_restarts_from_checkpoint(tmp_path):
    key, public, auth = auth_key()
    entered = asyncio.Event()
    calls = []

    async def upstream(body):
        calls.append(body)
        if len(calls) == 2:
            entered.set()
            await asyncio.Event().wait()
        return await good_call(body)

    app = create_app(tmp_path / "q.db", "", public_key=public, upstream_call=upstream)
    async with TestClient(TestServer(app)) as client:
        await client.post("/v2/reviews", headers=auth, json=spec())
        await client.put(
            "/v2/reviews/review/uploads/text", headers=auth, data=b"original"
        )
        await client.post("/v2/reviews/review/start", headers=auth)
        await asyncio.wait_for(entered.wait(), 3)
    count = 0

    async def resumed(body):
        nonlocal count
        count += 1
        return await good_call(body)

    app = create_app(tmp_path / "q.db", "", public_key=public, upstream_call=resumed)
    async with TestClient(TestServer(app)) as client:
        value = await wait_final(client, auth)
        assert value["state"] == "succeeded" and value["remaining"] == 4
        assert count == 2  # second expert and synthesis; first expert was checkpointed


def test_terminal_media_cleanup_keeps_final_ledger_and_evidence(tmp_path):
    quota, store = store_at(tmp_path)
    ready(store)
    job = store.claim()
    store.finish(*job[:3], {"evidence": [{"quote": "saved"}]})
    store.cleanup(time.time() + 86401)
    assert list(store.media.iterdir()) == []
    assert store.get("user", "review")["result"]["evidence"][0]["quote"] == "saved"
    assert store.start("user", "review", True)["remaining"] == 4
    quota.close()


async def test_real_upstream_sse_transport_persists_and_validates_final(
    tmp_path, monkeypatch
):
    import gateway

    key, public, auth = auth_key()
    streamed = []

    async def completion(request):
        body = await request.json()
        assert body["stream"] is True
        payload = json.dumps(analysis(["e1"]), ensure_ascii=False)
        response = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
        await response.prepare(request)
        for start in range(0, len(payload), 8):
            delta = payload[start : start + 8]
            streamed.append(delta)
            event = {"choices": [{"delta": {"content": delta}, "finish_reason": None}]}
            wire = ("data: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode()
            # Fragment the UTF-8/SSE transport, not just the model content.
            for offset in range(0, len(wire), 7):
                await response.write(wire[offset : offset + 7])
        await response.write(
            b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'
        )
        await response.write(b"data: [DONE]\n\n")
        return response

    provider = web.Application()
    provider.router.add_post("/chat", completion)
    async with TestServer(provider) as server:
        monkeypatch.setattr(gateway, "UPSTREAM", str(server.make_url("/chat")))
        app = create_app(tmp_path / "q.db", "synthetic-test-key", public_key=public)
        async with TestClient(TestServer(app)) as client:
            await client.post("/v2/reviews", headers=auth, json=spec(roles=["复盘"]))
            await client.put(
                "/v2/reviews/review/uploads/text", headers=auth, data=b"source"
            )
            await client.post("/v2/reviews/review/start", headers=auth)
            value = await wait_final(client, auth)
            assert value["state"] == "succeeded" and value["remaining"] == 4
            assert value["partial_report"] == value["result"]["reportText"]
            assert len(streamed) > 1
            previews = app[STORE].events("user", "review", 0)
            assert any(
                x["partial_report"] and x["state"] == "running" for x in previews
            )


async def test_minimum_asr_truncation_is_never_committed_as_full_source(tmp_path):
    quota, store = store_at(tmp_path)
    source = spec(sources=[{"source_id": "audio", "kind": "audio"}], roles=["复盘"])
    store.create("user", source)
    raw = wav(3)
    store.upload("user", "review", "audio", raw, 3)
    store.start("user", "review")
    job = store.claim()

    async def incomplete(body):
        assert body["max_tokens"] == 16384
        return answer("truncated original", "length")

    with pytest.raises(ValueError, match="asr_incomplete"):
        await ReviewPipeline(store, job, incomplete, "test").run()
    assert store.get("user", "review")["result"] is None
    assert store.checkpoint(*job[:3], "source/audio") is None
    store.cancel("user", "review")
    assert quota.remaining("user") == 5
    quota.close()


async def test_full_hour_upload_exceeds_legacy_30mb_limit_and_is_still_accepted(
    tmp_path,
):
    _key, public, auth = auth_key()
    app = create_app(
        tmp_path / "q.db",
        "",
        public_key=public,
        upstream_call=good_call,
        worker_enabled=False,
    )
    async with TestClient(TestServer(app)) as client:
        body = spec(sources=[{"source_id": "whole.wav", "kind": "audio"}])
        assert (await client.post("/v2/reviews", headers=auth, json=body)).status == 201
        raw = wav(3600, rate=9000)
        assert len(raw) > 30 * 1024 * 1024
        response = await client.put(
            "/v2/reviews/review/uploads/whole.wav", headers=auth, data=raw
        )
        assert response.status == 200, await response.text()
        response = await client.post("/v2/reviews/review/start", headers=auth)
        value = await response.json()
        assert value["state"] == "queued" and value["remaining"] == 4
        assert (
            await (await client.post("/v2/reviews/review/cancel", headers=auth)).json()
        )["remaining"] == 5
