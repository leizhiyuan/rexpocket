# Backlog: good ideas, not scheduled

Ideas reviewed and judged worth having, but not worth the effort yet. Each notes what it needs.

| Idea | What | Size | Needs |
| --- | --- | --- | --- |
| Find Rex on the tailnet | Mark the tailnet sheet's devices that answer as Rex on 7297 or 443, beside the Add host each already has. The device list is there; the probe is not: a dial and a hello through the node per device and port, with timeouts. | S-M | Run it on asking (a "Look for Rex" row), a few at a time, with `ConnectionCheck`'s Rex step. |

## Next: a QoL scout pass

The kind of gap people notice in use and the tests don't: a choice that can be made but never
changed. The first example was a Quick Settings tile. It could be bound to a host or tailnet, but
nothing freed or rebound it, and the "all three tiles are in use" message told people to do
something no screen offered. Before the split PRs, when usage allows, send a few read-only scout
agents across the app, each with one area. Each scout lists findings with a file and line, and
says how the person would hit each one. The fixes are then batched.

What to look for:
- **Set once, never changed:** a binding, choice or pairing with no Change or Clear (tiles, a
  host's tailnet account, key groups, quick replies, the Netbird add-on pairing, trusted host
  keys, fallback order).
- **Dead-end text:** a message, empty state or error that names an action with no button or link
  to it, or a button whose result isn't confirmed.
- **Asymmetry:** something a host can do that a tailnet can't, or the other way round. Also
  something a sheet offers that its row's TalkBack actions don't, or that is in Home but not in the
  terminal, or the other way round.
- **No way back:** a destructive or broad action with neither Undo nor a confirm; state that only
  a reinstall resets.
- **Hidden state:** a choice that changes behaviour with no visible sign of it (filters, the
  background mode a host follows, the address in use).
- **Settings coverage:** each setting is in the backup export, has a sensible default and can be
  reset.
- **Gesture-only features:** something only a gesture reaches. Pinch-to-zoom in the terminal
  is the known one: someone using the app didn't know it existed, and an omp table wrapped at
  52 columns until they found it. Planned fix: a Text size row in the tab ⋮ menu (− / +, Reset,
  showing the current columns), a one-time "Pinch to change text size" hint, and a default size
  in Settings › Appearance that the backup carries. Check other gestures the same way: long
  press, swipe, double tap.

**Done: the terminal and key bar.** The pinch has a Text size row in the ⋮ menu (− / +, Reset, the
columns and rows), a one-time hint, and a default for new tabs in Settings › Appearance and the
Terminal font dialog, which the backup already carried (terminal-controls.md, 4.2). Select text is
in the menu and the palette for a long press set to something else; Clear and Reset terminal ask
first; Copy and Share output are in the menu's Pane group, Clear and Reset under More; Return held
is labelled "New line" with a TalkBack action and named in the hint; an empty Quick replies panel
and the composer link to Edit quick replies; a deleted quick reply has Undo and the list has
Restore defaults; the composer's Clear has Restore, its draft goes only once Rex took the prompt,
and its Return after is kept per host; a selection's Copy shows the same toast as other copies
before Android 13. A selection is still one screen: Select all is "Select screen" and the
selection's menu offers Copy output for the rest (an autoscroll into the scrollback would need the
frozen frame to follow the viewport). Insert path is built (4.3).

**Done: Home and its sheets** (home.md). Mark all as seen is in the host sheet and the home's ⋮
(scoped to what the list shows, one Undo), and a done or failed row has its own Mark as seen;
Approve, Deny, Reply and Interrupt are in the tab sheet and under a blocked tab in the tree, and
the tab sheet has Host… for the host's own sheet; Rename has Automatic name (Rex restores its own
label for an empty one); the ⋮ has Connect all and Disconnect all, with Reconnect; while hosts are
away the counted chips are marked and an empty list says it covers connected hosts, with Connect
all; Activity says how many seen idle tabs it hides, with Show. Launch templates and Rex's custom
actions, from the table above, are built too (home.md, "Launch templates"; command-palette.md,
"Rex's custom actions"). The tailnet dialog's Leave confirm went to the tailnets pass.

Areas, one per scout:
- Home and its sheets (done: see above);
- the terminal and key bar;
- hosts, import and backup (done: each host's whole list of other addresses, Try other addresses
  and tailnet account in Export and the backup, Forget host key, an other address typed and not
  added kept by Save, the terminal font, key bar and gestures in the backup, Reset to defaults on
  Terminal controls and Notifications, a name another host has warned about and an export that says
  why it left a host out; with host links (host-link.md) and Settings › Security (app-lock.md));
- tailnets, tiles and background (done: account removal and sign-out, Leave with its hosts and Rejoin, sign-in and Restart on the page, why a tailnet is up or a host is off, tile taps and Add a tile confirmed, tiles and tailnet choices in the backup; launcher shortcuts with it);
- notifications and the alert actions (done: alert taps that open their pane after a restart, signed with a per-install key, and an expired or removed-host alert that says so; Reply, Approve, Deny and Interrupt confirmed, Not sent shown even after a restart; a Failures channel; Mute on an alert and in the tab menu, in the backup; Stop with Turn on, Android's stop on the page, hosts that went kept in the watch notification, Manage; the summary, tailnet trouble and stopped notes deep-linked; the background-mode tile and the Glance widget).

## From the design review (design-review.md, P2)

| Idea | What | Size | Needs |
| --- | --- | --- | --- |
| Import and export hosts beside Backup | Settings' "Import hosts" holds export too; Backup and Security are cards of one row. One card "Hosts and backup". | S | Rename the row, move it; OpenFromOutsideTest finds Settings by its Connection caption. |
| Blocked words on tab rows | "waiting for approval" → Rex's blocked kinds, if Rex's own clients word them. | S | Rex's wording for `permission`, `question`, `auth` (unconfirmed). |
| Background mode page | Notifications is eight cards even reordered; Android's checks and the host and tailnet pages could be "Background mode ›". | M | A page of its own, with its back arrow; NotificationsTest and the deep links (Manage, stopped notes). |

## Robustness pass: found, not fixed

From the robustness pass (connections, state, background, data, threading, security). Fixed in the
same pass, each with a test that failed first: a restarted pane stream's RESUME count
(StreamResumeTest), events dropped behind a slow call (EventBurstTest), no redial when the
network comes after a start with none (NetworkWatcherTest), a paste over one Rex message dropping
the connection (LargePasteTest), recents showing a locked app (AppLockTest), a desk's tunnel taken
by the phone (OtherBlocksTest). Left, with why:

| Finding | What a person hits | Why not now | Needs |
| --- | --- | --- | --- |
| A tab that is not a terminal | A desk's tunnel (or another block kind) in a tab shows an empty terminal on the phone; typing there fails. The block is no longer taken or streamed. | UI words in every translation. | A note in the pane ("A tunnel: open it on the desk"), from `BlockInfo.creator`, and the key bar off for it. |
| SSH secrets read on the main thread | `HostStore.list()` opens every host's password, key and passphrase through the keystore on each call, and the view model calls it on the main thread (each save, each find). With many SSH hosts a few hundred ms each time; an ANR risk on a slow keystore. | Touches every caller of the store. | Keep the opened hosts in memory (`list()` from a cache written by `write`), and open secrets only when a connection asks. |
| A keystore that fails once forgets SSH secrets | `SecretBox` keeps a failed key load as null for the process: `open` gives "" and the next `upsert` of any host writes "" over every saved secret. | Rare (a keystore that is busy at start); needs a device to show. | Keep the sealed text when it cannot be opened, and write it back unchanged; retry the key load. |
| Two writers of the host list | `upsert` reads, changes and writes the whole list without a lock: two at once (a trusted host key from a connection and a rename) lose one. Both are on the main thread today. | No caller off the main thread yet. | `@Synchronized` on the store's writes, or one writer. |
| Launcher shortcuts are not signed | MainActivity is exported, and an `OPEN_PANE` intent with a host, session and tab id opens that tab, connecting first. Alert links are HMAC-signed; shortcuts are not. The ids are random and private, so another app cannot guess them. | Low risk; pinned shortcuts made before a change would stop working. | Sign the ids with `AlertSigner`, as alert links are, and accept unsigned ones only from the launcher. |
| Notifications on the lock screen | With the app lock on, alerts still show a pane's words on the phone's lock screen unless Android hides sensitive content. Deliberate (app-lock.md), but the app sets no visibility of its own. | A product choice. | `VISIBILITY_PRIVATE` with a public version ("A program needs you") while the app lock is on. |
| Recents before Android 13 | `setRecentsScreenshotEnabled` is Android 13 and later; before, a locked app's last screen stays in recents unless Block screenshots is on. | FLAG_SECURE would also block the person's screenshots. | Set FLAG_SECURE while the app is locked or away on 12 and older. |
| Each connection attempt makes an HTTP client | `RexClient.defaultHttp()` builds a new OkHttpClient (pool, dispatcher threads) per attempt; a host down all night in background mode makes hundreds, each idling its threads for a minute. Not a leak, but churn. | Works; the threads end. | One client per host and route, rebuilt only when the route changes. |
