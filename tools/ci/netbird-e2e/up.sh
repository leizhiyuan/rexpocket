#!/usr/bin/env bash
# Brings up a self-hosted NetBird control plane (the combined netbird-server: management,
# signal, relay and STUN) and one SSH peer joined to it, for tailnet/netbird/e2e_test.go.
#
# No human login and no external IdP: the fresh server's unauthenticated /api/setup bootstrap
# creates the owner and, with NB_SETUP_PAT_ENABLED, returns an admin token; that token creates a
# reusable setup key; the peer joins with it.
#
# The test runs on the host, so the host must reach the server by the same name the peers use:
# netbird.e2e is mapped in /etc/hosts to the server's fixed address (needs root or sudo).
#
# Prints the environment for the test as NAME=value lines, also written to $NETBIRD_E2E_ENV_FILE
# (default: env in this directory) and, under GitHub Actions, appended to $GITHUB_ENV.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

host=netbird.e2e
addr=172.29.83.10
base="http://$host:8080"
env_file="${NETBIRD_E2E_ENV_FILE:-$here/env}"

# The server lives on a local docker network: never send it through an outbound proxy.
export NO_PROXY="${NO_PROXY:+$NO_PROXY,}$host,172.29.83.0/24"
export no_proxy="$NO_PROXY"

log() { echo "up.sh: $*" >&2; }

sudo_() { if [ "$(id -u)" = 0 ]; then "$@"; else sudo "$@"; fi; }

compose() { docker compose -f "$here/compose.yaml" --profile peer "$@"; }

if ! grep -qE "^$addr[[:space:]]+$host\$" /etc/hosts; then
	log "mapping $host to $addr in /etc/hosts"
	echo "$addr $host" | sudo_ tee -a /etc/hosts >/dev/null
fi

log "starting the server"
compose up -d server

log "waiting for the management API"
for i in $(seq 1 120); do
	if curl -fsS --noproxy '*' -o /dev/null "$base/api/instance" 2>/dev/null; then break; fi
	if [ "$i" = 120 ] || [ -z "$(compose ps -q --status running server)" ]; then
		log "management did not come up"; compose logs server >&2; exit 1
	fi
	sleep 1
done

log "bootstrapping the owner and an admin token"
setup=$(curl -fsS --noproxy '*' -X POST "$base/api/setup" -H 'Content-Type: application/json' \
	-d '{"email":"admin@rexpocket.test","password":"rexpocket-e2e-password","name":"E2E Admin","create_pat":true,"pat_expire_in":1}')
pat=$(printf '%s' "$setup" | python3 -c 'import json,sys; print(json.load(sys.stdin)["personal_access_token"])')

api() { curl -fsS --noproxy '*' -H "Authorization: Token $pat" -H 'Content-Type: application/json' "$@"; }

log "creating a reusable setup key"
key=$(api -X POST "$base/api/setup-keys" \
	-d '{"name":"e2e","type":"reusable","expires_in":86400,"auto_groups":[],"usage_limit":0}' |
	python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')

log "building the peer image"
build=(docker build -f "$here/Dockerfile.peer" -t rexpocket/netbird-e2e-peer:0.83.2)
if [ -n "${NETBIRD_E2E_BUILD_CA:-}" ]; then
	# Behind a TLS-intercepting proxy: build on the host network with its proxy and CA.
	build+=(--network host --secret "id=ca,src=$NETBIRD_E2E_BUILD_CA"
		--build-arg "HTTPS_PROXY=${HTTPS_PROXY:-}" --build-arg "HTTP_PROXY=${HTTP_PROXY:-}")
fi
"${build[@]}" "$here" >&2

log "starting the peer"
NETBIRD_E2E_SETUP_KEY="$key" compose up -d peer

log "waiting for the peer to register and connect"
peer=""
for i in $(seq 1 120); do
	peer=$(api "$base/api/peers" | python3 -c '
import json, sys
for p in json.load(sys.stdin):
    if p.get("hostname") == "peer" and p.get("connected") and p.get("dns_label"):
        print(p["dns_label"], p["ip"])
        break
' || true)
	if [ -n "$peer" ]; then break; fi
	if [ "$i" = 120 ]; then log "the peer did not connect"; compose logs >&2; exit 1; fi
	sleep 1
done
fqdn=${peer% *}
ip=${peer#* }

{
	echo "NETBIRD_E2E_MANAGEMENT_URL=$base"
	echo "NETBIRD_E2E_SETUP_KEY=$key"
	echo "NETBIRD_E2E_PEER=$fqdn"
	echo "NETBIRD_E2E_PEER_IP=$ip"
	echo "NO_PROXY=$NO_PROXY"
	echo "no_proxy=$NO_PROXY"
} >"$env_file"
if [ -n "${GITHUB_ENV:-}" ]; then cat "$env_file" >>"$GITHUB_ENV"; fi
cat "$env_file"
