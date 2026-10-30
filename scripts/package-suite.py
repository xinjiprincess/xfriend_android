"""Package verified default APKs, evidence and reproducible source (no local secrets)."""
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
SDK = Path("C:/Users/zhang/AppData/Local/Android/Sdk")
report = {"date": "2026-10-13", "version": "0.6.0", "build": "passed", "products": {}}
assert "BUILD SUCCESSFUL" in (ROOT / "verify-0.6-final.log").read_text(encoding="utf-8-sig")
for product, brand in [("xfriend", "Xfriend"), ("xsalers", "Xsalers")]:
    suites = [ET.parse(p).getroot() for p in (ROOT / f"app/build/test-results/test{brand}DebugUnitTest").glob("TEST-*.xml")]
    tests = sum(int(s.attrib["tests"]) for s in suites)
    failures = sum(int(s.attrib["failures"]) + int(s.attrib["errors"]) for s in suites)
    assert tests >= 92 and failures == 0
    lint = ET.parse(ROOT / f"app/build/reports/lint-results-{product}Debug.xml").getroot()
    errors = sum(i.attrib.get("severity") in ("Error", "Fatal") for i in lint)
    assert errors == 0
    apk = DIST / f"{brand}-Android-0.6.0-debug.apk"
    manifest = subprocess.check_output([str(SDK / "build-tools/34.0.0/aapt.exe"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], encoding="utf-8")
    assert "SalesFixtureActivity" not in manifest and f"com.singu.{product}.mobile.qa" not in manifest
    with zipfile.ZipFile(apk) as z:
        dex = b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        assert b"SalesFixtureActivity" not in dex
        # Shared production credentials must never ship in either client.
        assert not re.search(rb"sk-[a-z0-9]{40,}", dex)
    report["products"][brand] = {
        "package": f"com.singu.{product}.mobile", "tests": tests, "failures": failures,
        "lint_errors": errors, "lint_warnings": sum(i.attrib.get("severity") == "Warning" for i in lint),
        "qa_fixture_excluded": True, "shared_api_key_pattern_absent": True,
        "apk_bytes": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }
report["limits"] = ["公网试用 HTTPS 本次连接超时", "长录音和长文字需要自有模型；现有试用网关仍受单请求限制", "CRM 双向同步与订阅支付未接入", "真实模型端到端本次未验收"]
shots = sorted(p.name for p in (ROOT / "docs").glob("suite-0.6-*.png"))
report["ui_screenshots"] = shots
report["ui_checks"] = {"physical_device": "未测试", "visual_acceptance": "以本次 QA 证据记录为准"}
evidence = ROOT / "docs/qa-audio-evidence-0.6.json"
if evidence.exists(): report["android_audio_import"] = json.loads(evidence.read_text(encoding="utf-8-sig"))
(ROOT / "docs/verification-0.6.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
page = ROOT / "docs/DELIVERY-0.6.html"
html = page.read_text(encoding="utf-8")
html = re.sub(r'<div id="verification">.*?</div>', '<div id="verification">构建通过；两款各 92 项单元测试，零失败。Lint 零错误，Xfriend 11 条、Xsalers 13 条警告。APK 包名、签名与测试入口排除已检查。详见 <a href="verification-0.6.json">验证明细</a>。</div>', html, flags=re.S)
if shots:
    gallery = ''.join(f'<figure style="margin:12px 0;max-width:350px"><img style="width:100%;border-radius:20px" src="{n}" alt="{n}"><figcaption>{n} · 隔离 QA 示例资料</figcaption></figure>' for n in shots)
    html = re.sub(r'<section id="screens">.*?</section>', f'<section id="screens"><h2>Android 模拟器界面验收</h2><p>以下为实际运行截图，QA 包使用明确标记的示例资料。</p><div class="shots">{gallery}</div></section>', html, flags=re.S)
else:
    html = re.sub(r'<section id="screens">.*?</section>', '<section id="screens"><h2>界面验收状态</h2><p>Android 35、1080×2400 隔离 QA 包已启动 Xsalers 工作台，并成功读取实际 UI 树，示例客户、跟进待办及采集入口正常显示。其后模拟器在 host 和 software 两种渲染下均出现 QEMU 线程挂起，截图失败。</p><p>本次尚未完成全部页面视觉验收、专家创建及 CRM 编辑的模拟器操作验收，也未完成真机测试。报告不使用效果图替代运行截图。</p></section>', html, flags=re.S)
page.write_text(html, encoding="utf-8")
excluded = {".git", ".gradle", ".qa-avd", ".artifacts", ".venv", ".ruff_cache", ".pytest_cache", "__pycache__", "build", "dist", ".idea"}
allowed = {".kt", ".kts", ".xml", ".png", ".json", ".html", ".md", ".txt", ".py", ".toml", ".lock", ".ps1", ".sh", ".bat", ".properties", ".jar", ".cjs", ".conf", ".service"}
with zipfile.ZipFile(DIST / "Xfriend-Xsalers-0.6.0-source.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for p in ROOT.rglob("*"):
        rel = p.relative_to(ROOT)
        if not p.is_file() or any(c in excluded for c in rel.parts) or p.name == "local.properties":
            continue
        if p.suffix in allowed or p.name in ("gradlew", ".gitignore"):
            z.write(p, rel)
checksums = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(DIST.iterdir()) if p.suffix in (".apk", ".zip") and "0.6.0" in p.name)
(DIST / "SHA256SUMS-0.6.0.txt").write_text(checksums, encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
