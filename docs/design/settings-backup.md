# Export and import settings

Android backup is off for RexPocket on purpose (`android:allowBackup="false"`,
`fullBackupContent="false"`, and data extraction rules that exclude everything from cloud backup and
device transfer): a restore would copy the tailnet node's key to a second phone, which then fights the first
for the same identity, and it would bring back SSH passwords and keys sealed by a keystore key that
did not travel, so they could not be opened. **Settings › Backup › Export and import settings** is
the way to move a phone's setup instead: one file the person makes, keeps and opens on purpose.

## What is in it

| Part | From | Notes |
| --- | --- | --- |
| `appearance` | Settings › Appearance | `app` (`system`, `light`, `dark`), `terminal` (`app`, `dark`, `light`), `text` (`system`, `85`, `115`, `130`, `150`) |
| `terminal.fontScale` | the terminal's text size (pinch, Terminal font) | 0.7 to 1.8 |
| `notifications` | Background and notifications | `waiting`, `failed`, `done`, `messages`, `bells`, `vibrate` |
| `background.enabled` | Background alerts | turned on only where notifications are allowed (below) |
| `background.hosts[]` | each host's page | `name`, `stay`, `always`, `notify`, `events` (`Waiting`, `Failed`, `Done`, `Message`, `Bell` → on or off) |
| `home` | the Home | `filter` (`All`, `NeedsYou`, `Running`), `reopenLastTab`, `hostOrder`, `folded` and `unfolded` (host names) |
| `hosts` | the saved hosts | a `rexpocket-hosts/1` list, below |
| `quickReplies` | Settings › Quick replies | the replies and prompts in order, at most 20 of at most 2,000 characters; an import replaces the list |
| `secrets` | SSH passwords and keys | only with **Include SSH keys**, sealed |

Never in it: the tailnet's state, its node key, its login or setup key, the management server; the
session and pane a host had open; seen marks; the update channel; key bar and gestures (a later
version can add them as new parts). Session folds are left out: a session's id is the Rex's, so it
means nothing on a phone that has not connected yet.

```json
{
  "format": "rexpocket-settings/1",
  "appearance": { "app": "dark", "terminal": "app", "text": "115" },
  "terminal": { "fontScale": 1.2 },
  "notifications": { "waiting": true, "failed": true, "done": true, "messages": true, "bells": false, "vibrate": true },
  "background": { "enabled": true, "hosts": [ { "name": "agents", "stay": true, "always": true, "notify": true, "events": { "Bell": true } } ] },
  "home": { "filter": "NeedsYou", "reopenLastTab": false, "hostOrder": ["agents", "Desk mac"], "folded": ["Desk mac"], "unfolded": [] },
  "hosts": {
    "format": "rexpocket-hosts/1",
    "hosts": [
      { "name": "agents", "uri": "ssh://me@100.92.1.2:22", "dnsUri": "ssh://me@agents.example.ts.net:22",
        "hostKeyFingerprint": "SHA256:…", "keyComment": "rexpocket:agents" },
      { "name": "Desk mac", "uri": null, "dnsUri": "ws://desk.local:7297", "hostKeyFingerprint": null, "keyComment": null }
    ]
  },
  "quickReplies": ["yes", "continue", "/compact"],
  "secrets": { "kdf": "PBKDF2-HMAC-SHA256", "iterations": 600000, "salt": "…", "cipher": "AES-256-GCM", "nonce": "…", "hosts": 1, "data": "…" }
}
```

Every part is optional: a part that is missing leaves the phone's as it is. Unknown fields are
ignored at every level (a newer app's part, an event this version does not know); a known field
with a value this version does not know is refused. A file is at most 1 MiB.

### The hosts

`hosts` is a host list (host-import.md) with three widenings, because a backup keeps every host the
phone has rather than the SSH hosts another tool provisions:

- `dnsUri` and `uri` may also be Rex's own port, `ws://host[:port]` or `wss://host[:port]` (no
  account, path, query or fragment). Such a host has no host key: a `hostKeyFingerprint` on it is
  refused.
- A name follows Rex's rule (1-64 characters, no surrounding spaces, no control characters), so
  "Desk mac" goes in; two hosts with the same name (ignoring case) keep the first.
- Up to 200 hosts, and none at all.

The SSH entries of a backup's list are a valid `rexpocket-hosts/1` list on their own, so a tool can
take them out and hand them to Import hosts. The other way round does not hold: Import hosts refuses
a Rex port.

## Include SSH keys

Off by default. On, export asks for a passphrase twice (at least 8 characters, never stored) and
seals every SSH host's password, private key and key passphrase into `secrets`:

- **Key:** PBKDF2 (RFC 8018) with HMAC-SHA-256, **600,000 iterations** (OWASP's 2023 figure for
  this hash), a fresh random **16-byte salt**, a **256-bit** key. The passphrase is NFC-normalised and
  encoded as UTF-8, so the same words typed on another keyboard give the same key. PBKDF2 is written
  out on `HmacSHA256` (`BackupCrypto.derive`) rather than taken from `SecretKeyFactory`, whose Android
  providers have not always agreed on how a passphrase's characters become bytes; the tests hold it to
  RFC 7914's vector and to the JVM's own PBKDF2.
- **Cipher:** AES-256-GCM, a fresh random **96-bit nonce**, a **128-bit tag**; the associated data is
  `rexpocket-settings/1 secrets`, so the box opens in this kind of file only.
- **Plaintext:** `{"hosts":[{"name","password","privateKey","passphrase"}]}`, by the names in `hosts`.
- A file may name other rounds between 100,000 and 5,000,000; outside that, a salt that is not 16-64
  bytes, a nonce that is not 12, or another `kdf` or `cipher`, the file is refused. `hosts` (the
  count) is shown before the box is opened; it is not trusted for anything else.

A wrong passphrase and a changed byte fail the same way (the tag does not match): "That passphrase
does not open them." A box that opens but names a host the file does not have, a Rex-port host, a
host twice, or a key that is not PEM text, fails the whole import.

Derivation takes about 0.2 s on a laptop and a second or more on a phone; it runs off the main
thread, with the sheet saying it is working. There is no recovery without the passphrase.

## Import

The same steps as Import hosts: **read and check all of it → plan → preview → one confirm → apply**.

- **Sources:** Settings › Backup › Choose a file (the system picker) or the clipboard; a file opened
  with RexPocket ("Open with"); a share. A share or an opened file is a backup when its text, or its
  first 64 KiB, names `rexpocket-settings/`; otherwise it goes on to Import hosts, and then to the
  share sheet, as before. A backup also names `rexpocket-hosts/1` (its list), so it is looked for
  first.
- **All or nothing.** The whole file is read and checked before the preview: one bad value, one name
  that is not one of the file's hosts, one bad host entry, imports nothing, and the sheet says where
  (`appearance.app`, `background.hosts[2].name`, or the host entry's number and name).
- **Preview.** The hosts' counts (New, Changed, Unchanged), then each setting that would change, as
  old → new; only what differs is listed. Each host is shown as Import hosts shows it.
- **Hosts** go through Import hosts' own plan (`ImportPlan`): matched by name, then by address; a
  host key that is set is pinned, one that would change is shown side by side and changed only if
  that host's own box is ticked, and unticked, that host is left exactly as it was - its address,
  its background choices, its fold and its place too. **Nothing is removed**: a host that is not in
  the file keeps everything, its place included.
- **Order.** `home.hostOrder` lays the file's order onto the hosts it names, in the places those
  hosts take; every other host keeps its place. New hosts come after the phone's, as in Import hosts,
  before the order is laid on.
- **Sign-ins.** With a box in the file, the preview offers **Unlock SSH keys**. Unlocked, an SSH host
  that is new, or saved with nothing to sign in with, signs in with the backup's password or key
  (kept sealed by the keystore, as a pasted one is) instead of getting a fresh key; a saved host that
  has its own keeps it. Not unlocked, the import goes on without them, and Import hosts' rule
  applies: every SSH host that needs one gets a fresh Ed25519 key of its own, shown afterwards on
  the new keys' page.
- **Background mode** is turned on only where Android lets the app post notifications; otherwise
  it stays off and the app says to allow notifications and turn it on in Background and
  notifications. An always-connected host connects when its choice is written, as the switch does.
- **Logs** get counts, kinds and field names, never a value, a password, a key or the passphrase.

## Export

Settings › Backup › Save to a file (the system's create-document picker) or Share (the share sheet,
as text). Include SSH keys asks for the passphrase first. The sheet then says how many hosts went in,
how many hosts' sign-ins were sealed, and names the hosts left out (a name Rex's rule refuses, or a
second host of one name).

## Code

- `core/…/SettingsBackup.kt`: `SettingsFormat` (read, check, write, `open` the box), `settingChanges`
  (the preview's settings), `inFileOrder`, `credentialsFor`. `BackupCrypto.kt`: PBKDF2 and AES-GCM.
  `HostImport.kt`: `HostListFormat.read`/`writeList` with `HostListRules` (the backup's widenings).
- `app/…/ui/SettingsBackup.kt`: `SettingsBackupper` (export, the sources, the plan, unlock, apply)
  and the sheet; `RexViewModel.writeSettings` writes each setting through its own setter.
  `MainActivity` routes "Open with" and shares to it before Import hosts.

## Tests

- `SettingsBackupTest` (core): every part round-trips; the hosts are a host list (and a plain one
  still refuses a Rex port); no credential without a passphrase and none in the clear with one; the
  box opens with its passphrase only, a changed byte and a wrong passphrase both fail; 600,000 rounds
  and NFC; PBKDF2 against RFC 7914 and the JVM; unknown fields ignored; each kind of bad file refused
  with where; a box outside the rules refused; the preview's changes; the file's order; who gets the
  backup's sign-in.
- `SettingsBackupScreenTest` (app, JVM and emulator): a backup read back puts every setting back and
  removes no host; a bad file imports nothing and says where, and never opens Import hosts; a plain
  host list still goes to Import hosts; sealed keys come in only with their passphrase.
