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
# New command-line tools and emulator builds may use different default AVD homes.
# Give both an explicit disposable directory; never rely on ~/.android discovery.
# Do not change ANDROID_USER_HOME: AGP also uses it for the debug keystore.
# Main APK and test APK must keep the same signing identity across Gradle calls.
export ANDROID_EMULATOR_HOME="${RUNNER_TEMP:-/tmp}/swagdroid-emulator-home"
export ANDROID_AVD_HOME="$ANDROID_EMULATOR_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
AVD_PATH="$ANDROID_AVD_HOME/$AVD.avd"
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
for name in ("instrumentation-app.txt", "instrumentation-vault.txt", "installed-runners.txt", "test-install.log", "app-signature.txt", "app-test-signature.txt", "emulator.log", "emulator-version.txt", "acceleration.txt", "crash-logcat.txt", "avd.log", "avd-list.txt", "host-packages.log", "sdk-install.log", "tests.log"):
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
if ! ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:vault:assembleDebugAndroidTest --stacktrace --no-daemon > "$OUTPUT/compile.log" 2>&1; then
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
installed=false
: > "$OUTPUT/sdk-install.log"
for attempt in 1 2 3; do
  if timeout --kill-after=10s 900 "$SDKMANAGER" "platform-tools" "emulator" "$IMAGE" > "$OUTPUT/sdk-attempt-$attempt.log" 2>&1; then
    cat "$OUTPUT/sdk-attempt-$attempt.log" >> "$OUTPUT/sdk-install.log"
    installed=true
    break
  fi
  cat "$OUTPUT/sdk-attempt-$attempt.log" >> "$OUTPUT/sdk-install.log"
  if [[ "$attempt" -ge 3 ]] || ! grep -Eqi 'ZipFile|archive|Network|timed out|connection|SSL' "$OUTPUT/sdk-attempt-$attempt.log"; then break; fi
  echo "::warning title=Android SDK download retry::Package download/archive failure on attempt $attempt; retrying SDK installation only. Test failures are never retried or ignored."
  # Failed package-operation staging only, on this disposable runner. Keep installed SDKs.
  if [[ -d "$SDK/.temp" ]]; then
    find "$SDK/.temp" -mindepth 1 -maxdepth 1 -type d -name 'PackageOperation*' -exec rm -rf -- {} +
  fi
  sleep 2
done
[[ "$installed" == true ]] || fail "SDK/image installation failed after bounded download retries"
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
printf 'no\n' | "$AVDMANAGER" create avd --force --name "$AVD" --package "$IMAGE" --device pixel_5 --path "$AVD_PATH" > "$OUTPUT/avd.log" 2>&1
[[ -s "$AVD_PATH/config.ini" ]] || fail "avdmanager did not create the requested AVD configuration"
# This is the standard AVD index, pointing only to the real avdmanager-produced config.
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-35\n' "$AVD_PATH" > "$ANDROID_AVD_HOME/$AVD.ini"
"$EMULATOR" -list-avds > "$OUTPUT/avd-list.txt" 2>&1
grep -Fxq "$AVD" "$OUTPUT/avd-list.txt" || fail "The emulator cannot discover its explicitly created AVD"
echo "::notice title=Android AVD::Real API 35 AVD configuration created and discovered in the explicit CI-only directory."

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

echo "=== Verify APK signing identity before installing tests ==="
mapfile -t app_tests < <(find app/build/outputs/apk/androidTest -name '*.apk' -type f)
mapfile -t vault_tests < <(find core/vault/build/outputs/apk/androidTest -name '*.apk' -type f)
[[ "${#app_tests[@]}" == 1 && "${#vault_tests[@]}" == 1 ]] || fail "Expected one instrumentation APK for each smoke module"
APKSIGNER="$(find "$SDK/build-tools" -maxdepth 2 -type f -name apksigner | sort -V | tail -n 1)"
[[ -x "$APKSIGNER" ]] || fail "apksigner is unavailable"
"$APKSIGNER" verify --print-certs app/build/outputs/apk/debug/app-debug.apk > "$OUTPUT/app-signature.txt" 2>&1
"$APKSIGNER" verify --print-certs "${app_tests[0]}" > "$OUTPUT/app-test-signature.txt" 2>&1
python3 scripts/verify-apk-signatures.py --app "$OUTPUT/app-signature.txt" \
  --test "$OUTPUT/app-test-signature.txt" --output "$OUTPUT/matching-certificate.txt"

echo "=== Install and cold-launch the actual Debug APK ==="
"$ADB" -s "$SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk > "$OUTPUT/install.log" 2>&1
"$ADB" -s "$SERIAL" shell am start -W -n com.swagdroid.debug/com.swagdroid.MainActivity > "$OUTPUT/launch.txt"
grep -q 'Status: ok' "$OUTPUT/launch.txt" || { cat "$OUTPUT/launch.txt"; fail "Cold launch failed"; }

echo "=== Install the already compiled instrumentation APKs ==="
"$ADB" -s "$SERIAL" install -r -t "${vault_tests[0]}" > "$OUTPUT/test-install.log" 2>&1
"$ADB" -s "$SERIAL" install -r -t "${app_tests[0]}" >> "$OUTPUT/test-install.log" 2>&1
"$ADB" -s "$SERIAL" shell pm list instrumentation > "$OUTPUT/installed-runners.txt"
python3 - "$OUTPUT" <<'PYRUNNERS'
import json,re,sys
from pathlib import Path
root=Path(sys.argv[1])
rows=re.findall(r"instrumentation:(\S+) \(target=(\S+)\)", (root/"installed-runners.txt").read_text())
app=[component for component,target in rows if target=="com.swagdroid.debug" and component.endswith("/androidx.test.runner.AndroidJUnitRunner")]
vault=[component for component,target in rows if component.startswith("com.swagdroid.core.vault.") and component.endswith("/androidx.test.runner.AndroidJUnitRunner")]
assert len(app)==1 and len(vault)==1, rows
(root/"app-runner.txt").write_text(app[0])
(root/"vault-runner.txt").write_text(vault[0])
print("::notice title=Android runners::"+json.dumps({"app":app[0],"core:vault":vault[0],"transport":"adb am instrument; stock AndroidJUnitRunner","matchingDebugCertificateSha256":(root/"matching-certificate.txt").read_text()}))
PYRUNNERS

# Isolated XML output from the real runner protocol, distinct from Gradle UTP reports.
python3 - <<'PYCLEAN'
from pathlib import Path
for module in (Path("app"),Path("core/vault")):
    for xml in (module/"build/outputs/androidTest-results/adb-smoke").glob("**/TEST-*.xml"):
        xml.unlink()
PYCLEAN

echo "=== Real AndroidJUnitRunner UI and Room tests (ADB, no host UTP dependency) ==="
failed=0
if ! timeout 600 "$ADB" -s "$SERIAL" shell am instrument -w -r -e debug false   -e class com.swagdroid.core.vault.VaultMigrationInstrumentedTest   "$(cat "$OUTPUT/vault-runner.txt")" > "$OUTPUT/instrumentation-vault.txt" 2>&1; then failed=1; fi
if ! python3 scripts/instrumentation-to-junit.py --input "$OUTPUT/instrumentation-vault.txt"   --output core/vault/build/outputs/androidTest-results/adb-smoke/TEST-VaultMigrationInstrumentedTest.xml   --class-name com.swagdroid.core.vault.VaultMigrationInstrumentedTest --min-tests 4; then failed=1; fi

if ! timeout 600 "$ADB" -s "$SERIAL" shell am instrument -w -r -e debug false   -e class com.swagdroid.BasicSmokeTest   "$(cat "$OUTPUT/app-runner.txt")" > "$OUTPUT/instrumentation-app.txt" 2>&1; then failed=1; fi
if ! python3 scripts/instrumentation-to-junit.py --input "$OUTPUT/instrumentation-app.txt"   --output app/build/outputs/androidTest-results/adb-smoke/TEST-BasicSmokeTest.xml   --class-name com.swagdroid.BasicSmokeTest --min-tests 8; then failed=1; fi

if ! python3 scripts/summarize-junit.py --kind android --android-source adb-smoke --min-tests 12   --require-module app --require-module core:vault --fail-on-skip; then failed=1; fi
[[ "$failed" == 0 ]] || fail "Real Android instrumentation failed or was incomplete; no pass is inferred from compilation"

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
    echo '- Only synthetic files and an isolated test DB were used; deletion was confirmed solely for explicitly selected test fixtures.'
    echo '- This does not validate a physical phone, Shizuku/Root, or production signing.'
  } >> "$GITHUB_STEP_SUMMARY"
fi
