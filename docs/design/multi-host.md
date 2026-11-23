# Several hosts at once

The first step towards using RexPocket with a fleet of agents: every saved Rex host can be connected
at the same time, each on its own connection, and the home, Activity and Background alerts cover
all of them. The home keeps its layout; a redesign comes next, built on this.

## Model

- **`Fleet`** (core, `Fleet.kt`) holds one `PocketSession` per connected host, by host id. Each has
  its own control connection, reconnect and backoff, session list, views, statuses and alert ledger
  (`AlertPlanner`); nothing one does reaches another. On screen or not, and Background alerts on or
  off, apply to every member and to one added later. Its bells and notifications come out tagged
  with their host (`HostSignal`), its status changes as alert moves per host (`alertMoves`), and
  `reply`/`interrupt` go to the host they name.
- **`Connections`** (app, `ui/Connections.kt`) is process-scoped: one per process
  (`Connections.of(app)`), not the screen's. It owns the fleet, the tailnet nodes, each host's
  `Link` (the steps before Rex answers: the tailnet node, the SSH tunnel; the connect progress and
  the tailnet path), the store of what was connected, the network watcher, and everything Background
  alerts do: posting, settling, Reply and Interrupt (`Alerts.actions`), the watch notification.
- **`RexViewModel`** only watches it. It keeps what belongs to the screen: the route, the **focused
  host** (the one the terminal shows, or showed last), the session being opened, the resume after a
  restart, uploads, the share sheet. `pocket` is the focused host's state; `lives` is every
  connected host's (`HostLive`: state, connect steps, path) for the home.
- Persisted state stays keyed by host id and session id, as before: folds (`hostId/sessionId`),
  cached session lists, the last session and pane per host. New: `connected_hosts`, the hosts
  connected and not disconnected, in order. Notification tags are `hostId/blockId`, so two hosts
  (the same Rex saved twice, say) never settle each other's alerts.

## Lifecycle

- **Connect** is explicit: the Connect button on a host that is not connected, or Connect in its ⋮
  sheet. Opening something on a host (a session, a tab, an activity row, an alert, a share) connects
  it if needed. A host's row tap folds it in any state; it never connects.
- **Disconnect** is in the host's ⋮ sheet. Only that host goes; the others keep their connection
  (same client, no new hello). It stays disconnected at the next start.
- **Start**: the hosts in `connected_hosts` connect again, the one open last first and focused (it
  is the one the resume after Android killed the app goes back to), then the hosts set to Always
  connected (see "Background mode"). An app from before this had
  only the last host; that stands in for the list until one is written, so a single-host user sees
  what they saw.
- **Focus** moves to a host when a session or tab opened on it is shown, not at the tap: the pane on
  screen stays until the new one is there. Connecting a host from the list while another host's
  terminal is open does not take the terminal away.
- **Home**: a connected host shows its live sessions and status as before; one that is not shows one
  line, "Not connected · N sessions" (the count it had last time), with Connect under it. The eye
  lists every connected host's activity, each under its name when there are several.

## Background

Background alerts work as notifications.md says, for every connected host: the watch notification
names them all ("Watching mac, linux for programs that need you"), each notification has its host
as its subtext, and Reply and Interrupt are sent on the connection of the host they came from. While
the app is away every connection pauses its screen polls; with alerts on, each keeps its panes'
status current (the status reader reads a pane once, then Rex's events keep it), as the single
connection did. A network change redials every host at once.

### Background mode (alerts that outlive the app)

Background alerts are a background mode, as ntfy's is: on, the connections live as long as the
person wants them, not as long as the screen. Code: `WatchService` (the anchor), `BootReceiver`,
`BackgroundChecks` (the checklist), `Background.kt` in core (the choices, pure and unit-tested),
`BackgroundStore` (where each host's choices are kept).

- **The anchor.** `WatchService` is a `specialUse` foreground service that holds the process and so
  the process's `Connections`. It runs while background mode is on and a host is connected;
  `Connections` starts it (only while the app is on screen, or from the service itself) and stops it
  with the last host or when the mode goes off. It returns `START_STICKY` and the manifest has
  `stopWithTask="false"`: a swipe from recents removes the task and destroys the activity (and the
  view model), and `onTaskRemoved` keeps the watch while the mode is on; the connections stay, as
  they are not the activity's. With the mode off the swipe stops it.
- **Rebuilt after Android kills the process.** Android starts a sticky service again in a new
  process with a null intent. The service calls `Connections.of(app)`, which reads everything from
  the store (the mode is read there too: `Connections` writes it, so the watch's Stop is kept with no
  screen), and `restoreBackground()` connects `BackgroundHosts.inBackground(...)`: the hosts in
  `connected_hosts` that are kept in the background, and the always-connected ones. With no screen
  seen yet the process is away (no screen polls, news becomes notifications). When nothing is left
  to connect the service stops and says START_NOT_STICKY.
- **Boot and update.** `BootReceiver` takes `BOOT_COMPLETED` (and `QUICKBOOT_POWERON`) and
  `MY_PACKAGE_REPLACED`, and starts the watch with `ACTION_RESTORE` when the mode was on: the same
  restore. Both are on Android 12's list of moments an app may start a foreground service from the
  background, and Android 15's boot ban covers other types (`dataSync`, `camera`, media, phone,
  microphone), not `specialUse`. The receiver is not direct-boot aware: the hosts, their SSH keys
  (Keystore-wrapped) and the setting are in credential-encrypted storage, unreadable before the
  first unlock, so it waits for `BOOT_COMPLETED`, which comes after it; `LOCKED_BOOT_COMPLETED` is
  not taken (it would find nothing to read), and a locked user is checked anyway.
- **Restarts Android may refuse.** A sticky restart is not on Android 12's list; an app exempt from
  battery optimization is, so Settings asks for that exemption. Without it a refused
  `startForeground` is caught: the watch posts "Background alerts stopped" (open the app, see the
  checklist) instead of crashing or going quietly.
- **Which hosts.** Each host has its page in Settings › Background and notifications
  (`HostBackground`): **Stay connected in the background** (default on: every connected host stays),
  off lets that host go a minute after the app leaves the screen (`PARK_MS`; never the host whose
  terminal was left) and connects it again when the screen comes back; it stays in
  `connected_hosts` meanwhile. **Always connected** connects the host at every launch and every
  restart, even after a Disconnect, and keeps it. At launch the app connects `atLaunch(...)`: the
  last host first, then `connected_hosts`, then the always-connected.
- **Network and battery.** `NetworkWatcher` (a default-network callback) redials every host at once
  when the network changes or comes back. Away from the screen a host that stays down is retried
  with the same backoff, but capped at a minute instead of 8 s (`MAX_AWAY_BACKOFF_MS`); the screen
  coming back, a network change and a notification's Reply skip the wait. No wake locks and no
  alarms: OkHttp's 30 s ping keeps a live socket honest, and Doze lets a foreground service's network
  through (more so once exempt).
- **The watch notification** says how many hosts are connected and names them (with each one's
  state when there are several), opens the app, and has Stop and, for one or two hosts, Disconnect
  for each (a notification has room for three actions). Stop with no screen left turns the mode off
  and closes the connections (`connected_hosts` kept for the next start).

## Limits

- One terminal at a time: the focused host's. The others are live on the home, not on screen, so
  the phone takes no pane on them and changes no desktop's size.
- Each connected host is one control connection and its status events; a pane stream only for the
  pane on screen. Hosts the person does not use can be disconnected.
- One tailnet node serves every tailnet host, as before.
- A host saved twice (LAN and tailnet address of the same Rex) is two connections to one Rex, and
  its alerts come twice, once per host.
- Seen state is not shared with the desktop: Rex has none, and does not say what other clients show
  (rex-parity.md, "What other clients show").

## Tests

- `FleetTest` (core, two ScriptedRex): each host connects and disconnects on its own, both raise
  alerts and keep their own ledger, signals carry their host, a reply reaches the host it names, one
  host reconnecting leaves the other's connection alone.
- `MultiHostTest` (app, shared: JVM and emulator): two hosts live on the home, a tab on one leaves
  the other connected; Disconnect in the ⋮ sheet, the one line with Connect, the row's tap folding
  and not connecting, Connect beside the other host, `connected_hosts`.
- `NotificationsTest.twoHostsAlertEachWithItsHostAndAReplyGoesToItsHost` (app, JVM).
- `BackgroundTest` (core): which hosts connect at launch and at a restart without a screen, which
  are let go while away, and each host's notification choices over the defaults.
- `BackgroundModeTest` (app, JVM, Robolectric's service controller, ScriptedRex): the watch started
  again with a null intent connects the stored hosts and alerts; with the mode off it stops; a host
  not kept is not connected and an always-connected one is; a task removal keeps it; its
  notification names the host and its Disconnect works; Stop with no screen closes; boot and update
  start it only with the mode on.
- `BackgroundServiceTest` (emulator, API 30 and 34): MainActivity connected to ScriptedRex with the
  mode on, `finishAndRemoveTask()` (what a swipe does to the task), and the watch, the connection
  and an alert are still there; a boot (the receiver) connects the stored host again and alerts.
  Then device-test's shell steps, as root: a real `BOOT_COMPLETED` broadcast starts the watch, and
  `kill -9` of the process brings it back in the foreground on its own (START_STICKY), checked with
  the battery exemption; without it the result is only printed.
