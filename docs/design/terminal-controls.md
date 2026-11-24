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
| Key pill: Esc, C-c, Tab, Return, Ctrl, Fn, Paste, Attach, `/` by default; any key of the catalogue and the keys of key groups (4.1) | bottom | hidden |
| Small pill: arrow stick, keyboard key, full-screen key (scrolled into view) | bottom right | hidden |
| Exit-full-screen corner button | — | top right |

Full screen can be entered from: the ⋮ menu, the full-screen key in the small pill, and a gesture
(double tap by default; long press if chosen). It is left by the corner button, the same gesture,
or Back.

The grid takes the rows full screen gives, and while the phone holds the pane Rex is asked for
them. Whether they fill is the terminal's rule, as in Ghostty on a desk: a taller screen pulls rows
back from the scrollback only when the cursor is on the bottom row. A shell at its prompt fills;
an agent with a status line under its cursor gets the new rows empty under that line until it
draws more (`FullScreenGridTest`). While the desk holds the pane the program keeps the desk's size.

## 2. Gestures on the pane

### 2.1 Options and defaults

| Gesture | Options | Default |
| --- | --- | --- |
| Tap | Open keyboard · Toggle keyboard · Nothing | Open keyboard |
| Double tap | Full screen · Toggle keyboard · Nothing | Full screen |
| Long press | Select text · Full screen · Toggle keyboard | Select text |
| Wait for a double tap before a tap acts | on · off | off |
| Double-tap time | 100–600 ms, or the phone's | the phone's (`ViewConfiguration`, usually 300 ms) |
| Long-press time | 150–1500 ms, or the phone's | the phone's (usually 400 ms; the system accessibility setting changes it) |

Things every option keeps:

- A program that reads the mouse gets its click on every single tap, whatever the tap does to the
  keyboard. "Nothing" means nothing for the app, not for the program.
- A long press on a link always offers the link, whatever the long press is set to. Links are
  printed by agents for the person to open; losing them to a setting would be a trap.
- Drag scrolls the history (or sends the wheel), a horizontal swipe changes pane, a pinch changes
  the tab's text size. None of these is configurable now.

**Nothing only a gesture reaches.** Each gesture's job is also a row someone can find: the pinch is
the ⋮ menu's Text size row (4.2) and the palette's Bigger, Smaller and Reset text size; a long press
set to Full screen or the keyboard still leaves Select text in the ⋮ menu and the palette (a
selection started there takes the last line with text, handles to drag); Return held is Shift+Return,
with "New line" as its long-press label and as a TalkBack action of its own. A hint over the foot of
the pane, shown once, names the two that have no row of their own on the bar: "Pinch with two
fingers to change the text size. Hold Return for a new line." It goes with Got it, or once the size
is changed any way, and is stored as gone (`pinch_hint_seen` in the `terminal` preferences).

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
- A second press within the double-tap time of the first tap lifting, and within the double-tap
  slop of it, is a double tap when it lifts (not when it lands, so a second press held still is a
  long press - the old behaviour - and one that moves is a drag).
- When double tap is "Nothing", every tap acts at once and nothing waits.
- Without "wait", a tap acts as it lifts and a double tap follows it; the double tap then puts the
  keyboard back where it was before the first tap, so a double tap for full screen does not leave
  a keyboard the first tap raised. With "wait", the tap acts only once the double-tap time has passed, and
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

**TalkBack.** The pane is read line by line (`TerminalAccessibility`, an `ExploreByTouchHelper`):
each line on screen is a node with its words, rows the terminal wrapped joined into the line the
program wrote and blank rows left out, so touching reads a line and a swipe goes to the next. A
double tap on a line or on the pane does what a tap does (by default, the keyboard). While a line
of the pane holds TalkBack's focus, new output is announced: only lines that were not on screen
before, not the line the cursor is on (the prompt, or what is being typed), at most every 1.5 s and
400 characters (`OutputAnnouncer`, core), and nothing while a full-screen program (the alternate
screen) redraws itself. The keyboard stays `TerminalImeView`'s, which TalkBack does not see, so
typing is unchanged.

## 4. The key bar

The controls screen keeps "with a desk keyboard" and C-c (tap · hold unless busy), and opens
**Arrange keys** (4.1). The small pill gains the full-screen key, just past the keyboard key. The stick takes its own drags on the initial pass,
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

Only the bar's width and how many keys each pill shows decide it, never typing or the keyboard,
so the bar moves only on a rotation or a settings change. The order does not matter: whichever key
is last in the small pill is the one that gives way. A small pill of one or two keys (the
full-screen key or the stick hidden) never gives way: it stays whole, and the wide pill shrinks to
its 20 dp hint instead. A wide pill with every key hidden is not drawn; the small pill keeps its
place at the right.

**The ⋮ menu repeats every key-bar action that acts on the phone rather than sending a key**:
Paste, Send a file, the keyboard (Show keyboard / Hide keyboard) and Full screen, plus the top
bar's Release / Take over. They are always there, whatever the bar shows: a desk keyboard takes the
bar away, Arrange keys can hide those keys, full screen and a short window's keyboard hide the bar
or the top bar, and the menu is where people look for what the screen can do. Keys that send bytes
(Esc, C-c, Tab, Return, Ctrl, Shift, Alt, Fn, `/`, the arrows) are never repeated: the menu is no
second key bar. `BarKey.actsOnPhone` (TermScreen.kt) decides which keys these are, and has no
default, so a key added to the bar has to be put on one side.

The menu, top to bottom; each section's destructive row last, in red:

1. No caption: Command palette; Find (9); Keyboard shortcuts › (10); then, in the bar's order:
   Paste; Send a file; Show / Hide keyboard; Full screen; Compose prompt; then Text size (4.2).
2. Pane: Release / Take over (while the session is live and a tab is open, as the top bar's chip);
   New pane on the right; New pane below; Rename pane; Select text; Copy output; Share output;
   Insert path › (4.3); Close pane.
3. More: Clear terminal; Reset terminal - both red, both asking first, as Close pane does: what
   they erase does not come back. The palette's rows ask the same way.
4. Tab: New tab; Rename tab; Status details; Close tab.
5. Session: Sessions; Rename session; End session.

What changes it: Find, Paste, Send a file, the keyboard, Rename pane and Close pane need a pane on screen,
so the closed-pane note leaves them out. Send a file is greyed out, not removed, while a file is on
its way, so the rows do not jump. Close pane shows only with two or more panes: Rex closes a tab
with its last pane, which is Close tab. The keyboard row says what a tap does, by whether the
keyboard is up; Release or Take over by who holds the pane. A desk keyboard changes nothing.

### 4.1 Key bar and key group customization

People asked for two things: to put a key that lives behind `Fn` (F10 for htop, Home, PgDn) right
on the bar, and to choose what the `Fn` panel holds. The second grows into **key groups**: `Fn` is
one panel of keys opened by its key on the bar, and a person can have others - one for htop, one for
vim - made from a preset or from nothing. Everything here is in Settings › Terminal controls ›
Arrange keys.

#### The keys

One catalogue (`BarKey`, core) of every key the bar can draw, each with a stored name:

| Kind | Keys | Stored as |
| --- | --- | --- |
| The bar's own | Esc, C-c, Tab, Return, Ctrl, Shift, Alt, Paste, Attach, `/` | `escape`, `ctrl_c`, ... as before |
| Answering agents (off the bar by default) | Compose prompt (the composer; in the ⋮ menu too), Quick replies (a panel of the person's quick replies, never in a group) | `compose`, `quick_replies` |
| Group keys | one per group: `Fn`, and each group made | `fn`, `group_<id>` |
| The right pill's | arrow stick, keyboard key, full-screen key | `arrows`, `keyboard`, `full_screen` |
| Desk keys | Home, End, PgUp, PgDn, Ins, Del, F1-F12 | `home`, `page_up`, `f10`, ... |
| Arrow keys | ↑ ↓ ← →, each its own key | `arrow_up`, ... |
| Control keys | C-a to C-z but C-c (the bar's own, with its setting) | `ctrl_d`, ... |
| Characters | space, a-z, 0-9, and every ASCII symbol but `/` (the bar's own) | `char_<hex code>` |

New are the single arrows (Termux's default row has them; a held one repeats, as the arrow pad's
do), the control keys (one tap for the chords behind a held `ctrl`, which an agent or vim asks for
again and again), and the characters: htop wants `q` and space, vim `:`, tmux its follow-up letters,
and a phone keyboard keeps most symbols a page away. Every key sends what it does today wherever it
sits: a desk key goes to the pane's terminal by its Android key code, the rest go to Rex as keys,
and the modifiers held on the bar apply to all of them (Shift then End selects, Ctrl then F5 is
C-F5, Alt then `b`).

#### Where a key may live

- **The key pill**: any key but the right pill's three. Group keys live only here: a group inside a
  group would be a menu of menus.
- **A group's panel**: any key but group keys and the right pill's.
- **The right pill**: its own three keys only. It is the thumb's fixed place, its width rule
  assumes its few keys, and the stick in a scrolling row would fight the scroll for its drags.

A key is at most once in each place, but may be in several: on the pill and in `Fn` both, as `/`
is by default. So putting F10 on the bar adds it there and leaves `Fn`'s rows of F-keys whole;
moving a key is adding it in one place and removing it from the other, two steps a person sees.

Defaults are today's bar: the pill Esc, C-c, Tab, Return, Ctrl, Fn, Paste, Attach, `/`; `Fn` with
Home, End, PgUp, PgDn, Ins, Del, F1-F12 and the seven symbols; the right pill stick, keyboard key,
full-screen key. Shift and Alt are off the bar until added, as they were hidden.

The keyboard key cannot be removed: once the phone keyboard is down it is the one way back. Every
other key can, and none is ever lost: a place's "Add keys" page always offers every key that may go
there, so a key taken off is one tap from coming back. (C-c also has `ctrl` then `c`, and every
chord has a held `ctrl`.)

#### Key groups

A group is a named panel of keys and a key on the bar that pins it, as `Fn` pins its panel now.

- **Making one**: "New group" offers the presets - **Fn** (once deleted), **htop** (F1-F10, the
  four arrows, PgUp, PgDn, space, `/`, `q`), **vim** (Esc, `:`, `/`, `?`, `*`, `~`, C-d, C-u, C-o,
  C-r, C-w, C-v, arrows) and **tmux** (C-b, then `c n p d % " [ z` and arrows: the prefix and its
  follow-ups, two taps on a panel that stays open; tmux in a Rex pane is still tmux) - and "Empty
  group", which asks for a name. Presets are off by default, and a template copied when chosen, so
  a later change to a preset never changes a group someone has tuned. A preset already made opens
  that group instead of being greyed out.
- **Its key on the bar** shows the group's name (up to 8 characters, set smaller to fit the 48 dp
  key; `Fn`, `htop`, `vim` at the usual size), in the bar's monospace, lit while its panel is open.
  A new group's key goes right after the last group key on the pill, so groups sit together. It is
  a key like any other: moved in the pill's list, removed, added back from the pill's "Add keys".
  A group whose key is off the pill keeps its keys; its row says "Not on the key bar".
- **Its panel** lays its keys in rows of six, or seven when every key of the row is one character,
  in the person's order: today's `Fn` comes out as before (the moving keys, F1-F6, F7-F12, the
  seven symbols). It scrolls when it is taller than the room above the bar (a phone on its side with
  the keyboard up), never less than two rows. One panel at a time: opening another puts the first
  away, and the open one stays open across a rotation (as the arrow pad does). A held key does on a
  panel what it does on the bar: Return held is a new line, C-c per its setting, `ctrl` held opens
  the chords.
- **An empty group** draws no key: a key that opens nothing is a dead key, and the width rule counts
  only keys drawn. Its rows say why ("Empty: not on the key bar"), and adding a key brings it back.
- **At most 8 groups.** Each is a key on the bar and a row in settings; more would not be keys a
  thumb finds. At the limit "New group" says so and why instead of acting.
- **Rename** and **Delete** are on the group's page. Delete asks first, since the keys go with it;
  a preset deleted can be made again.
- Groups are listed in their keys' order on the bar, then those off the bar; reordering groups is
  reordering their keys in the pill, so there is one order, not two.

#### The pages

Arrange keys (portrait and landscape alike; it scrolls):

1. **Key pill**: its keys in order. A row: a drag handle, the name, up and down (one step; for
   TalkBack, where a drag is awkward), and remove (⊖). A group key's row says how many keys it
   opens. Last, "Add keys ›".
2. **Key groups**: a row per group - its name, "25 keys · on the key bar" - opening the group's
   page; then "New group ›".
3. **Right pill**: its keys as in 1, the keyboard key with a lock in place of ⊖; "Add keys ›" once
   one is removed.
4. **Reset to default**: the pills and `Fn` as they first came, other groups gone. It asks first
   when there are groups to lose.

A **group's page**: its keys as in 1 (a key also on the pill says so under its name), "Add keys ›",
then "On the key bar" (on or off), "Rename", "Delete group". A name is typed on a page of the sheet,
with Save as its last row, not in a dialog over it: a dialog takes the keyboard from the sheet's
window. Every page past Arrange keys has a back arrow before its title, as swiping it down does.

**Add keys** is the catalogue as chips in flowing rows under headings (The bar's keys, Groups,
Desk keys, Arrows, Control keys, Symbols, Letters and digits), only those that may go in the place.
A chip is a toggle: checked when the key is there, a tap adds it at the end or takes it off, and the
page stays open, so filling a group is a run of taps rather than a trip per key. Chips wrap to the
width: a phone shows six or so to a row, a landscape screen twice as many.

A drag moves a row within its list and stops at the list's ends.

**Controls that cannot act say why.** The platform's pattern is a control shown disabled with the
reason in supporting text, and a tooltip on a tap of the control itself. The keyboard key's row says
"Always on the bar: it brings the keyboard back"; its lock is a button whose tap or long press shows
that in a tooltip, and TalkBack reads "Keyboard cannot be removed" with the reason. "New group" at
the limit carries its reason the same way. Up on the first row and down on the last are disabled
(TalkBack says so): their reason is where the row is. Elsewhere a control that would do nothing is
not drawn (no "Add keys" on a full right pill) rather than greyed.

#### Interactions

- **Modifiers** apply to a key wherever it is, and a modifier key may be on a panel too (Shift next
  to End). A modifier that cannot be reached - off the pill and in no group whose key is on it - is
  let go, so it never stays held with no key to release it.
- **C-c's setting** (tap · hold unless busy) is the C-c key's, wherever it sits.
- **Width**: `keyBarWidths` holds for any number of keys (tested up to 40): the pill scrolls and
  hints, the right pill stays whole or gives way as before. Every key is 48 dp, so the rule's only
  input is still how many there are.

#### Stored, and every earlier form

| Key | Values | Missing means |
| --- | --- | --- |
| `keybar_order` | the key pill's keys, then the right pill's | built from the old switches, below |
| `keybar_hidden` | the default bar keys (the 14 of the last version) not on their pill | as `keybar_order` |
| `keybar_groups` | the group ids, in the order they were made | `fn` alone, with today's keys |
| `keybar_group_<id>` | the group's keys | the preset's keys, or none |
| `keybar_group_<id>_name` | its name | the preset's name |

- **Before Arrange keys** (the Shift, Alt and C-c "hidden" switches): read as before, and stored.
- **Arrange keys of the last version** (order and hidden of the 14 keys): the pill is the stored
  order without the hidden keys, the right pill likewise, and `Fn` today's: the same bar. That
  version's hidden key is now a removed one, back from "Add keys".
- **An older build reading this**: it knows `keybar_order` and `keybar_hidden`, drops the keys it
  does not know (F10, group keys), and finds each of its own keys either in the order or hidden, so
  it shows this bar less the new keys. Groups it ignores.
- **A later build's keys** in any list are dropped; a default bar key the order lacks and hidden
  does not name (one added since) goes back where it is by default; a group key whose group is gone
  is dropped; a key not allowed where it is stored is dropped.

#### Elsewhere

- **Termux** reads `extra-keys` from a properties file: rows of key names (ESC, CTRL, F1-F12, UP,
  PGDN...) or literal characters, each with an optional second key on a swipe up. Any key is
  possible, but only by editing a file. Taken: the catalogue by name, literal characters as keys,
  single arrows. Not taken: the file, and swipe-up second keys (deferred).
- **JuiceSSH** has a row of extra keys over its keyboard and a popup of more, with little to change
  about them as far as I know; a fixed set is what this bar had.
- **Blink** keeps its keys over the keyboard in fixed groups - modifiers, arrows, a scrolling strip
  of the rest - and puts its settings into remapping a desk keyboard. Taken: groups, and a fixed
  thumb place, which the right pill already is.

The model is `KeyArrangement` (core, pure): `add`, `remove`, `move`, `moveTo` for the pills;
`addGroup`, `renameGroup`, `deleteGroup`, `addToGroup`, `removeFromGroup`, `moveInGroup` for
groups; `shown`, `isShown`, `fromStored` and the stored form. `KeyPreset` holds the presets.

Deferred: a key with its own text or sequence (a macro, such as tmux's `C-b c` in one tap); a second
key on a swipe up, as Termux has; dragging a key from one list into another; resetting one group to
its preset; key size and a second row.

### 4.2 Text size

The terminal draws at 12 sp times a scale from 70% to 180%. There are two of them:

- **The default**, for every tab that has not set its own: Settings › Appearance › Terminal text
  size (a dialog with − / +, a step of 10%, and Reset to 100%), and the same line in the Terminal
  font dialog, beside the face it sizes. Stored as `font_scale`, as before, and carried by the
  settings backup as `terminalFontScale`; a backup from before this change loads as it did.
- **A tab's own**, set by a pinch, by the ⋮ menu's Text size row, by the palette's Bigger text,
  Smaller text and Reset text size, and by Ctrl+= / Ctrl+- / Ctrl+0. It belongs to that tab of
  that host (`TabTextSizes`, core): an omp table can be made to fit in one tab while the others
  stay readable. Reset, or stepping back to the default, drops it, and the tab follows the default
  again. Kept while the app runs, not stored: Rex's tab ids would pile up with nothing to prune
  them.

Before, a pinch changed the one stored size for every tab; that size is now the default, so nothing
a person had set is lost.

**The row**: an icon, "Text size" with "110% · 52×30" under it (the size, then the columns and rows
the pane fits at it, from the view's own grid), − and +, and Reset, which waits greyed while the
size is the default. − and + stop at the ends and go to the next step of 10%, so a size a pinch left
between steps is not stepped past. The sheet stays open: what the row changes shows on the row and
on the pane behind it. TalkBack reads the row as one ("Text size, 110 percent, 52 columns by 30
rows"), politely again as it changes; − and + are "Smaller text" and "Bigger text".

### 4.3 Insert path

A folder browser that types the path picked into the pane: for a shell, where an agent's own `@`
picker is not there to help. From the ⋮ menu's Pane group and the palette (Rex's own Insert Path…
words find it).

- It asks Rex, `block.method` `list_dir` (`com.superlogical.terminal.list_dir`) with `path` empty -
  the working directory of the pane's foreground program, as Rex resolves it - and `files` on. So
  it starts where the program is, with nothing for the phone to work out from the title or OSC 7.
- Rex's answer, as preview.22 gives it (`RexShapeTest`, `RealRexListDirTest`): `path` absolute,
  `home`, `status` (`ok`, `missing`, `not_dir`, `denied`), `entries` of `name` with `file` for
  what is not a folder and `symlink` for a link (a link leading nowhere is left out), `truncated`.
  The phone sorts them - folders first, then files, by name ignoring case - and keeps dot entries
  back until Show hidden. At most 1000 are listed; past that it says so.
- The sheet's title is the folder, the home as `~`. Its rows: Insert this folder; Up to the parent;
  Show / Hide hidden; then the folders, which open in place, and the files, which are typed.
- What is typed is one shell word (`shellPath`, core): as it is when every character is one no
  shell treats specially, otherwise in single quotes, a quote inside as `'\''`; a path under the
  home starts `~/` outside the quotes (`~/'my notes'`), so the shell still expands it. It goes as a
  paste, so nothing runs until Return, and no space is added.
- A folder that cannot be read says why, with Up still there; no answer in 15 s is "Could not
  list this folder".

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

Entering full screen then still left a band at the bottom (and a little at the top) until the
keyboard was opened and closed. The screen was padded by the bars' insets as Compose last heard
them, and Compose drops the insets Android sends while a bar animation is being prepared, taking
them at its end only when the animation had a duration: with animations off, or a bar that hides
without one, the old insets stayed until the next change, which the keyboard made. The room for the
bars now follows full screen itself: none in full screen, and otherwise the bars' sizes whether
shown or not (`statusBarsIgnoringVisibility`, `navigationBarsIgnoringVisibility`), which do not
change as the bars hide and show. Entering and leaving lay the screen out at once.

The window must be allowed into the camera cutout (`enableEdgeToEdge` in MainActivity's onCreate
sets it to always, from Android 11): in the default mode a window with its status bar hidden is
moved off the cutout's band, and full screen loses that band at the top. `TermFullScreenTest`
sets the same mode on its already-shown test window.

## 7. Haptics

The long press on the pane and the held keys of the bar buzz as they do today. A switch for it is
"later": it is one flag, but no one has asked, and the system's own touch-feedback setting already
turns these off.

## 8. Settings screen and storage

Settings › Terminal shows **Terminal controls ›**, **Terminal font ›**, **Appearance ›** and
**Reopen the last tab ›**. A row that opens another page or dialog ends in a chevron (›); a row that
acts at once (Check for updates, Source code) does not.

**Reset to defaults**, at the end of Terminal controls, after a confirm: the gestures and their
times, the key bar with a desk keyboard and the C-c key as the app comes. The keys and key groups
stay: Arrange keys has its own Reset. Greyed out while all of them are the defaults. The gestures,
the key bar and the arrangement are in the settings backup (`gestures`, `keyBar`;
settings-backup.md).

**Pickers.** No row steps blindly through its values any more: a tap used to move to the next
option, so a person had to tap round to find what the others were, and with three options could
not go back one. A row of a setting with named options now opens a Material single-choice dialog
(`ChoicePicker`, Pickers.kt): a radio row per option with the option's icon, its words and a line
on what it does, the current one selected. A tap on an option picks it and closes the dialog;
Cancel leaves the setting as it was. The sheet stays open under it, so the row's detail shows the
new value as the dialog goes. Since every such row opens a dialog, each ends in a chevron. That
covers Tap, Double tap, Long press, Wait for a double tap (On · Off), With a desk keyboard and C-c
here; Reopen the last tab (On · Off) and Update channel (Stable · Preview) in Settings; and both
rows of Appearance. Background alerts stays a switch: turning it on asks Android for permission
first, and it is moving to the Notifications page.

**Appearance** (Settings › Appearance) has two rows: **App theme** (System · Light · Dark; System
by default) and **Terminal colours** (Match app · Always dark · Always light; Match app by
default), so a light app can have a dark terminal. They are stored as `app_theme` (`system`,
`light`, `dark`) and `terminal_colors` (`app`, `dark`, `light`); missing or unknown is the default.
`RexTheme` reads them from the view model, so a pick shows at once, with no new activity. Terminal
colours set only the pane's paper, ink, cursor and selection (`Colors.pane`, `terminalTheme()`):
the bars around it stay the app's, and the closed-pane and empty notes, which are app text, keep
the app's paper. Matching the desk's own terminal colours is not offered yet (rex-parity.md).

**Terminal text size** opens the default for new tabs (4.2): − / +, Reset, and what it is for.

**Text size** (System · 85% · 115% · 130% · 150%; System by default, stored as `text_size`) scales
the app's text on top of Android's font size: `RexTheme` provides a `LocalDensity` whose font scale
is the phone's times the step (`TextSize.fontScale`, no further than 2.0 unless the phone already
is), made from a configuration with that scale so Android 14's non-linear scaling still applies. It
changes live, with no new activity. A dialog, a bottom sheet and a popup are windows of their own
whose density is the phone's again, so `ActionSheet`, the package's `AlertDialog` (Material's, with
each slot scaled) and the key bar's popups apply it once more (`AppTextScale`, which is idempotent);
HomeScreen.kt still imports Material's `AlertDialog`, so its host and tailnet dialogs follow only
the phone's size until that import goes. The terminal does not follow: its view draws in its own sp
size (Terminal font, a pinch, the palette's bigger and smaller), which already follows Android's
font size, and the row says so under its label. Diagnostics reports the step as `text size`.

Terminal controls is a sheet with two groups:

- **Gestures**: Tap, Double tap, Long press, Wait for double tap, Double-tap time, Long-press
  time. Choice rows open their picker; the two times open a slider with "Use the phone's". "Wait for a double tap" says what it does in a line
  under its label ("A tap waits in case a second follows" / "A tap acts at once"), its detail
  just On or Off. Every sheet row wraps - a label to three lines, a detail to two, at most 152 dp
  wide - so nothing is cut off on a narrow phone or with large text.
- **Key bar**: With a desk keyboard (Key bar hidden · Key bar shown), Arrange keys › (the key
  pill, key groups, right pill; 4.1), C-c (tap · hold unless busy).

**Icons.** A choice row's icon is the action it is set to, not a picture of the gesture, so it
changes with the setting: a keyboard for Open keyboard and for Open or close keyboard, the four
corners for Full screen, a text cursor and selection for Select text, the "block" mark for Nothing
(a neutral mark rather than no icon, so the group keeps one keyline); C-c is a finger tapping or a
finger held. The detail, the icon and the picker's line for each option come from one function per
setting (`choice` in Controls.kt and Appearance.kt, JVM-tested in `ChoiceTest`), so they cannot
disagree. Rows about time have time icons: an hourglass
for the wait, a timer for both durations. Icons are Material Symbols Rounded.

Stored in the app's shared preferences beside the existing keys:

| Key | Values | Missing means |
| --- | --- | --- |
| `gesture_tap` | `keyboard`, `toggle`, `nothing` | `keyboard` |
| `gesture_double_tap` | `full_screen`, `keyboard`, `nothing` | migrated, below |
| `gesture_long_press` | `select`, `full_screen`, `keyboard` | `select` |
| `gesture_wait_double_tap` | boolean | false |
| `gesture_double_tap_ms`, `gesture_long_press_ms` | int; 0 = the phone's | 0 |
| `keybar_order` | both pills' keys, comma-separated (`escape,ctrl_c,...,arrows,keyboard,full_screen`) | built once from the old keys, below |
| `keybar_hidden` | the default keys not on their pill, comma-separated | built once: `shift` unless `keybar_shift`, `alt` unless `keybar_alt`, `ctrl_c` if `keybar_ctrl_c` was `hidden` |
| `keybar_groups`, `keybar_group_<id>`, `keybar_group_<id>_name` | the key groups, their keys and names (4.1) | Fn alone, with its keys of before |

Migration: `gesture_double_tap` missing and the old `double_tap_full_screen` false gives
`nothing`; otherwise `full_screen`. The old key is left in place, so going back to an older build
keeps its own setting. Values out of range are clamped; unknown strings fall back to the default.

## 9. Find

Find looks through the pane's scrollback - the screen and every row of history the pane's terminal
holds - for what is typed, and shows where.

**Opening it.** The ⋮ menu's Find, the palette's Find (Rex's `client.find.*` words find it too),
and Ctrl+Shift+F (Meta+F on a Mac keyboard) on a desk keyboard. A one-line selection is taken as
the query. Ctrl+Shift+F again puts the keys back in the field with the query selected.

**The bar** floats over the pane's top rows rather than taking a row from it, so the program keeps
its size: the query (focused, its text selected so typing replaces it), the count ("3 of 12", "No
matches", "1000 of 1000+"), a case switch (Aa, off by default; TalkBack reads it as a switch, "Match
case"), previous, next and close, each 48 dp. Return goes to the next match and Shift+Return to the
previous (F3 and Shift+F3 too), the keyboard's Search key goes to the next, Escape closes. The
screen's other shortcuts (10) work from the field; Copy and Paste there are the field's own. The
count is a polite live region, so TalkBack says it as it changes. Closing gives a desk keyboard's
keys back to the pane.

**What is searched.** The pane's own libghostty terminal: the `GHOSTSNP` snapshot Rex sends when a
pane opens carries the scrollback Rex's terminal has, and every byte of output since is fed to the
same terminal, so the phone holds what Rex holds. libghostty-vt 1.0.1's C API, as the app binds it,
offers no search and hands out only the viewport (its render state), so
`TerminalSession.scrollbackRows` walks the viewport from the top a screen at a time under the
session's lock - no output lands in between, nothing draws half-way - and puts it back where it
was. At most the newest 50 000 rows are read. Rex's `format` (the screen and scrollback as text, as
Copy output uses) was not used: it holds no more history than the snapshot gave the phone, costs a
round trip of up to megabytes per search, and its lines cannot be mapped to the rows the pane draws,
which highlighting and scrolling need.

**Matching** (`searchScrollback`, core): lines the terminal wrapped are searched whole, so a word
broken at the right edge is found; the spaces padding a line the program ended match nothing; case
is ignored unless the switch is on; matches do not overlap; at most 1000 are kept. A wide character
covers its two columns. The search runs 150 ms after typing rests, off the main thread.

**Showing them.** Every match on screen gets a soft amber under its text, the current one a deep
orange with an edge (told apart without colour too). A new query starts on the newest match, at the
bottom where the person is, and the pane scrolls to put the current match a third of the way down.
New output searches again 400 ms after it rests and keeps Find on the match it was on
(`nextCurrent`), even when the oldest rows fell out of the scrollback, without moving the pane.

## 10. A desk keyboard

With a keyboard attached the pane holds the focus (3) and every key goes to the program, encoded by
libghostty in the modes the program set - except the screen's own few, below. The key bar hides by
default with a desk keyboard (Terminal controls › With a desk keyboard).

**What the program gets** (`TerminalSessionTest`): Esc, Tab and Shift+Tab, the arrows, Home, End,
PgUp, PgDn, Insert, Delete, F1-F12, each with Ctrl, Alt and Shift in xterm's form (`ESC[1;5D` is
Ctrl+Left, `ESC[5;5~` Ctrl+PgUp); Ctrl+letter as its control character, Ctrl+Space as NUL, Alt as
the ESC prefix (Alt+Backspace is `ESC DEL`), Ctrl+Shift+- as C-_ (a shell's undo); Meta (the
Command or Windows key) reaches a program that turned on the Kitty keyboard protocol as Super.
Shift+PgUp and Shift+PgDn scroll the history, as before. Numpad digits type as text; the numpad's
other keys with Ctrl are not encoded (the JNI key table has no numpad keys yet).

**The screen's keys** (`appShortcut`, core). The rule: Ctrl+Shift with a letter or symbol, which no
terminal program tells from Ctrl alone unless it speaks the Kitty protocol (C-S-c is C-c); Ctrl with
`=`, `-`, `0` and Tab, which have no byte of their own and are what Ghostty and browsers use; and
Meta with the keys a Mac uses for the same. Nothing with Alt. Ctrl+Shift+- stays the program's.

| Action | Keys |
| --- | --- |
| Command palette | Ctrl+Shift+P · Meta+Shift+P |
| Find | Ctrl+Shift+F · Meta+F |
| Copy selection | Ctrl+Shift+C · Meta+C (nothing selected: a toast says to select first; nothing goes to the program, which would read C-c) |
| Paste | Ctrl+Shift+V · Meta+V |
| New tab | Ctrl+Shift+T · Meta+T |
| Next tab | Ctrl+Tab · Ctrl+Shift+] · Meta+Shift+] |
| Previous tab | Ctrl+Shift+Tab · Ctrl+Shift+[ · Meta+Shift+[ |
| Close tab (asks first, as the menu does) | Ctrl+Shift+W · Meta+W |
| Bigger text | Ctrl+= (or +, Ctrl+Shift+=, numpad +) · Meta+= |
| Smaller text | Ctrl+- (numpad - too) · Meta+- |
| Reset text size | Ctrl+0 · Meta+0 |
| Back to hosts | Ctrl+Shift+H |
| Keyboard shortcuts | Ctrl+Shift+/ |

A held shortcut acts once, but for the text size, which steps on. The tab keys go round the
session's tabs, as the palette's Next and Previous tab do.

**Where they are listed.** The Keyboard shortcuts sheet (Ctrl+Shift+/, the ⋮ menu, the palette): a
row per action with its keys, and a tap runs it. Android's own keyboard shortcuts helper (Meta+/)
gets the same list from `MainActivity.onProvideKeyboardShortcuts` while the terminal screen is up.

Not done: the keys cannot be rebound; a key the system takes first (Meta+Tab, Alt+Tab, Meta+/) never
reaches the app.

## 11. Progress

Programs report a progress bar with OSC 9;4 (ConEmu's sequence, which Ghostty and Windows Terminal
show): state 0 removes it, 1 sets a percent, 2 is an error, 3 busy with no percent, 4 paused (or a
warning). Rex reads the sequence in its own terminal and forwards it to every client that may see
the pane as the block event `progress_report` `{state: set|error|indeterminate|pause|remove,
progress}` (rex-parity.md), skipping reports that come in quick succession but always sending the
newest. The phone takes that (`PocketState.progress`, by pane) rather than its own libghostty, whose
C API as bound here does not surface OSC 9;4; and Rex's event comes for panes not on screen and in
other sessions too, which a local terminal never sees.

Rex keeps no bar to read back, so a pane shows one only from the first report the connection hears.
A bar goes when the program removes it (state 0), its program exits (`child_exited`), the pane
closes, or no report has come for 15 s - as Ghostty's desktop app does, so a program that died
mid-way leaves no bar for good.

**Where it shows**, as a line 3 dp tall (2 dp under a pane's segment) in the action colour, red for
an error, amber paused, with a moving piece for work with no percent:

- along the foot of the terminal's top bar, for the tab (its focused pane's bar, else the most
  pressing of its panes': error, paused, a percent, busy), drawn over the bar so the pane never
  changes size for it; full screen hides it with the bar;
- under each pane's segment of the pane switcher, its own;
- on the home, under the tab's row, for the tab.

TalkBack reads a bar as a progress bar with its words ("Progress 42%", "Working, progress
unknown", "Failed at 10%", "Paused at 50%"); in a pane segment and a tab row, as part of the row.

**As a notification** (Settings › Background and notifications › Progress, off by default, stored
as `alerts.progress`): while the app is away and background alerts are on, each tab with a bar has a
quiet notification on its own channel (Progress, low importance) with Android's own progress bar,
the tab's name and host; it follows the bar at most once a second and goes with it, or when the
app comes back. A host whose notifications are off has none. A tap opens the pane.

## 12. Testing

- This pass's rows (`TextSizeTest`, shared): the Text size row steps the tab on screen and Reset
  goes back, another tab follows the default while one keeps its own, Appearance's default with its
  Reset and kept, the pinch hint shown once and remembered (and gone once the size changes), Select
  text and Copy output from the menu, Clear terminal asking first, Insert path from the palette
  asking Rex. `InsertPathTest` (shared): the browser over a made-up host - where it starts, hidden
  entries, a file typed quoted, a folder opened and inserted, a folder that could not be read, one
  Rex may not read, Up. `ListDirTest` (core, ScriptedRex and real Rex) and `DirListingTest` (the
  answer read, parents, quoting). `TextScaleTest` (core): steps and per-tab sizes. `TermAgentTest`:
  Return held's label and TalkBack action, Edit quick replies from an empty panel and from the
  composer, Restore defaults, Clear's Restore in the composer, Return after kept.

- `GesturesTest` (core, JVM): every gesture under every combination of double taps on or off and
  waiting on or off, custom times, drags, far second taps, held second presses, late timers, and
  the keyboard rules for taps and double taps.
- Migration (core, JVM, `GestureSettings.fromStored`, which the store calls): old key true, false
  and missing; a new choice winning over it; unknown values; out-of-range times.
- `KeyArrangementTest` (core, JVM): the default bar and Fn; every key's stored name read back; a
  desk key added to the pill, placed, moved at the ends and removed; where keys may and may not go;
  the keyboard key staying; Fn's keys arranged and reachability; empty groups; presets, empty
  groups, rename, delete and the limit of 8; every arrangement read back; migration from the old
  switches and from the last version's order and hidden keys; what an older build reads; unknown,
  missing and misplaced keys and groups.
- `KeyBarLayoutTest` (app, JVM): the widths at 360, 393, 411, 426 and 891 dp, a sweep of every
  width from 300 to 1400 dp for 8 to 40 keys, a group's panel rows (Fn's as before), small pills of one and two keys, no wide-pill keys.
- `ClosedPaneTest` / size test (core): a size set while a resize is out is still sent.
- Find: `ScrollbackSearchTest` (core: wrapped lines, padding, case, wide characters, the limit,
  which match stays current), `FindModelTest` (app, JVM: newest first, stepping round, output that
  pushes rows out), `TerminalSessionTest` (real libghostty: the whole scrollback read and the
  viewport put back, the newest rows only, a wrapped word, scrolling to a match), and
  `TermShortcutsTest` (shared: Ctrl+Shift+F, the menu and the palette open it; focus, Escape, the
  case switch, shortcuts from its field). Screenshots `terminal-find-progress-*`.
- Desk keyboard: `ShortcutsTest` (core: every listed key, and the program keeping Ctrl, Alt,
  Ctrl+Shift+-, the desk keys), `TerminalSessionTest` (the bytes each desk key and chord sends),
  `TermShortcutsTest` (tabs, close asking first, Home, a held key, the help sheet listing and
  running them, the program's keys not taken, Android's helper group). Screenshots
  `terminal-shortcuts-*`.
- Progress: `ProgressTest` (core: Rex's states, percents past 100, tabs), `PaneSignalsTest` (on
  ScriptedRex and on real Rex: OSC 9;4 in every state reaches the phone and goes; a bar with no new
  report goes; an OSC 9 that is a progress report is no notification), `ProgressViewTest` (shared:
  the top bar, a pane's segment, the home's tab row, TalkBack's words), `NotificationsTest`
  (the progress notification when asked for, following the bar and going; none by default).
- `ScreenSpeechTest` (core, JVM): lines from rows and wraps, which lines are new, the throttle and
  the cap; `TerminalAccessibilityTest` (emulator): the pane's node tree.
- `TerminalControlsTest` and `AppearanceTest` (shared): each choice row's picker - every option
  with its icon and line, the current one selected, a pick shown on the row and stored, Cancel
  changing nothing - and the pane's colours following Appearance at once.
- `TermChromeTest` (app, JVM): which bars show for tall and short windows, keyboard up or down, in
  and out of full screen.
- Instrumented: `TerminalViewTest` for the real view (wait mode, the person's long-press time, a
  long press set to act, a link still offered), `FullScreenTest` (menu, pill key, double tap,
  Back), `KeyBarLayoutInstrumentedTest` (the full-screen key whole at 411 and 891 dp, both pills
  hinting at 426 dp), `ArrangeKeysTest` (on the JVM too: rows moved by up, down and drag, in the key
  pill and in Fn's list, move on screen, in the bar or panel, and in the store; a key removed and
  added back from Add keys; F10 added through the page, placed first, in the bar, sent as F10 and
  with the row's Ctrl as C-F10; Home moved from Fn to the bar; a preset group's key and panel; an
  empty group without a key until it has one; rename, off the bar, delete; the keyboard key's line,
  lock and tooltip; New group at the limit; Reset, asking first when groups would go),
  `KeyBarBytesTest` (F10 put on the bar through the page sends `ESC [21~`, and `ESC [21;5~` after
  Ctrl, through a real libghostty terminal, as Fn's own F10 does), `KeyBarStateTest` (the open group
  panel stays open across a rotation, on the JVM too),
  `TermMenuTest` (the ⋮ menu's rows, order and sections for one
  and two panes, held and handed back, not live, a closed pane, an upload on its way, every phone
  key hidden with a desk keyboard; on the JVM the keyboard row with the keyboard up),
  `TermFullScreenTest` (the pane fills the window right after entering, the key bar is above the
  navigation bar right after leaving, Back leaves full screen and then the terminal). Whether a tap really closes the screen keyboard in full screen is covered by the pure rule
  only: the CI emulator reports a desk keyboard and shows no screen keyboard.
