# RexPocket design system

Every colour, font size and corner radius on screen comes from
`app/src/main/java/me/leaver/rexpocket/ui/Theme.kt`.
A screen that needs a value the tokens do not have is a broken screen, not a special case.

Layout follows the platform: an 8 dp grid (4 dp for the gaps inside a component), a 16 dp page
margin, 48 dp touch targets for everything that can be tapped however small its plate is drawn,
56 dp one-line list rows and 64 dp two-line rows. Spacing inside a component is still written
where it is used.

## Material

One anchor hue (an iOS action blue) on grouped paper, in the two appearances the phone has. No
gradients, no glass, no glow. Surfaces are told apart by lightness, and a hairline is decoration,
never a control edge. The terminal is the paper you sit on; the tile is the one dark mark.

| Token | Light | Dark | Job |
| --- | --- | --- | --- |
| `bg` | `#F2F2F7` | `#0A0A0B` | the page |
| `surface` | `#FFFFFF` | `#161618` | a card, a sheet |
| `raised` | `#FFFFFF` | `#242427` | a floating pill or circle: the home's controls, the dock, the pane bar |
| `terminal` | `#FFFFFF` | `#0A0A0B` | the pane's own paper |
| `key` | `#E9E9EF` | `#232326` | a key, the round top-bar controls |
| `keyOn` | `#DCE7FB` | `#12345C` | an armed or open key |
| `line` | `#D9D9DE` | `#2C2C2E` | hairline between rows |
| `edge` | 6 % black | `#323236` | the rim of a floating control |
| `shadow` | 25 % black | none | the shadow under a floating control; at night `raised` and `edge` do its job |
| `ink` | `#1C1C1E` | `#F2F2F7` | titles, body, glyphs |
| `muted` | `#63636A` | `#98989F` | labels, secondary detail |
| `accent` | `#0A62E8` | `#0A84FF` | links, buttons, the live dot, a held pane |
| `onAccent` | `#FFFFFF` | `#FFFFFF` | text on `accent` |
| `ok` | `#137A3D` | `#30D158` | online, running, a direct path |
| `amber` | `#9A6300` | `#FFD60A` | waiting, a relay, a warning |
| `danger` | `#C7162B` | `#FF453A` | failure, removal, a Stop key |
| `badge` | `#1C1C1E` | `#2C2C2E` | the terminal tile |
| `badgeInk` | `#30D158` | `#30D158` | the prompt on the tile |
| `tile` | `#F2F0EB` | `#E9E7E2` | the plate behind a program's mark |
| `tileInk` | `#1C1C1E` | `#1C1C1E` | a program's mark |

Contrast, light then dark, on `surface`/`bg`: ink 17.0/15.3 and 15.9/18.9, muted 6.1/5.5 and
6.6/6.9, accent 5.4/4.8 and 5.6/6.4, ok 5.4/4.9 and 8.4/9.9, amber 5.1/4.5 and 13.4/15.9, danger
5.9/5.3 and 5.1/5.6. The tile's glyph is 8.4 on `badge` in both.

The Material theme under the app carries the same colours, so the platform's own pieces - the press
ripple, dialogs, menus - are drawn in them.

## Type

Five sizes, five jobs, nothing else, on the platform's type steps: display 28 (an empty screen),
title 20 (a host, a sheet title), body 16 (a row's title), subhead 14 (a session's group label, a
key, a pane chip), label 13 (details; leading 18). Monospace is for paths, figures, a pane's number
and a chord key such as `C-c` - never for a label, a control or a heading.

## Icons

Every mark is a Material Symbols Rounded vector in `res/drawable` (`ic_*.xml`, Apache 2.0): one
grid, one weight, one corner, so a back arrow, a chevron and a keyboard read as one family. An icon
is 24 dp in a 48 dp target, 20 dp on a key. The only drawn mark is the terminal tile's prompt,
which has no symbol. Each icon control speaks its action; a toggle also speaks its state.

## The home

There is no second screen between a host, its sessions and their tabs.

Two keylines carry the whole list: 16 dp in from the card's edge for marks (the host's monitor, a
session's name, a tab's tile) and 64 dp for every title. Trailing controls are 48 dp slots flush
with the right edge, so a host's menu, a session's plus and fold, and a tab's chevron share one
centre line.

- A host is a 64 dp section line: a 24 dp monitor, its name at title size with the connection's
  dot, and a line that says what it is doing and how many sessions it has. The line folds the
  section, or connects the host when it is not the one open; the vertical `more` opens its menu.
- A session is a quiet group label under the host: its name in
  `muted` subhead, a blue dot while something in it works, its tab count or *Cached*, then a
  `add_circle` that makes a tab and the fold, a chevron that turns from down to right. A session
  whose tabs are not loaded opens (and loads) instead of folding.
- Its tabs are one card of 64 dp rows: the 36 dp program tile of its focused pane - badged with a
  ringed blue dot while a pane in it works - the tab's name, its pane count and what a working pane
  is doing, and a chevron; hairlines from the title's keyline. Tapping a tab attaches the session
  and shows that tab.
- A tab someone named keeps its name. One left at Rex's generated label takes the title its
  program set (`π - rexpocket`), then the program's name, then its position. A title a shell writes
  - its next command, `user@host:dir` - is never used.
- The page starts at the top with the list; there is no large title. Opening a tab keeps the
  connection when you come back, so the live tabs are still there.
- Two floating controls, 56 dp high on the page margin: a bottom-left pill with
  settings and the eye, and a bottom-right search circle. The eye keeps only the sessions and tabs
  with activity right now - a pane whose program is working or waiting, from Rex's program status;
  while it is on, the eye is filled and sits on an `keyOn` plate. Search opens the platform's search
  bar at the top - a 56 dp pill with back and clear - and Back closes it first.
- A host that is not connected shows the sessions it had last time; their tabs load when the host
  connects. The first run has one card: no hosts, the `rex server` line, and **Add host**.

## The session screen

Three floating layers, no bars:

The whole screen is the terminal's paper; nothing sits on a band of a second grey.

1. **The top bar.** 64 dp: a 40 dp round back arrow on a `key` plate, then one title block - the
   36 dp program tile of the shown pane (a ringed accent dot while the desk holds the pane), the tab name with the directory
   it is in, and the session under it with its state dot and a chevron - then the vertical `more`
   on the same plate. The title block opens the session's tabs and panes, with the session list at
   its top; `more` is the menu: Release / Take over first, with who holds the pane now, then pane,
   tab and session actions, each with its icon.
2. **The pane.** Full width, 10 dp of inset for its text. On a split the pane bar floats under it:
   one pill, a 48 dp segment per pane with its 24 dp program tile and name, the one on screen on a
   `keyOn` plate in `accent`. Tap a segment or swipe the pane left and right.
3. **The dock.** One floating pill - keyboard, Return, keys (the command mark) - and a 56 dp circle
   that makes a tab, both on `raised`, above the IME and the navigation bar. The keyboard button
   shows the platform's keyboard-hide mark while the IME is up. The key pad (`esc`, `ctrl`, Tab,
   `/`, arrows, `C-c`, `C-d`, `C-z`, `C-l`, paste, copy, arrow pad) opens from the keys button into
   the row above the pill: 44 dp keys, symbols where the platform has one, chords in monospace.

## Rules the screens follow

- One action colour. Blue means "this does something": a session's dot, the search circle's glyph,
  Release / Take over, the live bar. Red is only removal and failure.
- The home is the navigation, down to the tab. There is no separate session picker, and the
  terminal's back button returns to the home with the connection kept.
- The eye means activity, not folding: it keeps the panes that are working or waiting, and nothing
  else. A blue dot on a tab is that same activity.
- The terminal keeps the screen. Its chrome is round and floating, and the dock is one pill and one
  circle. Keys are asked for, not parked on screen.
- The control is a state, in the menu: Release while this phone holds the pane, Take over once the
  desk does; the tile's dot says the same thing without a word. A release belongs to the pane and
  survives tab and session switches.
- The phone has its own place in a session. Rex's active tab and focus are the desk's: the phone
  never moves them, and does not follow them either. It holds only the pane it shows.
- Marks come from one set, the platform's: no font glyph stands in for an icon, and no two icons
  are drawn by hand to different weights.
- A program's tile says what runs in a pane. A shell, or nothing reported yet, is the dark terminal
  tile. A program the app knows - pi, Claude, Codex, Gemini, Cursor, opencode, Copilot, Vim, Emacs,
  Docker, Node, Python, Git, ssh, top - is its mark on the light `tile` plate, drawn on a 24-unit
  grid at one stroke weight, with at most one colour of its own. Anything else is its initial.
  The marks are the app's drawings, never a product's logo. The name comes from what the program
  reports, else from the foreground process's start name (`pi`, not `node`).
- Everything tappable is at least 48 dp, and says what it does to a screen reader.
- Typing is the pane's. There is no composer: a tap raises the keyboard, the dock's `Enter` sends
  Enter, the key pad sends the rest.
- A status colour is the state's: green online/running, amber waiting/relaying, red failed. It never
  repeats what the line already says.
- The appearance follows the phone. Both palettes are first-class: the terminal, the dock, the
  sheets and the system bars switch together.
- One primary action per screen, in `accent`; a list row is one card; a sheet is grouped cards of
  56 dp rows on the paper with a 24 dp top corner and a drag handle. A group's rows all have icons
  or none do, and its hairlines start where its labels do. The current item carries a check.
- Motion is the platform's: the sheets, the press ripple, and the fold's chevron turning.

## Not applicable (web-only gates)

`overflow-x`, `vh`/`dvh`, `prefers-reduced-motion` media queries, `:focus-visible` CSS, font files,
image slots and the node/sloplint sweep are web concerns. The Android equivalents are: no horizontal
overflow (Compose clips), no bespoke motion to reduce, focus and press feedback from Compose's
indication, and the system font stack.
