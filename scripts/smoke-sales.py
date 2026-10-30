"""Interact only with the isolated .qa app and synthetic data on emulator-5554."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = "C:/Users/zhang/AppData/Local/Android/Sdk/platform-tools/adb.exe"


def adb(*args):
    return subprocess.run([ADB, "-s", "emulator-5554", *args], capture_output=True, check=True, encoding="utf-8", errors="replace", timeout=30).stdout


def ui():
    for _ in range(4):
        try:
            remote = f"/sdcard/sales-qa-{time.monotonic_ns()}.xml"
            dumped = adb("shell", "uiautomator", "dump", remote)
            if "dumped to" not in dumped:
                raise ValueError(dumped)
            raw = adb("shell", "cat", remote)
            root = ET.fromstring(raw)
            (ROOT / "docs/sales-ui.xml").write_text(raw, encoding="utf-8")
            return root
        except (subprocess.SubprocessError, ET.ParseError, ValueError):
            time.sleep(1)
    raise AssertionError("UI unavailable")


def node(root, label):
    return next(n for n in root.iter("node") if n.get("text") == label or n.get("content-desc") == label)


def tap(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def click(label):
    tap(node(ui(), label)); time.sleep(1.2)


def snapshot(name):
    adb("shell", "screencap", "-p", "/sdcard/sales-qa.png")
    adb("pull", "/sdcard/sales-qa.png", str(ROOT / "docs" / name))


action = sys.argv[1]
if action == "state":
    for n in ui().iter("node"):
        if n.get("text") or n.get("content-desc"):
            print(n.get("text") or n.get("content-desc"), n.get("bounds"), n.get("class"))
elif action == "click":
    click(sys.argv[2])
elif action == "shot":
    snapshot(sys.argv[2])
elif action == "customer":
    root = ui()
    if not any(n.get("text") == "客户资料" for n in root.iter("node")):
        click("客户"); click("新建客户")
    def fill(label, value):
        root = ui()
        field = next(n for n in root.iter("node") if n.get("class") == "android.widget.EditText" and any(c.get("text") == label for c in n.iter("node")))
        tap(field); time.sleep(.5); adb("shell", "input", "text", value)
    fill("姓名", "SalesDemo")
    fill("电话", "13800138000")
    adb("shell", "input", "keyevent", "4")
    click("保存")
    assert node(ui(), "SalesDemo") is not None
    snapshot("sales-customers-0.4.png")
    print("PASS create customer and link screen")
elif action == "record":
    click("采集")
    root = ui()
    if any(n.get("text") == "开始录音" or n.get("content-desc") == "开始录音" for n in root.iter("node")):
        tap(node(root, "开始录音"))
    else:
        adb("shell", "input", "swipe", "540", "1850", "540", "950", "400"); click("开始录音")
    time.sleep(1)
    root = ui()
    for n in root.iter("node"):
        if n.get("resource-id") == "com.android.permissioncontroller:id/permission_allow_foreground_only_button":
            tap(n); break
    time.sleep(3)
    click("停止录音")
    click("首页")
    assert node(ui(), "现场沟通录音") is not None
    snapshot("sales-recording-saved-0.4.png")
    print("PASS microphone grant, record WAV, save and list")
elif action == "edit-restart":
    root = ui()
    field = next(n for n in root.iter("node") if n.get("class") == "android.widget.EditText" and any(c.get("text") == "转写/识图原文（可校正）" for c in n.iter("node")))
    tap(field); time.sleep(.5)
    adb("shell", "input", "keycombination", "113", "29")
    adb("shell", "input", "text", "QA_OK")
    time.sleep(2)
    adb("shell", "input", "keyevent", "4")
    click("保存修改"); click("关闭")
    adb("shell", "am", "force-stop", "com.singu.xfriend.mobile.qa")
    adb("shell", "am", "start", "-n", "com.singu.xfriend.mobile.qa/com.singu.xfriend.SalesFixtureActivity")
    time.sleep(5)
    for _ in range(3):
        root = ui()
        if any(n.get("text") == "沟通详情" for n in root.iter("node")):
            break
        click("现场沟通录音")
    assert node(ui(), "QA_OK") is not None
    snapshot("sales-record-detail-0.4.png")
    click("关闭"); click("采集")
    assert node(ui(), "分析模式 · 谈判复盘") is not None
    snapshot("sales-capture-0.4.png")
    click("客户")
    assert node(ui(), "SalesDemo") is not None
    print("PASS edit transcript, restart, restore record/customer/mode")
elif action == "mode-restart":
    root = ui()
    if not any(n.get("text", "").startswith("分析模式 ·") for n in root.iter("node")):
        click("采集")
        root = ui()
    field = next(n for n in root.iter("node") if n.get("text", "").startswith("分析模式 ·"))
    tap(field); time.sleep(1)
    click("谈判复盘")
    assert node(ui(), "分析模式 · 谈判复盘") is not None
    click("专家分析")
    assert any(n.get("text", "").startswith("专家 ·") for n in ui().iter("node"))
    click("团队协作")
    assert node(ui(), "团队 · 客情分析团队") is not None
    snapshot("sales-capture-0.4.png")
    adb("shell", "am", "force-stop", "com.singu.xfriend.mobile.qa")
    adb("shell", "am", "start", "-n", "com.singu.xfriend.mobile.qa/com.singu.xfriend.SalesFixtureActivity")
    time.sleep(5)
    click("采集")
    assert node(ui(), "分析模式 · 谈判复盘") is not None
    click("客户")
    assert node(ui(), "SalesDemo") is not None
    print("PASS choose mode, expert/team, restart, restore mode and customer")
