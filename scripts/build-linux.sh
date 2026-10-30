#!/usr/bin/env bash
# Build and verify both Android products without writing caches outside this checkout.
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

export JAVA_HOME="${JAVA_HOME:-$project_root/.artifacts/android-toolchain/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$project_root/.artifacts/android-toolchain/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_root/.artifacts/gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$project_root/.artifacts/android-user}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

if [[ ! -x "$JAVA_HOME/bin/javac" || ! -f "$ANDROID_HOME/platforms/android-34/android.jar" ]]; then
    echo 'Requires JDK 17+ and Android SDK 34. Set JAVA_HOME and ANDROID_HOME.' >&2
    exit 1
fi
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

if (( $# == 0 )); then
    set -- :app:testXfriendDebugUnitTest :app:testXsalersDebugUnitTest \
        :app:assembleXfriendDebug :app:assembleXsalersDebug \
        :app:lintXfriendDebug :app:lintXsalersDebug
fi
bash ./gradlew --no-daemon --max-workers=2 \
    -Pkotlin.compiler.execution.strategy=in-process -PsalesUiFixture=false \
    --console=plain "$@"
