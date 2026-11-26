#!/bin/sh
# Builds app/libs/tailnet.aar: the official tailscale.com tsnet library, wrapped by tailnet/ for the
# app, arm64-v8a only. With REXPOCKET_NETBIRD=1 it also includes Netbird's embedded client and goes to
# app/libs/tailnet-netbird.aar instead; see below for why that is a separate build. With
# REXPOCKET_NETBIRD=addon it builds the Netbird node alone, for the Netbird add-on app, into
# netbirdaddon/libs/tailnet-netbird-addon.aar (docs/design/tailnet-backends.md).
#
# The app runs its own Tailscale or Netbird node through it, so it can reach tailnet names such as
# host.tailnet-xxxx.ts.net or host.netbird.cloud without either app and without taking the phone's
# VPN slot. Both nodes live in one library, because an APK can hold only one gomobile library: each
# carries its own Go runtime and its own libgojni.so. Netbird is left out of the default library: it
# adds about 30 MB to the APK, which is more than the app itself, so it is a build choice. Without it
# the library still has the Netbird classes, and the app offers no Netbird node.
# The versions are pinned by tailnet/go.mod and tailnet/go.sum, so the same checkout builds the same
# library.
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
# More of the same, about 0.7 MB less native code: control-plane debugging and metrics, the cloud and
# Linux helpers, sign-ins with OAuth clients and workload identity (the app signs in in the browser or
# with an auth key), captive-portal detection, the policy store and exit-node use. Kept on purpose:
# useroutes (subnet routes to reach a host), tailnetlock (tailnets that turn lock on) and posture
# (tailnets that check devices). serve and acme cannot go: tsnet itself calls them.
TAGS="$TAGS,ts_omit_ace,ts_omit_c2n,ts_omit_captiveportal,ts_omit_clientmetrics,ts_omit_cliconndiag"
TAGS="$TAGS,ts_omit_colorable,ts_omit_cloud,ts_omit_conn25,ts_omit_connreject,ts_omit_debugeventbus"
TAGS="$TAGS,ts_omit_debugportmapper,ts_omit_hujsonconf,ts_omit_identityfederation,ts_omit_oauthkey"
TAGS="$TAGS,ts_omit_serviceclientprefs,ts_omit_favorites,ts_omit_syspolicy,ts_omit_tundevstats"
TAGS="$TAGS,ts_omit_linkspeed,ts_omit_linuxdnsfight,ts_omit_usermetrics,ts_omit_netlog"
TAGS="$TAGS,ts_omit_remoteconfig,ts_omit_runtimemetrics,ts_omit_listenrawdisco,ts_omit_webbrowser"
TAGS="$TAGS,ts_omit_useexitnode"

# The Netbird build: its tag, and its own output so the default library stays as it is. The add-on
# has no Tailscale node, so its library is the Netbird package alone, about 20 MB less native code.
OUT="$ROOT/app/libs/tailnet.aar"
PACKAGES=". ./netbird"
case "${REXPOCKET_NETBIRD:-0}" in
    1)
        TAGS="$TAGS,netbird"
        OUT="$ROOT/app/libs/tailnet-netbird.aar"
        ;;
    addon)
        TAGS="$TAGS,netbird"
        OUT="$ROOT/netbirdaddon/libs/tailnet-netbird-addon.aar"
        PACKAGES="./netbird"
        ;;
esac

mkdir -p "$(dirname "$OUT")"
# One ABI: a phone that runs this app is arm64. Android 15 devices can use 16 KB pages, and a
# library whose LOAD segments are aligned to 4 KB does not load there, so the linker is told to
# align to 16 KB; tools/check-16kb.sh checks it.
# Two Java packages in one library: the Tailscale node (the root package) and the Netbird node.
# -checklinkname=0: Netbird reaches net's own interface list through go:linkname (github.com/wlynxg/anet),
# which Go 1.23 and later refuse to link unless the check is off.
gomobile bind \
    -o "$OUT" \
    -target android/arm64 \
    -androidapi 26 \
    -javapkg me.leaver.rexpocket.go \
    -trimpath \
    -ldflags "-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
    -tags "$TAGS" \
    $PACKAGES

rm -f "${OUT%.aar}-sources.jar"
"$ROOT/tools/check-16kb.sh" "$OUT"
echo "wrote $OUT"
