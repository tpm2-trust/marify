#!/usr/bin/env bash
# Builds the Go core for the app from the tpm2-kira commit pinned in
# ../core.properties (shared with the iOS app):
#
#   app/libs/kiracore.aar        core/mobile/kiracore            -> release builds
#   app/libs/kiracore-debug.aar  core/mobile/kiracore + kiratest -> debug and test builds
#
# Two separately bound .aar files cannot be linked together: each carries its
# own libgojni.so and the go.* runtime classes. The debug/test library is
# therefore one bind of both packages, and the release library contains no
# kiratest code at all.
#
# Environment:
#   KIRA_SRC      local tpm2-kira checkout to take the commit from
#                 (default: ../../tpm2-kira next to this repository, else a
#                 fresh clone of kira.repo)
#   ANDROID_HOME  Android SDK with an NDK installed (default ~/Android/Sdk)
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
prop() { sed -n "s/^$1=//p" "$here/../core.properties"; }
repo="$(prop kira.repo)"
commit="$(prop kira.commit)"
mobile_ver="$(prop gomobile.version)"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ndk="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
    [[ -n "$ndk" ]] || { echo "no NDK in $ANDROID_HOME/ndk (sdkmanager 'ndk;<version>')" >&2; exit 1; }
    export ANDROID_NDK_HOME="$ndk"
fi

gopath="$(go env GOPATH)"
export PATH="$gopath/bin:$PATH"
for tool in gomobile gobind; do
    go install "golang.org/x/mobile/cmd/$tool@$mobile_ver"
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

src="${KIRA_SRC:-$here/../../tpm2-kira}"
if git -C "$src" cat-file -e "$commit^{commit}" 2>/dev/null; then
    echo "tpm2-kira $commit from $src"
    git -C "$src" archive "$commit" | tar -x -C "$work"
else
    echo "tpm2-kira $commit from $repo"
    git clone --quiet "$repo" "$work/clone"
    git -C "$work/clone" archive "$commit" | tar -x -C "$work"
    rm -rf "$work/clone"
fi

# The core is a module of its own in tpm2-kira's repository (core/): it
# requires go-tpm and x/crypto alone, nothing of tpm2-kira or aSSH.
cd "$work/core"
# The bind needs golang.org/x/mobile/bind in the module graph. This touches
# only the temporary copy, never the pinned sources.
go get "golang.org/x/mobile@$mobile_ver"

out="$here/app/libs"
mkdir -p "$out"
gomobile bind -target=android -androidapi 28 -trimpath -ldflags=-s \
    -o "$out/kiracore.aar" ./mobile/kiracore
gomobile bind -target=android -androidapi 28 -trimpath -ldflags=-s \
    -o "$out/kiracore-debug.aar" ./mobile/kiracore ./mobile/kiratest
rm -f "$out"/*-sources.jar

echo "$commit" > "$out/CORE_COMMIT"
ls -l "$out"
