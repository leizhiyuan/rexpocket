# Home: the hosts, what needs you, and how each row behaves

The home is the list of hosts, each with its sessions and their tabs, and the place to see at a
glance what a fleet of agents needs. It builds on several hosts at once (multi-host.md). Code:
`HomeScreen.kt` (the screen, its dialogs and sheets), `HomeList.kt` (the rows and the tree),
`HomeActions.kt` (the standard sheets and TalkBack actions), `ActivityList.kt` (the filters),
`HomeMarks.kt` (seen marks and the filter), `tabAttention` and `activitySections` in core.

## What a row says

- **A tab** is dotted and worded by its most pressing pane (`tabAttention`): amber *waiting*, red
  *failed*, blue *done*, green *running* - the word always with the colour ("waiting for approval ·
  2 panes"). A waiting or failed tab shows the first line of its program's message. When the pane
  that has the news is not the one the tab shows, a line names it ("pane claude waiting for
  approval") and opens that pane.
- **A session** has a chip of its most pressing state that is news, and its tab count.
- **A host** has its connection's dot and a summary: "2 waiting · 1 failed · 4 sessions" when
  live, its connection's step while connecting, the problem while failed, "Not connected ·
  6 sessions" otherwise.
- A done or failed report that was **seen** keeps its word, quietly, with no dot, and does not count.

## One meaning per gesture

| Row | Tap | Long press / ⋮ | Buttons |
| --- | --- | --- | --- |
| Host | folds, in every state; never connects | host sheet | Connect, Cancel, Retry, or the tailnet's fix (Sign in, Join) |
| Session | folds, whether its tabs are loaded or not | session sheet | Open ("Tabs load when opened"), Connect ("Connect mac to see tabs") |
| Tab, pane line, Needs you row | opens it (queued while the host reconnects) | tab sheet | Reply, Interrupt (Needs you only) |

Nothing connects, retries or probes on a plain tap: the error line and the tailnet path line are
text; the path is measured again from the tailnet sheet.

**Folds.** A host's fold has three states: folded or opened by the person, which then holds in every
state, or not set - open while connected, folded otherwise. A host that is not connected opens to
the sessions it had last time, with one note saying so, rather than "cached" on every row. Their
sessions start folded (their tabs are not known) and open for this screen only. The search opens
every fold and hides the chevrons; Back clears it.

**Sheets** (`hostActions`, `sessionActions`, `tabActions`) are lists of `RowAction`s, and each
row's TalkBack actions are the same list's enabled items, in the same order; the click is labelled
Collapse, Expand or Open, and a folded row is said to be folded. An item that never applies is
hidden; one that needs a live host stays in place greyed out, saying "Connect first" or
"Reconnecting…"; paired items swap in place (Connect/Disconnect/Retry/Cancel, Collapse/Expand);
Move up and Move down grey out at the ends. A sheet follows its host while it is open; a confirm
dialog cannot confirm while its host is away; a sheet or dialog whose session or tab is gone, on any
device, closes. End session and Close tab confirm; Remove host confirms and offers Undo.

- Host: Connect / Retry now / Cancel / Disconnect (a failing host offers Retry now and Disconnect);
  New session, Collapse or Expand, Collapse and Expand all sessions; Move up, Move down; Edit host,
  Copy address, Tailnet details; Remove host….
- Session: Connect or Retry first while the host is not live; Open, New tab, Rename…; Collapse or
  Expand, Mark all as seen; Move up, Move down ("2 of 7", Rex's full order); End session….
- Tab: Open, Rename…; Mark as seen; Move up, Move down; Close tab….

## Filters, search and the bottom bar

**All, Needs you, Running** are chips at the top, with counts across every connected host; the
choice is kept. Needs you is waiting tabs, and failed and done tabs not yet seen; Running is working
tabs. Each is a flat list in Rex's priority order under each host's header (no folds), with Reply
(never for `auth`, as in notifications) and Interrupt on Needs you's rows, and a line for each host
that is not connected, with Connect. The home's ⋮ beside the chips has Expand all, Collapse all
(with Undo), Add host, Import hosts and Tailnet.

## Seen

Rex keeps no seen state and does not tell one client what another shows (rex-parity.md, "What other
clients show"), so seen is the phone's, by host and pane, for the report it was (`seenStamp`: state,
kind, message; a new report is news again), in `HomeMarks`. A done or failed pane is seen when:

1. it was on the phone's screen (`Connections.seen`, which also settles its notification);
2. the person marked it (Mark as seen, Mark all as seen; Undo puts the marks back);
3. **a desk looked at it**, inferred by `PocketSession.noteDeskLooks` into `PocketState.deskSeen`:
   a desk client took the pane after it finished (`block_owner_changed` reason `take`), or the pane
   is the session's active one (Rex's active tab, its focused pane) while a desk client owns it. A
   desk client is one whose `client.inspect` info says kind `app` on a platform other than `other`
   - Rex.app on a Mac, not this phone (which says `other`), another phone or Rex's CLI.
   `Connections` marks it and settles its notification.

**Limits of the inference.** It says a desk *showed* the pane, not that the person looked: a Mac
asleep on that tab counts, and a desk already on the tab when the program finished sees it at once.
A desk showing the tab in a second window that did not take the pane is not seen. Another phone
showing it is not counted. Marks of panes that have no report any more are dropped while the host
is live.

## Tests

`HomeStateTest` (status words, summaries, hidden-pane line; folds kept; a session without its tabs
folds and opens from its button), `HomeManageTest` (sheets and TalkBack offer the same items, in
order; moves, renames, ends, closes reach Rex without attaching), `MultiHostTest` (host taps fold,
never connect; the three-state fold; the host sheet following its connection; a failing host's
Retry), `ActivityViewTest` (Needs you and Running: order, counts, Mark as seen and Undo, seen on
the phone and on a desk, Reply hidden for `auth`, Interrupt, the not-connected line, the filter
kept), `DeskSeenTest` (core, ScriptedRex: the desk inference), `ClientViewProbeTest` (rex-real:
`client.inspect`'s shape).
