import aiohttp
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from bridge import create_app
from exporter import legacy_export


async def test_legacy_exports_prompt_skills_and_opt_in_keys():
    async def fetch(path):
        if path == "/api/experts":
            return [{"id": "one", "label": {"zh": "专家"}}]
        if path == "/api/providers":
            return [
                {
                    "id": "p",
                    "name": "模型",
                    "base_url": "https://example.com/v1",
                    "api_key": "fake-provider-secret",
                    "models": [{"id": "chat"}],
                }
            ]
        return {
            "prompt_files": ["SOUL.md"],
            "file_contents": [
                {"name": "SOUL.md", "content": "专业提示"},
                {"name": "skills/sales/SKILL.md", "content": "工作方法"},
            ],
        }

    without = await legacy_export(fetch)
    assert without["experts"][0]["prompt"] == "专业提示"
    assert without["experts"][0]["skills"] == [without["skills"][0]["id"]]
    assert without["providers"][0]["api_key"] == ""
    assert (await legacy_export(fetch, True))["providers"][0][
        "api_key"
    ] == "fake-provider-secret"
    assert without["warnings"]


async def test_export_needs_pair_and_exact_origin_and_strips_keys():
    async def desktop(request):
        if request.path == "/api/desktop/auth/status":
            return web.json_response(
                {
                    "enabled": True,
                    "state": "authenticated",
                    "user": {"user_id": "owner"},
                    "space_id": "personal",
                }
            )
        return web.json_response(
            {
                "format": "xfriend-mobile-export",
                "version": 1,
                "experts": [],
                "providers": [{"api_key": "secret"}],
                "cloud_token": None,
            }
        )

    source = web.Application()
    source.router.add_route("*", "/{tail:.*}", desktop)
    async with TestServer(source) as original:
        async with TestClient(
            TestServer(create_app(str(original.make_url("/")), "pair-code")),
            cookie_jar=aiohttp.CookieJar(unsafe=True),
        ) as client:
            assert (await client.get("/mobile/export")).status == 401
            await client.post(
                "/mobile/pair", data={"code": "pair-code"}, allow_redirects=False
            )
            assert (await client.get("/mobile/export")).status == 403
            origin = str(client.make_url("/")).rstrip("/")
            response = await client.get("/mobile/export", headers={"Origin": origin})
            assert response.status == 200
            assert "api_key" not in (await response.json())["providers"][0]
            response = await client.get(
                "/mobile/export?include_secrets=1", headers={"Origin": origin}
            )
            assert (await response.json())["providers"][0]["api_key"] == "secret"
            assert response.headers["Cache-Control"] == "no-store"
