"""Offline fixture UI acceptance for the two prebuilt 0.9 QA APKs.

Run manually: python scripts/verify-ui-0.9.py
Does not build, download, log in, call a model, or invoke verifyRuntime/verifyAudio.
Only clears the two .qa packages on its own XfriendSuiteQA emulator (port 5560).
Requires the existing SDK/AVD and .artifacts/{Xfriend,Xsalers}-0.9-qa.apk.
Writes real observations to docs/qa-ui-0.9.json, PNG/XML captures and logcat.
A nonzero exit means failure; screenshots alone never count as passing a flow.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or
           "C:/Users/zhang/AppData/Local/Android/Sdk")
ADB = SDK / "platform-tools/adb.exe"
EMULATOR = SDK / "emulator/emulator.exe"
SERIAL = "emulator-5560"
AVD = "XfriendSuiteQA"
ACTIVITY = "com.singu.xfriend.SalesFixtureActivity"
DOCS = ROOT / "docs"
COLD_START_SECONDS = 90
BOOT_SECONDS = 240
FIXTURE_BANNER = "界面验收 · 独立 QA 安装 · 示例数据"
PRODUCTS = {
    "xfriend": ("com.singu.xfriend.mobile.qa", "Xfriend-0.9-qa.apk", ["对话", "专家", "我的"]),
    "xsalers": ("com.singu.xsalers.mobile.qa", "Xsalers-0.9-qa.apk", ["今日", "客户", "复盘", "我的"]),
}


def utc():
    return datetime.now(timezone.utc).isoformat()


def capture_command(args, timeout=40):
    return subprocess.run([str(x) for x in args], capture_output=True, encoding="utf-8",
                          errors="replace", timeout=timeout)


def command(args, timeout=40):
    result = capture_command(args, timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, args))}\n"
                           + (result.stdout + result.stderr)[-2000:])
    return result.stdout


def adb(*args, timeout=40):
    return command([ADB, "-s", SERIAL, *args], timeout)



def wait_for_system_services(evidence, timeout=120):
    """Require useful services and the same system_server across >=10s, not just boot_completed."""
    state = {"passed": False, "timeout_seconds": timeout, "samples": []}
    evidence["system_services"] = state
    deadline = time.monotonic() + timeout
    stable_since = None
    previous_pid = None
    while time.monotonic() < deadline:
        sample = {"time": utc()}
        try:
            sample["boot_animation"] = adb("shell", "getprop", "init.svc.bootanim", timeout=8).strip()
            sample["system_server_pid"] = adb("shell", "pidof", "system_server", timeout=8).strip()
            sample["services"] = {name: adb("shell", "service", "check", name, timeout=8).strip()
                                  for name in ("activity", "package", "window", "input", "wifi")}
            sample["android_package"] = adb("shell", "pm", "path", "android", timeout=10).strip()
            ready = (sample["boot_animation"] == "stopped" and sample["system_server_pid"].isdigit()
                     and all(re.search(r":\s*found\s*$", value) for value in sample["services"].values())
                     and sample["android_package"].startswith("package:"))
            if ready and previous_pid == sample["system_server_pid"] and stable_since is not None:
                if stable_since is not None and time.monotonic() - stable_since >= 10:
                    state["samples"].append(sample)
                    state["passed"] = True
                    return
            else:
                stable_since = time.monotonic() if ready else None
            previous_pid = sample["system_server_pid"]
        except (RuntimeError, subprocess.TimeoutExpired) as exc:
            sample["error"] = str(exc); stable_since = None; previous_pid = None
        state["samples"].append(sample)
        time.sleep(3)
    raise TimeoutError("Required Android system services did not stabilize within 120 seconds")


def configure_fixture_network(evidence):
    state = {}
    evidence["network_setup"] = state
    adb("shell", "svc", "wifi", "disable")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        status = adb("shell", "cmd", "wifi", "status", timeout=10).strip()
        state["wifi"] = {"status": "pending", "observed": status}
        if re.search(r"Wi-?Fi is disabled\b", status, re.I):
            state["wifi"]["status"] = "disabled"
            break
        time.sleep(2)
    else:
        raise RuntimeError("Wi-Fi disable was not confirmed by cmd wifi status")
    result = capture_command([ADB, "-s", SERIAL, "shell", "svc", "data", "disable"])
    output = (result.stdout + result.stderr).strip()
    state["mobile_data"] = {"returncode": result.returncode, "output": output}
    if re.search(r"Can(?:not|'t) find service:\s*phone\b", output, re.I):
        state["mobile_data"].update(status="not_applicable", reason="Emulator has no phone service")
    elif result.returncode:
        state["mobile_data"]["status"] = "failed"
        raise RuntimeError("Mobile-data disable failed: " + output)
    else:
        state["mobile_data"]["status"] = "disable_command_succeeded"


THREADTIME = re.compile(r"^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$")


def qa_process(name):
    return any(name == package or name.startswith(package + ":") for package, _, _ in PRODUCTS.values())


def classify_crashes(logs):
    """Attribute log events chronologically; never match arbitrary package mentions in a stacktrace.

    Native tombstones name the victim, not the DEBUG/crash_dump reporter PID. Java FATAL
    headers use their same-PID Process line. Start/death events prevent stale PID reuse.
    Unresolved events remain visible separately and are never labelled as QA crashes.
    """
    rows = []
    for number, line in enumerate(logs.splitlines(), 1):
        match = THREADTIME.match(line)
        if match:
            pid, tid, level, tag, message = match.groups()
            rows.append((number, line, int(pid), tag.strip(), message))
    processes = {}
    groups = {"app_crashes": [], "system_crashes": [], "unattributed_crashes": []}
    for index, (number, line, reporter, tag, message) in enumerate(rows):
        start = re.search(r"Start proc (\d+):([^/\s]+)/", message) if tag == "ActivityManager" else None
        if start:
            processes[int(start[1])] = start[2]
        if tag == "am_proc_start":
            fields = message.strip("[]").split(",")
            if len(fields) >= 4 and fields[1].strip().isdigit():
                processes[int(fields[1])] = fields[3].strip()
        java = re.match(r"Process:\s*([^,]+),\s*PID:\s*(\d+)", message) if tag == "AndroidRuntime" else None
        if java:
            processes[int(java[2])] = java[1].strip()
        native = re.search(r"pid:\s*(\d+),.*>>>\s*(\S+)\s*<<<", message) if tag == "DEBUG" else None
        event = tag in ("am_crash", "am_anr")
        fatal = bool(re.search(r"\bFATAL\b|Fatal signal|^ANR in\b", message))
        if fatal or event or native:
            victim = reporter
            name = None
            attribution = "unresolved"
            if event:
                fields = message.strip("[]").split(",")
                if len(fields) >= 3:
                    name = fields[2].strip()
                    victim = int(fields[1]) if fields[1].strip().isdigit() else None
                    attribution = "event_process"
            elif native:
                victim, name = int(native[1]), native[2]
                attribution = "tombstone_victim"
            elif tag == "AndroidRuntime":
                # Interleaved log lines are allowed, but only this reporter's Process line counts.
                for _, _, other_pid, other_tag, other_message in rows[index + 1:index + 41]:
                    if other_pid == reporter and other_tag == "AndroidRuntime":
                        identity = re.match(r"Process:\s*([^,]+),\s*PID:\s*(\d+)", other_message)
                        if identity and int(identity[2]) == reporter:
                            name = identity[1].strip(); attribution = "java_process"; break
            elif message.startswith("ANR in "):
                identity = re.match(r"ANR in ([\w.:]+)(?:.*\(.*pid\s+(\d+))?", message)
                if identity:
                    name = identity[1]; victim = int(identity[2]) if identity[2] else None
                    attribution = "anr_process"
            elif "Fatal signal" in message:
                identity = re.search(r"\bpid\s+(\d+)\s*\(([^)]+)\)", message)
                if identity:
                    victim = int(identity[1])
                    # Linux task names may be truncated; prefer the lifecycle PID mapping.
                    name = processes.get(victim) or identity[2]
                    attribution = "native_pid"
            if name is None and victim in processes:
                name = processes[victim]; attribution = "process_lifecycle_pid"
            record = {"line": number, "message": line, "pid": victim,
                      "process": name, "attribution": attribution}
            group = "app_crashes" if name and qa_process(name) else "system_crashes" if name else "unattributed_crashes"
            groups[group].append(record)
        if tag == "am_proc_died":
            fields = message.strip("[]").split(",")
            if len(fields) >= 2 and fields[1].strip().isdigit():
                processes.pop(int(fields[1]), None)
        died = re.search(r"Process \S+ \(pid (\d+)\) has died", message) if tag == "ActivityManager" else None
        if died:
            processes.pop(int(died[1]), None)
    return {"checked": True, "passed": not groups["app_crashes"],
            "matches": [event["message"] for event in groups["app_crashes"]], **groups,
            "log": "docs/qa-ui-0.9-logcat.txt"}

def bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    return tuple(map(int, match.groups())) if match else (0, 0, 0, 0)


def matches(node, label):
    return label in (node.get("text"), node.get("content-desc"))


class UIUnavailable(RuntimeError):
    """Transient UIAutomator failure while Android or an activity is starting."""


class UI:
    def __init__(self, product, theme, package, evidence):
        self.product, self.theme, self.package = product, theme, package
        self.evidence = evidence
        self.prefix = f"suite-0.9-{product}-{theme}"
        self.last_xml = ""

    def tree(self):
        remote = "/sdcard/qa-ui-0.9.xml"
        # Remove stale dumps first: a failed dump must not reuse yesterday's UI.
        try:
            adb("shell", "rm", "-f", remote)
            adb("shell", "uiautomator", "dump", remote, timeout=25)
            self.last_xml = adb("shell", "cat", remote)
            tree = ET.fromstring(self.last_xml)
        except (RuntimeError, ET.ParseError, subprocess.TimeoutExpired) as exc:
            raise UIUnavailable(str(exc)) from exc
        if any(n.get("text") in ("Close app", "关闭应用", "应用无响应", "App isn't responding")
               for n in tree.iter("node")):
            raise RuntimeError("Blocking Android error dialog is visible; process attribution is recorded separately from logcat")
        return tree

    def nodes(self, tree, label, bottom=False):
        found = []
        for n in tree.iter("node"):
            x1, y1, x2, y2 = bounds(n)
            if (matches(n, label) and n.get("package") == self.package and
                    n.get("enabled", "true") == "true" and x2 > x1 and y2 > y1 and
                    0 <= x1 < 720 and 0 <= y1 < 1600 and (not bottom or y1 >= 1320)):
                found.append(n)
        return found

    def wait(self, labels, timeout=30, bottom=False):
        deadline = time.monotonic() + timeout
        last = "No UI dump yet"
        while time.monotonic() < deadline:
            try:
                tree = self.tree()
                if all(self.nodes(tree, label, bottom) for label in labels):
                    return tree
                last = "Visible: " + " | ".join(n.get("text", "") for n in tree.iter("node") if n.get("text"))
            except UIUnavailable as exc:
                last = str(exc)
            # Crash dialogs intentionally fail; only transient dump failures are retried.
            time.sleep(2)
        raise TimeoutError(f"Timed out waiting for {labels}: {last[-1800:]}")

    def check(self, name, labels, timeout=30):
        self.wait(labels, timeout)
        self.evidence["checks"].append({"name": name, "observed_labels": labels, "passed": True, "time": utc()})

    def tap(self, label, bottom=False, scroll=False):
        for attempt in range(7 if scroll else 1):
            tree = self.wait([label], timeout=6, bottom=bottom) if not scroll else self.tree()
            found = self.nodes(tree, label, bottom)
            if found:
                # Text nodes inside clickable Compose parents have valid touch coordinates too.
                x1, y1, x2, y2 = bounds(found[-1] if bottom else found[0])
                adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(0.8)
                return
            if scroll and attempt < 6:
                adb("shell", "input", "swipe", "360", "1250", "360", "570", "350")
                time.sleep(0.8)
        raise RuntimeError("Control not found: " + label)

    def tabs(self, expected):
        tree = self.wait(expected, timeout=COLD_START_SECONDS, bottom=True)
        # Inspect all text in the bottom navigation row, not headers/chips named '专家'.
        anchors = [self.nodes(tree, label, True)[-1] for label in expected]
        centers = [(bounds(n)[1]+bounds(n)[3])/2 for n in anchors]
        if max(centers)-min(centers) > 35:
            raise AssertionError("Navigation labels are not on one row")
        center = sum(centers)/len(centers)
        observed = sorted({n.get("text") or n.get("content-desc") for n in tree.iter("node")
                           if n.get("package") == self.package and (n.get("text") or n.get("content-desc"))
                           and abs((bounds(n)[1]+bounds(n)[3])/2-center) < 28})
        if observed != sorted(expected):
            raise AssertionError(f"Wrong navigation tabs: {observed}; expected {expected}")
        self.evidence["checks"].append({"name": "bottom_tabs", "expected": expected,
                                        "observed": observed, "passed": True, "time": utc()})

    def shot(self, name):
        tree = self.tree()
        if not any(n.get("package") == self.package for n in tree.iter("node")):
            raise AssertionError("Screenshot would capture another app")
        png = DOCS / f"{self.prefix}-{name}.png"
        xml = png.with_suffix(".xml")
        remote = "/sdcard/qa-ui-0.9.png"
        adb("shell", "rm", "-f", remote)
        adb("shell", "screencap", "-p", remote)
        adb("pull", remote, png)
        raw = png.read_bytes()
        if raw[:8] != b"\x89PNG\r\n\x1a\n" or len(raw) < 24:
            raise AssertionError("Invalid screenshot PNG")
        dimensions = struct.unpack(">II", raw[16:24])
        if dimensions != (720, 1600):
            raise AssertionError(f"Unexpected screenshot dimensions: {dimensions}")
        xml.write_text(self.last_xml, encoding="utf-8")
        self.evidence["screenshots"].append({"name": name, "png": str(png.relative_to(ROOT)),
            "xml": str(xml.relative_to(ROOT)), "size": list(dimensions),
            "sha256": hashlib.sha256(raw).hexdigest(), "time": utc()})


def friend_flow(ui):
    ui.check("fixture", [FIXTURE_BANNER, "Xfriend 助手"], COLD_START_SECONDS)
    ui.tabs(PRODUCTS["xfriend"][2]); ui.shot("chat")
    ui.tap("专家", bottom=True)
    ui.check("expert_cards", ["你的工作伙伴", "管理Xfriend 助手"]); ui.shot("experts")
    ui.tap("管理Xfriend 助手"); ui.tap("编辑")
    ui.check("expert_editor", ["编辑专家", "名称", "工作方式与指令", "保存"]); ui.shot("expert-editor")
    # Exercise Android Back, without changing or saving fixture configuration.
    adb("shell", "input", "keyevent", "4")
    ui.check("editor_back", ["你的工作伙伴", "管理Xfriend 助手"])
    ui.tabs(PRODUCTS["xfriend"][2])
    ui.tap("我的", bottom=True)
    ui.check("profile", ["我的工作空间", "电脑与平台"]); ui.shot("profile")
    ui.tap("电脑与平台")
    ui.check("connections_entry", ["使用电脑的完整能力", "电脑会话", "打开电脑工作台", "平台设备"])
    ui.shot("connections")
    ui.tap("返回"); ui.check("connections_back", ["我的工作空间"])
    ui.tabs(PRODUCTS["xfriend"][2])


def sales_flow(ui):
    ui.check("fixture", [FIXTURE_BANNER, "今日"], COLD_START_SECONDS)
    ui.tabs(PRODUCTS["xsalers"][2]); ui.shot("today")
    ui.tap("客户", bottom=True)
    ui.tap("示例客户 · 陈经理", scroll=True)
    ui.check("customer_detail", ["示例客户 · 陈经理", "返回客户", "编辑资料"]); ui.shot("customer-detail")
    ui.tap("沟通", scroll=True)
    ui.check("customer_history", ["沟通时间线"]); ui.shot("customer-history")
    ui.tap("方案沟通复盘", scroll=True)
    ui.check("saved_review", ["沟通复盘", "方案沟通复盘", "分析报告", "沟通原文"]); ui.shot("saved-review")
    ui.tap("沟通原文"); ui.shot("saved-transcript")
    # The saved fixture report is inspected; no live analysis/recording is started.
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    # Detail preserves its previous scroll position, so the top Back label may be offscreen.
    for _ in range(3):
        adb("shell", "input", "swipe", "360", "550", "360", "1250", "300")
    ui.wait(["返回客户"])
    adb("shell", "input", "keyevent", "4")
    ui.tabs(PRODUCTS["xsalers"][2])
    ui.tap("复盘", bottom=True)
    ui.check("review_entry", ["专家与团队", "关联客户"]); ui.shot("review")
    ui.tap("专家与团队")
    ui.check("review_experts", ["返回复盘", "销售专家"]); ui.shot("review-experts")
    adb("shell", "input", "keyevent", "4"); ui.wait(["关联客户"])
    ui.tabs(PRODUCTS["xsalers"][2])
    ui.tap("我的", bottom=True); ui.check("profile", ["为你的销售工作而设"]); ui.shot("profile")
    ui.tabs(PRODUCTS["xsalers"][2])


def main():
    DOCS.mkdir(exist_ok=True)
    evidence = {"started": utc(), "passed": False, "synthetic_only": True,
        "avd": AVD, "serial": SERIAL, "requested_resolution": [720, 1600],
        "cold_start_timeout_seconds": COLD_START_SECONDS, "scenarios": [],
        "limitations": ["Fixture UI only; no real model, network, login or desktop connection verification.",
                        "Screenshots require visual review; no claim of contrast/font/accessibility acceptance."]}
    emulator = None
    log = None
    booted = False
    try:
        for executable in (ADB, EMULATOR):
            if not executable.is_file(): raise FileNotFoundError(executable)
        # Do not take over or kill another task's emulator at this serial/port.
        devices = command([ADB, "devices"])
        if SERIAL in devices: raise RuntimeError(f"{SERIAL} already occupied; stop it before running this script")
        if not (ROOT / ".qa-avd" / (AVD + ".ini")).is_file():
            raise FileNotFoundError("Existing .qa-avd/XfriendSuiteQA.ini is required")
        aapts = sorted((SDK / "build-tools").glob("*/aapt.exe"))
        if not aapts: raise FileNotFoundError("SDK build-tools aapt.exe is required to verify QA package identities")
        evidence["apks"] = []
        for package, filename, _ in PRODUCTS.values():
            apk = ROOT / ".artifacts" / filename
            if not apk.is_file(): raise FileNotFoundError(apk)
            badging = command([aapts[-1], "dump", "badging", apk], timeout=60)
            match = re.search(r"^package: name='([^']+)'", badging, re.M)
            if not match or match.group(1) != package:
                raise RuntimeError(f"Refusing non-QA/mismatched APK: {filename}")
            evidence["apks"].append({"path": str(apk.relative_to(ROOT)), "package": package,
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "package_badging": badging.splitlines()[0]})
        log = (DOCS / "qa-ui-0.9-emulator.log").open("wb")
        env = dict(os.environ, ANDROID_AVD_HOME=str(ROOT / ".qa-avd"))
        emulator = subprocess.Popen([str(EMULATOR), "-avd", AVD, "-port", "5560", "-no-window", "-no-audio",
            "-no-boot-anim", "-no-snapshot", "-skin", "720x1600", "-gpu", "swiftshader_indirect",
            "-feature", "-Vulkan", "-memory", "3072", "-cores", "4"],
            env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        deadline = time.monotonic() + BOOT_SECONDS
        while time.monotonic() < deadline:
            if emulator.poll() is not None: raise RuntimeError("Emulator exited before boot")
            try:
                if adb("shell", "getprop", "sys.boot_completed", timeout=6).strip() == "1":
                    booted = True; break
            except (RuntimeError, subprocess.TimeoutExpired):
                pass
            time.sleep(2)
        if not booted: raise TimeoutError("Emulator cold boot exceeded 240 seconds")
        evidence["log_capture_started"] = utc()
        evidence["log_scope"] = "Full current emulator boot through QA; boot/system crashes retained"
        wait_for_system_services(evidence)
        adb("shell", "wm", "size", "720x1600"); adb("shell", "wm", "density", "300")
        adb("shell", "input", "keyevent", "82")
        # Fixtures use local seeded data. Disable mobile/Wi-Fi; never submit remote actions.
        configure_fixture_network(evidence)
        evidence["device"] = {"sdk": adb("shell", "getprop", "ro.build.version.sdk").strip(),
            "release": adb("shell", "getprop", "ro.build.version.release").strip(),
            "size": adb("shell", "wm", "size").strip(), "density": adb("shell", "wm", "density").strip()}
        for product, (package, filename, _) in PRODUCTS.items():
            output = adb("install", "-r", ROOT / ".artifacts" / filename, timeout=120)
            if "Success" not in output: raise RuntimeError("QA install did not report success: " + output)
            for theme, night in (("light", "no"), ("dark", "yes")):
                result = {"product": product, "theme": theme, "package": package, "passed": False,
                          "checks": [], "screenshots": [], "started": utc()}
                evidence["scenarios"].append(result)
                ui = UI(product, theme, package, result)
                try:
                    if "Success" not in adb("shell", "pm", "clear", package):
                        raise RuntimeError("Could not clear isolated QA fixture data")
                    adb("shell", "cmd", "uimode", "night", night)
                    actual = adb("shell", "cmd", "uimode", "night").strip()
                    if not re.search(r"Night mode:\s*" + night + r"\b", actual, re.I):
                        raise AssertionError("System theme did not change: " + actual)
                    result["observed_night_mode"] = actual
                    adb("shell", "am", "force-stop", package)
                    launched = adb("shell", "am", "start", "-W", "-n", package + "/" + ACTIVITY,
                                   timeout=COLD_START_SECONDS)
                    if "Error:" in launched or "Status: timeout" in launched:
                        raise RuntimeError("Fixture launch failed: " + launched)
                    result["launch_output"] = launched
                    (friend_flow if product == "xfriend" else sales_flow)(ui)
                    result["passed"] = True
                except Exception as exc:
                    result["error"] = f"{type(exc).__name__}: {exc}"
                    try: ui.shot("failed")
                    except Exception as shot_error: result["failure_capture_error"] = str(shot_error)
                finally:
                    result["finished"] = utc()
                    adb("shell", "am", "force-stop", package)
        evidence["flows_passed"] = len(evidence["scenarios"]) == 4 and all(r["passed"] for r in evidence["scenarios"])
    except Exception as exc:
        evidence["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        if booted:
            try:
                logs = adb("logcat", "-b", "all", "-d", "-v", "threadtime", timeout=30)
                (DOCS / "qa-ui-0.9-logcat.txt").write_text(logs, encoding="utf-8")
                evidence["crash_check"] = classify_crashes(logs)
            except Exception as exc:
                evidence["crash_check"] = {"checked": False, "passed": False, "error": str(exc)}
        # Only stop the emulator process created by this invocation.
        if emulator is not None:
            try:
                if booted: adb("emu", "kill", timeout=15)
                emulator.wait(timeout=20)
            except Exception:
                try:
                    if emulator.poll() is None:
                        emulator.terminate()
                        try: emulator.wait(timeout=10)
                        except subprocess.TimeoutExpired: emulator.kill(); emulator.wait(timeout=10)
                except Exception as exc:
                    evidence["cleanup_error"] = str(exc)
        if log is not None: log.close()
        evidence["passed"] = bool(evidence.get("flows_passed") and evidence.get("crash_check", {}).get("passed") and not evidence.get("error"))
        evidence["finished"] = utc()
        output = json.dumps(evidence, ensure_ascii=False, indent=2)
        (DOCS / "qa-ui-0.9.json").write_text(output, encoding="utf-8")
        print(output)
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"): sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
