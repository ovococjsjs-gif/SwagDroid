#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")/.."
chmod +x ./gradlew

emit_gradle_failure_annotations() {
  local phase="$1"
  local logfile="$2"

  # Slot 1: All Kotlin/KSP/R8/AGP compiler errors (`e: ...`) & FAILED tasks
  local errs
  errs=$(grep -E "^e: |FAILED$|^FAILURE:|^\* What went wrong:|Execution failed for task|AssertionError|Unresolved reference|Missing class|AGPBI:" "$logfile" \
    | grep -v "^> Task " \
    | head -n 40 \
    | tr '\r\n%' '   ' \
    | cut -c 1-3800 || true)
  echo "::error title=${phase} Errors::${errs:-No e: lines}"

  # Slot 2: Context around `* What went wrong:` (25 lines after)
  local wrong
  wrong=$(grep -A 25 "^\* What went wrong:" "$logfile" | head -n 28 | tr '\r\n%' '   ' | cut -c 1-3800 || true)
  echo "::error title=${phase} WhatWentWrong::${wrong:-None}"

  # Slot 3: Any unit test failures (lines around `FAILED` in test output)
  local tests
  tests=$(grep -B 2 -A 8 " FAILED$" "$logfile" | grep -v "^> Task " | head -n 30 | tr '\r\n%' '   ' | cut -c 1-3800 || true)
  if [ -n "$tests" ]; then
    echo "::error title=${phase} FailedTests::${tests}"
  fi
}

echo "=== Environment Check ==="
java -version
echo "ANDROID_HOME=${ANDROID_HOME:-unset}"

if [ -n "${ANDROID_HOME:-}" ] && [ -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]; then
  yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
fi

echo "=== Verifying non-destructive Vault migration SQL ==="
python3 scripts/verify-vault-migration.py || exit 1
python3 -m unittest discover -s scripts/tests || exit 1

echo "=== [1/3] Running JUnit4 Unit Tests Across All 11 Modules ==="
if ! ./gradlew testDebugUnitTest --continue --stacktrace --no-daemon > /tmp/swag_test.log 2>&1; then
  emit_gradle_failure_annotations "GradleTest" /tmp/swag_test.log
  python3 scripts/summarize-junit.py --allow-empty || true
  cat /tmp/swag_test.log
  exit 1
fi
tail -n 25 /tmp/swag_test.log
python3 scripts/summarize-junit.py || exit 1

echo "=== [2/3] Assembling Debug & R8-Minified Signed Release APKs ==="
if ! ./gradlew assembleDebug assembleRelease --continue --stacktrace --no-daemon > /tmp/swag_build.log 2>&1; then
  emit_gradle_failure_annotations "GradleBuild" /tmp/swag_build.log
  cat /tmp/swag_build.log
  exit 1
fi
tail -n 25 /tmp/swag_build.log

echo "=== Built APK Artifacts ==="
ls -lh app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk

if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
  echo "=== [3/3] Android 15 emulator UI + Room smoke ==="
  bash scripts/android-smoke.sh || exit 1
else
  echo "Android smoke NOT run: scripts/android-smoke.sh requires a disposable CI runner."
fi
