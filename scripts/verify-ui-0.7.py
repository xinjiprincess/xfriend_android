"""Actual Android large-file import QA; all inputs are synthetic in the isolated .qa package."""
import json
import os
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = Path("C:/Users/zhang/AppData/Local/Android/Sdk")
ADB = str(SDK / "platform-tools/adb.exe")
PKG = "com.singu.xsalers.mobile.qa"
env = dict(os.environ, ANDROID_AVD_HOME=str(ROOT / ".qa-avd"))
log = (ROOT / "android-ui-qa.log").open("wb")
emulator = subprocess.Popen([str(SDK / "emulator/emulator.exe"), "-avd", "XfriendSuiteQA", "-port", "5560", "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-skin", "720x1600", "-gpu", "swiftshader_indirect", "-feature", "-Vulkan", "-memory", "3072", "-cores", "4"], env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)


def adb(*args, timeout=20):
    return subprocess.run([ADB, "-s", "emulator-5560", *args], capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)


evidence = {"environment": "Android 35 emulator 720x1600", "synthetic_only": True}
try:
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        if emulator.poll() is not None:
            raise RuntimeError("模拟器提前退出")
        try:
            if adb("shell", "getprop", "sys.boot_completed", timeout=5).stdout.strip() == "1":
                break
        except subprocess.TimeoutExpired:
            pass
        time.sleep(2)
    else:
        raise RuntimeError("模拟器未完成启动")
    install = adb("install", "-r", str(ROOT / ".artifacts/Xsalers-0.7-qa.apk"), timeout=40)
    if "Success" not in install.stdout:
        raise RuntimeError("QA 安装失败：" + install.stdout + install.stderr)
    adb("shell", "pm", "clear", PKG)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "input", "keyevent", "82")
    adb("shell", "wm", "size", "720x1600")
    adb("shell", "wm", "density", "300")
    adb("shell", "am", "start", "-n", PKG + "/com.singu.xfriend.SalesFixtureActivity")
    time.sleep(12)
    import xml.etree.ElementTree as ET
    import re
    def ui():
        path = "/sdcard/xsalers-ui.xml"
        adb("shell", "uiautomator", "dump", path, timeout=30)
        raw = adb("shell", "cat", path).stdout
        return ET.fromstring(raw)
    def click(text):
        tree = ui()
        node = next((n for n in tree.iter("node") if n.get("text") == text or n.get("content-desc") == text), None)
        for _ in range(5):
            if node is not None: break
            adb("shell", "input", "swipe", "360", "1320", "360", "560", "350")
            time.sleep(1)
            tree = ui()
            node = next((n for n in tree.iter("node") if n.get("text") == text or n.get("content-desc") == text), None)
        if node is None: raise RuntimeError("无法找到控件: " + text)
        x1,y1,x2,y2 = map(int,re.findall(r"\d+",node.get("bounds")))
        adb("shell", "input", "tap", str((x1+x2)//2),str((y1+y2)//2))
        time.sleep(2)
    evidence["screens"] = []
    def shot(name):
        adb("shell", "screencap", "-p", "/sdcard/xsalers-ui.png", timeout=20)
        adb("pull", "/sdcard/xsalers-ui.png", str(ROOT / ("docs/suite-0.7-"+name+".png")))
        if not name.startswith("failed"): evidence["screens"].append(name)
    tree = ui()
    if any(n.get("text") == "Close app" for n in tree.iter("node")):
        click("Close app")
        adb("shell", "am", "start", "-n", PKG + "/com.singu.xfriend.SalesFixtureActivity")
        time.sleep(8)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        tree = ui()
        if any(n.get("text") == "今日" for n in tree.iter("node")): break
        if any(n.get("text") == "Close app" for n in tree.iter("node")):
            click("Close app")
            adb("shell", "am", "start", "-n", PKG + "/com.singu.xfriend.SalesFixtureActivity")
        time.sleep(4)
    else:
        shot("failed-home")
        raise RuntimeError("首页未加载: " + " | ".join(n.get("text", "") for n in tree.iter("node") if n.get("text")))
    shot("home")
    click("客户");shot("customers")
    click("示例客户 · 陈经理");shot("customer-detail")
    click("沟通");shot("customer-records")
    click("方案沟通复盘");shot("report")
    click("沟通原文");shot("transcript")
    adb("shell", "input", "keyevent", "4");time.sleep(2);adb("shell", "input", "keyevent", "4");time.sleep(2)
    click("复盘");shot("review")
    click("专家");shot("experts")
    evidence["passed"] = True
    evidence["screens"] = ["home","customers","customer-detail","customer-records","report","transcript","review","experts"]
except Exception as exc:
    evidence.setdefault("passed", False)
    evidence["limitation"] = type(exc).__name__ + ": " + str(exc)
    try: evidence["android_runtime"] = adb("logcat", "-d", "-s", "AndroidRuntime:E", timeout=8).stdout[-16000:]
    except Exception: pass
finally:
    (ROOT / "docs/qa-ui-evidence-0.7.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    try:
        adb("emu", "kill", timeout=8)
    except subprocess.TimeoutExpired:
        pass
    if emulator.poll() is None:
        emulator.terminate()
    log.close()
print(json.dumps(evidence, ensure_ascii=False, indent=2))
