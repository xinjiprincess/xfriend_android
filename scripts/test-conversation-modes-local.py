"""Compile runtime + extracted VM and run JUnit using local caches, without Gradle/network.
Requires this checkout's existing compiled baseline for unchanged application dependencies.
This is a scoped JVM check, not an APK build or Android lifecycle test.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
BASE = ROOT / "app/build/tmp/kotlin-classes/xfriendDebug"
JARS = {p.name: p for p in CACHE.rglob("*.jar")}
def jar(name):
    return JARS[name]
def run(args):
    result = subprocess.run([str(a) for a in args], cwd=ROOT)
    if result.returncode: raise SystemExit(result.returncode)
compiler = [jar(n) for n in ["kotlin-compiler-embeddable-1.9.22.jar", "kotlin-stdlib-1.9.22.jar", "kotlin-script-runtime-1.9.22.jar", "kotlin-reflect-1.6.10.jar", "trove4j-1.0.20200330.jar", "annotations-13.0.jar"]]
deps = [jar(n) for n in ["kotlin-stdlib-1.9.22.jar", "kotlinx-coroutines-core-jvm-1.8.1.jar", "kotlinx-coroutines-test-jvm-1.8.1.jar", "json-20240303.jar", "okhttp-4.12.0.jar", "okio-jvm-3.6.0.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar", "mockwebserver-4.12.0.jar", "annotations-13.0.jar"]]
deps += [BASE, ROOT / "app/build/intermediates/javac/xfriendDebug/compileXfriendDebugJavaWithJavac/classes", Path.home() / "AppData/Local/Android/Sdk/platforms/android-34/android.jar"]
source = ROOT / "app/src/main/java/com/singu/xfriend"
tests = ROOT / "app/src/test/java/com/singu/xfriend"
names = ["ConversationModeTest", "AgentRuntimeTest", "AgentRuntimeCheckpointTest", "AgentRuntimeSalesContextTest", "FriendRunQueueTest", "FriendRunCheckpointTest", "FriendRunRuntimeIntegrationTest", "FriendRunRegistryTest", "FriendRunWorkerTest", "FriendRunCryptoTest", "FriendModelClientTest"]
with tempfile.TemporaryDirectory(prefix="xfriend-mode-test-") as folder:
    temp = Path(folder)
    for group, artifact, version in [("androidx.compose.runtime", "runtime-android", "1.6.8"), ("androidx.lifecycle", "lifecycle-viewmodel-android", "2.8.3")]:
        aar = next((CACHE / group / artifact / version).rglob("*.aar"))
        dest = temp / (artifact + ".jar")
        with zipfile.ZipFile(aar) as archive:
            dest.write_bytes(archive.read("classes.jar"))
        deps.append(dest)
    main = (source / "MainActivity.kt").read_text(encoding="utf-8")
    vm = temp / "FriendViewModel.kt"
    vm.write_text("""package com.singu.xfriend
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.util.UUID
""" + main[main.index("class FriendViewModel("):main.index("\n@Composable\ninternal fun XfriendApp")], encoding="utf-8")
    files = [source / (n + ".kt") for n in ["FriendModelClient", "ConversationMode", "AgentRuntime", "AgentRuntimeCheckpoint", "Store", "FriendStoreRegistry", "FriendRun", "FriendRunQueue", "FriendRunRepository", "FriendRunService", "FriendRunCheckpoint", "FriendRunWorker", "FriendRunLiveness", "FriendRunCrypto"]]
    files += [vm] + [tests / (n + ".kt") for n in names]
    out = temp / "classes"
    cp = os.pathsep.join(map(str, deps))
    run(["java", "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-Xfriend-paths=" + str(BASE), "-cp", cp, "-d", out, *files])
    run(["java", "-cp", str(out) + os.pathsep + cp, "org.junit.runner.JUnitCore", *["com.singu.xfriend." + n for n in names]])
