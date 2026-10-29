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
    from="$src"
else
    echo "tpm2-kira $commit from $repo"
    git clone --quiet "$repo" "$work/clone"
    git -C "$work/clone" archive "$commit" | tar -x -C "$work"
    from="$work/clone"
fi
# The core is a module of its own in tpm2-kira's repository (core/): it
# requires go-tpm, x/crypto and tpm2-verifier (the EK root store), nothing
# of tpm2-kira or aSSH. tpm2-verifier is a submodule of tpm2-kira, which an
# archive leaves out: each submodule the core's go.mod replaces with
# ../<path> - and only those - at the commit tpm2-kira records, from the
# checkout's submodule when it has that commit, else from its URL.
for sub in $(sed -n 's#^replace [^ ]* => \.\./\([^ ]*\)$#\1#p' "$work/core/go.mod"); do
    sha="$(git -C "$from" ls-tree "$commit" "$sub" | awk '$1 == "160000" {print $3}')"
    [[ -n "$sha" ]] || { echo "the core replaces with ../$sub, which tpm2-kira $commit has no submodule for" >&2; exit 1; }
    mkdir -p "$work/$sub"
    if git -C "$from/$sub" cat-file -e "$sha^{commit}" 2>/dev/null; then
        git -C "$from/$sub" archive "$sha" | tar -x -C "$work/$sub"
    else
        url="$(git -C "$from" show "$commit:.gitmodules" | git config -f - "submodule.$sub.url")"
        git clone --quiet "$url" "$work/sub"
        git -C "$work/sub" archive "$sha" | tar -x -C "$work/$sub"
        rm -rf "$work/sub"
    fi
    echo "  $sub $sha"
done
rm -rf "$work/clone"
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
