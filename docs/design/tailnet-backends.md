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

## Status on the home screen

The user's complaint was that there was "very little UI or UX stating tailnet connecting then
hosts". Before this change the only signs were the Settings row's word ("Running", "Starting…"), a
login link that opened on its own, a red line under a host whose text was usually the SOCKS
error, and a path line after a probe. Every state below was there in the node; little of it reached
the screen.

### The state map

From "app opened" to "terminal shown", for a host on the tailnet:

| Step | Node / connection state | Before | Now |
| --- | --- | --- | --- |
| Tailnet starting | box status `""` until the node reports | nothing; Settings row "Starting…" | Home card: spinner, "Starting Tailscale…", "Step 1 of 3", step bar |
| Login needed | `NeedsLogin` + `authUrl` | browser opened; URL only inside the Tailnet dialog | loud card: "Sign in to Tailscale", Sign in and Copy link, "Step 2 of 3" |
| Approval needed | `NeedsMachineAuth` | Settings row "Waiting for approval" | loud card with the admin-console note |
| Connecting | `Starting`, or `Running` before the netmap named the node | Settings row only | card: "Connecting to Tailscale…", "Step 3 of 3" |
| Connected | `Running` + self | Settings row "Running" | one quiet row: green dot, "Tailscale · Connected as pixel-8 on example.com", "3 of 5 online"; tap for details |
| Peers known | netmap peers | never shown | details sheet: every device, online/offline, OS, MagicDNS name, IP, link, Rex-host chip |
| Start failed | `TailnetException` | Settings row "Failed to start" | red card with the node's message, Try again and Tailnet settings |
| Not joined, tailnet hosts saved | settings `enabled = false` | red "Join a tailnet first." after a tap | quiet card "N hosts are on a tailnet" with Join |
| Host: node starting | `ConnectStep.StartTailnet` | "Connecting to host:port" | "Starting Tailscale…" or "Waiting for the Tailscale sign-in…", seconds, step bar |
| Host: resolving | `ConnectStep.FindPeer` (`awaitPeer`) | same | "Looking for mac on Tailscale…" |
| Host: dialing | `ConnectStep.Reach` (SOCKS dial, WebSocket upgrade) | same | "Reaching mac over Tailscale…" |
| Host: Rex answering | `ConnectStep.OpenRex` (`PocketState.clientId` set, bootstrap running) | same | "Rex answered · loading sessions…" |
| Host: live | `Phase.Live` | "Online", path line after 3 s | unchanged; the steps go away, the time each took goes to Diagnostics |
| Host: failed | `PocketState.problem` | SOCKS text ("SOCKS server general failure") | told from the node: device offline (last seen …), no device by that name, Rex not listening on the port, sign in to reach the host; fix action next to Retry (Sign in, Devices, Join, Tailnet settings) |

`TailnetStage` (`core/…/TailnetStage.kt`) is the node half of this, mapped by `tailnetStage()`
from the backend state, the login URL, the device name and the start error. `ConnectProgress` is the
host half: the view model moves it on at each step, and keeps how long each took. Both are unit
tested in `TailnetDetailsTest.kt`.

A Netbird node joins with a setup key, so its steps are Start and Connect only; it cannot tell
whether a peer is online, so its devices show the node's own connection to them (direct, relayed,
connecting, idle) and its latency instead.

### What the bridge reports

The state JSON both nodes report grew, keeping every old field and name (an older library still
parses):

- `selfInfo`: `dnsName`, `hostName`, `ips`; Tailscale adds `os`, `user` (login name), `relay`
  (home DERP region) and `keyExpiry`.
- `health`: Tailscale's health warnings; for Netbird, a broken management or signal connection.
- per peer: `os`, `online` and `lastSeen` (Tailscale; Netbird leaves them out, since the embedded
  client's status has no control-plane presence), `link` and `linkDetail` (`direct` with the
  endpoint, `peerRelay`, `derp` with the region, `relay` with the Netbird relay server,
  `connecting`, `idle`), and for Netbird `latencyMs` and `lastHandshake`.
- `tailnet` for Netbird is the management server's host, since Netbird has no network name.

`Node.DialError(host)` is new on both nodes and the stub: the reason for the last failed dial to a
host through the proxy, kept two minutes (`tailnet/internal/dials`). Tailscale's SOCKS5 server
answers every failure with "general failure", so without it the app could not tell a refused port
from an offline peer. The Netbird proxy resolves a name before dialing, so the app also asks under
the peer's address. `explainTailnetProblem` reads it with the device list: a refused dial is "Rex is
not listening on port N", an offline device is offline, a name the listed tailnet does not have is
unknown, a node that needs a sign-in says so.

Cheap and reliable was the rule: everything comes from `LocalClient.Status` (Tailscale) or
`Client.Status` (Netbird), which the node already read; nothing new is polled. A connection to a
name the node's device list does not have now waits 2 s for it instead of 10 s.

### Sizes of this change

Release APK without libghostty, throwaway key, against `ce66592`: 29,412,901 B before, 29,507,933 B
after (+95,032 B, +0.32%): native code +13,920 B, dex +52,773 B uncompressed, `resources.arsc`
+31,960 B (the new strings in English, Simplified and Traditional Chinese). The new strings live in
`tailnet_strings.xml` of each locale; `values-zh-rTW` keeps its rule of falling back to `values-zh`
for every string it does not have.

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

A Netbird build was also tried by hand on an arm64 phone. A first build joined, but a host reached by
its `.netbird.cloud` name failed, which led to the name fixes above. With the fixes, the phone reaches
a Mac over SSH by its `.netbird.cloud` name and is in daily use.

## Not verified

- **The Go library on arm64 hardware in CI.** No hosted runner offers an arm64 emulator with KVM, so
  CI runs the same sources built for x86_64. The arm64 build is checked by hand on a phone.
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
- `app` screens: `TailnetStatusTest` (sharedTest, JVM and emulator) runs the whole app on its view
  model with `FakeTailnetNode` reporting the real nodes' states and `FakeSocks` in place of the
  node's proxy (`TailnetHarness.kt`): each stage's card, the details and device order, a device
  offered as a host, the steps of a connect through the proxy to ScriptedRex, and the offline,
  unknown-name and not-listening explanations. `ScreenshotsTest` records `tailnet-*.png`: starting,
  needs login, connecting, connected, details (Tailscale and Netbird), not joined, start failed,
  reaching a host, peer offline, Rex not listening.
- `core`: `TailnetDetailsTest.kt` for the new state fields, the stage map, connect progress and the
  problem explanations.
- `tailnet` (Go): `state_test.go` (the Tailscale state JSON and link), `internal/dials`, and
  `netbird/state_test.go`; the end-to-end test also checks the self, the peer's link after a ping,
  and that a refused dial leaves a reason in `DialError`.
- `tailnet` (Go): the Tailscale tests, and the Netbird package with and without the `netbird` tag:
  saved-key read, start errors, the stub, the ping JSON fields, peer-name resolution, the resolver,
  and ping wake-up; plus the end-to-end test above.
