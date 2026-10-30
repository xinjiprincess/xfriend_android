import hashlib
import shutil
import time
import zipfile
from pathlib import Path

STAGE = Path(__file__).resolve().parents[1]
TARGET = Path("D:/xfriend-android").resolve()
assert str(TARGET).lower().replace("\\", "/") == "d:/xfriend-android"

changed = [
    "README.md", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/singu/xfriend/AccountScreen.kt",
    *[f"app/src/main/java/com/singu/xfriend/{name}.kt" for name in ("SalesDomain", "SalesStore", "SalesApi", "SalesMedia", "SalesViewModel", "SalesScreen")],
    "app/src/test/java/com/singu/xfriend/SalesDomainTest.kt",
    "app/src/uiFixture/AndroidManifest.xml",
    "app/src/uiFixture/java/com/singu/xfriend/SalesFixtureActivity.kt",
    "desktop-bridge/bridge.py", "desktop-bridge/exporter.py", "desktop-bridge/test_exporter.py", "desktop-bridge/README.md",
]
for directory in ("trial-gateway", "docs", "scripts"):
    for path in (STAGE / directory).rglob("*"):
        if not path.is_file() or any(p.startswith(".") or p == "__pycache__" for p in path.relative_to(STAGE).parts):
            continue
        if directory == "docs" and path.suffix not in (".html", ".json", ".png"):
            continue
        if directory == "scripts" and path.name not in ("smoke-sales.py", "check-report.py", "finish-sales-docs.py", "package-sales.py"):
            continue
        changed.append(path.relative_to(STAGE).as_posix())
changed = sorted(set(changed))
backup = TARGET / ".artifacts" / ("before-sales-0.4-" + time.strftime("%Y%m%d-%H%M%S") + ".zip")
backup.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(backup, "w", zipfile.ZIP_DEFLATED) as z:
    for relative in changed:
        destination = (TARGET / relative).resolve()
        assert destination.is_relative_to(TARGET)
        if destination.is_file():
            z.write(destination, relative)
for relative in changed:
    source = STAGE / relative
    assert source.is_file(), relative
    destination = TARGET / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
for name in ("verify-final-0.4.log", "python-tests-final-0.4.log", "report-check-0.4.log", "smoke-sales-0.4.log"):
    source = STAGE / name
    if source.exists():
        shutil.copy2(source, TARGET / name)

dist = TARGET / "dist"
dist.mkdir(exist_ok=True)
apk = dist / "Xfriend-Android-0.4.0-debug.apk"
shutil.copy2(STAGE / "app/build/outputs/apk/debug/app-debug.apk", apk)
outputs = [apk]
for directory, title in (("desktop-bridge", "Desktop-Bridge"), ("trial-gateway", "Trial-Gateway"), ("docs", "Desktop-Handoff")):
    archive = dist / f"Xfriend-{title}-0.4.0.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for source in (TARGET / directory).rglob("*"):
            if not source.is_file() or any(p.startswith(".") or p == "__pycache__" for p in source.relative_to(TARGET / directory).parts):
                continue
            if directory == "docs" and ("0.4" not in source.name and not source.name.startswith("mobile-export-v1")):
                continue
            if source.suffix in (".apk", ".pyc", ".xml"):
                continue
            z.write(source, source.relative_to(TARGET).as_posix())
    outputs.append(archive)
sumfile = dist / "SHA256SUMS-0.4.0.txt"
sumfile.write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in outputs), encoding="ascii")
print("Backup: " + str(backup))
for path in outputs:
    print(f"Delivered {path.name}: {path.stat().st_size} bytes")
print("SHA256 manifest: " + str(sumfile))
