# Backlog: good ideas, not scheduled

Ideas reviewed and judged worth having, but not worth the effort yet. Each notes what it needs.

| Idea | What | Size | Needs |
| --- | --- | --- | --- |
| Host onboarding | Import hosts from a connected Rex (`hosts.list`). Suggest tailnet devices that answer as Rex on 443 or 7297. Add a host from a `rexpocket://add?addr=&name=` link or QR code, with no secrets in it. | M | A QR made on the desk needs Rex or a small script. |
| App lock and screenshot block | An optional biometric or device-credential lock with a timeout. An optional `FLAG_SECURE`, which blocks screenshots and blanks the recents thumbnail. | S | - |

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
- hosts, import and backup;
- tailnets, tiles and background (done: account removal and sign-out, Leave with its hosts and Rejoin, sign-in and Restart on the page, why a tailnet is up or a host is off, tile taps and Add a tile confirmed, tiles and tailnet choices in the backup; launcher shortcuts with it);
- notifications and the alert actions (done: alert taps that open their pane after a restart, signed with a per-install key, and an expired or removed-host alert that says so; Reply, Approve, Deny and Interrupt confirmed, Not sent shown even after a restart; a Failures channel; Mute on an alert and in the tab menu, in the backup; Stop with Turn on, Android's stop on the page, hosts that went kept in the watch notification, Manage; the summary, tailnet trouble and stopped notes deep-linked; the background-mode tile and the Glance widget).
