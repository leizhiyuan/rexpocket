#!/usr/bin/env bash
# Installs the Rex server pinned in tools/rex/pin.env from Superlogical's apt repository, on a
# Debian or Ubuntu amd64 machine (a GitHub runner), as root or with sudo:
#
#   - the archive key is fetched and its primary key's fingerprint checked before apt trusts it;
#   - apt is pinned to Superlogical's origin, because Debian and Ubuntu have an unrelated package
#     that is also named rex;
#   - the pinned .deb is downloaded, checked against its SHA256, installed and held;
#   - `rex version` must print the pinned version and API revision.
#
# The package is not kept anywhere: it lands in a temporary directory that is removed.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=pin.env
. "$here/pin.env"

sudo=""
[ "$(id -u)" -eq 0 ] || sudo="sudo"
arch="$(dpkg --print-architecture)"
[ "$arch" = amd64 ] || { echo "install-rex: only the amd64 package is pinned (this is $arch)" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "install-rex: fetching the archive key"
curl -fsSL --retry 3 -o "$work/rex-archive-keyring.gpg" "$REX_KEYRING_URL"
fingerprint="$(gpg --show-keys --with-colons "$work/rex-archive-keyring.gpg" 2>/dev/null | awk -F: '$1 == "pub" { want = 1; next } want && $1 == "fpr" { print $10; exit }')"
if [ "$fingerprint" != "$REX_KEY_FINGERPRINT" ]; then
    echo "install-rex: the archive key's fingerprint is '$fingerprint', not $REX_KEY_FINGERPRINT; not trusting it" >&2
    exit 1
fi
echo "install-rex: archive key $fingerprint (as pinned)"
$sudo install -m 0644 "$work/rex-archive-keyring.gpg" /usr/share/keyrings/rex-archive-keyring.gpg

$sudo tee /etc/apt/sources.list.d/rex.sources > /dev/null <<EOF
Types: deb
URIs: $REX_APT_URL
Suites: $REX_APT_SUITE
Components: main
Architectures: amd64
Signed-By: /usr/share/keyrings/rex-archive-keyring.gpg
EOF
host="$(echo "$REX_APT_URL" | sed -E 's#^https?://([^/]+).*#\1#')"
$sudo tee /etc/apt/preferences.d/rex > /dev/null <<EOF
Package: rex
Pin: origin $host
Pin-Priority: 1001

Package: rex
Pin: release *
Pin-Priority: -1
EOF

$sudo apt-get update -o Dir::Etc::sourcelist=/etc/apt/sources.list.d/rex.sources -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0 -qq
(cd "$work" && $sudo apt-get download -qq "rex=$REX_VERSION")
deb="$(ls "$work"/rex_*_amd64.deb)"
echo "$REX_SHA256_AMD64  $deb" | sha256sum -c -
$sudo apt-get install -y -qq "$deb"
$sudo apt-mark hold rex > /dev/null

version="$(rex version)"
echo "$version"
echo "$version" | grep -qx "$REX_VERSION_OUTPUT" || { echo "install-rex: expected '$REX_VERSION_OUTPUT'" >&2; exit 1; }
echo "$version" | grep -q "^api: revision $REX_API_REVISION " || { echo "install-rex: expected API revision $REX_API_REVISION" >&2; exit 1; }
