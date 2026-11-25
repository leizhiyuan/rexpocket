# Home: the hosts, what needs you, and how each row behaves

The home is the list of hosts, each with its sessions and their tabs, and the place to see at a
glance what a fleet of agents needs. It builds on several hosts at once (multi-host.md). Code:
`HomeScreen.kt` (the screen, its dialogs and sheets), `HomeList.kt` (the rows and the tree),
`HomeActions.kt` (the standard sheets and TalkBack actions), `ActivityList.kt` (the filters),
`HomeMarks.kt` (seen marks and the filter), `tabAttention` and `activitySections` in core.

## What a row says

- **A tab** is dotted and worded by its most pressing report (`tabAttention`), across every record
  of every pane: amber *waiting*, red *failed*, blue *done*, green *running* - the word always with
  the colour ("waiting for approval · 2 panes"). What else it has follows: "running · 1 done · 1
  pane" for an agent at work whose task finished, "waiting for an answer · running · 2 panes" for a
  tab working in one pane and asking in another. A waiting or failed tab shows the first line of
  its program's message. When the pane
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
| Tab, pane line, Needs you row | opens it (queued while the host reconnects) | tab sheet | Approve, Deny, Reply, Interrupt (a filtered row; a blocked tab in the tree); Mark as seen (a done or failed filtered row) |

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
  New session, New tab from template, Collapse or Expand, Collapse and Expand all sessions, Mark all
  as seen (every tab of the host with news); Move up, Move down; Edit host, Copy address, Tailnet
  details; Remove host….
- Session: Connect or Retry first while the host is not live; Open, New tab, New tab from template,
  Rename…; Collapse or Expand, Mark all as seen; Move up, Move down ("2 of 7", Rex's full order); End
  session….
- Tab: Open, Rename…; Approve, Deny, Reply and Interrupt where they apply (as on Needs you's row:
  Approve and Deny for an approval prompt whose keys the app knows, Reply for a blocked tab but never
  a login, Interrupt while a program works or waits); Mark as seen, Status details, Host mac… (the
  host's own sheet, the way to its connection from a filtered list, where its header is not in
  sight); Move up, Move down; Close tab….

**Rename** gives a session or tab back the name Rex chose: Automatic name, or Save with the field
empty, sends an empty label, which `session.set_label` and `session.set_window_label` take as "the
label the server chose" ("quiet-meadow", "Window 89abcdef"; the API reference). A line under the
field says so.

**Hosts** are in the phone's own order (Move up and Move down in the host sheet; Rex has no order
of hosts), never moved by their connection, so positions stay put; their headers stick to the top
while their sessions scroll, and are TalkBack headings, their ⋮ named "Actions for mac". Remove
host… confirms, disconnects and removes the host with its cached list, folds and seen marks; Undo
puts it back in its place, not connected. (It is no longer in the host dialog.)

**Snackbars**: "Disconnected mac · Reconnect", "Disconnected all hosts · Reconnect" (which connects
the same hosts again), "Collapsed all · Undo", "Marked as seen · Undo" (one Undo for every host a
Mark all as seen covered), "Removed mac · Undo", "Deleted api · Undo" (a launch template), "Couldn't rename: … · Retry" (and the same for a new tab, an end, a close, a
move that Rex refused), and "api-work was ended on mac" when a session whose sheet or dialog was
open, or that was on the terminal, was ended elsewhere (its saved fold goes too). End and Close
confirm first, so they have no Undo, nor does Rename.

## Filters, search and the bottom bar

**Activity** is a fourth chip, between All and Needs you: Rex's Activity view on the phone. It
lists every connected host's tabs that a program reported on, each once, by its most pressing
report (seen done and failed ones left out, as everywhere), arranged as Rex arranges it:

- **By priority** (Rex's "Show Activity by Priority", the default): captions Blocked, Failed,
  Done, Working, Idle (Rex's own words: its Program Activity settings say Working, Blocked,
  Done, and its priority view blocked, failed, done, working, idle), each with its count, as TalkBack headings; under each, one card of rows
  from every connected host, newest first, a row naming its host when more than one is
  connected. One list across hosts is what Rex's priority view is for: the most pressing thing
  first, wherever it is.
- **By session** (Rex's "Show Activity by Session"): under each host's header, each session's
  caption and its tabs in Rex's order.
- **Include idle** (Rex's "Include idle processes in activity"): off, an idle tab is there until
  it was seen - shown on the phone, Mark as seen, or a desk looking at it, as done and failed
  are; on, always. Idle is in the Activity view only: never in Needs you, Running, the counts or
  the dots. While it is off and seen idle tabs are left out, a line under the controls says so:
  "2 idle tabs hidden · Show", which turns it on.

The two choices are a pair of chips and a switch row at the top of the list, every one 48 dp and
said as selected or on; they are kept, with the chip, and go in the settings backup. A row is the
same row as Needs you's - tap opens the pane that reported, long press and ⋮ the tab sheet, the
same TalkBack actions, Reply, Approve and Deny on a waiting row, Interrupt on a busy one - so the
Activity view is Needs you and Running together, with idle, and Rex's two arrangements; those two
chips stay as they were, for the one-handed "what needs me" and "what runs".

**All, Needs you, Working** are chips (the Working chip was Running until 2026-11-24; its stored
and backed-up value is still `Running`, so older backups load) at the top, with counts across every connected host; the
choice is kept. **Needs you** is the tabs whose most pressing report waits on the person, or failed
or finished and was not seen. **Working is anything working**: every tab with a `working` record in
any pane, root or child, whatever else it has - so a tab can be in both, and its row says both
("working · 1 done" in Working, "done · working" in Needs you). The dot is always the tab's most
pressing state, in Rex's order. A tab with no records, or only idle ones, is in neither. Each is a
flat list (Needs you in Rex's priority order, Running newest first) under each host's header (no
folds), with Reply
(never for `auth`, as in notifications) and Interrupt on Needs you's rows, **Mark as seen** on a
done or failed one (with Undo, TalkBack "Mark build as seen"), and a line for each host that is
not connected, with Connect.

**What the filters cannot see.** Only a live connection hears a host's programs, so the counts and
lists are the connected hosts'. While any saved host is not connected - or was asked to connect and
cannot get through, which its line offers to Retry now - the counted chips carry a
small hollow ring and say "Connected hosts only" to TalkBack, and an empty list says "Nothing needs
you on connected hosts · 2 not connected", with Connect all when more than one is away (one has its
own line's Connect under it).

**Answering from Needs you** (notifications.md, "Approve and Deny", "Quick replies", "The prompt
composer"): a row waiting on an agent's approval prompt - `permission` from an agent in the key
table - has **Approve** and **Deny** before Reply (TalkBack: "Approve for build"), which send that
agent's keys without taking the pane. The row's prompt is read again from Rex first; when it changed,
nothing is sent, a toast says "Prompt changed, nothing was sent" and the pane opens. **Reply**'s
dialog has the quick replies as chips that put their text in the field, and **Compose**, which takes
the pane, with what was typed, to the prompt composer: a longer prompt in one paste, Return after it
or not, its draft kept for the pane. Every button is at least 48 dp high. The same buttons are under
a blocked tab in the tree (All), so a prompt is answered without changing the filter, and in the tab
sheet wherever the tab is listed; the TalkBack actions follow the sheet.

The home's ⋮ beside the chips has Expand all, Collapse all (with Undo), **Mark all as seen** (what
the list shows now: the filter's tabs, or the tree's as the search narrows it, on every connected
host; one Undo); **Connect all** (the hosts not connected) and **Disconnect all** (with Reconnect);
Add host, Import hosts and Tailnet; the tailnet is a chip there once connected, and the full card in
the list while it is on its way or needs the person.

The **bottom bar** holds ⚙, the search field (above the keyboard while it is up) and +. + makes a
session on the one connected host, or asks which host, offering one that is not connected as
"Connect & create". The search keeps the tree, every fold open and no chevrons, each row with its
usual tap and ⋮; Back clears it. A + New tab row under a session's tabs makes a tab and opens it;
the session sheet's New tab makes one without leaving the home.

**Not built from the spec:** the "Moved to place 2 of 7" snackbar for a row moved out of view (the
sheet's caption says the place, in Rex's full order, which a move always uses).

## Launch templates

A host has a list of templates, each a name, a directory and a command ("api", `~/src/api`,
`claude`), in the person's order. **New tab from template** in the host's sheet and a session's
sheet lists them; a tap opens a new tab, labelled with the template's name, that starts in the
directory and runs the command, and shows it on the phone (the host connects first when it is
not). From a session's sheet the tab goes in that session; from the host's, in the session the
phone shows on that host, or its first, or a new session when it has none. The sheet's caption
says which ("Opens in api-work"). A session ended meanwhile, at the desk or here, is no longer
offered: the caption then says a new session, and that is where the tab opens.

Rex's shell block takes `command` and `cwd` itself (`block-creators.json`, the shell flavor's
options), so nothing is typed into the tab: it is made running `sh -c 'cd ~/'src/api' && claude;
exec "${SHELL:-sh}" -l'` with `interactive: true`, so Rex runs it through the person's shell with
their startup files read and the agent is on the PATH their terminals have. The directory is quoted
and a leading `~` left to the shell (a plain `cwd` does not expand it; one that is not a directory
starts in the home); the command is a shell line; when it ends the tab is a login shell in that
directory, as if the line had been typed. Seen on preview.22 (`RealRexLaunchTemplateTest`).

Edit templates lists them; a template's own sheet has Edit…, Move up, Move down and Delete (with
Undo; the sheet closes so the snackbar is in sight). The dialog has Name, Directory and Command;
Save needs a name and a directory or a command. They are kept per host on the phone
(`LaunchTemplateStore`) and go in the settings backup, by host name (settings-backup.md, an
optional `templates` list, so older files load). Code: `LaunchTemplate` (core), `HomeTemplates.kt`.

## Status of tabs never opened

Every tab of every session has its state on the home, in Running, Needs you and Activity, without
being opened. Each pane is read once (`program_status`, `pwd`, `process`, `title`) when its view is
first known, and then follows Rex's status events. A session the phone has not attached on this
connection may send none (a Mac did not, though the guide says `event_scope: all` brings them):
its panes' `program_status` and its `session.view` are read again every 12 s while the app is on
screen and every minute while only background alerts run, backing off to 2 min while the reads
fail, until a status event comes from that session. Rex has no call for many panes at once, so
one sweep is a burst of calls sent together. Details in rex-parity.md, "Status of sessions the
phone never opened"; `UnopenedStatusTest` (scripted and real Rex).

A tab also counts as working when its program's title spins (a braille frame such as ⠇ standing
as a word: omp's "π ⠇ ROTATE: …", Claude Code's "⠐ Claude Code") and Rex holds no working or
blocked record for it: the phone adds a `working` record with id `~title` (Status details shows
it). On a person's Mac, Rex.app listed three omp tabs as Working whose records the phone did not
have, even once opened; a prompt mark (OSC 133;A) makes Rex drop a `working` record (reason
`prompt`, checked on preview.22) and omp does not report a state twice, while its title keeps
spinning. `UnopenedStatusTest.aSpinningTitleIsWorkingWhenRexDroppedTheRecord` (scripted and real).

## Seen

Rex keeps no seen state and does not tell one client what another shows (rex-parity.md, "What other
clients show"), so seen is the phone's, in `HomeMarks`: by host and pane, a mark that lists **each
record's report** that was seen (`seenMark`: one `seenStamp` per record - its id, state, kind and
message; a new report is news again). Seen is per record: a seen done or failed record is left out
and the pane falls back to its next report, so an agent whose root works while a child record is
done and seen is running, not gone. A mark made before marks were per record is the root record's
stamp, which still holds. A pane's reports are seen when:

1. it was on the phone's screen (`Connections.seen`, which also settles its notification);
2. the person marked it (Mark as seen, Mark all as seen; Undo puts the marks back);
3. **a desk looked at it**, inferred by `PocketSession.noteDeskLooks` into `PocketState.deskSeen`:
   a desk client took the pane after it finished (`block_owner_changed` reason `take`), or the pane
   is the session's active one (Rex's active tab, its focused pane) while a desk client owns it. A
   desk client is one whose `client.inspect` info says kind `app` on a platform other than `other`
   - Rex.app on a Mac, not this phone (which says `other`), another phone or Rex's CLI.
   `Connections` marks it and settles its notification.

What a desk saw is the reports the pane had when it looked; a child record that finishes later is
news again until the next look.

**Limits of the inference.** It says a desk *showed* the pane, not that the person looked: a Mac
asleep on that tab counts, and a desk already on the tab when the program finished sees it at once.
A desk showing the tab in a second window that did not take the pane is not seen. Another phone
showing it is not counted. Marks of panes that have no report any more are dropped while the host
is live.

## Status details

The tab sheet's **Status details** (and the terminal ⋮'s, for the tab on screen) says why a tab is,
or is not, where the home puts it: what the phone shows for it and the lists it is in, then pane by
pane the phone's summary and **every record Rex reports** - id, parent, state, kind, `app`,
`effective_app`, owner pid and name, title, msg, progress, `updated_at` with its age - and whether
the phone's mark and a desk's cover it. **Copy** puts it all on the clipboard as plain text: the
person asked, so the programs' titles and messages are in it. The field names stay Rex's, in
English, as a log's do. The diagnostics report carries a redacted digest instead
(`activityDigest`): each tab by place ("session 1 tab 2"), each record as root or child with its
state, kind, app, owner name, age and seen marks, what the phone made of it and its lists - no
titles, messages, record ids or labels.

## Tests

`AgentReplayTest` (core, ScriptedRex and rex-real: omp, Codex, Claude Code and an agent with child
records replayed through their states turn by turn; the phone holds Rex's records and lists the tab
as they imply; rex-parity.md, "How the agents report"), `ActivityTest` (core: idle until seen or always, by session in Rex's order), `SettingsBackupTest` (the Activity fields optional), `RunningTest` (core, ScriptedRex: root working with a child done, unseen and seen; seen on a desk;
a tab working in one pane and waiting in another; a tab with nothing at work; a session whose view
came late; nothing seen hides a working report; old marks still hold), `RealRexAppTest.anAgentAtWorkWithAChildDoneIsRunning` (rex-real: a shell reporting OSC 7501
root working and a child done), `HomeStateTest` (status words, summaries, hidden-pane line; folds kept; a session without its tabs
folds and opens from its button; Collapse all's Undo restores the folds exactly),
`HomeManageTest` (sheets and TalkBack offer the same items, in order; moves, renames, ends, closes
reach Rex without attaching; a session ended at the desk closes its sheet and says so),
`MultiHostTest` (host taps fold, never connect; the three-state fold; the host sheet following its
connection; a failing host's Retry; + with two, one and no hosts connected; host order and Remove
host with Undo), `ActivityViewTest` (the Activity chip by priority and by session, idle until seen and with Include idle, both kept; Needs you and Running: an agent at work with a child done in both lists and
still running once seen, a tab working and waiting in both, Status details and its Copy; order,
counts, Mark as seen and Undo, seen on
the phone and on a desk, Reply hidden for `auth`, Interrupt, the not-connected line, the filter
kept; Approve's Return for Claude and Deny's Esc for Codex, none for Terraform, a changed prompt
opening the pane and typing nothing, Reply's Compose on the emulator), `DeskSeenTest` (core, ScriptedRex: the desk inference), `ClientViewProbeTest` (rex-real:
`client.inspect`'s shape). `HomeBulkTest` (a blocked tab's buttons in the tree and its sheet, and
the tab sheet's Host…; a done row's Mark as seen, the home's and a host's Mark all as seen, each with
Undo; Connect all, Disconnect all and Reconnect; the empty filter and the marked chips while a host
is away; the idle tabs Activity hides; Automatic name and adding a template on the emulator; a
template run from the host's sheet, moved, deleted and undone), `LaunchTemplateTest` (core: the
shell line and its quoting; what the phone asks ScriptedRex for; on a real Rex the tab starts in the
directory, runs the command and is a shell after), `SettingsBackupTest` (templates by host name).

## Tailnet controls

The tailnet chip, its sheet and the home's ⋮ carry the tailnet's Connect and Disconnect in the
places and words a host's row and sheet have them: Connect, Cancel, Retry now, Disconnect; a
Disconnect with hosts on the tailnet confirms and names them; the snackbar "Disconnected
Tailscale" has Reconnect, which brings the hosts back too. The chip stays (grey) while the
tailnet is disconnected by hand or waits for a host; the card is for stages that need the person.
Each host's sheet and the tailnet's sheet open its page, "Connection and notifications", one layout
for both. See [tailnet-control.md](tailnet-control.md).
