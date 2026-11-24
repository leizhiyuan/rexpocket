# Add a host from a link or a QR code

A host can be handed to the phone as a link, `rexpocket://add?addr=<address>&name=<name>`, tapped
in a message or a page, or read from a QR code that holds it. It is one of three ways in besides
typing: Import hosts takes a list (host-import.md) and From Rex takes a connected Rex's own list.

## The link

- `addr` (required): the address as the host dialog's Address field takes it: `host`,
  `host:port`, `ws://` or `wss://host[:port]`, or `ssh://user@host[:port]`. URL-encoded.
- `name` (optional): the host's name, Rex's rule (1-64 characters, no surrounding spaces, no
  control characters).
- Nothing else: no password, no private key, no host key. An address with a password in it
  (`ssh://me:pw@...`), a query or a fragment in the address, a key given twice, a link over 1 KiB, or
  an address the app cannot read is refused with "That link does not name a host to add", and the
  log says why in a word, never the link.

`HostLink.write(address, name)` (core) makes one; `tools/host-link.sh` prints one, and its QR code
when `qrencode` is installed, on the desk:

```sh
tools/host-link.sh 'ssh://me@build.example.com' 'build vm'
```

## Opening it

`MainActivity` takes `ACTION_VIEW` of `rexpocket://add` (`DEFAULT` and `BROWSABLE`, so a browser or
a chat app can open it). The link never saves anything: the Add host dialog opens filled in, with a
line saying it came from a link and to check the name and address. The person can Test connection
first. Save is the only way it is written, with the same checks as a host typed in (the account and
a password or key for SSH; the host key asked on the first connection, as always). A dialog already
open starts again with what the link says.

## Scanning a QR code

There is no scanner in the app. The phone's own camera app (and Google Lens) reads a QR code and
offers to open the link it holds, which comes to the app through the same filter. That keeps the
APK as it is (no ML Kit, no ZXing, no Play services), asks for no camera permission, and works on
any phone whose camera app reads QR codes, which Android's have done since 9 on most makers. A
phone whose camera does not can use any QR app that opens links.

## Network safety

Nothing listens. The desk does not serve the link over HTTP for the phone to fetch, and the phone
opens no port to receive it: the link is all there is, and it holds no secret, so showing it on a
screen in a café gives away an address and nothing more.

## Code and tests

- `core/…/HostLink.kt`: `parse` and `write`. `HostLinkTest`: links read, written and read back,
  and each kind of refusal.
- `RexViewModel.openHostLink` asks the Home for the dialog (`HomeRequest.AddHost`);
  `HostsAndSecurityTest.aHostLinkAsksTheHomeToAddTheHostAndSavesNothing` (JVM and emulator), and
  `HostDialogQolTest.aHostFromALinkSaysToCheckItAndSavesOnlyOnSave` (emulator: the dialog's fields
  never idle on Robolectric).
