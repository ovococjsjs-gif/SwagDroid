#!/usr/bin/env bash
# A disposable CI emulator only. Never selects a connected physical device.
set -Eeuo pipefail
cd "$(dirname "$0")/.."

if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo "This script is restricted to a disposable GitHub Actions runner. It must not target a user's phone." >&2
  exit 2
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK" ]] || { echo "::error title=Android smoke::Android SDK is unavailable"; exit 1; }
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
IMAGE="system-images;android-35;google_apis;x86_64"
AVD="swagdroid-ci-api35"
SERIAL="emulator-5554"
OUTPUT="$PWD/build/android-smoke"
mkdir -p "$OUTPUT"
emulator_pid=""

fail() { echo "::error title=Android smoke::$*"; exit 1; }

collect_and_stop() {
  local status=$?
  trap - EXIT INT TERM ERR
  if [[ -n "$emulator_pid" ]]; then
    if [[ "$(timeout 5 "$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" == "device" ]]; then
      timeout 10 "$ADB" -s "$SERIAL" logcat -b crash -d > "$OUTPUT/crash-logcat.txt" 2>&1 || true
      timeout 10 "$ADB" -s "$SERIAL" shell dumpsys activity activities > "$OUTPUT/activities.txt" 2>&1 || true
      timeout 10 "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUTPUT/final-screen.png" 2>/dev/null || true
      timeout 10 "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    fi
    if kill -0 "$emulator_pid" 2>/dev/null; then kill "$emulator_pid" 2>/dev/null || true; fi
    wait "$emulator_pid" 2>/dev/null || true
  fi
  if [[ "$status" -ne 0 ]]; then
    python3 - "$OUTPUT" <<'PYDIAG'
import sys
from pathlib import Path
root=Path(sys.argv[1])
# Log download hosts may be unavailable to reviewers; publish bounded diagnostic text
# through the ordinary check annotations instead of reporting only a generic timeout.
for name in ("emulator.log", "emulator-version.txt", "acceleration.txt", "crash-logcat.txt", "avd.log", "host-packages.log", "sdk-install.log", "tests.log"):
    path=root/name
    if not path.exists(): continue
    text="\n".join(path.read_text(errors="replace").splitlines()[-40:])[-3200:]
    if text:
        escaped=text.replace("%","%25").replace("\r","%0D").replace("\n","%0A")
        print(f"::warning title=Android diagnostics {name}::{escaped}")
PYDIAG
  fi
  exit "$status"
}
trap collect_and_stop EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'echo "::error title=Android smoke shell::Unexpected exit at script line $LINENO (status $?)"' ERR

# Qt's version probe and headless rendering must not require an X server.
export QT_QPA_PLATFORM=offscreen

echo "=== Compile instrumentation APKs before downloading/booting the emulator ==="
if ! ./gradlew :app:assembleDebugAndroidTest :core:vault:assembleDebugAndroidTest --stacktrace --no-daemon > "$OUTPUT/compile.log" 2>&1; then
  grep -E '^e: |FAILED|What went wrong:|Execution failed' "$OUTPUT/compile.log" | head -n 35 || true
  python3 - "$OUTPUT/compile.log" <<'PY'
import sys
from pathlib import Path
lines=Path(sys.argv[1]).read_text(errors="replace").splitlines()
text="\n".join(line for line in lines if line.startswith("e: ") or "Execution failed" in line or line.startswith("FAILURE:"))[:12000]
print("::error title=Android test compilation::"+text.replace("%","%25").replace("\n","%0A").replace("\r","%0D"))
PY
  tail -n 70 "$OUTPUT/compile.log"
  exit 1
fi

[[ -e /dev/kvm ]] || fail "KVM is unavailable; Android runtime tests cannot be claimed as passed"
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  sudo -n chmod 666 /dev/kvm || fail "Cannot enable KVM access on the disposable CI runner"
fi

echo "=== Native headless emulator dependencies ==="
if ! (sudo -n apt-get update -q && sudo -n apt-get install -y -q   libgl1 libpulse0 libnss3 libxcomposite1 libxcursor1 libxrandr2 libxi6 libxtst6   libxkbcommon0 libxkbcommon-x11-0 libxcb-cursor0 libasound2t64) > "$OUTPUT/host-packages.log" 2>&1; then
  fail "Native emulator dependency installation failed"
fi

echo "=== Install Android 15 / API 35 emulator image ==="
if ! timeout 900 "$SDKMANAGER" "platform-tools" "emulator" "$IMAGE" > "$OUTPUT/sdk-install.log" 2>&1; then
  tail -n 70 "$OUTPUT/sdk-install.log"
  fail "SDK/image installation failed"
fi
"$EMULATOR" -accel-check > "$OUTPUT/acceleration.txt" 2>&1 || { cat "$OUTPUT/acceleration.txt"; fail "Emulator acceleration check failed"; }
# Some emulator versions return non-zero for this informational flag. Do not turn
# a version probe into a smoke failure; acceleration/boot/tests are the actual gates.
"$EMULATOR" -version > "$OUTPUT/emulator-version.txt" 2>&1 || true
python3 - "$OUTPUT" <<'PYENV'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
data={name: (root/name).read_text(errors="replace")[:1500] for name in ("acceleration.txt","emulator-version.txt")}
print("::notice title=Android smoke tooling::"+json.dumps(data).replace("%","%25"))
PYENV

"$ADB" start-server >/dev/null
# connectedAndroidTest discovers devices. Refuse to run if anything is already connected.
[[ -z "$("$ADB" devices | awk 'NR>1 && NF {print $1}')" ]] || fail "Runner already has an ADB device; refusing to touch it"
printf 'no\n' | "$AVDMANAGER" create avd --force --name "$AVD" --package "$IMAGE" --device pixel_5 > "$OUTPUT/avd.log" 2>&1

"$EMULATOR" -avd "$AVD" -port 5554 -no-window -noaudio -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect -memory 2048 -cores 2 -camera-back none -camera-front none \
  > "$OUTPUT/emulator.log" 2>&1 &
emulator_pid=$!
export ANDROID_SERIAL="$SERIAL"

connected=false
for attempt in $(seq 1 90); do
  kill -0 "$emulator_pid" 2>/dev/null || fail "Emulator process exited before connecting to ADB"
  if [[ "$(timeout 5 "$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" == "device" ]]; then
    connected=true
    break
  fi
  sleep 2
done
[[ "$connected" == "true" ]] || fail "Emulator did not connect to ADB"
booted=false
for attempt in $(seq 1 120); do
  if [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    booted=true
    break
  fi
  kill -0 "$emulator_pid" 2>/dev/null || fail "Emulator exited during boot"
  sleep 2
done
[[ "$booted" == "true" ]] || fail "Android did not finish booting"
[[ "$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')" == "$SERIAL" ]] || fail "Unexpected ADB device set"

hardware="$("$ADB" -s "$SERIAL" shell getprop ro.hardware | tr -d '\r')"
[[ "$hardware" == *ranchu* || "$hardware" == *goldfish* ]] || fail "Not the disposable emulator we expected"
# Changes below affect only this fresh test emulator, never a physical device.
"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0
"$ADB" -s "$SERIAL" shell settings put global device_provisioned 1
"$ADB" -s "$SERIAL" shell settings put secure user_setup_complete 1
"$ADB" -s "$SERIAL" shell input keyevent 82
"$ADB" -s "$SERIAL" logcat -c

for prop in ro.build.version.sdk ro.build.version.release ro.product.cpu.abi ro.hardware ro.build.fingerprint; do
  printf '%s=%s\n' "$prop" "$("$ADB" -s "$SERIAL" shell getprop "$prop" | tr -d '\r')"
done > "$OUTPUT/device.txt"
python3 - "$OUTPUT/device.txt" <<'PY'
import json,sys
from pathlib import Path
text=Path(sys.argv[1]).read_text()
data=dict(line.split("=",1) for line in text.splitlines() if "=" in line)
assert data["ro.build.version.sdk"]=="35",data
assert data["ro.product.cpu.abi"]=="x86_64",data
print("::notice title=Android smoke device::"+json.dumps(data).replace("%","%25"))
PY

echo "=== Install and cold-launch the actual Debug APK ==="
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk > "$OUTPUT/install.log" 2>&1
"$ADB" -s "$SERIAL" shell am start -W -n com.swagdroid.debug/com.swagdroid.MainActivity > "$OUTPUT/launch.txt"
grep -q 'Status: ok' "$OUTPUT/launch.txt" || { cat "$OUTPUT/launch.txt"; fail "Cold launch failed"; }

# Remove old XML, not application data, to ensure a failed task cannot reuse previous results.
python3 - <<'PY'
from pathlib import Path
for module in (Path("app"),Path("core/vault")):
    for xml in (module/"build/outputs/androidTest-results/connected").glob("**/TEST-*.xml"):
        xml.unlink()
PY

echo "=== Real Android UI and Room migration tests ==="
if ! timeout 900 ./gradlew :core:vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
  --stacktrace --no-daemon > "$OUTPUT/tests.log" 2>&1; then
  python3 scripts/summarize-junit.py --kind android --allow-empty || true
  tail -n 100 "$OUTPUT/tests.log"
  fail "Connected instrumentation task failed; see Android test annotations and logs"
fi
python3 scripts/summarize-junit.py --kind android --min-tests 8 --require-module app --require-module core:vault --fail-on-skip

"$ADB" -s "$SERIAL" exec-out run-as com.swagdroid.debug cat files/swag_last_crash.log > "$OUTPUT/app-crash.txt" 2>/dev/null || true
if grep -q 'Offline Crash Black-Box' "$OUTPUT/app-crash.txt"; then
  head -n 60 "$OUTPUT/app-crash.txt"
  fail "Application crash record was produced during the smoke run"
fi

echo "::notice title=Android smoke completed::API 35 x86_64; Debug APK installed and launched; required UI and Room cases completed without skips. No Root/Shizuku or physical-phone coverage claimed."
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo '## Android smoke environment'
    echo '- Fresh Android 15 (API 35), x86_64 emulator with KVM.'
    echo '- Debug APK installed and cold-launched; Compose UI and real Android Room migration tests executed.'
    echo '- Only synthetic files and an isolated test DB were used; no cleanup deletion was confirmed.'
    echo '- This does not validate a physical phone, Shizuku/Root, or production signing.'
  } >> "$GITHUB_STEP_SUMMARY"
fi
