<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a> · <a href="README.zh-TW.md">繁體中文</a>
</p>

# RexPocket

An Android client for [Rex](https://rex.app), the terminal multiplexer. It shows every session on
your own Rex servers, down to the tab, and opens any pane as a full-screen terminal you type into
directly. It talks to your Rex and nothing else - on the local network, or over your tailnet
through a Tailscale node built into the app.

| Home | Terminal | Terminal at night |
| --- | --- | --- |
| ![Home](docs/screenshots/home-en.png) | ![Terminal](docs/screenshots/terminal-en.png) | ![Terminal at night](docs/screenshots/terminal-dark-en.png) |

## Features

- **Every host, session and tab on one screen.** Each tab shows what runs in it - pi, Claude,
  Codex, an editor, or a shell - with its own mark and the title its program set, and a blue dot
  while a program in it is working or waiting for you.
- **A real terminal.** The pane is drawn by Ghostty's emulator from Rex's own stream: a snapshot of
  the whole terminal first, then its output as it comes. Colours, scrollback and full-screen
  programs are exactly as on the desk.
- **Made for the phone.** Typing goes straight into the pane. A floating dock holds the keyboard,
  Return and a key row: `esc`, `C-c`, arrows that repeat while held, Tab, `shift` / `ctrl` /
  `alt` for the next key (from the row or the phone keyboard), paste, `/` and copy. Hold `ctrl`
  for `C-c` `C-d` `C-z` `C-r` `C-l` `C-a` `C-e` `C-w` `C-j`. A split shows one pane at a time,
  with a bar to move between them.
- **Links.** Long-press a link a program printed, or one it set as a hyperlink, to open, copy or
  share it.
- **Files and images.** Pick any file on the phone and send it to the pane you are looking at.
  Rex stores it on the computer and types its path itself, so a screenshot can go straight into an
  agent's prompt.
- **Background alerts.** Optional: the app stays connected while it is away and notifies you when a
  program waits for approval, an answer or a login, or fails. Tap the alert to open that pane.
- **Works beside the desk.** The phone holds only the pane it shows and sizes it to the phone; the
  desktop keeps its own tab. **Release** hands a pane back to the desk until you **Take over**.
- **Your tailnet, without the Tailscale app.** The app runs its own Tailscale node, so a host is
  reachable by its MagicDNS name or `100.x` address without taking the phone's VPN slot.
- **Knows when it is old.** Once a day, and whenever Settings › Updates is tapped, the app asks its
  GitHub releases whether a newer build exists and says so; nothing is downloaded until you ask, and
  Android asks once for permission to install it. An alpha follows the alphas, a finished build only
  takes finished ones.
- **Light and dark,** following the phone, in English and Simplified Chinese.

## Set up the computer

Rex has to listen where the phone can reach it.

**On the local network.** Start Rex with a plaintext port:

```sh
rex server start --extra-listen :7297 --allow-anonymous
```

Anyone who can reach that port has full access to that Rex, so open it only on a network you
trust.

**Over a tailnet.** Turn on Rex's built-in Tailscale on the computer; it serves Rex over HTTPS
at its MagicDNS name. Nothing has to be opened on the router.

## Use the app

1. **Add a host.** Settings › Add host. A name and an address is all it takes:
   - `192.0.2.10` - plaintext on port 7297
   - `192.0.2.10:7300` - another port
   - `my-mac.tail1234.ts.net` or a `100.x` address - through Tailscale, on 443 with TLS

   The line under the field shows how the app will connect.
2. **For a tailnet host, join the tailnet.** Settings › Tailscale, give the phone a node name and
   save. The app opens the sign-in page once; after that the node starts with the app.
3. **Open a tab.** Tap a tab on the home screen. The plus beside a session makes a new tab; the
   host's menu starts a new session.
4. **In the terminal:**
   - Tap the pane to type. Drag to scroll back, pinch to change the font size, long-press to
     select, or long-press a link to open it.
   - The command key opens the key row. Tap `shift`, `ctrl` or `alt` for the next key, twice to
     keep it on: `shift` then Return is a new line in an agent's prompt. Hold `ctrl` and slide to a
     chord.
   - Tap the title for the session's tabs and panes, and the list of sessions.
   - Swipe left or right, or use the bar under the pane, to move between the panes of a split.
   - The menu holds Release / Take over, splitting, renaming and closing.
5. **On the home screen,** the eye keeps only the tabs with a program working or waiting, and
   search filters hosts, sessions and tabs.
6. **Background alerts:** Settings › Background alerts, and allow notifications. A quiet
   notification shows while the app watches; its **Stop** turns alerts off. The app does not start
   on its own after a reboot, and Android's battery saving can delay an alert.

## Reporting a problem

The app keeps the last few hours of its own log on the phone, so a problem can be reported after it
happened.

1. **Settings › Diagnostics.** The line says `Off` while the app only keeps its last few hours, and
   `Recording` while a recording runs.
2. If the problem already happened, tap **Share report** and attach the file to an issue. If it only
   shows up while you use the app, tap **Start recording**, reproduce it, then come back and tap
   **Stop and share**.
3. **Open an issue** copies the same report and opens the issue page: paste it, say what you were
   doing, and attach the file if you shared one.

The report holds the app version, the phone, your hosts, what the connection did, and the app's own
log - the last few hours, and a recording too if you made one. Addresses and host names become
`<hidden>`; tab titles, what a pane printed and what you typed are never in it. It is plain text, so
you can read it before you send it.

## How it works

- **Control.** One WebSocket to `/api/control` (`rex.control.v1`). The app subscribes to Rex's
  events, so a session's tabs, each program's status, its title and the shell's directory arrive
  as they change; nothing is polled. Calls run side by side; typed input has its own queue, so
  keys reach the program in the order they were pressed.
- **The pane.** A second WebSocket to `/api/data` (`rex.data.terminal.v1`) carries a `GHOSTSNP`
  snapshot of the terminal, then its output. A dropped stream resumes from the last byte; an idle
  pane costs no traffic.
- **Ownership.** The phone owns only the pane on its screen, with `session.own_block`, so Rex
  applies the phone's size to it. Moving between tabs and panes is local to the phone: Rex's
  active tab stays the desktop's. When the desktop takes the shown pane, the phone takes it back,
  waiting longer each time the desk insists. A released pane stays the desk's until Take over.
- **Tailscale.** The node is the official `tailscale.com/tsnet` library running in userspace,
  with a SOCKS5 proxy on loopback in front of it; only the app's own connections use it. The node's UDP is bound to the phone's Wi-Fi or mobile
  network, around a proxy VPN such as Clash when the VPN allows apps to bypass it, so a direct
  path can form. The home screen shows the path to each tailnet host - direct, peer relay or
  DERP - with its round trip; tap it to measure again.

### A faster tailnet path

From mobile data to a computer behind a home router, Tailscale often falls back to a DERP relay
abroad, and every round trip costs a few hundred milliseconds. Either make the computer reachable
(UPnP or NAT-PMP on the router, or a port forward for its Tailscale UDP port), or run your own
DERP server or peer relay close to you and add it to the tailnet. The app follows without a
change.

## Build

Requires JDK 17, the Android SDK (platform 36) with an NDK, and python3 with curl.

```sh
./gradlew :core:test :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`; `:app:assembleRelease` builds one signed with the
release key (see Releasing below). Builds are arm64-v8a only. The terminal is the official libghostty-vt, built from the
Ghostty revision pinned in [native/ghostty/source.json](native/ghostty/source.json) by
[tools/build-ghostty.py](tools/build-ghostty.py), which Gradle runs; the first build downloads Zig
and the Ghostty source, checked against their pinned checksums. To move to a newer Ghostty, change
the revision and checksum there. `app/libs/tailnet.aar` - the Go module in `tailnet/`, around
`tailscale.com` at the version its `go.mod` pins - is checked in; to build it again, run
[tools/build-tailnet.sh](tools/build-tailnet.sh). The integration
tests in `core` run against a Rex on `127.0.0.1:7297` (set `REX_TEST_HOST` / `REX_TEST_PORT`) and
are skipped without one.

### Releasing

The app updates itself from its GitHub releases, and Android only installs an update signed by the
same key as the installed app. So **every release is signed with one key, whatever computer builds
it**; a release signed with another key cannot be installed over the last, only after uninstalling,
which erases the app's hosts and tailnet login.

1. Tell Gradle where the release key is, in the untracked `local.properties` (or as Gradle properties or
   environment variables). A relative path is from the project root:
   ```properties
   REXPOCKET_KEYSTORE=release.keystore
   REXPOCKET_KEYSTORE_PASSWORD=...
   REXPOCKET_KEY_ALIAS=...
   REXPOCKET_KEY_PASSWORD=...
   ```
2. Raise `versionCode` and `versionName` in `app/build.gradle.kts`, and commit it as
   `build: release <version>`.
3. `./gradlew :app:assembleRelease`. The build stops if the key is not set; it never falls back to the
   debug key. Check the result with `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.
4. Publish a GitHub release tagged `v<version>` with that one APK attached, named
   `RexPocket-<version>.apk`. Mark it a pre-release for an alpha, beta or rc: an installed pre-release
   follows pre-releases, an installed finished build only takes finished ones. Do not replace the APK of
   a published release - a new fix is a new version.

## Acknowledgements

- [Rex](https://rex.app), by [Superlogical](https://superlogical.com) - the multiplexer, its API and
  its session model.
- [Ghostty](https://ghostty.org), by Mitchell Hashimoto and contributors, MIT - the terminal
  emulator: the app builds its official libghostty-vt library, license in `app/src/main/assets/licenses/Ghostty-MIT.txt`.
- [Tailscale](https://github.com/tailscale/tailscale), by Tailscale Inc. and contributors, BSD-3-Clause -
  the tsnet library the app's Tailscale node is.
- [Material Symbols](https://github.com/google/material-design-icons) (Rounded), by Google, Apache
  License 2.0 - the interface icons. The program marks are the app's own drawings, not the
  products' logos.
