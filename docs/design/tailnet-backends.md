# Netbird as a tailnet backend

The app runs its own tailnet node, so a host on a tailnet is reached without the Tailscale app and
without taking the phone's VPN slot. This note records how Netbird joined that node as a second
backend, why it is built in only on request, and what was and was not verified.

## What the user sees

Settings › Tailnet picks the network: Tailscale (the default) or Netbird. Netbird joins with a setup
key from its dashboard the first time, and a self-hosted Netbird also takes its management server's
address. One node runs at a time; joining the other one stops the first. A host whose name is under
`.ts.net`, `.netbird.cloud`, or in `100.64/10` is reached through whichever node is joined. A
self-hosted Netbird names its peers under a domain of its own, so those are reached by address.

## Decisions

**One gomobile library, not two.** Each gomobile library carries its own Go runtime and its own
`libgojni.so`. Both aars named the file the same, and an APK can hold one such library, so a second
aar cannot be added beside the first. The Netbird package is a second Java package of the same
`tailnet.aar` instead.

**Netbird is a build choice.** Netbird's embedded client adds about 30 MB to the APK (see below), more
than the app itself. The Netbird code sits behind the `netbird` build tag. Without the tag the
library has the same Java API, reports `Available() == false`, and fails every start with a clear
message, so the Kotlin code compiles the same either way and the default APK stays the same size.
`REXPOCKET_NETBIRD=1 tools/build-tailnet.sh` builds `app/libs/tailnet-netbird.aar`, and
`-Prexpocket.netbird=true` links it. The Netbird library is git-ignored: it is too big to check in.

**One identity per node, not per credential.** A setup key is used once to join. Later starts pass
no key, and the WireGuard key the node registered with stands in for it, because the embedded client
wants one credential to start. The app compares node configs with `TailnetConfig.sameNode`, which
ignores credentials, so a connect after a join does not restart the node.

**Path probing from the node's own numbers.** Netbird has no disco ping. The probe reads the peer's
connection state, latency, and whether the path is direct (`RemoteIceCandidateEndpoint`) or through a
relay server (`RelayServerAddress`). The app shows a relay server as `TailnetPath.Relay`, a new case
next to the Tailscale ones.

## Code layout

- `tailnet/` (Go): the Tailscale node, unchanged, and `tailnet/netbird/`: the Netbird node behind
  the `netbird` tag, `platform.go` (the shared `Platform` interface), and `unavailable.go` (the stub).
- `core/` (pure Kotlin, JVM-tested): `TailnetBackend` (the stored choice), `TailnetState`
  (`parseTailnetState`, `parseTailnetPath`: the JSON both nodes report), `TailnetPath.Relay`, and
  `HostAddress.isTailnet` with `.netbird.cloud`.
- `app/…/net/`: `TailnetNode` (the interface the view model uses), `TailnetBox` (the lifecycle,
  peers, probing, and network-change handling both backends share), `TailscaleBox` and `NetbirdBox`
  (open the node and say how their platform hooks work).
- Settings keep their old keys: `tailscale_enabled` and `tailscale_hostname` still mean the joined
  node and its name, and a missing `tailnet_backend` reads as Tailscale, so an installed app keeps
  its join.

## Sizes

Measured on release APKs built with a throwaway key, without libghostty (see below):

| Build | APK | Change |
| --- | --- | --- |
| Baseline (`bd75101`) | 29,108,101 B | - |
| This change, default (stub) | 29,141,981 B | +33,880 B (+0.12%) |
| This change, `-Prexpocket.netbird=true` | 59,404,861 B | +30,296,760 B (+104%) |

The default delta is the stub's native code (+13 KB), dex (+10 KB), and resources (+4 KB). The
Netbird library's native code is 51.5 MB uncompressed, against 21.2 MB for Tailscale alone. Netbird's
own engine accounts for most of it: its client pulls in the SSH server, packet capture, firewall
backends, and DNS, and none of those can be removed without forking it. Stored, page-aligned native
libraries are required for Android 15's 16 KB pages, so the APK cannot compress them.

The 16 KB alignment check passes for both libraries and both APKs.

## Verification

Three GitHub Actions workflows run on every push:

- **`android-apk`** builds the default and the Netbird debug APKs for arm64, with libghostty.
- **`Netbird e2e`** brings up `netbirdio/netbird-server` 0.83.2 (management, signal, relay, STUN) and
  an OpenSSH peer in Docker (`tools/ci/netbird-e2e/`). The setup key comes from the server's
  unauthenticated `/api/setup` bootstrap and its REST API, with no login. `tailnet/netbird/e2e_test.go`
  (tags `netbird e2e`) runs the app's Go node against it. The node joins and reaches Running and lists
  the peer. Ping returns a path, relayed in CI. The test reads `SSH-2.0` from the peer's port 22
  through the SOCKS5 proxy by its name. It then restarts without the key and does it again.
- **`device-test`** runs every instrumented test class on x86_64 emulators, API 30 and 34, with an
  x86_64 build of both native libraries (`-Prexpocket.abi=x86_64`, test-only; release builds stay
  arm64). The x86_64 build is needed because the emulator's ARM translation stops with SIGILL on Go's
  CPU-feature probe (`mrs x0, midr_el1`), which real arm64 phones handle. The run covers app launch,
  Netbird library load, start errors, the terminal, and the IME: 22 of 22 pass.

The end-to-end test found two bugs, both fixed with unit tests:

- The SOCKS5 server resolved names with system DNS before dialing, so peer names never reached the
  node. `peerResolver` now answers them from the node's peers.
- Ping waited on a lazily connected peer that nothing woke. It now sends a datagram until the peer
  connects.

A Netbird build was also tried by hand on an arm64 phone. A first build connected and joined; a host
reached by its `.netbird.cloud` name failed there, which led to the name fixes above.

## Not verified

- **The Go library on arm64 hardware in CI.** No hosted runner offers an arm64 emulator with KVM, so
  CI runs the same sources built for x86_64. The arm64 build is checked on a phone only by hand.
- **Network changes on Netbird.** `NetbirdRunning.networkChanged` does nothing, because the embedded
  client exposes no rebind; Netbird's engine is relied on to follow the network.
- **Interactive Netbird login.** The embedded client has no SSO flow, so a node joins only with a setup
  key.
- **Self-hosted names.** A self-hosted Netbird's domain is not recognised when a host is added; those
  peers need their `100.x` address. Each name would need a setting to be recognised. Once dialing,
  any peer name the node knows resolves.
- **Shared addresses.** Tailscale and Netbird both use `100.64/10`, so such an address goes to
  whichever node is joined.

## Licensing

The app links only Netbird's BSD-3-Clause client code; the build pulls in no `management/`, `signal/`,
`relay/`, or `combined/` directories, which are AGPLv3. The end-to-end workflow runs Netbird's
server image in CI only; nothing from it ships.

## Tests

- `core`: JVM tests for the Netbird name rule, the state and path parsers, the backend choice, and the
  relay case.
- `app`: `TailnetBoxTest` (node-name cleaning) and the instrumented `TailnetNodeInstrumentedTest`.
- `tailnet` (Go): the Tailscale tests, and the Netbird package with and without the `netbird` tag:
  saved-key read, start errors, the stub, the ping JSON fields, peer-name resolution, the resolver,
  and ping wake-up; plus the end-to-end test above.
