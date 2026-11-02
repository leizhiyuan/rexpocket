<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

# RexPocket

A Rex client for your phone: one list of hosts with their sessions under them, and a terminal that fills the screen with a floating dock at its foot. It only talks to your own Rex, never through a third party.

- **The screen is streamed, not polled.** Rex serves the pane over `/api/data` (`rex.data.terminal.v1`): the stream opens with a `GHOSTSNP` snapshot of the whole terminal - scrollback, colours, cursor, and the escape sequence the program is in the middle of - then carries its output as it comes. An idle pane costs nothing; the phone measured 2 CPU ticks and 0.3 KB per 20 seconds, where polling cost 107 ticks and 500 KB. [libghostty-vt](https://github.com/sagernet/libghostty-android) restores the snapshot and is fed the rest in order, so a reconnect shows the pane as it is, not a replayed history.
- **What you type arrives in order.** Typing in the pane, the keys in the dock and a paste all go out through one queue; Rex only orders some of its methods, so they are sent one at a time, each after the reply to the one before.
- **The app is quiet in the background.** Nothing is polled while it is not on screen, and the connection stays open.
- **It says what went wrong, in words.** Unreachable, no answer, dropped, refused by Rex, tailnet not joined: each has its own line, with **Retry now** on both screens. A change of network redials at once.
- **The pane is yours while you look at it.** It is held with `session.own_block` and sized to the phone. If the desktop takes it back - Rex does that when its own window is focused - the app takes it back within a few seconds and reapplies the phone's size, so the pane is never drawn at the desktop's width with its right side cut off. The menu says which way that is - **Release** while the phone holds the pane, **Take over** once it has been handed back - and the tile carries a blue dot while the desk holds it. A release is not undone by the take-back loop, and it belongs to the pane: moving to another tab, or another session, does not take it - the pane stays the desk's until you take it again.

## Screenshots

| Home | Terminal | Terminal at night |
| --- | --- | --- |
| ![Home](docs/screenshots/home-en.png) | ![Terminal](docs/screenshots/terminal-en.png) | ![Terminal at night](docs/screenshots/terminal-dark-en.png) |

## On the computer

Expose a plaintext port only on a network you trust. Anyone who can connect has full access to that Rex.

```sh
rex server start --extra-listen :7297 --allow-anonymous
```

The phone and the computer must share a network, or sit inside your own VPN / Tailscale route, or the app can join your tailnet itself - see [Tailscale](#tailscale). The control connection is `ws://host:7297/api/control` with the `rex.control.v1` subprotocol, and the pane's stream is `/api/data` on the same host.

## On the phone

1. **Home.** One list, three levels. A host is a section, a session is a group under it, and the session's tabs are the rows: a tile, the tab's name, how many panes it holds, and a chevron. The session line carries its tab count, the plus that makes a tab, and the fold. The host line says whether it is online and, when it is the one connected, how the tailnet carries it. Tapping a tab opens it - the session is attached, the tab focused - and on a host that is not connected the tap connects first and says *Opening*. A session whose tabs are not loaded yet says *Open to see tabs*. The eye keeps only what is active right now: a pane that is working or waiting. The search button filters hosts, sessions and tabs; the gear opens settings - Tailscale, add host, source. A host's menu starts a new session, edits the host or disconnects it. A host is a name and an address: `192.0.2.10` is port 7297 over plaintext, `host:7300` picks the port, and a `.ts.net` name or a `100.x` address goes through Tailscale on 443 with TLS; the line under the field shows what the app made of it, and deleting a host asks first.
2. **The top bar.** The session screen floats its chrome over the terminal: a round back button, then the pane's tile, the tab name with its directory and, under it, the session with its state and a chevron. That whole block opens the session's tabs and panes, with the session list at the top; the menu holds the actions. A blue dot on the tile means the pane is not this phone's right now.
3. **The terminal.** It fills the screen and is typed into directly: tap it to raise the keyboard. Drag to read scrollback; pinch to change the font size; long-press to select and then tap `Copy` in the key pad. Until Rex sends the pane's first snapshot the area says *Reading the screen…*, and offers Retry if it takes long - a pane with a long history arrives as several megabytes.
4. **The dock.** One floating pill at the foot: the keyboard (it turns into keyboard-hide while the keyboard is up), Return, and the keys. Return sends the Enter key; the key pad holds `esc`, `ctrl` (double-tap to lock), Tab, `/`, the arrows, `C-c`, `C-d`, `C-z`, `C-l`, paste, copy and the arrow pad, and closes again so it is not a permanent row. The circle beside the pill makes a tab. `esc`, Tab, the arrows and `/` are keys for the program in the pane, so `/` opens pi's command menu; while a program is working or waiting for you, `C-c` is named `Stop` and turns red. `ctrl` applies to the next key pressed in the pad.
5. **Panes on a phone.** The phone draws one pane at a time, so a desktop split gets an indicator bar under the terminal: one segment per pane with its number and name, the one on screen highlighted. Tap a segment, or swipe the pane left and right, to move between them. Swiping is the only gesture read on the pane; scrolling and selection are the terminal's.
6. **Release and Take over.** They sit in the menu, and they belong to the pane: a release hands it to the desk, stops the phone's resize and turns the retake loop off; it survives switching tabs and sessions, and only **Take over** ends it. `own_block` and `disown_block` are serialized, so a take in flight cannot undo a release.
7. **Appearance.** The app follows the phone: light grouped paper in the day, near-black at night. The terminal paper changes with it, and so do the system bars.

## Tailscale

The app can join your tailnet itself, so a host there is reachable by its MagicDNS name (`host.tailnet-xxxx.ts.net`) or its `100.x` address without the Tailscale app and without taking the phone's one VPN slot. The node runs inside the app (sing-box's Tailscale endpoint): only the connections this app opens for Rex go through it, the rest of the phone is untouched.

1. The `Tailscale` row on the home screen asks for this phone's node name; saving joins the tailnet: the app starts its node and, when it has never been authorized, opens the authorization page in a browser by itself. The login stays in the app, so this happens once.
2. The row says what the node is doing: starting, running, login needed, waiting for approval, or failed - with the reason one tap away. A start that fails on libbox's cache file - which a process killed mid-run can leave behind - drops that cache and tries once more, so the node does not stay down until the app's data is cleared.
3. A tailnet connection is dialed through a SOCKS5 inbound on loopback that sing-box routes into the tailnet, so a MagicDNS name is resolved by Tailscale and a `100.x` address is dialed by it. The same route carries the pane's stream as well as the control connection.
4. **The node's UDP goes around a VPN on the phone.** A proxy VPN, such as Clash, would otherwise carry the node's WireGuard and hole-punching packets too: the node then sees the proxy's address as its own, a direct path cannot form, and handshakes through the proxy stall until the pane never arrives. So the node binds its UDP sockets to the phone's own network (Wi-Fi or mobile data), and leaves its TCP - to the control plane and to DERP - on the default route, where a blocked `tailscale.com` is reached through the proxy. Android allows this only when the VPN lets apps bypass it; Clash does. These packets are then outside the VPN's rules.
5. **The path is shown.** For a host on the tailnet, the line under its name on the home list says how the phone reaches it - `direct`, `through a peer relay`, or `relayed by DERP sfo` - with the round trip in milliseconds; tap it to measure again. On the same Wi-Fi it is direct. From mobile data to a computer behind a home router it is usually a DERP relay abroad, a few hundred milliseconds away, and the first picture of a pane takes several seconds.
6. **Making it direct is the network's business.** The computer's node has to be reachable from outside: UPnP or NAT-PMP on the home router, or a port forward for its Tailscale UDP port, lets the phone punch through. Failing that, a DERP server or a peer relay in the same country brings the relay from abroad to tens of milliseconds; both are set up in the tailnet, and the app follows without a change.

The library is arm64-v8a only: `app/libs/libbox.aar`, ~57 MB unpacked, built from sing-box 1.14.4 by [tools/build-libbox.sh](tools/build-libbox.sh). Rebuild it for another ABI, or with other protocol tags, if you need them.

## Acknowledgements

- [Rex](https://rex.app), by [Superlogical](https://superlogical.com), is the multiplexer this client talks to: its RPC, its session model and its pane ownership are the whole reason this app exists.
- [libghostty-android](https://github.com/SagerNet/libghostty-android), by [SagerNet](https://github.com/SagerNet), provides the VT bindings and the terminal components the pane is drawn with.
- [Ghostty](https://ghostty.org) ([ghostty-org/ghostty](https://github.com/ghostty-org/ghostty)), by Mitchell Hashimoto and its contributors, is the terminal emulator underneath.
- [Material Symbols](https://github.com/google/material-design-icons) (Rounded), by Google under the Apache License 2.0, are the icons.
- [sing-box](https://github.com/SagerNet/sing-box) and its [libbox](https://github.com/SagerNet/sing-box/tree/dev/platform) Android library carry the Tailscale node the app runs for hosts on a tailnet.

## Languages

The UI ships in English and Simplified Chinese. English is the default (`values/`), Chinese lives in `values-zh/`, so an English device starts in English and a Chinese device starts in Chinese. Per-app language selection works on Android 13 and later.

## Build

Requires Android SDK 36 and JDK 17.

```sh
./gradlew :core:test :app:assembleDebug
```

`app/libs/libbox.aar` ships with the repository (see [Tailscale](#tailscale)), and the terminal's emulator, `libghostty-android`, comes from Maven Central, so a plain checkout builds without any further toolchain.

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`; `:app:assembleRelease` writes one signed with the debug key, for personal builds. Both are arm64-v8a only, and the application id is `me.leaver.rexpocket` - a phone that has the older `app.rexpocket` keeps it as a separate app, with its own hosts and its own tailnet login.
