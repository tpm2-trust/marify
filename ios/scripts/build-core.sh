#!/usr/bin/env bash
# Builds the Go core for the iOS app from the tpm2-kira commit pinned in
# ../core.properties (shared with the Android app):
#
#   Frameworks/Kiracore.xcframework   core/mobile/kiracore   -> every build
#   Frameworks/Kiratest.xcframework   core/mobile/kiratest   -> Debug builds only (demo machine)
#
# Needs macOS with Xcode (gomobile compiles with Xcode's clang and SDKs),
# Go >= 1.24 and a network connection for the first run.
#
# Environment:
#   KIRA_SRC  local tpm2-kira checkout to take the commit from
#             (default: ../../tpm2-kira next to this repository, else a
#             fresh clone of kira.repo)
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
prop() { sed -n "s/^$1=//p" "$here/../core.properties"; }
repo="$(prop kira.repo)"
commit="$(prop kira.commit)"
mobile_ver="$(prop gomobile.version)"

[[ "$(uname)" == "Darwin" ]] || { echo "gomobile's iOS target needs macOS with Xcode" >&2; exit 1; }
xcode-select -p >/dev/null || { echo "Xcode command line tools not selected (xcode-select --install)" >&2; exit 1; }

gopath="$(go env GOPATH)"
export PATH="$gopath/bin:$PATH"
for tool in gomobile gobind; do
    go install "golang.org/x/mobile/cmd/$tool@$mobile_ver"
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

src="${KIRA_SRC:-$here/../../tpm2-kira}"
if [[ -d "$src/.git" ]]; then
    git -C "$src" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$src" fetch --quiet "$repo" "$commit"
    git clone --quiet --shared --no-checkout "$src" "$work/src"
else
    git clone --quiet --no-checkout "$repo" "$work/src"
fi
git -C "$work/src" checkout --quiet "$commit"
echo "tpm2-kira $commit"

# The core is a module of its own in tpm2-kira's repository (core/): it
# requires go-tpm and x/crypto alone, nothing of tpm2-kira or aSSH.
cd "$work/src/core"
# The bind runtime must match the tools: pin x/mobile in the module graph.
go get "golang.org/x/mobile@$mobile_ver" >/dev/null
gomobile init

mkdir -p "$here/Frameworks"
rm -rf "$here/Frameworks/Kiracore.xcframework" "$here/Frameworks/Kiratest.xcframework"
gomobile bind -target=ios,iossimulator -iosversion 17.0 -trimpath -ldflags=-s \
    -o "$here/Frameworks/Kiracore.xcframework" ./mobile/kiracore
gomobile bind -target=ios,iossimulator -iosversion 17.0 -trimpath -ldflags=-s \
    -o "$here/Frameworks/Kiratest.xcframework" ./mobile/kiratest

echo "slices (project.yml expects ios-arm64 and ios-arm64_x86_64-simulator):"
ls "$here/Frameworks/Kiratest.xcframework" | grep -v Info.plist
echo "built from tpm2-kira $commit with golang.org/x/mobile $mobile_ver"
