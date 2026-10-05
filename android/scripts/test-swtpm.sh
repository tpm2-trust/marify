#!/usr/bin/env bash
# Runs the app's session code and Go core against a tpm2-kira machine on a
# software TPM (swtpm), isolated from the regular test suite:
#
#   1. starts TestAppMachine (tpm2-kira cmd/attest_app_test.go, build tag
#      apptest) on the host: swtpm + the real tpmBackend, served over TCP
#   2. forwards its two ports into the emulator/phone with `adb reverse`
#   3. runs only app.kira.swtpm.SwtpmMachineSessionTest
#   4. stops the machine and removes the forwards
#
# Needs: go, swtpm, a device or emulator in `adb devices`.
# Environment: KIRA_SRC (tpm2-kira checkout, default ../../tpm2-kira),
#              KIRA_SWTPM_PORT (control port, default 7317; data = port + 1),
#              ANDROID_HOME (default ~/Android/Sdk).
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
src="$(cd "${KIRA_SRC:-$here/../../tpm2-kira}" && pwd)"
port="${KIRA_SWTPM_PORT:-7317}"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
log="$here/app/build/swtpm-machine.log"
mkdir -p "$(dirname "$log")"

for tool in go swtpm; do
    command -v "$tool" >/dev/null || { echo "$tool not found" >&2; exit 1; }
done
"$adb" get-state >/dev/null || { echo "no device or emulator connected" >&2; exit 1; }

echo "Starting the swtpm machine from $src (log: $log)"
(cd "$src" && KIRA_APPTEST_ADDR="127.0.0.1:$port" \
    go test -tags apptest -count=1 -v -timeout 60m -run '^TestAppMachine$' ./cmd) >"$log" 2>&1 &
machine=$!

cleanup() {
    { exec 3<>"/dev/tcp/127.0.0.1/$port" && echo quit >&3 && read -r -t 5 _ <&3; } 2>/dev/null || true
    sleep 1
    kill "$machine" 2>/dev/null || true
    wait "$machine" 2>/dev/null || true
    "$adb" reverse --remove "tcp:$port" 2>/dev/null || true
    "$adb" reverse --remove "tcp:$((port + 1))" 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 300); do   # the first run compiles the test binary
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && break
    kill -0 "$machine" 2>/dev/null || { echo "machine exited:"; cat "$log"; exit 1; }
    sleep 1
done
grep -m1 'ready' "$log" || true

"$adb" reverse "tcp:$port" "tcp:$port"
"$adb" reverse "tcp:$((port + 1))" "tcp:$((port + 1))"

cd "$here"
./gradlew connectedDebugAndroidTest -Pkira.swtpm \
    -Pandroid.testInstrumentationRunnerArguments.class=app.kira.swtpm.SwtpmMachineSessionTest \
    -Pandroid.testInstrumentationRunnerArguments.swtpm="$port"
