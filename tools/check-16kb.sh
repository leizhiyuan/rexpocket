#!/bin/sh
# Fails unless every arm64 library in the given APKs or AARs aligns its LOAD segments to 16 KB:
# Android 15 devices with 16 KB pages cannot load one that does not.
#
#   tools/check-16kb.sh app/build/outputs/apk/release/app-release.apk app/libs/tailnet.aar
set -eu

: "${ANDROID_HOME:?set ANDROID_HOME}"
READELF=$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | tail -1)
[ -x "$READELF" ] || { echo "no llvm-readelf under $ANDROID_HOME/ndk" >&2; exit 2; }

status=0
for archive in "$@"; do
    work=$(mktemp -d)
    unzip -q -o "$archive" '*arm64-v8a/*.so' -d "$work" 2>/dev/null || true
    for lib in $(find "$work" -name '*.so'); do
        # Every LOAD segment's alignment, in hex; anything below 0x4000 is too small.
        # macOS awk has no strtonum, so the shell does the hex.
        small=""
        for align in $("$READELF" -lW "$lib" | awk '$1 == "LOAD" { print $NF }'); do
            [ $((align)) -lt 16384 ] && small="$small $align"
        done
        if [ -n "$small" ]; then
            echo "$(basename "$archive"): $(basename "$lib") aligns LOAD to$small (needs 0x4000)" >&2
            status=1
        fi
    done
    rm -rf "$work"
done
exit $status
