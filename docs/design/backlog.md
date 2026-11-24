# Backlog: good ideas, not scheduled

Ideas reviewed and judged worth having, but not worth the effort yet. Each notes what it needs.

| Idea | What | Size | Needs |
| --- | --- | --- | --- |
| Agent launch templates | A per-host list of "name, directory, command". Running one opens a new tab and starts the agent, for example `cd ~/proj && claude`. | M | Check whether Rex's block layout takes a command or a cwd directly. If it doesn't, the phone types the line. |
| Host onboarding | Import hosts from a connected Rex (`hosts.list`). Suggest tailnet devices that answer as Rex on 443 or 7297. Add a host from a `rexpocket://add?addr=&name=` link or QR code, with no secrets in it. | M | A QR made on the desk needs Rex or a small script. |
| App lock and screenshot block | An optional biometric or device-credential lock with a timeout. An optional `FLAG_SECURE`, which blocks screenshots and blanks the recents thumbnail. | S | - |
| Shortcuts, tile, widget | Launcher shortcuts to recent and pinned panes. A Quick Settings tile that turns background mode on and off. A Glance widget with waiting, done and running counts per host. | S, then M | The background service's state. |
| Rex custom actions | Run the user's Rex Lua actions from the palette (`client.keymap`, `client.action`) and answer their prompts (`client_prompt_requested`, `client.reply`). | M-L | Check the payload shapes on a real Rex first. |
| Insert path | Browse the host's folders over `terminal.list_dir` and type the chosen path. Agents such as Claude Code already have an `@` file picker that works from the phone, so this mainly helps in a plain shell. | M | `list_dir` isn't wired up yet. |

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

Areas, one per scout:
- Home and its sheets;
- the terminal and key bar;
- hosts, import and backup;
- tailnets, tiles and background;
- notifications and the alert actions.
