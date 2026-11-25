# Notifications

What RexPocket tells the person while it is away from the screen, how it decides, and what the
notifications let them do. Code: `AlertPlanner` (core, `Attention.kt`) decides, `Alerts.kt` (app)
posts, `Connections` (app, process-scoped) connects the two for every connected host, `NotificationSettings.kt`
is the page. Several hosts at once: [multi-host.md](multi-host.md).

## Sources

- Rex's program status (OSC 7501), `program_status_changed` / `program_status_removed`, and the
  Program Status Protocol's States and Security sections (docs:program-status in rex-parity.md).
- `terminal.bell` and `terminal.desktop_notification`, from `block_creator.list`
  (`rex-protocol/block-creators.json`), as a block event (`block_event` in a `session_event`).
- Real Rex 0.1.0-preview.22 (api 19), recorded in `RexShapeTest` (see "Real Rex" below).

## What makes a notification

Everything goes through **Background alerts** (background mode: the watch service that keeps the
connections up while the app is away, past a swipe from recents, a process Android killed, a reboot
and an update; multi-host.md, "Background mode"). With them off nothing is posted: without the
service Android cuts the connection, so there would be nothing to hear anyway. Nothing is posted while the app is on screen, either; the
screen already shows it (Activity, tab dots).

| Rex says | Kind | Default | Channel (importance) | Actions |
| --- | --- | --- | --- | --- |
| `blocked` `permission` from an agent in the key table | Waiting | on | Needs you (high) | Approve, Deny, Reply |
| `blocked` (`permission` from any other program, `question`, none) | Waiting | on | Needs you (high) | Reply, Interrupt |
| `blocked` `auth` | Waiting | on | Needs you (high) | Interrupt; never Reply |
| `error` | Failed | on | Failures (high) | Mute |
| `done` | Done | **on** | Finished (default) | Reply, Mute |
| `desktop_notification` (OSC 9, OSC 777) | Message | on | Program messages (default) | Reply; Interrupt while busy; Mute |
| `bell`, pane not on screen | Message | **off** | Program messages (default) | as above |
| `bell`, pane on screen | - | vibrate on | - | - |

Mute takes the slot the other actions leave (a notification shows three), so an approval prompt has
none; the tab's ⋮ menu always has it (below, "Muted tabs").

Tapping any of them opens exactly that pane (`openAlert`), also after Android killed and restarted
the process and after any number of later alerts. The activity is exported, so the tap's intent
names the pane (host, session, block) with an HMAC-SHA256 over them (`AlertSigner`, core) made with
a per-install key: 32 random bytes in `noBackupFilesDir`, never in a settings backup, the diagnostics
or a log (the app also has no Android backup at all). The first signed field names the kind of
link, so a host's link cannot be passed off as a pane's. An intent of the alert scheme whose
signature does not check - one from before a reinstall or a data clear, or anyone else's - opens
the home with "That alert has expired". An alert of a host removed since says "That host is no
longer saved" and its notifications go. Before, a tap carried a one-use token held in memory: a
restart or more than 64 alerts left it opening the home.

- **Failures have their own channel**, apart from Needs you: a person can keep waits loud and make
  failures quiet, or the other way round. The Failures channel is made once, from what the person
  set on Needs you, which failures went on before (`Alerts.failedChannel`): its importance (off stays
  off), sound, vibration, lights, badge and lock-screen visibility. Do Not Disturb's override is not
  the app's to set on a new channel, so it starts unset; a person who let Needs you through Do Not
  Disturb sets it once for Failures. A tailnet that stopped working, and Android stopping background
  mode, go on Failures too; a tailnet's sign-in stays on Needs you.

- **Done is on by default.** The protocol defines it as "finished a piece of work; the result is
  ready to look at and the user has not seen it yet", and Rex's own Activity ranks it above working.
  For an agent it is the end of a turn: the moment to look. It goes on its own default-importance
  channel, so a person who finds it noisy can quiet the channel or switch it off on the page.
- **A new report is news once.** The ledger stamps state, kind and `msg` (not the title, which
  agents animate). A report already seen while on screen is not raised later.
- **One notification per pane** (tag = host id and block id), whatever it is about. A program that reports
  `blocked` and also sends OSC 9 - Claude Code and others do - makes one alert: a status that
  follows a message within 10 s replaces it silently (it knows more and offers Reply); a message
  that follows a status within 10 s is dropped. Later, each is news of its own.
- **Rate limit.** A pane's messages alert (sound/vibration) at most once per 30 s; one sooner only
  updates the text, silently. A repeated bell within that time is dropped. Rex itself already joins
  bells in quick succession. (Program Status Protocol, Security: "Terminals SHOULD rate-limit
  anything a record causes outside the terminal".)
- **The bell.** On the pane on screen it is a 60 ms vibration with notification usage, so silent mode
  and the system's notification vibration setting apply, at most once a second. Off screen it is
  folded into the notification logic as a Message ("Rang the bell") but only when the person turns
  "A bell rings in a pane I am not looking at" on: shells ring it for a mistyped Tab, so on by
  default would be noise. An agent that uses the bell to ask for attention is better served by its
  OSC 9 or OSC 7501 option.
- **Plain text.** The title is the app's own words: the pane's program or label, and the tab. The
  program's text (`msg`, the notification's title and body) is the body, made plain by `plainText`:
  control characters out, direction overrides and invisible formatting gone, 300 characters at most.
  So a program cannot pose as another pane (Security: say which terminal it came from).

## The watch notification and the tiles: hosts and tailnets alike

The watch notification and the Quick Settings tiles treat a host and a tailnet the same way
(tailnet-control.md, "One model for hosts and tailnets"):

- **Lines.** With more than one item, one line each, the joined tailnet first, then each connected
  host: "Tailscale · connected", "mac · connecting…", in the state words the pages and tiles use.
  A tailnet alone (kept Always, no host) titles it "Tailscale connected", "No host connected · it
  stays up for a quick connect".
- **Actions, three at most.** Stop always comes first. Then one action per item, tailnets first
  ("Connect Tailscale" when it is off, "Disconnect Tailscale" otherwise; "Disconnect mac"), while
  every item fits in the two slots left; one item leaves a slot for **Manage**. With three or more
  items there is Manage alone beyond Stop, so no host or tailnet has an action another lacks: Manage
  opens Settings › Background and notifications, which has them all, as do the tiles. A tailnet
  that hosts are connected through does not go from the notification: its action opens the app on
  the confirm that names them.
- **A host that went stays, as Off.** A host that was connected while the watch ran and is not now
  (disconnected from the notification, the app or a tile) stays as an Off line with **Connect mac**,
  as a tailnet that is off does, until it connects again or is removed; the newest three are kept.
- **Tailnet trouble.** While away, a tailnet that needs a sign-in (on Needs you, "Tailscale needs a
  sign-in") or stopped working (on Failures) posts one notification, which opens the tailnet's page;
  it goes once that is over or the app is on screen. Its page's "Sign-in and failures" turns it off.
- **Stop has a way back.** The watch's Stop leaves a quiet note on the watch's channel, "Background
  mode is off", with **Turn on** (it starts the watch again, which connects the hosts kept in the
  background) and a tap that opens Settings › Background and notifications. It goes once background
  mode is on again, from anywhere.
- **Android stopped it.** When Android will not let the watch go to the foreground again (battery use
  restricted, Android 12 and later), a notification on Failures says so and opens Settings ›
  Background and notifications; the stop is also recorded, so the page shows a red row, "Android
  stopped background mode", with when and Fix (the battery exemption), even where notifications are
  not allowed and the notification could not show. The row goes once the watch runs again.
- **Background mode's own tile** (`BackgroundTile`), apart from the pool below: a tap turns
  background mode on or off, as the page's switch does (on, it connects the hosts kept in the
  background as a restart would); the tile is active while it is on, its subtitle "On · 2 hosts",
  "Off", or "Allow notifications" where Android does not allow them, in which case the tap opens the
  page, which asks. A long press opens the page.
- **Only with background mode.** The watch notification is the foreground service's, so it and its
  Stop, Connect and Disconnect buttons exist only while background mode is on. With it off, a host's
  and a tailnet's page say so under In the background, with **Turn on background mode** (the same
  switch, and on Android 13 and later the same permission question, as Settings'); a tile still
  connects, opening the app, which then holds the connection. With background mode on and Android
  hiding the notification (notifications not allowed - Android 13 and later run the service without
  the permission - or the Background connection channel switched off), the service still runs and
  the checklist says its notification and buttons are hidden, with Fix (below).
- **Tiles.** A pool of three (`RexTile1` to `RexTile3`): tile services are declared in the manifest,
  so there cannot be one per host. Each is bound to a host or a tailnet: the first tap of an
  unbound tile opens a picker, and a page's **Add a tile** binds the first free one (or the one it
  is on) and, on Android 13 and later, asks Android to add it (`requestAddTileService`). A tap
  connects what is off and disconnects the rest (a tailnet hosts use asks first, in the app); the
  label is its name, the subtitle its state; a long press opens its page
  (`QS_TILE_PREFERENCES`). Connecting from a tile starts the watch when background mode is on, and
  opens the app when it is off, since the app is then what keeps the connection.
  Why a pool and not one tile with a picker: a tap should act, not ask; three cover the tailnet and
  the two hosts a person most uses, and a tile whose item was removed says so and asks again.
- **Changing a tile.** A page on a tile shows **On tile N** (Change…: the picker for that tile) and
  **Remove from tile**; a page on none has Add a tile, and when all three are taken, **Replace tile
  N (what it shows)** for each. The picker lists the three tiles with their items, so any can be
  chosen and changed, marks the item on the chosen one, says when an item moves from another tile
  (an item is on one tile at most), and has **Clear tile**. Settings › Background and notifications
  › **Quick Settings tiles** lists the three, each with Change… and Clear. Every change asks Android
  to redraw the tiles it touched (`TileStore.assign`, `requestListeningState`). The same words for a
  host and a tailnet.

## Clearing

A notification goes when its record goes (`program_status_removed`, a clear, the pane closing), when
the record turns into a state that is not news (working, idle), when its kind is switched off, when
the person opens the pane, or when a Reply, Interrupt, Approve or Deny from it was taken by Rex. A
message stays until the pane is opened, since nothing says it is over.

An action that went through does not just vanish: the pane's notification becomes a quiet note of
what happened ("Approved", "Denied", "Interrupted", "Sent: yes", "Tab muted…") with no actions, which
Android takes away after 4 s (`setTimeoutAfter`, `Alerts.confirm`). Replacing the notification is
also what stops Reply's spinner. The pane going back to work, as it does once answered, leaves the
note to go by itself.

## Grouping

Each notification is in the group of its host (`host/hostId`), whatever its session: with many
hosts the shade shows one entry per host, which opens to its panes. The group's summary is silent,
titled by the host, says how many programs have something ("3 programs"), lists the latest six
(`InboxStyle`, pane and tab: text), and goes with the last one. Its tap opens the home on Activity,
where that host's programs are listed (a signed link, as a pane's). The channels stay one per kind for
the whole app, not per host: Android's channel pages are where sound and Do Not Disturb are set,
and a set of four per host would grow with every host saved and never shrink (a deleted channel
still counts). What differs per host is chosen in the app, on the host's page (below).

## Reply and Interrupt

`AlertActionReceiver` (not exported; explicit PendingIntents, mutable only for Reply's RemoteInput)
hands the action to `Connections`, which holds the connections (`Alerts.actions`), and it goes on the
connection of the host the notification came from. It waits up to 6 s
for a redialing connection, then:

- **Reply**: the text is pasted into the pane (`block.event` `paste`, as the key bar's Paste: the
  terminal brackets it when the program asked for that), then Enter (`client.key` `enter`).
- **Interrupt**: Ctrl+C (`client.key` `key_c` with `ctrl`).
- **Approve** and **Deny**: below.

Both name the pane, which need not be the one on screen or even in the attached session: a
bootstrapped connection may send keys to any session's panes (verified on preview.22). Rex answers
`client.key` with `{handled: false}` when no Lua binding took the key, which is the normal case.
On success the notification says so for a moment (Clearing, above); otherwise it comes back saying
"Not sent", with a sound this once, as the person is waiting to hear. No Reply for
`auth`: a password does not belong in a notification's reply history or on a lock screen. The connections
are the process's, not the activity's, so a Reply still goes after the activity was destroyed; once
the process is gone there is none, and the action says Not sent. A process Android started again has
no record of the notification an action came from, so each action's intent carries the alert's
title, host and kind, and Not sent, Prompt changed and the confirmation are made from those: the
spinner always stops.

Reply offers the person's quick replies (below) as its choices (`RemoteInput.setChoices`): the
first five that are one line of at most 40 characters, since Android sends a choice's text as shown.
A choice is sent as typed text is.

## Approve and Deny

A program that reports `blocked` with `kind=permission` is waiting for "approval to do something"
(Program Status Protocol, Keys). Rex says nothing about how to give it: the keys are the program's.
So the app answers only the agents whose prompts it knows, by the record's `app` (`effective_app`,
matched by its first word, so `claude`, `claude-code` and `Claude Code` agree), and offers Approve
and Deny only for those (`ApprovalKeys`, core `Approvals.kt`):

| Agent (`app`) | Approve | Deny | What the keys answer |
| --- | --- | --- | --- |
| Claude Code (`claude`) | Return | Esc | Its permission prompt is a menu, "1. Yes" first and selected; Return takes it. Its last option is "No, and tell Claude what to do differently (esc)". |
| Codex (`codex`) | `y` | Esc | Its approval prompt names a key per answer: "Yes, proceed (y)", "No, and tell Codex what to do differently (esc)". |
| omp (`omp`), tool approval | Return | Esc | The dialog opens with Approve (Approve Once beside grants) selected; Return (`tui.select.confirm`) takes it once 500 ms have passed (`APPROVE_ARM_MS`), Esc (`tui.select.cancel`) is Deny. Its own approve chord, Ctrl+Enter or Ctrl+Q (`app.approval.approve`), also approves once, but a confirm does not take it, so the phone sends Return. |
| omp (`omp`), confirm | Return | Esc | `showHookConfirm` (Yes, No) and the settings-change prompt (Approve once selected): Return takes the selected, which is yes; Esc cancels, which omp counts as no. |
| omp (`omp`), plan review | - | - | Not answered: `msg` "Review plan: <title>". Return moves between the plan, its contents and the buttons, and Esc only closes the review; it keeps Reply and Interrupt. |

**omp's sources** (can1357/oh-my-pi at 6b96042, read 2026-11-24), under
`packages/coding-agent/src/`: `utils/run-status.ts` (`holdBlockedStatus`: the newest open dialog is
the record, `app=omp`), `modes/controllers/extension-ui-controller.ts` (`showCollabAwareApproval`
reports `permission` with the compact prompt as `msg`; `showHookConfirm` and `showHookSelector` with
`kind: "permission"` report the title), `ui/overlays/panels/approval-dialog.tsx` (the tool
approval's keys), `ui/overlays/pickers/hook-selector.tsx` (a confirm's keys),
`modes/interactive-mode.ts` (the plan review's "Review plan: " record, `#promptCfgChange` with
`initialIndex: 1`), `ui/overlays/panels/plan-review-model.ts` (the plan review's keys) and
`config/keybindings/tui-keybindings.ts`, `app-keybindings.ts` (the defaults: Return, Esc or Ctrl+C,
Ctrl+Enter or Ctrl+Q). A person who rebound those keys in omp's keybindings gets the defaults from
the phone. One case answers differently: with text typed in omp's composer under the tool
approval, Return sends that text, which denies the call with it as the reason; the phone cannot see
the draft. The records and keys are checked in `ApprovalsTest`, `PromptAnswerTest` (ScriptedRex and
real Rex) and the `omp` replay (`answers` lines, AgentReplayTest).

**Sources.** The agents' own prompts, as they show them, not Rex: Rex's docs (llms-full.txt, read
2026-11-23) name Claude Code, Codex, OpenCode and Pi as the agents `rex skill setup` knows, and
Program Status's `app` examples (`claude-code`, `terraform`), but no keys. OpenCode, Pi (omp's upstream), Gemini and
others are left out until their prompts are checked; a program not in the table (Terraform's
"Apply?", a script's own question) keeps Reply and Interrupt, as before. Which keys a given agent
version takes is **unconfirmed** on a real phone with a real agent (see the report).

- **Only the prompt the person saw.** The notification (and the Needs you row) carries the prompt's
  stamp (`promptStamp`: the record's id, state, kind, app, message and `updated_at`). Before typing,
  `PocketSession.answerPrompt` reads the pane's records again from Rex (`program_status`) and sends
  the keys only when the pane's record is still that prompt. A program that asked again, even the
  same question, has a new `updated_at`. Anything else - another prompt, a program that went back to
  work, a record gone - sends nothing: the notification comes back quietly saying "Prompt changed,
  nothing was sent. Open the pane to see what it asks now.", without Approve and Deny, its tap
  opening the pane (Android 12 and later let no notification action start an activity, so the
  person taps the notification); in Needs you a toast says so and the pane opens.
- **Unlocked.** Approve and Deny are `setAuthenticationRequired(true)`: from the lock screen,
  Android 12 and later ask for the device unlock first. An approval runs something on the computer.
  Reply needs no unlock, as before.
- **Never for `auth`.** A login is typed into the pane; `question` wants words (Reply).
- **Three actions.** A notification shows three, so Approve and Deny take Interrupt's place: Approve,
  Deny, Reply. Deny is what stops the agent's action.

## Quick replies

Settings › Terminal › Quick replies: a list the person edits - add, edit (a page in the sheet, so
the keyboard stays with its window), Move up and Move down (buttons and TalkBack actions), delete -
starting as `yes`, `continue`, `/compact`. At most 20, each up to 2000 characters, kept as typed
(`QuickReplies`, core; `QuickReplyStore`, app, one JSON array in the app's settings). They are:

- the choices of a notification's Reply (above);
- a key on the bar, **Quick replies** (Arrange keys › Add keys; not on the bar by default), which
  pins a panel of them above the bar as a group's key does; a tap sends one, pasted then Return,
  and puts the panel away;
- rows of the command palette (group Quick reply), sent the same way;
- chips in the composer and in Needs you's Reply, which put the text in the field to change first.

A delete says so at the top of the list with **Undo**, which puts the list back as it was; once any
of the app's own three is gone the list ends with **Restore defaults**, which adds them back at the
end. The list is one tap from where replies are used in the terminal: **Edit quick replies** at the
foot of the key bar's panel (so an empty panel is not a dead end), under the composer's chips, and
in the palette.

## The prompt composer

A multi-line sheet for a longer prompt, from the terminal (the bar's **Compose prompt** key, which
Arrange keys can add, the ⋮ menu's Compose prompt and the palette's) and from a Needs you row's Reply
(its Compose, which carries what was typed). Send pastes the whole text into the pane in one
`block.event` `paste` - so the terminal brackets it when the program turned bracketed paste on, and
an agent takes the lines as one prompt - then Return, unless **Press Return after it** is switched
off. The draft is kept per host and pane (`PromptDrafts`, the view model's) while the app runs:
leaving the composer or the pane loses nothing. From the terminal the draft goes only once Rex has
taken the prompt, so a send that fails keeps it; from Needs you, Send clears it. **Clear** can be
taken back: it becomes **Restore** until something is typed. Press Return after it is the person's
choice per host, kept (`composer_enter_after_<host>` in the `terminal` preferences), rather than on
again at every opening. The pane it goes to is fixed when it
opens. Rex's own Compose Input… (`client.composer.open`) opens the desk's composer; this one is the
phone's and sends the same paste.

## Permission and settings

Android 13+ asks for `POST_NOTIFICATIONS` when the person turns Background alerts on - the one moment
the question explains itself. Refused, or refused before for good (Android then no longer asks), or
with notifications off for the app: a toast says where and Android's notification page for the app
opens, rather than a message with nowhere to go. Settings › Background and notifications (a chevron
row in the Connection group, and what Manage, the stopped notes and the background tile's long
press open) shows:

- **Background alerts**, with what it does.
- **Let it run in the background**, a checklist (`BackgroundChecks`), each row saying it is fine or
  **Fix**, which opens the place to fix it:
  - *Android stopped background mode* (red, only after it happened): when, and Fix (above, "Android
    stopped it").
  - *Background notification*: with background mode off, that there is no notification and no
    buttons, and **Turn on**; with it on and the notification hidden (`Alerts.watchHidden`: not
    allowed, or its channel off), that the service runs with its notification and buttons hidden,
    and Fix, which opens the app's notification page or the channel's; otherwise Yes.
  - *Allowed by Android*: notifications on (and permitted on 13+); the app's notification page.
  - *Battery use*: Unrestricted when exempt from battery optimization
    (`PowerManager.isIgnoringBatteryOptimizations`). Fix asks with
    `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the system's Allow dialog, the
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission), falling back to the optimization list and the
    app's page. The app comes from GitHub releases, not Google Play, whose policy limits that
    permission; ntfy's builds ask the same way. The exemption is what lets Android restart the watch
    after killing it (Android 12+), and keeps Doze from pausing the network.
  - *Background activity* (Android 9+): `ActivityManager.isBackgroundRestricted`, battery use set to
    Restricted, which stops the app as soon as it leaves the screen; the app's page.
  - *Background data*: Data Saver restricting the app (`restrictBackgroundStatus`); its data page.
  - *If your phone still stops it*: dontkillmyapp.com's page for the phone's maker, for the
    makers' own killers (Xiaomi, Huawei, Samsung's sleeping apps and so on).
- **Hosts**: each saved host, with what it does in the background, opening its page: Stay connected
  in the background, Always connected, Notifications from this host (off silences it), and the five
  switches below for this host, each saying "(default)" while it follows the page's, until set
  here; Use the defaults forgets the host's own (`HostBackground`, kept by `BackgroundStore`). With
  background mode off the page says so above them, "Background alerts are off", with **Turn on**:
  the switches are kept but say nothing until it is on. **Unmute every tab** when some are muted.
  The host's row here, and on the home (a crossed-out bell beside its name, read as "Notifications
  off"), says when its notifications are off and how many tabs are muted.
- **Tell me when**: a switch per kind, every host's default.
- **Quick Settings tiles**: the three, each with Change… and Clear (above).
- Vibrate on a bell, and each channel's Android page (sound, vibration, lock screen, Do Not
  Disturb): Needs you, Failures, Finished, Program messages and Progress, marked "Off in Android"
  when the person switched it off there.
- **Reset to defaults**, last, after a confirm: the kinds of alert, Vibrate and Progress bars as
  the app comes (`AlertPrefs()`). Background mode, each host's and tailnet's page and Android's own
  settings are not this page's to reset. Greyed out while they are the defaults already.

The page reads Android's side again whenever the app comes back.

## Muted tabs

A tab's ⋮ menu has **Mute this tab** (then **Unmute this tab**, saying its programs send no
notifications), and an alert has **Mute** where a slot is free. A muted tab's panes post no alert
and no progress bar, and what they had up goes. Mutes are the host's (`HostBackground.muted`, by
Rex's window id, the newest 100), kept with its other background choices, in the settings backup
(`background.hosts[].mutedTabs`), counted on the host's row, and undone one by one from the tab or
all at once from the host's page. A whole host is silenced on its page, as before.

## Widget

A home screen widget (`HomeWidget`, Glance) lists each connected host with how many tabs are
blocked, failed (only when there are some), done and not yet seen, and working, as the Activity view
it opens counts and words them (`activitySections`: each tab once, by its most pressing report;
blocked, failed and done add up to the home's Needs you), four hosts at most. A tap opens the home on Activity. Only a
live connection hears a host's programs, so the counts are as fresh as the background watch keeps
them: it writes them as they change, at most every 3 s, and the widget says "Updated 14:05". Without
the watch (background mode off, or stopped) they are the ones from when the app last left the
screen, and it says "Background off · counts may be stale · 14:05". Nothing redraws it on a timer.

## Real Rex (preview.22 / api 19)

Recorded in `RexShapeTest` (`rex-shapes/preview.22.txt`), run against real Rex in `rex-real`:

- `printf '\a'` gives `block_event` `bell` with payload `{}`.
- OSC 9 (`ESC ] 9 ; body BEL`) gives `desktop_notification` `{title: "", body}`; OSC 777
  (`ESC ] 777 ; notify ; title ; body BEL`) gives `{title, body}`. Both reach every client that may
  see the pane, from any session after a bootstrap with `event_scope: all`.
- `block.event` paste answers `{}`, `client.key` answers `{handled: false}`.
- `PaneSignalsTest` (core) runs on ScriptedRex and real Rex: the bell and both notification forms
  from a pane not on screen, a reply that the shell runs, Ctrl+C that stops `sleep 60`, and a reply
  to a closed pane that reports false.

## Progress bars

A program's OSC 9;4 progress bar, which Rex forwards as `progress_report`, is drawn on the terminal
and the home (terminal-controls.md, "Progress"). Behind a switch of its own (Progress › Progress
bars while away, off by default, `alerts.progress`, in the settings backup as `progress`), it is
also a notification while the app is away and background alerts are on: one per tab with a bar, on
the low-importance Progress channel, silent, with Android's progress bar (indeterminate for work
with no percent), the tab's name, the host as subtext; changed at most once a second, gone with the
bar or when the app comes back, none for a host whose notifications are off. It is apart from the
pane's alert: a bar is news of work under way, not a call for the person.

## Tests
- Progress: `NotificationsTest.aProgressBarWhileAwayIsANotificationForItsTabWhenAskedFor`
  (channel, percent, indeterminate, gone with the bar and when back on screen) and
  `progressBarsMakeNoNotificationByDefault`.

- `AlertPlannerTest`, `AttentionLedgerTest` (core): every decision above.
- `PaneSignalsTest` (core): ScriptedRex and real Rex.
- `NotificationsTest` (app, Robolectric, ShadowNotificationManager, ScriptedRex): channels, text,
  actions, Reply and Interrupt reaching the pane, the fold, done off, nothing while on screen, the
  bell's vibration (ShadowVibrator) and its opt-in notification.
- `NotificationsTest.aHostsOwnChoicesDecideItsNotifications`: a host's own switch wins, the rest
  follow the default, a silenced host posts nothing.
- Approve and Deny: `ApprovalsTest` (core: the table, first-word matching, never for `auth` or a
  question, what makes a prompt new), `PromptAnswerTest` (core, ScriptedRex and real Rex: the keys
  reach the program - on real Rex a shell reports the prompt with OSC 7501 and reads the key -
  an older prompt's stamp sends nothing, a gone pane says so; on ScriptedRex a prompt that changed
  without the phone hearing, `report(..., quiet = true)`), `NotificationsTest` (the three actions,
  unlock required, Approve's Return for Claude, Deny's Esc for Codex, Prompt changed, no Approve for
  Terraform, a question or a login).
- Quick replies: `QuickRepliesTest` (core), `QuickRepliesScreenTest` (shared: the page),
  `NotificationsTest.replyOffersTheQuickRepliesAsSetNow`, `TermAgentTest` (shared: the bar's key and
  panel, the palette).
- The composer: `TermAgentTest` (the Compose key and the ⋮ menu, a chip into the text, the draft
  kept for the pane when the screen is made again, Return off, Send), `ActivityViewTest.
  replysComposeSendsALongerPromptInOnePaste` (emulator: Reply's Compose, one paste and Return on
  ScriptedRex); screenshots `quick-replies-*`, `composer-*`, `activity-approval-*`.
- `BackgroundModeTest` (JVM) and `BackgroundServiceTest` (emulator): background mode without a
  screen; see multi-host.md, "Tests".
- `TilesScreenTest` (shared): a page's On tile N and Remove from tile, Replace with all three
  taken, the picker rebinding (moving an item from another tile) and clearing, Settings' three
  tiles, and the background-mode line and its Turn on; `WatchHiddenTest` (JVM): the checklist's
  hidden notification without the permission and with the channel off, each Fix's page.
- `LaunchTest.notificationsHasASwitchForEachKindThatTheStoreKeeps` (shared: JVM and emulator: also
  the Failures and Progress channel rows) and the `notifications-*` screenshots.
- Alert links and actions: `AttentionLedgerTest` (core: a signature opens exactly its target, after
  a restart with the same key, never with another), `NotificationsTest` (a tap's link after the key
  is read again, a forged or old link expired, the summary's host link, a removed host, a Reply the
  app has no record of saying Not sent, the confirmations, Mute, the Failures channel made from
  Needs you).
- `BackgroundModeTest`: Stop's note and its Turn on, Android's stop recorded until the watch runs, a
  host that went kept as Off with Connect, the widget's counts written by the watch;
  `HomeWidgetTest` (the stored counts, the footer, the background tile's words).

## Left out

- `progress_report` (OSC 9;4) as a progress bar in the notification: Rex does not map it to the
  root record; programs that report status give `progress` already, which Activity shows.
- Approve and Deny for programs not in the key table: what an agent takes as yes differs (a menu with
  Enter, `y`, `1`), so a wrong guess would answer the wrong thing. Reply lets the person type it.
- Notifications with the app's process dead and not restarted (force stop, or a maker's killer that
  also blocks restarts): Rex has no push; Background alerts are the way, and the checklist says what
  to allow.
- A per-session mute: a host's mute is on its page, a tab's in its ⋮ menu. Rex shares no seen state; a done or failed
  pane a desk app looked at is inferred seen and its notification goes (home.md, "Seen").
