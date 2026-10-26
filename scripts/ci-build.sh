#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")/.."
chmod +x ./gradlew

emit_gradle_failure_annotations() {
  local phase="$1"
  local logfile="$2"

  grep -E "^e: |FAILURE:|^\* What went wrong:|^> |Caused by:|AssertionError|Execution failed for task|Missing class|AGPBI:|Could not resolve|Plugin \[id:" "$logfile" \
    | tail -n 30 \
    | while IFS= read -r line; do
        clean="${line//%/}"
        echo "::error title=${phase}::${clean}"
      done

  local tail_summary
  tail_summary=$(tail -n 45 "$logfile" | tr '\r\n' '  ' | cut -c 1-3500)
  echo "::error title=${phase} Tail::${tail_summary}"
}

echo "=== Environment Check ==="
java -version
echo "ANDROID_HOME=${ANDROID_HOME:-unset}"

if [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]; then
  yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
fi

echo "=== [1/2] Running JUnit4 Unit Tests Across All 11 Modules ==="
if ! ./gradlew testDebugUnitTest --stacktrace --no-daemon > /tmp/swag_test.log 2>&1; then
  emit_gradle_failure_annotations "GradleTest" /tmp/swag_test.log
  cat /tmp/swag_test.log
  exit 1
fi
tail -n 25 /tmp/swag_test.log

echo "=== [2/2] Assembling Debug & R8-Minified Signed Release APKs ==="
if ! ./gradlew assembleDebug assembleRelease --stacktrace --no-daemon > /tmp/swag_build.log 2>&1; then
  emit_gradle_failure_annotations "GradleBuild" /tmp/swag_build.log
  cat /tmp/swag_build.log
  exit 1
fi
tail -n 25 /tmp/swag_build.log

echo "=== Built APK Artifacts ==="
ls -lh app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk
