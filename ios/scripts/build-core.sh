#!/usr/bin/env bash
# Builds the Go core for the iOS app from the tpm2-kira commit pinned in
# ../core.properties (shared with the Android app):
#
#   Frameworks/Kiracore.xcframework       mobile/kiracore                 -> Release
#   Frameworks/KiracoreDebug.xcframework  mobile/kiracore + mobile/kiratest -> Debug, tests
#
# KiracoreDebug binds kiracore and kiratest (the demo machine) TOGETHER, in
# one `gomobile bind` invocation, so they share a single embedded Go
# runtime; Debug links it INSTEAD OF Kiracore.xcframework, never alongside
# it. Each `gomobile bind` run embeds its own full Go runtime, and linking
# two independently bound libraries into one process crashes (SIGSEGV
# inside the cgo stack-switch trampoline, a few seconds after the first
# call that reaches the second runtime) -- see tpm2-kira's
# mobile/kiratest package doc. The module both frameworks expose to Swift
# is named "Kiracore" either way (gomobile bakes that in from -o at build
# time, not from the file's name on disk), so `import Kiracore` resolves
# the same way in both configs; KiracoreDebug additionally exports the
# Kiratest* demo-machine API.
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

cd "$work/src"
# The bind runtime must match the tools: pin x/mobile in the module graph.
go get "golang.org/x/mobile@$mobile_ver" >/dev/null
gomobile init

mkdir -p "$here/Frameworks"
rm -rf "$here/Frameworks/Kiracore.xcframework" "$here/Frameworks/KiracoreDebug.xcframework"
gomobile bind -target=ios,iossimulator -iosversion 17.0 -trimpath -ldflags=-s \
    -o "$here/Frameworks/Kiracore.xcframework" ./mobile/kiracore

# The combined build must also be built with -o ".../Kiracore.xcframework":
# gomobile bakes the embedded module/framework name in from -o's basename at
# build time, and `import Kiracore` (used unconditionally in the app's
# Swift source) needs that name regardless of config. Build it to a
# throwaway path under the same name so it doesn't clobber the release
# build above, then rename the directory on disk -- that's just a mv, it
# does not touch the module name already baked into the framework.
gomobile bind -target=ios,iossimulator -iosversion 17.0 -trimpath -ldflags=-s \
    -o "$work/Kiracore.xcframework" ./mobile/kiracore ./mobile/kiratest
mv "$work/Kiracore.xcframework" "$here/Frameworks/KiracoreDebug.xcframework"

echo "slices (project.yml expects ios-arm64 and ios-arm64_x86_64-simulator):"
ls "$here/Frameworks/KiracoreDebug.xcframework" | grep -v Info.plist
echo "built from tpm2-kira $commit with golang.org/x/mobile $mobile_ver"
