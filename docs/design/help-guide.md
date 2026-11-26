# Help: a guide that takes you there

People using the app have not found what it can do: the pinch that changes the text size (an omp
table wrapped at 52 columns until someone found it), launch templates, Insert path, Mute on a
notification, the app lock. Each of those has a row now (backlog.md, "Gesture-only features"), but
a row in the right sheet is still a row nobody opens. Help answers "how do I…" in the person's
words and then **takes them there**: it opens the screen, the sheet or the menu, and points at the
control with a ring and one line.

## How a person uses it

1. **Open Help.** From the home's ⋮ (Help and guide), Settings (its own row, last before About),
   the terminal's ⋮ menu (last row) and the command palette (Help and guide, and a "Help: …" row for
   every entry, so typing "text size" in the palette finds the help too). Those are where people look
   for what a screen can do; no screen gets a "?" of its own (below).
2. **Find the thing.** Help is a sheet with a search field ("What do you want to do?") over the
   entries grouped by what they are about. Typing ranks them with the palette's own ranking
   (`rankPalette`): the title, the one-line hint, the body and the entry's keywords are searched, and
   in Chinese the English keywords too, since people type `claude`, `tmux` or `F10` as they are.
3. **Read it.** An entry opens to its page: two or three sentences on what it does and how, the
   gesture or the long press if there is one, and **Show me**.
4. **Show me.** Help closes, the app goes where the control is - back to the home or to the
   terminal, opening Settings, the host's sheet, the terminal's ⋮ menu - scrolls the control into
   view, and draws the **spotlight**: the screen dimmed a little, a ring round the control that
   pulses three times and stays, and a card next to it with the entry's title, the hint ("Tap − or +,
   or pinch the terminal with two fingers") and Got it. Nothing is blocked: the touch that ends the
   spotlight goes on to whatever was touched, so a tap on the control both dismisses the spotlight
   and does what the control does. The card itself is the one exception: a press on its words stays
   on it, so reading it with a thumb on it does not open the row under it. Back ends the spotlight
   (or the tour) first, rather than leaving the app with it still up.

**What a place needs first.** A terminal entry needs a tab on screen; a host's sheet needs a host.
Help says so on the entry's page instead of failing: "This is on the terminal screen. Open a tab
first", with **Open a tab on mac** (its last session, connecting first; a new session on a host that
has none) for each saved host, after which the spotlight follows by itself once the pane is up.
With no host there is **Add a host** (the Add host entry). An entry about a host with more than one
host asks which. If the control still does not show up within a few seconds (a sheet that lost the
row, a closed pane), the spotlight gives up and Help comes back on the entry with "Couldn't show it
from here", never a ring round nothing.

## The quick tour

Five spotlights on the home, one after another, each card with Next (Done on the last) and Skip
tour: the filters (Needs you and Activity), a host's ⋮ (long press any row for everything it can
do), + (a new session; New tab offers launch templates), ⚙ (Settings: notifications, keys, text
size, app lock, backup), and the home's ⋮ (Help and this tour, any time). A step whose control is
not there (no host row yet) is skipped. A tap anywhere but the card ends the tour, as Skip does, and
so does Back: a person who wants to do something is not made to finish it.

**When it runs.** Once, by itself, on a new install only: the first time the app opens with a host
after a start that had none (the start after the first host was added, or after a rotation). An
install that already had hosts when this was added - everyone using the app today - never gets it
by itself, and a test that saves a host before starting does not either. It runs only on a plain
start, on the home with nothing over it a moment after the app opens: a start for something - a
notification, the widget, a launcher shortcut, a share, a tile, a `rexpocket://add` link and its Add
host dialog - is what the person came for, so the tour waits for the next start. Help's first row, **Take
the quick tour**, runs it again any time. Seen or skipped, it is stored as done (`tour` in the
`guide` preferences); like the pinch hint it is the phone's, not in the settings backup.

## The registry

`ui/guide/GuideEntries.kt` is the one list. An entry is:

| Field | What |
| --- | --- |
| `id` | a stable name (`text_size`), for tests and the palette |
| `section` | Home and hosts, Terminal, Answering agents, Gestures and long presses, Key bar, Notifications, Tailnet, Settings and security |
| `title`, `hint`, `body`, `keywords` | string resources, in English, Simplified and Traditional Chinese |
| `place` | where the control lives: `Home`, `HomeMenu`, `Settings`, `Notifications`, `Controls`, `Security`, `HostSheet`, `HostPage`, `NewTab` (a host's), `Terminal`, `TermMenu` |
| `spot` | the control itself, a `GuideSpot` |

The tour is a second list of (`spot`, title, hint). Adding a feature to Help is a line here, its
four strings, and one `Modifier.guideTarget(GuideSpot.X)` on the control.

### Inventory

Every entry below is in the registry; the place and the control it points at follow the arrow.

- **Home and hosts.** Add a host → home ⋮, Add host. Add a host from another computer (a
  `rexpocket://add` link or its QR code from `tools/host-link.sh`, read with the phone's camera; or
  Import hosts) → home ⋮, Import hosts. See what needs you → the Needs you chip. Rex's Activity view
  → the Activity chip. Find a host, session or tab → the search field. Start a session → +.
  Rename, move or end a session or tab (long press a row, or ⋮) → the first host's ⋮. Edit, move or
  remove a host → the host's sheet, Edit host. Run a command in a folder (launch templates, "claude
  in ~/proj") → New tab's sheet, Add template…. Silence a host → its page, Notifications. A Quick
  Settings tile → its page, the tile group.
- **Terminal.** Change the text size → ⋮, Text size. Search every action (palette) → ⋮, Command
  palette. Run your Rex actions → the same row. Type a folder's path (Insert path) → ⋮, Insert
  path. Send a file → ⋮, Send a file. Find in the output → ⋮, Find. Split → ⋮, New pane on the right.
  Switch tab, pane or session → the title. New tab → ⋮, New tab. Copy or share the output → ⋮, Copy
  output. Full screen → ⋮, Full screen. A desk keyboard's shortcuts → ⋮, Keyboard shortcuts.
- **Answering agents.** Approve, deny or reply → the Needs you chip. Write a long prompt → ⋮,
  Compose prompt. Quick replies → Settings, Quick replies. Mute a tab (also from its notification's
  Mute) → ⋮, Mute this tab.
- **Gestures and long presses.** Pinch for text size, swipe sideways for the next pane, long press
  to select (and a link to open it), double tap for full screen → the pane. Hold Return for a new
  line → the Return key. Hold Ctrl for a chord → the Ctrl key. Change what a tap does → Settings › Terminal controls, Tap.
- **Key bar.** The folder key (Insert path) → that key. Add or remove keys (F10, Home, a vim group) → Terminal controls, Arrange keys.
- **Notifications.** Notifications while the app is closed → Notifications, Background alerts.
- **Tailnet.** Use a tailnet → Settings, Tailnet.
- **Settings and security.** Lock the app → Security, Lock RexPocket. Block screenshots → Security,
  its row. Back up settings → Settings, Backup. Dark or light → Settings, Appearance. Terminal font →
  Settings, Terminal font. Updates and the preview channel → Settings, Check for updates. Send a
  diagnostics report → Settings, Diagnostics.

## Pointing at a control

The mechanism is deliberately small: a modifier on the control, a layer per window, and a state
object, all in `ui/guide/`.

- **`Modifier.guideTarget(spot)`** goes on the control. With no guide in the composition (every
  screen test that does not start the whole app) it is no modifier at all. Otherwise, only while
  the spotlight wants this spot, it asks to be scrolled into view (`BringIntoViewRequester`, so a
  sheet's scroll or the key bar's row moves to it) and reports its bounds to its layer from
  `onGloballyPositioned`; a control clipped out of sight reports nothing, so "found" means on
  screen. A control not wanted costs one state read. The scroll waits two frames, not a time: a
  sheet has laid out by then, and a test's frame clock, which only moves while something draws,
  gets there too.
- **`GuideLayer`** is a box round a window's content that knows its own coordinates; a target
  reports to the nearest one (`LocalGuideLayer`) and the layer draws the scrim, the ring and the
  card over that window. There is one at the app's root and one inside `ActionSheet`, the one sheet
  every sheet of the app is built on. It has to be per window: a bottom sheet is a dialog window of
  its own, and a ring drawn in the activity's window would be under it. Dialogs (the host dialog,
  the font dialog) have no layer: Help opens the sheet whose row opens the dialog, and points there.
- **The spotlight never takes a touch.** The overlay draws without a pointer handler, so hits fall
  through to the content; the layer watches the initial pass for a press anywhere outside the card
  and ends the spotlight then, without consuming it. The pulse is finite (three beats, then a still
  ring), so it costs nothing after, never keeps a test's clock busy, and does not move with
  animations off.
- **Going there.** `Guide` holds the request (a place, a host) and the spotlight. Each screen that
  owns places has one `GuideOpens` block that opens them with the screen's own state: HomeScreen
  sets its sheet flags, TermScreen opens its ⋮ menu and leaves full screen (the title is hidden in
  it). The route (home or terminal) and the prerequisites are `GuideHost`'s, in RexApp, which uses the
  view model as the rows themselves do.
- **TalkBack.** When the spotlight shows, its title and hint are announced; the card is a node with
  Got it (Next and Skip tour in the tour), so it is found by swiping and dismissed by a double tap.
  The card is a traversal group with a `traversalIndex` before its window's content, so the first
  swipe after the announcement lands on it rather than going through the whole screen; a sheet's
  layer orders the same, the card being in that sheet's window (`GuideEdgeTest`).
  The ring is drawing only, the control keeps its own label and actions.
- Colours are the theme's tokens, so dark and light follow; the card is at most 360 dp wide and sits
  under the control, or over it when there is no room under it, inside what the window shows: the
  layer spans the window edge to edge, so the keyboard and the system bars (`safeDrawing`) are taken
  off first, or a card for the Return key or the pane would sit behind the keyboard (`cardTop`).
- **What is kept.** Help's sheet (the list or an entry's page, with its note) is saved as the home's
  sheets are, through a rotation, the app lock and the process going. The spotlight is not: a ring
  round something that may have moved is worse than none, and Show me is one tap again.
- **Show me and Settings' back arrow.** A page Help opens (Security, Terminal controls,
  Notifications) has no arrow back to Settings, even when Settings opened one before and it was
  swiped away; Settings opens with no arrow either, as from the home.

The hooks into existing code: `ActionSheet` wraps its content in a layer; `RowAction` takes a spot
for the rows its sheets draw; `ChoiceRow` passes a modifier through; one `GuideOpens` in HomeScreen
and one in TermScreen; RexApp provides the guide and hosts `GuideHost`; Help's row in the four
places; and one `guideTarget` per control. Nothing else changes, no dependency is added.

## Tests

- `GuideRegistryTest` (JVM and emulator, the whole app on ScriptedRex): for every entry, from the
  home or from a terminal on a tab, Show me ends with that entry's control drawn in a spotlight, on
  screen. An entry whose control was renamed, moved or removed fails here - the Insert path key, a
  Home redesign - not in the hands of someone following the guide. And each tour step finds its
  control.
- `GuideScreenTest` (JVM and emulator): Help from the home's ⋮ and from Settings; searching
  ("lock") narrows to the entry and Show me opens Security on Lock RexPocket; the spotlight's Got it
  and a tap elsewhere dismiss it, and the tap reaches the control; a terminal entry with no tab open
  says so and its Open a tab gets there; the palette's Help rows; the tour's Next, Skip and its one
  automatic run on a new install only.
- `GuideEdgeTest` (JVM and emulator): Back ends the tour without leaving the app; a press on the
  card's words does not reach the row under it; Help's page survives a rotation; Show me into a
  page Settings also opens has no arrow to Settings; Backup swiped away and opened from a file has
  none either; no tour on a start from the widget, or under a link's Add host (emulator).
  `GuideCardTest` (JVM) places the card above the keyboard and below the status bar.
- `GuideEntriesTest` (JVM): ids unique; every spot of the enum is pointed at by an entry, a tour
  step or is a row that opens Help; every section has entries; the gestures and long presses are
  all there; when the tour is due. The strings in all three languages are `TranslationParityTest`'s.
- The screen tests hand their own `Guide` to the app (an internal overload of `RexPocketApp`), and
  `freshApp` clears the `guide` preferences, so no other test ever sees the tour.

## Left out, on purpose

- **A "?" on every screen.** Help is where people already look (⋮, Settings, the palette); more
  buttons would be more for everyone to read past. The first-run screen (no hosts) explains its
  three ways in itself and has no Help row: it is one page of what Help would say.
- **Making the change for the person.** Show me points; it never taps, toggles or types. A guide
  that turns on the app lock is a guide that can lock someone out.
- **Dialogs and system screens as targets.** Android's notification shade, the Quick Settings
  panel, the widget picker and the camera are not the app's to draw on; their entries say what to
  do there and point at the app's own end of it (Mute this tab, a host's tile group).
- **A tour of the terminal.** The ⋮ menu is the terminal's own map; its entries each point at their
  row. A second tour on a screen people go to to work would be in the way.
- **Rich media** (videos, animations of a pinch). Text and the real control are enough, and keep
  the APK and the strings as they are.
- **Syncing seen tours**, or putting the tour in the backup: it is a hint for this phone.
