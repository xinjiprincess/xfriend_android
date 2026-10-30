import asyncio
import json
from pathlib import Path

from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[1]


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(channel="chrome", headless=True)
        page = await browser.new_page(viewport={"width": 1200, "height": 900})
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        await page.goto((ROOT / "docs/DESKTOP-INTEGRATION-0.4.html").as_uri())
        assert "0.4" in await page.title()
        assert await page.locator("section").count() == 7
        assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        assert await page.evaluate("[...document.images].every(i => i.complete && i.naturalWidth > 0)")
        await page.screenshot(path=str(ROOT / "docs/report-preview-0.4.png"))
        await page.set_viewport_size({"width": 400, "height": 860})
        assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        await page.screenshot(path=str(ROOT / "docs/report-mobile-preview-0.4.png"))
        await page.goto((ROOT / "docs/DEPLOYMENT-0.4.html").as_uri())
        assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        assert not errors, errors
        await browser.close()
    schema = json.loads((ROOT / "docs/mobile-export-v1.schema.json").read_text(encoding="utf-8"))
    sample = json.loads((ROOT / "docs/mobile-export-v1.example.json").read_text(encoding="utf-8"))
    from jsonschema import Draft202012Validator

    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(sample)
    print("PASS report: seven sections, images, 1200/400px widths, deployment page, export JSON schema/sample")


asyncio.run(main())
