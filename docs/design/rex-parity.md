# Rex parity

What Rex offers, what RexPocket does with it, and what a phone should do about the rest. Every claim
names its source; anything taken only from a screenshot is marked **unconfirmed**.

## Sources

| Short name | What it is |
| --- | --- |
| **docs:program-status** | [Program Status Protocol (OSC 7501)](https://www.superlogical.com/rex/docs/build/program-status), revision 0.4 |
| **docs:events** | [Events reference](https://www.superlogical.com/rex/docs/reference/events) |
| **docs:api** | [Server API Methods](https://www.superlogical.com/rex/docs/reference/api) |
| **docs:lua** | [Rex Lua API](https://www.superlogical.com/rex/docs/reference/lua) (the terminal's block methods) |
| **docs:mac-actions**, **docs:mac-settings** | [Mac Actions](https://www.superlogical.com/rex/docs/reference/mac-actions), [Mac Settings](https://www.superlogical.com/rex/docs/reference/mac-settings) |
| **docs:ios-actions**, **docs:ios-settings** | [iOS Actions](https://www.superlogical.com/rex/docs/reference/ios-actions), [iOS Settings](https://www.superlogical.com/rex/docs/reference/ios-settings) |
| **docs:actions** | [Custom Actions](https://www.superlogical.com/rex/docs/customize/actions) |
| **code** | This repository: `RexClient.kt`, `RexModels.kt`, `PocketSession.kt`, `Attention.kt`, `Upload.kt` |
| **fixture** | `app/src/test/resources/rex-stream.bin`, recorded from a real Rex (data connection only) |
| **screenshot** | The user's Mac, Rex's Activity sidebar with a Failed and a Done group (November 2026) |
| **real** | Rex 0.1.0-preview.22 (api revision 19) on Linux, driven by RexPocket's code, probes and the `rex` CLI as the desk; see "Real Rex" below. Marked **verified on preview.22 / api 19** |

All docs pages were read on 2026-11-22 from `superlogical.com/rex/docs`, which serves every page as
Markdown (`/rex/docs/llms-full.txt`). rex.app was not used. The claims marked **verified on
preview.22 / api 19** were then checked against a real Rex; "Real Rex" lists what was seen, and where
it differs from the docs, real Rex wins. Rex's own catalogue of its API and CLI, taken from that
server, is in [rex-protocol/](rex-protocol/README.md).

## What Rex sends and what the app does with it

### The control connection

Hello and welcome (**code**: `RexClient`): the app asks for API revision 7 (11 for uploads, on their
own connection) and keeps the welcome's client id, reconnect token, `resumed`, `api_revision` and
`limits.max_message_bytes`. All used.

### Methods (docs:api)

| Method | App | Use |
| --- | --- | --- |
| `session.bootstrap` (with `event_scope: all`), `session.list`, `session.view`, `session.attach` | used | the host list, each session's tabs and panes, opening a session |
| `session.create`, `session.new_window`, `session.new_split`, `session.close_window`, `block.close`, `session.destroy` | used | new session, tab and pane; close tab, pane, session |
| `session.set_label`, `session.set_window_label`, `session.set_block_label` | used | rename |
| `session.move`, `session.move_window` | used | Move up and Move down for a session or a tab on the home ("Ordering") |
| `session.own_block`, `session.disown_block` | used | the phone's size for the pane on screen; Release / Take over |
| `block.event` (`paste`), `client.key` | used | paste, keys |
| `block.method` by full name: terminal `write`, `resize`, `size`, `program_status`, `pwd`, `process`, `title`, `upload_begin`, `clipboard_read` | used | typing, sizing, status, directory, program, title, Send a file, a program's large copy |
| `block.method` by short name: terminal `clear`, `reset`, `format` (`text`, `trim`, `unwrap`, `max_bytes`), `blob_read` | used | the palette's Clear terminal and Reset terminal; Copy output and Share output |
| `data.connect` | used | the pane's stream (**fixture**) |
| `session.focus_block`, `session.focus_window`, `session.focus_next_*`, `session.focus_previous_*`, `focus_history.reverse` | not used, on purpose | moving between tabs and panes is the phone's own; Rex's focus is the desktop's (README, "Ownership") |
| `session.zoom_block`, `session.swap_blocks`, `session.move_block`, `session.resize_pane` | not used | see the verdicts below |
| floating layers: `session.new_layer`, `float_block`, `close_layer`, `raise_layer`, `lower_layer`, `resize_layer`, `set_layer_state` | not used | the app shows only a window's tiled blocks (**code**: `parseView` reads every layer's blocks as one list) |
| `session.detach_block`, `session.new_block`, `session.list_blocks`, `session.describe_block`, `session.help`, `help.all`, `block_creator.list` | not used | |
| `client.action`, `client.keymap`, `client.reply` | used | the person's custom actions in the palette and their questions (command-palette.md, "Rex's custom actions") |
| `client.perform`, `client.list`, `client.set_label`, `client.set_script` | not used | |
| `hosts.list` | Used | Import hosts › From Rex: the connected Rex's host list read as hosts to import, through the same preview as a host list (host-import.md); `hosts.put` and `hosts.remove` only seed it in the tests |
| `hosts.put`, `hosts.remove` | not used | the phone never writes Rex's list |
| `server.*` (status, version, whoami, config_reload, stop, tailscale.*, apple.*) | not used | |
| `target.complete`, `target.resolve` | not used | |

### Events (docs:events; code: `PocketSession.onEvent`)

| Event | App |
| --- | --- |
| `session_created`, `session_destroyed`, `session_moved` | used |
| `session_view_changed`, `session_label_changed` | used |
| `block_owner_changed`, `block_closed`, `window_closed`, `window_created`, `active_window_changed` | used: the view is read again |
| `window_label_changed`, `block_created` | not handled by name; the `session_view_changed` that follows them is (docs:events: it "follows almost every other event") |
| `client_connected`, `client_disconnected`, `client_connection_changed`, `client_joined`, `client_left`, `client_changed`, `creator_changed`, `keymap_changed`, `hosts_changed`, `tailscale_status_changed`, `client_performed`, `client_perform_requested` | ignored |
| `client_prompt_requested`, `client_prompt_ended`, `client_call_requested` | used on an action's own connection: questions shown, `client_call_requested` answered `not_supported` |
| `terminal.program_status_changed` (`id`, `record`, `reason`) | used: Activity, tab dots, alerts. `reason` is always `report` (verified on preview.22 / api 19) and is not read; the record's `updated_at` gives Activity its ages |
| `terminal.program_status_removed` (`ids`, `reason`) | **was ignored, now used** (this change): Rex drops working and blocked records when the program exits or the shell prompts again (docs:program-status, "Lifetime"), and a `clear` removes records; the phone kept them, so a pane stayed "running" |
| `terminal.pwd_changed`, `terminal.process_changed`, `terminal.title_changed` | used |
| `terminal.child_exited` (`exit_code`, `error`) | used: the closed note says the program exited, with its code, when the pane closes within 5 s of it; otherwise that it was closed on another device |
| `terminal.clipboard_written` (`text`, `id`, `bytes`) | used: an OSC 52 copy goes on the phone's clipboard, from the pane on screen while the app is in front (`PocketSession.copies`); when `text` is left out the copy is read with `clipboard_read` |
| `terminal.bell` (`{}`), `terminal.desktop_notification` (`title`, `body`; `title` is `""` for OSC 9) | **used** (verified on preview.22 / api 19): a buzz on the pane on screen, notifications while away; see [notifications.md](notifications.md) |
| `terminal.progress_report` (`state`, `progress`) | **used** (this change): a pane's OSC 9;4 progress bar, on the top bar, the pane switcher and the home's tab row, and as a progress notification when asked for (terminal-controls.md, "Progress"). `state` is `set`, `error`, `indeterminate`, `pause` or `remove`; `progress` (0-255 in the schema, the program's number) is left out when the program gave none. Rex skips reports that come in quick succession but always sends the newest, and has no method to read a bar back. Held by `PaneSignalsTest` on ScriptedRex and on real Rex |

### A status record (docs:program-status, "Keys")

`state` (`idle`, `working`, `done`, `blocked`, `error`), `id` (a path; children inherit `app`), `kind`
(`permission`, `question`, `auth`, with `blocked` only), `progress` (0-100, with `working` or
`blocked`), `app`, `title`, `msg`. **There is no time in a record, and the events carry none.** The app
reads all of them (`parseProgramRecord`); a pane's records are summed up to one by `summarizeStatus`.

**Correction (verified on preview.22 / api 19):** a record does carry a time. Rex adds `updated_at`
(when the report arrived, by the server's clock), `effective_app` (the `app` inherited from the
nearest parent record) and `owner` (the foreground process, `pid` and `name`), at the app's API
revision 7 too. The app now takes a state's age from `updated_at`, so a state found on the first read
has its age (capped at now, as the phone's clock may differ). Rex itself sums nothing up:
`program_status` lists every record, root first, and the summary is the app's. It follows Activity's
order, blocked, error, done, working, idle (`statusPriority`), so a pane whose root works while a child
record is done sums up to done (`PocketState.blockStatus`, which the terminal's pane and tab dots
show). Whether a program is still busy - for the key bar's C-c and the running dots - is asked of
every record instead (`PocketState.busy`).

**The home no longer works from the summary (this change).** The user's Mac showed an agent's tab
as Working while the phone's Running did not list it. Three causes, all confirmed in code and held
by `RunningTest`: (1) the pane's records were summed up to the most pressing, so root `working` plus
child `done` read as done; (2) once that done was seen - on the phone, by Mark as seen, or by the
desk inference, which is exactly what a Mac looking at the tab triggers - `tabAttention` dropped the
pane and never fell back to its working record, so the tab left both Needs you and Running; (3) each
tab stood in one group only, so a tab working in one pane and waiting in another was never in
Running. Now the phone keeps every record (`PocketState.records`), seen is per record
(home.md, "Seen"), and the home reads all of them: Running is every tab with a working record,
Needs you every tab whose most pressing unseen report needs the person, and a tab can be in both.

### How the agents report (read 2026-11-24)

| Agent | Source | Records | `app` | States and when |
| --- | --- | --- | --- | --- |
| omp (oh-my-pi) | can1357/oh-my-pi, `packages/coding-agent/src/utils/run-status.ts`, event controller | root only | `omp` | `idle` at startup and after an interrupt; `working` from each run's start; `blocked` (`permission`: tool approvals, plan review; `question`: `ask`, an interactive console) while a dialog is open, with `msg`; `working` again when it is answered; `done` after a turn with a result, `error` (with `msg`) after a failed one; `clear` at exit. Re-sends `working` after Ctrl-Z/`fg`, since the shell's prompt dropped it. Never repeats a report |
| Codex (0.163+) | openai/codex, `codex-rs/tui/src/terminal_program_status.rs` | root only | `codex` | `idle`, `working`, `blocked` (`permission`, `question`, `auth`), `done`, `error`; no `msg`; `clear` when it stops |
| Claude Code (2.1.295+) | closed; Kaku's docs list its states | root (assumed) | **unconfirmed** | `working`, `blocked`, `done`, `error`, `idle` |
| pi (upstream) | badlogic/pi-mono | none | - | does not report OSC 7501: its tab shows its program, never a state |

So for omp, the user's agent, a working run is a single root `working` record; the phone always
put that in Running when it had it. What could keep it out, all fixed or ruled out here: a `done`
record of another program in the same pane (any child or root record summed over it - fixed by
reading every record); the session's view not loaded (its first `session.view` failed or came
late, so its tabs were unknown - now a report from a session without a view reads it at once,
and every status sweep retries missing views; `RunningTest.aSessionWhoseViewCameLateStillReachesRunning`
fails without it); status events that never came for a session the phone had not opened (seen on
a Mac; now such panes are read again on a timer, "Status of sessions the phone never opened"
below); and the desk-seen inference, which marks only `done`, `error` and `idle`
reports and never hides `working` (`RunningTest.nothingSeenHidesAWorkingReport`). Status details
on the phone now shows exactly which records Rex holds for such a tab.

**Replays.** `AgentReplayTest` replays each agent's sequence over several turns - working, blocked,
working, done, seen, the next turn, an error, a suspend, idle, clear - from `agent-replays/`
(omp, Codex, Claude Code as documented, and an agent with child records), on ScriptedRex
(rex-integration) and on a real Rex (rex-real, a shell loop in the pane writing each report).
After every step the phone holds Rex's records, and its lists are what Rex's records imply.
`tools/agent-status-capture` records a real session on a desk as such a fixture, titles and
messages redacted; any `.replay` added there is replayed.

### How Rex decides a tab is Working

Rex's docs do not spell out the sidebar's rule, so this is an inference from them, documented as
such:

- A tab shows "working dots, progress pies, and needs-attention dots and messages ... when a program
  in them reports its status" (docs:mac-settings, Show activity in tabs), and each state's activity
  is configured on its own: Working, Blocked, Error, Done (docs:mac-settings, Program Activity). So
  a tab is Working when **a program in it reports `working`** - any record, as a record is "what a
  program in the terminal reports" and root and children "exist at the same time ... Both are true,
  and the terminal decides which to show" (docs:program-status, "Records and ids", "Several
  records").
- `done` is "ready to look at and the user has not seen it yet", and the terminal "decides when to
  stop showing" done and error (docs:program-status, States); the Dock badge counts tabs with a
  program "blocked, failed, or finished that you haven't seen" (docs:mac-settings, Badge Dock
  icon). So seen applies to the finished report, not to the program still at work: once seen, the
  tab shows what is left - its working record. That is what the user's Mac showed, and what the
  phone now does.
- Activity by priority still puts a tab in one group, its most pressing (blocked, failed, done,
  working, idle); the phone's Running filter is wider on purpose: "anything working".
- Not used, as Rex's docs give them no meaning for this: a record's `owner` (the foreground process
  when it was reported) and `reason`. A working record whose owner is gone is dropped by Rex itself
  (`program_status_removed`), which the phone follows.

## Activity

### What Rex's Activity is

- A view of the sidebar's tabs, toggled by **Toggle Activity** (`client.sidebar.activity.toggle`,
  `cmd+.`), with two arrangements: **Show Activity by Session** and **Show Activity by Priority**
  (docs:mac-actions). The iOS app has Toggle Activity and View Activity… too (docs:ios-actions).
- Its rows are tabs: "Keeps tabs whose programs are idle in the Activity filter and popup"
  (docs:mac-settings, Include idle processes in activity).
- The priority order, from the search words Rex gives Show Activity by Priority: "blocked failed
  done working idle" (docs:mac-actions). The screenshot agrees for the two groups it shows (Failed
  above Done). The exact captions of the other groups are **unconfirmed**; the app uses Waiting,
  Failed, Done, Running, from the setting names "programs waiting for you", "Error", "Done",
  "Working" (docs:mac-settings, Program Activity).
- Idle tabs are shown only until seen, unless **Include idle processes in activity** is on
  (docs:mac-settings). `done` is "the result is ready to look at and the user has not seen it yet"
  and the terminal "decides when to stop showing" done and error records (docs:program-status).
  Whether a tab was seen is the client's own: no method or event reports it (docs:api, docs:events).

### What the phone does now

The eye on the home screen shows the connected host's tabs whose program reported `blocked`,
`error`, `done` or `working`, in that order, as groups Waiting, Failed, Done, Running
(`activitySections` in core, `ActivityList.kt`). A tab stands in the group of its most pressing
pane. Each row: the reporting program's tile (its `app`, or the foreground process) with a dot in
the group's colour (red failed and blue done as in the screenshot; amber waiting and green running
as the app's tabs already use - **unconfirmed** against Rex), the tab's title, its session's label,
`progress` when given, the age, and the record's `msg` in a box under it. A row opens the pane that
reported. "No program is waiting, failed, done or running" shows only when no tab has such a record;
off the host, the eye says to connect.

What the desktop shows that the phone cannot get from Rex:

- **The age** (`48m`, `9m` in the screenshot) is no longer one of them: records carry `updated_at`
  (verified on preview.22 / api 19), and the phone uses it.
- **Seen and unseen.** Done and failed tabs stay on the phone until Rex drops the record or the
  program reports something else. Idle tabs are left out, since showing them "until seen" needs a
  seen state Rex does not share. See the verdicts.
- **The second line** in the screenshot (`tau`) may be the session's label or the project directory;
  **unconfirmed**. The phone shows the session's label.
- **The failure box text** (`failed: prompt_failed: 40…`): the phone shows `msg`; whether Rex adds
  the word "failed:" itself or the program wrote it is **unconfirmed**.
- By-session arrangement and idle: built (this change), as the home's Activity chip
  (home.md, "Filters"): Rex's two arrangements, and Include idle with the phone's own seen marks
  standing in for the desk's (seen is per record, and a desk looking counts; home.md, "Seen").

Tests: `ActivityTest` (core, ScriptedRex): grouping, priority, a split tab, records dropped by Rex
(fails without this change), ages; `ActivityViewTest` (app, JVM and emulator): the eye shows failed
and done tabs grouped with the message, a row opens its pane, the empty line only when Rex has
nothing; screenshots `activity-portrait`, `activity-landscape`.

## Command palette

Built; see [command-palette.md](command-palette.md). Rex's iOS app has one (docs:ios-actions).

## Verdicts

Done = the app has it. Build now = built in this change. Adapt = worth doing differently on a phone,
not done yet, with an estimate. Skip = not for this app, with the reason.

| Rex capability (source) | Verdict | Notes |
| --- | --- | --- |
| Sessions: list, new, open, rename, close (docs:ios-actions Session) | Done | Next/Previous Session added to the palette |
| Tabs: new, rename, close, go to, next/previous (docs:ios-actions Tab) | Done | Next/Previous Tab and Go to Tab in the palette; on the home, a long press or ⋮ on a session or tab renames, adds or closes without attaching ("Managing sessions from the home") |
| Panes: split right/down, rename, close, focus next/previous (docs:ios-actions Pane) | Done | Split left/up: adapt, 0.5 day (`session.new_split` `side: before`) |
| Paste into Pane, Insert File, Show/Hide Keyboard, font size (docs:ios-actions) | Done | Font size in the palette now |
| Copy Block ID (`pane.copy_id`) | Build now | palette row |
| Command Palette (`client.palette.toggle`, `shift+cmd+p`) | Build now | command-palette.md |
| Toggle Activity / View Activity (`client.sidebar.activity.toggle`, `pane.view_activity`) | Build now | the eye, above |
| `terminal.program_status_removed` (docs:events) | Build now | was ignored |
| Show Activity by Session, by Priority (docs:mac-actions) | Built (this change) | the home's Activity chip, its By priority and By session chips |
| Include idle processes in activity (docs:mac-settings) | Built (this change) | the Activity view's Include idle: off, idle tabs until seen (the phone's per-record seen marks, a desk's look included); on, always |
| Mark as Unread (`client.tab.unread`) | Adapt, 0.5 day | the phone's seen marks can be taken back (Undo does); a sheet item would do it on demand |
| Custom actions in the palette (`rex.action`; `client.keymap` lists them, `client.action` runs them; docs:actions, docs:api) | Done | the configuration file's actions with no required `args`, under their category, run with `origin: palette` for the pane on screen (command-palette.md); shapes held by `RealRexActionsTest` |
| Prompts from actions (`rex.ui`, `client_prompt_requested`, answered with `client.reply`; docs:api) | Done | a dialog over the screen: a line of text, or items to pick (one, several, or typed); Cancel; closed by `client_prompt_ended` or its timeout. Rex asks only connections at revision 17 or later, so an action runs on its own connection at 17 |
| Move Tab, Move Tab Backward/Forward (`session.move_window`); session order (`session.move`) | Done | Move up and Move down in the home's session and tab sheets and in TalkBack's actions ("Ordering") |
| Zoom Pane (`session.zoom_block`) | Skip | the phone shows one pane at a time already; zooming would change the desktop's layout |
| Swap/Move Pane, Balance, Resize Pane, floating panes, Move Tab to New Window (docs:mac-actions) | Skip | layout editing for a screen that shows several panes; the phone shows one |
| Insert Path… (`terminal.list_dir`, docs:lua) | Built | Insert path in the ⋮ menu and the palette: the pane's program's folders from its working directory, the path picked typed as one shell word (terminal-controls.md, 4.3); Go to Directory… (typing `cd` with it) is not offered |
| Insert Photo… (docs:ios-actions) | Adapt, 0.5 day | Send a file already uploads any file; a photo picker entry point |
| Compose Input… (`client.composer.open`, docs:ios-actions) | Adapted (this change) | the phone's own composer, not the desk's: a multi-line sheet that sends one `block.event` paste, then Return or not, with a draft per pane (notifications.md, "The prompt composer"); typing still goes straight into the pane |
| Jump to Previous/Next Prompt, Scroll Page Up/Down (docs:ios-actions) | Adapt, 1 day | needs OSC 133 marks from the terminal library |
| Find (`client.find.*`, docs:mac-actions) | Built (this change) | the pane's own libghostty terminal is searched - the snapshot's scrollback and all output since, which is what Rex's terminal holds - with highlights and scrolling to a match; terminal `format` was not used: no more history, a round trip per search, and no rows to highlight (terminal-controls.md, "Find") |
| Copy mode (docs:mac-actions) | Skip | long-press selection is the phone's way |
| `terminal.child_exited` exit code and error (docs:events) | Adapt, 0.5 day | say why a pane closed on the closed-pane note |
| `terminal.desktop_notification`, `terminal.bell` (docs:events) | Done | Background alerts, one notification per pane, rate-limited and folded with the status alert (notifications.md) |
| Alerts for `done` (docs:program-status, States) | Done | on by default on a quieter channel: Rex's "result ready, not seen yet" |
| Answering an agent from its notification (`block.event` paste, `client.key`) | Done | Reply (paste + Enter) and Interrupt (Ctrl+C) to the named pane; no Reply for `auth`. Approve and Deny (this change) for the agents whose keys are known, below |
| `terminal.progress_report` (OSC 9;4) | Built (this change) | Rex forwards OSC 9;4 as its own event and does not map it to a status record (the record's `progress` is OSC 7501's); the phone shows the event's bar (terminal-controls.md, "Progress") |
| Keyboard shortcuts (docs:mac-actions, docs:ios-actions) | Built (this change) | a desk keyboard's own keys on the terminal screen (Ctrl+Shift+..., and Meta for a Mac keyboard), a help sheet and Android's shortcut helper; everything else goes to the program (terminal-controls.md, "A desk keyboard") |
| Rex's host list (`hosts.list`, `hosts_changed`) | Done | Import hosts › From Rex (host-import.md): label to name, endpoints to the address and other addresses, a Unix socket left out, no host key (asked on first SSH connection). `hosts_changed` is not followed: an import is a one-off. Shape held by RexShapeTest and `RealRexHostListTest` |
| Switch Host, Add/Remove Remote Host, Forward Port (docs:ios-actions Host) | Done / Skip | the home list is the hosts; port forwarding is Rex's client feature, not the server's |
| Settings, themes, cursor, appearance (docs:ios-settings) | Done in part | font, key bar, gestures and Settings › Appearance (app theme; terminal colours: match app, always dark, always light) are the app's. "Match the desk": adapt, 1 day - the stream's `theme` message (docs:llms "The Terminal Stream") carries the desk's default colours, but `StreamDecoder` drops it today and the app paints its own over the snapshot's; it needs the message decoded into a feed, kept per pane and applied instead of the app's, which no emulator here can check (libghostty runs on CI only). `set_theme` is not for this: it changes the desk's colours too |
| Diagnostics, Copy Debug/Version Info (docs:ios-actions) | Done | Settings › Diagnostics |
| Install Rex for Agents (`client.agents.setup`) | Skip | runs on the computer, not the phone |
| Server and Tailscale management (`server.*`) | Skip | administration of the computer; the desktop app's job |
| Clients list, perform on another client (`client.list`, `client.perform`) | Skip | nothing a phone user asked for |

### Answering an approval prompt

Rex reports that a program waits for approval (`state=blocked`, `kind=permission`) and which
program it is (`app`, inherited as `effective_app`), and gives every client `client.key` to answer
it; it does not say which key means yes. That is each agent's, so Approve and Deny exist only for a
short table of agents (notifications.md, "Approve and Deny"): Claude Code (Return / Esc), Codex
(`y` / Esc) and omp (Return / Esc for its tool approval and Yes/No confirms, from
`packages/coding-agent/src/ui/overlays/panels/approval-dialog.tsx` and
`ui/overlays/pickers/hook-selector.tsx` with the default `tui.select.confirm` and
`tui.select.cancel` bindings in `config/keybindings/tui-keybindings.ts`; not its plan review,
`msg` "Review plan: ...", whose keys depend on focus). Before the keys go, the pane's records are read again (`program_status`, which a
bootstrapped connection may call on any session) and compared with the prompt the person saw,
`updated_at` included, so an answer never lands on a newer prompt. `PromptAnswerTest` runs it on
ScriptedRex and on real Rex, where a shell reports the prompt (OSC 7501) and reads the key.

## Real Rex

Rex 0.1.0-preview.22 (`rex version`: `api: revision 19 (supports 0 to 19)`), the amd64 package from
`pkgs.superlogical.com/rex/apt` (archive key `E28636AC71905C6DDE40C1EDFAA6B80F641307A7`, package
SHA256 as pinned in `tools/rex/pin.env`), run as `rex server run --listen unix://… --extra-listen
http://127.0.0.1:7297 --allow-anonymous`. The phone side spoke API revision 7, as the app does. Every
point below is **verified on preview.22 / api 19**; `RexShapeTest` replays most of them against both
this Rex and ScriptedRex (`core/src/test/resources/rex-shapes/preview.22.txt`), and the `rex-real`
workflow runs that, the scenarios and `RealRexAppTest` against this Rex on every push.

### The open questions, answered

- **`program_status_removed`.** The root record's id is the empty string, in `ids` and in
  `program_status_changed`'s `id` (whose `record` then has no `id`). `reason` seen: `clear` (a
  `state=clear` report; with no `id` it removes every record at once, all ids in one event) and
  `child_exited` (the terminal's program exited while working; sent before `child_exited` itself).
  The schema (`block_creator.list`) also names `reset`, `prompt`, `foreground` and `evicted`; a stock
  Ubuntu bash, with no shell integration, produced neither `prompt` nor `foreground`: a `working`
  record outlived the prompt and Ctrl+C while bash was the record's `owner`. The app removes the
  listed ids whatever the reason.
- **`program_status` for a session not attached.** It answers, as the docs say: a connection that
  bootstrapped (`event_scope: all`) may call `program_status`, `size`, `session.view` and the other
  pane methods on any session; only `session.own_block` (`bad_request`, "the caller must be a client
  attached to the session") and `data.connect` (`conflict`, "stream is not attached to session …")
  need `session.attach`. Without a bootstrap every call is `conflict` "stream is not attached to
  session …". The bootstrapped connection also gets every session's `block_event`s, so Activity
  keeps failed and done tabs of other sessions current without attaching them (`ActivityTest`,
  `RealRexAppTest.activityShowsWhatRealProgramsReported`).
- **Status of sessions the phone never opened (2026-11-24).** Checked again on preview.22 Linux, at
  API revisions 7 and 19, with a probe of two sessions: a connection that bootstrapped with
  `event_scope: all` on session A gets B's `program_status_changed` and `title_changed` whether or
  not any client is attached to B, after a `session.attach` of A or of another session, and after
  a resume followed by a fresh `session.bootstrap`. A resumed connection that does *not* bootstrap
  again gets no block events and its `program_status` calls on B are `conflict` ("stream is not
  attached"); the app always bootstraps again. Rex's guide (llms.txt, "Events"; llms-full.txt,
  same table) says the same, and offers nothing else for status: no subscription, no session-level
  or batched `program_status` - one call per pane. Yet on a person's Mac (Rex.app, host m1-max)
  the phone showed status only for the tabs of sessions it had opened, i.e. attached: an omp tab
  Rex showed Working had no state on the phone. So the phone no longer relies on those events for a
  session it has not attached: the panes of a session that is neither attached on this connection
  nor known to have sent a status event on it are read again (`program_status` per pane, all at
  once, plus that session's `session.view`) every 12 s on screen, every minute while only
  background alerts run, doubling to at most 2 min while the reads fail. A status event from such a
  session ends its sweep for the connection. `ScriptedRex(blockEventsOnlyWhenAttached = true)` acts
  as that Mac did; `ScriptedUnopenedStatusTest` fails without the sweep, and
  `RealRexUnopenedStatusTest` runs the same two-session case (an OSC 7501 loop reporting working,
  blocked, done in the session the phone did not open) on the real Rex.
- **Working without a record (2026-11-24).** On preview.22, OSC 9;4 gives only `progress_report`
  and a title (OSC 0/2) only `title_changed`: neither makes a status record. A prompt mark
  (OSC 133;A) after `state=working` gives `program_status_removed` `{ids: [""], reason: "prompt"}`.
  Rex answers the feature query `OSC 7501 ; ?` with `states=idle,working,done,blocked,error:
  kinds=permission,question,auth`. omp (run-status.ts) writes only the root record, never the same
  report twice, and sets a spinner title while it works. Rex's docs say Activity comes from
  reports, and name nothing else; yet the Mac listed omp tabs Working with nothing the phone could
  read. The phone now counts a pane whose program title has a braille spinner frame, and no
  working or blocked record, as working (a record `~title`; home.md). The groups use Rex's words:
  Working (was Running), Blocked (was Waiting), Failed, Done, Idle.
- **A program that fails.** Rex makes no status record of an exit code: `sh -c 'exit 3'` gives only
  `child_exited` (`exit_code: 3`, `error: "exit status 3"`), `process`'s `last_exit`, and on the
  stream an APC `{"type":"child_exited","exit_code":3}`. Activity's Failed group comes only from a
  program that reports `state=error`, which then outlives its exit. A shell pane's program exiting
  closes the pane (and its tab, if it was the last), unless it failed within 250 ms (`exit.
  quick_exit_threshold_ms`) or the block was made with `exit.on_completion: false`.
- **The order of a pane closed by its program's exit.** `program_status_removed` (reason
  `child_exited`, for the records it drops), then `child_exited`, then `block_closed`, then - for a
  tab's last pane - `window_closed`, (`active_window_changed` when it was the active tab) and
  `session_view_changed`. So `child_exited` comes before the pane's and the tab's closed events,
  and the phone knows why the pane went by the time it reads the view again (`RexShapeTest`, "the
  program in a tab of one pane exits with 0"; `PaneLifecycleScenarios.theProgramInTheShownTabExits…`).
  `exit 0` gives `{exit_code: 0}` with no `error`.
- **Bell and desktop notifications.** `printf '\a'` gives `block_event` `bell` with `{}`;
  `printf '\e]9;body\a'` gives `desktop_notification` `{title: "", body}` and
  `printf '\e]777;notify;title;body\a'` gives `{title, body}`, to every client that may see the pane,
  from any session after a bootstrap. `block.event` paste and `client.key` to a pane of a session the
  connection only bootstrapped work too; `client.key` answers `{handled: false}` (`RexShapeTest`,
  `PaneSignalsTest`).
- **OSC 52.** `printf '\e]52;c;%s\a' $(printf hi | base64)` in a pane: every client that may see
  the pane gets `block_event` `clipboard_written` `{text: "hi", id, bytes: 2}` (at the app's API
  revision 7 too), and `clipboard_read` (`{}` args: the newest copy) answers `{id, data, total, eof:
  true}`.
- **Clear, Reset and the pane as text.** `block.method` `clear` and `reset` answer `{}`; Rex then
  sends the clients' streams what redoes it on their own terminals: for `clear`
  `ESC 7 ESC[H ESC[<n>M ESC 8 ESC[<n>A ESC[3J` (the lines above the cursor deleted, the scrollback
  erased), for `reset` `ESC c`. `<n>` is the cursor's row in Rex's own terminal, at the size of
  whoever holds the pane. With the phone holding it the phone's grid is that size and the result is
  the desk's: the cursor's line on the top row. With the desk holding it the phone's grid keeps the
  phone's size, and once the desk's screen has scrolled the phone's cursor is lower than Rex's: a
  desk of 27 rows after 40 prompts sends `<n>` = 26 to a phone of 51 rows whose cursor is on row 39,
  so the phone's prompt lands on row 13 with 13 older prompts still above it (worked through on a
  VT model, not seen on a device). That difference is the phone's, from its grid not following the
  desk's size, and is not fixed here. The rest is Rex's own and the desk shows it too (owner's Mac
  screenshots, 2026-11): a stray empty line at the top after some clears, and prompts that come
  back. `reset` leaves the cursor at the top left, on the desk and the phone alike, which is right. `format` with `{format: "text", trim: true, unwrap: true}` answers
  `{content}`; with `max_bytes` set and more left, `{content, id, total}` (no `eof`, though the docs
  say it is sent with `max_bytes`), and `blob_read` with that `id` and `offset` then answers `{id,
  data, total, eof}`.
- **OSC 9;4.** `printf '\e]9;4;1;42\a'` in a pane gives every client that may see it `block_event`
  `progress_report` `{state: "set", progress: 42}`; states 2, 3 and 4 give `error`, `indeterminate`
  and `pause`, and `9;4;0` gives `remove`. No `desktop_notification` comes of it, though it shares
  OSC 9's number (`PaneSignalsTest.progressBarsReachThePhoneInEveryStateAndGo`, run against this Rex
  in `rex-real`).
- **`client.keymap`.** On a server with no configuration file: `bindings: []`, `diagnostics: []`,
  `config` (path, exists, generation, hash) and 22 `actions`, all `kind: block`, each with `name`,
  `description` and `args` (a JSON Schema); only `com.superlogical.terminal.clear` ("Clear Terminal")
  and `…reset` ("Reset Terminal") have a `title`, `category` (`Pane`) and `keywords` - the ones a
  palette would list (the phone's palette has both, as `block.method` `clear` and `reset`). With an `init.lua` (`tools/rex/test-init.lua`), each `rex.action` is listed with `kind: lua`, `name`, `title`, `category` (`Actions` when the file gives none), `description`, `keywords`, `source` (`init.lua:12`) and `args` as a JSON Schema whose `required` lists what must be given; `client.action` answers `{result, client_actions}`, `result` what `run` returned, `ctx` carrying the call's `origin`, `session_id` and `block_id` (the focused one when left out). A `rex.ui.input` or `rex.ui.pick` sends `client_prompt_requested` `{call_id, action, prompt: {kind, title, placeholder, value, confirm, allow_other, multiple, items: [{id, title, subtitle, icon, detail}]}, timeout_ms}` (a plain-string item comes as `{id, title}`); `client.reply {call_id, result: {text} | {ids} | {cancelled: true}}` answers `{}` and the action gets the answer; a timeout sends `client_prompt_ended {call_id, reason: timeout}`. Only connections whose hello asked for revision 17 or later are sent questions: at 7 to 16 the action gets `nil, "unsupported"` (`RealRexActionsTest`). Saved in
  `rex-protocol/client-keymap.json`.
- **Ordering** (verified on preview.22 / api 19, at the app's revision 7). `session.move` takes
  `session_id` and `before_session_id` (left out: to the end) and answers `{}`; a bootstrapped
  connection may move a session it never attached, one that did neither gets `conflict` "stream is
  not attached to session …". Every bootstrapped client, the mover too, gets `session_moved` with
  the same two ids (no `before_session_id` when last); a move to where it already is answers `{}`
  and sends nothing. `before_session_id` naming the session itself is `bad_request` "before_session_id
  must identify a different session"; an unknown one `session_not_found` "session … not found" or
  "before session … not found". `session.move_window` (`session_id`, `window_id`,
  `before_window_id`) answers `{revision}` and sends `session_view_changed`, the active tab
  unchanged; in place, the revision is unchanged and no event comes; the same window twice is
  `bad_request`, an unknown one `not_found` "window … not found". Session order and ids survive a
  Rex restart. The phone applies `session_moved` to its list as it says (no `session.list` round
  trip) and reads the view again after a tab move, so its order is always Rex's, in the order Rex
  made the moves. Panes are not ordered: `session.move_block` puts a pane in a new split beside
  another, a layout change for a screen that shows several ("Swap/Move Pane", Skip).
- **Managing sessions from the home.** A connection that bootstrapped (`event_scope: all`) and never
  attached a session may call `session.set_label`, `session.set_window_label`, `session.new_window`,
  `session.close_window` and `session.destroy` on it; each answers `{revision}` (`destroy` `{}`).
  Without a bootstrap they are `conflict` "stream is not attached to session …", as above. Attaching
  is not what resizes a desktop's panes (taking one with `own_block` is), but an unattached caller
  also stays out of `session.list_clients`, and the pane its `new_window` makes has **no owner**
  (`block_created`, no `block_owner_changed`), where an attached caller becomes the owner (`create`).
  So the home's sheets never attach: Rename, New tab and End session for a session, Rename and Close
  for a tab, the last two confirmed first since they end programs (no undo can bring them back;
  a rename is undone by renaming). To an unattached caller the answer may come before the events
  it caused. The orders: a tab closed is `window_closed`, (`active_window_changed`),
  `session_view_changed`, then `block_closed`; a session destroyed is, per pane, `block_closed` and
  `session_view_changed`, then `session_destroyed`, then the answer. A `session.view` the phone sends
  for one of those `block_closed` events can therefore find the session gone (`session_not_found`),
  which the phone takes as the end it is, not a failure (`RexShapeTest`, "phone … has not attached";
  `RealRexAppTest.theHomeManagesASessionItNeverAttached`; `HomeManageTest` on ScriptedRex).
- **A tab closed from another client.** `session.close_window` (or `rex window close`): the phone gets
  `window_closed`, `active_window_changed` (when it was the active tab; `{}` when no tab is left),
  `session_view_changed`, and only then `block_closed` for each of its panes; the pane's stream closes
  with 4000 `block closed`. `block.close` of a tab's last pane: `block_closed`, `window_closed`,
  (`active_window_changed`), `session_view_changed`. A pane of a split: `block_closed`,
  `session_view_changed`. Refusals afterwards: `block.close`, every terminal method and `data.connect`
  are `not_found` "block not found: <id>"; `session.own_block`/`disown_block` are `bad_request` "block
  <id> is not in the session"; `session.close_window` and `set_window_label` are `not_found` "window
  <id> not found"; `set_block_label` is `not_found` "block not found".
- **Stream ends.** 4000 `block closed` when the pane closes (also for each pane of a destroyed
  session); 1001 `data provider canceled` when the client that opened it ends with a close frame;
  a second HELLO for a pane the same client already streams is refused with `0x02` and closed with
  1008 `data handshake rejected`; a stream the client closes with 1000 ends, any other close waits 60 s
  for a RESUME (the WELCOME says 60000 ms). A stream opens with the `GHOSTSNP` snapshot and then a
  `rex1` `resize` APC carrying the owner. A control connection that drops without a close frame
  leaves the stream open.

### What other clients show

Asked for "a done tab seen on the Mac counts as seen on the phone" (see notifications.md, "Left
out"): does Rex tell a client which tab or pane each other client is showing or focusing? From the
docs (api 19) and `ClientViewProbeTest` against real preview.22 in `rex-real` (two desk clients on
a session, the second focusing the other tab and taking its pane, the phone reading what Rex offers):

- **No.** `session.list_clients`, `client.list` and `client.inspect` describe a client (id, info,
  principal, connection state, the sessions it attached, its actions) and nothing of what it shows:
  no window, block or focus field. No event names a client's view.
- What there is: the session's **one active tab and focused pane**, shared by every client
  (`active_window_changed`, the view's `active_window_id` and each window's focused block), with no
  client named; and a pane's **owner** (`block_owner_changed` with `owner`, `previous`, `reason`
  `take`/`request`; each block's `owner` in `session.view`; `com.superlogical.terminal.size`'s
  `owner` and the size each client asked for). Seen on preview.22: the second client's
  `session.focus_window` gave the phone `active_window_changed {window_id}` and its take
  `block_owner_changed {owner, previous, reason: take, block_id}`; the client records had no view
  field, and `client_connected` (a session event) carries only the client's id, principal and time.
  Rex.app takes the pane of the window it focuses (`PocketSession.ownershipLoop`), so the owner is
  the desk that last focused a pane, not one that merely shows it.
- `focus_history.reverse` is the caller's own history only.

**Feasible as an inference, not as a fact.** The phone never moves Rex's focus, so a change of the
active tab or focused pane to a done pane after it became done, or a desk client (`info.kind` app,
`platform` macos) taking that pane, means a desk looked at it, near enough: `block_owner_changed`
reason `take` to a client other than the phone is the stronger signal, the active tab the weaker
(a CLI or script can move it too). A desk already showing the tab when it became done sends nothing
new, but then the active tab and the pane's desk owner say so at that moment. Neither says the person
was looking: a Mac asleep on that tab counts. Built as the home's seen marks (home.md, "Seen"): a
take after the pane finished, or the pane active while a desk client (`client.inspect` kind `app`, a
platform other than `other`) owns it, marks it seen on the phone.

### Other differences found, and fixed

| Seen on real Rex | ScriptedRex before | App before | Now |
| --- | --- | --- | --- |
| A resumed hello keeps the client: same id, `resumed: true`, still attached; its panes come back (`block_owner_changed` reason `restore`) | every connection a new client | `reconnectNow` closed with a close frame, which ends the client, its ownership and its streams (1001) - though its comment said Rex resumes it | fake resumes; `RexClient.drop` ends the socket without a close frame, which `reconnectNow` uses |
| A drop passes the panes at once to the next client in line (`lost`, to the desk that made the pane); a close frame passes them with `disconnect` | panes simply let go | - | fake keeps Rex's line: take beats request, most recent first |
| Panes of `session.create`'s first windows have no owner; `new_window` and `new_split` make the caller the owner (`create`) | nobody owned anything | `PocketSessionTest` assumed the creator owned a new session's pane, and 9 of its tests timed out before doing anything | fake as Rex; tests wait for the pane, which the phone takes by watching it |
| `block_owner_changed` carries `block_id`, `owner` (left out for none), `previous`, `reason`; disowning a pane another client holds changes nothing | empty payload, always sent | - | fake as Rex |
| Events of a change come before the response to the call that made it; every event and response has a `seq` | after, no `seq` | - | fake as Rex |
| Events go only to clients that may see them: attached sessions, or bootstrapped with a scope | to everyone | - | fake as Rex |
| Records: root id `""`, `updated_at`, `effective_app`, `owner`, event `reason` | id `agent`, no time | no ages for a state found on reading | fake and app as Rex |
| A session's last tab closing leaves the session, with no `windows` and no `active_window_id` in its view | (already, from the docs) | the screen showed a blank pane, typing failed with "No terminal in this window" | "No tabs in this session" with New tab, no key bar (`EmptySessionTest`) |
| New tabs are labelled `Window <last 8 of the id>`, panes `shell`; ids are `kind:<uuid>` | `shell`, `kind:N` | - | fake as Rex |
| `session.create`, `new_window`, `new_split`, `close_window`, `block.close` answer with ids and `revision`; `data.connect` with `expires_in_ms` | bare | - | fake as Rex |
| `close_window`, `set_label`, `set_window_label` answer `{revision}` only; `new_window` from a caller not attached makes a pane with no owner | `close_window` with its window id, `set_label` with the label, `set_window_label` `{}`; the caller always owned | - | fake as Rex |
| Rex's `pwd` is only what a program reported (OSC 7); a stock bash reports none | - | `statusAndDirectoryFollowEventsWithoutPolling` assumed the shell reports it | the test's shell reports its directory, as zsh's and fish's integrations do |
| Output written before a pane's stream opens is in the snapshot | - | `ForegroundStreamTest` typed before the stream opened and waited for the output after the snapshot | the test waits for the snapshot first |

### Where the docs and real Rex disagree

- None of substance at preview.22: the API reference served by the server (`/llms.txt`, saved in
  `rex-protocol/`) matched what was seen, including the attachment rules and the empty session.
  The website's events reference, which this document first followed, does not list `updated_at`,
  `effective_app` or `owner` on a record, nor the `reason` values; the server's own
  `block_creator.list` does.

### Not verified

- `program_status_removed` with `reason` `prompt`, `foreground`, `reset` or `evicted` (they need shell
  integration, a reset, or 64 records); a question answered on two devices at once; anything over Tailscale or TLS (the tests use the anonymous loopback listener).
- The rename dialog's text field on Robolectric: Compose never goes idle with it focused, so
  `RealRexAppTest` sends the rename as the dialog's Save does instead of typing into it.
