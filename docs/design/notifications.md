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

## Clearing

A notification goes when its record goes (`program_status_removed`, a clear, the pane closing), when
the record turns into a state that is not news (working, idle), when its kind is switched off, when
the person opens the pane, or when a Reply or Interrupt from it was taken by Rex. A message stays
until the pane is opened, since nothing says it is over.

## Grouping

Each notification is in the group of its host (`host/hostId`), whatever its session: with many
hosts the shade shows one entry per host, which opens to its panes. The group's summary is silent,
titled by the host, says how many programs have something ("3 programs"), lists the latest six
(`InboxStyle`, pane and tab: text), and goes with the last one. The channels stay one per kind for
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

Both name the pane, which need not be the one on screen or even in the attached session: a
bootstrapped connection may send keys to any session's panes (verified on preview.22). Rex answers
`client.key` with `{handled: false}` when no Lua binding took the key, which is the normal case.
On success the notification goes; otherwise it comes back, silently, saying "Not sent". No Reply for
`auth`: a password does not belong in a notification's reply history or on a lock screen. The connections
are the process's, not the activity's, so a Reply still goes after the activity was destroyed; once
the process is gone there is none, and the action says Not sent.

## Permission and settings

Android 13+ asks for `POST_NOTIFICATIONS` when the person turns Background alerts on - the one moment
the question explains itself. Settings › Background and notifications (a chevron row in the
Connection group) shows:

- **Background alerts**, with what it does.
- **Let it run in the background**, a checklist (`BackgroundChecks`), each row saying it is fine or
  **Fix**, which opens the place to fix it:
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
  here; Use the defaults forgets the host's own (`HostBackground`, kept by `BackgroundStore`).
- **Tell me when**: a switch per kind, every host's default.
- Vibrate on a bell, and each channel's Android page (sound, vibration, lock screen, Do Not
  Disturb), marked "Off in Android" when the person switched it off there.

The page reads Android's side again whenever the app comes back.

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
- `NotificationsTest.aHostsOwnChoicesDecideItsNotifications`: a host's own switch wins, the rest
  follow the default, a silenced host posts nothing.
- `BackgroundModeTest` (JVM) and `BackgroundServiceTest` (emulator): background mode without a
  screen; see multi-host.md, "Tests".
- `LaunchTest.notificationsHasASwitchForEachKindThatTheStoreKeeps` (shared: JVM and emulator) and the
  `notifications-*` screenshots.

## Left out

- `progress_report` (OSC 9;4) as a progress bar in the notification: Rex does not map it to the
  root record; programs that report status give `progress` already, which Activity shows.
- Quick answers such as "Yes"/"No" for `permission`: what an agent takes as yes differs (a menu with
  Enter, `y`, `1`), so a wrong guess would answer the wrong thing. Reply lets the person type it.
- Notifications with the app's process dead and not restarted (force stop, or a maker's killer that
  also blocks restarts): Rex has no push; Background alerts are the way, and the checklist says what
  to allow.
- A per-session mute, and a seen state shared with the desktop (Rex shares none). A host's mute is
  on its page.
