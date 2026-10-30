#!/bin/sh
# Builds app/libs/libbox.aar: sing-box's Go core with the Tailscale endpoint, arm64-v8a only.
#
# The app runs its own Tailscale node through it, so it can reach tailnet names such as
# host.tailnet-xxxx.ts.net without the Tailscale app and without taking the phone's VPN slot.
#
# Requires: Go, JDK 17, an Android NDK (r28 is what this was built with), and the Android SDK.
# gomobile is installed into GOBIN by this script.
#
#   ANDROID_HOME=/path/to/sdk ANDROID_NDK_HOME=/path/to/ndk JAVA_HOME=/path/to/jdk17 \
#       tools/build-libbox.sh [sing-box checkout]
#
# The second argument is handy when a checkout already exists; otherwise the pinned release is
# cloned into build/sing-box.
set -eu

VERSION=1.14.4
GOMOBILE_VERSION=v0.1.13
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC=${1:-$ROOT/build/sing-box}

: "${ANDROID_HOME:?set ANDROID_HOME}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"

if [ ! -d "$SRC/experimental/libbox" ]; then
    git clone --depth 1 --branch "v$VERSION" https://github.com/SagerNet/sing-box.git "$SRC"
fi

export GOTOOLCHAIN=local
export GOBIN="${GOBIN:-$(go env GOPATH)/bin}"
go install "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
go install "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_VERSION"

PATH="$JAVA_HOME/bin:$GOBIN:$PATH"

# with_gvisor is what the Tailscale endpoint runs on. The protocol tags sing-box ships for its own
# Android app are left out: they add tens of megabytes and this app only needs Tailscale, a direct
# outbound and a mixed inbound. ts_omit_* drops tailscale features the app does not use either.
TAGS="with_gvisor,with_tailscale,badlinkname,tfogo_checklinkname0"
TAGS="$TAGS,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient"
TAGS="$TAGS,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird"

LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=$VERSION"
LDFLAGS="$LDFLAGS -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid="

cd "$SRC"
mkdir -p "$ROOT/app/libs"
# One ABI: a phone that runs this app is arm64, and the library is ~57 MB per ABI.
gomobile bind \
    -o "$ROOT/app/libs/libbox.aar" \
    -target android/arm64 \
    -androidapi 24 \
    -javapkg=io.nekohasekai \
    -libname=box \
    -trimpath -buildvcs=false \
    -ldflags "$LDFLAGS" \
    -tags "$TAGS" \
    ./experimental/libbox

echo "wrote $ROOT/app/libs/libbox.aar"
