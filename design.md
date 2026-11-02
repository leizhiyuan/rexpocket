<!-- Hallmark v3.0.0 · UI rebuild of me.leaver.rexpocket · the terminal app it follows · 2026-11-02 -->
<!-- Critique: the app was a dark terminal with its chrome on top and a text box under it. It now
     follows the mobile terminal it was measured against: light paper, one blue for anything you
     can act on, one dark tile as the only strong mark, the tabs and keys in a dock under the pane,
     and typing straight into the pane. The composer is gone; the keyboard is the pane's. -->

# RexPocket design system

Every colour, font size and corner radius on screen comes from
`app/src/main/java/me/leaver/rexpocket/ui/Theme.kt`.
A screen that needs a value the tokens do not have is a broken screen, not a special case.

Two deliberate exceptions: the dock's keys and tab names are set at 15 sp because they are
component measures inside a 44 dp row, and spacing inside a component (a chip's padding, a gap of
2 dp) is still written where it is used.

## Material

One anchor hue (an iOS action blue) on light grouped paper. No gradients, no glass, no glow. Surfaces
are told apart by lightness, and a hairline is decoration, never a control edge. The terminal is the
one place the app is dark: a tile, not a background.

| Token | Value | Job | Contrast on `surface` / `bg` |
| --- | --- | --- | --- |
| `bg` | `#F2F2F7` | paper | — |
| `surface` | `#FFFFFF` | a card, a sheet, the pane, the dock | — |
| `key` | `#E9E9EF` | a key, the plus, an inactive plate | — |
| `keyOn` | `#DCE7FB` | an armed key such as Ctrl | — |
| `line` | `#D9D9DE` | hairline, pager dot, inactive edge | 1.3 (decoration) |
| `ink` | `#1C1C1E` | titles, body, key glyphs | 17.0 / 15.3 |
| `muted` | `#63636A` | labels, key labels, secondary detail | 6.1 / 5.5 |
| `accent` | `#0A62E8` | anything you can act on: links, the pill, the live tab | 5.4 / 4.8 |
| `onAccent` | `#FFFFFF` | text and icons on `accent` | 5.4 |
| `ok` | `#137A3D` | running, online, joined, a direct path | 5.4 / 4.9 |
| `amber` | `#9A6300` | waiting on a person, connecting | 5.1 / 4.5 |
| `danger` | `#C7162B` | failure, removal, a Stop key | 5.9 / 5.3 |
| `badge` | `#1C1C1E` | the terminal tile | — |
| `badgeInk` | `#30D158` | the prompt on the tile | 8.4 on `badge` |
| `scrim` | `#40000000` | behind a sheet | — |

## Type

Four sizes, four jobs, nothing else: display 32, title 20, body 17 (leading 23), label 13
(leading 18). Monospace is used for paths and figures only - never for a label, a control or a
heading. The dock's keys and tab names are the component exception at 15.

## The terminal screen

The screen is a stack of surfaces, in this order:

1. **The header**, on the paper. A 38 dp dark tile with `>_` in `badgeInk`, then two lines: the tab
   name and its directory (mono, muted), and under it a status dot, the session name, the program's
   state and a chevron. On the right, the control pill and the menu. The name opens tabs and panes;
   the session line opens the session list.
2. **The pane**, a white card with 24 dp corners, inset 6 dp from the header and 12 dp inside for
   the terminal's own text. The emulator's paper is `surface`, its ink is `ink`, its cursor is
   `accent`.
3. **The pager**, 26 dp of paper with a 22x6 dp `accent` bar on the shown pane and a 6 dp `line` dot
   for each other pane. Only when the tab has more than one pane; a swipe on the pane does the same.
4. **The dock**, a white card with 24 dp corners and a 50 dp tab row: a 26 dp dark tile with the
   tab's number or letter, its name, and its pane count. The shown tab is marked by a 3 dp `accent`
   rule on the card's bottom edge, under its name. The layout button (44 dp) and the plus (44 dp,
   `key`, `ink` glyph) stay outside the scroll at the right.
5. **The key row**, on the paper: 44 dp keys in `key` with 15 sp `muted` labels, 4 dp apart, in one
   row that scrolls; the keyboard key sits outside the scroll at the right, drawn, not set as a
   glyph. The row is on the paper and above the IME, so it is the closest thing to the thumb.

## Rules the screens follow

- One action colour. Blue means "this does something": a row's `Enter`, `Edit`, the new-session
  button, the control pill, the active tab's rule. Red is only removal and failure.
- The tab row lives under the pane, not over it. A phone is held at the bottom; the row that moves
  you between terminals is the one the thumb reaches.
- The shown tab is a rule under its name, not a filled chip: the row stays one line high however
  many tabs there are.
- The control pill is the state, not a menu: filled `Release` while this phone holds the pane,
  outlined `Take over` once it belongs to the desk. Tapping it does what it says.
- A release belongs to the pane, not to the screen: showing that pane again - another tab, another
  session - must not take it back. Only Take over ends it.
- Typing is the pane's. There is no composer and no Send: a tap raises the keyboard on the terminal,
  the `⏎` key presses Enter, and the keyboard key raises or drops the IME.
- The status colour is the state's: green running, amber waiting, red failed, grey idle. It never
  repeats what the header already says.
- One primary action per screen, at the foot, full width, in `accent` with `onAccent` text.
- A list row is one card: its whole surface opens the thing, and the label on the right is only its
  own target when it does something else (edit).
- A sheet is a white card on the paper with a 24 dp top corner and a hairline drag handle; a group
  of rows is its own card, and a section label is `muted` label-size text above it.
- Motion respects the platform's animation scale: the tab row jumps to the shown tab instead of
  animating when the user has asked for less motion.

## Not applicable (web-only gates)

`overflow-x`, `vh`/`dvh`, `prefers-reduced-motion` media queries, `:focus-visible` CSS, font files,
image slots and the node/sloplint sweep are web concerns. The Android equivalents are: no horizontal
overflow (Compose clips), reduced motion via `rememberReducedMotion()`, focus and press feedback from
Compose's indication, and the system font stack.
