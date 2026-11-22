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
  `TermFullScreenTest`). They are
  compiled into both the JVM unit tests and the instrumented tests:
  - on the JVM with Robolectric, in seconds on any computer:
    `./gradlew -x :app:buildGhostty -x :app:buildGhosttyHost :app:testDebugUnitTest --tests 'me.leaver.rexpocket.ui.*'`
    (with an empty `native/.build/host` when libghostty's host build is not there);
  - on the emulator in `device-test`, each class listed in its test script.
- Robolectric's Android jar comes through Gradle's repositories (`robolectricAndroid` in
  `app/build.gradle.kts`), not from Maven Central at run time. It wants Java 21.

## Screenshots

`ScreenshotsTest` (JVM, Roborazzi) captures the terminal screen with the key bar, Terminal controls
and Arrange keys, the terminal's ⋮ menu and full screen right after entering it, in portrait and
landscape on a Pixel 6-sized screen (the JVM tests' default too, in `robolectric.properties`), and Arrange keys before and
after a move. They land in `app/build/outputs/roborazzi/`; `device-test` uploads them as the
**screenshots** artifact of each run, with the JVM test report. They are for review: look at them
when a change touches a screen. Nothing is compared against stored images yet, as fonts render
differently between computers; once CI is the only place that records, golden images can be
committed and verified with Roborazzi's compare mode.

A new screen or settings page gets: a shared screen test following the rules above, and a
screenshot in portrait and landscape.

## Rex integration

The app is a client of Rex's control (`/api/control`) and data (`/api/data`) connections, and its
worst bugs live in the order things happen between the phone, Rex and the desk: a tab closed on the
desk while the phone holds its pane, then another tab shown. Those are tested as **scenarios** in
`core`, written once and run against two servers.

### Can a real Rex run in CI?

No. Rex is proprietary and in a beta that hands out its builds after a sign-in (a Discord login on
superlogical.com); Linux and FreeBSD get a server-only build, but its install commands sit behind
that login, and nothing says it may be redistributed or fetched by a robot. rex.app answers
automated requests with a challenge page. So a GitHub runner has no lawful, repeatable way to get
the binary, and no CI job downloads one. If Superlogical publishes a Linux server build that may be
fetched without an account, `rex-integration.yml` can start it with
`rex server start --extra-listen :7297 --allow-anonymous` and set `REX_TEST_HOST`; the real-Rex
classes below then run instead of skipping.

### What replaces it

- **`ScriptedRex`** (`core/src/testFixtures/.../ScriptedRex.kt`) is a Rex in the test JVM, on
  MockWebServer. It speaks the protocol the app speaks - hello and welcome with a client id and
  reconnect token, request and response, events wrapped as `session_event`, tickets from
  `data.connect`, and on `/api/data` the HELLO/RESUME handshake, a 32-byte WELCOME, a `GHOSTSNP`
  snapshot and then output - with the shapes taken from `RexClient`, `parseView`, `TerminalStream`
  and the stream fixture `app/src/test/resources/rex-stream.bin`. It keeps Rex's rules that the
  real-Rex tests rely on: each connection is a new client whose panes are let go when it ends; a
  pane call (own, disown, size, resize, write, ticket) on a pane outside the attached session, or
  closed, is refused with `block … is not in the session`; every change is told to every client
  after its answer; a closed pane's streams close with 4000 and a HELLO for it is refused with
  0x03; a tab's last pane closing closes the tab, a session's last tab its session. `refuse(method)`
  makes a method fail, for the errors that must still show; `hold(method)` keeps its answers back
  until `release`, a slow network for a test to act in; `pictures = false` sends no snapshot.
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

`ScriptedRexLifecycleTest` runs them on every push, in the `rex-integration` workflow with the rest
of `:core:test`. `RealRexLifecycleTest` runs the same scenarios against a real Rex and is skipped
when none listens.

### Against a real Rex, on your computer

On the Mac (or Linux machine) with Rex installed:

```sh
rex server start --extra-listen 127.0.0.1:7297 --allow-anonymous
./gradlew :core:test --tests '*RealRexLifecycleTest*' --tests '*PocketSessionTest*'
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
