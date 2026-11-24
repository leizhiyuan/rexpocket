# agent-status-capture

Records what a coding agent tells Rex about itself - its OSC 7501 program status reports, and
the records Rex drops on its own - as a replay fixture for `AgentReplayTest`
(`core/src/test/resources/agent-replays/`). CI cannot run real agents (no model keys); a capture
from a real session on a desk is how a fixture comes to match what an agent really does.

## Capturing

You need Rex's `rex` command and Python 3 on the desk, and Rex running.

1. In Rex, split the tab you will run the agent in. In the agent's pane, run `echo $REX_BLOCK`
   and copy the block id.
2. In the other pane:

   ```sh
   tools/agent-status-capture/capture.py block:01a2... -o omp-session.replay
   ```

3. Use the agent in its pane as you normally would: a prompt or two, an approval, a question,
   a turn that ends, Ctrl-C, quitting. Then Ctrl-C the capture (it also stops when the pane
   closes).

Options: `--raw FILE` also writes each record as JSON lines (with `effective_app`, `updated_at`
and the owner's process name); `--keep-text` keeps titles and messages instead of redacting them
(do not commit such a capture); arguments after `--` go to every `rex` call, for example
`-- -S unix:///path/rex.sock`.

## What goes in the file

```
# Captured with tools/agent-status-capture on 2026-11-24; titles and messages redacted.
report state=working:app=omp
# +12.4s report
report state=blocked:kind=permission:app=omp | msg=<msg 1>
# +15.0s report
report state=working:app=omp
# +40.2s child_exited
drop (root)
```

- `report <pairs>`: one report as the program wrote it (its own `app`, not Rex's
  `effective_app`), title and msg after `|`.
- `drop <ids>`: records Rex removed (`(root)` is the root record), after a program's `clear`,
  the program exiting or a shell prompt; the comment above says which.
- Titles and messages become `<msg 1>`, `<title 2>`...: the same text keeps its placeholder, so
  the fixture still shows when a message changed. No session, block or client ids, paths or
  screen contents are written.

## Making it a fixture

Copy the file to `core/src/test/resources/agent-replays/<agent>-<what>.replay`, add a comment on
top saying which agent and version it came from, and add `expect running|needs-you|both|none`
lines where the home's lists are known (a `see` line is the person looking at the pane). Every
`.replay` file there is replayed by `AgentReplayTest` (on ScriptedRex in rex-integration, on a real
Rex in rex-real): after each step the phone must hold Rex's records and list the tab as Rex's own
records imply, and as the `expect` lines say.
