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

- `app/src/sharedTest` holds the screen tests (`ArrangeKeysTest`, `TerminalControlsTest`). They are
  compiled into both the JVM unit tests and the instrumented tests:
  - on the JVM with Robolectric, in seconds on any computer:
    `./gradlew -x :app:buildGhostty -x :app:buildGhosttyHost :app:testDebugUnitTest --tests 'me.leaver.rexpocket.ui.*'`
    (with an empty `native/.build/host` when libghostty's host build is not there);
  - on the emulator in `device-test`, each class listed in its test script.
- Robolectric's Android jar comes through Gradle's repositories (`robolectricAndroid` in
  `app/build.gradle.kts`), not from Maven Central at run time. It wants Java 21.

## Screenshots

`ScreenshotsTest` (JVM, Roborazzi) captures the terminal screen with the key bar, Terminal controls
and Arrange keys, in portrait and landscape on a Pixel 6-sized screen, and Arrange keys before and
after a move. They land in `app/build/outputs/roborazzi/`; `device-test` uploads them as the
**screenshots** artifact of each run, with the JVM test report. They are for review: look at them
when a change touches a screen. Nothing is compared against stored images yet, as fonts render
differently between computers; once CI is the only place that records, golden images can be
committed and verified with Roborazzi's compare mode.

A new screen or settings page gets: a shared screen test following the rules above, and a
screenshot in portrait and landscape.
