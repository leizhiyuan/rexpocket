#!/bin/sh
# Builds app/libs/tailnet.aar: the official tailscale.com tsnet library, wrapped by tailnet/ for the
# app, arm64-v8a only.
#
# The app runs its own Tailscale node through it, so it can reach tailnet names such as
# host.tailnet-xxxx.ts.net without the Tailscale app and without taking the phone's VPN slot.
# The versions are pinned by tailnet/go.mod and tailnet/go.sum, so the same checkout builds the
# same library.
#
# Requires: Go (go.mod's toolchain is fetched when GOTOOLCHAIN allows it), JDK 17, an Android NDK
# and the Android SDK. gomobile is installed into GOBIN by this script.
#
#   ANDROID_HOME=/path/to/sdk ANDROID_NDK_HOME=/path/to/ndk JAVA_HOME=/path/to/jdk17 \
#       tools/build-tailnet.sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

: "${ANDROID_HOME:?set ANDROID_HOME}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"

cd "$ROOT/tailnet"
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
export GOBIN="${GOBIN:-$(go env GOPATH)/bin}"
# The gomobile that matches golang.org/x/mobile in go.mod, so bind and its runtime agree.
MOBILE=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE"

PATH="$JAVA_HOME/bin:$GOBIN:$PATH"

# The app is a client that dials through the node and nothing else: the features a server, a CLI
# or a desktop would use are left out. Leaving out logtail also means the node never uploads its
# log to Tailscale.
TAGS="ts_omit_logtail,ts_omit_ssh,ts_omit_taildrop,ts_omit_drive,ts_omit_webclient,ts_omit_doctor"
TAGS="$TAGS,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,ts_omit_tap"
TAGS="$TAGS,ts_omit_tpm,ts_omit_appconnectors,ts_omit_clientupdate,ts_omit_relayserver"
TAGS="$TAGS,ts_omit_portlist,ts_omit_wakeonlan,ts_omit_advertiseexitnode,ts_omit_advertiseroutes"
TAGS="$TAGS,ts_omit_flashappliance,ts_omit_systray,ts_omit_dbus,ts_omit_networkmanager"
TAGS="$TAGS,ts_omit_resolved,ts_omit_iptables,ts_omit_sdnotify,ts_omit_syslog,ts_omit_completion"
TAGS="$TAGS,ts_omit_qrcodes,ts_omit_cli"

mkdir -p "$ROOT/app/libs"
# One ABI: a phone that runs this app is arm64. Android 15 devices can use 16 KB pages, and a
# library whose LOAD segments are aligned to 4 KB does not load there, so the linker is told to
# align to 16 KB; tools/check-16kb.sh checks it.
gomobile bind \
    -o "$ROOT/app/libs/tailnet.aar" \
    -target android/arm64 \
    -androidapi 26 \
    -javapkg me.leaver.rexpocket.go \
    -trimpath \
    -ldflags "-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
    -tags "$TAGS" \
    .

rm -f "$ROOT/app/libs/tailnet-sources.jar"
"$ROOT/tools/check-16kb.sh" "$ROOT/app/libs/tailnet.aar"
echo "wrote $ROOT/app/libs/tailnet.aar"
