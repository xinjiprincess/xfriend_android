"""Small UI driver for isolated product QA builds; never uses a real account."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = "C:/Users/zhang/AppData/Local/Android/Sdk/platform-tools/adb.exe"


def adb(*args):
    return subprocess.run([ADB, "-s", "emulator-5560", *args], check=True,
                          capture_output=True, encoding="utf-8", errors="replace", timeout=45).stdout


def ui():
    remote = f"/sdcard/suite-{time.monotonic_ns()}.xml"
    result = adb("shell", "uiautomator", "dump", remote)
    if "dumped to" not in result:
        raise RuntimeError(result)
    raw = adb("shell", "cat", remote)
    root = ET.fromstring(raw)
    (ROOT / "docs/suite-ui.xml").write_text(raw, encoding="utf-8")
    return root


def click(label):
    root = ui()
    n = next(n for n in root.iter("node") if n.get("text") == label or n.get("content-desc") == label)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


action = sys.argv[1]
if action == "state":
    for n in ui().iter("node"):
        if n.get("text") or n.get("content-desc"):
            print(n.get("text") or n.get("content-desc"), n.get("bounds"), n.get("class"))
elif action == "click":
    click(sys.argv[2])
elif action == "shot":
    name = Path(sys.argv[2]).name
    adb("shell", "screencap", "-p", "/sdcard/suite-qa.png")
    adb("pull", "/sdcard/suite-qa.png", str(ROOT / "docs" / name))
else:
    raise ValueError(action)
