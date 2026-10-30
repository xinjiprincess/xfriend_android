"""Build local source archives from an explicit allowlist; never copy state."""

import hashlib
import io
import json
from pathlib import Path
import tarfile
import tomllib
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NAME = "xfriend-trial-gateway-0.7.0"
FILES = (
    "gateway.py", "reviews.py", "review_store.py", "review_pipeline.py",
    "pyproject.toml", "uv.lock", "README.md", "review-protocol.json",
    "deployment-checklist.txt", "xfriend-trial.service",
    "test_gateway.py", "test_reviews.py", "test_review_integration.py",
    "release/DEPLOYMENT-0.7.txt", "release/gateway.env.example",
    "release/nginx-v2-location.conf", "release/package.py",
    "release/evidence/pytest.txt", "release/evidence/pytest.xml",
)


def build():
    project = tomllib.loads((ROOT / "pyproject.toml").read_text("utf-8"))
    assert project["project"]["version"] == "0.7.0"
    suites = ET.parse(ROOT / "release/evidence/pytest.xml").getroot()
    assert sum(int(s.get("tests", 0)) for s in suites.iter("testsuite")) == 57
    for suite in suites.iter("testsuite"):
        assert all(int(suite.get(k, 0)) == 0 for k in ("errors", "failures", "skipped"))
    data = {}
    for name in FILES:
        path = ROOT / name
        assert not path.is_symlink() and path.resolve().is_relative_to(ROOT)
        data[name] = path.read_bytes()
    data["release/evidence/release-status.json"] = (
        json.dumps({
            "version": "0.7.0", "tests_passed": 57,
            "command": "uv run --offline --locked pytest -q --junitxml=release/evidence/pytest.xml",
            "model_e2e": "not_run", "apk_e2e": "not_run",
            "deployed": False, "remote_connections_this_release": False,
            "deployment_blocker": "113.219.170.192 hostkey unverified; reported mismatch",
            "package": "source_only; dependency wheels not included",
        }, ensure_ascii=False, indent=2) + "\n"
    ).encode()
    manifest = "".join(
        f"{hashlib.sha256(body).hexdigest()}  {name}\n"
        for name, body in sorted(data.items())
    )
    data["MANIFEST.sha256"] = manifest.encode()
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    zip_path = output / f"{NAME}.zip"
    tar_path = output / f"{NAME}.tar.gz"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, body in sorted(data.items()):
            archive.writestr(f"{NAME}/{name}", body)
    with tarfile.open(tar_path, "w:gz") as archive:
        for name, body in sorted(data.items()):
            info = tarfile.TarInfo(f"{NAME}/{name}")
            info.size = len(body)
            info.mode = 0o644
            archive.addfile(info, io.BytesIO(body))
    # Verify both archives against precisely the selected bytes without extraction.
    with zipfile.ZipFile(zip_path) as archive:
        assert set(archive.namelist()) == {f"{NAME}/{n}" for n in data}
        assert all(archive.read(f"{NAME}/{n}") == b for n, b in data.items())
    with tarfile.open(tar_path) as archive:
        assert set(archive.getnames()) == {f"{NAME}/{n}" for n in data}
        assert all(m.isfile() for m in archive.getmembers())
        assert all(archive.extractfile(f"{NAME}/{n}").read() == b for n, b in data.items())
    checksums = "".join(
        f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n"
        for p in (tar_path, zip_path)
    )
    (output / "SHA256SUMS").write_text(checksums, encoding="utf-8")
    (output / "CONTENTS.txt").write_text(manifest, encoding="utf-8")
    print(f"Verified {len(data)} files in each archive; no recursive source collection.")
    print(checksums, end="")


if __name__ == "__main__":
    build()
