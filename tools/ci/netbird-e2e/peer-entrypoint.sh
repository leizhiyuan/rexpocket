#!/usr/bin/env bash
# Start sshd, then hand over to the stock NetBird entrypoint (service run + netbird up).
set -euo pipefail
/usr/sbin/sshd -e
exec /usr/local/bin/netbird-entrypoint.sh "$@"
