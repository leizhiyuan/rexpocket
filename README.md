<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

# RexPocket

A Rex client for your phone. The interaction follows [Moshi](https://getmoshi.app/docs/rex): a host list, session picker, tabs and panes in the terminal header, and a toolbar of special keys. It only talks to your own Rex, never through a third party.

The screen is replayed from the server's `com.superlogical.terminal.format` (`vt`) dump, polled roughly every 180 ms while idle and every 30 ms for a moment after input, so a swipe in a full-screen program such as pi, which turns into wheel reports, comes back quickly. That dump is a linear replay of the whole scrollback plus the screen, so rendering is handed to [libghostty-vt](https://github.com/sagernet/libghostty-android): the first dump resets with `RIS` and replays in full, and later polls feed only the appended tail, so the native scroll position is not yanked back to the bottom while you type or read. The pane you are viewing is held with `session.own_block` and sized to the phone; leaving the session or disconnecting `disown`s it so the desktop can take the size back.

Sending a line means writing the text and then pressing Enter as a separate key event. Full-screen TUIs such as pi read a newline inside pasted text as a line break in their own input box, so a newline bundled with the text only breaks the line instead of submitting it.

## Screenshots

| Hosts | Sessions | Terminal |
| --- | --- | --- |
| ![Hosts](docs/screenshots/home-en.png) | ![Sessions](docs/screenshots/sessions-en.png) | ![Terminal](docs/screenshots/terminal-en.png) |

## On the computer

Expose a plaintext port only on a network you trust. Anyone who can connect has full access to that Rex.

```sh
rex server start --extra-listen :7297 --allow-anonymous
```

The phone and the computer must share a network, or sit inside your own VPN / Tailscale route, or the app can join your tailnet itself - see [Tailscale](#tailscale). The app uses `ws://host:7297/api/control` with the `rex.control.v1` subprotocol by default.

## On the phone

1. Add a host: name, IP or domain, port `7297`, plaintext on.
2. Tapping a host always lands on the session list. Opening a session goes back to the pane the phone showed there last time. The first time, or when that pane is gone, it shows the tab and pane Rex has focused, the same place the desktop is on.
3. Session → tab → pane. Rex calls a tab a window. Under the header, the tab bar lists the session's tabs: tap one to switch, and Rex brings back the pane that tab last had focused. A tab holding several panes shows their count, and below it a pane strip lists them by number. The phone draws only one pane at a time, so a desktop split shows up as chips here rather than side by side. `+` on either row adds a tab or a pane. Long-press a tab or pane chip for a sheet to rename or close it; closing asks first.
4. Terminal: drag down to read scrollback, and the arrow at the bottom right returns to following the output. Pinch to change the font size, long-press to select and then tap `Copy` in the toolbar.
5. Toolbar: `Esc`, `Ctrl` (double-tap to lock), `Tab`, `⏎`, `/`, arrows, `C-c` `C-d` `C-z` `C-l`, paste and copy.
6. The composer writes into the terminal. `Send`, or the keyboard's own send key, writes the text and then presses Enter; with the box empty it presses Enter alone, which is how a full-screen program such as pi confirms the item its menu has selected. Long-press `Send` to send Enter without the text.
7. `Esc`, `Tab`, the arrows, `⏎` and `/` are keys for the program in the pane, so `/` opens pi's command menu and the arrows walk it. Typing a command such as `/model` in the composer and sending it lands the text in pi, where the menu takes the Enter that follows the text to accept the completed command, so tap `⏎` to run it.
8. `Sessions` opens a list of the sessions on this Rex; tap one to switch to it. `···` opens a sheet of actions grouped by level: pane, tab, session. While a program is working or waiting for you the header shows `Stop`, which sends `Ctrl-C`.

## Tailscale

The app can join your tailnet itself, so a host there is reachable by its MagicDNS name (`host.tailnet-xxxx.ts.net`) or its `100.x` address without the Tailscale app and without taking the phone's one VPN slot. The node runs inside the app (sing-box's Tailscale endpoint): only the connections this app opens for Rex go through it, the rest of the phone is untouched.

1. `Tailscale` on the home screen: switch it on and save. Paste an auth key from the Tailscale admin console (`Settings → Keys`; a reusable, pre-authorized key is convenient) or leave the field empty to authorize the node by link instead.
2. Edit the host and turn on `Via Tailscale`. That connection is then dialed through a SOCKS5 inbound on loopback that sing-box routes into the tailnet, so a MagicDNS name is resolved by Tailscale and a `100.x` address is dialed by it.
3. The first connection starts the node. While it still needs a login, the session list shows a banner: tap it to copy the login link, authorize the node in a browser, and come back - the connection that was retrying goes through. The node keeps that login, so this happens once.
4. The home screen row and the `Tailscale` dialog show the node's state (`Starting`, `Login needed`, `Running`) and the node name the tailnet sees; the field stays empty for a device name and falls back to the model name. Leaving Rex stops the node again.

The library is arm64-v8a only: `app/libs/libbox.aar`, ~57 MB unpacked, built from sing-box 1.14.4 by [tools/build-libbox.sh](tools/build-libbox.sh). Rebuild it for another ABI, or with other protocol tags, if you need them.

## Languages

The UI ships in English and Simplified Chinese. English is the default (`values/`), Chinese lives in `values-zh/`, so an English device starts in English and a Chinese device starts in Chinese. Per-app language selection works on Android 13 and later.

## Build

Requires Android SDK 36 and JDK 17.

```sh
./gradlew :core:test :app:assembleDebug
```

`app/libs/libbox.aar` ships with the repository (see [Tailscale](#tailscale)), so a plain checkout builds without any extra toolchain.

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
