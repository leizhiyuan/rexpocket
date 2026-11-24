# Testing the screens

How the app's UI is tested, so that a screen that looks right in a test also works on a phone.

## Why

Arrange keys shipped with tests that passed while the page did nothing on a phone: up and down
changed the stored order, but the list kept its old order on screen, and a drag could not move a
row. The tests checked the model and the key bar beside the page, never what the page itself showed
after a tap, and never dragged. (The cause was the sheet's row layout, a SubcomposeLayout, which did
not measure the group again when a keyed row moved; see `GroupRows` in `Sheets.kt`.)

## Rules for a screen test

1. **Drive the screen the app shows, in the container it shows it in.** A settings page is shown in
   its bottom sheet (`ActionSheet`), through the same function HomeScreen calls
   (`TerminalControlsPages`), not a copy or a part of it.
2. **Feed it production state.** Values come from the real `RexViewModel` (collected the way RexApp
   collects them) and changes go to it, which writes them to the real `HostStore`. No
   `mutableStateOf` stand-in for the model: a stand-in proves the stand-in works. See
   `ControlsOnModel` in `app/src/sharedTest`.
3. **Assert what the person sees after each action**: the order of rows on screen (their positions,
   not the model's list), a row's detail text, an eye's state description. Then what the store kept,
   and the page opened again.
4. **Use the gesture the person uses.** A drag is `performTouchInput { down; moveBy…; up }` on the
   handle, in small steps, inside the sheet, so the sheet's own scroll and drag are in play.
5. **A bug fix starts with a test that fails without it.**

## Where the tests run

- `app/src/sharedTest` holds the screen tests (`ArrangeKeysTest`, `TerminalControlsTest`, `TermMenuTest`,
  `TermFullScreenTest`, `CommandPaletteTest`, `ActivityViewTest`, `EmptyHomeTest`, `LargeFontTest`, `AppearanceTest`, `HomeManageTest`, `MultiHostTest`, `ShareTest`, `HostImportScreenTest`, `OtherAddressesTest`, `TilesScreenTest`, `QuickRepliesScreenTest`, `TermAgentTest`, `TermShortcutsTest`, `ProgressViewTest`). They are
  compiled into both the JVM unit tests and the instrumented tests:
  - on the JVM with Robolectric, in seconds on any computer:
    `./gradlew -x :app:buildGhostty -x :app:buildGhosttyHost :app:testDebugUnitTest --tests 'me.leaver.rexpocket.ui.*'`
    (with an empty `native/.build/host` when libghostty's host build is not there);
  - on the emulator in `device-test`, each class listed in its test script.
- Robolectric's Android jar comes through Gradle's repositories (`robolectricAndroid` in
  `app/build.gradle.kts`), not from Maven Central at run time. It wants Java 21.

## Screenshots

`ScreenshotsTest` (JVM, Roborazzi) captures the terminal screen with the key bar, Terminal controls
(as it comes, and with every choice changed, so its icons show the change) and Arrange keys,
Settings and Diagnostics (on ScriptedRex), the terminal's ⋮ menu, the command palette, the home screen's Needs you filter (on
ScriptedRex, its programs reporting statuses) and full screen right after entering it, in portrait and
landscape on a Pixel 6-sized screen, the tailnet's states on the home screen and its details sheet
(`tailnet-*`, on a stand-in node: see tailnet-status.md), the first run with no hosts (`empty-home-*`), the share sheet and its pane picker (`share-*`), Find open over a terminal whose tab and panes show progress bars (`terminal-find-progress-*`), the keyboard shortcuts sheet (`terminal-shortcuts-*`), the home's tab sheet (`home-tab-sheet-*`) and Import hosts' preview and keys (`host-import-*`, host-import.md), (the JVM tests' default too, in `robolectric.properties`), and Arrange keys before and
after a move. They land in `app/build/outputs/roborazzi/`; `device-test` uploads them as the
**screenshots** artifact of each run, with the JVM test report. They are for review: look at them
when a change touches a screen. Nothing is compared against stored images yet, as fonts render
differently between computers; once CI is the only place that records, golden images can be
committed and verified with Roborazzi's compare mode.

**Pickers and Appearance.** `ScreenshotsTest` also takes a choice row's picker (`picker-tap-*`, the
dialog on its own: under Robolectric a tall sheet's window is drawn over a dialog opened from it),
and Settings › Appearance with a light app and an always-dark terminal, with and without the
Terminal colours picker open (`appearance-light-app-dark-terminal-*`, `picker-terminal-colours-*`).

**TalkBack.** `TerminalAccessibilityTest` (emulator only: it needs libghostty) reads the pane's
node tree as TalkBack does: a node per line with its words, wrapped rows joined, blank rows left
out, the nodes following the screen, a double tap on a line acting as a tap. Which new lines are
read out, and how often, is `ScreenSpeechTest` in core.

**Large text.** `LargeFontTest` shows Terminal controls, Arrange keys, Appearance, the Tap, C-c and Terminal colours pickers, Settings, Home with the
tailnet's card, the first run and the host dialog on a 360 dp phone with the font 1.3 and 1.5 times
larger, and fails on any text that is ellipsized or runs past the lines it may take (each Text's own
layout says so). The host dialog runs on the emulator only: a text field in a dialog never lets
Compose idle on Robolectric, so it has no picture either. `ScreenshotsTest` takes the same screens
as `large-1.3-*` and `large-1.5-*` (Robolectric's `setFontScale` on a `w360dp` screen). The
`…Largest13` cases put Settings › Appearance › Text size at 150% on a phone at 1.3 (the app's text at
1.95): Terminal controls, Arrange keys, Appearance and its Text size picker, Settings, Home and the
first run; `large-1.3-text-150-*` are their pictures. `AppearanceTest` checks a pick redraws the sheet
and its picker larger at once (each is a window of its own, which a `LocalDensity` set outside does
not reach: before `AppTextScale`, the sheets in these tests were drawn at the phone's size). A sheet row
whose detail no longer fits beside its label puts the detail under it (`SheetItem`).

A new screen or settings page gets: a shared screen test following the rules above, and a
screenshot in portrait and landscape.

## Rex integration

The app is a client of Rex's control (`/api/control`) and data (`/api/data`) connections, and its
worst bugs live in the order things happen between the phone, Rex and the desk: a tab closed on the
desk while the phone holds its pane, then another tab shown. Those are tested as **scenarios** in
`core`, written once and run against two servers.

### A real Rex in CI

Yes. Superlogical publishes a Linux server build in a public apt repository
(`pkgs.superlogical.com/rex/apt`, suite `preview`), so the `rex-real` workflow installs the version
pinned in `tools/rex/pin.env` with `tools/rex/install-rex.sh` - the archive key's fingerprint and the
package's SHA256 checked, apt pinned to Superlogical's origin (Debian and Ubuntu have an unrelated
`rex`) - starts it on loopback (`rex server run --listen unix://… --extra-listen
http://127.0.0.1:7297 --allow-anonymous`), and runs everything that needs a real Rex: `:core:test`
(the scenarios, `PocketSessionTest`, the reconnect tests, `RexShapeTest`, `RealRexAgentReplayTest`) and the app's
`RealRexAppTest`, then fails if any of them was skipped. Rex is proprietary: nothing of it is
committed, cached or uploaded, only the test reports. To move to a new Rex, bump `pin.env`; see its
comment.

### What replaces it

- **`ScriptedRex`** (`core/src/testFixtures/.../ScriptedRex.kt`) is a Rex in the test JVM, on
  MockWebServer, for every run that has no real Rex (and the app's screen tests, which want one they
  can script). It speaks the protocol the app speaks and does what Rex 0.1.0-preview.22 does, as
  recorded in rex-parity.md, "Real Rex": clients that resume with their token, the line a pane passes
  down when its owner goes, who owns a new pane, events before the answer and only to clients that
  may see them, the refusals' codes and words, the order of a closed tab's events, a session that
  outlives its tabs, the stream's end codes and its one-per-client rule, program status with `""` for
  the root record and `updated_at`. **`RexShapeTest`** holds it to that: one script runs against
  ScriptedRex on every build and against the shapes recorded from the real Rex
  (`core/src/test/resources/rex-shapes/preview.22.txt`), and in `rex-real` against the real Rex
  too. `refuse(method)` makes a method fail, for the errors that must still show; `report(block,
  state, …)` is a program reporting its status (OSC 7501), `report(block, null)` a clear, and
  `exit(block, code)` its program ending; `bell(block)` and `notify(block, title, body)` its bell and desktop notifications; `progress(block, state, value)` its OSC 9;4 progress bar, as Rex's `progress_report`; `uploads` and `pastes` are what reached a pane by the native upload and by `paste` events; `hold(method)` keeps its answers back until `release`, a
  slow network for a test to act in; `drop(client)` is a network outage; `pictures = false` sends no
  snapshot.
- **`Desk`** is a second client that changes sessions only through the protocol - new session,
  new tab, split, close a tab or pane, take a pane, ask who holds one - so a scenario means the
  same against `ScriptedRex` and a real Rex.
- **`Screen`** does what `TermScreen` does with a `PocketSession`: it streams the pane on screen
  (none while the closed note stands in for it) and starts over when that changes, and it records
  every notice that reached the banner, so "no error flashed" can be asserted.
- **`PaneLifecycleScenarios`** are the sequences: the desk closes the shown tab or the shown pane
  of a split, then the phone shows the tab that is left, picks another from the list, or makes a
  new one; the same with the pane released, and taken by the desk first; the phone closes the
  shown tab or pane itself, or one the desk closed first; a reconnect. Each asserts the screen: the
  closed note, the next pane on screen with its picture and held by the phone on Rex without a Take
  over, and an empty banner for a while after.

`ScriptedRex` and `Desk` are core's test fixtures, so the app's screen tests connect to the same
fake Rex: `LaunchTest` and `HomeStateTest` (`app/src/sharedTest`) run the whole app -
`RexPocketApp` on its real view model and store - against it, on the JVM and on the emulator. On
the JVM without libghostty's host build the panes send no picture (`pictures = false`), so the
terminal screen opens on its pane with nothing to draw.

`HomeManageTest` drives the home's session and tab sheets (long press and ⋮) and checks Rex got the
rename, new tab, close, end or move (Move up and Move down, from the sheet and from TalkBack's
actions, the rows' new places on screen) with no `session.attach` of that session and no pane taken;
`HomeStateTest` holds a fold to its session through a move at the desk, a rename and connecting
again, folded on screen while the list is still the cached one; `OrderTest` (core, on both servers)
moves sessions and tabs from the phone and the desk; its rename
dialog runs on the emulator only. What the home's rows, sheets, filters and seen marks are held to
is in home.md, "Tests". `ShareTest` is a share from another app: the app started by it,
connecting while the sheet is up, the sheet naming the last pane, nothing sent until Paste or Send,
then the text in ScriptedRex's `pastes` (no Enter typed) or the file in its `uploads`; the picker
when no pane is remembered, and a file over 100 MiB that cannot be sent. `ShareIntentTest` (JVM)
reads share intents, and holds that only other apps' `content:` URIs are taken.

### Sharing into the app

`ShareActivity` takes `ACTION_SEND` and `ACTION_SEND_MULTIPLE` (`text/*`, `image/*`, any file) and
hands the intent, with its read grant, to `MainActivity` in the app's own task, so one view model
and one connection serve it. `readShare` keeps files over text (an image's caption is dropped) and
only `content:` URIs not of this app, so a crafted share cannot send the app's own files to a pane.
The view model holds the share (`offerShare`) for the pane in `lastPane` of the last host and
session, connecting to that host if needed - cold start, tailnet joining and all, with the home's
connect progress on the sheet - and the start-up resume stands aside. Paste or Send is the only way
anything goes: text through Rex's `paste` event, files one after another through the upload pill
(`attachUpload`'s path, 100 MiB each), and the pane is then shown. Without a remembered pane, or
when it is gone, the sheet opens on the picker: the open host's sessions, tabs and panes, and the
other hosts, which connect when tapped.

Several hosts at once are tested as multi-host.md, "Tests", says: `FleetTest` on two ScriptedRex,
`MultiHostTest` on two with the app's view model.

Notifications are tested as notifications.md, "Tests", says: `PaneSignalsTest` on both servers, `NotificationsTest` on ScriptedRex with the app's view model.

Find, a desk keyboard's keys and progress bars are tested as terminal-controls.md, "Testing", lists. What needs libghostty - reading the scrollback, the bytes a key sends - is `TerminalSessionTest`, on CI only; OSC 9;4 against a real Rex is `RealRexPaneSignalsTest` in `rex-real`.

`ScriptedRexLifecycleTest` runs them on every push, in the `rex-integration` workflow with the rest
of `:core:test`. `RealRexLifecycleTest` runs the same scenarios against a real Rex: in the `rex-real`
workflow, and skipped where none listens.

### Against a real Rex, on your computer

On the Mac (or Linux machine) with Rex installed:

```sh
tools/rex/install-rex.sh          # Debian or Ubuntu amd64; on a Mac, Rex's own installer
rex server run --listen unix:///tmp/rex-dbg.sock --extra-listen http://127.0.0.1:7297 \
  --allow-anonymous --state-dir /tmp/rex-dbg-state &
REX_TEST_HOST=127.0.0.1 REX_TEST_PORT=7297 ./gradlew :core:test
mkdir -p native/.build/host && ./gradlew -x :app:buildGhostty -x :app:buildGhosttyHost \
  :app:testDebugUnitTest --tests 'me.leaver.rexpocket.ui.RealRexAppTest'
REX_RECORD_SHAPES=1 ./gradlew :core:test --tests '*RexShapeTest*'   # after a Rex bump: record its shapes
```

`REX_TEST_HOST` and `REX_TEST_PORT` point the tests at another machine; listen only on a network
you trust, since an anonymous plaintext listener has full access to that Rex. Each scenario makes
its own session, named `rexpocket-scenario-…`, and destroys it at the end. A scenario that passes
on `ScriptedRex` and fails on the real Rex is a difference to copy into `ScriptedRex`.

### The model the scenarios hold the app to

- A notice that belongs to a pane carries its id (`Notice.blockId`) and lasts only while that pane
  is on screen: `settled` drops it when the pane closes, is replaced or is left, and it goes when
  what failed works again (the stream flows, or the phone holds the pane).
- A pane is taken on sight unless it was handed back; the holder chip says "This phone" while that
  take is on its way, and "Take over" only for a released pane (`PocketState.holder`).
- What the phone remembers about a pane - that it holds it, that it released it - goes with the
  pane. Handing back a pane that closed is not attempted, and its refusal never stops the next pane.
- A call on a pane that is gone - closing it, typing into it, renaming, release, take over - is
  not an error: the pane closed, and the screen already says so.
