# Rex's own description of itself

What a real Rex server says about its CLI and API, saved so that work on RexPocket can look things
up without a Rex at hand. Nothing here is Rex itself: no binary, no state, only the text and JSON
the server and its CLI print about themselves.

Taken from **Rex 0.1.0-preview.22** (`rex version`: commit `a086db17db8d`, built
2026-11-19T19:29:44Z, `api: revision 19 (supports 0 to 19)`), the Linux amd64 package from
Superlogical's apt repository, on 2026-11-22.

| File | What it is | Made with |
| --- | --- | --- |
| `cli/rex*.txt` | `--help` of every `rex` command and subcommand (shell completion scripts left out) | `rex <command> --help` |
| `api-list.json` | every method of the server API, with its JSON Schemas and examples, and the block types | `rex api list --json` |
| `api-list.txt` | the same, one line each | `rex api list` |
| `api/<method>.json` | one method | `rex api describe <method> --json` |
| `block-creators.json` | the block creators, their flavors (with options) and their methods and **events**, such as `program_status_changed` | `rex api call block_creator.list '{}'` |
| `client-keymap.json` | what `client.keymap` answers on a server with no configuration file: no bindings, 22 block actions, two of them (Clear Terminal, Reset Terminal) with a title and category | `client.keymap` at API revision 7 |
| `llms.txt` | the server's API guide: connections, events, data streams, every method and event | `GET /llms.txt` on the server |

To refresh them for a new Rex, run a server as in [testing.md](../testing.md) ("Against a real Rex")
and repeat the commands above; `rex api list --json` names every method to describe. What RexPocket
relies on and how a real Rex behaves is in [rex-parity.md](../rex-parity.md), "Real Rex".
