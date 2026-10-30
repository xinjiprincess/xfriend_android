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
report = {"date": "2026-10-14", "version": "0.8.0", "build": "passed", "products": {}}
assert "BUILD SUCCESSFUL" in (ROOT / "verify-0.8-final.log").read_text(encoding="utf-8-sig")

for product, brand in [("xfriend", "Xfriend"), ("xsalers", "Xsalers")]:
    suites = [ET.parse(p).getroot() for p in (ROOT / f"app/build/test-results/test{brand}DebugUnitTest").glob("TEST-*.xml")]
    tests = sum(int(s.attrib["tests"]) for s in suites)
    failures = sum(int(s.attrib["failures"]) + int(s.attrib["errors"]) for s in suites)
    assert tests >= 259 and failures == 0
    lint = ET.parse(ROOT / f"app/build/reports/lint-results-{product}Debug.xml").getroot()
    errors = sum(i.attrib.get("severity") in ("Error", "Fatal") for i in lint)
    assert errors == 0
    apk = DIST / f"{brand}-Android-0.8.0-debug.apk"
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
    assert certificate_digest == certificate(DIST / f"{brand}-Android-0.7.0-debug.apk")
    badging = subprocess.check_output([str(SDK / "build-tools/34.0.0/aapt.exe"), "dump", "badging", str(apk)], encoding="utf-8")
    assert "versionCode='8'" in badging and "versionName='0.8.0'" in badging
    report["products"][brand] = {
        "package": f"com.singu.{product}.mobile", "certificate_sha256": certificate_digest, "upgrade_signature_matches": True, "tests": tests, "failures": failures,
        "lint_errors": errors, "lint_warnings": sum(i.attrib.get("severity") == "Warning" for i in lint),
        "qa_fixture_excluded": True, "shared_api_key_pattern_absent": True,
        "apk_bytes": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }
report["limits"] = ["未连接用户原手机，原始报错尚未在真机复现", "只对 MiMo 做本轮真实模型验证，其他供应商待验收", "模拟器后台验证不代表所有厂商锁屏与节能策略", "云端 Agent 引擎尚未部署；113 主机身份未核验", "手机仅执行原生工具；复杂桌面专家使用电脑执行", "桌面全量 make all 尚未执行，以相关检查记录为准"]
for name in ["qa-xfriend-runtime-0.8", "qa-mimo-0.8"]:
    p = ROOT / "docs" / (name + ".json")
    if p.exists(): report[name] = json.loads(p.read_text(encoding="utf-8-sig"))
ui_result = report.get("qa-xfriend-runtime-0.8", {})
assert ui_result.get("passed"), "Android live-model/background/tool QA must pass"
shots = [f"xfriend-0.8-{name}.png" for name in ui_result.get("screens", [])]
report["ui_screenshots"] = shots
(ROOT / "docs/verification-0.8.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
page = ROOT / "docs/DELIVERY-0.8.html"
html = page.read_text(encoding="utf-8")
counts = "；".join(f"{brand} {v['tests']} 项单元测试、Lint {v['lint_errors']} 错误/{v['lint_warnings']} 警告" for brand, v in report["products"].items())
html = re.sub(r'<div id="verification">.*?</div>', '<div id="verification">构建通过。'+counts+'。APK 排除 QA 入口，未发现共享模型密钥。详见 <a href="verification-0.8.json">验证明细</a>。</div>', html, flags=re.S)

if shots:
    gallery = ''.join(f'<figure style="margin:12px 0;max-width:350px"><img style="width:100%;border-radius:20px" src="{n}" alt="{n}"><figcaption>{n} · 隔离 QA 示例资料</figcaption></figure>' for n in shots)
    html = re.sub(r'<section id="screens">.*?</section>', f'<section id="screens"><h2>Android 模拟器界面验收</h2><p>以下为实际运行截图，QA 包使用明确标记的示例资料。</p><div class="shots">{gallery}</div></section>', html, flags=re.S)
else:
    html = re.sub(r'<section id="screens">.*?</section>', '<section id="screens"><h2>界面验收状态</h2><p>Android 真实模型验收尚未通过，详见 qa-ui-evidence-0.8.json。没有用效果图替代真实截图。</p></section>', html, flags=re.S)

page.write_text(html, encoding="utf-8")
excluded = {".git", ".gradle", ".qa-avd", ".artifacts", ".venv", ".ruff_cache", ".pytest_cache", "__pycache__", "build", "dist", ".idea", ".portable-core-check", "node_modules"}
allowed = {".kt", ".kts", ".xml", ".png", ".json", ".html", ".md", ".txt", ".py", ".toml", ".lock", ".ps1", ".sh", ".bat", ".properties", ".jar", ".cjs", ".conf", ".service"}
with zipfile.ZipFile(DIST / "Xfriend-Xsalers-0.8.0-source.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for p in ROOT.rglob("*"):
        rel = p.relative_to(ROOT)
        if not p.is_file() or any(c in excluded for c in rel.parts) or p.name == "local.properties" or p.name.startswith(".env") or "credential" in p.name.lower() or p.name == "qa-friend-model.json":
            continue
        if p.suffix in allowed or p.suffix in (".xfpack", ".xpack") or p.name in ("gradlew", ".gitignore"):
            z.write(p, rel)
checksums = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(DIST.iterdir()) if p.suffix in (".apk", ".zip") and "0.8.0" in p.name)
(DIST / "SHA256SUMS-0.8.0.txt").write_text(checksums, encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
