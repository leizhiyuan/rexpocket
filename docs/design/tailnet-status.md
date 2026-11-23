# Tailnet status on the home screen

The app runs its own tailnet node, so a host on a tailnet is reached without the Tailscale app and
without taking the phone's VPN slot. This note records how the node's state reaches the screen: the
home screen's card and details, and the steps of a connection to a host. The backends themselves
are in [tailnet-backends.md](tailnet-backends.md).

The user's complaint was that there was "very little UI or UX stating tailnet connecting then
hosts". Before this change the only signs were the Settings row's word ("Running", "Starting…"), a
login link that opened on its own, a red line under a host whose text was usually the SOCKS
error, and a path line after a probe. Every state below was there in the node; little of it reached
the screen.

## The state map

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

## What the bridge reports

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

## Sizes of this change

Release APK without libghostty, throwaway key, against `ce66592`: 29,412,901 B before, 29,507,933 B
after (+95,032 B, +0.32%): native code +13,920 B, dex +52,773 B uncompressed, `resources.arsc`
+31,960 B (the new strings in English, Simplified and Traditional Chinese). The new strings live in
`tailnet_strings.xml` of each locale; `values-zh-rTW` keeps its rule of falling back to `values-zh`
for every string it does not have.

## Tests

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
