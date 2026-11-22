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
| `session.own_block`, `session.disown_block` | used | the phone's size for the pane on screen; Release / Take over |
| `block.event` (`paste`), `client.key` | used | paste, keys |
| `block.method` by full name: terminal `write`, `resize`, `size`, `program_status`, `pwd`, `process`, `title`, `upload_begin`, `clipboard_read` | used | typing, sizing, status, directory, program, title, Send a file, a program's large copy |
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
| `terminal.program_status_changed` (`id`, `record`, `reason`) | used: Activity, tab dots, alerts. `reason` is always `report` (verified on preview.22 / api 19) and is not read; the record's `updated_at` gives Activity its ages |
| `terminal.program_status_removed` (`ids`, `reason`) | **was ignored, now used** (this change): Rex drops working and blocked records when the program exits or the shell prompts again (docs:program-status, "Lifetime"), and a `clear` removes records; the phone kept them, so a pane stayed "running" |
| `terminal.pwd_changed`, `terminal.process_changed`, `terminal.title_changed` | used |
| `terminal.child_exited` (`exit_code`, `error`) | ignored |
| `terminal.clipboard_written` (`text`, `id`, `bytes`) | used: an OSC 52 copy goes on the phone's clipboard, from the pane on screen while the app is in front (`PocketSession.copies`); when `text` is left out the copy is read with `clipboard_read` |
| `terminal.bell`, `terminal.desktop_notification` (`title`, `body`), `terminal.progress_report` (`state`, `progress`) | ignored |

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
record is done shows as done, everywhere the summary is shown (Activity, tab and pane dots, the eye's
filter). Whether a program is still busy - for the key bar's C-c and the running dots - is asked of
every record instead (`PocketState.busy`).

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
- **A program that fails.** Rex makes no status record of an exit code: `sh -c 'exit 3'` gives only
  `child_exited` (`exit_code: 3`, `error: "exit status 3"`), `process`'s `last_exit`, and on the
  stream an APC `{"type":"child_exited","exit_code":3}`. Activity's Failed group comes only from a
  program that reports `state=error`, which then outlives its exit. A shell pane's program exiting
  closes the pane (and its tab, if it was the last), unless it failed within 250 ms (`exit.
  quick_exit_threshold_ms`) or the block was made with `exit.on_completion: false`.
- **OSC 52.** `printf '\e]52;c;%s\a' $(printf hi | base64)` in a pane: every client that may see
  the pane gets `block_event` `clipboard_written` `{text: "hi", id, bytes: 2}` (at the app's API
  revision 7 too), and `clipboard_read` (`{}` args: the newest copy) answers `{id, data, total, eof:
  true}`.
- **`client.keymap`.** On a server with no configuration file: `bindings: []`, `diagnostics: []`,
  `config` (path, exists, generation, hash) and 22 `actions`, all `kind: block`, each with `name`,
  `description` and `args` (a JSON Schema); only `com.superlogical.terminal.clear` ("Clear Terminal")
  and `…reset` ("Reset Terminal") have a `title`, `category` (`Pane`) and `keywords` - the ones a
  palette would list. Custom `rex.action`s need an `init.lua`, which was not tried. Saved in
  `rex-protocol/client-keymap.json`.
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
  integration, a reset, or 64 records); a configuration file's own actions in `client.keymap`;
  prompts from actions; anything over Tailscale or TLS (the tests use the anonymous loopback listener).
- The rename dialog's text field on Robolectric: Compose never goes idle with it focused, so
  `RealRexAppTest` sends the rename as the dialog's Save does instead of typing into it.
