#!/bin/sh
# Builds app/libs/netbird.aar: the embeddable Netbird client (github.com/netbirdio/netbird/client/embed),
# wrapped by netbird/ for the app, arm64-v8a only.
#
# This is the Netbird counterpart of tools/build-tailnet.sh: the app can join a Netbird network
# through it instead of a Tailscale one. The versions are pinned by netbird/go.mod and
# netbird/go.sum, so the same checkout builds the same library.
#
# Requires: Go (go.mod's toolchain is fetched when GOTOOLCHAIN allows it), JDK 17, an Android NDK
# and the Android SDK. gomobile is installed into GOBIN by this script.
#
#   ANDROID_HOME=/path/to/sdk ANDROID_NDK_HOME=/path/to/ndk JAVA_HOME=/path/to/jdk17 \
#       tools/build-netbird.sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

: "${ANDROID_HOME:?set ANDROID_HOME}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"

cd "$ROOT/netbird"
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
export GOBIN="${GOBIN:-$(go env GOPATH)/bin}"
# The gomobile that matches golang.org/x/mobile in go.mod, so bind and its runtime agree.
MOBILE=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE"

PATH="$JAVA_HOME/bin:$GOBIN:$PATH"

mkdir -p "$ROOT/app/libs"
# One ABI, aligned to 16 KB for Android 15 devices with 16 KB pages; tools/check-16kb.sh checks it.
gomobile bind \
    -o "$ROOT/app/libs/netbird.aar" \
    -target android/arm64 \
    -androidapi 26 \
    -javapkg me.leaver.rexpocket.go \
    -trimpath \
    -ldflags "-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
    .

rm -f "$ROOT/app/libs/netbird-sources.jar"
"$ROOT/tools/check-16kb.sh" "$ROOT/app/libs/netbird.aar"
echo "wrote $ROOT/app/libs/netbird.aar"
