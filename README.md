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

The phone and the computer must share a network, or sit inside your own VPN / Tailscale route. The app uses `ws://host:7297/api/control` with the `rex.control.v1` subprotocol by default.

## On the phone

1. Add a host: name, IP or domain, port `7297`, plaintext on.
2. Tapping a host always lands on the session list. Opening a session goes back to the pane the phone showed there last time. The first time, or when that pane is gone, it shows the tab and pane Rex has focused, the same place the desktop is on.
3. Session → tab → pane. Rex calls a tab a window. Under the header, the tab bar lists the session's tabs: tap one to switch, and Rex brings back the pane that tab last had focused. A tab holding several panes shows their count, and below it a pane strip lists them by number. The phone draws only one pane at a time, so a desktop split shows up as chips here rather than side by side. `+` on either row adds a tab or a pane. Long-press a tab or pane chip for a sheet to rename or close it; closing asks first.
4. Terminal: drag down to read scrollback, and the arrow at the bottom right returns to following the output. Pinch to change the font size, long-press to select and then tap `Copy` in the toolbar.
5. Toolbar: `Esc`, `Ctrl` (double-tap to lock), `Tab`, `/`, arrows, `C-c` `C-d` `C-z` `C-l`, paste and copy.
6. The composer writes into the terminal. Tap `Send` to send the text followed by Enter; long-press it to send a single Enter without the text.
7. `Sessions` opens a list of the sessions on this Rex; tap one to switch to it. `···` opens a sheet of actions grouped by level: pane, tab, session. While a program is working or waiting for you the header shows `Stop`, which sends `Ctrl-C`.

## Languages

The UI ships in English and Simplified Chinese. English is the default (`values/`), Chinese lives in `values-zh/`, so an English device starts in English and a Chinese device starts in Chinese. Per-app language selection works on Android 13 and later.

## Build

Requires Android SDK 36 and JDK 17.

```sh
./gradlew :core:test :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
