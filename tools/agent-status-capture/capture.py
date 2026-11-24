#!/usr/bin/env python3
"""Capture what a coding agent reports to Rex (OSC 7501), as a replay fixture.

Run it in one Rex pane while the agent runs in another; see README.md here. It follows the
agent pane's program status with `rex events --json`, and writes every report Rex records and
every record Rex drops as the lines core/src/test/resources/agent-replays/*.replay are made of:

    report state=working:app=omp
    report id=task-1:state=done | title=<title 1> | msg=<msg 2>
    drop task-1

Titles and messages are redacted - each distinct text becomes a placeholder, so a fixture still
shows when a message changed - unless --keep-text is given. Nothing else of the session is
written: no ids of sessions or blocks, no paths, no screen. Add `expect` lines by hand where the
home's lists are known (see the fixtures).

Usage:
    capture.py BLOCK_ID [-o FILE] [--keep-text] [--raw FILE] [-- REX_ARGS...]

BLOCK_ID is the agent's pane: run `echo $REX_BLOCK` in it, or pick it from `rex block ls`.
Arguments after `--` go to every rex command (for example `-S unix:///path/rex.sock`).
Stops on Ctrl-C, or when the pane closes.
"""
import argparse
import json
import subprocess
import sys
import time

KEYS = ("id", "state", "kind", "app", "progress")


class Redactor:
    def __init__(self, keep):
        self.keep = keep
        self.seen = {}

    def __call__(self, key, text):
        if self.keep:
            return text
        if text not in self.seen:
            self.seen[text] = "<%s %d>" % (key, len(self.seen) + 1)
        return self.seen[text]


def report_line(rid, record, redact):
    """One `report` line: the pairs as the program wrote them (the `app` it gave, not Rex's
    effective_app), then title and msg as plain text, redacted."""
    pairs = []
    if rid:
        pairs.append("id=" + rid)
    pairs.append("state=" + record.get("state", "idle"))
    for key in ("kind", "app", "progress"):
        value = record.get(key)
        if value is not None:
            pairs.append("%s=%s" % (key, value))
    line = "report " + ":".join(pairs)
    for key in ("title", "msg"):
        text = record.get(key)
        if text:
            line += " | %s=%s" % (key, redact(key, text.replace("|", "/")))
    return line


def rex(args, extra):
    return subprocess.run(["rex"] + extra + args, check=True, capture_output=True, text=True).stdout


def main():
    argv = sys.argv[1:]
    extra = []
    if "--" in argv:
        at = argv.index("--")
        argv, extra = argv[:at], argv[at + 1:]
    parser = argparse.ArgumentParser(description="Capture an agent's OSC 7501 reports as a replay fixture.")
    parser.add_argument("block", help="the agent pane's block id ($REX_BLOCK in it)")
    parser.add_argument("-o", "--out", help="the fixture to write (default: stdout)")
    parser.add_argument("--keep-text", action="store_true", help="keep titles and messages as they are")
    parser.add_argument("--raw", help="also write each event's record as JSON lines (titles and messages redacted the same way)")
    opts = parser.parse_args(argv)

    redact = Redactor(opts.keep_text)
    session = json.loads(rex(["block", "inspect", opts.block, "--json"], extra))["session_id"]
    out = open(opts.out, "w") if opts.out else sys.stdout
    raw = open(opts.raw, "w") if opts.raw else None
    started = time.time()

    def write(line):
        out.write(line + "\n")
        out.flush()

    write("# Captured with tools/agent-status-capture on %s; titles and messages %s." % (
        time.strftime("%Y-%m-%d"), "kept" if opts.keep_text else "redacted"))
    # What the pane holds already, as reports, so the replay starts where the capture did.
    current = json.loads(rex(["block", "call", "-b", opts.block, "program_status"], extra)).get("records", [])
    for record in current:
        write(report_line(record.get("id", ""), record, redact))

    events = subprocess.Popen(["rex"] + extra + ["events", "-s", session, "--json"], stdout=subprocess.PIPE, text=True)
    try:
        for line in events.stdout:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            inner = event.get("payload", {}).get("event", {})
            if inner.get("type") == "block_closed" and inner.get("payload", {}).get("block_id") == opts.block:
                break
            if inner.get("type") != "block_event":
                continue
            body = inner.get("payload", {})
            if body.get("block_id") != opts.block:
                continue
            name, payload = body.get("name"), body.get("payload", {})
            when = "# +%.1fs %s" % (time.time() - started, payload.get("reason", ""))
            if name == "program_status_changed":
                record = payload.get("record", {})
                write(when)
                write(report_line(payload.get("id", ""), record, redact))
                if raw:
                    shown = {k: v for k, v in record.items() if k in KEYS or k in ("effective_app", "updated_at")}
                    for key in ("title", "msg"):
                        if record.get(key):
                            shown[key] = redact(key, record[key])
                    if record.get("owner"):
                        shown["owner"] = record["owner"].get("name")
                    raw.write(json.dumps({"id": payload.get("id", ""), "record": shown, "reason": payload.get("reason")}) + "\n")
                    raw.flush()
            elif name == "program_status_removed":
                write(when)
                # The records Rex removed, exactly (a program's clear, the program exiting, a shell
                # prompt), whatever caused it: the replay drops the same ones.
                ids = [rid if rid else "(root)" for rid in payload.get("ids", [])]
                if ids:
                    write("drop " + " ".join(ids))
    except KeyboardInterrupt:
        pass
    finally:
        events.terminate()
        if opts.out:
            out.close()
        if raw:
            raw.close()


if __name__ == "__main__":
    main()
