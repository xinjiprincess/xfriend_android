"""Package verified default APKs, evidence and reproducible source (no local secrets)."""
import hashlib
import os
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
SDK = Path("C:/Users/zhang/AppData/Local/Android/Sdk")
report = {"date": "2026-10-13", "version": "0.7.0", "build": "passed", "products": {}}
assert "BUILD SUCCESSFUL" in (ROOT / "verify-0.7-final.log").read_text(encoding="utf-8-sig")
assert "BUILD SUCCESSFUL" in (ROOT / "verify-0.7-package.log").read_text(encoding="utf-8-sig")
for product, brand in [("xfriend", "Xfriend"), ("xsalers", "Xsalers")]:
    suites = [ET.parse(p).getroot() for p in (ROOT / f"app/build/test-results/test{brand}DebugUnitTest").glob("TEST-*.xml")]
    tests = sum(int(s.attrib["tests"]) for s in suites)
    failures = sum(int(s.attrib["failures"]) + int(s.attrib["errors"]) for s in suites)
    assert tests >= 142 and failures == 0
    lint = ET.parse(ROOT / f"app/build/reports/lint-results-{product}Debug.xml").getroot()
    errors = sum(i.attrib.get("severity") in ("Error", "Fatal") for i in lint)
    assert errors == 0
    apk = DIST / f"{brand}-Android-0.7.0-debug.apk"
    import shutil
    shutil.copy2(ROOT / f"app/build/outputs/apk/{product}/debug/app-{product}-debug.apk", apk)
    manifest = subprocess.check_output([str(SDK / "build-tools/34.0.0/aapt.exe"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], encoding="utf-8")
    assert "SalesFixtureActivity" not in manifest and f"com.singu.{product}.mobile.qa" not in manifest
    with zipfile.ZipFile(apk) as z:
        dex = b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        assert b"SalesFixtureActivity" not in dex
        # Shared production credentials must never ship in either client.
        assert not re.search(rb"sk-[a-z0-9]{40,}", dex)
    signing_env = dict(os.environ, JAVA_HOME="C:/Program Files/Java/jdk-20")
    def certificate(path):
        output = subprocess.check_output([str(SDK / "build-tools/34.0.0/apksigner.bat"), "verify", "--print-certs", str(path)], encoding="utf-8", env=signing_env)
        return re.search(r"certificate SHA-256 digest: ([a-f0-9]+)", output).group(1)
    certificate_digest = certificate(apk)
    assert certificate_digest == certificate(DIST / f"{brand}-Android-0.6.0-debug.apk")
    badging = subprocess.check_output([str(SDK / "build-tools/34.0.0/aapt.exe"), "dump", "badging", str(apk)], encoding="utf-8")
    assert "versionCode='7'" in badging and "versionName='0.7.0'" in badging
    report["products"][brand] = {
        "package": f"com.singu.{product}.mobile", "certificate_sha256": certificate_digest, "upgrade_signature_matches": True, "tests": tests, "failures": failures,
        "lint_errors": errors, "lint_warnings": sum(i.attrib.get("severity") == "Warning" for i in lint),
        "qa_fixture_excluded": True, "shared_api_key_pattern_absent": True,
        "apk_bytes": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }
report["limits"] = ["113试用服务器SSH身份未核验，新v2试用网关未部署", "真实模型长录音端到端未验收", "手机—电脑实际账号媒体端到端未验收", "真机节能与权限差异未测试", "订阅支付为预留入口", "桌面全量make all未执行；执行了本次功能相关检查与前端构建"]
report["cloud_deployment"] = {"account_server": "43.136.175.19", "ready": 200, "unauthenticated_sync": 401, "backup_verified": True, "signing_key_preserved": True, "production_user_write_test": False}
ui_evidence = ROOT / "docs/qa-ui-evidence-0.7.json"
ui_result = json.loads(ui_evidence.read_text(encoding="utf-8")) if ui_evidence.exists() else {}
shots = [f"suite-0.7-{name}.png" for name in ui_result.get("screens", [])] if ui_result.get("passed") else []
report["ui_evidence"] = ui_result
report["ui_screenshots"] = shots
report["ui_checks"] = {"physical_device": "未测试", "visual_acceptance": "以本次 QA 证据记录为准"}
evidence = ROOT / "docs/qa-audio-evidence-0.7.json"
if evidence.exists(): report["android_audio_import"] = json.loads(evidence.read_text(encoding="utf-8-sig"))
(ROOT / "docs/verification-0.7.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
page = ROOT / "docs/DELIVERY-0.7.html"
html = page.read_text(encoding="utf-8")
counts = "；".join(f"{brand} {v['tests']} 项单元测试、Lint {v['lint_errors']} 错误/{v['lint_warnings']} 警告" for brand, v in report["products"].items())
html = re.sub(r'<div id="verification">.*?</div>', '<div id="verification">构建通过。'+counts+'。APK 排除 QA 入口，未发现共享模型密钥。详见 <a href="verification-0.7.json">验证明细</a>。</div>', html, flags=re.S)

if shots:
    gallery = ''.join(f'<figure style="margin:12px 0;max-width:350px"><img style="width:100%;border-radius:20px" src="{n}" alt="{n}"><figcaption>{n} · 隔离 QA 示例资料</figcaption></figure>' for n in shots)
    html = re.sub(r'<section id="screens">.*?</section>', f'<section id="screens"><h2>Android 模拟器界面验收</h2><p>以下为实际运行截图，QA 包使用明确标记的示例资料。</p><div class="shots">{gallery}</div></section>', html, flags=re.S)
else:
    html = re.sub(r'<section id="screens">.*?</section>', '<section id="screens"><h2>界面验收状态</h2><p>模拟器数据迁移与后台音频导入通过；完整页面视觉验收未通过，详见 qa-ui-evidence-0.7.json。没有用效果图替代真实截图。</p></section>', html, flags=re.S)

page.write_text(html, encoding="utf-8")
excluded = {".git", ".gradle", ".qa-avd", ".artifacts", ".venv", ".ruff_cache", ".pytest_cache", "__pycache__", "build", "dist", ".idea"}
allowed = {".kt", ".kts", ".xml", ".png", ".json", ".html", ".md", ".txt", ".py", ".toml", ".lock", ".ps1", ".sh", ".bat", ".properties", ".jar", ".cjs", ".conf", ".service"}
with zipfile.ZipFile(DIST / "Xfriend-Xsalers-0.7.0-source.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for p in ROOT.rglob("*"):
        rel = p.relative_to(ROOT)
        if not p.is_file() or any(c in excluded for c in rel.parts) or p.name == "local.properties":
            continue
        if p.suffix in allowed or p.suffix in (".xfpack", ".xpack") or p.name in ("gradlew", ".gitignore"):
            z.write(p, rel)
checksums = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(DIST.iterdir()) if p.suffix in (".apk", ".zip") and "0.7.0" in p.name)
(DIST / "SHA256SUMS-0.7.0.txt").write_text(checksums, encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
