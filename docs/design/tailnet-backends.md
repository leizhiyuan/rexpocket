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

How the node's state, Netbird's included, reaches the home screen is in
[tailnet-status.md](tailnet-status.md).

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

## The Netbird add-on

Embedding Netbird costs every user 30 MB, and most use Tailscale. So the default build offers
Netbird through a second app instead, installed only by those who want it. The CI run builds three
APKs, all signed with the kept debug key:

| APK | Gradle | App id | Netbird |
| --- | --- | --- | --- |
| `rexpocket.apk` | `:app:assembleDebug` | `me.leaver.rexpocket` | through the add-on: "Netbird (add-on)" |
| `rexpocket-netbird-addon.apk` | `:netbirdaddon:assembleDebug` | `me.leaver.rexpocket.netbird` | runs the node |
| `rexpocket-netbird.apk` | `:app:assembleDebug -Prexpocket.netbird=true` | `me.leaver.rexpocket` | embedded; no add-on |

The two app builds share an id and a key, so either installs over the other.

**A bound service, not a port.** The add-on's `NodeService` is exported only behind
`me.leaver.rexpocket.netbird.permission.NODE`, a `signature` permission, and checks it again on
every call. Both apps declare the permission (from `:netbirdaddon-api`'s manifest), so either may be
installed first, and Android refuses an app with another key that declares it. The add-on starts
its node with `Netbird.startPrivate`, which opens no SOCKS5 proxy: nothing listens on loopback.

**The interface** is `INetbirdNode` (AIDL, in `:netbirdaddon-api`, which both apps depend on):
`version`, `start(hostname, setupKey, managementUrl)` (the configure-and-start of the embedded
node), `stop`, `state` and `ping` (the JSON the Go library already reports, so `TailnetStage` and the
status screens work unchanged), `dialError`, `dial(host, port, timeout)`, a listener for state
changes, and `keepRunning`; since revision 2 (see "Versions" below) also `revision`,
`startProfile(profile, …)` and `forgetProfile(profile)`, for accounts. A failure is an
`IllegalStateException` with the node's own words, which Binder carries.

**Dialing.** `dial` returns a `ParcelFileDescriptor`: one end of a Unix socket pair, whose other end
the add-on pipes to the Netbird connection (`Node.DialFd` in `tailnet/netbird/dialfd.go`, with half
closes passed on). In RexPocket the descriptor is a `java.net.Socket` through a custom `SocketImpl`
(`core/…/DialedSocket.kt`, `app/src/addon/…/FdStream.kt`), so the Rex connection and the SSH
tunnel take it where they took a SOCKS proxy: `TailnetNode.dialer` is set, OkHttp gets
`DialedSocketFactory` with `UnresolvedDns` (the name reaches the add-on as it is), and
`SshSettings.dialer` does the same for JSch. The `SocketImpl` carries the descriptor, so Android's
TLS, which works on descriptors, also works over it. Read timeouts are a `poll` before each read;
a close shuts the socket down first, which wakes a blocked read.

**Versions.** Two numbers say what an add-on speaks (`NetbirdAddon`):

- `VERSION`, the base protocol: revision 1's nine calls and what they mean. RexPocket uses only an
  add-on of its own `VERSION`, checked right after binding; it stays 1 as long as calls are only
  added. An add-on of another one is not used, and the error says which side to update: "The
  Netbird add-on is older than this RexPocket … Update the add-on", or "… newer … Update RexPocket".
- `REVISION`, the calls added since: 2, which added accounts. AIDL gives each call its place as its
  transaction code, so new calls are appended (`InterfaceTest` pins the codes). Binder answers a
  call the add-on does not have with an empty reply, which reads as 0 and does nothing, so
  RexPocket asks `revision(itsOwn)` and reads 0 as 1, and never makes a call of a revision the
  add-on does not speak (a `startProfile` would otherwise be taken and silently do nothing). The
  service also declares its revision as `<meta-data>` (`NetbirdAddon.META_REVISION`), so the
  screens know what an installed add-on can do without binding it; an add-on without it is 1.

| | Add-on of revision 1 (the first) | Add-on of revision 2 |
| --- | --- | --- |
| RexPocket of revision 1 (exact `VERSION == 1` check) | everything | everything: `version()` is still 1, and the new calls are never made |
| This RexPocket | the first account only: Accounts are hidden, a second account's start fails with "too old for a second account", and the page and the Tailnet dialog offer Update the add-on | everything |

The add-on now carries RexPocket's version name and code (read from `app/build.gradle.kts`), so a
release's two APKs match by name, and an add-on is "older than RexPocket" when its revision is
below the app's or its version name is older (`AddonPackage.olderThan`). The first add-on was
"1.0", revision 1, and counts as older by its revision.

**Updating the add-on.** Tailscale and the embedded Netbird update with the app; the add-on is an
app of its own. The app's updater keeps installing the app's own APK by name (`isUpdateAsset`:
`rexpocket.apk` in this build, `rexpocket-netbird.apk` in the embedded one, never the add-on). When
the add-on is older, Settings › Tailnet and the tailnet's page say so and offer **Update the
add-on** (`AddonUpdates`): it reads the same releases feed, takes `rexpocket-netbird-addon.apk` of
the release of the app's own version (else the newest older one that carries it, never a newer
one: `addonReleaseFor`), checks the size and SHA-256 the feed gives, and opens Android's installer,
asking first for the install permission as the app's updater does. Android installs it only over
an add-on signed with the same key. A release without the add-on opens the releases page instead.
Once installed, the binding dies, is made again, and the new add-on is asked again what it speaks;
the add-on's package broadcasts make the page show or hide Accounts at once.

**Whose add-on.** The service checks every caller for the signature permission. RexPocket checks
the other way too: before it binds, the add-on's package must be signed with RexPocket's own key
(`checkSignatures`). Without that, an app of another key installed under the add-on's name, with
the real add-on absent, would be bound and handed setup keys and connections; now it is refused
with "signed with different keys".

**When the add-on dies.** The binding uses `BIND_AUTO_CREATE`, so Android binds again after the
add-on's process ends. Meanwhile the state reads "Starting", with "The Netbird add-on stopped.
RexPocket is starting it again." as its health line, so the status shows it; once bound, the client
starts the node again with the same account and settings, without a setup key (the add-on kept its
registration). A dead binding (the add-on updated or removed) is made again. Not installed, signed
otherwise, or not answering in 10 s: the start fails with that reason. The Tailnet dialog says when
the add-on is missing and links to the releases (`NETBIRD_ADDON_URL`).

**In the background.** While RexPocket is on screen, its binding gives the add-on RexPocket's own
importance, so no foreground service is needed. When the last RexPocket activity stops and
background alerts are on (RexPocket keeps its connection open with its own `specialUse`
foreground service), RexPocket calls `keepRunning(true)`, and the add-on becomes a foreground
service of type `specialUse`, with a silent notification, for as long as its node runs; coming
back to the screen calls `keepRunning(false)`. A start without any screen (a boot, the watch
restarted by Android) has no activity that stops, so the wish follows background mode from the
box's creation, and background mode turned on or off while away is passed on too. Android 12 and later let it start one then because it
is bound by an app that may (RexPocket, just left the screen or in its own foreground service); if
Android refuses, the add-on logs it and may be stopped in the background like any app, and the
rebind above brings it back. When RexPocket unbinds or its process ends, the add-on stops its node.
The add-on asks for no notification permission: without it, its notification is hidden, and the
service still runs.

**Isolation.** The add-on's client in RexPocket lives in `app/src/addon/` (with
`src/testAddon` and `src/androidTestAddon`), compiled only into the default build;
`app/src/embedded/` is what the embedded build has in its place (a stored add-on choice runs the
embedded node). The shared code has one call into each: `addonEntries` in `TailnetNodes.of` and
`TailnetBackendNote` in the Tailnet dialog and `TailnetBackendRow` on the tailnet's page. Dropping the add-on later is: those two calls, the
`NetbirdAddon` backend, the `src/addon*` and `netbirdaddon*` directories, `DialedSocket` and the
dialer plumbing, `DialFd`/`StartPrivate`, and the add-on's CI steps. Dropping the embedded build
instead is `src/embedded`, the `rexpocket.netbird` property and its CI steps.

**Libraries.** The add-on's library is the Netbird package alone (`REXPOCKET_NETBIRD=addon
tools/build-tailnet.sh` writes `netbirdaddon/libs/tailnet-netbird-addon.aar`, not checked in): no
Tailscale, 39.1 MB of native code against 51.5 MB for the embedded library. It cannot be the
embedded one's output without carrying Tailscale it never runs, and `app/libs/tailnet.aar` is not
touched, so the default APK's native code stays as it was.

## Code layout

- `tailnet/` (Go): the Tailscale node, unchanged, and `tailnet/netbird/`: the Netbird node behind
  the `netbird` tag, `platform.go` (the shared `Platform` interface), and `unavailable.go` (the stub).
- `core/` (pure Kotlin, JVM-tested): `TailnetBackend` (the stored choice), `TailnetState`
  (`parseTailnetState`, `parseTailnetPath`: the JSON both nodes report), `TailnetPath.Relay`, and
  `HostAddress.isTailnet` with `.netbird.cloud`.
- `app/…/net/`: `TailnetNode` (the interface the view model uses), `TailnetBox` (the lifecycle,
  peers, probing, and network-change handling both backends share), `TailscaleBox` and `NetbirdBox`
  (open the node and say how their platform hooks work).
- The add-on: `netbirdaddon/` (the app, its `NodeService`, and `Nodes`, its node and accounts),
  `netbirdaddon-api/` (the AIDL, `NetbirdAddon`'s names, versions and account names, the
  permission), `app/src/addon/` (`AddonClient`, `NetbirdAddonBox`, `FdStream`, `AddonUpdates`, the
  dialog's note and the page's row), `core/…/DialedSocket.kt`, and `tailnet/netbird/dialfd.go`.
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

- **`android-apk`** builds the three debug APKs above for arm64, with libghostty, checks that they
  carry the kept key, that `rexpocket.apk` asks for the add-on's permission and
  `rexpocket-netbird.apk` has nothing of the add-on, and reports their sizes.
- **`Netbird e2e`** brings up `netbirdio/netbird-server` 0.83.2 (management, signal, relay, STUN) and
  an OpenSSH peer in Docker (`tools/ci/netbird-e2e/`). The setup key comes from the server's
  unauthenticated `/api/setup` bootstrap and its REST API, with no login. `tailnet/netbird/e2e_test.go`
  (tags `netbird e2e`) runs the app's Go node against it. The node joins and reaches Running and lists
  the peer. Ping returns a path, relayed in CI. The test reads `SSH-2.0` from the peer's port 22
  through the SOCKS5 proxy by its name. It then restarts without the key and does it again, and
  once more the add-on's way (`StartPrivate`, no proxy), reading the banner through `DialFd` and
  checking that a refused port fails with a reason. Its second job, `addon`, runs the add-on's
  path against the same control plane on an API 34 emulator: accounts, the add-on killed and back,
  and add-ons of an older revision and another version (see Tests).
- **`device-test`** runs every instrumented test class on x86_64 emulators, API 30 and 34, with an
  x86_64 build of both native libraries (`-Prexpocket.abi=x86_64`, test-only; release builds stay
  arm64). The x86_64 build is needed because the emulator's ARM translation stops with SIGILL on Go's
  CPU-feature probe (`mrs x0, midr_el1`), which real arm64 phones handle. The run covers app launch,
  Netbird library load, start errors, the terminal, and the IME: 22 of 22 pass. Then it installs
  the x86_64 add-on and runs the default build's `me.leaver.rexpocket.net.addon` tests:
  `FdStreamTest` (the dialed socket on a real socket pair: both ways, half close, read timeout, a
  close waking a read) and `NetbirdAddonDeviceTest` (the service's signature permission, which the
  shell's uid lacks; bind, version, configure and state through the binding, with the add-on's
  "setup key" error coming back; and, where `am crash` exists, the add-on's process ending and
  being bound again).

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
- **The add-on's foreground start at boot.** Android 12 and later let a bound service start its
  foreground service only while the binder may; RexPocket's watch is a foreground service then, so
  it should, but this is checked on the emulator's boot path only through the app's own watch.
- **Network changes on Netbird.** `NetbirdRunning.networkChanged` does nothing, because the embedded
  client exposes no rebind; Netbird's engine is relied on to follow the network.
- **Interactive Netbird login.** The embedded client has no SSO flow, so a node joins only with a setup
  key.
- **Self-hosted names.** A self-hosted Netbird's domain is not recognised when a host is added; those
  peers need their `100.x` address. Each name would need a setting to be recognised. Once dialing,
  any peer name the node knows resolves.
- **Shared addresses.** Tailscale and Netbird both use `100.64/10`, so such an address goes to
  whichever node is joined.

## Accounts

The embedded Netbird node takes its state directory at start, so it has accounts as Tailscale
does (docs/design/tailnet-control.md, "Accounts"): the first in `files/netbird`, others in
`files/netbird-profiles/<id>`, and `NetbirdConfig.profile` restarts the node on a switch. A new
account has no registration, so it joins with its setup key in Tailnet settings: the dialog shows
the setup key field again once the node failed to start (before, it hid the field as soon as the
backend was joined, so a second account had no way to join, embedded or not).

The add-on has accounts the same way since revision 2: `startProfile(profile, …)` runs account
`profile` on a registration in the add-on's own `files/netbird-profiles/<name>`, the first account
(`NetbirdAddon.FIRST_PROFILE`, RexPocket's `netbird-addon`) staying in `files/netbird` so an add-on
that joined keeps its registration. The name is cleaned by `NetbirdAddon.profileDirectory`
(letters, digits, dashes), and the add-on refuses a name that leaves nothing, so no caller reaches
outside that directory. One account runs at a time; a start of another stops the running one.
Removing an account (`forgetProfile`) deletes its registration in the add-on; not the one running,
nor the first. When the add-on is away, the removal is kept (`ForgetQueue`, with the app's settings)
and done at the next binding. Whether the tailnet's page shows its Accounts group follows the
add-on: `TailnetNode.keepsAccounts`, a flow the add-on's box sets from its revision, so an add-on of
revision 1 shows none and offers its update instead, and the accounts come back once it is updated.

## Feature matrix

Every tailnet feature, for the three ways the app runs a node: before this change and after it.
The lifecycle, peers, probing and control (`TailnetBox`, `TailnetControl`) are shared, so most
rows were the same from the start; the add-on column is where the work was.

| Feature | Tailscale (embedded) | Netbird (embedded) | Netbird add-on, before | Netbird add-on, after |
| --- | --- | --- | --- | --- |
| Join | sign-in link, or an auth key | setup key; management URL | the same as embedded | the same |
| Interactive sign-in (SSO) | yes | no (see below) | no | no |
| Status, devices, path in the tailnet sheet | yes | yes; relay, latency | yes: the same JSON through Binder | the same |
| Test connection's route check | through SOCKS | through SOCKS | dials through the add-on | the same |
| Connect and Disconnect by hand | yes | yes | yes | yes |
| Only when needed, Stop after | yes | yes | yes | yes |
| In the background: Follow, Always, Let go | yes | yes | yes, once an activity had stopped | yes, also from a start without a screen |
| Boot and sticky restart | yes | yes | the node started, but the add-on was never asked to stay in the foreground | asked from the start (background mode) |
| Quick Settings tiles, watch notification line | yes | yes | yes | yes |
| Accounts: add, switch | yes | yes | no (`accounts = false`) | yes, with an add-on of revision 2; an older one: hidden, Update offered |
| A new account joining with its key | signs in | no: the dialog hid the setup key once joined | - | yes (fixed for both Netbird builds) |
| Removing an account | its directory deleted | its directory deleted | - | deleted by the add-on; kept until the add-on is back |
| Diagnostics | node events | node events | node events, add-on bound, died, back | also the add-on's version name and revision, removals |
| Updates | with the app | with the app (`rexpocket-netbird.apk`) | none: the add-on was "1.0" for ever and only a link to the releases | carries the app's version; Update the add-on downloads, checks and installs the release's add-on |
| Versions | - | - | one number, an exact match; "install both from one run" | base version and revision; an older add-on still works; the message says which side to update |
| Errors and needs-sign-in | the node's words; login link | the node's words | the node's words; add-on missing, other key, no answer | also: which side is older, too old for a second account |
| Signature checks | - | - | the add-on checked RexPocket's permission | and RexPocket checks the add-on's key before binding |
| The add-on's process killed | - | - | bound again, the first account restarted | bound again, the same account restarted |
| The add-on updated under a running node | - | - | binding made again | made again, revision asked again, Accounts shown or hidden at once |
| Network changes | rebinds at once | the engine follows on its own | the same as embedded | the same (see below) |

Not done, for both Netbird builds alike:

- **Interactive sign-in.** Netbird's embedded client takes a setup key, a private key, or a JWT
  (`embed.Options.JWTToken`). An SSO join would mean the app running the IdP's device or PKCE flow
  itself, with the IdP settings read from the management server, which `client/embed` does not
  expose. Setup keys work for Netbird Cloud and self-hosted alike.
- **A network change passed on.** `client/embed` has no rebind or network-change call (its API is
  start, stop, dial, listen, status). The only lever is stopping and starting the engine, which
  drops every connection on it for a few seconds at each Wi-Fi to mobile hand-over; Netbird's own
  ICE restarts a peer connection that breaks, so nothing is done.

## Licensing

The app links only Netbird's BSD-3-Clause client code; the build pulls in no `management/`, `signal/`,
`relay/`, or `combined/` directories, which are AGPLv3. The end-to-end workflow runs Netbird's
server image in CI only; nothing from it ships.

## Tests

The add-on's path:

- `netbirdaddon-api` (JVM): `InterfaceTest`: every call keeps its transaction code and the later
  ones come after; the base version stays 1; an account's directory name is the same on both sides.
- `netbirdaddon` (JVM): `NodesTest`: the first account in `files/netbird`, another in a directory of
  its own, a name kept inside the accounts directory, the same settings keeping the node, another
  account stopping the running one first, a failed start, removal and what it refuses.
- `app` (JVM, `src/testAddon`): `AddonClientTest`, beside the cases below: an older or newer base
  version and the message for each; an add-on of revision 1 running the first account and refusing
  a second without making the call; an account restarted on its own registration after the
  add-on's process ends; a removal done by the add-on and the binding let go, kept while it is
  away, not asked again once refused; an add-on of another key never bound; when an add-on is older.
  `AddonUpdatesTest`: the release of the app's version first, else the newest older one with the
  add-on, never a newer one, the add-on's APK by name while the updater keeps the app's.
  `TailnetAccountsTest.aNodeThatCannotKeepAccountsShowsNoneUntilItCan` (sharedTest): Accounts
  follow `keepsAccounts`.
- `device-test` (`NetbirdAddonDeviceTest`): also the add-on's revision from its package and its
  binding, a new account refused without a key, a name outside the accounts refused by the add-on
  itself, a removal of an account that never ran.
- `Netbird e2e`, job `addon` (`NetbirdAddonE2ETest` on an API 34 emulator, against the Docker control
  plane and SSH peer, the emulator naming `netbird.e2e` through a dnsmasq container): the first
  account joins, reads the peer's SSH banner through the add-on's dial by name and by address, and
  comes back without its key on the same address; a second account is refused without a key, joins
  with it as a peer of its own and reads the banner; back on the first; the second removed, and its
  registration gone. The add-on's process crashed (`am crash`): noticed, bound again, the node back
  on its account, the banner read again. Then an add-on built as revision 1 installed over it
  (`-Prexpocket.addonRevision=1`): the first account runs, a second is refused with "too old",
  Accounts hidden, the add-on older. Then one of another base version (`-Prexpocket.addonVersion=2`):
  refused with "Update RexPocket". Then the current add-on again: accounts as at first.

The rest:

- `core`: JVM tests for the Netbird name rule, the state and path parsers, the backend choice, and the
  relay case.
- `app`: `TailnetBoxTest` (node-name cleaning) and the instrumented `TailnetNodeInstrumentedTest`;
  for the add-on, `AddonClientTest` (a fake binding and add-on: bind and start, add-on missing,
  another version, another key, no answer, process death and the restart, a dead binding, the
  background wish) and the device tests above.
- `core`: `DialedSocketTest`: OkHttp through a dialer, by name and by address, a read timeout, a
  failed dial, and the socket's own state.
- `tailnet` (Go): the Tailscale tests, and the Netbird package with and without the `netbird` tag:
  saved-key read, start errors, the stub, the ping JSON fields, peer-name resolution, the resolver,
  and ping wake-up; plus the end-to-end test above.
