import base64
import concurrent.futures
import json
import time

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from gateway import Quota, create_app, validate_task, verify_lease


def encoded(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def identity(key, user="user", space="personal", expires=None):
    now = int(time.time())
    payload = json.dumps(
        {
            "schema_version": 2,
            "product": "singu-xfriend",
            "user_id": user,
            "space_id": space,
            "device_id": "device",
            "not_before": now - 10,
            "issued_at": now - 10,
            "offline_until": expires or now + 100,
            "expires_at": now + 1000,
        }
    ).encode()
    return encoded(
        json.dumps(
            {"payload": encoded(payload), "signature": encoded(key.sign(payload))}
        ).encode()
    )


def task(rid="first"):
    return {
        "request_id": rid,
        "operation": "analyze",
        "kind": "text",
        "text": "客户需要先确认预算",
        "roles": ["客户需求分析师"],
        "prompt": "引用事实",
    }


def test_signature_and_expiry():
    key = Ed25519PrivateKey.generate()
    public = encoded(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
    assert verify_lease(identity(key), public) == "user"
    with pytest.raises(web.HTTPUnauthorized):
        verify_lease(identity(Ed25519PrivateKey.generate()), public)
    with pytest.raises(web.HTTPUnauthorized):
        verify_lease(identity(key, expires=int(time.time()) - 1), public)


def test_five_concurrent_reservations_and_persistence(tmp_path):
    q = Quota(tmp_path / "quota.db")

    def reserve(n):
        try:
            q.reserve("user", str(n), str(n))
            q.complete("user", str(n), {"report": "report"})
            return True
        except web.HTTPPaymentRequired:
            return False

    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        assert sum(pool.map(reserve, range(20))) == 5
    assert q.remaining("user") == 0
    q.close()
    reopened = Quota(tmp_path / "quota.db")
    assert reopened.remaining("user") == 0
    assert reopened.remaining("another") == 5
    reopened.close()


def test_retry_and_payload_conflict(tmp_path):
    q = Quota(tmp_path / "q.db")
    q.reserve("u", "r", "hash")
    with pytest.raises(web.HTTPConflict):
        q.reserve("u", "r", "hash")
    q.complete("u", "r", {"report": "秘密客户内容"})
    assert q.reserve("u", "r", "hash")["report"] == "秘密客户内容"
    with pytest.raises(web.HTTPConflict):
        q.reserve("u", "r", "other")
    assert "秘密客户内容".encode() not in (tmp_path / "q.db").read_bytes()
    q.close()


def test_refund_and_crash_recovery(tmp_path):
    path = tmp_path / "q.db"
    q = Quota(path)
    q.reserve("u", "1", "h")
    q.refund("u", "1")
    assert q.remaining("u") == 5
    q.reserve("u", "2", "h")
    q.close()
    q = Quota(path)
    assert q.remaining("u") == 5
    q.close()


@pytest.mark.parametrize(
    "change",
    [
        {"model": "attacker-model"},
        {"kind": "url", "media": "https://internal/"},
        {"roles": []},
        {"request_id": "../x"},
        {"text": "x" * 12001},
        {"kind": "audio", "media": "AAAA"},
    ],
)
def test_rejects_unbounded_or_arbitrary_upstream(change):
    with pytest.raises(web.HTTPBadRequest):
        validate_task(task() | change)


async def test_http_quota_auth_refund_and_hides_model(tmp_path):
    key = Ed25519PrivateKey.generate()
    public = encoded(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
    called = []

    async def upstream(body):
        called.append(body)
        if body["messages"][-1]["content"] == "fail":
            raise ValueError("sk-test-secret model-internal")
        return {
            "model": "hidden",
            "choices": [{"message": {"content": "报告：预算待确认"}}],
        }

    app = create_app(
        tmp_path / "q.db", "fake-secret", public_key=public, upstream_call=upstream
    )
    async with TestClient(TestServer(app)) as client:
        assert (await client.get("/v1/quota")).status == 401
        auth = {"X-Xfriend-Lease": identity(key)}
        assert (await (await client.get("/v1/quota", headers=auth)).json())[
            "remaining"
        ] == 5
        for n in range(5):
            response = await client.post("/v1/tasks", headers=auth, json=task(str(n)))
            assert response.status == 200
            result = await response.json()
            assert result["remaining"] == 4 - n
            assert "model" not in result and "fake-secret" not in json.dumps(result)
        assert (
            await client.post("/v1/tasks", headers=auth, json=task("sixth"))
        ).status == 402
        assert (
            await client.post("/v1/tasks", headers=auth, json=task("0"))
        ).status == 200
        auth2 = {"X-Xfriend-Lease": identity(key, space="other-space")}
        assert (await (await client.get("/v1/quota", headers=auth2)).json())[
            "remaining"
        ] == 0
        assert len(called) == 5


async def test_failed_upstream_refunds_without_leaking_error(tmp_path):
    key = Ed25519PrivateKey.generate()
    public = encoded(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    async def upstream(_):
        raise ValueError("sk-sensitive-model")

    async with TestClient(
        TestServer(
            create_app(tmp_path / "q.db", "", public_key=public, upstream_call=upstream)
        )
    ) as client:
        headers = {"X-Xfriend-Lease": identity(key)}
        response = await client.post("/v1/tasks", headers=headers, json=task())
        assert response.status == 502 and "sk-sensitive" not in await response.text()
        assert (await (await client.get("/v1/quota", headers=headers)).json())[
            "remaining"
        ] == 5


async def test_team_pipeline_one_credit(tmp_path):
    key = Ed25519PrivateKey.generate()
    public = encoded(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))
    calls = []

    async def upstream(body):
        calls.append(body)
        return {"choices": [{"message": {"content": "已完成本轮"}}]}

    async with TestClient(
        TestServer(
            create_app(tmp_path / "q.db", "", public_key=public, upstream_call=upstream)
        )
    ) as client:
        response = await client.post(
            "/v1/tasks",
            headers={"X-Xfriend-Lease": identity(key)},
            json=task() | {"roles": ["需求", "风险"]},
        )
        assert response.status == 200 and (await response.json())["remaining"] == 4
        assert len(calls) == 3
