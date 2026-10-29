#!/usr/bin/env bash
# Every Android test of the app, from a defined state, with nobody at the
# keyboard:
#
#   1. the Go core from the tpm2-kira commit in ../core.properties
#      (scripts/build-core.sh)
#   2. the JVM unit tests, lint, and the release APK without kiratest
#   3. a fresh emulator: the AVD marify-test (API 36, google_apis, x86_64),
#      made if missing; its data wiped, cold boot, no snapshot, no window,
#      on its own port - an emulator already running is left alone
#   4. the screen lock the keystore tests need (PIN 1234), set on the fresh
#      data, so the device is unlocked; animations off
#   5. the instrumented tests (connectedDebugAndroidTest)
#   6. the session code and the core against a tpm2-kira machine on swtpm
#      (scripts/test-swtpm.sh)
#   7. a summary from the JUnit results; the emulator is stopped
#
# The exit status is 0 only when every step passed. Results and logs:
# app/build/test-emulator/.
#
# Options:  --no-swtpm       leave out step 6 (no go/swtpm on this host)
#           --no-core        use the core in app/libs as it is (CORE_COMMIT)
#           --keep-emulator  leave the emulator running afterwards
# Environment: JAVA_HOME (default /usr/lib/jvm/java-25-openjdk),
#              ANDROID_HOME (default ~/Android/Sdk), KIRA_SRC (tpm2-kira
#              checkout, default ../../tpm2-kira), MARIFY_TEST_PORT (the
#              emulator's console port, default 5580).
set -euo pipefail

swtpm=true core=true keep=false
for arg in "$@"; do
    case "$arg" in
    --no-swtpm) swtpm=false ;;
    --no-core) core=false ;;
    --keep-emulator) keep=true ;;
    *) echo "unknown option $arg (--no-swtpm, --no-core, --keep-emulator)" >&2; exit 2 ;;
    esac
done

here="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"
sdk="$ANDROID_HOME"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
avdmanager="$sdk/cmdline-tools/latest/bin/avdmanager"
apkanalyzer="$sdk/cmdline-tools/latest/bin/apkanalyzer"

avd=marify-test
image="system-images;android-36;google_apis;x86_64"
port="${MARIFY_TEST_PORT:-5580}"
# Every adb and Gradle device command goes to this emulator alone.
export ANDROID_SERIAL="emulator-$port"
pin=1234

out="$here/app/build/test-emulator"
rm -rf "$out"
mkdir -p "$out"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { echo "test-emulator: $*" >&2; exit 1; }

for tool in "$adb" "$emulator" "$avdmanager" "$apkanalyzer" "$JAVA_HOME/bin/javac"; do
    [[ -x "$tool" ]] || die "$tool not found (ANDROID_HOME, JAVA_HOME)"
done
[[ -d "$sdk/${image//;//}" ]] || die "no system image $image: sdkmanager '$image'"
[[ -r /dev/kvm && -w /dev/kvm ]] || die "/dev/kvm is not usable: the emulator needs KVM"
if $swtpm; then
    for tool in go swtpm; do command -v "$tool" >/dev/null || die "$tool not found (or --no-swtpm)"; done
fi
"$adb" -s "$ANDROID_SERIAL" get-state >/dev/null 2>&1 && die "$ANDROID_SERIAL is running already: stop it, or set MARIFY_TEST_PORT"

failed=()
# run NAME CMD...: one step; its output into NAME.log, a failure noted.
run() {
    local name=$1
    shift
    if "$@" >"$out/$name.log" 2>&1; then
        echo "ok    $name"
    else
        echo "FAIL  $name (app/build/test-emulator/$name.log)"
        tail -20 "$out/$name.log" | sed 's/^/      /'
        failed+=("$name")
    fi
}
# keep_results NAME: the instrumented results of the last run, kept under NAME.
keep_results() {
    mkdir -p "$out/$1"
    cp -r "$here/app/build/outputs/androidTest-results/connected/." "$out/$1/" 2>/dev/null || true
}

if $core; then
    step "1. the Go core (core.properties)"
    run build-core "$here/scripts/build-core.sh"
    ((${#failed[@]} == 0)) || die "no core, no app"
fi
echo "core: tpm2-kira $(cat "$here/app/libs/CORE_COMMIT" 2>/dev/null || echo '?')"

cd "$here"
step "2. JVM unit tests, lint, the release APK"
run unit-lint ./gradlew --quiet testDebugUnitTest lintDebug lintRelease assembleRelease assembleDebug
mkdir -p "$out/unit"
cp app/build/test-results/testDebugUnitTest/*.xml "$out/unit/" 2>/dev/null || true
release_kiratest="$("$apkanalyzer" dex packages app/build/outputs/apk/release/app-release-unsigned.apk | grep -c kiratest || true)"
if [[ $release_kiratest == 0 ]]; then
    echo "ok    no kiratest in the release APK"
else
    echo "FAIL  the release APK carries kiratest ($release_kiratest entries)"
    failed+=("release-kiratest")
fi

step "3. a fresh emulator ($avd, $image, port $port)"
if ! "$emulator" -list-avds | grep -qx "$avd"; then
    echo no | "$avdmanager" create avd --name "$avd" --package "$image" --device pixel_6 >"$out/avd-create.log" 2>&1 ||
        die "the AVD could not be made (avd-create.log)"
    echo "made the AVD $avd"
fi
"$emulator" -avd "$avd" -port "$port" -wipe-data -no-snapshot -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect >"$out/emulator.log" 2>&1 &
emu=$!
stop_emulator() {
    $keep && return 0
    "$adb" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do kill -0 "$emu" 2>/dev/null || return 0; sleep 1; done
    kill "$emu" 2>/dev/null || true
}
trap stop_emulator EXIT
timeout 300 "$adb" wait-for-device || die "the emulator did not come up (emulator.log)"
booted=""
for _ in $(seq 1 300); do
    booted="$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [[ $booted == 1 ]] && break
    kill -0 "$emu" 2>/dev/null || die "the emulator exited (emulator.log)"
    sleep 1
done
[[ $booted == 1 ]] || die "the emulator did not finish booting in 300 s"
echo "booted: API $("$adb" shell getprop ro.build.version.sdk | tr -d '\r')"

step "4. screen lock and animations"
"$adb" shell locksettings set-pin "$pin" >/dev/null
# The user's storage unlocks some moments after the boot completes: wait
# for that state, not a time.
unlocked_storage=false
for _ in $(seq 1 60); do
    if "$adb" shell 'dumpsys user' | grep -q RUNNING_UNLOCKED; then unlocked_storage=true; break; fi
    sleep 1
done
$unlocked_storage || die "the user's storage is still locked 60 s after setting the PIN"
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb" shell settings put global "$s" 0
done
# The display stays on, so the lock screen does not come back; then it is
# dismissed with the PIN, as a person would. With the lock screen up the
# app's activities do not come to the front and the keystore refuses keys
# that need an unlocked device.
"$adb" shell svc power stayon true
"$adb" shell settings put system screen_off_timeout 2147483647
keyguard_showing() { "$adb" shell dumpsys window | grep -qE 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true|KeyguardShowing=true'; }
unlocked=false
for _ in 1 2 3 4 5; do
    "$adb" shell input keyevent KEYCODE_WAKEUP
    "$adb" shell wm dismiss-keyguard
    sleep 1
    "$adb" shell input text "$pin"
    "$adb" shell input keyevent KEYCODE_ENTER
    sleep 2
    if ! keyguard_showing; then unlocked=true; break; fi
done
$unlocked || die "the lock screen does not go away"
"$adb" shell dumpsys window | grep -E 'mCurrentFocus' | sed 's/^ */focus: /'
echo "PIN $pin set, the lock screen dismissed, the display stays on, animations off"

step "5. instrumented tests"
rm -rf app/build/outputs/androidTest-results
run instrumented ./gradlew --quiet connectedDebugAndroidTest
keep_results instrumented

if $swtpm; then
    step "6. against a tpm2-kira machine on swtpm"
    rm -rf app/build/outputs/androidTest-results
    run swtpm "$here/scripts/test-swtpm.sh"
    keep_results swtpm
    cp app/build/swtpm-machine.log "$out/swtpm/" 2>/dev/null || true
fi

step "7. summary"
python3 - "$out" <<'EOF'
import glob, os, sys, xml.etree.ElementTree as ET
out = sys.argv[1]
for suite in ("unit", "instrumented", "swtpm"):
    files = glob.glob(os.path.join(out, suite, "**", "*.xml"), recursive=True)
    if not files:
        continue
    n = bad = skipped = 0
    for f in files:
        for tc in ET.parse(f).getroot().iter("testcase"):
            n += 1
            if tc.find("failure") is not None or tc.find("error") is not None:
                bad += 1
                print(f"  FAIL {suite}: {tc.get('classname', '').split('.')[-1]}.{tc.get('name')}")
            elif tc.find("skipped") is not None:
                skipped += 1
    print(f"{suite:13} {n:3} tests, {bad} failed, {skipped} skipped")
EOF
if ((${#failed[@]})); then
    echo "FAILED: ${failed[*]} - app/build/test-emulator/"
    exit 1
fi
echo "ALL PASS - app/build/test-emulator/"
