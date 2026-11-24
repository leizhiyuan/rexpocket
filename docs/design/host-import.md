# Import and export hosts

Setting up a phone for many SSH hosts one at a time means typing each address and comparing each
host key's fingerprint by eye. **Import hosts** takes a list of hosts with the host key each is
expected to show, so the first connection is checked against it instead of asking, and gives every
new host a key of its own in one step. It sits beside SSH hosts (ssh-hosts.md): Settings ›
Connection › Import hosts, the row under Add host at the end of the home list, and the first run's
SSH card.

## Sources

Every source goes through the same steps: **read and check all of it → plan → preview → one
confirm → apply → the new keys**.

- **A host list file** (`rexpocket-hosts/1`, below): picked with the system file picker, pasted from
  the clipboard, opened with RexPocket ("Open with", `ACTION_VIEW` of `application/json`), or shared
  to it. A share is a host list when its text, or the first 64 KiB of a shared `.json`/`text/*`
  file, names the format; anything else goes to the share sheet as before. Any tool that knows the
  hosts can write the list: a provisioning script for a fleet of sandbox VMs, or RexPocket's own
  Export hosts.
- **The connected Rex's host list** (`hosts.list`): Import hosts › From Rex, one row per connected
  host. A Rex host's `label` is the name and its `endpoints`, in order, the address and the other
  addresses; an endpoint the phone cannot use (a Unix socket or named pipe, `ssh://` with no
  account) is left out, and a host with none left is listed as skipped. Rex sends no host keys, so
  SSH hosts from Rex ask on their first connection, as today. A label follows Rex's rule (1-64
  characters, no surrounding spaces, no control characters), not the file's stricter one.

There is no import from a URL or any other network source.

A **settings backup** (`rexpocket-settings/1`, settings-backup.md) carries the phone's hosts as a
host list of this format, inside it. Its hosts go through the same plan, preview and trust rules
below; the backup only widens what the list may hold (Rex's own port, Rex's names, up to 200 hosts),
and a file that names the settings format is taken by Settings › Backup before it comes here.

## The format: `rexpocket-hosts/1`

```json
{
  "format": "rexpocket-hosts/1",
  "note": "anything; ignored",
  "hosts": [
    {
      "name": "agents",
      "uri": "ssh://me@100.92.1.2:22",
      "dnsUri": "ssh://me@agents.example.ts.net:22",
      "hostKeyFingerprint": "SHA256:<43 characters of base64, no padding>",
      "keyComment": "provision:owner-phone"
    }
  ]
}
```

- `format` must be exactly `rexpocket-hosts/1`; anything else, or none, is refused with an error
  that says so.
- `name`: 1-64 characters of `[A-Za-z0-9._-]`, unique in the file (ignoring case).
- `dnsUri`, `uri`: `ssh://user@host[:port]` as the app's own address parser reads it
  (`HostAddressParser`), with the account named, scheme `ssh` only, port 1-65535, and no password,
  path, query or fragment. Either may be null, not both. `dnsUri` is the address the app connects
  by; `uri` is kept beside it as another address (below).
- `hostKeyFingerprint`: null, or exactly what the app shows for a host key: `SHA256:` and the
  unpadded base64 of 32 bytes, as `ssh-keygen -lf` prints it.
- `keyComment`: null, or one line of at most 256 characters: the comment of the key the phone makes
  for the host (`rexpocket:<name>` when null).
- `otherUris` (optional, added without a new format): every other address, in the order a
  connection tries them, each read as `uri` is; at most 16. With it, `uri` is ignored for the list
  (Export still writes the first other address there, for a reader from before `otherUris`);
  without it, `uri` is the one other address, as before. An empty list leaves a host's own as they
  are, as a file without other addresses always did.
- `tryOthers` (optional): Try other addresses, true or false; missing leaves the host's as it is.
- `tailnetAccount` (optional): the name of the tailnet account the host names (one line, at most 64
  characters). Found by name on import; an account this phone does not have leaves the host
  following the active one. Never an account's id or anything of its login.
- Unknown fields are ignored. A file is at most 64 KiB and 50 hosts.

The file holds public data only. There is no field for a password or a private key, and an `ssh://`
with a password is refused.

**Export hosts** (on the same sheet: save to a file, or share) writes this format from the phone's
SSH hosts: the name, the address as `dnsUri`, every other SSH address in order as `otherUris` (the
first also as `uri`), Try other addresses, the tailnet account's name, the pinned host key and the
comment of the host's key when it is one the phone made. The row says before it runs that it holds
SSH hosts only, and that Settings › Backup has every host and setting (a row on the sheet opens
it). Afterwards the sheet names each host left out, by why: reached on a Rex port (Backup has
them), a name the format does not allow, the name of a host already written (ignoring case: the
list would not read back; "rename one to keep both"), or past the most a file holds. Never a
password, a key or a passphrase. To move every host, with its settings and optionally its sign-in,
use Settings › Backup (settings-backup.md).

## What counts as the same host

Each entry is matched to a saved host **by name** (ignoring case), and when no saved host has its
name, **by address**: the entry's address or other address against a saved host's address or other
addresses (same account, same host ignoring case, same port; for a Rex-port host, the same TLS
choice). An entry that fits more than one saved host, or a saved host that two entries fit, fails
the whole import.

## Trust rules

1. **All or nothing.** The whole list is read, checked and planned before anything changes. One bad
   entry imports nothing, and the sheet says which entry (number and name) and why.
2. **Preview first.** Each host is New, Unchanged or Changed, with each changed field as old → new
   (name, address, other addresses). A host key that is set or changed shows the pinned key and the
   import's side by side. Nothing is written until Import.
3. **Host keys.** A fingerprint becomes the pinned key, so the first connection is checked against
   it: a match connects with no prompt, a mismatch is the red "host key changed" stop, as for any
   pinned host. A saved host whose pinned key differs is shown as a key change with a warning and
   is changed only if that host's own box is ticked; unticked, the host is left exactly as it was,
   its other changes too. No fingerprint keeps what is pinned, or the first connection's prompt.
4. **Client keys.** Each SSH host that is new, or saved with nothing to sign in with, gets a fresh
   Ed25519 key made on the phone, with `keyComment` as its comment; no key is ever shared between
   hosts. The private key is kept sealed by the keystore, as a pasted one is (ssh-hosts.md 2.4); a
   phone that cannot seal it says so. After the import one screen lists every new key's public
   half, each with Copy and Share, and all of them at once as `authorized_keys` text (a `# name`
   line over each key) or as JSON for a tool to install:
   `{"format":"rexpocket-public-keys/1","hosts":[{"name":…,"publicKey":"ssh-ed25519 … comment"}]}`.
   A saved host keeps its password or key.
5. **Nothing is removed.** Hosts not in the list are untouched; a list without other addresses
   leaves a host's as they are.
6. **Logs.** Diagnostics get counts and host names only, never key material.

## Other addresses

`SavedHost.fallbacks` keeps a host's other addresses (`ssh://user@host:port`, `ws://` or `wss://`)
in order: a file's `uri` beside its `dnsUri`, Rex's other endpoints. When the main address cannot be
reached, a connection tries them in turn; the host dialog edits the list and its order, and Test
connection checks each. See [multi-host.md](multi-host.md), "Other addresses". Export hosts and the
settings backup write them all back, in order, as `otherUris` (and the first as `uri`), with Try
other addresses; an import shows a changed order or Try other addresses as a change of that host.

## Code

- `core/…/HostImport.kt`: `HostListFormat` (read, check, write; `read` and `writeList` take the
  `HostListRules` a settings backup widens), `RexHostList`, `ImportPlan` (match, classify, confirm),
  `applyTo`. `SshKeys.kt`: the keys and `PublicKeyList`.
  `PocketSession.hostList()` reads `hosts.list`.
- `app/…/ui/HostImport.kt`: `HostImporter` (the sources, the plan, the write, Export) and the sheet;
  `MainActivity` routes "Open with" and shares to it, after Settings › Backup has declined them.
  The keys page also shows the keys a settings backup's import made (`showKeys`).

## Tests

- `HostImportTest` (core): the parser (valid, wrong or missing format, too large, too many, bad
  name, other schemes, a password, path or query, bad fingerprint, no address), one bad entry
  failing the file, matching, New/Unchanged/Changed, a key change only for a confirmed host, a fresh
  key per new host that JSch loads and signs with, the public key list, Export read back, and Rex's
  host list.
- `RexHostListTest` (core): the phone reads a host the desk put with `hosts.put`, on ScriptedRex and
  on a real Rex (`rex-real`); `RexShapeTest` holds the shape of `hosts.list` and `hosts_changed`.
- `HostImportScreenTest` (app, JVM and emulator): a shared list previewed then imported with its keys
  pinned and the saved hosts untouched, a key change confirmed for its host, a bad list importing
  nothing and naming the entry, and From Rex. `ScreenshotsTest` takes `host-import-*`.
