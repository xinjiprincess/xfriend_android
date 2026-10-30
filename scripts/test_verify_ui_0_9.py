"""Offline regression tests. Never invoke adb, an emulator, Gradle, or modify QA evidence."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("verify-ui-0.9.py")
spec = importlib.util.spec_from_file_location("verify_ui_0_9", SCRIPT)
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
APP = "com.singu.xfriend.mobile.qa"
SALES = "com.singu.xsalers.mobile.qa"


def line(pid, tag, text):
    return f"10-17 01:39:20.792 {pid:5} {pid:5} E {tag}: {text}"


class CrashTests(unittest.TestCase):
    def test_real_bluetooth_log_is_not_qa_crash(self):
        logs = SCRIPT.parent.parent / "docs/qa-ui-0.9-logcat.txt"
        if not logs.exists(): self.skipTest("Local captured log is not available")
        raw = logs.read_text(encoding="utf-8")
        if "com.google.android.bluetooth" not in raw or "Fatal signal" not in raw:
            self.skipTest("Current evidence no longer contains the reported Bluetooth crash")
        result = qa.classify_crashes(raw)
        # The original captured run failed before launching either QA package.
        self.assertTrue(any(e["process"] == "com.google.android.bluetooth" for e in result["system_crashes"]))
        self.assertFalse(any("bluetooth" in e["message"] for e in result["app_crashes"]))

    def test_java_process_line_after_interleaved_system_logs(self):
        raw = "\n".join([line(77, "AndroidRuntime", "FATAL EXCEPTION: main"),
            line(88, "AndroidRuntime", "Process: com.android.bluetooth, PID: 88"),
            line(77, "AndroidRuntime", f"Process: {APP}, PID: 77")])
        result = qa.classify_crashes(raw)
        self.assertFalse(result["passed"])
        self.assertEqual(APP, result["app_crashes"][0]["process"])

    def test_native_pid_child_process_and_pid_reuse(self):
        raw = "\n".join([line(600, "ActivityManager", f"Start proc 77:{APP}:worker/u0a120 for service"),
            line(77, "libc", "Fatal signal 6 (SIGABRT) in tid 78, pid 77 (truncated.name)"),
            line(600, "am_proc_died", f"[0,77,{APP}:worker,0,0]"),
            line(600, "ActivityManager", "Start proc 77:com.android.bluetooth/u1002 for service"),
            line(77, "libc", "Fatal signal 6 (SIGABRT) in tid 78, pid 77 (truncated.name)")])
        result = qa.classify_crashes(raw)
        self.assertEqual(1, len(result["app_crashes"]))
        self.assertEqual(1, len(result["system_crashes"]))
        self.assertEqual("com.android.bluetooth", result["system_crashes"][0]["process"])

    def test_tombstone_uses_victim_not_debug_reporter(self):
        result = qa.classify_crashes(line(999, "DEBUG", f"pid: 77, tid: 78, name: worker >>> {SALES} <<<"))
        self.assertFalse(result["passed"])
        self.assertEqual(77, result["app_crashes"][0]["pid"])

    def test_events_anr_exact_package_and_unknown_separate(self):
        raw = "\n".join([line(600, "am_crash", f"[0,77,{APP},0,Exception,boom]"),
            line(600, "am_anr", f"[0,78,{SALES}:worker,0,timeout]"),
            line(600, "ActivityManager", f"ANR in {SALES} (pid 78)"),
            line(600, "am_crash", f"[0,79,{APP}.other,0,Exception,mentions {APP}]"),
            line(80, "AndroidRuntime", "FATAL EXCEPTION: main"),
            line(81, "libc", "FATAL: no process identity")])
        result = qa.classify_crashes(raw)
        self.assertEqual(3, len(result["app_crashes"]))
        self.assertEqual(1, len(result["system_crashes"]))
        self.assertEqual(2, len(result["unattributed_crashes"]))
        self.assertFalse(qa.qa_process("com.singu.xfriend.mobile"))


class SetupTests(unittest.TestCase):
    def test_phone_missing_is_not_applicable_and_wifi_verified(self):
        evidence = {}
        missing = subprocess.CompletedProcess([], 20, "", "cmd: Can't find service: phone\n")
        with patch.object(qa, "adb", side_effect=["", "Wifi is disabled"]), patch.object(qa, "capture_command", return_value=missing):
            qa.configure_fixture_network(evidence)
        self.assertEqual("disabled", evidence["network_setup"]["wifi"]["status"])
        self.assertEqual("not_applicable", evidence["network_setup"]["mobile_data"]["status"])
        self.assertEqual(20, evidence["network_setup"]["mobile_data"]["returncode"])

    def test_other_mobile_data_error_is_not_swallowed(self):
        failure = subprocess.CompletedProcess([], 1, "", "permission denied")
        with patch.object(qa, "adb", side_effect=["", "Wi-Fi is disabled"]), patch.object(qa, "capture_command", return_value=failure):
            with self.assertRaises(RuntimeError): qa.configure_fixture_network({})

    def test_wifi_must_actually_be_disabled(self):
        clock = [0]
        def sleep(seconds): clock[0] += seconds
        with patch.object(qa, "adb", return_value="Wifi is enabled"), patch.object(qa, "capture_command") as mobile, \
                patch.object(qa.time, "monotonic", side_effect=lambda: clock[0]), patch.object(qa.time, "sleep", side_effect=sleep):
            with self.assertRaises(RuntimeError): qa.configure_fixture_network({})
            mobile.assert_not_called()

    def test_services_must_stay_ready_after_boot(self):
        clock = [0]
        def sleep(seconds): clock[0] += seconds
        def adb(*args, **kwargs):
            if args[1] == "getprop": return "running" if clock[0] < 6 else "stopped"
            if args[1] == "pidof": return "600"
            if args[1] == "service": return f"Service {args[-1]}: found"
            if args[1] == "pm": return "package:/system/framework/framework-res.apk"
            raise AssertionError(args)
        evidence = {}
        with patch.object(qa, "adb", side_effect=adb), patch.object(qa.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(qa.time, "sleep", side_effect=sleep):
            qa.wait_for_system_services(evidence)
        self.assertTrue(evidence["system_services"]["passed"])
        self.assertGreaterEqual(clock[0], 16)
        self.assertNotIn("phone", evidence["system_services"]["samples"][-1]["services"])

    def test_missing_required_service_times_out(self):
        clock = [0]
        def sleep(seconds): clock[0] += seconds
        with patch.object(qa, "adb", return_value="Service wifi: not found"), \
                patch.object(qa.time, "monotonic", side_effect=lambda: clock[0]), patch.object(qa.time, "sleep", side_effect=sleep):
            evidence = {}
            with self.assertRaises(TimeoutError): qa.wait_for_system_services(evidence, timeout=15)
        self.assertFalse(evidence["system_services"]["passed"])


if __name__ == "__main__":
    unittest.main()
