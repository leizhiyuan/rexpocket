# Notifications

What RexPocket tells the person while it is away from the screen, how it decides, and what the
notifications let them do. Code: `AlertPlanner` (core, `Attention.kt`) decides, `Alerts.kt` (app)
posts, `RexViewModel.raiseAlerts`/`onSignal` connect the two, `NotificationSettings.kt` is the page.

## Sources

- Rex's program status (OSC 7501), `program_status_changed` / `program_status_removed`, and the
  Program Status Protocol's States and Security sections (docs:program-status in rex-parity.md).
- `terminal.bell` and `terminal.desktop_notification`, from `block_creator.list`
  (`rex-protocol/block-creators.json`), as a block event (`block_event` in a `session_event`).
- Real Rex 0.1.0-preview.22 (api 19), recorded in `RexShapeTest` (see "Real Rex" below).

## What makes a notification

Everything goes through **Background alerts** (the watch service that keeps the connection up while
the app is away). With them off nothing is posted: without the service Android cuts the connection,
so there would be nothing to hear anyway. Nothing is posted while the app is on screen, either; the
screen already shows it (Activity, tab dots).

| Rex says | Kind | Default | Channel (importance) | Actions |
| --- | --- | --- | --- | --- |
| `blocked` (`permission`, `question`, none) | Waiting | on | Needs you (high) | Reply, Interrupt |
| `blocked` `auth` | Waiting | on | Needs you (high) | Interrupt; never Reply |
| `error` | Failed | on | Needs you (high) | none |
| `done` | Done | **on** | Finished (default) | Reply |
| `desktop_notification` (OSC 9, OSC 777) | Message | on | Program messages (default) | Reply; Interrupt while busy |
| `bell`, pane not on screen | Message | **off** | Program messages (default) | as above |
| `bell`, pane on screen | - | vibrate on | - | - |

Tapping any of them opens exactly that pane (`openAlert`, a one-use token per notification).

- **Done is on by default.** The protocol defines it as "finished a piece of work; the result is
  ready to look at and the user has not seen it yet", and Rex's own Activity ranks it above working.
  For an agent it is the end of a turn: the moment to look. It goes on its own default-importance
  channel, so a person who finds it noisy can quiet the channel or switch it off on the page.
- **A new report is news once.** The ledger stamps state, kind and `msg` (not the title, which
  agents animate). A report already seen while on screen is not raised later.
- **One notification per pane** (tag = block id), whatever it is about. A program that reports
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

## Clearing

A notification goes when its record goes (`program_status_removed`, a clear, the pane closing), when
the record turns into a state that is not news (working, idle), when its kind is switched off, when
the person opens the pane, or when a Reply or Interrupt from it was taken by Rex. A message stays
until the pane is opened, since nothing says it is over.

## Grouping

Each notification is in the group of its host and session (`hostId/sessionId`), with a silent
summary titled by the session's label and the host as subtext; the summary goes with the last one.

## Reply and Interrupt

`AlertActionReceiver` (not exported; explicit PendingIntents, mutable only for Reply's RemoteInput)
hands the action to the view model that holds the connection (`Alerts.actions`). It waits up to 6 s
for a redialing connection, then:

- **Reply**: the text is pasted into the pane (`block.event` `paste`, as the key bar's Paste: the
  terminal brackets it when the program asked for that), then Enter (`client.key` `enter`).
- **Interrupt**: Ctrl+C (`client.key` `key_c` with `ctrl`).

Both name the pane, which need not be the one on screen or even in the attached session: a
bootstrapped connection may send keys to any session's panes (verified on preview.22). Rex answers
`client.key` with `{handled: false}` when no Lua binding took the key, which is the normal case.
On success the notification goes; otherwise it comes back, silently, saying "Not sent". No Reply for
`auth`: a password does not belong in a notification's reply history or on a lock screen. If Android
destroyed the activity's view model while the process lives on, there is no connection to send on,
and the action says Not sent.

## Permission and settings

Android 13+ asks for `POST_NOTIFICATIONS` when the person turns Background alerts on - the one moment
the question explains itself. Settings › Notifications (a chevron row in the Connection group) shows:
Background alerts (with what it does), **Allowed by Android** (Yes/No, opens the app's notification
page), a switch per kind, Vibrate on a bell, and each channel's Android page (sound, vibration, lock
screen, Do Not Disturb), marked "Off in Android" when the person switched it off there. The page reads
both again whenever the app comes back.

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

## Tests

- `AlertPlannerTest`, `AttentionLedgerTest` (core): every decision above.
- `PaneSignalsTest` (core): ScriptedRex and real Rex.
- `NotificationsTest` (app, Robolectric, ShadowNotificationManager, ScriptedRex): channels, text,
  actions, Reply and Interrupt reaching the pane, the fold, done off, nothing while on screen, the
  bell's vibration (ShadowVibrator) and its opt-in notification.
- `LaunchTest.notificationsHasASwitchForEachKindThatTheStoreKeeps` (shared: JVM and emulator) and the
  `notifications-*` screenshots.

## Left out

- `progress_report` (OSC 9;4) as a progress bar in the notification: Rex does not map it to the
  root record; programs that report status give `progress` already, which Activity shows.
- Quick answers such as "Yes"/"No" for `permission`: what an agent takes as yes differs (a menu with
  Enter, `y`, `1`), so a wrong guess would answer the wrong thing. Reply lets the person type it.
- Notifications with the app's process dead: Rex has no push; Background alerts are the way.
- A per-host or per-session mute, and a seen state shared with the desktop (Rex shares none).
