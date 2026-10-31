<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

# RexPocket

A Rex client for your phone. The interaction follows [Moshi](https://getmoshi.app/docs/rex): a host list, a session picker, tabs and panes in the terminal header, and a toolbar of special keys. It only talks to your own Rex, never through a third party.

- **The screen is streamed, not polled.** Rex serves the pane over `/api/data` (`rex.data.terminal.v1`): the stream opens with a `GHOSTSNP` snapshot of the whole terminal - scrollback, colours, cursor, and the escape sequence the program is in the middle of - then carries its output as it comes. An idle pane costs nothing; the phone measured 2 CPU ticks and 0.3 KB per 20 seconds, where polling cost 107 ticks and 500 KB. [libghostty-vt](https://github.com/sagernet/libghostty-android) restores the snapshot and is fed the rest in order, so a reconnect shows the pane as it is, not a replayed history.
- **What you type arrives in order.** Keystrokes from the terminal view, the toolbar and the composer go out through one queue; Rex only orders some of its methods, so they are sent one at a time, each after the reply to the one before.
- **The app is quiet in the background.** Nothing is polled while it is not on screen, and the connection stays open.
- **It says what went wrong, in words.** Unreachable, no answer, dropped, refused by Rex, tailnet not joined: each has its own line, with **Retry now** on both screens. A change of network redials at once.
- **The pane is yours while you look at it.** It is held with `session.own_block` and sized to the phone. If the desktop takes it back - Rex does that when its own window is focused - the app takes it back within a few seconds and reapplies the phone's size, so the pane is never drawn at the desktop's width with its right side cut off.

## Screenshots

| Hosts | Sessions | Terminal |
| --- | --- | --- |
| ![Hosts](docs/screenshots/home-en.png) | ![Sessions](docs/screenshots/sessions-en.png) | ![Terminal](docs/screenshots/terminal-en.png) |

## On the computer

Expose a plaintext port only on a network you trust. Anyone who can connect has full access to that Rex.

```sh
rex server start --extra-listen :7297 --allow-anonymous
```

The phone and the computer must share a network, or sit inside your own VPN / Tailscale route, or the app can join your tailnet itself - see [Tailscale](#tailscale). The control connection is `ws://host:7297/api/control` with the `rex.control.v1` subprotocol, and the pane's stream is `/api/data` on the same host.

## On the phone

1. **Hosts.** A host is a name and an address. `192.0.2.10` is port 7297 over plaintext, `host:7300` picks the port, and a `.ts.net` name or a `100.x` address goes through Tailscale on 443 with TLS; the line under the field shows what the app made of it. Save is disabled until the address says where to connect, deleting a host asks first, and the `Tailscale` row above the list is where the phone's own node is joined.
2. **Sessions.** The list is drawn from what this host had last time, so it has content immediately, and the live list replaces it when the connection opens. A tap while the connection is still coming up waits for it and says *Opening*. New session waits for Rex to create it before switching, so the typing never lands in the session you were leaving. Opening a session goes back to the pane the phone showed there last time; the first time, or when that pane is gone, it shows the tab and pane Rex has focused, the same place the desktop is on.
3. **Session → tab → pane.** Rex calls a tab a window. The tab bar lists the session's tabs: tap one to switch, and Rex brings back the pane that tab last had focused. A tab holding several panes shows their count, and the pane strip below lists them by number. The phone draws one pane at a time, so a desktop split shows up as chips here rather than side by side. `+` adds a tab or a pane; long-press a chip for a sheet to rename or close it, and closing asks first.
4. **The terminal.** Drag to read scrollback; the arrow at the bottom right returns to following the output. Pinch to change the font size, long-press to select and then tap `Copy` in the toolbar. Until Rex sends the pane's first snapshot the area says *Reading the screen…*, and offers Retry if it takes long - a pane with a long history arrives as several megabytes.
5. **The composer.** `Send`, or the keyboard's own send key, writes the text and then presses Enter as a separate key event; full-screen programs such as pi read a newline inside pasted text as a line break, so it cannot ride along with the text. With the box empty it presses Enter alone, which is how pi confirms the item its menu has selected; long-press `Send` for that. Each pane keeps its own draft, sent lines can be recalled with a hardware keyboard's arrows, and `Ctrl` applies to the next character typed.
6. **The toolbar.** `Esc`, `Ctrl` (double-tap to lock), `Tab`, `⏎`, `/`, arrows, `C-c` `C-d` `C-z` `C-l`, paste, copy and the arrow pad. `Esc`, `Tab`, the arrows, `⏎` and `/` are keys for the program in the pane, so `/` opens pi's command menu and the arrows walk it. While a program is working or waiting for you, the header shows `Stop`, which sends `Ctrl-C`.
7. **The two menus.** `Sessions` lists the sessions on this Rex; `···` opens a sheet of actions grouped by level: pane, tab, session.

## Tailscale

The app can join your tailnet itself, so a host there is reachable by its MagicDNS name (`host.tailnet-xxxx.ts.net`) or its `100.x` address without the Tailscale app and without taking the phone's one VPN slot. The node runs inside the app (sing-box's Tailscale endpoint): only the connections this app opens for Rex go through it, the rest of the phone is untouched.

1. The `Tailscale` row on the home screen asks for this phone's node name; saving joins the tailnet: the app starts its node and, when it has never been authorized, opens the authorization page in a browser by itself. The login stays in the app, so this happens once.
2. The row says what the node is doing: starting, running, login needed, waiting for approval, or failed - with the reason one tap away. A start that fails on libbox's cache file - which a process killed mid-run can leave behind - drops that cache and tries once more, so the node does not stay down until the app's data is cleared.
3. A tailnet connection is dialed through a SOCKS5 inbound on loopback that sing-box routes into the tailnet, so a MagicDNS name is resolved by Tailscale and a `100.x` address is dialed by it. The same route carries the pane's stream as well as the control connection.
4. Reachability is the network's business: through a relay on the far side of a proxy, the two handshakes before a picture can take seconds, and a tunnel that is reset mid-transfer starts the pane over from a fresh snapshot.

The library is arm64-v8a only: `app/libs/libbox.aar`, ~57 MB unpacked, built from sing-box 1.14.4 by [tools/build-libbox.sh](tools/build-libbox.sh). Rebuild it for another ABI, or with other protocol tags, if you need them.

## Languages

The UI ships in English and Simplified Chinese. English is the default (`values/`), Chinese lives in `values-zh/`, so an English device starts in English and a Chinese device starts in Chinese. Per-app language selection works on Android 13 and later.

## Build

Requires Android SDK 36 and JDK 17.

```sh
./gradlew :core:test :app:assembleDebug
```

`app/libs/libbox.aar` ships with the repository (see [Tailscale](#tailscale)), and the terminal's emulator, `libghostty-android`, comes from Maven Central, so a plain checkout builds without any further toolchain.

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`; `:app:assembleRelease` writes one signed with the debug key, for personal builds. Both are arm64-v8a only, and the application id is `me.leaver.rexpocket` - a phone that has the older `app.rexpocket` keeps it as a separate app, with its own hosts and its own tailnet login.
