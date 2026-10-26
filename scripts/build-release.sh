#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo "=== [1/3] Running JUnit4 unit tests across all 11 SwagDroid modules ==="
./gradlew testDebugUnitTest --stacktrace

echo "=== [2/3] Building Debug APK (com.swagdroid.debug) ==="
./gradlew assembleDebug --stacktrace

echo "=== [3/3] Building R8-minified Release APK (com.swagdroid v1.0.0) ==="
./gradlew assembleRelease --stacktrace

echo ""
echo "✅ SwagDroid v1.0.0 Full Build Succeeded!"
echo "   Release APK: app/build/outputs/apk/release/app-release.apk"
echo "   Debug APK:   app/build/outputs/apk/debug/app-debug.apk"
