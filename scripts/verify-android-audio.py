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
log = (ROOT / "android-audio-qa.log").open("wb")
emulator = subprocess.Popen([str(SDK / "emulator/emulator.exe"), "-avd", "XfriendSuiteQA", "-port", "5560", "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-skin", "1080x2400", "-gpu", "swiftshader_indirect", "-feature", "-Vulkan", "-memory", "2048", "-cores", "2"], env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)


def adb(*args, timeout=20):
    return subprocess.run([ADB, "-s", "emulator-5560", *args], capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)


evidence = {"environment": "Android 35 emulator 1080x2400", "synthetic_only": True}
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
    install = adb("install", "-r", str(ROOT / "app/build/outputs/apk/xsalers/debug/app-xsalers-debug.apk"), timeout=40)
    if "Success" not in install.stdout:
        raise RuntimeError("QA 安装失败：" + install.stdout + install.stderr)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "input", "keyevent", "82")
    start = adb("shell", "am", "start", "-n", PKG + "/com.singu.xfriend.SalesFixtureActivity", "--ez", "verifyAudio", "true")
    if start.returncode:
        raise RuntimeError("测试界面启动失败：" + start.stderr)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        state = adb("shell", "dumpsys", "activity", "services", PKG, timeout=8).stdout
        if "SalesTaskService" in state:
            evidence["service_started"] = True
            break
        time.sleep(1)
    else:
        evidence["android_runtime"] = adb("logcat", "-d", "-s", "AndroidRuntime:E").stdout[-16000:]
        raise RuntimeError("后台服务未启动，见 Android 运行日志")
    adb("shell", "input", "keyevent", "3")
    evidence["app_sent_to_background"] = True
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        result = adb("shell", "run-as", PKG, "cat", "files/qa-audio-evidence.json", timeout=8)
        if result.returncode == 0 and result.stdout.strip():
            result_json = json.loads(result.stdout)
            evidence["result"] = result_json
            assert not result_json.get("timeout")
            assert len(result_json["tasks"]) == 2 and all(t["status"] == "done" for t in result_json["tasks"]), "导入任务未成功"
            assert len(result_json["records"]) == 2
            assert all(r["rate"] == 16000 and r["channels"] == 1 for r in result_json["records"])
            assert any(r["bytes"] > 20 * 1024 * 1024 for r in result_json["records"])
            evidence["passed"] = True
            break
        if emulator.poll() is not None:
            raise RuntimeError("Android 进程挂起/退出，无法完成导入验收")
        time.sleep(2)
    else:
        raise RuntimeError("后台导入验收超时")
    adb("shell", "am", "start", "-n", PKG + "/com.singu.xfriend.SalesFixtureActivity")
    time.sleep(1)
    cap = adb("shell", "screencap", "-p", "/sdcard/suite-0.6.png", timeout=10)
    if cap.returncode == 0:
        adb("pull", "/sdcard/suite-0.6.png", str(ROOT / "docs/suite-0.6-xsalers-home.png"))
except Exception as exc:
    evidence.setdefault("passed", False)
    evidence["limitation"] = str(exc)
    try: evidence["android_runtime"] = adb("logcat", "-d", "-s", "AndroidRuntime:E", timeout=8).stdout[-16000:]
    except Exception: pass
finally:
    (ROOT / "docs/qa-audio-evidence-0.6.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    try:
        adb("emu", "kill", timeout=8)
    except subprocess.TimeoutExpired:
        pass
    if emulator.poll() is None:
        emulator.terminate()
    log.close()
print(json.dumps(evidence, ensure_ascii=False, indent=2))
