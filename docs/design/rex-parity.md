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

All docs pages were read on 2026-11-22 from `superlogical.com/rex/docs`, which serves every page as
Markdown (`/rex/docs/llms-full.txt`). rex.app was not used. Nothing here was checked against a running
Rex: no CI runner can get one (see testing.md), and the user's Mac was not reachable from this session.

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
| `session.own_block`, `session.disown_block` | used | the phone's size for the pane on screen; Release / Take over |
| `block.event` (`paste`), `client.key` | used | paste, keys |
| `block.method` by full name: terminal `write`, `resize`, `size`, `program_status`, `pwd`, `process`, `title`, `upload_begin` | used | typing, sizing, status, directory, program, title, Send a file |
| `data.connect` | used | the pane's stream (**fixture**) |
| `session.focus_block`, `session.focus_window`, `session.focus_next_*`, `session.focus_previous_*`, `focus_history.reverse` | not used, on purpose | moving between tabs and panes is the phone's own; Rex's focus is the desktop's (README, "Ownership") |
| `session.zoom_block`, `session.swap_blocks`, `session.move_block`, `session.move_window`, `session.move`, `session.resize_pane` | not used | see the verdicts below |
| floating layers: `session.new_layer`, `float_block`, `close_layer`, `raise_layer`, `lower_layer`, `resize_layer`, `set_layer_state` | not used | the app shows only a window's tiled blocks (**code**: `parseView` reads every layer's blocks as one list) |
| `session.detach_block`, `session.new_block`, `session.list_blocks`, `session.describe_block`, `session.help`, `help.all`, `block_creator.list` | not used | |
| `client.action`, `client.keymap`, `client.reply`, `client.perform`, `client.inspect`, `client.list`, `client.set_label`, `client.set_script` | not used | custom actions and prompts, below |
| `hosts.list`, `hosts.put`, `hosts.remove` | not used | Rex's own list of other Rex servers |
| `server.*` (status, version, whoami, config_reload, stop, tailscale.*, apple.*) | not used | |
| `target.complete`, `target.resolve` | not used | |

### Events (docs:events; code: `PocketSession.onEvent`)

| Event | App |
| --- | --- |
| `session_created`, `session_destroyed`, `session_moved` | used |
| `session_view_changed`, `session_label_changed` | used |
| `block_owner_changed`, `block_closed`, `window_closed`, `window_created`, `active_window_changed` | used: the view is read again |
| `window_label_changed`, `block_created` | not handled by name; the `session_view_changed` that follows them is (docs:events: it "follows almost every other event") |
| `client_connected`, `client_disconnected`, `client_connection_changed`, `client_joined`, `client_left`, `client_changed`, `creator_changed`, `keymap_changed`, `hosts_changed`, `tailscale_status_changed`, `client_performed`, `client_call_requested`, `client_perform_requested`, `client_prompt_requested`, `client_prompt_ended` | ignored |
| `terminal.program_status_changed` (`id`, `record`, `reason`) | used: Activity, tab dots, alerts. `reason` is not read |
| `terminal.program_status_removed` (`ids`, `reason`) | **was ignored, now used** (this change): Rex drops working and blocked records when the program exits or the shell prompts again (docs:program-status, "Lifetime"), and a `clear` removes records; the phone kept them, so a pane stayed "running" |
| `terminal.pwd_changed`, `terminal.process_changed`, `terminal.title_changed` | used |
| `terminal.child_exited` (`exit_code`, `error`) | ignored |
| `terminal.bell`, `terminal.desktop_notification` (`title`, `body`), `terminal.progress_report` (`state`, `progress`), `terminal.clipboard_written` (`text`) | ignored |

### A status record (docs:program-status, "Keys")

`state` (`idle`, `working`, `done`, `blocked`, `error`), `id` (a path; children inherit `app`), `kind`
(`permission`, `question`, `auth`, with `blocked` only), `progress` (0-100, with `working` or
`blocked`), `app`, `title`, `msg`. **There is no time in a record, and the events carry none.** The app
reads all of them (`parseProgramRecord`); a pane's records are summed up to one by `summarizeStatus`.

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

- **The age** (`48m`, `9m` in the screenshot). Records carry no time. The phone shows the time since
  it saw the state begin, from the event; a state that was already there when the phone read the
  pane has no age. The desktop has been watching all along, so its ages are complete.
- **Seen and unseen.** Done and failed tabs stay on the phone until Rex drops the record or the
  program reports something else. Idle tabs are left out, since showing them "until seen" needs a
  seen state Rex does not share. See the verdicts.
- **The second line** in the screenshot (`tau`) may be the session's label or the project directory;
  **unconfirmed**. The phone shows the session's label.
- **The failure box text** (`failed: prompt_failed: 40…`): the phone shows `msg`; whether Rex adds
  the word "failed:" itself or the program wrote it is **unconfirmed**.
- By-session arrangement: the phone always shows by priority, with each row naming its session.

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
| Tabs: new, rename, close, go to, next/previous (docs:ios-actions Tab) | Done | Next/Previous Tab and Go to Tab in the palette |
| Panes: split right/down, rename, close, focus next/previous (docs:ios-actions Pane) | Done | Split left/up: adapt, 0.5 day (`session.new_split` `side: before`) |
| Paste into Pane, Insert File, Show/Hide Keyboard, font size (docs:ios-actions) | Done | Font size in the palette now |
| Copy Block ID (`pane.copy_id`) | Build now | palette row |
| Command Palette (`client.palette.toggle`, `shift+cmd+p`) | Build now | command-palette.md |
| Toggle Activity / View Activity (`client.sidebar.activity.toggle`, `pane.view_activity`) | Build now | the eye, above |
| `terminal.program_status_removed` (docs:events) | Build now | was ignored |
| Show Activity by Session (docs:mac-actions) | Adapt, 0.5 day | a second arrangement of the same rows |
| Idle in Activity, seen/unseen, Mark as Unread (docs:mac-settings, `client.tab.unread`) | Adapt, 1-2 days | needs the phone's own seen state per tab (a tab shown on the phone counts as seen), stored per host |
| Custom actions in the palette (`rex.action`; `client.keymap` lists them, `client.action` runs them; docs:actions, docs:api) | Adapt, 1-2 days | the palette's next step: list the configuration file's actions with a `title` and no required `args`, run with `origin: palette`; the shape of `client.keymap`'s `actions` must be checked on a real Rex first |
| Prompts from actions (`rex.ui`, `client_prompt_requested`, answered with `client.reply`; docs:api) | Adapt, 2 days | in the palette's sheet; without it an action that asks waits until another app answers |
| Move Tab, Move Tab Backward/Forward (`session.move_window`) | Adapt, 0.5 day | reorder in the title sheet |
| Zoom Pane (`session.zoom_block`) | Skip | the phone shows one pane at a time already; zooming would change the desktop's layout |
| Swap/Move Pane, Balance, Resize Pane, floating panes, Move Tab to New Window (docs:mac-actions) | Skip | layout editing for a screen that shows several panes; the phone shows one |
| Go to Directory…, Insert Path… (`terminal.list_dir`, docs:lua) | Adapt, 1 day | a directory picker that types a path; useful with agents |
| Insert Photo… (docs:ios-actions) | Adapt, 0.5 day | Send a file already uploads any file; a photo picker entry point |
| Compose Input… (`client.composer.open`, docs:ios-actions) | Skip for now | the app types straight into the pane by design (terminal-controls.md); revisit if asked |
| Jump to Previous/Next Prompt, Scroll Page Up/Down (docs:ios-actions) | Adapt, 1 day | needs OSC 133 marks from the terminal library |
| Find (`client.find.*`, docs:mac-actions) | Adapt, 2 days | search the pane's scrollback (terminal `format`, docs:lua) |
| Copy mode (docs:mac-actions) | Skip | long-press selection is the phone's way |
| `terminal.child_exited` exit code and error (docs:events) | Adapt, 0.5 day | say why a pane closed on the closed-pane note |
| `terminal.desktop_notification`, `terminal.bell` (docs:events) | Adapt, 1 day | feed them to Background alerts, rate-limited (docs:program-status, "Security") |
| `terminal.progress_report` (OSC 9;4) | Adapt, 0.5 day | a terminal MAY map OSC 9;4 to the root record (docs:program-status); whether Rex does is unconfirmed. If not, a progress pie for programs that only send OSC 9;4 |
| Rex's host list (`hosts.list`, `hosts_changed`) | Adapt, 1 day | offer Rex's hosts as hosts to add; they are Rex servers the desktop reaches by label |
| Switch Host, Add/Remove Remote Host, Forward Port (docs:ios-actions Host) | Done / Skip | the home list is the hosts; port forwarding is Rex's client feature, not the server's |
| Settings, themes, cursor, appearance (docs:ios-settings) | Done in part | font, key bar and gestures are the app's; themes: adapt, 2 days |
| Diagnostics, Copy Debug/Version Info (docs:ios-actions) | Done | Settings › Diagnostics |
| Install Rex for Agents (`client.agents.setup`) | Skip | runs on the computer, not the phone |
| Server and Tailscale management (`server.*`) | Skip | administration of the computer; the desktop app's job |
| Clients list, perform on another client (`client.list`, `client.perform`) | Skip | nothing a phone user asked for |

## Not verified

- No change here was run against a real Rex or on the user's phone; ScriptedRex sends the event
  shapes of docs:events, but `program_status_removed`'s `ids` for the root record (taken here as an
  empty or null id, as the app already keys the root record) and the values of `reason` were not
  seen from a real Rex.
- Whether a real Rex answers `program_status` for a pane of a session the connection has not
  attached. Rex's API reference says it does: after `session.bootstrap` "the connection can then
  call methods on any session"; only `data.connect`, `session.own_block` and `session.disown_block`
  need `session.attach`. ScriptedRex now follows that (it used to refuse every pane call outside the
  one session last attached), and `ActivityTest` checks that a record set before the phone connected
  shows for a session the phone never opened. Not yet seen on a real Rex.
