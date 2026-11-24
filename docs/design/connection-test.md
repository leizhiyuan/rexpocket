# Test connection

The Add host and Edit host dialogs have **Test connection**: it tries the host as it is typed, one
step at a time, and says for each step whether it passed, why not in plain words, and what to do
about it. It saves nothing. Save is still the only way a host, or a host key, is written.

## Steps

| Step | What it does | Fails as |
| --- | --- | --- |
| Tailnet (a tailnet host) | the joined node, started as a connection starts it, waits until it runs and lists the host | not joined; the node not connected (needs a sign-in, or would not start) |
| Find the name (otherwise) | resolves the name on the phone's network | no such name |
| Reach the port | a TCP connection to the port, then closed; through the node for a tailnet host | refused, unreachable, no answer in time |
| Host key (SSH) | the SSH key exchange: the key the machine shows against the one pinned | not trusted yet (with the key), changed (both keys), no agreement on a cipher |
| Sign in (SSH) | the password or key, in the same SSH connect | nothing to sign in with, the key unusable, refused |
| Reach Rex | Rex's hello and welcome, over the SSH exec channel or the port, as the app connects | Rex not installed (`rex was not found`), not Rex on that port (HTTP but no WebSocket upgrade, or a closed connection), refused (401/403), TLS failed, no answer |
| Rex version | the API revision Rex answered a hello asking for 19 (preview.22's) with | below 7, what the app asks for on every connection; below 11 passes with a note that sending files needs 11 |

A step that fails ends the test; the steps after it are "Not tried". Each step has a timeout of its
own (tailnet 45 s, name 10 s, port 10 s, SSH 25 s, Rex 15 s). **Stop the test** ends it at once: the
running step reads Stopped, and a blocking call under it (a DNS lookup, a JSch connect) is left to
finish on its own thread, its socket closed where there is one.

## Host keys

An SSH host whose key is not pinned stops at Host key with the key's `SHA256:` fingerprint, to
compare with `ssh-keygen -lf` on the machine, and **Trust this key and test again**. A key that is
not the pinned one shows both, in red, with **Trust the new key and test again** and a warning. A
key trusted here is held by the dialog for that account, machine and port only (changing any of
them forgets it) and pinned when the person taps Save; the test itself writes nothing.

## Tailnet hosts

The test uses the tailnet node's own seam (`TailnetNode.checkRoute`): the SOCKS port `start` gives,
or, for a node with no proxy, the sockets that node dials. It is the route a connection to the host
takes, so a test passes exactly when a connection would get through. No tailnet setting changes.

## Code and tests

- `core/…/ConnectionCheck.kt`: the steps, their failures and timeouts. `SshTunnel.open` connects the
  SSH session alone so the host key and the sign-in can be told apart.
- `app/…/ui/HostCheck.kt`: `HostTester` (one test at a time, the latest report) and the dialog's
  panel; `Connections.checkRoute` brings the tailnet route up.
- `ConnectionCheckTest` (core): a Rex port passes every step with its revision; a closed port, a
  name that does not resolve, a web server that is not Rex, one that refuses, one that never
  answers; cancelling; a tailnet route given and missing; and on a real SSH server (Apache MINA,
  whose exec channel pipes to ScriptedRex): a key to trust then every step passing, a key that is
  not the pinned one, a wrong password, a machine without Rex.
- `HostCheckScreenTest` (app, JVM and emulator): every step said and passed, a refused port's reason
  and fix, one TalkBack stop per step, a key to trust, and the dialog's test saving nothing.
