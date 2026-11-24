#!/bin/sh
# Prints a rexpocket://add link for a host, and its QR code when qrencode is installed, for the
# phone's camera to read. The link holds the address and a name only: never a password or a key.
# See docs/design/host-link.md.
#
#   tools/host-link.sh ADDRESS [NAME]
#   tools/host-link.sh 'ssh://me@build.example.com' 'build vm'
set -eu

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "usage: $0 ADDRESS [NAME]" >&2
    exit 2
fi

case "$1" in
    *://*:*@*) echo "the address has a password in it; a link never carries one" >&2; exit 1 ;;
esac

link=$(python3 -I -c '
import sys, urllib.parse
addr = sys.argv[1].strip()
name = sys.argv[2].strip() if len(sys.argv) > 2 else ""
q = "addr=" + urllib.parse.quote(addr, safe="")
if name:
    q += "&name=" + urllib.parse.quote(name, safe="")
print("rexpocket://add?" + q)
' "$@")

echo "$link"
if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ansiutf8 "$link"
else
    echo "(install qrencode to print the QR code here)" >&2
fi
