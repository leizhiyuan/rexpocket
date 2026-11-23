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
