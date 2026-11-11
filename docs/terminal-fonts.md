# Terminal fonts

Open the home screen's Settings → Terminal font to choose system monospace or JetBrains Mono. The default remains system monospace. Only terminal output changes; the rest of the app uses its existing UI fonts.

Selecting JetBrains Mono downloads its regular, bold, italic and bold italic faces once, about 1.1 MB in total. Downloads come directly from the JetBrains Mono repository on GitHub at a pinned commit, with a SHA-256 check for each file. No font binaries are bundled in the APK. The SIL Open Font License is included in `app/src/main/assets/licenses/JetBrainsMono-OFL.txt`.

The complete verified family is saved in private application files and the selection in preferences. After downloading, it works offline, including after restarting the app or switching to system monospace and back. A failed or incomplete download keeps the previous selection; tap JetBrains Mono again to retry. Network access to GitHub is needed for the first download.

On Android 10+, a custom font family supplies all four styles with the same ASCII advance (0.6 em). Android 8–9 uses the downloaded regular face with synthetic styles. JetBrains Mono does not include Chinese glyphs; CJK continues to use Android font fallback.

## Why offer a separate monospace font?

RexPocket's terminal view measures `M` in the chosen typeface for the cell width and draws each cell at `column * cellWidth`; the default is `Typeface.MONOSPACE`. A device font substitution that resolves to a proportional face can therefore produce large gaps after narrow glyphs, even though ordinary UI text looks normal.

Issue #3 reports this on a Samsung Galaxy S10 running Android 12. The reporter confirmed that the first test build, with bundled JetBrains Mono, fixed the spacing. The actual runtime system font mapping has not been captured, so the OEM substitution mechanism remains a hypothesis rather than a verified device-wide diagnosis. The downloadable font provides the same independent fixed-width font choice without including its binaries in each APK.

The code: `TerminalView.measureFont` (cell measurement) and `TerminalView.drawText` (cell drawing) in `app/src/main/java/me/leaver/rexpocket/terminal/`.

## Validation

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The download store's tests cover verified offline reuse, checksum rejection, interrupted-download retry, corruption repair, abandoned partial downloads, and missing family members.

Device regression checks for the settings version:

1. On the S10, compare the same terminal pane at the same font size before and after selecting JetBrains Mono. Check `Explored`, `ManagedActionBase`, Chinese, regular/bold/italic styles, selection alignment, wrapping, resizing and reconnecting.
2. Restart offline, then switch to system monospace and back. The downloaded family should remain usable without a request.
3. Start a first download without network access. It should report failure without changing the active font; reconnect and retry.

The reporter has verified the initial bundled-font workaround. These checks for the subsequent settings/download UI still need device validation.
