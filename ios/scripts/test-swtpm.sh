#!/usr/bin/env bash
# Runs the app's session code and Go core against a tpm2-kira machine on a
# software TPM (swtpm), isolated from the regular test suite:
#
#   1. starts TestAppMachine (tpm2-kira cmd/attest_app_test.go, build tag
#      apptest) on this Mac: swtpm + the real tpmBackend, served over TCP
#   2. runs only MarifyTests/SwtpmMachineSessionTest in the simulator, which
#      shares the Mac's network, with MARIFY_SWTPM set to the control port
#   3. stops the machine
#
# Needs: go, swtpm (brew install swtpm), Xcode with an iOS simulator,
# Marify.xcodeproj (xcodegen generate) and the frameworks (build-core.sh).
# Environment: KIRA_SRC (tpm2-kira checkout, default ../../tpm2-kira),
#              KIRA_SWTPM_PORT (control port, default 7317; data = port + 1),
#              MARIFY_SIMULATOR (destination name, default "iPhone 16").
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
src="$(cd "${KIRA_SRC:-$here/../../tpm2-kira}" && pwd)"
port="${KIRA_SWTPM_PORT:-7317}"
sim="${MARIFY_SIMULATOR:-iPhone 16}"
log="$here/build/swtpm-machine.log"
mkdir -p "$(dirname "$log")"

for tool in go swtpm xcodebuild; do
    command -v "$tool" >/dev/null || { echo "$tool not found" >&2; exit 1; }
done

echo "Starting the swtpm machine from $src (log: $log)"
(cd "$src" && KIRA_APPTEST_ADDR="127.0.0.1:$port" \
    go test -tags apptest -count=1 -v -timeout 60m -run '^TestAppMachine$' ./cmd) >"$log" 2>&1 &
machine=$!

cleanup() {
    { exec 3<>"/dev/tcp/127.0.0.1/$port" && echo quit >&3 && read -r -t 5 _ <&3; } 2>/dev/null || true
    sleep 1
    kill "$machine" 2>/dev/null || true
    wait "$machine" 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 300); do   # the first run compiles the test binary
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && break
    kill -0 "$machine" 2>/dev/null || { echo "machine exited:"; cat "$log"; exit 1; }
    sleep 1
done
grep -m1 'ready' "$log" || true

cd "$here"
# xcodebuild hands TEST_RUNNER_-prefixed variables to the test process.
TEST_RUNNER_MARIFY_SWTPM="$port" xcodebuild test \
    -project Marify.xcodeproj -scheme Marify \
    -destination "platform=iOS Simulator,name=$sim" \
    -only-testing:MarifyTests/SwtpmMachineSessionTest
