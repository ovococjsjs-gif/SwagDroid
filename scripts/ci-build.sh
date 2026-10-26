#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
chmod +x ./gradlew

echo "=== Environment Check ==="
java -version
echo "ANDROID_HOME=${ANDROID_HOME:-unset}"

# Accept any pending Android SDK licenses automatically if sdkmanager is available
if [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]; then
  yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
fi

echo "=== [1/2] Running JUnit4 Unit Tests Across All 11 Modules ==="
if ! ./gradlew testDebugUnitTest --stacktrace --no-daemon > /tmp/swag_test.log 2>&1; then
  ERR=$(grep -E "e: |FAILURE:|Caused by:|Execution failed|AssertionError|Unresolved reference" /tmp/swag_test.log | tail -n 20 | tr '\n' ' | ' || true)
  echo "::error title=Unit Test / Kotlin Error::${ERR:-See job log}"
  cat /tmp/swag_test.log
  exit 1
fi
tail -n 30 /tmp/swag_test.log

echo "=== [2/2] Assembling Debug & R8-Minified Signed Release APKs ==="
if ! ./gradlew assembleDebug assembleRelease --stacktrace --no-daemon > /tmp/swag_build.log 2>&1; then
  ERR=$(grep -E "e: |FAILURE:|Caused by:|Execution failed|Missing class|AGPBI:|Unresolved reference" /tmp/swag_build.log | tail -n 20 | tr '\n' ' | ' || true)
  echo "::error title=APK Assemble / R8 Error::${ERR:-See job log}"
  cat /tmp/swag_build.log
  exit 1
fi
tail -n 30 /tmp/swag_build.log

echo "=== Built APK Artifacts ==="
ls -lh app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk
