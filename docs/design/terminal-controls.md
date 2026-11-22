# Terminal controls

How a person drives the terminal screen with fingers, and what they can change about it. This
covers every input on the screen, the options and defaults for each, where the settings live, how
they are stored and migrated, and how gestures that compete for the same touch are told apart.

The rule for defaults: what the app did before this design, unless there is a strong reason. The
one change of behaviour by default is that a tap in full screen also closes the keyboard (see 2.1).

## 1. What is on the screen

| Part | Normal | Full screen |
| --- | --- | --- |
| Top bar: back, the tab title (opens tabs and panes), who holds the pane, ⋮ | shown | hidden |
| Pane switcher pill (only with 2+ panes in the tab) | over the key bar | hidden |
| Pane | between the bars | the whole window |
| Key pill: Esc, C-c, Tab, Return, Ctrl, (Shift), (Alt), Fn, Paste, Attach, `/` | bottom | hidden |
| Small pill: arrow stick, keyboard key, full-screen key (scrolled into view) | bottom right | hidden |
| Exit-full-screen corner button | — | top right |

Full screen can be entered from: the ⋮ menu, the full-screen key in the small pill, and a gesture
(double tap by default; long press if chosen). It is left by the corner button, the same gesture,
or Back.

## 2. Gestures on the pane

### 2.1 Options and defaults

| Gesture | Options | Default |
| --- | --- | --- |
| Tap | Open keyboard · Toggle keyboard · Nothing | Open keyboard |
| Double tap | Full screen · Toggle keyboard · Nothing | Full screen |
| Long press | Select text · Full screen · Toggle keyboard | Select text |
| Wait for a double tap before a tap acts | on · off | off |
| Double-tap window | 100–600 ms, or the phone's | the phone's (`ViewConfiguration`, usually 300 ms) |
| Long-press time | 150–1500 ms, or the phone's | the phone's (usually 400 ms; the system accessibility setting changes it) |

Things every option keeps:

- A program that reads the mouse gets its click on every single tap, whatever the tap does to the
  keyboard. "Nothing" means nothing for the app, not for the program.
- A long press on a link always offers the link, whatever the long press is set to. Links are
  printed by agents for the person to open; losing them to a setting would be a trap.
- Drag scrolls the history (or sends the wheel), a horizontal swipe changes pane, a pinch changes
  the font size. None of these is configurable now.

**Open keyboard in full screen.** In full screen the key bar, and the keyboard key with it, is
gone. A tap that can only open the keyboard leaves a person with a keyboard and no way to put it
down but Back. So in full screen "Open keyboard" acts as "Toggle keyboard". This is the one
default behaviour change. With "Nothing", the keyboard still goes down with Back (the system's
own), and the corner button brings the key bar back.

### 2.2 Telling them apart

One pure class, `TapGestures` (core, JVM-tested), decides from touch-down, move, up, cancel and
the time. The view feeds it and runs a timer at the deadline it reports.

- A press that moves beyond the touch slop is a drag, swipe or pinch: no tap gesture at all. A
  second finger cancels.
- A press held still for the long-press time is a long press; its lift is nothing.
- A second press within the double-tap window of the first tap lifting, and within the double-tap
  slop of it, is a double tap when it lifts (not when it lands, so a second press held still is a
  long press - the old behaviour - and one that moves is a drag).
- When double tap is "Nothing", every tap acts at once and nothing waits.
- Without "wait", a tap acts as it lifts and a double tap follows it; the double tap then puts the
  keyboard back where it was before the first tap, so a double tap for full screen does not leave
  a keyboard the first tap raised. With "wait", the tap acts only once the window has passed, and
  the first tap of a double tap never acts. A second press that lands too far away lets a waiting
  tap out at once.
- A second press that turns into a long press or a drag drops a tap that was waiting: the person
  was not just tapping.

Keyboard after a double tap (pure function `keyboardAfterDoubleTap`): "before" is the keyboard as
it was before the first tap when that tap acted, or as it is now when it waited. Full screen and
Nothing leave it at "before"; Toggle keyboard turns "before" over.

### 2.3 Later

- Two-finger tap (for example to paste, or to toggle the keyboard). It competes with pinch; it
  needs the gesture class to track pointers. Cheap to add once asked for.
- Three-finger swipes, edge swipes: they fight the system's back gesture on modern Android.
- Volume keys as Ctrl or arrows (Termux has this). Needs key handling in the activity.

## 3. The keyboard

How it opens: a tap on the pane (setting above), the keyboard key, or the Return key of the key
bar never opens it. How it closes: the keyboard key, Back, a tap set to toggle, a tap in full
screen (2.1), or a double tap / long press set to toggle. A keyboard on the desk keeps the focus on
the pane and needs no screen keyboard.

## 4. The key bar

Today's settings stay, and move into the controls screen: keep the key bar with a desk keyboard,
show Shift, show Alt, and C-c (tap · hold unless busy · hidden). The small pill gains the
full-screen key, just past the keyboard key. The stick takes its own drags on the initial pass,
before the scroll sees them, so scrolling never steals a stick gesture.

How the two pills share the width (`keyBarWidths`, JVM-tested at phone widths):

1. When every key fits (landscape, a tablet), nothing scrolls: the small pill shows the stick, the
   keyboard key and the full-screen key whole, and the wide pill takes the rest.
2. Otherwise the wide pill scrolls, and its last visible key must show only in part - 18 to 38 dp
   of its 48, under a 16 dp fade - as the hint that more keys follow. With the small pill whole
   beside it that usually falls out (411 dp: 4 keys and 31 dp of the fifth), and the full-screen key
   shows whole.
3. When it does not, the small pill gives the wide pill just enough of the full-screen key to make
   the wide pill's hint 20 dp: a little more of the same key, or a sliver of the next. The
   full-screen key then shows in part (22 to 50 dp), scrolled to on a swipe: both pills hint at
   once (393 dp: 4 keys and 20 dp; 45 dp of the full-screen key).

Only the bar's width and the set of keys (Shift, Alt, C-c shown or not) decide it, never typing or
the keyboard, so the bar moves only on a rotation or a settings change.

The ⋮ menu keeps "Full screen": the pill key is a quick way for people who use the bar; the menu
is where people look for what a screen can do, and it stays reachable when the bar is hidden by a
desk keyboard.

Later: choosing and ordering the keys of the wide pill, key size, a second row. These need a key
model the bar is built from; the current bar is fixed code.

## 5. Short windows and landscape

A phone on its side is some 400 dp tall; with the keyboard up, little more than 130 dp is left. The
top bar (64 dp), the pane switcher and the key bar used to share it, and the key bar was squeezed
to a strip. Rule, for a window under 480 dp tall:

- The pane switcher moves into the top bar's row, where a landscape window has width to spare, so
  it never floats over the pane's text.
- While the keyboard is up, the top bar steps aside and a small ⋮ button sits in the corner (as
  the exit-full-screen button does), so the menu - and from it everything the top bar did - is one
  tap away. The bar's room is kept, so the pane's grid does not change with the keyboard (the
  keyboard never resizes the program, as before). With the switcher out of the bottom stack, the
  key bar alone rises above the keyboard at its full height. The bar comes back when the keyboard
  goes.

Not a setting: it is layout, and the threshold is where the key bar stops fitting.

## 6. Full screen layout

Entering full screen grows the pane several times within one round trip to Rex as the system bars
animate away. The size loop sent the first size, and a size set while that call was out was
recorded as sent when the answer came back, so the pane kept the old grid - the bands at the top
and bottom - until the next size change, which a rotation made. The loop now waits for the wanted
size to differ from the one sent, not for a flag a late answer can overwrite.

In full screen the display cutout is padded on the sides only: a punch hole at the top of a phone
held upright cost a whole band of rows, while one at the side of a phone on its side would hide the
start of every line.

## 7. Haptics

The long press on the pane and the held keys of the bar buzz as they do today. A switch for it is
"later": it is one flag, but no one has asked, and the system's own touch-feedback setting already
turns these off.

## 8. Settings screen and storage

Settings › Terminal shows two rows: **Terminal controls ›** and **Font**. Terminal controls is a
sheet with two groups:

- **Gestures**: Tap, Double tap, Long press, Wait for double tap, Double-tap window, Long-press
  time. Choice rows step through their options on tap and stay open, as the C-c row does; the two
  times open a slider with "Use the phone's". "Wait for a double tap" says what it does in a line
  under its label ("A tap waits in case a second follows" / "A tap acts at once"), its detail
  just On or Off. Every sheet row wraps - a label to three lines, a detail to two, at most 152 dp
  wide - so nothing is cut off on a narrow phone or with large text.
- **Key bar**: With a desk keyboard, Shift, Alt, C-c.

Stored in the app's shared preferences beside the existing keys:

| Key | Values | Missing means |
| --- | --- | --- |
| `gesture_tap` | `keyboard`, `toggle`, `nothing` | `keyboard` |
| `gesture_double_tap` | `full_screen`, `keyboard`, `nothing` | migrated, below |
| `gesture_long_press` | `select`, `full_screen`, `keyboard` | `select` |
| `gesture_wait_double_tap` | boolean | false |
| `gesture_double_tap_ms`, `gesture_long_press_ms` | int; 0 = the phone's | 0 |

Migration: `gesture_double_tap` missing and the old `double_tap_full_screen` false gives
`nothing`; otherwise `full_screen`. The old key is left in place, so going back to an older build
keeps its own setting. Values out of range are clamped; unknown strings fall back to the default.

## 9. Testing

- `GesturesTest` (core, JVM): every gesture under every combination of double taps on or off and
  waiting on or off, custom times, drags, far second taps, held second presses, late timers, and
  the keyboard rules for taps and double taps.
- Migration (core, JVM, `GestureSettings.fromStored`, which the store calls): old key true, false
  and missing; a new choice winning over it; unknown values; out-of-range times.
- `ClosedPaneTest` / size test (core): a size set while a resize is out is still sent.
- `TermChromeTest` (app, JVM): which bars show for tall and short windows, keyboard up or down, in
  and out of full screen.
- Instrumented: `TerminalViewTest` for the real view (wait mode, the person's long-press time, a
  long press set to act, a link still offered), `FullScreenTest` (menu, pill key, double tap,
  Back), `KeyBarLayoutInstrumentedTest` (the full-screen key whole at 411 and 891 dp, both pills
  hinting at 426 dp). Whether a tap really closes the screen keyboard in full screen is covered by the pure rule
  only: the CI emulator reports a desk keyboard and shows no screen keyboard.
