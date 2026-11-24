#!/usr/bin/env bash
# Tears down what up.sh started: the containers, their network and the peer's state.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
docker compose -f "$here/compose.yaml" --profile peer --profile emulator down -v --remove-orphans
rm -f "${NETBIRD_E2E_ENV_FILE:-$here/env}"
