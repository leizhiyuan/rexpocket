# Rex API

> How to connect to a Rex server, call its methods, receive its events, and stream a block's
> output, followed by a reference for every method and every server and session event.

A Rex server runs your terminals and keeps them running whether or not anything is looking at them.
Clients, such as Rex.app and the `rex` CLI, connect to the server to show and control them.

This page documents the API that every client uses, so that you can write your own client, script,
or tool. The server you fetched it from builds it from its own API, so it always matches that
server's version. It describes API revision 19.

## How Rex Is Organized

Rex organizes work into four kinds of things. Most methods act on one or more of them.

- A **session** is a group of related work, such as one project. The server keeps a session running
  whether or not anything is looking at it.
- A **window** is a tab within a session. It holds a layout of blocks.
- A **block** is one application in a window. Today that means a terminal. Rex.app shows a block as
  a pane.
- A **client** is a program connected to the server, such as Rex.app, the `rex` CLI, or a script.

A window's **layout** divides its space between blocks with **splits**, side by side or one above
the other. A window can also have **floating layers**, which hold blocks above the layout. A block
that is still running but isn't placed in any window is **detached**. A session's **active
window** is the one it shows, and each window has one **focused** block, the one that receives
typing.

Every block is made by a **creator**, which has a name such as `com.superlogical.terminal`. A
**flavor**, such as `com.superlogical.terminal.shell`, is one way a creator can make a block.

Every session, window, block, and client has an **ID** that never changes and is never reused, such
as `block:01a10d99-0c06-7795-bf67-798ce4c36a33`. Each can also have a **label**, which is a name a
person chooses and can change.

A client can claim a block with `session.own_block` and become its **owner**. While a terminal has
an owner, it takes its size and colors from that client. An **action** is a named thing a client
can do, such as opening Rex.app's command palette or clearing a terminal's screen.

## Endpoints

The server answers HTTP requests on the same address that clients connect to. A local server
listens on a Unix socket, or on a named pipe on Windows. `rex server status` prints the address on
its `endpoint` line. A server that a `rex` command started also puts it in the `REX_SERVER`
environment variable of each of its terminals. Over a Unix socket or a named pipe, any host name
works in a URL, such as `ws://localhost/api/control`.

If the server doesn't let a caller in, such as a Tailscale user that its access rules don't allow,
it answers every request with status 403 and a JSON body such as `{"error": "..."}`.

| Endpoint | What it serves |
| --- | --- |
| `GET /api/control` | The control connection, a WebSocket that carries every method call and event. |
| `GET /api/data` | Data connections, WebSockets that each stream one block's output, such as everything a terminal's program prints. |
| `GET /api/help` | Every method as JSON, with the exact JSON Schema of its arguments and its result. |
| `GET /llms.txt` | This page, as Markdown. |
| `GET /llms.html` | This page, as HTML. |

## Methods

The API is a set of methods, such as `session.create` and `session.view`. To call a method, a client
sends the method's name and its arguments, a JSON object, on the control connection. The server
answers with the method's result, also a JSON object, or with an error.

A method's name starts with what it acts on, such as `session.` for sessions and everything in them,
`client.` for clients, and `server.` for the server itself. Blocks add methods of their own, named
after their creator, such as `com.superlogical.terminal.write`.

The `rex` CLI makes the connection for you, so it is the quickest way to try a method:

```sh
rex api list                         # list every method with a short description
rex api describe session.view        # show one method's arguments, result, and examples
rex api call -s work session.view    # call a method and print its result
```

The server ignores fields it doesn't know in a method's arguments. A client written for a newer
server can still call an older one, as long as it sends the fields the older server needs.

These are the methods this server provides. The method reference at the end of this page describes
each one in full.

- `block.close`: Close a block and end the program running in it.
- `block.event`: Send an input event, such as a key press or a paste, to a block.
- `block_creator.list`: List the creators the server can make blocks with, and their flavors.
- `client.action`: Perform an action from the configuration file or a block, as this client.
- `client.inspect`: Get one connected client's details, including the actions it offers.
- `client.key`: Send a key to a block, giving Lua key bindings the first chance at it.
- `client.keymap`: Get what each key does for this client. The result combines the client's own bindings with the configuration file and the client's Lua scripts.
- `client.list`: List the clients connected to the server, in the order they connected.
- `client.perform`: Ask another client to perform one of its actions and wait for its answer.
- `client.reply`: Answer a client_call_requested, client_perform_requested, or client_prompt_requested event.
- `client.set_label`: Set or remove this client's label.
- `client.set_script`: Load Lua scripts for this client, replacing any it loaded before.
- `data.connect`: Get a one-time ticket for opening a data connection to a block. A data connection streams a block's content as it changes, such as a terminal's output.
- `focus_history.reverse`: Step back through the focus changes the calling principal made.
- `help.all`: Describe every method the server provides, including block methods.
- `hosts.list`: List the server's hosts: the other Rex servers you connect to by label.
- `hosts.put`: Add a host to the server's host list, or replace the host with the same ID.
- `hosts.remove`: Remove a host from the server's host list.
- `server.apple.grant_file_access`: Let the server, and every program it runs, read files a Mac app was given.
- `server.apple.privacy_status`: Report the macOS privacy permissions the server and its programs have.
- `server.config_reload`: Reload the configuration file and tell every client to fetch its keymap again.
- `server.status`: Get the server's identity, session count, configuration, and Tailscale status.
- `server.stop`: Shut the server down, or with if_idle, only if nothing is using it.
- `server.tailscale.disable`: Turn off built-in Tailscale, keeping its sign-in for the next enable.
- `server.tailscale.enable`: Turn on built-in Tailscale so devices on your tailnet can reach the server.
- `server.tailscale.login`: Ask Tailscale for a sign-in link for the server's Tailscale device.
- `server.tailscale.logout`: Sign the server out of Tailscale and turn off built-in Tailscale.
- `server.tailscale.rename`: Change the server's Tailscale device name without signing in again.
- `server.version`: Get the version of Rex that the server is running and how it was built.
- `server.whoami`: Get how the server identified you, and the account the server runs as.
- `session.attach`: Attach to a session: start receiving its events and get its current view.
- `session.attach_all`: Start receiving events from the whole server and get the list of sessions.
- `session.bootstrap`: Set up a new connection in one call: receive events, list sessions, and attach.
- `session.close_layer`: Close a floating layer and every block in it.
- `session.close_window`: Close a window and every block in it.
- `session.create`: Create a session, optionally with its first windows already in it.
- `session.describe_block`: Get a block's details and the methods, events, and actions it provides.
- `session.destroy`: Destroy a session and close every block in it.
- `session.detach`: Detach the connection from a session. The connection stays open and the session keeps running.
- `session.detach_block`: Take a block out of its window without closing it. The block keeps running.
- `session.disown_block`: Give up the calling client's claim on blocks. The result lists each block's owner.
- `session.float_block`: Move a block out of its window's layout into a new floating layer.
- `session.focus_block`: Focus a block, and make its window the active window if it isn't.
- `session.focus_direction`: Move focus to the nearest block to the left, right, above, or below.
- `session.focus_next_pane`: Move focus to the next block in the active window.
- `session.focus_next_window`: Make the next window the active window.
- `session.focus_previous_pane`: Move focus to the previous block in the active window.
- `session.focus_previous_window`: Make the previous window the active window.
- `session.focus_window`: Make a window the active window. Focus goes to the block it last had focused.
- `session.help`: Describe the methods this session accepts and the methods its blocks provide.
- `session.list`: List the sessions on the server.
- `session.list_blocks`: List every block in the session, including detached blocks.
- `session.list_clients`: List the clients attached to the session.
- `session.lower_layer`: Move a floating layer to the back, or just below another floating layer.
- `session.move`: Move a session to another place in the order that session.list returns.
- `session.move_block`: Move a block, or place a detached one, in a new split beside another block.
- `session.move_window`: Move a window to another place in the session's window order.
- `session.new_block`: Create a block without placing it in a window. Place it later with session.move_block.
- `session.new_layer`: Create blocks in a new floating layer at the front of a window.
- `session.new_split`: Create blocks in a new split beside an existing block or split.
- `session.new_window`: Create a window and the blocks in its layout. It becomes the active window by default.
- `session.own_block`: Claim blocks for the calling client. The result lists each block's owner.
- `session.raise_layer`: Move a floating layer to the front, or just above another floating layer.
- `session.resize_layer`: Move or resize a floating layer.
- `session.resize_pane`: Move the border between two blocks to change their sizes.
- `session.set_block_label`: Set a block's label.
- `session.set_label`: Set the session's label.
- `session.set_layer_state`: Minimize a floating layer or return it to normal, optionally raising and focusing it.
- `session.set_window_label`: Set a window's label.
- `session.swap_blocks`: Swap the places of two blocks.
- `session.view`: Get the session's windows, their layouts, and their blocks in one snapshot.
- `session.zoom_block`: Zoom a block to fill its window, or undo the zoom.
- `target.complete`: List the labels, window positions, and short IDs that start with a prefix. Use it to complete a name as someone types it.
- `target.resolve`: Look up sessions, windows, blocks, or clients by ID, label, or position. Each query gets its own result, which says whether it matched one thing, nothing, or several.

Blocks made by `com.superlogical.onboarding` provide these methods:

- `com.superlogical.onboarding.set_step`: Move the walkthrough to another step. Emits step_changed when the step changes.
- `com.superlogical.onboarding.state`: Return the step the walkthrough is on.

Blocks made by `com.superlogical.terminal` provide these methods:

- `com.superlogical.terminal.blob_read`: Read a result that was too large for one reply or event, such as the output of format with max_bytes set, or a selection_copied event without text. The server keeps each result for about five minutes.
- `com.superlogical.terminal.clear`: Clear the screen and scrollback, keeping the current line at the top. It changes nothing while a full-screen program, such as an editor, is running.
- `com.superlogical.terminal.clipboard_read`: Read text that a program in the terminal copied to the clipboard. The server keeps each copy for about a minute, so a client can read text too large to send in an event.
- `com.superlogical.terminal.download_begin`: Open a file on the terminal's machine for download, and get a ticket for the rex.data.bytes.v1 data stream that carries its bytes from the terminal. A relative path is relative to the working directory of the terminal's foreground process. Only regular files can be downloaded.
- `com.superlogical.terminal.format`: Get the screen and scrollback as plain text, HTML, or escape sequences.
- `com.superlogical.terminal.input_mode`: Get how the terminal handles typed input, such as whether it is echoed. On Windows the call fails with not_found.
- `com.superlogical.terminal.list_dir`: List the subdirectories of a directory, and optionally its files. A relative path or ~ is resolved the way a program in the terminal would resolve it.
- `com.superlogical.terminal.modes`: List the terminal modes that programs can turn on, such as the alternate screen, bracketed paste, and mouse reporting, with each mode's name, number, and whether it is on. It doesn't wake an idle terminal.
- `com.superlogical.terminal.ports`: List the TCP ports that programs in the terminal are listening on, such as a development server's, with each port's address and the process that holds it. status is 'partial' when some processes couldn't be looked at, so a port missing from a partial reply may still be open. A truncated reply has an id, and blob_read with that id returns the whole list. It doesn't wake an idle terminal. To follow changes, call it once and then follow the ports_changed event.
- `com.superlogical.terminal.process`: Get the processes running in the terminal and how its program exited.
- `com.superlogical.terminal.program_status`: List the status that programs in the terminal have reported. Programs report a status, such as working or blocked, with OSC 7501 escape sequences.
- `com.superlogical.terminal.pwd`: Get the working directory that a program in the terminal last reported. To find where each process actually is, call process instead.
- `com.superlogical.terminal.reset`: Reset the terminal to its starting state and clear the screen and scrollback. The program keeps running, and the size and default colors stay the same.
- `com.superlogical.terminal.resize`: Request a terminal size in columns and rows. It applies at once if the caller owns the block or no client does, and otherwise when the caller becomes the owner.
- `com.superlogical.terminal.set_theme`: Replace the terminal's default colors. As with resize, they apply at once if the caller owns the block or no client does, and otherwise when the caller becomes the owner.
- `com.superlogical.terminal.size`: Get the terminal's size, its owner, and the size each client last requested.
- `com.superlogical.terminal.title`: Get the terminal's title and the process that set it.
- `com.superlogical.terminal.upload_begin`: Reserve a file to upload to the terminal's machine, such as one the user dropped on the terminal, and get a ticket for the rex.data.bytes.v1 data stream that carries its bytes. Once every file of the batch has arrived, the terminal types their paths as if the user had pasted them.
- `com.superlogical.terminal.write`: Type text into the terminal as if the user had typed it. The call returns once the text is queued, without waiting for the program to read it.

Blocks made by `com.superlogical.tunnel` provide these methods:

- `com.superlogical.tunnel.info`: Return what the tunnel forwards and how many connections it is relaying.

## The Control Connection

A client opens one control connection to the server and keeps it open for as long as it runs. The
control connection is a WebSocket at `GET /api/control`. Ask for the subprotocol `rex.control.v1`,
which WebSocket libraries send in the `Sec-WebSocket-Protocol` header.

Every message, in both directions, is one JSON object in one WebSocket text message. The server
offers `permessage-deflate` compression without context takeover, and works with or without it.

The server sends three kinds of message, each named by its `type`: one `welcome` when the
connection opens, a `response` to each request, and `event` messages, which it sends without being
asked. Skip a message whose `type` you don't know, because a newer server may add one. A client
sends one `hello`, then only `request` messages. The server skips a message whose `type` it doesn't
know. Older servers close the connection instead, so send a new type only when the welcome's
`api_revision` includes it.

### Opening the Connection

Within 5 seconds of opening the WebSocket, send a hello:

```json
{
  "type": "hello",
  "api_revision": 19,
  "client": {"kind": "cli", "name": "my-tool", "version": "0.1.0"}
}
```

A hello has these fields:

- `type` (string, required): Always hello.
- `reconnect_token` (string): The reconnect_token from the last welcome, to continue that client. Leave it out to start a new client. Never log it.
- `client` (object or null): What the connecting program is, for showing to people. Keep the whole hello under 512 bytes.
  - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
  - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
  - `name` (string): The program's name, such as Rex.app or rex. If it is longer than 256 bytes, the server keeps only the first 256.
  - `version` (string): The program's version, as the program prints it, such as 1.6.0. If it is longer than 256 bytes, the server keeps only the first 256.
  - `label` (string): The client's label, such as studio. Two clients can share a label.
- `api_revision` (integer): The newest revision of the control API the client speaks. Left out means 0.
- `api_min_revision` (integer): The oldest revision of the control API the client can use. Left out means 0.

Keep the whole hello under 512 bytes, and don't compress it. A hello
can carry a reconnect token, and compressing a secret can let someone who watches the connection
guess it. If your WebSocket library compresses messages, set it to leave messages under
512 bytes uncompressed, as Rex's own clients do.

The server answers with a welcome, which is always its first message:

```json
{
  "type": "welcome",
  "protocol": "rex.control.v1",
  "limits": {"max_message_bytes": 1048576},
  "client": {"id": "client:<uuid>", "reconnect_token": "<token>"}
}
```

A welcome has these fields:

- `type` (string, required): Always welcome.
- `protocol` (string, required): The protocol, rex.control.v1.
- `limits` (object, required): The limits of this connection.
  - `max_message_bytes` (integer, required): The largest message the server sends or accepts, in bytes. Never below 65536. Send no more than the smaller of this and your own limit.
- `carriers` (array of objects or null): Other ways to reach the same server, called carriers. Left out when there are none.
  - `carrier` (string, required): The carrier's name, such as quic. Ignore a carrier you don't know.
  - `port` (integer, required): The port the carrier accepts connections on, on the host you connected to.
  - `alpn` (array of strings or null, required): The TLS application protocols the carrier accepts, most preferred first. Offer them in the TLS handshake.
- `client` (object or null): The client this connection serves. Left out by servers that can't resume clients.
  - `id` (string, required): The client's ID. It stays the same when the client reconnects.
  - `reconnect_token` (string, required): Send this in a later hello to continue this client. Each welcome has a new token, so keep the newest one. If a connection drops before its welcome arrives, send the token you already had. Never log it.
  - `resumed` (boolean): True if this connection continued an existing client. False for a new one, including when the server didn't accept the token.
- `features` (array of strings or null): Optional behaviors the server supports. Send a newer field or value only if its feature is listed. Ignore names you don't know.
- `api_revision` (integer): The revision of the control API every later message on this connection uses. Left out means 0.
- `api_server_revision` (integer): The newest revision of the control API the server speaks. Left out means 0.
- `api_min_revision` (integer): The oldest revision of the control API the server speaks. Left out means 0.

Check `type` and `protocol` before you send anything else. Ignore fields you don't know, because
newer servers add them.

If the server can't serve the client, it closes the connection instead of sending a welcome. The
section "When the Server Closes the Connection" lists the reasons.

### API Revisions

Each change to what clients and servers send each other, such as a new field, method, or event,
gets a new API revision, one higher than the last. This page describes API revision 19.
This server speaks API revisions 0 through 19. When a client
uses an older revision, the server translates every message to and from that revision's shapes, so
an older client keeps working with a newer server.

Send the API revision your client was written for in `api_revision`. A client that leaves it out
uses revision 0. To receive messages
in the shapes this page shows, send 19.

Send the oldest revision your client can use in `api_min_revision`. Leaving it out means 0, which
tells the server that your client can use every revision from 0 through `api_revision`. The server
picks the newest revision that both sides speak and names it in the welcome's `api_revision`. Every
later message on the connection uses that revision's shapes.

If the client and the server share no revision, the server closes the connection with status 1008.
The reason says which side to update.

### Calling a Method

To call a method, send a request:

```json
{"type": "request", "id": "1", "method": "session.create", "payload": {"label": "work"}}
```

A request has these fields:

- `type` (string, required): Always request.
- `id` (string, required): Any non-empty string you choose. The response carries the same id, so you can match it to this request.
- `method` (string, required): The method to call, such as session.view.
- `payload` (any): The method's arguments, as a JSON object. Leave it out for a method that takes none.

An `id` can be any string that isn't empty, but it must differ from the `id` of every request that
is still waiting for its response. If it doesn't, the server closes the connection.

The server answers with a response that carries the same `id`:

```json
{"type": "response", "id": "1", "seq": 0, "payload": {"session_id": "session:<uuid>"}}
```

If the call fails, the response has an `error` instead of a `payload`, such as for a request that
names a block that doesn't exist:

```json
{
  "type": "response",
  "id": "2",
  "seq": 0,
  "error": {"code": "not_found", "message": "block not found", "status": 404}
}
```

A response has these fields:

- `type` (string, required): Always response.
- `id` (string, required): The id of the request this answers.
- `seq` (integer, required): The seq of the last event queued for this connection before the server started the request, or 0 if there was none. Apply events with a higher seq on top of the result.
- `payload` (any): The method's result. Left out when the request failed.
- `error` (object or null): Why the request failed. Left out when it succeeded.
  - `code` (string, required): What kind of failure it was, such as bad_request or not_found. Branch on this, and go by status for a code you don't know.
  - `message` (string, required): What went wrong, written for people. Its wording can change between releases.
  - `status` (integer, required): A number with the same meaning as an HTTP status code, such as 404.

### Order of Requests

The server can run several requests at the same time, so responses can arrive in a different order
than their requests. Wait for a response before you send a request that depends on it.

Some methods are different. The server runs them one at a time, in the order they arrive on the
connection, so that keys and the changes bound to them happen in the order they were typed. Each
one waits for the one before it to finish. These methods run in order: `block.close`, `block.event`, `client.action`, `client.key`, `focus_history.reverse`, `session.close_layer`, `session.close_window`, `session.detach_block`, `session.disown_block`, `session.float_block`, `session.focus_block`, `session.focus_direction`, `session.focus_next_pane`, `session.focus_next_window`, `session.focus_previous_pane`, `session.focus_previous_window`, `session.focus_window`, `session.move_block`, `session.move_window`, `session.new_layer`, `session.new_split`, `session.new_window`, `session.own_block`, `session.set_layer_state`, `session.swap_blocks`, `session.zoom_block`.

Every other method can run at any time, alongside the ordered ones. That includes every block
method, such as `com.superlogical.terminal.write`. To type text in two calls, wait for the first
call's response before you send the second, or send all the text in one call.

`client.action` can send its client a `client_call_requested` event, which asks the client to make a
call on another server. The action waits, up to a time limit, for the client to report the outcome
with `client.reply`. `client.reply` doesn't run in order, so it never waits behind the action it
answers.

An action can also ask the person a question. Every connected app that can show the question
receives a `client_prompt_requested` event. The event holds the prompt and the name of the
`action` that asks. The app answers with `client.reply` when the person answers or closes the
prompt, with `{"text": "..."}`, `{"ids": ["..."]}`, or `{"cancelled": true}` as the `result`.

The same prompt may be open on several devices, each with its own `call_id`. The first answer ends
it on all of them, and so does a cancel. Every other app then receives a `client_prompt_ended`
event with its `call_id` and a `reason`, and closes the prompt whatever the reason is. An app that
can't show a prompt replies with the error code `not_supported`. That takes only this app out of
the prompt, and the others keep showing it.

The server waits `timeout_ms` for an answer. That can be minutes, and the time doesn't count toward
the action's time limit.

### Errors

Branch on an error's `code`, not on its `message`. The message is written for people, and its
wording can change in any release.

| `code` | `status` | Meaning |
| --- | --- | --- |
| `bad_request` | 400 | The arguments are invalid, such as a field that is missing or has the wrong type. |
| `not_found` | 404 | Something the arguments name doesn't exist, such as a block, window, or layer. |
| `method_not_found` | 404 | The server, or the block the arguments name, has no method by that name. This happens when the server is older than the client. |
| `session_not_found` | 404 | No session has the `session_id` the arguments name. The server doesn't remember destroyed sessions, so a destroyed session and an ID that never existed both get this code. |
| `conflict` | 409 | The request can't run in the current state, such as when the connection isn't attached to the session, the session is shutting down, the session has no windows, or a limit has been reached. A block that closes or moves while a request is changing the layout around it also causes this error. The request can succeed once the state changes. |
| `not_supported` | 400 | The client the request names has no such action or can't perform it now, or the server doesn't keep the data the request needs. |
| `refused` | 403 | The client the request names chose not to perform the action. The message says why. |
| `client_unavailable` | 409 | The client the request names can't take the request, such as when its connection has dropped. |
| `too_large` | 413 | The result is larger than the message limit, or the change would make stored data, such as the host list, larger than the server allows. |
| `revision_too_low` | 400 | The method needs a newer API revision than the connection uses. Use an older method, or update the client. |
| `timeout` | 504 | The request didn't finish in the time the server allows, such as an action from the configuration file that ran too long. Some of its work may already have happened. |
| `internal` | 500 | The request failed for any other reason. A failure with another status and no more specific code also uses `internal`, such as status 403 for a method that the server accepts only over its local socket. Some mistakes in a request, which this server doesn't yet tell apart, also get this code, so read the message before you blame the server. |

Later releases may add codes. Handle a code you don't know by its `status`. Servers released before
`method_not_found` existed send `not_found` for a missing method.

### Attaching to Sessions

A connection must attach to a session before it can use it. Until it does, a request whose arguments
name the session in `session_id` fails with `conflict`, or with `session_not_found` if the session
doesn't exist. Methods that don't name a session, such as `session.list` and `server.status`, work
right away. So do `client.list` and `client.perform`, because their `session_id` doesn't name a
session for the connection to act on.

Three methods attach a connection:

- `session.attach` attaches to one session and returns its current view. The connection then
  receives every event from that session.
- `session.attach_all` lets the connection use every session and starts events for the whole server,
  without attaching to any one session. Its `event_scope` chooses which events. Use it to watch the
  server, such as to keep a list of sessions up to date.
- `session.bootstrap` does what `session.attach_all` does, attaches to the sessions you name or to
  one it picks, and returns the server's status, all in one request. An app that shows sessions
  sends it as the first request on a new connection.

Only an attachment made by `session.attach` or `session.bootstrap` lets a connection open data
connections to the session's blocks, or claim and release them with `session.own_block` and
`session.disown_block`. With only `session.attach_all`, those calls fail. `session.detach` ends an
attachment.

### Events

The server sends events on the control connection without being asked, such as when a session is
created:

```json
{
  "type": "event",
  "seq": 1,
  "payload": {"type": "session_created", "payload": {"session": {"session_id": "session:<uuid>", "label": "work"}}}
}
```

An event message has these fields:

- `type` (string, required): Always event.
- `seq` (integer, required): The event's place among the events sent on this connection. The first is 1, and each one after it goes up by one.
- `payload` (any, required): The event, with its name in type and its fields in payload.

The event inside `payload` has two fields of its own: `type` is the event's name, and `payload` holds
the event's fields.

An event that happens in a session arrives inside a `session_event`, which says which session it
came from:

```json
{
  "type": "event",
  "seq": 2,
  "payload": {
    "type": "session_event",
    "payload": {
      "session_id": "session:<uuid>",
      "event": {"type": "block_created", "payload": {"block_id": "block:<uuid>"}}
    }
  }
}
```

Blocks send events of their own, such as when a terminal changes directory. Each one arrives as a
`block_event` inside a `session_event`, with the block's ID, its creator, the event's name, and the
event's fields. `block_creator.list` describes the events each creator sends.

Skip any event whose type you don't know. Newer servers add events.

Which events a connection receives depends on how it is attached:

| Attached with | Receives |
| --- | --- |
| Nothing | `keymap_changed` and `hosts_changed`, which concern the whole account, and the events that ask this client to do something, `client_call_requested`, `client_perform_requested`, and `client_prompt_requested`. Also `client_prompt_ended`, for a prompt this client is showing. |
| `session.attach` | Also every event from that session, and its `session_destroyed`. |
| `session.attach_all` with `event_scope` set to `session` | Also `session_created`, `session_destroyed`, `session_moved`, `tailscale_status_changed`, and every event from every session except `block_event`. A session the connection attached to with `session.attach` still sends its `block_event`s. |
| `session.attach_all` with `event_scope` set to `all` | Also every other event from the server and every session, including `block_event`, `client_joined`, `client_left`, `client_changed`, and `client_performed`. An event that asks a client to do something still goes only to that client. |

`session.bootstrap` counts as `session.attach_all` with the `event_scope` you send, plus
`session.attach` for each session it attaches. The event reference near the end of this page lists
every event and its fields.

### Combining Results With Events

Each event's `seq` counts the events sent on the connection, starting at 1 and going up by one with
no gaps. Each response's `seq` is the `seq` of the last event the server had queued for the
connection when it started the request, or 0 if there was none. Together they tell a client which
events a result already includes.

To combine a result, such as the view that `session.attach` returns, with events:

1. Apply the result. If events arrive before the response, hold them until it does.
2. Skip events whose `seq` is at most the response's `seq`. The result already includes them.
3. Apply the events with a higher `seq`.

An event with a higher `seq` happened after the request started, and its change may already be in
the result as well. Apply events so that seeing a change twice does no harm. For a session's view,
compare revisions. The revision goes up by one with every change to what `session.view` reports. A
`session_view_changed` whose revision is at or below the one in your view describes a change you
already have. When a restarted server restores a session, its revision jumps above every revision
the session had before the restart, so a view you kept from before the restart always compares as
older.

While a connection is open, the server never skips an event it has queued for it. If a client falls
so far behind that its queued events pass 8 MiB, the server closes the connection with status 1008
and the reason `event backlog exceeded`. Connect again and read the state again.

### Message Size

A message can be at most `limits.max_message_bytes` from the welcome, measured after decompression.
This server allows 1,048,576 bytes (1 MiB), and every server allows at least
65,536 bytes (64 KiB).

- A result that would be larger, such as the text of a very large terminal screen, fails with
  `too_large`. The connection stays open.
- A request that is larger closes the connection with status 1009. Check the size before sending,
  and fail only that request. Write a large request in fragments, so that pings and pongs can go
  out while it is on its way.
- An event that would be larger is replaced by an `event_dropped` event with the same `seq`, so
  there is no gap. The connection stays open. A connection at an API revision without
  `event_dropped` gets nothing in its place.

### Staying Connected

The server pings the client every 120 seconds and waits up to 60 seconds for
each answer. After 3 unanswered pings in a row, it closes the connection
with status 1001 and the reason `keepalive timeout`. Browsers and most WebSocket libraries answer
pings for you. If your library can send pings, ping the server on the same schedule, so that your
client notices when the server is gone too.

### Reconnecting

If a connection drops without a WebSocket close frame, such as when a laptop sleeps or a phone loses
its network, or the client stops answering pings, the server keeps the client for
60 seconds. The client stays attached to its sessions and keeps its ID and its
script.

To continue the client, open a new connection and send the `reconnect_token` from the last welcome
in the hello. The new welcome has `resumed` set to true and carries a new token, and the old token
stops working. If the token is unknown, has expired, or was issued to someone else, the server
starts a new client instead, and `resumed` is false.

Events that happen while the client has no connection are lost, and `seq` starts again at 1 on the
new connection. Read the state again, such as with `session.view`, and combine it with events as
described above.

Responses to requests that were still running when the connection dropped are lost too, but the
server may have run those requests anyway. Check the state before you send one again, so that you
don't create a second window or type the same text twice.

A client that closes its connection with a close frame ends at once. So does a client that isn't
attached to any session, hasn't called `session.attach_all` or `session.bootstrap`, and has no
script, because the server has nothing to keep for it.

### When the Server Closes the Connection

| Status | Reason | Why |
| --- | --- | --- |
| 1001 | `keepalive timeout` | The client stopped answering pings. |
| 1002 | `protocol error` | The client broke the protocol in one of these ways: its WebSocket didn't use the `rex.control.v1` subprotocol, its first message wasn't a hello, a message wasn't JSON or had no `type` string, a request's `id` or `method` was missing or wasn't a string, such as a number for `id`, or a request reused the `id` of a request that is still waiting for its response. |
| 1003 | `protocol error` | The client sent a binary message. |
| 1007 | `protocol error` | The client sent text that isn't valid UTF-8. |
| 1008 | `no common API revision: ...` | The client and the server share no API revision. The reason says which side to update. |
| 1008 | `event backlog exceeded` | The client fell more than 8 MiB of events behind. |
| 1009 | `read limited at ...` | The client sent a message larger than the message limit. |
| 1009 | `event exceeds message limit` | An event was larger than the message limit. |

If no hello arrives within 5 seconds, the server drops the connection without a close frame. Most
WebSocket libraries report that as an abnormal closure, status 1006.

## Data Connections

A data connection carries one **stream**: the bytes that one block produces, such as everything a
terminal's program prints. A client opens one for each block whose output it shows. A stream can
outlive its connection. If the connection drops, the client can resume the stream on a new one.

Bytes flow only from the server to the client. To type into a terminal, call `client.key` or
`com.superlogical.terminal.write` on the control connection.

To open a data connection:

1. Attach the control connection to the block's session with `session.attach` or
   `session.bootstrap`. `session.attach_all` isn't enough.
2. Call `data.connect` for a ticket. For a terminal, set `data_ref_id` to `terminal` and
   `subprotocol` to `rex.data.terminal.v1`:
   ```json
   {
     "session_id": "session:<uuid>",
     "block_id": "block:<uuid>",
     "data_ref_id": "terminal",
     "subprotocol": "rex.data.terminal.v1"
   }
   ```
   The result's `ticket` is 16 bytes written as 22 base64url characters. The ticket works once,
   within 60 seconds, and only on a connection from the same user as the control
   connection, such as the same local account or the same Tailscale login.
3. Open a WebSocket at `GET /api/data` with the same subprotocol. Data connections are
   never compressed.
4. Within 5 seconds, send a HELLO as one binary message. Its 16 ticket
   bytes are the ticket from step 2, decoded from base64url without padding.
5. The server answers with a WELCOME, or with a REJECT and then closes the connection with the
   reason `data handshake rejected`. Either answer arrives as one binary message.
6. After the WELCOME, every binary message from the server is part of the stream. Each holds 1 to
   65,536 bytes (64 KiB). Join them in order. Where one message ends and the next begins
   has no meaning.

The server ignores any message the client sends after the WELCOME.

### Handshake Messages

Each handshake message is a few bytes in a fixed layout. Integers are unsigned and little-endian.

| Message | Size | Layout |
| --- | --- | --- |
| HELLO | 17 bytes | `0x01`, then the 16 bytes of the ticket. |
| RESUME | 25 bytes | `0x02`, the 16 bytes of the latest reconnect token, then the number of stream bytes received so far, as a 64-bit integer. |
| WELCOME | 32 bytes | `0x81`, a 16-byte reconnect token, 3 zero bytes, how long the server keeps the stream for a resume in milliseconds as a 32-bit integer, then a 64-bit channel nonce that is never zero. |
| REJECT | 2 bytes | `0x82`, then a reason. |

The server ignores any bytes after the fixed part of a HELLO or a RESUME, so that later versions can
extend them. A WELCOME is always exactly 32 bytes.

The terminal stream marks its own control sequences with the channel nonce, so that a client can
tell them apart from look-alikes that a program printed.

A REJECT gives one of these reasons:

| Reason | Meaning |
| --- | --- |
| `0x01` | The ticket or token is unknown, has expired, has already been used, or was issued to someone else, or its session no longer exists. |
| `0x02` | This client already has a connection to the same data source of that block, and a client can have only one at a time. If the old connection dropped, send a RESUME, which replaces it. |
| `0x03` | The stream isn't available, such as when its block has closed, or a RESUME counted fewer bytes than the server sent. |
| `0x04` | Opening the connection would pass a limit. Try again later. This server doesn't send it yet. |
| `0x05` | The handshake was malformed or didn't arrive in time, or a RESUME counted more bytes than the server sent. |
| `0x06` | The server failed. |
| `0x07` | The ticket or token was issued for a different subprotocol. |

Treat a reason you don't know as a failure. A HELLO uses up its ticket even when the server
refuses it, so get a new ticket from `data.connect` before you try again. After a refused RESUME,
start over with a new ticket and a HELLO.

### The Terminal Stream

A terminal's stream starts with a snapshot of the terminal in Ghostty's snapshot format. Then comes
everything the terminal's program writes, with Rex's own messages placed where they are safe to
apply. Each Rex message is an APC escape sequence of the form `ESC _ rex1;<nonce>;<json> ESC \`,
where `<nonce>` is the WELCOME's channel nonce in decimal. Skip one whose nonce doesn't match,
because a program printed it. The JSON's `type` says what the message is:

- `resize`: the terminal's size, in `cols` and `rows`. `cell_width_px` and `cell_height_px` give a
  cell's size in pixels, or 0 when it's unknown. `owner` is the block's owner when the size was
  set, or null. One comes right after the snapshot, and another each time the size changes. An
  owner change alone sends none.
- `theme`: the terminal's default colors. `foreground`, `background`, and `cursor` are written as
  `RRGGBB` or `#RRGGBB`, and `palette` lists changes to the 256-color palette, each written as
  `index=RRGGBB`. A color that is left out is the built-in one. `scheme` is `light` or `dark`, or
  left out when the terminal decides from `background`. Each `theme` replaces the one before it.
  One follows the first `resize` unless every color is built in, and another comes each time the
  colors change.
- `child_exited`: the terminal's program exited with `exit_code`. No more program output follows,
  but the stream stays open.

Skip a `type` you don't know.

The snapshot format is meant for terminal emulators built on Ghostty. To read a terminal's screen as
text instead, call `com.superlogical.terminal.format`.

### Resuming a Data Connection

If a data connection drops, the server keeps the stream running for 60 seconds and
holds on to its output. To resume:

1. Stop reading the old connection.
2. Open a new WebSocket at `GET /api/data` with the same subprotocol.
3. Send a RESUME with the reconnect token from the latest WELCOME and the number of stream bytes you
   have received.

The server answers with a WELCOME that carries a new reconnect token and the same channel nonce, and
continues the stream from the first byte you haven't received. A resume needs no ticket and no
control connection.

The count must be exact, or the server refuses the resume and ends the stream. Count every stream
byte that your code has used, across every connection of the stream. Don't count the WELCOME, and
don't count bytes that a WebSocket library has read ahead but your code hasn't used yet.

To start over instead of resuming, get a new ticket from `data.connect` and send a HELLO. The server
ends the waiting stream and starts a new one.

### When a Data Connection Closes

| Status | Reason | What happened |
| --- | --- | --- |
| 1000 | | The stream ended. |
| 1001 | `keepalive timeout` | The client stopped answering pings. Resume the stream. |
| 1001 | `data provider canceled` | The server stopped the stream, such as when it shuts down or the client that opened the stream leaves the server. |
| 1008 | `data provider unavailable` | The block has no data source with that `data_ref_id`, or doesn't support the subprotocol. |
| 1011 | `data provider failed` | The block failed. |
| 1013 | `data reconnect required` | Start a new stream with a new ticket. This also happens when more output piled up while the client was away than the server could keep. |
| 4000 | `block closed` | The block closed. Don't reconnect. |
| 1002, 1008, 1011, or 1013 | `data handshake rejected` | The server sent a REJECT. Its reason says why. |

When your client closes a data connection with status 1000, its stream ends. Any other close, or a
connection that drops, leaves the stream waiting for a resume. The server pings data connections on
the same schedule as the control connection.

## Calling Block Methods

Blocks provide methods of their own, named after their creator and the method, such as
`com.superlogical.terminal.write`. Call one like any other method. Its arguments always hold
`session_id` and `block_id`, which name the block, and the method's own arguments under `args`:

```json
{
  "type": "request",
  "id": "7",
  "method": "com.superlogical.terminal.write",
  "payload": {
    "session_id": "session:<uuid>",
    "block_id": "block:<uuid>",
    "args": {"data": "echo hello\n"}
  }
}
```

The result is the block method's own result, such as `{"bytes_written": 11}`. If the block has no
method by that name, the call fails with `method_not_found`.

Some block methods can also be called through actions, such as `com.superlogical.terminal.clear`.
Rex.app shows actions in its command palette, a key binding can name one, and `client.action`
performs one.

## A First Session

These calls create a session with one terminal, run a command in it, and read its screen. Make each
call after the one before it has answered, because each uses an ID from an earlier result.

1. Call `session.create` to make a session with one window that holds a shell:
   ```json
   {
     "label": "work",
     "initial_windows": [
       {"layout": {"block": {"flavor": "com.superlogical.terminal.shell"}}}
     ]
   }
   ```
   The result's `session_id` names the session, and `initial_windows[0].block_ids[0]` names the
   terminal's block.
2. Call `session.attach` with `{"session_id": "session:<uuid>"}`. The connection can now use the
   session, and it receives the session's events.
3. Call `com.superlogical.terminal.write` to type a command:
   ```json
   {"session_id": "session:<uuid>", "block_id": "block:<uuid>", "args": {"data": "ls\n"}}
   ```
4. Call `com.superlogical.terminal.format` to read the screen:
   ```json
   {"session_id": "session:<uuid>", "block_id": "block:<uuid>"}
   ```
   The result's `content` is the screen as plain text. The command's output can take a moment to
   appear, so if it isn't there yet, call it again.

The session keeps running after your client disconnects. Call `session.destroy` to end it and the
terminal in it.

## Event Reference

Each event below lists the fields of its inner `payload`. Events about the whole server come first,
then session events, each group in alphabetical order. A session event arrives inside a
`session_event`.

Events that blocks send, such as a terminal's bell, aren't listed here. Each one arrives as a
`block_event`, and `block_creator.list` lists each creator's events with a JSON Schema of their
fields.

### `client_call_requested`

Sent to ask the client to call a method on another server, for an action this connection started with client.action. Make the call, then answer with client.reply and the same call_id, even if the call fails.

Fields:

- `call_id` (string, required): The ID to send back to client.reply. A connection never reuses one.
- `server` (string, required): The server to call, by the id the client gave it in the servers list of client.action.
- `method` (string, required): The method to call, such as session.set_label.
- `args` (any): The method's arguments. Left out when there are none.

### `client_changed`

Sent when a client changes its label or its actions, or reconnects and describes itself differently. Only connections that set event_scope to all in session.attach_all or session.bootstrap receive it.

Fields:

- `client` (object, required): The client as it is now.
  - `client_id` (string, required): The client's ID. It stays the same when the client reconnects.
  - `info` (object): What the client says about itself, such as its name and version, for showing to people. Left out if it said nothing.
    - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
    - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
    - `name` (string): The program's name, such as Rex.app or rex. If it is longer than 256 bytes, the server keeps only the first 256.
    - `version` (string): The program's version, as the program prints it, such as 1.6.0. If it is longer than 256 bytes, the server keeps only the first 256.
    - `label` (string): The client's label, such as studio. Two clients can share a label.
  - `principal` (object, required): Who the client is, as the server identified it when it accepted the connection.
    - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
    - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
    - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
    - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
    - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
    - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
    - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
    - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
    - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
    - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
    - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
    - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
    - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
  - `connected_at` (string, required): When the client first connected, as an RFC 3339 timestamp. It doesn't change when the client reconnects.
  - `connection_state` (string): Whether the client has a connection: connected, or lost if it dropped and the client may still reconnect. Treat a missing or unknown value as connected.
  - `connection_lost_at` (string or null): When the server noticed the connection drop, as an RFC 3339 timestamp. Set only while connection_state is lost.
  - `session_ids` (array of strings or null): The sessions the client attached to with session.attach or session.bootstrap, sorted by ID. Left out if there are none.

### `client_joined`

Sent when a new client connects to the server, but not when a client reconnects after its connection drops. Only connections that set event_scope to all in session.attach_all or session.bootstrap receive it.

Fields:

- `client` (object, required): The client that connected.
  - `client_id` (string, required): The client's ID. It stays the same when the client reconnects.
  - `info` (object): What the client says about itself, such as its name and version, for showing to people. Left out if it said nothing.
    - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
    - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
    - `name` (string): The program's name, such as Rex.app or rex. If it is longer than 256 bytes, the server keeps only the first 256.
    - `version` (string): The program's version, as the program prints it, such as 1.6.0. If it is longer than 256 bytes, the server keeps only the first 256.
    - `label` (string): The client's label, such as studio. Two clients can share a label.
  - `principal` (object, required): Who the client is, as the server identified it when it accepted the connection.
    - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
    - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
    - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
    - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
    - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
    - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
    - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
    - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
    - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
    - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
    - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
    - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
    - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
  - `connected_at` (string, required): When the client first connected, as an RFC 3339 timestamp. It doesn't change when the client reconnects.
  - `connection_state` (string): Whether the client has a connection: connected, or lost if it dropped and the client may still reconnect. Treat a missing or unknown value as connected.
  - `connection_lost_at` (string or null): When the server noticed the connection drop, as an RFC 3339 timestamp. Set only while connection_state is lost.
  - `session_ids` (array of strings or null): The sessions the client attached to with session.attach or session.bootstrap, sorted by ID. Left out if there are none.

### `client_left`

Sent when a client leaves the server because it closed its connection, or because its connection dropped and it didn't reconnect within 60 seconds. Only connections that set event_scope to all in session.attach_all or session.bootstrap receive it.

Fields:

- `client_id` (string, required): The client that left.

### `client_perform_requested`

Sent to ask the client to perform one of its actions for a client.perform call. Answer quickly with client.reply and the same call_id, because the caller is waiting and later requests to this client wait behind this one.

Fields:

- `call_id` (string, required): The ID to send back to client.reply. A connection never reuses one.
- `action` (string, required): The name of the action to perform.
- `args` (any): The action's arguments, as a JSON object. Left out if the request had none.
- `origin` (string): What caused the request, as the requester gave it, such as key or cli. Empty means api.
- `session_id` (string): The session the action applies to, if the request named one. The server hasn't checked it.
- `block_id` (string): The block the action applies to, if the request named one. The server hasn't checked it.
- `requester` (object, required): The client that sent the request. The server fills this in from the requester's connection, so it can't be faked.
  - `client_id` (string, required): The client's ID.
  - `info` (object): What that client says about itself, for showing to people. Use principal, not this, to decide what to allow. Left out if it said nothing.
    - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
    - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
    - `name` (string): The program's name, such as Rex.app or rex. If it is longer than 256 bytes, the server keeps only the first 256.
    - `version` (string): The program's version, as the program prints it, such as 1.6.0. If it is longer than 256 bytes, the server keeps only the first 256.
    - `label` (string): The client's label, such as studio. Two clients can share a label.
  - `principal` (object, required): Who that client is, as the server identified it when it accepted the connection.
    - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
    - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
    - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
    - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
    - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
    - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
    - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
    - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
    - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
    - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
    - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
    - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
    - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).

### `client_performed`

Sent when a client.perform call to an existing client ends, whether or not the client performed the action. Only connections that set event_scope to all in session.attach_all or session.bootstrap receive it.

Fields:

- `client_id` (string, required): The client that was asked.
- `action` (string, required): The name of the action the request asked for.
- `requester` (object, required): The client that sent the request.
  - `client_id` (string, required): The client's ID.
  - `info` (object): What that client says about itself, for showing to people. Use principal, not this, to decide what to allow. Left out if it said nothing.
    - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
    - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
    - `name` (string): The program's name, such as Rex.app or rex. If it is longer than 256 bytes, the server keeps only the first 256.
    - `version` (string): The program's version, as the program prints it, such as 1.6.0. If it is longer than 256 bytes, the server keeps only the first 256.
    - `label` (string): The client's label, such as studio. Two clients can share a label.
  - `principal` (object, required): Who that client is, as the server identified it when it accepted the connection.
    - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
    - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
    - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
    - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
    - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
    - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
    - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
    - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
    - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
    - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
    - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
    - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
    - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
- `outcome` (string, required): How it ended: performed, refused (turned down without trying), failed (any other error), or timeout (no answer in time). Treat a value you don't know as failed.
- `error` (object or null): Why the action wasn't performed. Set for every outcome except performed.
  - `code` (string, required): The kind of failure, as an error code such as not_found, refused, or internal.
  - `message` (string, required): What went wrong, written for people. In client.reply, a message longer than 4096 bytes is shortened and ends with an ellipsis.

### `client_prompt_ended`

Sent when a prompt that this client is showing ends without this client's answer, such as when another client answers it or its timeout passes. Close the prompt with this call_id, whatever the reason. A later client.reply for it fails with not_found.

Fields:

- `call_id` (string, required): The call_id of the client_prompt_requested event whose prompt ended.
- `reason` (string, required): Why the prompt ended: answered if another client answered or cancelled it, timeout if its timeout passed, or cancelled if the action stopped waiting. Close the prompt for any reason, including one you don't know.

### `client_prompt_requested`

Sent to ask the client to put a question to the person using it, for an action from the configuration file that called rex.ui. Every connected app that can show the prompt receives it, and the first answer wins. Show the prompt, then answer with client.reply and the same call_id once the person answers or closes it. If client_prompt_ended arrives for the call_id first, close the prompt without answering.

Fields:

- `call_id` (string, required): The ID to send back to client.reply. A connection never reuses one. Each client that shows the same prompt receives its own.
- `action` (string): The name of the action that asks, as rex.action defined it. Show it, or the title client.keymap lists for it, so the person knows what the question is for. Left out when unknown.
- `prompt` (object, required): The question to show.
  - `kind` (string, required): What the prompt asks for: input for a line of text, or pick for a choice from items. Answer not_supported for a kind you don't know.
  - `title` (string, required): A short name for the question, such as Branch name. Show it where the person can see what is being asked.
  - `placeholder` (string): The hint to show in the text field while it is empty.
  - `value` (string): The text the field starts with, for the person to edit.
  - `confirm` (string): The label of the control that submits the answer, such as Stage. Left out when the client should choose one.
  - `multiple` (boolean): True when the person may choose any number of items, including none. Only for pick.
  - `allow_other` (boolean): True when the person may answer with typed text instead of an item. Only for pick without multiple.
  - `items` (array of objects or null): The choices, in the order to show them. Only for pick.
    - `id` (string, required): Identifies the item in the answer. Unique within the prompt.
    - `title` (string, required): The item's main text.
    - `subtitle` (string): A second, quieter line of text.
    - `icon` (string): A picture for the item, by name: document, folder, branch, terminal, code, server, database, package, globe, link, person, tag, clock, star, gear or plus. Show a generic picture for a name you don't know.
    - `detail` (string): Short text for the far end of the row, such as a file's status.
    - `selected` (boolean): True when the item starts out chosen. Only with multiple.
- `timeout_ms` (integer, required): How long the server waits for the answer, in milliseconds from when it sent the event. Stop showing the prompt after that.

### `client_script_events_dropped`

Sent when the Lua scripts that a client loaded with client.set_script fall behind and miss events from a session. Later misses aren't reported until the scripts catch up. The client receives it if it's attached to that session, and so do connections that set event_scope to all.

Fields:

- `session_id` (string, required): The session whose events the scripts missed.
- `client_id` (string, required): The client whose scripts fell behind.
- `event_name` (string, required): The first event the scripts missed, by the name rex.on uses for it, such as block_closed or com.superlogical.terminal.bell.

### `event_dropped`

Sent in place of an event that was too large to send, with the sequence number that event would have had. Fetch again whatever the dropped event would have changed: the session named by session_id, or if there is none, the server-wide state that event_type is about.

Fields:

- `event_type` (string, required): The type of the dropped event, such as hosts_changed. If the event was about one session, this is the type of the event inside session_event, such as block_event.
- `session_id` (string): The session the dropped event was about. Left out for an event about the whole server.
- `block_id` (string): For a dropped block_event, the block that sent it.
- `creator_name` (string): For a dropped block_event, the creator that made the block.
- `block_event_name` (string): For a dropped block_event, the event's name, as the block's creator lists it.
- `size` (integer, required): The size of the dropped event in bytes, not counting the control message around it.

### `hosts_changed`

Sent to every client when the server's host list changes, such as after hosts.put, hosts.remove, or server.config_reload. Call hosts.list for the new list, unless your last result from it has the same hash.

Fields:

- `hash` (string, required): Identifies the new host list. If your last hosts.list result has the same hash, you already have this list.

### `keymap_changed`

Sent to every client after server.config_reload reads the configuration file again, which may change any client's keymap. Call client.keymap again, unless your last result from this server has the same hash.

Fields:

- `generation` (integer, required): How many times the server has loaded its configuration file since it started, counting this load. The next client.keymap result reports the same number in config.generation, unless the server loads the file again first.
- `hash` (string, required): The hash of the configuration file just loaded, as client.keymap reports it in config.hash. If your last client.keymap result from this server has the same hash, your keymap is unchanged.

### `session_created`

Sent when a session is created. Connections that called session.attach_all or session.bootstrap receive it.

Fields:

- `session` (object, required): The new session, as session.list reports it.
  - `session_id` (string, required): The session's ID.
  - `label` (string, required): The session's label.

### `session_destroyed`

Sent when a session is destroyed. It is the last event about that session, except that a client_script_events_dropped event for it can still follow. It reaches connections attached to the session as well as those that called session.attach_all or session.bootstrap.

Fields:

- `session_id` (string, required): The session that was destroyed.

### `session_moved`

Sent when a session moves to a new place in the order that session.list returns. Connections that called session.attach_all or session.bootstrap receive it.

Fields:

- `session_id` (string, required): The session that moved.
- `before_session_id` (string): The session that now comes directly after the one that moved. Left out when the moved session is now last.

### `tailscale_status_changed`

Sent when the server's Tailscale status changes, with the complete new status. Only connections that called session.attach_all or session.bootstrap receive it.

Fields:

- `error` (string): Why built-in Tailscale turned off after its HTTPS listener failed. The next enable clears it.
- `state` (string, required): 'disabled' (off), 'starting', 'needs_login' (see auth_url), 'needs_machine_approval' (a tailnet admin must approve the device), or 'running' (on the tailnet). Treat a value you don't know as 'starting'.
- `dns_name` (string): The device's MagicDNS name, such as rex.tail1234.ts.net. Left out until known.
- `auth_url` (string): The link to open to sign the device in to Tailscale. Present while state is needs_login, once Tailscale has made the link.
- `account` (object): The Tailscale account that signed the device in. Left out until Tailscale reports it.
  - `login_name` (string, required): The Tailscale login name, usually an email address. For display only, because it can change.
- `tailnet_name` (string): The tailnet's display name, or its domain if it has none. Left out until known.
- `allow_tailnet_members` (boolean, required): True if anyone your tailnet's access rules allow may connect. False lets in only the Tailscale user who signed the device in.
- `tags` (array of strings or null): The Tailscale tags the device has, such as tag:rex. A tagged device has no owner. Left out when it has none.
- `https` (object): The state of the device's HTTPS listener. Left out while built-in Tailscale is off, and by older servers.
  - `state` (string, required): 'pending' until the HTTPS listener is ready, then 'available'. The server is reachable over the tailnet only when available. Treat a value you don't know as 'pending'.
  - `url` (string): The server's address on the tailnet, such as https://rex.tail1234.ts.net. Present once HTTPS is available and the device's name is known.
- `control_url` (string): The control server the device uses, such as a Headscale server. Left out for Tailscale's own, while built-in Tailscale is off, and by older servers.

### `active_window_changed`

Sent when the session's active window changes. The window_id field names the new active window, and is left out when the session has no windows. This is a session event.

Fields:

- `window_id` (string): The new active window. Left out when the session has no windows.

### `block_closed`

Sent when a block closes and leaves the session, including when the session is destroyed. API calls that name the block fail from then on, though the block may still be shutting down. This is a session event.

Fields:

- `block_id` (string, required): The block that closed.

### `block_created`

Sent when a block joins the session. API calls can name the block from then on, though it may not be placed in a window yet. This is a session event.

Fields:

- `block_id` (string, required): The new block's ID.

### `block_event`

Sent when a block sends one of the events that its creator lists in block_creator.list, such as a terminal's bell. Only connections attached to the session, or that set event_scope to all, receive it. This is a session event.

Fields:

- `block_id` (string, required): The block that sent the event.
- `creator_name` (string): The creator that made the block, such as com.superlogical.terminal.
- `name` (string, required): The event's name, as the creator lists it, such as bell.
- `payload` (any): The event's fields, in the shape the creator lists for it. Left out when the event has none.

### `block_owner_changed`

Sent when a block's owner changes, with the new owner, the previous one, and why. It isn't sent when a block closes, because block_closed already says the owner is gone. This is a session event.

Fields:

- `block_id` (string, required): The block whose owner changed.
- `owner` (string): The client that owns the block now. Left out when the block no longer has an owner.
- `previous` (string): The client that owned the block before. Left out when the block had no owner.
- `reason` (string): Why the owner changed: 'create' (the new owner created the block), 'take' or 'request' (the new owner called session.own_block with that mode), 'disown' (the previous owner called session.disown_block), 'lost' (the previous owner's connection dropped), 'disconnect' (the previous owner detached from the session), or 'restore' (the owner reconnected and got the block back). Ignore a value you don't know.

### `client_connected`

Sent when a client attaches to the session, with the same client_id, principal, and connected_at that session.list_clients reports for it. This is a session event.

Fields:

- `client_id` (string, required): The client that attached.
- `principal` (object, required): Who the client is, as the server identified it when it accepted the connection.
  - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
  - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
  - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
  - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
  - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
  - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
  - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
  - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
  - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
  - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
  - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
  - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
  - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
- `connected_at` (string, required): When the client attached to the session, as an RFC 3339 timestamp.

### `client_connection_changed`

Sent when an attached client's connection drops or comes back. The client stays attached while it's away, and if it doesn't reconnect within 60 seconds, client_disconnected follows. This is a session event.

Fields:

- `client_id` (string, required): The client whose connection changed.
- `principal` (object, required): Who the client is, the same as in its client_connected event.
  - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
  - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
  - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
  - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
  - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
  - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
  - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
  - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
  - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
  - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
  - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
  - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
  - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
- `connection_state` (string, required): Either connected, if the connection came back, or lost, if it dropped. Treat a value you don't know as connected.
- `connection_lost_at` (string or null): When the server noticed the connection drop, as an RFC 3339 timestamp. Set only while connection_state is lost.

### `client_disconnected`

Sent when a client detaches from the session: it called session.detach, it left the server, or the session was destroyed. This is a session event.

Fields:

- `client_id` (string, required): The client that detached.
- `principal` (object, required): Who the client was, the same as in its client_connected event.
  - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
  - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
  - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
  - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
  - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
  - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
  - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
  - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
  - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
  - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
  - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
  - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
  - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).

### `creator_changed`

Sent when the creators available in the session change, such as when one is added. Call block_creator.list for the new list. This is a session event.

Fields:

None.

### `session_label_changed`

Sent when the session's label changes. The label field holds the new label in full. This is a session event.

Fields:

- `label` (string, required): The session's new label. When the label was removed, this is the default label the session went back to.

### `session_view_changed`

Sent when anything that session.view reports changes, with the new revision but not the view. These events can arrive out of revision order, so call session.view only when revision is higher than any you've seen. This is a session event.

Fields:

- `revision` (integer, required): The session's revision after the change.

### `window_closed`

Sent when a window leaves the session, including when its last block closes, moves to another window, or is detached. It isn't sent when the whole session is destroyed. This is a session event.

Fields:

- `window_id` (string, required): The window that closed.

### `window_created`

Sent when a new window is added to the session, with its ID and label. This is a session event.

Fields:

- `window_id` (string, required): The new window's ID.
- `label` (string, required): The new window's label.

### `window_label_changed`

Sent when a window's label changes. The label field holds the new label in full. This is a session event.

Fields:

- `window_id` (string, required): The window whose label changed.
- `label` (string, required): The window's new label. When the label was removed, this is the default label the window went back to.

## Method Reference

Each method below lists its arguments and its result. Arguments marked required must be present. In
a result, the server leaves out a field that isn't marked required when it has no value.

### `block.close`

Close a block and end the program running in it.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session that holds the block.
- `block_id` (string): The block to close. It may be a detached block. Send this or window_id, not both.
- `window_id` (string): The window whose focused block to close. It doesn't have to be the active window. Send this or block_id, not both.
- `keep_last` (boolean): If true, fail rather than close the only block left in a window, counting floating layers. Ignored for detached blocks.

Result:

- `block_id` (string, required): The block that closed.
- `revision` (integer, required): The session's revision after this call. It is unchanged if the block was detached, because a detached block isn't part of the view.

Notes:

- Send exactly one of `block_id` and `window_id`. If you send both, or neither, the call fails with `bad_request`.
- The server sends `block_closed` when the block closes. If it was the last block in its window, the window closes too and the server sends `window_closed`.
- If `keep_last` is true and the block is the only one left in its window, counting blocks in floating layers, the call fails with `bad_request` and closes nothing.
- If the block's creator is still placing it, the call returns at once and the block closes when placement finishes.
- On one connection, `block.close` waits for earlier requests that can change focus, such as `session.new_split` and `session.focus_block`, to finish. A close by `window_id` therefore closes the block those requests left focused.
- If the session doesn't exist, the call fails with `session_not_found`. If `block_id` or `window_id` names something that isn't in the session, or the window has no focused block, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>"
}
```

Example result:

```json
{
  "block_id": "block:<uuid>",
  "revision": 42
}
```

### `block.event`

Send an input event, such as a key press or a paste, to a block.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session that holds the block.
- `block_id` (string, required): The block to send the event to.
- `event` (object, required): The event to send: its type, such as paste, and its payload.
  - `type` (string, required): The kind of event, such as key, paste, mouse, wheel, or focus.
  - `payload` (any): The event's fields. Leaving it out is the same as an empty object.

Result:

An empty object, `{}`.

Notes:

- A client may send only `key`, `paste`, `mouse`, `wheel`, and `focus` events. Every other event type is sent only by the server. A `paste` event's `payload` is `{"text": "..."}`, and a `focus` event's `payload` is `{"action": "in"}` or `{"action": "out"}`.
- The event goes straight to the block, so Lua key bindings never see it. To give key bindings the first chance at a key, use `client.key` instead.
- On one connection, `block.event` calls run one at a time, in order with `client.key`, so the block receives events in the order you send them.
- The call returns once the block has received the event, which may be before the block has finished acting on it.
- The call fails with `bad_request` if `event` is missing or its `payload` has the wrong shape for its type. It also fails with `bad_request` if `event.type` is empty, names an event only the server sends, or names an event this server doesn't know.
- If the session doesn't exist, the call fails with `session_not_found`, and if `block_id` names a block that isn't in the session, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>",
  "event": {
    "type": "paste",
    "payload": {
      "text": "echo hello\n"
    }
  }
}
```

Example result:

```json
{}
```

### `block_creator.list`

List the creators the server can make blocks with, and their flavors.

Arguments:

None.

Result:

- `creators` (array of objects or null, required): Every creator available, with its flavors and the API its blocks provide.
  - `name` (string, required): The creator's name, such as com.superlogical.terminal.
  - `title` (string, required): A short name for people to read, such as Terminal.
  - `description` (string, required): A sentence about what the creator's blocks do.
  - `host_id` (string): Identifies the machine and account its blocks run as. If it equals your local server's host_id, its blocks see the same files you do.
  - `flavors` (array of objects or null, required): The flavors this creator offers.
    - `name` (string, required): The flavor's short name within its creator, such as shell.
    - `flavor` (string, required): The flavor's full name, such as com.superlogical.terminal.shell. Use it in the flavor field of session.new_block or a layout.
    - `title` (string, required): A short name for people to read, such as Shell.
    - `description` (string, required): A sentence about what this flavor creates.
    - `options_schema` (any): A JSON Schema for the options this flavor accepts.
  - `api` (object, required): The methods and events that this creator's blocks provide.
    - `events` (object): The block events, by name. Each value is a JSON Schema for the event's fields.
    - `methods` (map of string to object): The block methods, by short name.
      - `description` (string): What the method does.
      - `input_schema` (any): A JSON Schema for the method's arguments.
      - `output_schema` (any): A JSON Schema for the method's result.
      - `actions` (array of objects or null): The actions that call this method, in the order the creator declares them.
        - `name` (string, required): The action's short name, such as reset. Its full name, used in key bindings, is the creator's name, a dot, and this name.
        - `title` (string, required): The name to show a person, such as Reset Terminal.
        - `description` (string): A sentence about what the action does, to show under its title in a menu or command palette.
        - `category` (string): A group of related actions, such as Pane.
        - `keywords` (string): More words to search for the action by, separated by spaces.
        - `args` (any): The fixed arguments the method is called with when the action runs. Left out when there are none.
        - `repeats` (boolean): True if holding the key down should run the action again, as holding an arrow key does.
  - `features` (array of strings or null): Optional features the creator supports. Ignore names you don't know. No names are defined yet.

Notes:

- Creators are sorted by `name`.
- To create a block, pass a flavor's `flavor` to `session.new_block` or in the `block` of a layout, with `options` that match its `options_schema`.
- `api.methods` lists the methods that every block from the creator provides. Call one as `<creator>.<method>`, such as `com.superlogical.terminal.title`, with `session_id`, `block_id`, and its arguments under `args`.
- A method's `actions` are things a person can do with a block, such as choosing an item in Rex.app's command palette or pressing a key. Performing one calls the method with the action's `args`, which are fixed values, not a schema like the `args` that `client.keymap` lists for an action.

Example result:

```json
{
  "creators": [
    {
      "name": "com.superlogical.terminal",
      "title": "Terminal",
      "description": "A terminal that runs a shell or any other command-line program.",
      "flavors": [
        {
          "name": "shell",
          "flavor": "com.superlogical.terminal.shell",
          "title": "Shell",
          "description": "A terminal that runs the user's shell, or a command you choose.",
          "options_schema": {
            "type": "object",
            "properties": {
              "cwd": {"type": "string"}
            }
          }
        }
      ],
      "api": {
        "methods": {
          "reset": {
            "description": "Reset the terminal to its starting state and clear the screen and scrollback. The program keeps running, and the size and default colors stay the same.",
            "input_schema": {"type": "object"},
            "output_schema": {"type": "object"},
            "actions": [
              {
                "name": "reset",
                "title": "Reset Terminal",
                "category": "Pane"
              }
            ]
          },
          "title": {
            "description": "Get the terminal's title and the process that set it.",
            "input_schema": {"type": "object"},
            "output_schema": {
              "type": "object",
              "properties": {
                "title": {"type": "string"}
              }
            }
          }
        },
        "events": {
          "bell": {"type": "object"}
        }
      }
    }
  ]
}
```

### `client.action`

Perform an action from the configuration file or a block, as this client.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `action` (string, required): The full name of the action, such as dev_layout or com.superlogical.terminal.reset. client.keymap lists them.
- `args` (any): The action's arguments, as a JSON object. Leave it out if there are none, and always for an action a creator declares.
- `origin` (string): What caused the request: key, palette, or api (the default). The action reads it as ctx.origin. Other values count as api, but script makes the call fail with bad_request.
- `session_id` (string): The session the action applies to. Required for a block action. A configuration file action may leave it out, and then has no default session.
- `block_id` (string): The block the action applies to. Defaults to the session's focused block, unless server is set. Requires session_id.
- `servers` (array of objects or null): Other servers this client is connected to, which a configuration file action can call with rex.server(name). Don't list the server you're sending this to.
  - `name` (string, required): The name an action uses for the server, in rex.server(name) and ctx.server. It must be unique and not empty.
  - `id` (string, required): Any non-empty value the client uses to recognize the server. It comes back unchanged in client_call_requested events.
- `server` (string): The name, from servers, of the server that holds session_id and block_id. Leave it out when they're on the server you're sending this to.

Result:

- `result` (any): The block method's result, or what the configuration file action returned. Left out if that action returned nothing, nil, or true.
- `client_actions` (array of objects or null, required): Actions the configuration file action asked this client to perform with rex.client.queue, in order. Perform each one as you would a key binding.
  - `action` (string, required): The name of the client action to perform. Ignore a name you don't know.
  - `args` (any): The action's arguments, as a JSON object. Left out if there are none.
- `ordered_after` (string): The id of the request this connection ran just before this one, counting only methods that run in order, such as client.key. Left out if there was none.

Notes:

- `action` decides what runs. A name without a dot, such as `dev_layout`, is an action that the configuration file defines with `rex.action`. A name with a dot, such as `com.superlogical.terminal.reset`, is an action or method of a block, named after its creator. `client.keymap` lists every name in `actions`.
- A block action, such as `com.superlogical.terminal.reset`, has fixed arguments, so a request for one with `args` fails with `bad_request`. A block method, such as `com.superlogical.terminal.write`, takes its arguments from `args`.
- If `args` isn't an object, the call fails with `bad_request`. `null` counts as leaving it out.
- The client's own actions, such as `pane.split`, run in the client and can't be sent here. A request for one, or for any name the server doesn't know, fails with `not_found`.
- On each connection, `client.key`, `client.action`, `block.event`, and the methods that change focus or the layout run one at a time, in the order they arrive. For an action bound to a key, use this method rather than calling the block method by name, such as `com.superlogical.terminal.write`, so the action takes effect after the keys typed before it.
- When `session_id` is set, the connection must be attached to that session, with `session.attach`, `session.bootstrap`, or `session.attach_all`, or the call fails with `conflict`. If `session_id` names no session, the call fails with `session_not_found`. Neither check applies when `server` is set.
- Send `session_id` with any block action or method, and whenever you send `block_id`, or the call fails with `bad_request`. A block action or method runs on `block_id`, or on the session's focused block if you leave that out. If the session has no focused block, the call fails with `conflict`.
- If the block isn't the kind the action or method belongs to, the call fails with `conflict`. If `block_id` names a block that isn't in the session, it fails with `not_found`.
- An action from the configuration file may leave out `session_id`. It then has no `ctx.session_id`, and its `rex.session` calls have no default session. With `session_id`, it sees `block_id`, or the focused block, as `ctx.block_id`.
- If `origin` is `script`, the call fails with `bad_request`, because only `rex.invoke` may report that origin.
- An action from the configuration file may run for 30 seconds, including time spent waiting on other servers. If it runs longer, the call fails with `timeout`. If the file is reloaded while it runs, the call fails with `conflict`.
- If an action from the configuration file raises an error, the call fails with a message that starts with where it happened, such as `init.lua:4: attempt to index a nil value`. However the action fails, part of it may already have run.
- Each entry in `servers` needs a unique, non-empty `name` and a non-empty `id`, and `server` must name one of them. Otherwise the call fails with `bad_request`. The server doesn't interpret either value.
- Set `server` when the session belongs to one of `servers`. The IDs then go to the action unchecked, and the focused block isn't filled in, so send `block_id` yourself. The action's `rex.session`, `rex.block.call`, and `rex.call` calls go to that server.
- When `server` is set, any name with a dot fails with `bad_request`, because a block action must run on the server that holds the block. Send it to that server instead.
- When the action calls another server, this connection receives a `client_call_requested` event. Make the call there and report the outcome with `client.reply`. If no reply comes within 10 seconds, or the reply is an error, the action's call fails and the action keeps running.

Example arguments:

```json
{
  "action": "com.superlogical.terminal.reset",
  "origin": "key",
  "session_id": "session:<uuid>"
}
```

Example result:

```json
{
  "result": {},
  "client_actions": [],
  "ordered_after": "key-41"
}
```

### `client.inspect`

Get one connected client's details, including the actions it offers.

Arguments:

- `client_id` (string, required): The client to describe. It can be any client, not only the one calling.

Result:

- `client` (object, required): The client, as client.list describes it.
  - `client_id` (string, required): The client's ID. It stays the same when the client reconnects.
  - `info` (object): What the client says about itself, such as its name and version, for showing to people. Left out if it said nothing.
    - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
    - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
    - `name` (string): The program's name, such as Rex.app or rex. If it is longer than 256 bytes, the server keeps only the first 256.
    - `version` (string): The program's version, as the program prints it, such as 1.6.0. If it is longer than 256 bytes, the server keeps only the first 256.
    - `label` (string): The client's label, such as studio. Two clients can share a label.
  - `principal` (object, required): Who the client is, as the server identified it when it accepted the connection.
    - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
    - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
    - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
    - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
    - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
    - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
    - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
    - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
    - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
    - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
    - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
    - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
    - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
  - `connected_at` (string, required): When the client first connected, as an RFC 3339 timestamp. It doesn't change when the client reconnects.
  - `connection_state` (string): Whether the client has a connection: connected, or lost if it dropped and the client may still reconnect. Treat a missing or unknown value as connected.
  - `connection_lost_at` (string or null): When the server noticed the connection drop, as an RFC 3339 timestamp. Set only while connection_state is lost.
  - `session_ids` (array of strings or null): The sessions the client attached to with session.attach or session.bootstrap, sorted by ID. Left out if there are none.
- `actions` (array of objects or null): The actions the client declared in its latest client.keymap request that listed any. Left out if it declared none.
  - `name` (string, required): The name that bindings use for the action, such as pane.split. Names from the configuration file never contain a dot.
  - `kind` (string): What performs the action: client (the client itself), lua (a rex.action in the configuration file), or block (a block of the creator that the name starts with).
  - `title` (string): The name to show a person, such as Split Pane.
  - `description` (string): A sentence about what the action does, to show under its title in a menu or command palette.
  - `category` (string): A group of related actions, such as Layouts. Configuration file actions without one are filed under Actions.
  - `keywords` (string): More words to search for the action by, separated by spaces.
  - `args` (any): A JSON Schema of the arguments the action takes. Left out when the action doesn't describe any.
  - `repeats` (boolean): True if holding the key down should run the action again, as holding an arrow key does.
  - `source` (string): Where the action is defined, as file:line, such as init.lua:12. Left out when there's no location, as for every action a client declares.

Notes:

- If `client_id` is missing or isn't a client ID, the call fails with `bad_request`. If it names no connected client, including one that has left, the call fails with `not_found`.
- To ask the client to perform one of the actions in `actions`, call `client.perform`.

Example arguments:

```json
{"client_id": "client:<uuid>"}
```

Example result:

```json
{
  "client": {
    "client_id": "client:<uuid>",
    "info": {
      "kind": "app",
      "platform": "macos",
      "name": "Rex.app",
      "version": "1.6.0",
      "label": "studio"
    },
    "principal": {
      "kind": "peer",
      "transport": "unix",
      "display": "uid 501",
      "uid": 501,
      "gid": 20
    },
    "connected_at": "2026-09-27T09:30:00Z",
    "connection_state": "connected",
    "session_ids": ["session:<uuid>"]
  },
  "actions": [
    {"name": "pane.split", "kind": "client", "title": "Split Pane"}
  ]
}
```

### `client.key`

Send a key to a block, giving Lua key bindings the first chance at it.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session the key is typed in.
- `key` (object, required): The key event, as the keyboard reported it.
  - `key` (string, required): The physical key, named after its W3C KeyboardEvent code in snake_case, such as key_a for KeyA, the A key on a US keyboard. It doesn't change with the keyboard layout. Any other key is unidentified. One of `unidentified`, `backquote`, `backslash`, `bracket_left`, `bracket_right`, `comma`, `digit_0`, `digit_1`, `digit_2`, `digit_3`, `digit_4`, `digit_5`, `digit_6`, `digit_7`, `digit_8`, `digit_9`, `equal`, `intl_backslash`, `intl_ro`, `intl_yen`, `key_a`, `key_b`, `key_c`, `key_d`, `key_e`, `key_f`, `key_g`, `key_h`, `key_i`, `key_j`, `key_k`, `key_l`, `key_m`, `key_n`, `key_o`, `key_p`, `key_q`, `key_r`, `key_s`, `key_t`, `key_u`, `key_v`, `key_w`, `key_x`, `key_y`, `key_z`, `minus`, `period`, `quote`, `semicolon`, `slash`, `alt_left`, `alt_right`, `backspace`, `caps_lock`, `context_menu`, `control_left`, `control_right`, `enter`, `meta_left`, `meta_right`, `shift_left`, `shift_right`, `space`, `tab`, `convert`, `kana_mode`, `non_convert`, `delete`, `end`, `help`, `home`, `insert`, `page_down`, `page_up`, `arrow_down`, `arrow_left`, `arrow_right`, `arrow_up`, `num_lock`, `numpad_0`, `numpad_1`, `numpad_2`, `numpad_3`, `numpad_4`, `numpad_5`, `numpad_6`, `numpad_7`, `numpad_8`, `numpad_9`, `numpad_add`, `numpad_backspace`, `numpad_clear`, `numpad_clear_entry`, `numpad_comma`, `numpad_decimal`, `numpad_divide`, `numpad_enter`, `numpad_equal`, `numpad_memory_add`, `numpad_memory_clear`, `numpad_memory_recall`, `numpad_memory_store`, `numpad_memory_subtract`, `numpad_multiply`, `numpad_paren_left`, `numpad_paren_right`, `numpad_subtract`, `numpad_separator`, `numpad_up`, `numpad_down`, `numpad_right`, `numpad_left`, `numpad_begin`, `numpad_home`, `numpad_end`, `numpad_insert`, `numpad_delete`, `numpad_page_up`, `numpad_page_down`, `escape`, `f1`, `f2`, `f3`, `f4`, `f5`, `f6`, `f7`, `f8`, `f9`, `f10`, `f11`, `f12`, `f13`, `f14`, `f15`, `f16`, `f17`, `f18`, `f19`, `f20`, `f21`, `f22`, `f23`, `f24`, `f25`, `fn`, `fn_lock`, `print_screen`, `scroll_lock`, `pause`, `browser_back`, `browser_favorites`, `browser_forward`, `browser_home`, `browser_refresh`, `browser_search`, `browser_stop`, `eject`, `launch_app_1`, `launch_app_2`, `launch_mail`, `media_play_pause`, `media_select`, `media_stop`, `media_track_next`, `media_track_previous`, `power`, `sleep`, `audio_volume_down`, `audio_volume_mute`, `audio_volume_up`, `wake_up`, `copy`, `cut`, or `paste`.
  - `mods` (string, required): The modifier keys held down, joined with + in this order: shift, ctrl, alt, super, caps_lock, num_lock, shift_side, ctrl_side, alt_side, super_side. On a Mac, super is the Command key. The lock names mean the lock is on, and a name ending in _side means the right-hand key of that pair is held. An empty string means none.
  - `consumed_mods` (string, required): The modifiers the keyboard layout used to produce text, such as shift for a capital letter. Written like mods, which also includes them.
  - `text` (string, required): The text the key typed. Empty for keys that type nothing, such as arrows and modifiers.
  - `unshifted_codepoint` (integer, required): The Unicode code point the key types without shift, such as 110 for n. Bindings such as ctrl+n match on it, so they follow the keyboard layout. 0 if none.
  - `composing` (boolean, required): True while the key is part of an unfinished composition, such as a dead key or an input method.
  - `action` (string, required): What happened to the key: press, release, or repeat while it's held down. One of `press`, `release`, or `repeat`.
- `block_id` (string): The block to send the key to. Defaults to the session's focused block.
- `prefix` (array of strings or null): The keys pressed before key when key ends a key sequence, in order. For ctrl+b then x, send x as key and ctrl+b here.

Result:

- `handled` (boolean, required): True if a Lua function in the client's script or the configuration file took the key. No block received it then.

Notes:

- The connection must be attached to `session_id`, with `session.attach`, `session.bootstrap`, or `session.attach_all`, or the call fails with `conflict`. If `session_id` names no session, the call fails with `session_not_found`.
- Without `block_id`, the key goes to the session's focused block. The server reads it once, before any Lua function runs, so a function that moves focus doesn't change where the key goes. Either way, Lua functions see the block the key goes to as `ctx.block_id`.
- On each connection, `client.key`, `client.action`, `block.event`, and the methods that change focus or the layout run one at a time, in the order they arrive. That keeps keys in the order you send them.
- The key goes first to the Lua functions bound to it in this client's scripts, then to those in the configuration file. If none of them takes the key, it goes to the block.
- Only a key bound to a Lua function reaches Lua, and only when it's pressed or repeating. Every other key goes straight to the block. `client.keymap` reports the bound keys as bindings for `client.key.forward`.
- If no Lua function takes the key and the session has no focused block, the key goes nowhere. The call still succeeds, with `handled` set to false.
- To send a key sequence, wait until it's complete. Then send its last key as `key` and the keys before it in `prefix`, such as `["ctrl+b"]`. The server doesn't remember keys between requests.
- A key with a `prefix` reaches only the functions bound to that exact sequence. A key without one reaches only the functions bound to that key alone.
- If no function takes a key sequence, only `key` goes on to the block. The keys in `prefix` never do.
- A Lua function bound to a key may run for 5 seconds. If it raises an error or runs longer, the call fails and the key doesn't reach the block.
- If an element of `prefix` isn't a key the server can read, the call fails with `bad_request`, and the message names the element.
- If no Lua function takes the key and `block_id` names a block that isn't in the session, the call fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "key": {
    "key": "key_g",
    "mods": "ctrl",
    "consumed_mods": "",
    "text": "",
    "unshifted_codepoint": 103,
    "composing": false,
    "action": "press"
  }
}
```

Example result:

```json
{"handled": true}
```

### `client.keymap`

Get what each key does for this client. The result combines the client's own bindings with the configuration file and the client's Lua scripts.

Arguments:

- `actions` (array of objects or null): The actions the client performs itself. Leave it out to skip the checks for unknown actions and wrong arguments. The server keeps the list for client.inspect and client.perform.
  - `name` (string, required): The name that bindings use for the action, such as pane.split. Names from the configuration file never contain a dot.
  - `kind` (string): What performs the action: client (the client itself), lua (a rex.action in the configuration file), or block (a block of the creator that the name starts with).
  - `title` (string): The name to show a person, such as Split Pane.
  - `description` (string): A sentence about what the action does, to show under its title in a menu or command palette.
  - `category` (string): A group of related actions, such as Layouts. Configuration file actions without one are filed under Actions.
  - `keywords` (string): More words to search for the action by, separated by spaces.
  - `args` (any): A JSON Schema of the arguments the action takes. Left out when the action doesn't describe any.
  - `repeats` (boolean): True if holding the key down should run the action again, as holding an arrow key does.
  - `source` (string): Where the action is defined, as file:line, such as init.lua:12. Left out when there's no location, as for every action a client declares.
- `server_actions` (string): Which of the server's actions the reply lists: all (the default), configured (only the configuration file's), or none. An unknown value means all.
- `defaults` (array of objects or null): The client's built-in layers, lowest first. They sit beneath the configuration file. Problems found only in them aren't reported, because the user can't edit them.
  - `name` (string, required): The layer's name, such as default or settings, which its bindings report as their source. Unique in the request, with no colon.
  - `bindings` (array of objects or null): What the layer binds, in the order it bound them. A later binding sits above an earlier one on the same key.
    - `keys` (array of strings or null, required): The keys that trigger the binding, such as cmd+k, or cmd+[KeyK] for a key by its position. A key sequence has one element per key, in the order pressed.
    - `action` (string, required): The name of the action to run.
    - `args` (any): The arguments to run the action with, as a JSON object, exactly as the user wrote them. Left out if there are none.
    - `source` (string): Where the binding came from: a layer name, such as default, or a file:line, such as init.lua:12. Ignored in a request.
    - `mode` (string): The mode the binding belongs to, such as resize. The binding applies only while that mode is active. Left out for a binding outside every mode.
  - `removals` (array of objects or null): What the layer takes off the layers beneath it. Removals never touch the layer's own bindings.
    - `keys` (array of strings or null, required): The keys to clear, spelled like a binding's keys. Only the same spelling matches. Empty, with mode set, to remove that whole mode.
    - `action` (string): The one action to take off the keys, such as pane.split. Leave it out to clear the keys whatever is bound to them.
    - `mode` (string): The mode to remove the keys from. Leave it out for keys outside every mode. With empty keys, it removes the whole mode.
- `overrides` (array of objects or null): Layers of shortcuts a person recorded in the client, lowest first. They sit above the configuration file and this client's scripts. Problems in them come back as warnings. It has the same fields as `defaults`.

Result:

- `bindings` (array of objects or null, required): The resolved bindings, in the order to try them. When several share a key, run the first one whose action is available. It has the same fields as `defaults.bindings` in the arguments.
- `diagnostics` (array of objects or null, required): Problems to show the user, such as a binding to an action that doesn't exist. Empty when there are none.
  - `severity` (string, required): How serious the problem is: warning, for something the user probably didn't mean, or error, for a part that was skipped. Show a value you don't know as a warning.
  - `source` (string): Where the problem is, as file:line, such as init.lua:22. Left out when it isn't known.
  - `message` (string, required): The problem in one sentence, ready to show. Its wording may change, so don't parse it.
  - `action` (string): The full name of the action the problem is about, as written, even if misspelled. Left out if it isn't about one action.
  - `key` (string): The key the problem is about, spelled like a binding's keys, with a sequence joined by spaces, such as ctrl+a b. Left out if it isn't about one key.
  - `mode` (string): The mode the problem is about. Left out if it isn't about a mode.
- `actions` (array of objects or null): The server's actions that keys can be bound to: the configuration file's, then each creator's, by creator name. Left out when there are none. It has the same fields as `actions` in the arguments.
- `modes` (array of objects or null): Every mode in the keymap, sorted by name. Left out when there are none. The client decides which mode is active.
  - `name` (string, required): The mode's name, such as resize. Its bindings have it as their mode, and client.mode.enter takes it as its name argument.
  - `exclusive` (boolean, required): True if keys the mode doesn't bind are ignored while it's active. If false, they do what they do outside the mode.
  - `blocked` (array of arrays of strings or nulls, or null): Keys that must not reach the bindings outside the mode while it's active. If the mode also binds one, that binding still runs. Each is spelled like a binding's keys.
- `config` (object, required): The configuration file the keymap came from. Present even when the file doesn't exist, so you can tell the user where to create it.
  - `path` (string, required): The absolute path of the configuration file, whether or not it exists.
  - `exists` (boolean, required): True if the file existed when the server last read it.
  - `generation` (integer, required): How many times the server has loaded its configuration file since it started, counting from 1. It goes up even when the file didn't change, so compare hash and instance_id, not this, to tell whether a keymap you kept is current.
  - `instance_id` (string, required): The ID of the server process, as server.status reports it. A new value means the server restarted, so send your client.set_script scripts again.
  - `hash` (string, required): The SHA-256 of the configuration file and the modules it requires while it loads, in lowercase hexadecimal. Empty if there's no file or it couldn't be read.

Notes:

- Bindings stack in layers, from bottom to top: your `defaults`, then the account's configuration file, then the scripts this client loaded with `client.set_script`, then your `overrides`.
- A layer's `name` becomes the `source` of its bindings and of warnings about it. If a name is empty, contains a colon, or is used by another layer in either list, the call fails with `bad_request`.
- The keymap is always the calling client's, so a request that includes `client_id` fails with `bad_request`. So does a top-level `bindings` list, which older servers accepted. Send those bindings as a layer in `defaults` instead.
- A key sequence such as ctrl+b followed by c is `["ctrl+b", "c"]` in `keys`. The configuration file writes it as `ctrl+b>c`, but the result always lists the keys separately. The server doesn't split an element such as `ctrl+b>c`. It treats it as a key it can't read.
- Keys the server can read come back in their standard spelling. `cmd+d` and `cmd+[KeyD]` are still different keys: the first follows the keyboard layout and the second names a physical key.
- A key spelling the server can't read is kept as written, in either list, so a newer client keeps its keys. Only a removal with the same spelling can take it off. A sequence with any such key is kept the same way.
- A sequence sits on the stack of its first key, in order among that key's other bindings. If the top binding a client can perform is a sequence, the client waits for the next key. The server keeps no state between keys, so waiting is the client's job.
- A key bound to a Lua function, in the configuration file or this client's scripts, comes back as a binding for `client.key.forward`. To perform it, send the key to `client.key`. Treat it as always available: the bindings below it on that key never run, even when no function takes the key.
- For a sequence bound to a function, send its last key to `client.key` with the earlier keys in `prefix`. The binding ends the stack only for that exact sequence, so other sequences that start with the same key still work.
- A key can't both run a binding of its own and start a sequence, so the higher of the two wins. The server keeps both and adds one warning per key, with the `source`, `action`, and `key` of the binding that can't run. It warns even about a binding in `defaults`, unless the one above it is in `defaults` too.
- A removal matches keys in full. Removing `["ctrl+b"]` leaves every sequence that starts with ctrl+b, and removing `["ctrl+b", "c"]` leaves ctrl+b.
- A removal that removes nothing gets a warning, unless it's in `defaults`, names a key the server can't read, or blocks a key in a mode.
- A binding or removal with a `mode` belongs to that mode, a set of bindings the client uses only while the mode is active. If a mode name contains anything but lowercase letters, digits, `_`, and `-`, or is `root`, the call fails with `bad_request`.
- Bindings in a mode stack and are checked like any others. A key in a mode never hides the same key outside it, and a removal affects only its own mode. The configuration file writes a key in a mode as `resize/left`.
- `modes` lists a mode if any layer binds a key in it, blocks a key in it, or declares it with `rex.mode`.
- A removal in a mode that names no `action` always blocks its key, so the key is listed in the mode's `blocked`, whether or not anything was removed. A removal that names an action never blocks.
- A removal with a `mode` and no `keys` removes the whole mode: its bindings, its blocked keys, and its declarations in the layers beneath. If it also names an `action`, the call fails with `bad_request`.
- Above a removed mode, a layer that binds, blocks, or declares a key in the mode brings the mode back, with only what that layer and the ones above it say. Otherwise the mode isn't in `modes`.
- The client decides which mode is active, with its own `client.mode.enter`, `client.mode.exit`, and `client.mode.exit_all` actions. The server keeps no mode state.
- The server warns about a `client.mode.enter` binding whose `name` argument isn't a mode in the keymap, and about a declared mode with nothing bound in it.
- An empty request returns the bindings of the configuration file and this client's scripts, without checking for unknown actions or wrong arguments.
- When `actions` isn't empty, the server adds its own actions before checking: those the configuration file defines with `rex.action`, every block method, and every block action. A binding to one of these is never reported as unknown.
- A binding with wrong arguments is kept, with a warning. A binding to a block method must pass arguments that match the method's input schema, and passing none counts as `{}`. A binding to a block action, or to a method with no input schema, must pass no arguments.
- A binding to an action from the configuration file must match the `args` schema the action declares, if it declares one. The client's own actions aren't checked, because the client checks their arguments itself.
- `actions` in the result lists the configuration file's actions in the order it defines them, then each creator's methods and block actions, sorted by creator and then by method name. It doesn't repeat the actions you sent.
- A block method in `actions` has no `title`, and its `args` is its input schema. Bind it, but don't show it in a menu. A block action has a `title` and no `args`.
- `server_actions` changes only what `actions` lists in the result, not which actions bindings are checked against.
- To show a diagnostic next to the binding it's about, match its `action`, `key`, and `mode` to the binding's.
- A client can keep using a stored result while its request is unchanged and `config.instance_id` and `config.hash` both match. Call this method again after every `client.set_script` call, and after every `keymap_changed` event whose `hash` differs from the stored one.

Example arguments:

```json
{
  "actions": [
    {"name": "pane.split", "kind": "client", "title": "Split Pane"},
    {"name": "pane.close", "kind": "client", "title": "Close Pane"}
  ],
  "defaults": [
    {"name": "default", "bindings": [
      {"keys": ["cmd+d"], "action": "pane.split", "args": {"direction": "right"}}
    ]}
  ],
  "overrides": [
    {"name": "settings",
     "bindings": [{"keys": ["cmd+e"], "action": "pane.split"}],
     "removals": [{"keys": ["cmd+d"], "action": "pane.split"}]}
  ]
}
```

Example result:

```json
{
  "bindings": [
    {"keys": ["cmd+e"], "action": "pane.split", "source": "settings"},
    {"keys": ["cmd+d"], "action": "pane.close", "source": "init.lua:14"}
  ],
  "diagnostics": [],
  "actions": [
    {"name": "dev_layout", "kind": "lua", "title": "Open Dev Layout",
     "category": "Actions", "source": "init.lua:3"},
    {"name": "com.superlogical.terminal.reset", "kind": "block",
     "title": "Reset Terminal", "category": "Pane"}
  ],
  "config": {
    "path": "/Users/alice/.config/rex/init.lua",
    "exists": true,
    "generation": 1,
    "instance_id": "0b6f2c1e-6d0a-4c4e-9a53-2f7c0d8e5b41",
    "hash": "a52ebcbcb5986024409b0550c7ac36eb59f4c40d3e2bd3de5ed2212c949b469b"
  }
}
```

### `client.list`

List the clients connected to the server, in the order they connected.

Arguments:

- `session_id` (string): List only the clients attached to this session with session.attach or session.bootstrap.
- `kinds` (array of strings or null): List only the clients of these kinds, such as app. A client that didn't report a kind matches none.

Result:

- `clients` (array of objects or null, required): The clients, in the order they first connected. An empty list, never null, when none match.
  - `client_id` (string, required): The client's ID. It stays the same when the client reconnects.
  - `info` (object): What the client says about itself, such as its name and version, for showing to people. Left out if it said nothing.
    - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
    - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
    - `name` (string): The program's name, such as Rex.app or rex. If it is longer than 256 bytes, the server keeps only the first 256.
    - `version` (string): The program's version, as the program prints it, such as 1.6.0. If it is longer than 256 bytes, the server keeps only the first 256.
    - `label` (string): The client's label, such as studio. Two clients can share a label.
  - `principal` (object, required): Who the client is, as the server identified it when it accepted the connection.
    - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
    - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
    - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
    - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
    - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
    - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
    - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
    - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
    - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
    - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
    - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
    - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
    - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
  - `connected_at` (string, required): When the client first connected, as an RFC 3339 timestamp. It doesn't change when the client reconnects.
  - `connection_state` (string): Whether the client has a connection: connected, or lost if it dropped and the client may still reconnect. Treat a missing or unknown value as connected.
  - `connection_lost_at` (string or null): When the server noticed the connection drop, as an RFC 3339 timestamp. Set only while connection_state is lost.
  - `session_ids` (array of strings or null): The sessions the client attached to with session.attach or session.bootstrap, sorted by ID. Left out if there are none.

Notes:

- With no arguments, the result lists every connected client, including short-lived ones such as the `rex` CLI.
- `session_id` counts only clients attached to that session with `session.attach` or `session.bootstrap`. A connection that only called `session.attach_all` doesn't count. Any client may use this filter, attached or not.
- In `kinds`, a kind this server doesn't know counts as `other`. A client that didn't report a kind matches no filter, not even `other`.
- If a client's connection drops without closing, the client stays in the list for up to 60 seconds, with `connection_state` set to `lost`, so it can reconnect. A client that wasn't attached to anything and had no scripts leaves at once.
- To keep a copy of this list up to date, call `session.attach_all` with `event_scope` set to `all`. The connection then receives `client_joined`, `client_left`, and `client_changed` events.

Example arguments:

```json
{"kinds": ["app"]}
```

Example result:

```json
{
  "clients": [
    {
      "client_id": "client:<uuid>",
      "info": {
        "kind": "app",
        "platform": "macos",
        "name": "Rex.app",
        "version": "1.6.0",
        "label": "studio"
      },
      "principal": {
        "kind": "peer",
        "transport": "unix",
        "display": "uid 501",
        "uid": 501,
        "gid": 20
      },
      "connected_at": "2026-09-27T09:30:00Z",
      "connection_state": "connected",
      "session_ids": ["session:<uuid>"]
    }
  ]
}
```

### `client.perform`

Ask another client to perform one of its actions and wait for its answer.

Arguments:

- `client_id` (string, required): The client to ask. To find one by its label, call target.resolve with kinds set to client.
- `action` (string, required): The name of an action the client declared, such as pane.split. client.inspect lists them. At most 256 bytes.
- `args` (any): The action's arguments, as a JSON object. Leave it out if there are none.
- `origin` (string): What caused the request, such as key, palette, api, or cli. The client receives it unchanged. Empty means api.
- `session_id` (string): The session the action applies to, if any. The client receives it without the server checking it.
- `block_id` (string): The block to act on instead of the client's focused block, if any. The client receives it without the server checking it.
- `timeout_ms` (integer): How long to wait for the answer, in milliseconds, once the client is sent the request. Defaults to 10000. Values over 30000 count as 30000.

Result:

- `result` (any): What the client's action returned. Left out if it returned null, as most actions do.
- `performed_by` (string, required): The client that performed the action. It is always the client that client_id named.

Notes:

- The server sends the named client a `client_perform_requested` event and waits for it to answer with `client.reply`.
- The server tells the named client which client asked, based on the connection the request arrived on, so a client can't claim to be another.
- The connection doesn't need to be attached to `session_id`, because the server only passes it on to the client.
- Requests to one client wait in line, and the client answers them one at a time in the order they arrived. `timeout_ms` counts from when a request reaches the front of the line.
- A request without `client_id` or `action`, with an `action` longer than 256 bytes, with a negative `timeout_ms`, or with `args` that aren't an object or don't match the action's schema fails with `bad_request`. If `client_id` names no connected client, the call fails with `not_found`.
- If the client has listed its actions and `action` isn't one of them, the call fails with `not_supported`. A client that listed none is asked anyway, and it may answer `not_supported` itself.
- A client that declines answers `refused`, and the call fails with that code and a message that says why.
- If the client is reconnecting, already has 16 requests waiting for it, or disconnects while the request waits, the call fails with `client_unavailable`.
- If the client doesn't answer in time, the call fails with `timeout`. The client isn't told, so it may still perform the action later.
- When a request ends, the server sends a `client_performed` event with the outcome to connections that called `session.attach_all` with `event_scope` set to `all`. A request that names no connected client sends no event.

Example arguments:

```json
{
  "client_id": "client:<uuid>",
  "action": "pane.split",
  "args": {"direction": "right"},
  "origin": "cli"
}
```

Example result:

```json
{"performed_by": "client:<uuid>"}
```

### `client.reply`

Answer a client_call_requested, client_perform_requested, or client_prompt_requested event.

Arguments:

- `call_id` (string, required): The call_id of the event you're answering.
- `result` (any): The call's result, what the action returned, or the prompt's answer. For a call or action with no output, use null. For a prompt, send a prompt result: text, ids, or cancelled. Set this or error, not both.
- `error` (object or null): Why the call failed, the action wasn't performed, or the prompt couldn't be shown. Set this or result, not both.
  - `code` (string, required): The kind of failure, as an error code such as not_found, refused, or internal.
  - `message` (string, required): What went wrong, written for people. In client.reply, a message longer than 4096 bytes is shortened and ends with an ellipsis.

Result:

An empty object, `{}`.

Notes:

- A `client_call_requested` event asks this client to make one call on another server, for a `client.action` request that is still running. Reply to every one, even when the call fails. Send the event's `call_id`, on the connection that received the event.
- A `client_perform_requested` event asks this client to perform one of its own actions for another client's `client.perform` request. Reply the same way. `result` is what the action returned, or `null`.
- A `client_prompt_requested` event asks this client to put a question to the person using it, for an action that called `rex.ui`. Reply once the person answers, with `result` set to `{"text": "..."}` for typed text, `{"ids": ["..."]}` for chosen items, or `{"cancelled": true}` if they closed the prompt. A result the prompt doesn't accept, such as an ID it didn't offer, fails with `bad_request` and leaves the prompt waiting. If you can't show the prompt, set `error.code` to `not_supported`.
- The server sends a prompt to every connected app that can show it, so the same prompt may be open on several devices. The first answer ends it on all of them, and so does a cancel. Every other app then receives a `client_prompt_ended` event with its own `call_id`. Close the prompt whatever the event's `reason` is. A reply after that fails with `not_found`.
- A reply that sets both `result` and `error`, or neither, fails with `bad_request`. For a call with no result, send `"result": null`.
- For a call that failed, use the code and message the other server returned, or explain why you couldn't make the call. The waiting action sees only the message.
- For an action you didn't perform, set `error.code` to `not_supported`, `refused`, or `internal`. The client that sent `client.perform` gets that code unchanged. An empty code is sent as `internal`.
- If `error.message` is longer than 4096 bytes, the server shortens it and ends it with an ellipsis (…). The reply is still accepted.
- A reply runs as soon as it arrives. It doesn't wait behind this connection's earlier `client.key` and `client.action` requests, because a `client.action` request that is waiting for the reply holds that line.
- A `call_id` that nothing is waiting for fails with `not_found`. That includes a call that was already answered and one that timed out.

Example arguments:

```json
{
  "call_id": "1",
  "result": {"revision": 7}
}
```

Example result:

```json
{}
```

### `client.set_label`

Set or remove this client's label.

Arguments:

- `label` (string, required): The label to set, such as studio. An empty string removes it. At most 64 bytes, with no control characters.

Result:

An empty object, `{}`.

Notes:

- The label always belongs to the client that sends the request. A request that includes `client_id` fails with `bad_request`.
- The server removes whitespace from both ends of `label` before it checks the label, so a label of only spaces removes the current one.
- A label longer than 64 bytes, or one that isn't valid UTF-8 or contains a control character, fails with `bad_request`. The message says which rule it breaks.
- Labels don't have to be unique. Two clients with the same label are told apart by their IDs.
- Labels are for people to read. The server never uses one to decide what a client may do.
- When the label changes, the server sends a `client_changed` event to connections that called `session.attach_all` with `event_scope` set to `all`.
- A client can also send a label when it connects, in the `client` field of its `hello` message. The same rules apply, but the server fixes the label instead of failing: it cuts a label longer than 64 bytes down to size and drops one that is otherwise invalid. It also shortens a `name` or `version` that is longer than 256 bytes.

Example arguments:

```json
{"label": "studio"}
```

Example result:

```json
{}
```

### `client.set_script`

Load Lua scripts for this client, replacing any it loaded before.

Arguments:

- `scripts` (array of objects or null, required): Every Lua source to load, run in order. They replace all the scripts loaded before. An empty list unloads them.
  - `name` (string): A file name to show in error messages and diagnostics, such as init.lua.
  - `source` (string, required): The Lua code.

Result:

- `bindings` (array of strings or null): The single keys the scripts bind to Lua functions. A key sent alone reaches the scripts only if it's listed here and pressed or repeating. Key sequences aren't listed.
- `all_keys` (boolean): Never sent by this server. If an older server sends true, send every key to client.key, not only those in bindings.
- `diagnostics` (array of objects or null): Problems in the scripts, written to show the user. The server skips each line it can't carry out and loads the rest.
  - `severity` (string, required): How serious the problem is: warning, for something the user probably didn't mean, or error, for a part that was skipped. Show a value you don't know as a warning.
  - `source` (string): Where the problem is, as file:line, such as init.lua:22. Left out when it isn't known.
  - `message` (string, required): The problem in one sentence, ready to show. Its wording may change, so don't parse it.
  - `action` (string): The full name of the action the problem is about, as written, even if misspelled. Left out if it isn't about one action.
  - `key` (string): The key the problem is about, spelled like a binding's keys, with a sequence joined by spaces, such as ctrl+a b. Left out if it isn't about one key.
  - `mode` (string): The mode the problem is about. Left out if it isn't about a mode.

Notes:

- The scripts belong to this client and apply in every session it's attached to. A request that includes `session_id` or `client_id` fails with `bad_request`.
- If a script has a syntax error, raises an error, or runs longer than 5 seconds while it loads, the call fails with `bad_request`. The scripts loaded before keep running.
- To see every key and key sequence the scripts bound to a function, call `client.keymap`. It reports each one as a binding for `client.key.forward`.
- The keys that reach the scripts are fixed when they load. A function that a script binds later, while it runs, receives a key only if a function was already bound to that key or sequence when the scripts loaded.
- A successful call can still return `diagnostics`, even with severity `error`. An error marks a part of a script the server skipped, such as a binding to a key it can't read. The rest of the scripts still load.
- Loading scripts changes this client's keymap without a `keymap_changed` event. Call `client.keymap` again after each call to this method.

Example arguments:

```json
{
  "scripts": [
    {
      "name": "init.lua",
      "source": "rex.bind(\"ctrl+g\", function(ctx, ev)\n  rex.session.set_label{ label = \"work\" }\n  return true\nend)\nrex.bind(\"ctrl+hoem\", \"pane.zoom\")"
    }
  ]
}
```

Example result:

```json
{
  "bindings": ["ctrl+g"],
  "diagnostics": [
    {"severity": "error", "source": "init.lua:5", "message": "unknown key \"hoem\""}
  ]
}
```

### `data.connect`

Get a one-time ticket for opening a data connection to a block. A data connection streams a block's content as it changes, such as a terminal's output.

Arguments:

- `session_id` (string, required): The session that holds the block. The connection must be attached to it with session.attach or session.bootstrap.
- `block_id` (string, required): The block that provides the data.
- `data_ref_id` (string, required): Which of the block's data sources to open, such as terminal.
- `subprotocol` (string): The subprotocol the data connection will use, such as rex.data.terminal.v1. If set, the ticket works only on a connection that uses it.

Result:

- `ticket` (string, required): A single-use ticket to send in the HELLO message that opens the data connection. Only a data connection from the same user can use it.
- `expires_in_ms` (integer, required): How long the ticket stays valid after the server issues it, in milliseconds.

Notes:

- The connection must be attached to `session_id` with `session.attach` or `session.bootstrap`. `session.attach_all` isn't enough. If the connection isn't attached, the call fails with `conflict`.
- To use the ticket, open a WebSocket to `/api/data` on the server and send the ticket in the HELLO message. A ticket works once, even if the handshake fails, and expires after `expires_in_ms`.
- If another user presents the ticket, the data connection gets REJECT reason 0x01, as it would for an unknown ticket. The ticket is then used up, so your own data connection can't use it either.
- The server checks only that `data_ref_id` is well formed. If the block has no data by that name, the server closes the data connection with WebSocket status 1008 after the handshake.
- Set `subprotocol` to the subprotocol the data connection will use, such as `rex.data.terminal.v1`. The server then refuses a data connection that uses a different one with REJECT reason 0x07, before any data is sent.
- If `subprotocol` is left out, the block checks the subprotocol after the handshake and closes the data connection with status 1008 if it doesn't support it.
- The call fails with `bad_request` if `data_ref_id` or `subprotocol` is malformed, and with `not_found` if the block isn't in the session or has no data to offer.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>",
  "data_ref_id": "terminal",
  "subprotocol": "rex.data.terminal.v1"
}
```

Example result:

```json
{
  "ticket": "AAECAwQFBgcICQoLDA0ODw",
  "expires_in_ms": 60000
}
```

### `focus_history.reverse`

Step back through the focus changes the calling principal made.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string): Only go back to a block in this session. The client must be attached to it.
- `not_session_id` (string): Only go back to a block in another session than this one.
- `window_id` (string): Only go back to a block in this window.
- `not_window_id` (string): Only go back to a block in another window than this one.
- `cursor` (integer): The cursor from an earlier result, to keep stepping back from where that call stopped. Leave it out to start from where focus is now.

Result:

- `reversed` (boolean, required): Whether the call moved focus. False means no place the request allows was left to go back to, and the other fields are left out.
- `cursor` (integer): Pass this as cursor in the next call to keep stepping back.
- `session_id` (string): The session that holds the block focus moved to. It may not be the session the client is showing.
- `window_id` (string): The window that holds the block focus moved to. It is that session's active window.
- `block_id` (string): The block focus moved to.

### `help.all`

Describe every method the server provides, including block methods.

Notes:

- The result has two lists. `methods` describes each method with its arguments, result, examples, and notes. `block_types` lists each creator whose blocks provide methods, with its flavors and those methods.
- The result describes the server you are connected to, so it always matches that server's version.
- Over HTTP, `GET /api/help` returns the same document with an added `http` field, and `/llms.txt` shows it as Markdown.

### `hosts.list`

List the server's hosts: the other Rex servers you connect to by label.

Arguments:

None.

Result:

- `hosts` (array of objects or null, required): Every host in the list, sorted by label, ignoring case, then by id. An empty list when there are no hosts.
  - `id` (string): The host's ID, such as host:<uuid>, chosen by the device that adds the host. If hosts.put leaves it out, the server creates one.
  - `label` (string, required): The name you connect to the host by, such as studio. Unique in the list, ignoring case, and at most 64 characters.
  - `endpoints` (array of strings or null, required): The addresses that reach the host, in the order to try them, such as studio.tail1234.ts.net or unix:///tmp/rex.sock. Each takes a form the -S option of rex accepts. A socket path works only on a device where the path exists, such as the local end of an ssh -L forward.
  - `icon` (string): The name of an icon for clients to show next to the label. Rex doesn't interpret it.
  - `host_id` (string): The host_id that the host's server.status reported when the host was added, if known. Compare it with a server's host_id to tell whether this host is that server.
  - `added_from` (string): The device or client that added the host. For display only.
  - `updated_at` (string): When the host last changed, as an RFC 3339 timestamp. The server sets it and ignores any value a client sends.
  - `source` (string): Where the configuration file declares the host, such as init.lua:12. Left out for a host added with hosts.put. The server sets it.
- `hash` (string, required): Identifies this version of the list. hosts.put, hosts.remove, and the hosts_changed event report the same hash for the same list.
- `diagnostics` (array of objects or null): Warnings about the hosts declared in the configuration file. Left out when there are none. The hash doesn't cover them.
  - `severity` (string, required): How serious the problem is: warning, for something the user probably didn't mean, or error, for a part that was skipped. Show a value you don't know as a warning.
  - `source` (string): Where the problem is, as file:line, such as init.lua:22. Left out when it isn't known.
  - `message` (string, required): The problem in one sentence, ready to show. Its wording may change, so don't parse it.
  - `action` (string): The full name of the action the problem is about, as written, even if misspelled. Left out if it isn't about one action.
  - `key` (string): The key the problem is about, spelled like a binding's keys, with a sequence joined by spaces, such as ctrl+a b. Left out if it isn't about one key.
  - `mode` (string): The mode the problem is about. Left out if it isn't about a mode.

Notes:

- Every Rex server keeps a host list. Each of your devices reads its hosts from the one server it uses as its home, so they all see the same list.
- The list holds the hosts declared with `rex.host` in the server's configuration file, and the hosts that clients added with `hosts.put`. A declared host has a `source`, such as `init.lua:4`. A host added with `hosts.put` has none.
- Each host comes back exactly as it was stored, including fields this server doesn't know. A client that sends a host back with `hosts.put` must keep those fields.
- A declared host with a mistake in it, or whose label another host already uses, is left out of `hosts`. A warning in `diagnostics` names the line that declared it.
- A server started without a state directory can't store hosts, so it lists only the declared ones.
- If a hand edit broke the file that stores the hosts, the call fails with `conflict` and a message that names the file.

Example result:

```json
{
  "hosts": [
    {
      "id": "host:0199abcd-1234-7abc-8def-0123456789ab",
      "label": "studio",
      "endpoints": ["studio.tail1234.ts.net", "192.168.1.20:7297"],
      "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10",
      "added_from": "laptop",
      "updated_at": "2026-10-03T12:00:00Z"
    },
    {
      "id": "host:6f1c1d4e-2b7a-5c39-8e0f-3a9b7c5d1e2f",
      "label": "attic",
      "endpoints": ["attic.example-tailnet.ts.net"],
      "source": "init.lua:4"
    }
  ],
  "hash": "9f2c4e1a0b3d5f7e9a1c3e5f7b9d1f3a5c7e9b1d3f5a7c9e1b3d5f7a9c1e3f5b",
  "diagnostics": [
    {
      "severity": "warning",
      "source": "init.lua:9",
      "message": "the label \"studio\" is already used by the host at studio.tail1234.ts.net, which was stored through the API"
    }
  ]
}
```

### `hosts.put`

Add a host to the server's host list, or replace the host with the same ID.

Arguments:

- `host` (object, required): The host to add, or to store in place of the host with the same id.
  - `id` (string): The host's ID, such as host:<uuid>, chosen by the device that adds the host. If hosts.put leaves it out, the server creates one.
  - `label` (string, required): The name you connect to the host by, such as studio. Unique in the list, ignoring case, and at most 64 characters.
  - `endpoints` (array of strings or null, required): The addresses that reach the host, in the order to try them, such as studio.tail1234.ts.net or unix:///tmp/rex.sock. Each takes a form the -S option of rex accepts. A socket path works only on a device where the path exists, such as the local end of an ssh -L forward.
  - `icon` (string): The name of an icon for clients to show next to the label. Rex doesn't interpret it.
  - `host_id` (string): The host_id that the host's server.status reported when the host was added, if known. Compare it with a server's host_id to tell whether this host is that server.
  - `added_from` (string): The device or client that added the host. For display only.
  - `updated_at` (string): When the host last changed, as an RFC 3339 timestamp. The server sets it and ignores any value a client sends.
  - `source` (string): Where the configuration file declares the host, such as init.lua:12. Left out for a host added with hosts.put. The server sets it.

Result:

- `host` (object, required): The host as the server stored it, with its id and updated_at. It has the same fields as `host` in the arguments.
- `hash` (string, required): Identifies the list after the call, the same way hosts.list does.

Notes:

- The device that adds a host chooses its `id`, so sending the same request again is safe and adds nothing. If `host` has no `id`, the server creates one.
- If `host` matches the stored host in every field except `updated_at`, nothing changes and the result holds the stored host.
- `label` can't be empty, can't start or end with white space, and has at most 64 characters. No other host in the list may have the same label, ignoring case. If one does, the call fails with `conflict` and a message that names that host and, for a declared host, the line that declared it.
- `endpoints` needs at least one endpoint, each in a form that the `-S` option of `rex` accepts. Unix sockets and named pipes aren't allowed, because their paths work only on their own machine.
- To change a declared host, put a host with its `id`. The server never writes the configuration file. It stores the host you send, which takes the place of the declared one. A put also brings back a host that `hosts.remove` removed.
- The server sets `updated_at` and drops `source`. It stores every other field as sent, including fields it doesn't know.
- When the put changes the list, every connected client receives a `hosts_changed` event.
- If `host` fails a check, the call fails with `bad_request` and a message that names the field. A server started without a state directory can't store hosts, and fails with `not_supported`. A hand edit that broke the file that stores the hosts makes the call fail with `conflict`.
- The file that stores the hosts can hold at most 1 MiB, which is room for thousands of hosts. A put that would make it larger fails with `too_large`.

Example arguments:

```json
{
  "host": {
    "id": "host:0199abcd-1234-7abc-8def-0123456789ab",
    "label": "studio",
    "endpoints": ["studio.tail1234.ts.net"],
    "added_from": "laptop"
  }
}
```

Example result:

```json
{
  "host": {
    "id": "host:0199abcd-1234-7abc-8def-0123456789ab",
    "label": "studio",
    "endpoints": ["studio.tail1234.ts.net"],
    "added_from": "laptop",
    "updated_at": "2026-10-03T12:00:00Z"
  },
  "hash": "9f2c4e1a0b3d5f7e9a1c3e5f7b9d1f3a5c7e9b1d3f5a7c9e1b3d5f7a9c1e3f5b"
}
```

### `hosts.remove`

Remove a host from the server's host list.

Arguments:

- `id` (string, required): The host to remove.

Result:

- `removed` (boolean, required): True if the host was in the list before the call and isn't now. False if there was nothing to remove.
- `hash` (string, required): Identifies the list after the call, the same way hosts.list does.

Notes:

- Removing a host that isn't in the list isn't an error. `removed` is false, nothing changes, and no event is sent, so calling it again is safe. The same happens if `id` is left out.
- The server never writes the configuration file, so removing a declared host hides it. Removing a stored host that took the place of a declared one removes both. Either way, only a later `hosts.put` with the same `id` brings the host back.
- When a host is removed, every connected client receives a `hosts_changed` event.
- If `id` isn't a valid host ID, the call fails with `bad_request`. A server started without a state directory can't store hosts, and fails with `not_supported`. A hand edit that broke the file that stores the hosts makes the call fail with `conflict`.

Example arguments:

```json
{"id": "host:0199abcd-1234-7abc-8def-0123456789ab"}
```

Example result:

```json
{
  "removed": true,
  "hash": "4b8d0f2a6c8e0a2c4e6a8c0e2a4c6e8a0c2e4a6c8e0a2c4e6a8c0e2a4c6e8a0c"
}
```

### `server.apple.grant_file_access`

Let the server, and every program it runs, read files a Mac app was given.

Arguments:

- `files` (array of objects or null, required): The files to make readable, in the order they were dropped or pasted. At most 256.
  - `path` (string, required): The file's absolute path. If it names a directory, access covers everything inside it.
  - `token` (string): A macOS sandbox extension token for path, which the app gets from sandbox_extension_issue_file. The server uses it only if it can't already open path.

Result:

- `files` (array of objects or null, required): One result for each requested file, in the same order as the request.
  - `path` (string, required): The requested path, unchanged.
  - `access` (string, required): 'readable' if the server could already open the file, 'granted' if it can now because it used the token, or 'denied' if it still can't. Treat a value you don't know as 'denied'.
  - `message` (string): Why access was denied, written for a log. The wording can change, so check access instead.

Notes:

- A Mac app calls this when a file is dropped on or pasted into a terminal. macOS lets the app open the file, but not always the server, which macOS treats as a different app.
- For each file, the server first tries to open `path`. It uses `token` only if it can't, so most files, such as those in a project, come back `readable` and their tokens go unused.
- A file the server can't open doesn't fail the call. A relative path is always `denied`.
- Access lasts until the server exits. If a file comes back `denied`, or with an `access` value the client doesn't know, copy the file somewhere the server can read instead.
- A request that names more than 256 files fails with `bad_request`.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example arguments:

```json
{
  "files": [
    {"path": "/Users/alice/project/README.md", "token": "<sandbox extension token>"},
    {"path": "/var/folders/xy/T/TemporaryItems/Screenshot.png", "token": "<sandbox extension token>"}
  ]
}
```

Example result:

```json
{
  "files": [
    {"path": "/Users/alice/project/README.md", "access": "readable"},
    {"path": "/var/folders/xy/T/TemporaryItems/Screenshot.png", "access": "granted"}
  ]
}
```

### `server.apple.privacy_status`

Report the macOS privacy permissions the server and its programs have.

Arguments:

None.

Result:

- `services` (array of objects or null, required): One entry for each privacy service the server knows. Empty when the server can't read privacy decisions, for example when it doesn't run on macOS.
  - `service` (string, required): The protected resource. Later versions of Rex may add services, so skip one you don't know.
  - `status` (string, required): 'granted' if the server may use the resource, 'denied' if the person turned it off or has not turned it on, 'not_determined' if the person has not been asked yet, or 'unknown' if the server could not tell. Treat a value you don't know as 'unknown'.
- `responsible_path` (string): The executable macOS holds responsible for the server. Privacy decisions belong to the app that contains it. Left out when the server can't tell.

Notes:

- macOS decides privacy permissions for the app it holds responsible for a process. Every shell and program the server runs belongs to the server, so they all get the permissions listed here, whatever the app that shows their terminals was allowed.
- Only the server can read its own permissions. A Mac app calls this to show them, and calls it again after the person changes one in System Settings.
- Reading the permissions never makes macOS ask the person anything.
- `responsible_path` is the executable macOS holds responsible for the server. The permissions belong to the app that contains it. When the server was started in the foreground from a terminal app, that is the terminal app.
- Skip a `service` you don't know, and treat a `status` you don't know as `unknown`. A server that isn't running on macOS lists no services.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example result:

```json
{
  "services": [
    {"service": "full_disk_access", "status": "granted"},
    {"service": "screen_recording", "status": "denied"},
    {"service": "microphone", "status": "not_determined"},
    {"service": "camera", "status": "not_determined"}
  ],
  "responsible_path": "/Users/alice/Library/Application Support/com.superlogical.rex/RexServer/Versions/Current/Rex Server.app/Contents/MacOS/rex"
}
```

### `server.config_reload`

Reload the configuration file and tell every client to fetch its keymap again.

Arguments:

None.

Result:

- `generation` (integer, required): How many times the server has loaded its configuration file since it started, counting this load. It goes up on every load, even one that fails.
- `diagnostics` (array of objects or null, required): Problems found in the configuration file, written for people to read. Empty when there are none.
  - `severity` (string, required): How serious the problem is: warning, for something the user probably didn't mean, or error, for a part that was skipped. Show a value you don't know as a warning.
  - `source` (string): Where the problem is, as file:line, such as init.lua:22. Left out when it isn't known.
  - `message` (string, required): The problem in one sentence, ready to show. Its wording may change, so don't parse it.
  - `action` (string): The full name of the action the problem is about, as written, even if misspelled. Left out if it isn't about one action.
  - `key` (string): The key the problem is about, spelled like a binding's keys, with a sequence joined by spaces, such as ctrl+a b. Left out if it isn't about one key.
  - `mode` (string): The mode the problem is about. Left out if it isn't about a mode.

Notes:

- The server reads the same configuration file it read when it started, usually `init.lua`, along with the modules that file loads with `require`.
- The next `client.keymap` result reports the same `generation` in `config.generation`, unless the file is reloaded again first.
- A mistake that stops the file from loading, such as a Lua syntax error, doesn't fail the call. The server keeps the bindings, actions, and hosts from the last load that worked, and `diagnostics` reports the mistake.
- If the file no longer exists, the reload removes the bindings, actions, and hosts it used to declare. That isn't an error.
- `diagnostics` lists only problems in the file itself. A problem that depends on a client, such as a binding to an action that client doesn't have, is reported by `client.keymap` instead.
- After the load, every connected client receives a `keymap_changed` event with the new `generation` and `hash`. A client can keep its keymap if its last `client.keymap` result has the same `hash` and came from the same server process, that is, the same `instance_id`.
- If the reload changes the host list, such as by adding or removing a `rex.host` declaration, every connected client also receives a `hosts_changed` event.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example result:

```json
{
  "generation": 2,
  "diagnostics": [
    {"severity": "error", "source": "init.lua:2", "message": "rex.bind: expected a string, got table"}
  ]
}
```

### `server.status`

Get the server's identity, session count, configuration, and Tailscale status.

Arguments:

None.

Result:

- `pid` (integer, required): The server's process ID.
- `instance_id` (string, required): A random ID for this run of the server. A new value means a new server process, even if pid is the same.
- `host_id` (string): Identifies the machine and account the server runs as. Servers that see the same files report the same value. Left out when the server has no state directory.
- `started_at` (string, required): When the server started, as an RFC 3339 timestamp.
- `exit_empty` (boolean, required): True if the server stops on its own soon after its last session and last connection close.
- `session_count` (integer, required): How many sessions the server has.
- `tailscale` (object): The status of built-in Tailscale. Present only when the server can run it.
  - `error` (string): Why built-in Tailscale turned off after its HTTPS listener failed. The next enable clears it.
  - `state` (string, required): 'disabled' (off), 'starting', 'needs_login' (see auth_url), 'needs_machine_approval' (a tailnet admin must approve the device), or 'running' (on the tailnet). Treat a value you don't know as 'starting'.
  - `dns_name` (string): The device's MagicDNS name, such as rex.tail1234.ts.net. Left out until known.
  - `auth_url` (string): The link to open to sign the device in to Tailscale. Present while state is needs_login, once Tailscale has made the link.
  - `account` (object): The Tailscale account that signed the device in. Left out until Tailscale reports it.
    - `login_name` (string, required): The Tailscale login name, usually an email address. For display only, because it can change.
  - `tailnet_name` (string): The tailnet's display name, or its domain if it has none. Left out until known.
  - `allow_tailnet_members` (boolean, required): True if anyone your tailnet's access rules allow may connect. False lets in only the Tailscale user who signed the device in.
  - `tags` (array of strings or null): The Tailscale tags the device has, such as tag:rex. A tagged device has no owner. Left out when it has none.
  - `https` (object): The state of the device's HTTPS listener. Left out while built-in Tailscale is off, and by older servers.
    - `state` (string, required): 'pending' until the HTTPS listener is ready, then 'available'. The server is reachable over the tailnet only when available. Treat a value you don't know as 'pending'.
    - `url` (string): The server's address on the tailnet, such as https://rex.tail1234.ts.net. Present once HTTPS is available and the device's name is known.
  - `control_url` (string): The control server the device uses, such as a Headscale server. Left out for Tailscale's own, while built-in Tailscale is off, and by older servers.
- `api_revision` (integer): The newest revision of the API the server supports. Left out when it is 0.
- `api_min_revision` (integer): The oldest revision of the API the server supports. Left out when it is 0.
- `config` (object, required): The configuration file the server loaded most recently. Always present, even when there is no file.
  - `generation` (integer, required): How many times the server has loaded its configuration file since it started, counting from 1. It goes up on every load, even one that fails.
  - `hash` (string, required): The SHA-256 of the configuration file and the modules it requires while it loads, in lowercase hexadecimal. Empty if there's no file or it couldn't be read.
- `listeners` (array of objects or null): Each endpoint the server accepts connections on, primary first. Servers older than API revision 12 leave it out. Doesn't include built-in Tailscale, which the tailscale member describes.
  - `endpoint` (string, required): The endpoint the listener was configured with, such as unix:///run/rex.sock, 127.0.0.1:7297, or tailnet://:7297.
  - `addresses` (array of strings or null): The addresses the listener is bound to, such as a socket path or 127.0.0.1:54321. They show the port that an endpoint like 127.0.0.1:0 got. Left out when the server can't tell.
  - `transport` (string, required): How the listener identifies its callers: 'unix', 'named_pipe', 'tcp', or 'tailscale'. Show a value you don't know as it is.
  - `admission` (string): Which callers the listener lets in: 'peer_credentials', 'anonymous', 'tailscale_owner', 'tailscale_identified', or 'login_server'. Left out when the server can't tell. Show a value you don't know as it is.
  - `primary` (boolean): True for the server's primary endpoint, where commands such as rex server status and rex server stop look for the server. Left out for the other listeners.

Notes:

- Two servers report the same `host_id` only when they see the same files, as two servers run by one user on one machine usually do. If the values differ, or either one is missing, assume the servers don't share files. The value means nothing else.
- `config` describes the last load of the configuration file. Its `generation` and `hash` match the `config` of a `client.keymap` result taken at the same moment.
- A client that saved a keymap can compare `config.hash` and `instance_id` with the values it saved. If either differs, call `client.keymap` again.
- Later changes to `tailscale` arrive as `tailscale_status_changed` events, which only connections that called `session.attach_all` or `session.bootstrap` receive.

Example result:

```json
{
  "pid": 1234,
  "instance_id": "7d7e9aca-9a92-41b1-b849-418e76d8f84b",
  "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10",
  "started_at": "2026-08-13T12:00:00Z",
  "exit_empty": false,
  "session_count": 2,
  "tailscale": {
    "state": "running",
    "dns_name": "rex.tail1234.ts.net",
    "account": {
      "login_name": "alice@example.com"
    },
    "tailnet_name": "Superlogical",
    "allow_tailnet_members": false,
    "https": {
      "state": "available",
      "url": "https://rex.tail1234.ts.net"
    }
  },
  "config": {
    "generation": 3,
    "hash": "a3f1c0de5b7e2a94c6d8f0e1b2a3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5"
  }
}
```

### `server.stop`

Shut the server down, or with if_idle, only if nothing is using it.

Arguments:

- `if_idle` (boolean): Stop only if the server has no sessions and no other client is connected. A server that is in use refuses with conflict and keeps running.

Result:

An empty object, `{}`.

Notes:

- Only a client on the server's own machine, connected over its Unix socket or named pipe, can call this method.
- Without `if_idle`, the server stops no matter what it is doing, and every program running in its sessions ends.
- With `if_idle`, the server stops only if it has no sessions, including any still being created or closed, and no client is connected except the caller. Otherwise it keeps running and the call fails with `conflict`. The error message says what is using the server.
- The server checks and stops in one step. A client that connects just after the check is refused.
- The server replies before it shuts down. Close the connection after reading the reply. The server waits up to a second for the connection to close before it stops.
- A server running inside another program, with no process of its own, fails the call with `not_supported`.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example arguments:

```json
{"if_idle": true}
```

Example result:

```json
{}
```

### `server.tailscale.disable`

Turn off built-in Tailscale, keeping its sign-in for the next enable.

Arguments:

None.

Result:

- `tailscale` (object, required): The status of built-in Tailscale after the call.
  - `error` (string): Why built-in Tailscale turned off after its HTTPS listener failed. The next enable clears it.
  - `state` (string, required): 'disabled' (off), 'starting', 'needs_login' (see auth_url), 'needs_machine_approval' (a tailnet admin must approve the device), or 'running' (on the tailnet). Treat a value you don't know as 'starting'.
  - `dns_name` (string): The device's MagicDNS name, such as rex.tail1234.ts.net. Left out until known.
  - `auth_url` (string): The link to open to sign the device in to Tailscale. Present while state is needs_login, once Tailscale has made the link.
  - `account` (object): The Tailscale account that signed the device in. Left out until Tailscale reports it.
    - `login_name` (string, required): The Tailscale login name, usually an email address. For display only, because it can change.
  - `tailnet_name` (string): The tailnet's display name, or its domain if it has none. Left out until known.
  - `allow_tailnet_members` (boolean, required): True if anyone your tailnet's access rules allow may connect. False lets in only the Tailscale user who signed the device in.
  - `tags` (array of strings or null): The Tailscale tags the device has, such as tag:rex. A tagged device has no owner. Left out when it has none.
  - `https` (object): The state of the device's HTTPS listener. Left out while built-in Tailscale is off, and by older servers.
    - `state` (string, required): 'pending' until the HTTPS listener is ready, then 'available'. The server is reachable over the tailnet only when available. Treat a value you don't know as 'pending'.
    - `url` (string): The server's address on the tailnet, such as https://rex.tail1234.ts.net. Present once HTTPS is available and the device's name is known.
  - `control_url` (string): The control server the device uses, such as a Headscale server. Left out for Tailscale's own, while built-in Tailscale is off, and by older servers.

Notes:

- Turning off built-in Tailscale closes every connection that came in over the tailnet. Sessions keep running.
- The device keeps its sign-in, so the next `server.tailscale.enable` reconnects without signing in. To sign out instead, call `server.tailscale.logout`.
- If built-in Tailscale is already off, the call changes nothing and returns the current status, so calling it again is safe.
- If the server can't run built-in Tailscale, the call fails with `not_supported`.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example result:

```json
{
  "tailscale": {
    "state": "disabled",
    "allow_tailnet_members": false
  }
}
```

### `server.tailscale.enable`

Turn on built-in Tailscale so devices on your tailnet can reach the server.

Arguments:

- `hostname` (string): The device name to use if this call starts the device, such as studio. If left out, the server uses its configured name, or rex.
- `allow_tailnet_members` (boolean, required): True lets in anyone your tailnet's access rules allow. False lets in only the Tailscale user who signed the device in. Applies on every call, even when the device is already on.
- `auth_key` (string): A Tailscale auth key that signs the device in without a browser. Used only when this call starts the device. Never logged or saved.
- `advertise_tags` (array of strings or null): Tailscale tags, such as tag:rex, for the device to sign in with. Used only when this call starts the device. Needs allow_tailnet_members set to true.
- `control_url` (string): The control server to sign in with if this call starts the device, such as https://headscale.example.com. If left out, the server uses its configured control server, or Tailscale's own.

Result:

- `tailscale` (object, required): The status of built-in Tailscale after the call.
  - `error` (string): Why built-in Tailscale turned off after its HTTPS listener failed. The next enable clears it.
  - `state` (string, required): 'disabled' (off), 'starting', 'needs_login' (see auth_url), 'needs_machine_approval' (a tailnet admin must approve the device), or 'running' (on the tailnet). Treat a value you don't know as 'starting'.
  - `dns_name` (string): The device's MagicDNS name, such as rex.tail1234.ts.net. Left out until known.
  - `auth_url` (string): The link to open to sign the device in to Tailscale. Present while state is needs_login, once Tailscale has made the link.
  - `account` (object): The Tailscale account that signed the device in. Left out until Tailscale reports it.
    - `login_name` (string, required): The Tailscale login name, usually an email address. For display only, because it can change.
  - `tailnet_name` (string): The tailnet's display name, or its domain if it has none. Left out until known.
  - `allow_tailnet_members` (boolean, required): True if anyone your tailnet's access rules allow may connect. False lets in only the Tailscale user who signed the device in.
  - `tags` (array of strings or null): The Tailscale tags the device has, such as tag:rex. A tagged device has no owner. Left out when it has none.
  - `https` (object): The state of the device's HTTPS listener. Left out while built-in Tailscale is off, and by older servers.
    - `state` (string, required): 'pending' until the HTTPS listener is ready, then 'available'. The server is reachable over the tailnet only when available. Treat a value you don't know as 'pending'.
    - `url` (string): The server's address on the tailnet, such as https://rex.tail1234.ts.net. Present once HTTPS is available and the device's name is known.
  - `control_url` (string): The control server the device uses, such as a Headscale server. Left out for Tailscale's own, while built-in Tailscale is off, and by older servers.

Notes:

- Built-in Tailscale is a copy of Tailscale inside Rex. It makes the server join your tailnet as a device of its own, so the machine doesn't need Tailscale installed.
- If built-in Tailscale is already on, the call only applies `allow_tailnet_members`, without restarting the device or dropping connections. It ignores `hostname`, `auth_key`, and `advertise_tags`.
- The first time the device starts, it must sign in to Tailscale. With a valid `auth_key`, it signs in without a browser. Otherwise `state` becomes `needs_login`, and the sign-in link appears in `auth_url` and in the server's log.
- The device keeps its sign-in, so later calls need no `auth_key`. The server never logs, saves, or returns the key.
- `advertise_tags` signs the device in as a tagged device, which has no owner, so it needs `allow_tailnet_members` set to true. The auth key or OAuth client that signs the device in must be allowed to grant the tags.
- The call returns once the device has started, before it has finished connecting. The server is reachable over the tailnet only once `https.state` is `available`. Follow the progress with `tailscale_status_changed` events, on a connection that called `session.attach_all` or `session.bootstrap`.
- If the HTTPS listener fails later, built-in Tailscale turns off: `state` becomes `disabled` and `error` says why.
- When the call starts the device, it fails with `bad_request` for a tag that isn't a valid Tailscale tag, and for tags with `allow_tailnet_members` set to false.
- While the server is shutting down, the call fails with `conflict`.
- If the server can't run built-in Tailscale, the call fails with `not_supported`.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example arguments:

```json
{
  "hostname": "studio",
  "allow_tailnet_members": false
}
```

Example result:

```json
{
  "tailscale": {
    "state": "starting",
    "allow_tailnet_members": false,
    "https": {
      "state": "pending"
    }
  }
}
```

### `server.tailscale.login`

Ask Tailscale for a sign-in link for the server's Tailscale device.

Arguments:

None.

Result:

- `tailscale` (object, required): The status of built-in Tailscale after the call. auth_url holds the sign-in link if it is ready.
  - `error` (string): Why built-in Tailscale turned off after its HTTPS listener failed. The next enable clears it.
  - `state` (string, required): 'disabled' (off), 'starting', 'needs_login' (see auth_url), 'needs_machine_approval' (a tailnet admin must approve the device), or 'running' (on the tailnet). Treat a value you don't know as 'starting'.
  - `dns_name` (string): The device's MagicDNS name, such as rex.tail1234.ts.net. Left out until known.
  - `auth_url` (string): The link to open to sign the device in to Tailscale. Present while state is needs_login, once Tailscale has made the link.
  - `account` (object): The Tailscale account that signed the device in. Left out until Tailscale reports it.
    - `login_name` (string, required): The Tailscale login name, usually an email address. For display only, because it can change.
  - `tailnet_name` (string): The tailnet's display name, or its domain if it has none. Left out until known.
  - `allow_tailnet_members` (boolean, required): True if anyone your tailnet's access rules allow may connect. False lets in only the Tailscale user who signed the device in.
  - `tags` (array of strings or null): The Tailscale tags the device has, such as tag:rex. A tagged device has no owner. Left out when it has none.
  - `https` (object): The state of the device's HTTPS listener. Left out while built-in Tailscale is off, and by older servers.
    - `state` (string, required): 'pending' until the HTTPS listener is ready, then 'available'. The server is reachable over the tailnet only when available. Treat a value you don't know as 'pending'.
    - `url` (string): The server's address on the tailnet, such as https://rex.tail1234.ts.net. Present once HTTPS is available and the device's name is known.
  - `control_url` (string): The control server the device uses, such as a Headscale server. Left out for Tailscale's own, while built-in Tailscale is off, and by older servers.

Notes:

- The call starts the sign-in and returns without waiting for the link. If `auth_url` is empty, the link arrives later in a `tailscale_status_changed` event, which only connections that called `session.attach_all` or `session.bootstrap` receive. `server.status` also shows it once it is ready.
- Calling it again while a sign-in is already under way is safe and doesn't start another one.
- If built-in Tailscale is off, the call fails with `conflict`. Turn it on with `server.tailscale.enable` first.
- If the request fails, or Tailscale doesn't accept it within 10 seconds, the call fails. The server keeps trying in the background.
- If the server can't run built-in Tailscale, the call fails with `not_supported`.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example result:

```json
{
  "tailscale": {
    "state": "needs_login",
    "auth_url": "https://login.tailscale.com/a/1a2b3c4d5e6f",
    "allow_tailnet_members": false,
    "https": {
      "state": "pending"
    }
  }
}
```

### `server.tailscale.logout`

Sign the server out of Tailscale and turn off built-in Tailscale.

Arguments:

None.

Result:

- `tailscale` (object, required): The status of built-in Tailscale after the call.
  - `error` (string): Why built-in Tailscale turned off after its HTTPS listener failed. The next enable clears it.
  - `state` (string, required): 'disabled' (off), 'starting', 'needs_login' (see auth_url), 'needs_machine_approval' (a tailnet admin must approve the device), or 'running' (on the tailnet). Treat a value you don't know as 'starting'.
  - `dns_name` (string): The device's MagicDNS name, such as rex.tail1234.ts.net. Left out until known.
  - `auth_url` (string): The link to open to sign the device in to Tailscale. Present while state is needs_login, once Tailscale has made the link.
  - `account` (object): The Tailscale account that signed the device in. Left out until Tailscale reports it.
    - `login_name` (string, required): The Tailscale login name, usually an email address. For display only, because it can change.
  - `tailnet_name` (string): The tailnet's display name, or its domain if it has none. Left out until known.
  - `allow_tailnet_members` (boolean, required): True if anyone your tailnet's access rules allow may connect. False lets in only the Tailscale user who signed the device in.
  - `tags` (array of strings or null): The Tailscale tags the device has, such as tag:rex. A tagged device has no owner. Left out when it has none.
  - `https` (object): The state of the device's HTTPS listener. Left out while built-in Tailscale is off, and by older servers.
    - `state` (string, required): 'pending' until the HTTPS listener is ready, then 'available'. The server is reachable over the tailnet only when available. Treat a value you don't know as 'pending'.
    - `url` (string): The server's address on the tailnet, such as https://rex.tail1234.ts.net. Present once HTTPS is available and the device's name is known.
  - `control_url` (string): The control server the device uses, such as a Headscale server. Left out for Tailscale's own, while built-in Tailscale is off, and by older servers.

Notes:

- After signing out, built-in Tailscale turns off. The next `server.tailscale.enable` must sign in again.
- If built-in Tailscale is off, the call changes nothing and the saved sign-in stays. Turn it on first to sign out.
- If signing out fails, or Tailscale doesn't answer within 10 seconds, the call fails and built-in Tailscale stays on, so you can try again.
- If the server can't run built-in Tailscale, the call fails with `not_supported`.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example result:

```json
{
  "tailscale": {
    "state": "disabled",
    "allow_tailnet_members": false
  }
}
```

### `server.tailscale.rename`

Change the server's Tailscale device name without signing in again.

Arguments:

- `hostname` (string, required): The new device name, such as studio: 1 to 63 ASCII letters, digits, and hyphens, not starting or ending with a hyphen.

Result:

- `tailscale` (object, required): The status of built-in Tailscale after the call. dns_name may still show the old name.
  - `error` (string): Why built-in Tailscale turned off after its HTTPS listener failed. The next enable clears it.
  - `state` (string, required): 'disabled' (off), 'starting', 'needs_login' (see auth_url), 'needs_machine_approval' (a tailnet admin must approve the device), or 'running' (on the tailnet). Treat a value you don't know as 'starting'.
  - `dns_name` (string): The device's MagicDNS name, such as rex.tail1234.ts.net. Left out until known.
  - `auth_url` (string): The link to open to sign the device in to Tailscale. Present while state is needs_login, once Tailscale has made the link.
  - `account` (object): The Tailscale account that signed the device in. Left out until Tailscale reports it.
    - `login_name` (string, required): The Tailscale login name, usually an email address. For display only, because it can change.
  - `tailnet_name` (string): The tailnet's display name, or its domain if it has none. Left out until known.
  - `allow_tailnet_members` (boolean, required): True if anyone your tailnet's access rules allow may connect. False lets in only the Tailscale user who signed the device in.
  - `tags` (array of strings or null): The Tailscale tags the device has, such as tag:rex. A tagged device has no owner. Left out when it has none.
  - `https` (object): The state of the device's HTTPS listener. Left out while built-in Tailscale is off, and by older servers.
    - `state` (string, required): 'pending' until the HTTPS listener is ready, then 'available'. The server is reachable over the tailnet only when available. Treat a value you don't know as 'pending'.
    - `url` (string): The server's address on the tailnet, such as https://rex.tail1234.ts.net. Present once HTTPS is available and the device's name is known.
  - `control_url` (string): The control server the device uses, such as a Headscale server. Left out for Tailscale's own, while built-in Tailscale is off, and by older servers.

Notes:

- The device keeps its identity and its sign-in. Until the server exits, a later `server.tailscale.enable` without a `hostname` uses the new name too.
- `dns_name` in the result may still show the old name. The new one arrives in a `tailscale_status_changed` event, on a connection that called `session.attach_all` or `session.bootstrap`.
- If built-in Tailscale is off, the call fails with `conflict`. To choose the name then, pass `hostname` to `server.tailscale.enable` instead.
- If `hostname` isn't a valid device name, the call fails with `bad_request`.
- If the server can't run built-in Tailscale, the call fails with `not_supported`.
- The server accepts this method only over its local Unix socket, or its named pipe on Windows. Over any other connection, such as TCP or Tailscale, the call fails with status 403.

Example arguments:

```json
{"hostname": "studio"}
```

Example result:

```json
{
  "tailscale": {
    "state": "running",
    "dns_name": "rex.tail1234.ts.net",
    "allow_tailnet_members": false,
    "https": {
      "state": "available",
      "url": "https://rex.tail1234.ts.net"
    }
  }
}
```

### `server.version`

Get the version of Rex that the server is running and how it was built.

Arguments:

None.

Result:

- `rex_version` (string, required): The version of Rex the server runs, as rex version prints it, such as 0.0.1780750825-g9a754d.
- `platform` (string, required): The operating system and CPU the server was built for, such as darwin_arm64 or linux_amd64.
- `commit` (string): The Git commit the server was built from. Left out when unknown.
- `built_at` (string or null): When the server was built, as an RFC 3339 timestamp. Most builds record the time of their Git commit. Left out when unknown.
- `dirty` (boolean, required): True if the server was built from source with uncommitted changes.
- `api_revision` (integer): The newest revision of the API the server supports. Left out when it is 0.
- `api_min_revision` (integer): The oldest revision of the API the server supports. Left out when it is 0.

Notes:

- A missing `api_revision` or `api_min_revision` means 0. Servers from before these fields existed leave both out.

Example result:

```json
{
  "rex_version": "0.0.1780750825-g9a754d",
  "platform": "darwin_arm64",
  "commit": "9a754d1234567890",
  "built_at": "2026-06-06T13:00:25Z",
  "dirty": false
}
```

### `server.whoami`

Get how the server identified you, and the account the server runs as.

Arguments:

None.

Result:

- `principal` (object, required): How the server identified you when your connection opened.
  - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
  - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
  - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
  - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
  - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
  - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
  - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
  - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
  - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
  - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
  - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
  - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
  - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
- `account` (string): The operating system account the server runs as. It can differ from your own name. Left out when unknown.

Notes:

- Which fields `principal` has depends on its `kind`. A `peer` has `uid` and `gid`, and usually `pid`. A `windows_peer` has `sid`, and usually `pid`. A `tailscale` client has `user_id`, `login_name`, and `node`, plus `tags` if its device is tagged. An `anonymous` client has none of these.
- The server identifies a client when its connection opens. `principal` stays the same for as long as the connection lasts.
- `principal` describes a decision the server already made when it let the client in. Use it to show who is connected, not to decide what a client may do.

Example result:

```json
{
  "principal": {
    "kind": "peer",
    "transport": "unix",
    "display": "uid 501",
    "uid": 501,
    "gid": 20,
    "pid": 4242
  },
  "account": "alice"
}
```

### `session.attach`

Attach to a session: start receiving its events and get its current view.

Arguments:

- `session_id` (string, required): The session to attach to.

Result:

- `client_id` (string, required): This connection's client ID. It's the same for every session the connection attaches to.
- `view` (object, required): The session's view, read after the connection started receiving its events. Apply it first, then the events whose seq is higher than the response's.
  - `revision` (integer, required): The view's revision. A larger revision is a newer view of the session, even across a server restart. It goes up by one with each change, and jumps ahead when a restarted server restores the session.
  - `session_id` (string or null, required): The session's ID.
  - `label` (string): The session's label.
  - `active_window_id` (string or null): The window the session shows. Left out when the session has no windows.
  - `windows` (array of objects or null): The session's windows, in order. Rex.app shows them as tabs in this order.
    - `window_id` (string or null, required): The window's ID.
    - `label` (string): The window's label.
    - `focused_block_id` (string or null): The window's focused block, which receives typing while the window is active. Left out if the window has none.
    - `zoomed_block_id` (string or null): The block zoomed to fill the window, if any. Layouts keep their normal sizes, so draw this block over the whole window.
    - `layers` (array of objects or null, required): The window's layers, from back to front. The first is the tiled layer, which holds the window's layout and fills the window.
      - `layer_id` (string or null, required): The layer's ID.
      - `kind` (string, required): How the layer is placed: 'tiled' for the layer that fills the window, or 'floating' for a floating layer. Draw a kind you don't know as 'floating'.
      - `bounds` (array of 4 numbers, required): Where the layer sits, as [x, y, w, h] fractions of the window's width and height.
      - `state` (string): How the layer is presented: 'minimized' means don't draw it, though its blocks keep running, and only a floating layer can be minimized. Left out for a normal layer. Draw a state you don't know as normal.
      - `layout` (object or null, required): How the layer divides its space between blocks, as a tree of splits. Null when the layer is empty.
        - `rect` (array of 4 numbers, required): Where this node sits, as [x, y, w, h] fractions of the window's width and height.
        - `block_id` (string or null): The block at this node. Set only when split isn't.
        - `split` (object or null): How this node divides in two. Set only when block_id isn't.
          - `split_id` (string or null, required): The split's ID. It stays the same while the split exists, so you can match splits across views.
          - `direction` (string, required): How the space is divided: 'horizontal' puts before on the left and after on the right, and 'vertical' puts before above after. Draw a direction you don't know as 'horizontal'. One of `horizontal` or `vertical`.
          - `ratio` (number, required): The fraction of the space that before gets, between 0 and 1.
          - `before` (object, same fields as `layout`, required): The node on the left or top.
          - `after` (object, same fields as `layout`, required): The node on the right or bottom.
      - `blocks` (array of objects or null, required): The blocks in the layer, in layout order. The layout field says where each one sits.
        - `block_id` (string or null, required): The block's ID.
        - `label` (string): The block's label.
        - `creator_name` (string): The creator that made the block, such as com.superlogical.terminal.
        - `flavor` (string): The flavor the block was made from, such as com.superlogical.terminal.shell.
        - `host_id` (string): Identifies the machine and account the block runs as. If it equals your local server's host_id, the block sees the same files you do. Otherwise treat the block as remote.
        - `owner` (string or null): The client that owns the block. Left out when the block has no owner.

Notes:

- The connection starts receiving every event from the session, including `block_event`, before the server reads `view`. Apply `view` first, then each event whose `seq` is higher than the response's `seq`, even one that arrived before the response.
- Most methods that take a `session_id` work only on a connection attached to that session, with this method, `session.bootstrap`, or `session.attach_all`. On a connection that isn't attached, they fail with `conflict`.
- Attaching makes the connection's client one of the session's clients. The server sends `client_connected`, and `session.list_clients` lists the client.
- `data.connect`, `session.own_block`, and `session.disown_block` need this kind of attachment. Attaching with `session.attach_all` isn't enough for them.
- Calling it again for the same session is safe. It returns the same `client_id` and a fresh `view`.
- If `session_id` names no session, the call fails with `session_not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>"
}
```

Example result:

```json
{
  "client_id": "client:<uuid>",
  "view": {
    "revision": 3,
    "session_id": "session:<uuid>",
    "label": "work",
    "active_window_id": "window:<uuid>",
    "windows": [
      {
        "window_id": "window:<uuid>",
        "label": "editor",
        "focused_block_id": "block:<uuid>",
        "layers": [
          {
            "layer_id": "layer:<uuid>",
            "kind": "tiled",
            "bounds": [0, 0, 1, 1],
            "layout": {
              "rect": [0, 0, 1, 1],
              "block_id": "block:<uuid>"
            },
            "blocks": [
              {
                "block_id": "block:<uuid>",
                "label": "shell",
                "creator_name": "com.superlogical.terminal",
                "flavor": "com.superlogical.terminal.shell",
                "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

### `session.attach_all`

Start receiving events from the whole server and get the list of sessions.

Arguments:

- `event_scope` (string, required): Which events to receive. 'session' sends every session's events except block_event, plus session_created, session_destroyed, session_moved, and tailscale_status_changed. 'all' also sends block_event and the client events, such as client_joined.

Result:

- `sessions` (array of objects or null, required): Every session on the server when this connection started receiving events.
  - `session_id` (string, required): The session's ID.
  - `label` (string, required): The session's label.

Notes:

- Use `session` unless you need `block_event` or the client events. With `all`, the client events are `client_joined`, `client_left`, `client_changed`, `client_performed`, and `client_script_events_dropped`. Any value other than `session` or `all` fails with `bad_request`.
- The connection still receives every event from a session it attached to with `session.attach`, whatever the scope.
- The connection doesn't join any session. It can call methods on every session, but it isn't listed in `session.list_clients` and can't call `data.connect` or own blocks. To join a session too, call `session.attach`, or use `session.bootstrap` to do both in one call.
- Calling it again replaces the scope with the new `event_scope`.
- The session list is a snapshot taken after events start. Apply each event whose `seq` is higher than the response message's `seq` on top of it.
- Events arrive in order, and their `seq` has no gaps. If the connection falls more than 8 MiB of events behind, the server closes it rather than skip events. Open a new connection to get a fresh snapshot.

Example arguments:

```json
{
  "event_scope": "session"
}
```

Example result:

```json
{
  "sessions": [
    {
      "session_id": "session:<uuid>",
      "label": "work"
    }
  ]
}
```

### `session.bootstrap`

Set up a new connection in one call: receive events, list sessions, and attach.

Arguments:

- `event_scope` (string, required): Which events to receive. 'session' sends every session's events except block_event, plus session_created, session_destroyed, session_moved, and tailscale_status_changed. 'all' also sends block_event and the client events, such as client_joined.
- `preferred_session_id` (string): The session to attach to, if it exists. Otherwise the server attaches the first session it can. Ignored when attach_session_ids is set.
- `attach_session_ids` (array of strings or null): The sessions to attach to, in order. Unknown and repeated IDs are skipped, and an empty list attaches none. Leave it out to let the server pick one.
- `data_tickets` (array of objects or null): The kinds of block to issue data tickets for. The response has a ticket for each visible block in an attached session that matches one. At most 8 entries.
  - `creator_name` (string, required): Issue tickets for blocks whose creator_name is this, such as com.superlogical.terminal. Required.
  - `data_ref_id` (string, required): Which of each block's data sources the ticket opens, such as terminal.
  - `subprotocol` (string): The subprotocol the data connection will use, such as rex.data.terminal.v1. If set, each ticket works only on a connection that uses it.

Result:

- `client_id` (string, required): This connection's client ID, the same one session.attach returns. Present even when no session is attached.
- `server_status` (object, required): The server's status, as server.status returns it.
  - `pid` (integer, required): The server's process ID.
  - `instance_id` (string, required): A random ID for this run of the server. A new value means a new server process, even if pid is the same.
  - `host_id` (string): Identifies the machine and account the server runs as. Servers that see the same files report the same value. Left out when the server has no state directory.
  - `started_at` (string, required): When the server started, as an RFC 3339 timestamp.
  - `exit_empty` (boolean, required): True if the server stops on its own soon after its last session and last connection close.
  - `session_count` (integer, required): How many sessions the server has.
  - `tailscale` (object): The status of built-in Tailscale. Present only when the server can run it.
    - `error` (string): Why built-in Tailscale turned off after its HTTPS listener failed. The next enable clears it.
    - `state` (string, required): 'disabled' (off), 'starting', 'needs_login' (see auth_url), 'needs_machine_approval' (a tailnet admin must approve the device), or 'running' (on the tailnet). Treat a value you don't know as 'starting'.
    - `dns_name` (string): The device's MagicDNS name, such as rex.tail1234.ts.net. Left out until known.
    - `auth_url` (string): The link to open to sign the device in to Tailscale. Present while state is needs_login, once Tailscale has made the link.
    - `account` (object): The Tailscale account that signed the device in. Left out until Tailscale reports it.
      - `login_name` (string, required): The Tailscale login name, usually an email address. For display only, because it can change.
    - `tailnet_name` (string): The tailnet's display name, or its domain if it has none. Left out until known.
    - `allow_tailnet_members` (boolean, required): True if anyone your tailnet's access rules allow may connect. False lets in only the Tailscale user who signed the device in.
    - `tags` (array of strings or null): The Tailscale tags the device has, such as tag:rex. A tagged device has no owner. Left out when it has none.
    - `https` (object): The state of the device's HTTPS listener. Left out while built-in Tailscale is off, and by older servers.
      - `state` (string, required): 'pending' until the HTTPS listener is ready, then 'available'. The server is reachable over the tailnet only when available. Treat a value you don't know as 'pending'.
      - `url` (string): The server's address on the tailnet, such as https://rex.tail1234.ts.net. Present once HTTPS is available and the device's name is known.
    - `control_url` (string): The control server the device uses, such as a Headscale server. Left out for Tailscale's own, while built-in Tailscale is off, and by older servers.
  - `api_revision` (integer): The newest revision of the API the server supports. Left out when it is 0.
  - `api_min_revision` (integer): The oldest revision of the API the server supports. Left out when it is 0.
  - `config` (object, required): The configuration file the server loaded most recently. Always present, even when there is no file.
    - `generation` (integer, required): How many times the server has loaded its configuration file since it started, counting from 1. It goes up on every load, even one that fails.
    - `hash` (string, required): The SHA-256 of the configuration file and the modules it requires while it loads, in lowercase hexadecimal. Empty if there's no file or it couldn't be read.
  - `listeners` (array of objects or null): Each endpoint the server accepts connections on, primary first. Servers older than API revision 12 leave it out. Doesn't include built-in Tailscale, which the tailscale member describes.
    - `endpoint` (string, required): The endpoint the listener was configured with, such as unix:///run/rex.sock, 127.0.0.1:7297, or tailnet://:7297.
    - `addresses` (array of strings or null): The addresses the listener is bound to, such as a socket path or 127.0.0.1:54321. They show the port that an endpoint like 127.0.0.1:0 got. Left out when the server can't tell.
    - `transport` (string, required): How the listener identifies its callers: 'unix', 'named_pipe', 'tcp', or 'tailscale'. Show a value you don't know as it is.
    - `admission` (string): Which callers the listener lets in: 'peer_credentials', 'anonymous', 'tailscale_owner', 'tailscale_identified', or 'login_server'. Left out when the server can't tell. Show a value you don't know as it is.
    - `primary` (boolean): True for the server's primary endpoint, where commands such as rex server status and rex server stop look for the server. Left out for the other listeners.
- `attach_all` (object, required): What session.attach_all returns: every session on the server when this connection started receiving events.
  - `sessions` (array of objects or null, required): Every session on the server when this connection started receiving events.
    - `session_id` (string, required): The session's ID.
    - `label` (string, required): The session's label.
- `selected_session` (object or null, required): The session the server chose and attached. Null if you sent attach_session_ids, or if no session could be attached.
  - `session` (object, required): The session's entry from attach_all.
    - `session_id` (string, required): The session's ID.
    - `label` (string, required): The session's label.
  - `view` (object, required): The session's view, read after the connection started receiving its events. Apply it first, then the events whose seq is higher than the response's.
    - `revision` (integer, required): The view's revision. A larger revision is a newer view of the session, even across a server restart. It goes up by one with each change, and jumps ahead when a restarted server restores the session.
    - `session_id` (string or null, required): The session's ID.
    - `label` (string): The session's label.
    - `active_window_id` (string or null): The window the session shows. Left out when the session has no windows.
    - `windows` (array of objects or null): The session's windows, in order. Rex.app shows them as tabs in this order.
      - `window_id` (string or null, required): The window's ID.
      - `label` (string): The window's label.
      - `focused_block_id` (string or null): The window's focused block, which receives typing while the window is active. Left out if the window has none.
      - `zoomed_block_id` (string or null): The block zoomed to fill the window, if any. Layouts keep their normal sizes, so draw this block over the whole window.
      - `layers` (array of objects or null, required): The window's layers, from back to front. The first is the tiled layer, which holds the window's layout and fills the window.
        - `layer_id` (string or null, required): The layer's ID.
        - `kind` (string, required): How the layer is placed: 'tiled' for the layer that fills the window, or 'floating' for a floating layer. Draw a kind you don't know as 'floating'.
        - `bounds` (array of 4 numbers, required): Where the layer sits, as [x, y, w, h] fractions of the window's width and height.
        - `state` (string): How the layer is presented: 'minimized' means don't draw it, though its blocks keep running, and only a floating layer can be minimized. Left out for a normal layer. Draw a state you don't know as normal.
        - `layout` (object or null, required): How the layer divides its space between blocks, as a tree of splits. Null when the layer is empty.
          - `rect` (array of 4 numbers, required): Where this node sits, as [x, y, w, h] fractions of the window's width and height.
          - `block_id` (string or null): The block at this node. Set only when split isn't.
          - `split` (object or null): How this node divides in two. Set only when block_id isn't.
            - `split_id` (string or null, required): The split's ID. It stays the same while the split exists, so you can match splits across views.
            - `direction` (string, required): How the space is divided: 'horizontal' puts before on the left and after on the right, and 'vertical' puts before above after. Draw a direction you don't know as 'horizontal'. One of `horizontal` or `vertical`.
            - `ratio` (number, required): The fraction of the space that before gets, between 0 and 1.
            - `before` (object, same fields as `layout`, required): The node on the left or top.
            - `after` (object, same fields as `layout`, required): The node on the right or bottom.
        - `blocks` (array of objects or null, required): The blocks in the layer, in layout order. The layout field says where each one sits.
          - `block_id` (string or null, required): The block's ID.
          - `label` (string): The block's label.
          - `creator_name` (string): The creator that made the block, such as com.superlogical.terminal.
          - `flavor` (string): The flavor the block was made from, such as com.superlogical.terminal.shell.
          - `host_id` (string): Identifies the machine and account the block runs as. If it equals your local server's host_id, the block sees the same files you do. Otherwise treat the block as remote.
          - `owner` (string or null): The client that owns the block. Left out when the block has no owner.
- `attached_sessions` (array of objects or null): The sessions attached from attach_session_ids, in the order requested. Sessions that don't exist are left out, and so may be sessions that are being destroyed.
  - `session` (object, required): The session's entry from attach_all.
    - `session_id` (string, required): The session's ID.
    - `label` (string, required): The session's label.
  - `view` (object, required): The session's view, read after the connection started receiving its events. Apply it first, then the events whose seq is higher than the response's. It has the same fields as `selected_session.view`.
- `data_tickets` (array of objects or null): A data ticket for each visible block in the returned views that matches an entry of the request's data_tickets. Call data.connect for a block that has none.
  - `session_id` (string, required): The session that holds the block.
  - `block_id` (string, required): The block the ticket opens.
  - `data_ref_id` (string, required): The data source the ticket opens.
  - `subprotocol` (string): The subprotocol the ticket is bound to. Left out if the request named none.
  - `ticket` (string, required): A single-use ticket to send in the HELLO message that opens the data connection. Only a data connection from the same user can use it.
  - `expires_in_ms` (integer, required): How long the ticket stays valid after the server issues it, in milliseconds.

Notes:

- Send it as the first request on a new connection. Apply its result first, then each event whose `seq` is higher than the response's `seq`, even one that arrived before the response.
- `event_scope` works as it does for `session.attach_all`, and the connection can then call methods on any session. A value other than `session` or `all` fails with `bad_request`.
- If `attach_session_ids` is left out, the server attaches one session and returns it in `selected_session`, which is null when the server has no sessions.
- If `attach_session_ids` is set, even to an empty array, the server returns the sessions it attached in `attached_sessions` and leaves `selected_session` null.
- A session in `attach_session_ids` that is destroyed during the call is left out of `attached_sessions`. If the session chosen for `selected_session` is destroyed during the call, the call fails with `session_not_found`. Any other failure to attach also fails the whole call.
- If the connection falls more than 8 MiB of events behind, the server closes it rather than skip events. Open a new connection and call `session.bootstrap` again.
- If the call fails, close the connection and open a new one before you try again. Part of the setup may already have taken effect.

Example arguments:

```json
{
  "event_scope": "session",
  "preferred_session_id": "session:<uuid>"
}
```

Example result:

```json
{
  "client_id": "client:<uuid>",
  "server_status": {
    "pid": 4242,
    "instance_id": "7d7e9aca-9a92-41b1-b849-418e76d8f84b",
    "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10",
    "started_at": "2026-10-17T09:00:00Z",
    "exit_empty": false,
    "session_count": 1,
    "config": {
      "generation": 1,
      "hash": "a3f1c0de5b7e2a94c6d8f0e1b2a3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5"
    }
  },
  "attach_all": {
    "sessions": [
      {
        "session_id": "session:<uuid>",
        "label": "work"
      }
    ]
  },
  "selected_session": {
    "session": {
      "session_id": "session:<uuid>",
      "label": "work"
    },
    "view": {
      "revision": 3,
      "session_id": "session:<uuid>",
      "label": "work",
      "active_window_id": "window:<uuid>",
      "windows": [
        {
          "window_id": "window:<uuid>",
          "label": "editor",
          "focused_block_id": "block:<uuid>",
          "layers": [
            {
              "layer_id": "layer:<uuid>",
              "kind": "tiled",
              "bounds": [0, 0, 1, 1],
              "layout": {
                "rect": [0, 0, 1, 1],
                "block_id": "block:<uuid>"
              },
              "blocks": [
                {
                  "block_id": "block:<uuid>",
                  "label": "shell",
                  "creator_name": "com.superlogical.terminal",
                  "flavor": "com.superlogical.terminal.shell",
                  "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10"
                }
              ]
            }
          ]
        }
      ]
    }
  }
}
```

### `session.close_layer`

Close a floating layer and every block in it.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `layer_id` (string or null, required): The floating layer to close. Every block in it closes too.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- `layer_id` must name a floating layer. Naming the window's layout, the layer of kind `tiled` in `session.view`, fails the call with `bad_request`. Close the window with `session.close_window` instead.
- The server closes every block in the layer, which ends the programs running in them. The call returns once the blocks have closed.
- If the layer had focus, focus returns to the layer that had it before, at the block last focused there. Otherwise focus doesn't change.
- If the layer held the window's last blocks, the server closes the window too.
- If the session doesn't exist, the call fails with `session_not_found`. If `layer_id` names no layer in the session, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "layer_id": "layer:<uuid>"
}
```

Example result:

```json
{"revision": 42}
```

### `session.close_window`

Close a window and every block in it.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `window_id` (string or null): The window to close. Leave it out to close the active window. Every block in it closes too.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The server closes every block in the window, which ends the programs running in them. The call returns once the blocks have closed.
- If the window was active, the window after it becomes active, or the one before it if it was last.
- If the session doesn't exist, the call fails with `session_not_found`. If `window_id` names a window that isn't in the session, it fails with `not_found`. A window leaves the session as soon as a close starts, so closing the same window twice also fails with `not_found`.
- If `window_id` is left out and the session has no windows, the call fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "window_id": "window:<uuid>"
}
```

Example result:

```json
{"revision": 42}
```

### `session.create`

Create a session, optionally with its first windows already in it.

Arguments:

- `label` (string): The session's label, up to 128 characters. Leave it out to let the server choose one, such as quiet-meadow.
- `initial_windows` (array of objects or null): Windows to create with the session, in order. The last one becomes the active window. If any of them fails, the server creates no session.
  - `window_label` (string): The window's label, up to 128 characters. Leave it out for a label made from the window's ID, such as Window 89abcdef.
  - `layout` (object or null): The blocks to create in the window and how to arrange them. Required. Each node holds either block, with a flavor and optional options and label, or split, with direction, before, after, and an optional ratio.

Result:

- `session_id` (string, required): The new session's ID.
- `initial_windows` (array of objects or null): The windows created with the session, in the order requested.
  - `window_id` (string, required): The new window's ID.
  - `block_ids` (array of strings or null, required): The new blocks' IDs, in layout order: each split's before side comes ahead of its after side.
  - `revision` (integer, required): The session's revision after the window was created.

Notes:

- Every argument is optional. With none, the server creates a session with no windows.
- If `label` is left out or empty, the server picks one that no other session has, such as `quiet-meadow`.
- The server sends `session_created` only once every window in `initial_windows` is ready, so no client sees a half-built session. If any window fails, the server closes the session and its blocks, and the call fails.
- The blocks in the initial windows start with no owner, because the calling client can't be attached to a session that doesn't exist yet. To own them, call `session.attach` and then `session.own_block`.
- The call doesn't attach the connection to the new session. Call `session.attach` to receive its events.
- If a layout is malformed, the call fails with `bad_request` before any block is created. A flavor that no creator provides fails with `not_found`.
- If the server already has as many sessions as it allows, the call fails with `conflict`.

Example arguments:

```json
{
  "label": "work",
  "initial_windows": [
    {
      "window_label": "editor",
      "layout": {
        "block": {
          "flavor": "com.superlogical.terminal.shell"
        }
      }
    }
  ]
}
```

Example result:

```json
{
  "session_id": "session:<uuid>",
  "initial_windows": [
    {
      "window_id": "window:<uuid>",
      "block_ids": ["block:<uuid>"],
      "revision": 1
    }
  ]
}
```

### `session.describe_block`

Get a block's details and the methods, events, and actions it provides.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_id` (string or null, required): The block to describe. It may be a detached block.

Result:

- `block` (object, required): The block, described as in session.list_blocks.
  - `block_id` (string or null, required): The block's ID.
  - `label` (string, required): The block's label.
  - `creator_name` (string): The creator that made the block, such as com.superlogical.terminal.
  - `flavor` (string): The flavor the block was made from, such as com.superlogical.terminal.shell.
  - `host_id` (string): Identifies the machine and account the block runs as. If it equals your local server's host_id, the block sees the same files you do. Otherwise treat the block as remote.
  - `placement` (string, required): Whether the block is in a window: 'placed' or 'detached'. Treat a value you don't know as 'detached'.
  - `window_id` (string or null): The window the block is in. Left out when the block is detached.
  - `owner` (string or null): The client that owns the block. Left out when the block has no owner.
- `api` (object): The methods and events the block provides.
  - `events` (object): The block events, by name. Each value is a JSON Schema for the event's fields.
  - `methods` (map of string to object): The block methods, by short name.
    - `description` (string): What the method does.
    - `input_schema` (any): A JSON Schema for the method's arguments.
    - `output_schema` (any): A JSON Schema for the method's result.
    - `actions` (array of objects or null): The actions that call this method, in the order the creator declares them.
      - `name` (string, required): The action's short name, such as reset. Its full name, used in key bindings, is the creator's name, a dot, and this name.
      - `title` (string, required): The name to show a person, such as Reset Terminal.
      - `description` (string): A sentence about what the action does, to show under its title in a menu or command palette.
      - `category` (string): A group of related actions, such as Pane.
      - `keywords` (string): More words to search for the action by, separated by spaces.
      - `args` (any): The fixed arguments the method is called with when the action runs. Left out when there are none.
      - `repeats` (boolean): True if holding the key down should run the action again, as holding an arrow key does.

Notes:

- `api` lists the methods and events the block provides, in the same form as `block_creator.list`. Call a method as `<creator>.<method>` with `session_id`, `block_id`, and its arguments under `args`.
- If `block_id` names a block that isn't in the session, or one that is closing, the call fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>"
}
```

Example result:

```json
{
  "block": {
    "block_id": "block:<uuid>",
    "label": "shell",
    "creator_name": "com.superlogical.terminal",
    "flavor": "com.superlogical.terminal.shell",
    "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10",
    "placement": "placed",
    "window_id": "window:<uuid>",
    "owner": "client:<uuid>"
  },
  "api": {
    "methods": {
      "title": {
        "description": "Get the terminal's title and the process that set it.",
        "input_schema": {"type": "object"},
        "output_schema": {
          "type": "object",
          "properties": {
            "title": {"type": "string"}
          }
        }
      }
    },
    "events": {
      "bell": {"type": "object"}
    }
  }
}
```

### `session.destroy`

Destroy a session and close every block in it.

Arguments:

- `session_id` (string, required): The session to destroy. Every block in it closes.

Result:

An empty object, `{}`.

Notes:

- The server closes every window and block in the session. Closing a terminal hangs up the program running in it, as closing a terminal window does.
- The call returns once the session has closed. `session_destroyed` is the last event about the session, though a `client_script_events_dropped` event for it can still follow.
- The connection must be attached to the session, with `session.attach`, `session.bootstrap`, or `session.attach_all`, or the call fails with `conflict`.
- If `session_id` names no session, the call fails with `session_not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>"
}
```

Example result:

```json
{}
```

### `session.detach`

Detach the connection from a session. The connection stays open and the session keeps running.

Arguments:

- `session_id` (string, required): The session to detach from.

Result:

An empty object, `{}`.

Notes:

- The connection's other attachments, and any events it receives through `session.attach_all`, continue as before.
- The client leaves the session. The server sends `client_disconnected`, and each block the client owned passes to the next client in line for it, if there is one.
- If the connection isn't attached to the session, the call succeeds and changes nothing, even if the session doesn't exist.
- A `session.attach` for the same session that is still in progress isn't canceled. This call succeeds without detaching, and the attach completes.
- If the connection attaches to the session again later, its client keeps the same `client_id`.

Example arguments:

```json
{
  "session_id": "session:<uuid>"
}
```

Example result:

```json
{}
```

### `session.detach_block`

Take a block out of its window without closing it. The block keeps running.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_id` (string or null, required): The block to detach. It keeps running but leaves its window.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- `session.list_blocks` lists the block with `placement` set to `detached`, and `session.move_block` places it in a window again.
- Detaching a block that is already detached succeeds and changes nothing.
- If the block was the last one in its window, the window closes too. If it was the only block in a floating layer, the layer closes.
- If the block had focus, another block in its window gets focus.
- To close the block instead, call `block.close`.
- If `block_id` names a block that isn't in the session, the call fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>"
}
```

Example result:

```json
{"revision": 42}
```

### `session.disown_block`

Give up the calling client's claim on blocks. The result lists each block's owner.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_ids` (array of strings or nulls, or null): The blocks to give up. Leave it out or empty to give up every block in the session.

Result:

- `blocks` (array of objects or null, required): Each block and its owner after this call.
  - `block_id` (string or null, required): The block's ID.
  - `owner` (string or null): The client that owns the block. Left out when the block has no owner.

Notes:

- The calling client must be attached to the session, with `session.attach` or `session.bootstrap`. A connection that only called `session.attach_all` fails with `bad_request`.
- A block the caller owned passes to the next connected client in line for it, or is left with no owner. For a block the caller doesn't own, the caller only leaves the line.
- `blocks` lists each block's owner after the call, in the order of `block_ids`, with a repeated ID listed once. When you give up every block, they are sorted by `block_id`.
- Every change of owner sends `block_owner_changed`. If a block in a window changes owner, the call also sends one `session_view_changed`.
- If any ID in `block_ids` isn't a block in the session, the call fails with `bad_request` and changes nothing.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_ids": ["block:<uuid>"]
}
```

Example result:

```json
{
  "blocks": [
    {
      "block_id": "block:<uuid>",
      "owner": "client:<uuid>"
    }
  ]
}
```

### `session.float_block`

Move a block out of its window's layout into a new floating layer.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_id` (string or null, required): The block to float. It must be in its window's layout, not already in a floating layer.
- `bounds` (array of 4 numbers or object, required): Where the new floating layer sits, as [x, y, w, h] fractions of the window's width and height. It must fit inside the window.
  - `x` (number, required): The left edge, as a fraction of the window's width.
  - `y` (number, required): The top edge, as a fraction of the window's height.
  - `w` (number, required): The width, as a fraction of the window's width.
  - `h` (number, required): The height, as a fraction of the window's height.

Result:

- `layer_id` (string or null, required): The new floating layer's ID.
- `revision` (integer, required): The session's revision after this call.

Notes:

- `bounds` is measured from the window's top-left corner. Its width and height must be greater than 0.
- The new layer is in the block's own window, in front of every other floating layer there.
- Focus and the active window don't change. If the window was zoomed, the zoom ends.
- Floating the last block of a window's layout leaves the layout empty. The window stays open, and its layer of kind `tiled` in `session.view` keeps the same `layer_id`.
- If the session doesn't exist, the call fails with `session_not_found`. If `block_id` names a block that isn't in any window, including a detached block, it fails with `not_found`, and bad `bounds` fail with `bad_request`.
- If the block is already in a floating layer, the call fails with `bad_request`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>",
  "bounds": [0.1, 0.1, 0.6, 0.5]
}
```

Example result:

```json
{
  "layer_id": "layer:<uuid>",
  "revision": 42
}
```

### `session.focus_block`

Focus a block, and make its window the active window if it isn't.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_id` (string or null, required): The block to focus. It must be in a window, which becomes the active window.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The block can be in the window's layout or in a floating layer. If it isn't already its window's focused block, any zoom in that window ends.
- If the block already has focus in the active window, the call changes nothing.
- If the session doesn't exist, the call fails with `session_not_found`. If `block_id` names a block that isn't in any window, including a detached block, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>"
}
```

Example result:

```json
{"revision": 42}
```

### `session.focus_direction`

Move focus to the nearest block to the left, right, above, or below.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `direction` (string, required): Which way to move focus from the focused block in the active window. One of `left`, `right`, `up`, or `down`.
- `wrap` (boolean): Whether to continue from the opposite edge when no block lies that way. Defaults to false. Older servers ignore it and always wrap.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The search starts at the focused block of the active window. It covers every block in that window, floating layers included, by where each appears on screen. When two blocks are equally close, the one in front wins.
- If no block lies in `direction`, focus doesn't change and the call still succeeds. Moving focus ends any zoom in the window.
- If the session doesn't exist, the call fails with `session_not_found`. If the session has no windows, it fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "direction": "right",
  "wrap": true
}
```

Example result:

```json
{"revision": 42}
```

### `session.focus_next_pane`

Move focus to the next block in the active window.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `wrap` (boolean): Whether to go from the last block back to the first. Defaults to false.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- Blocks are in layout order: the window's layout first, reading each split's `before` side ahead of its `after` side, then each floating layer from back to front.
- If there's no next block, focus doesn't change and the call still succeeds. Moving focus ends any zoom in the window.
- If the session doesn't exist, the call fails with `session_not_found`. If the session has no windows, it fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "wrap": true
}
```

Example result:

```json
{"revision": 42}
```

### `session.focus_next_window`

Make the next window the active window.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `wrap` (boolean): Whether to go from the last window back to the first. Defaults to false.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The window order is the order of `windows` in `session.view`.
- Each window keeps its own focused block, which then receives typing.
- If there's no next window, nothing changes and the call still succeeds.
- If the session doesn't exist, the call fails with `session_not_found`. If the session has no windows, it fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "wrap": true
}
```

Example result:

```json
{"revision": 42}
```

### `session.focus_previous_pane`

Move focus to the previous block in the active window.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `wrap` (boolean): Whether to go from the first block back to the last. Defaults to false.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- Blocks are in layout order: the window's layout first, reading each split's `before` side ahead of its `after` side, then each floating layer from back to front.
- If there's no previous block, focus doesn't change and the call still succeeds. Moving focus ends any zoom in the window.
- If the session doesn't exist, the call fails with `session_not_found`. If the session has no windows, it fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "wrap": true
}
```

Example result:

```json
{"revision": 42}
```

### `session.focus_previous_window`

Make the previous window the active window.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `wrap` (boolean): Whether to go from the first window back to the last. Defaults to false.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The window order is the order of `windows` in `session.view`.
- Each window keeps its own focused block, which then receives typing.
- If there's no previous window, nothing changes and the call still succeeds.
- If the session doesn't exist, the call fails with `session_not_found`. If the session has no windows, it fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "wrap": true
}
```

Example result:

```json
{"revision": 42}
```

### `session.focus_window`

Make a window the active window. Focus goes to the block it last had focused.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `window_id` (string or null, required): The window to make active. Focus goes to the block that was focused in it.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- If the window is already active, the call changes nothing.
- If the session doesn't exist, the call fails with `session_not_found`. If `window_id` names a window that isn't in the session, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "window_id": "window:<uuid>"
}
```

Example result:

```json
{"revision": 42}
```

### `session.help`

Describe the methods this session accepts and the methods its blocks provide.

Arguments:

- `session_id` (string, required): The session to act on.

Notes:

- The result has the same form as `help.all`, with an added `http` field. It describes only the methods the session answers itself, such as `session.view` and `session.new_split`, and the block methods of the creators that session can use.
- Most clients call `help.all` instead, which describes every method the server provides.

Example arguments:

```json
{
  "session_id": "session:<uuid>"
}
```

### `session.list`

List the sessions on the server.

Arguments:

None.

Result:

- `sessions` (array of objects or null, required): Every session on the server. New sessions go at the end, and session.move changes the order.
  - `session_id` (string, required): The session's ID.
  - `label` (string, required): The session's label.

Notes:

- Sessions are listed in the order they were created, until `session.move` changes that order. `session.attach_all` and `session.bootstrap` list them in the same order.

Example result:

```json
{
  "sessions": [
    {
      "session_id": "session:<uuid>",
      "label": "work"
    }
  ]
}
```

### `session.list_blocks`

List every block in the session, including detached blocks.

Arguments:

- `session_id` (string, required): The session to act on.

Result:

- `blocks` (array of objects or null, required): Every block in the session, including detached blocks.
  - `block_id` (string or null, required): The block's ID.
  - `label` (string, required): The block's label.
  - `creator_name` (string): The creator that made the block, such as com.superlogical.terminal.
  - `flavor` (string): The flavor the block was made from, such as com.superlogical.terminal.shell.
  - `host_id` (string): Identifies the machine and account the block runs as. If it equals your local server's host_id, the block sees the same files you do. Otherwise treat the block as remote.
  - `placement` (string, required): Whether the block is in a window: 'placed' or 'detached'. Treat a value you don't know as 'detached'.
  - `window_id` (string or null): The window the block is in. Left out when the block is detached.
  - `owner` (string or null): The client that owns the block. Left out when the block has no owner.

Notes:

- Treat a `placement` you don't recognize like `detached`. The block exists and you can call its methods, but don't assume where it is shown.
- Blocks are sorted by `block_id`.
- To see where each block sits in its window and which one has focus, call `session.view`.

Example arguments:

```json
{
  "session_id": "session:<uuid>"
}
```

Example result:

```json
{
  "blocks": [
    {
      "block_id": "block:01234567-89ab-cdef-0123-456789abcde1",
      "label": "shell",
      "creator_name": "com.superlogical.terminal",
      "flavor": "com.superlogical.terminal.shell",
      "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10",
      "placement": "placed",
      "window_id": "window:<uuid>",
      "owner": "client:<uuid>"
    },
    {
      "block_id": "block:01234567-89ab-cdef-0123-456789abcde2",
      "label": "logs",
      "creator_name": "com.superlogical.terminal",
      "flavor": "com.superlogical.terminal.shell",
      "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10",
      "placement": "detached"
    }
  ]
}
```

### `session.list_clients`

List the clients attached to the session.

Arguments:

- `session_id` (string, required): The session to act on.

Result:

- `clients` (array of objects or null, required): The clients attached to the session, in the order they attached.
  - `client_id` (string or null, required): The client's ID.
  - `principal` (object, required): Who the client is, as the server identified it when it accepted the connection.
    - `kind` (string, required): How the server identified the client: 'peer' (Unix socket credentials), 'windows_peer' (named pipe credentials), 'tailscale' (a Tailscale login), or 'anonymous' (not identified). Show a kind you don't know by its name.
    - `transport` (string, required): How the client first reached Rex: 'unix', 'named_pipe', 'tcp', or 'tailscale'. A login server passes it on unchanged.
    - `display` (string, required): A readable name for the client, such as uid 501 or alice@example.com. It can be out of date or shared, so compare uid, sid, or user_id instead.
    - `remote_addr` (string): The client's network address. Left out when there is none, such as on a Unix socket.
    - `uid` (integer or null): The client's Unix user ID. Only for kind peer.
    - `gid` (integer or null): The client's Unix group ID. Only for kind peer.
    - `pid` (integer or null): The client's process ID when it connected, for kind peer or windows_peer, if known. Process IDs are reused, so use it only for debugging.
    - `sid` (string): The client's Windows security identifier, which survives account renames. Only for kind windows_peer.
    - `user_id` (string): The Tailscale user ID that owns the client's device. Only for kind tailscale. Check tags before treating it as a person.
    - `login_name` (string): The client's Tailscale login name, usually an email address. For display only.
    - `node` (string): The name of the client's Tailscale device. Only for kind tailscale.
    - `tags` (array of strings or null): The Tailscale tags of the client's device. Any tag means the client is a machine, such as a CI runner, not a person.
    - `path` (array of strings or null): The route the client took to this server, for troubleshooting. Entries include 'tailscale' (built-in Tailscale), 'tailnet' and 'tailnet-serve' (the machine's own Tailscale), and 'login-server' (passed on by a login server).
  - `info` (object): What the client says about itself, such as its name and version, for showing to people. Left out if it said nothing.
    - `kind` (string): What sort of program the client is: app, cli, agent, or other. Left out if the client didn't say. Treat a kind you don't know as other.
    - `platform` (string): What the client runs on: macos, ios, linux, windows, freebsd, web, or other. Left out if the client didn't say. Treat a platform you don't know as other.
    - `name` (string): The program's name, such as Rex.app or rex. If it is longer than 256 bytes, the server keeps only the first 256.
    - `version` (string): The program's version, as the program prints it, such as 1.6.0. If it is longer than 256 bytes, the server keeps only the first 256.
    - `label` (string): The client's label, such as studio. Two clients can share a label.
  - `connected_at` (string, required): When the client attached to this session, as an RFC 3339 timestamp.
  - `connection_state` (string): Whether the client has a connection: connected, or lost if it dropped and the client may still reconnect. Treat a missing or unknown value as connected.
  - `connection_lost_at` (string or null): When the server noticed the connection drop, as an RFC 3339 timestamp. Set only while connection_state is lost. A client that doesn't reconnect within 60 seconds leaves the session.

Notes:

- Only clients attached with `session.attach` or `session.bootstrap` are listed. A connection that only called `session.attach_all` isn't. To list every connected client, call `client.list`.

Example arguments:

```json
{
  "session_id": "session:<uuid>"
}
```

Example result:

```json
{
  "clients": [
    {
      "client_id": "client:<uuid>",
      "principal": {
        "kind": "peer",
        "transport": "unix",
        "display": "uid 501",
        "uid": 501,
        "gid": 20
      },
      "info": {
        "kind": "app",
        "platform": "macos",
        "name": "Rex.app",
        "version": "1.6.0",
        "label": "studio"
      },
      "connected_at": "2026-09-27T09:30:00Z",
      "connection_state": "connected"
    }
  ]
}
```

### `session.lower_layer`

Move a floating layer to the back, or just below another floating layer.

Arguments:

- `session_id` (string, required): The session to act on.
- `layer_id` (string or null, required): The floating layer to move back.
- `relative_layer_id` (string or null): Another floating layer in the same window to place it just below. Leave it out to move it behind every other floating layer.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- `layer_id` must name a floating layer. Naming the window's layout, the layer of kind `tiled` in `session.view`, fails the call with `bad_request`.
- A floating layer never goes behind the window's layout. At the back, it sits behind every other floating layer and in front of the layout.
- If the layer is already in place, the call changes nothing. Focus and the layer's bounds never change.
- If the session doesn't exist, the call fails with `session_not_found`. If `layer_id` names no layer in the session, it fails with `not_found`, and if `relative_layer_id` is the same as `layer_id`, it fails with `bad_request`.
- If `relative_layer_id` names a layer of kind `tiled`, or a layer in another window, the call fails with `bad_request`. If it names no layer in the session, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "layer_id": "layer:<uuid>"
}
```

Example result:

```json
{"revision": 42}
```

### `session.move`

Move a session to another place in the order that session.list returns.

Arguments:

- `session_id` (string, required): The session to move.
- `before_session_id` (string or null): The session to place it just before. Leave it out to move the session to the end.

Result:

An empty object, `{}`.

Notes:

- The order is the same for every client. Each move that changes it sends `session_moved` with the same `session_id` and `before_session_id`.
- Moving a session to where it already is succeeds and changes nothing.
- The connection must be attached to the session it moves, with `session.attach`, `session.bootstrap`, or `session.attach_all`, or the call fails with `conflict`.
- If `before_session_id` names the session being moved, the call fails with `bad_request`.
- If either ID names no session, the call fails with `session_not_found`, and the message says which one.

Example arguments:

```json
{
  "session_id": "session:01234567-89ab-cdef-0123-456789abcde1",
  "before_session_id": "session:01234567-89ab-cdef-0123-456789abcde2"
}
```

Example result:

```json
{}
```

### `session.move_block`

Move a block, or place a detached one, in a new split beside another block.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_id` (string or null, required): The block to move. It may be detached or placed in any window.
- `anchor_block_id` (string or null, required): The block to place it beside. It must be placed in a window.
- `direction` (string or null): How to divide the anchor's space: 'horizontal' for side by side or 'vertical' for one above the other. Defaults to horizontal. One of `horizontal` or `vertical`.
- `side` (string or null): Where the moved block goes: 'before' for the left or top part, or 'after' for the right or bottom part. Defaults to after. One of `before` or `after`.
- `ratio` (number): The fraction of the space that the before part gets, greater than 0 and less than 1. Defaults to 0.5.

Result:

- `created_split_id` (string or null, required): The split created beside the anchor.
- `removed_split_id` (string or null): The split that held the block before the move, which the move removed. Left out if no split was removed, such as for a detached block.
- `revision` (integer, required): The session's revision after this call.

Notes:

- The block and the anchor can be in different windows or layers.
- If the block had focus in its window, focus moves with it and its new window becomes active. A detached block doesn't take focus.
- Zoom ends in every window the move changes.
- If the move empties a floating layer, the server removes it. If it empties a whole window, the server closes the window. A window's layout that empties while the window still has floating layers stays, with the same `layer_id`.
- Apart from the two splits named in the result, every split keeps its ID.
- If the session doesn't exist, the call fails with `session_not_found`. If `block_id` and `anchor_block_id` are the same, or `ratio` is out of range, it fails with `bad_request`. If either block isn't in the session, or the anchor isn't in a window, it fails with `not_found`.
- If the block is detached or in another window, and the anchor's window already has as many blocks as it allows, the call fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:01234567-89ab-cdef-0123-456789abcde1",
  "anchor_block_id": "block:01234567-89ab-cdef-0123-456789abcde2",
  "direction": "vertical"
}
```

Example result:

```json
{
  "created_split_id": "split:01234567-89ab-cdef-0123-456789abcde1",
  "removed_split_id": "split:01234567-89ab-cdef-0123-456789abcde2",
  "revision": 42
}
```

### `session.move_window`

Move a window to another place in the session's window order.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `window_id` (string or null, required): The window to move.
- `before_window_id` (string or null): The window to place it just before. Leave it out to move the window to the end.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The window order is the order of `windows` in `session.view` and of the tabs in Rex.app. `session.focus_next_window` and `session.focus_previous_window` follow it.
- The active window, focus, and the windows' blocks don't change. If the window is already in place, the call changes nothing.
- If the session doesn't exist, the call fails with `session_not_found`. If either window isn't in the session, it fails with `not_found`, and if `before_window_id` is the same as `window_id`, it fails with `bad_request`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "window_id": "window:01234567-89ab-cdef-0123-456789abcde1",
  "before_window_id": "window:01234567-89ab-cdef-0123-456789abcde2"
}
```

Example result:

```json
{"revision": 42}
```

### `session.new_block`

Create a block without placing it in a window. Place it later with session.move_block.

Arguments:

- `session_id` (string, required): The session to act on.
- `flavor` (string, required): The flavor of block to create, such as com.superlogical.terminal.shell. block_creator.list lists them.
- `options` (any): Settings for the block's creator, in the shape of the flavor's options_schema from block_creator.list.
- `label` (string): The block's label, up to 128 characters. Defaults to the flavor's short name, such as shell.

Result:

- `block_id` (string or null, required): The new block's ID. The block is detached until you place it with session.move_block.
- `revision` (integer, required): The session's revision. Creating a detached block doesn't change it.

Notes:

- The server passes `options` to the flavor's creator unchanged.
- The block starts detached: it runs, sends events, and answers method calls, but no window shows it. `session.move_block` can place it beside a block that is in a window.
- If the calling client is attached to the session, it becomes the block's owner. Otherwise the block has no owner until an attached client claims it with `session.own_block`.
- A malformed `flavor` or `label` fails with `bad_request`. A flavor that no creator provides fails with `not_found`.
- If the server already has as many blocks as it allows, the call fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "flavor": "com.superlogical.terminal.shell",
  "label": "logs"
}
```

Example result:

```json
{
  "block_id": "block:<uuid>",
  "revision": 7
}
```

### `session.new_layer`

Create blocks in a new floating layer at the front of a window.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `window_id` (string or null): The window to add the floating layer to. Leave it out to use the active window.
- `bounds` (array of 4 numbers or object, required): Where the new floating layer sits, as [x, y, w, h] fractions of the window's width and height. It must fit inside the window.
  - `x` (number, required): The left edge, as a fraction of the window's width.
  - `y` (number, required): The top edge, as a fraction of the window's height.
  - `w` (number, required): The width, as a fraction of the window's width.
  - `h` (number, required): The height, as a fraction of the window's height.
- `layout` (object or null, required): The blocks to create in the floating layer and how to arrange them. Each node holds either block, with a flavor and optional options and label, or split, with direction, before, after, and an optional ratio.
- `focus` (boolean): Whether to make the window active and focus the first new block. Defaults to false.

Result:

- `layer_id` (string or null, required): The new floating layer's ID.
- `block_ids` (array of strings or nulls, or null, required): The new blocks' IDs, in layout order: each split's before side comes ahead of its after side.
- `revision` (integer, required): The session's revision after this call.

Notes:

- `bounds` is measured from the window's top-left corner. Its width and height must be greater than 0.
- A `block` node in `layout` starts one new block from a `flavor`, such as `com.superlogical.terminal.shell`. A `split` node divides its space in two and holds another node in each of `before` and `after`.
- In a `split` node, `direction` is `horizontal` to put `before` on the left of `after`, or `vertical` to put it on top. `ratio` is the fraction of the space that `before` gets, greater than 0 and less than 1. It defaults to 0.5.
- `layout` always starts new blocks. To move a block that is already in a window's layout into a floating layer, use `session.float_block`.
- The new layer starts in front of every other floating layer in the window.
- Either the whole layout is created or none of it is. If a block fails to start, the server closes the blocks it already started, and the call fails.
- If the session doesn't exist, the call fails with `session_not_found`. If `window_id` names a window that isn't in the session, or no creator provides a `flavor`, it fails with `not_found`. Bad `bounds`, or a malformed `layout` or `flavor`, fail with `bad_request`.
- If `window_id` is left out and the session has no windows, the call fails with `conflict`.
- If the call would go past a limit on blocks per window or blocks on the server, it fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "bounds": [0.1, 0.1, 0.8, 0.6],
  "layout": {
    "block": {
      "flavor": "com.superlogical.terminal.shell",
      "label": "shell"
    }
  },
  "focus": true
}
```

Example result:

```json
{
  "layer_id": "layer:<uuid>",
  "block_ids": ["block:<uuid>"],
  "revision": 42
}
```

### `session.new_split`

Create blocks in a new split beside an existing block or split.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `anchor_id` (string or null): The block or split whose space to split. A block ID splits that one block. A split ID puts the new blocks beside that split and everything under it. Leave it out to split the focused block of window_id, or of the active window. Don't send it with window_id.
- `window_id` (string or null): The window whose focused block to split. Send it only when anchor_id is left out.
- `direction` (string, required): How to divide the anchor's space: 'horizontal' for side by side or 'vertical' for one above the other. One of `horizontal` or `vertical`.
- `side` (string, required): Where the new blocks go: 'before' for the left or top part, or 'after' for the right or bottom part. One of `before` or `after`.
- `ratio` (number): The fraction of the space that the before part gets, greater than 0 and less than 1. Defaults to 0.5.
- `layout` (object or null, required): The blocks to create beside the anchor and how to arrange them. Each node holds either block, with a flavor and optional options and label, or split, with direction, before, after, and an optional ratio.
- `focus` (boolean): Whether to make the anchor's window active and focus the first new block. Defaults to false.

Result:

- `anchor_id` (string or null, required): The block or split whose space was split. If the request didn't name one, this is the focused block the server used.
- `block_ids` (array of strings or nulls, or null, required): The new blocks' IDs, in layout order: each split's before side comes ahead of its after side.
- `split_ids` (array of strings or nulls, or null, required): The IDs of the splits this call created: first the one at the anchor, then the new layout's own splits in layout order.
- `revision` (integer, required): The session's revision after this call.

Notes:

- The server picks the block to split before it starts the new blocks, so a focus change while they start doesn't move the split.
- A `block` node in `layout` starts one new block from a `flavor`, such as `com.superlogical.terminal.shell`. A `split` node divides its space in two and holds another node in each of `before` and `after`.
- In a `split` node, `direction` is `horizontal` to put `before` on the left of `after`, or `vertical` to put it on top. `ratio` is the fraction of the space that `before` gets, greater than 0 and less than 1. It defaults to 0.5.
- Any zoom in the window ends, whether or not `focus` is set.
- If `anchor_id` is a split ID, the new split takes that split's place. The old split, with everything under it, goes on one side and `layout` on the other. Anchoring a layer's top split makes the new blocks span the whole layer. A layer with one block has no split, so anchor its block instead.
- Splits that already existed keep their IDs.
- Either the whole layout is created or none of it is. If a block fails to start, the server closes the blocks it already started, and the call fails.
- If the session doesn't exist, the call fails with `session_not_found`. If `window_id` names a window that isn't in the session, or no creator provides a `flavor`, it fails with `not_found`. Sending both `anchor_id` and `window_id`, a bad `ratio`, or a malformed `layout` or `flavor` fail with `bad_request`.
- If `anchor_id` names a block that isn't in a window, including a detached block, or a split that isn't in any window, the call fails with `not_found`. If both `anchor_id` and `window_id` are left out and the session has no windows, it fails with `conflict`.
- If the call would go past a limit on blocks per window or blocks on the server, it fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "anchor_id": "block:01234567-89ab-cdef-0123-456789abcde1",
  "direction": "horizontal",
  "side": "after",
  "layout": {
    "block": {
      "flavor": "com.superlogical.terminal.shell",
      "label": "logs"
    }
  },
  "focus": true
}
```

Example result:

```json
{
  "anchor_id": "block:01234567-89ab-cdef-0123-456789abcde1",
  "block_ids": ["block:01234567-89ab-cdef-0123-456789abcde2"],
  "split_ids": ["split:<uuid>"],
  "revision": 42
}
```

### `session.new_window`

Create a window and the blocks in its layout. It becomes the active window by default.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `window_label` (string): The window's label, up to 128 characters. Leave it out for a label made from the window's ID, such as Window 89abcdef.
- `layout` (object or null): The blocks to create in the window and how to arrange them. Required. Each node holds either block, with a flavor and optional options and label, or split, with direction, before, after, and an optional ratio.
- `focus` (boolean or null): Whether to make the new window the active window. Defaults to true. A session with no active window always makes the new one active.

Result:

- `window_id` (string or null, required): The new window's ID.
- `block_ids` (array of strings or nulls, or null, required): The new blocks' IDs, in layout order: each split's before side comes ahead of its after side.
- `revision` (integer, required): The session's revision after the window was created.

Notes:

- A `block` node in `layout` starts one new block from a `flavor`, such as `com.superlogical.terminal.shell`. A `split` node divides its space in two and holds another node in each of `before` and `after`.
- In a `split` node, `direction` is `horizontal` to put `before` on the left of `after`, or `vertical` to put it on top. `ratio` is the fraction of the space that `before` gets, greater than 0 and less than 1. It defaults to 0.5.
- `layout` always starts new blocks. To place a detached block that already exists, use `session.move_block`.
- In the new window, the first block in `block_ids` has focus.
- Either the whole layout is created or none of it is. If a block fails to start, the server closes the blocks it already started, and the call fails.
- If the session doesn't exist, the call fails with `session_not_found`. If `layout` is missing or malformed, or a `flavor` is malformed, it fails with `bad_request`, and if no creator provides a `flavor`, it fails with `not_found`.
- If the call would go past a limit on windows per session, blocks per window, or blocks on the server, it fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "window_label": "server",
  "layout": {
    "split": {
      "direction": "horizontal",
      "before": {
        "block": {
          "flavor": "com.superlogical.terminal.shell",
          "label": "shell"
        }
      },
      "after": {
        "block": {
          "flavor": "com.superlogical.terminal.shell",
          "label": "logs"
        }
      }
    }
  }
}
```

Example result:

```json
{
  "window_id": "window:<uuid>",
  "block_ids": [
    "block:01234567-89ab-cdef-0123-456789abcde1",
    "block:01234567-89ab-cdef-0123-456789abcde2"
  ],
  "revision": 42
}
```

### `session.own_block`

Claim blocks for the calling client. The result lists each block's owner.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_ids` (array of strings or nulls, or null): The blocks to claim. Leave it out or empty to claim every block in the session.
- `mode` (string): How to claim them. 'take' makes the calling client the owner right away. 'request' makes it the owner only of blocks that have none, and it may get the rest when their owner gives them up or leaves. Defaults to take.

Result:

- `blocks` (array of objects or null, required): Each claimed block and its owner after this call.
  - `block_id` (string or null, required): The block's ID.
  - `owner` (string or null): The client that owns the block. Left out when the block has no owner.

Notes:

- When the clients attached to a session want different things for a block, such as a terminal's size, the block follows its owner. Owning a block gives no other rights over it.
- The calling client must be attached to the session, with `session.attach` or `session.bootstrap`. A connection that only called `session.attach_all` fails with `bad_request`.
- Either mode puts the caller in line for the block. When the owner gives it up, detaches, or loses its connection, the block passes to the connected client in line that claimed it most recently, with any `take` ahead of every `request`.
- An owner whose connection drops gets each of its blocks back when it reconnects, unless that block's owner has changed again since.
- `blocks` lists each block's owner after the call, in the order of `block_ids`, with a repeated ID listed once. When you claim every block, they are sorted by `block_id`.
- Every change of owner sends `block_owner_changed`. If a block in a window changes owner, the call also sends one `session_view_changed`, because `session.view` shows owners.
- A `mode` other than `take` or `request` fails with `bad_request`.
- If any ID in `block_ids` isn't a block in the session, the call fails with `bad_request` and changes nothing.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_ids": ["block:<uuid>"],
  "mode": "request"
}
```

Example result:

```json
{
  "blocks": [
    {
      "block_id": "block:<uuid>",
      "owner": "client:<uuid>"
    }
  ]
}
```

### `session.raise_layer`

Move a floating layer to the front, or just above another floating layer.

Arguments:

- `session_id` (string, required): The session to act on.
- `layer_id` (string or null, required): The floating layer to move forward.
- `relative_layer_id` (string or null): Another floating layer in the same window to place it just above. Leave it out to move it in front of every other floating layer.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- `layer_id` must name a floating layer. Naming the window's layout, the layer of kind `tiled` in `session.view`, fails the call with `bad_request`.
- If the layer is already in place, the call changes nothing. Focus and the layer's bounds never change.
- If the session doesn't exist, the call fails with `session_not_found`. If `layer_id` names no layer in the session, it fails with `not_found`, and if `relative_layer_id` is the same as `layer_id`, it fails with `bad_request`.
- If `relative_layer_id` names a layer of kind `tiled`, or a layer in another window, the call fails with `bad_request`. If it names no layer in the session, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "layer_id": "layer:<uuid>"
}
```

Example result:

```json
{"revision": 42}
```

### `session.resize_layer`

Move or resize a floating layer.

Arguments:

- `session_id` (string, required): The session to act on.
- `layer_id` (string or null, required): The floating layer to move or resize.
- `bounds` (array of 4 numbers or object, required): The layer's new position and size, as [x, y, w, h] fractions of the window's width and height. It must fit inside the window.
  - `x` (number, required): The left edge, as a fraction of the window's width.
  - `y` (number, required): The top edge, as a fraction of the window's height.
  - `w` (number, required): The width, as a fraction of the window's width.
  - `h` (number, required): The height, as a fraction of the window's height.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- `layer_id` must name a floating layer. Naming the window's layout, the layer of kind `tiled` in `session.view`, fails the call with `bad_request`.
- `bounds` is measured from the window's top-left corner. Its width and height must be greater than 0.
- Focus and the order of floating layers don't change. Setting the bounds the layer already has changes nothing.
- If the session doesn't exist, the call fails with `session_not_found`. If `layer_id` names no layer in the session, it fails with `not_found`, and bad `bounds` fail with `bad_request`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "layer_id": "layer:<uuid>",
  "bounds": [0.2, 0.15, 0.5, 0.6]
}
```

Example result:

```json
{"revision": 42}
```

### `session.resize_pane`

Move the border between two blocks to change their sizes.

Arguments:

- `session_id` (string, required): The session to act on.
- `direction` (string, required): The kind of split between the two blocks: 'horizontal' for a border between left and right, or 'vertical' for one between top and bottom. One of `horizontal` or `vertical`.
- `before_block_id` (string or null, required): A block on the left of the border, or above it when direction is vertical.
- `after_block_id` (string or null, required): A block on the right of the border, or below it when direction is vertical.
- `ratio` (number, required): The fraction of the split's space that the before part gets, greater than 0 and less than 1.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The two blocks pick the border. The server finds the innermost split in `direction` that has `before_block_id` on its left or top side and `after_block_id` on its right or bottom side. The blocks don't need to touch.
- `ratio` has no default. Unlike in `session.new_split`, 0 doesn't mean an even split.
- The blocks can be in any window, active or not. Focus doesn't change.
- The call always raises `revision` and sends `session_view_changed`, even if the border was already where you asked.
- If the session doesn't exist, the call fails with `session_not_found`. If the two block IDs are the same, or `ratio` is out of range, it fails with `bad_request`, and if either block isn't in any window, it fails with `not_found`.
- If the blocks are in different windows or layers, or no split between them matches, the call fails with `bad_request`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "direction": "horizontal",
  "before_block_id": "block:01234567-89ab-cdef-0123-456789abcde1",
  "after_block_id": "block:01234567-89ab-cdef-0123-456789abcde2",
  "ratio": 0.65
}
```

Example result:

```json
{"revision": 42}
```

### `session.set_block_label`

Set a block's label.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_id` (string or null): The block to relabel. Required.
- `label` (string): The new label, up to 128 characters. An empty or missing label restores a default made from the block's ID.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- `block_id` is required. Leaving it out fails with `bad_request`.
- The server trims whitespace from both ends of `label`. An empty `label` restores a default made from the block's ID, such as `Block 89abcdef`, rather than the flavor name a new block starts with.
- A label longer than 128 characters, or one that contains a control character, fails with `bad_request`.
- Relabeling a block that is in a window sends `session_view_changed`. A detached block isn't part of the view, so relabeling it sends no event and leaves `revision` unchanged.
- If `block_id` names a block that isn't in the session, the call fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>",
  "label": "shell"
}
```

Example result:

```json
{"revision": 42}
```

### `session.set_label`

Set the session's label.

Arguments:

- `session_id` (string, required): The session to act on.
- `label` (string): The new label, up to 128 characters. An empty or missing label restores the label the server chose when it created the session.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The server trims whitespace from both ends of `label`. An empty `label` restores the label the server chose when it created the session, such as `quiet-meadow`. It does so even if the session was created with a label.
- A label longer than 128 characters, or one that contains a control character, fails with `bad_request`.
- A new label sends `session_label_changed`, then `session_view_changed`. Setting the label the session already has changes nothing.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "label": "work"
}
```

Example result:

```json
{"revision": 42}
```

### `session.set_layer_state`

Minimize a floating layer or return it to normal, optionally raising and focusing it.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `layer_id` (string or null, required): The layer to change.
- `state` (string, required): The state to set: 'normal' or 'minimized'.
- `focus` (boolean): Whether to also raise the layer, focus the block last focused in it, and make its window active, all in one change. Needs state to be 'normal' and the layer to have a block. Defaults to false.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- Only a floating layer can be minimized. Minimizing the window's layout, the layer of kind `tiled` in `session.view`, fails with `bad_request`. Setting it to `normal` succeeds and changes nothing.
- With `focus` set to `true`, the layer is also raised, the block last focused in it takes focus, or its first block, and its window becomes active, all in one revision. This also works on a layer that is already normal and on the tiled layer. `focus` with `minimized`, or on a layer with no blocks, fails with `bad_request` and changes nothing.
- `state` must be `normal` or `minimized`. Any other value fails with `bad_request`.
- A minimized layer stays in `session.view` with `state` set to `minimized`, its blocks keep running, and it keeps its bounds and its place in the stack. `state` is left out for a normal layer.
- Minimizing the layer that has focus moves focus to the layer used before it, or to the nearest visible pane, and clears zoom. Returning a layer to normal leaves focus where it is. Setting the state the layer already has changes nothing.
- A window must keep a visible pane, so minimizing the layer that holds its last visible panes fails with `bad_request`.
- If the session doesn't exist, the call fails with `session_not_found`. If `layer_id` names no layer in the session, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "layer_id": "layer:<uuid>",
  "state": "minimized"
}
```

Example result:

```json
{"revision": 42}
```

### `session.set_window_label`

Set a window's label.

Arguments:

- `session_id` (string, required): The session to act on.
- `window_id` (string or null, required): The window to relabel.
- `label` (string): The new label, up to 128 characters. An empty or missing label restores a default made from the window's ID.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- The server trims whitespace from both ends of `label`. An empty `label` restores a default made from the window's ID, such as `Window 89abcdef`.
- A label longer than 128 characters, or one that contains a control character, fails with `bad_request`.
- A new label sends `window_label_changed`, then `session_view_changed`. Setting the label the window already has changes nothing.
- If `window_id` names a window that isn't in the session, the call fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "window_id": "window:<uuid>",
  "label": "editor"
}
```

Example result:

```json
{"revision": 42}
```

### `session.swap_blocks`

Swap the places of two blocks.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_id` (string or null, required): One of the two blocks to swap.
- `other_block_id` (string or null, required): The other block. The order of the two doesn't matter.

Result:

- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows, unless the method's notes say otherwise.

Notes:

- Both blocks must be in a window. They can be in different windows or layers.
- Only the two blocks move. Splits, their sizes and IDs, floating layers, and the window order stay the same.
- If one of the blocks has focus in the active window, focus follows it to its new place, and that place's window becomes active.
- If the session doesn't exist, the call fails with `session_not_found`. If `block_id` and `other_block_id` are the same, it fails with `bad_request`. If either block isn't in any window, including a detached block, it fails with `not_found`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:01234567-89ab-cdef-0123-456789abcde1",
  "other_block_id": "block:01234567-89ab-cdef-0123-456789abcde2"
}
```

Example result:

```json
{"revision": 42}
```

### `session.view`

Get the session's windows, their layouts, and their blocks in one snapshot.

Arguments:

- `session_id` (string, required): The session to act on.

Result:

- `revision` (integer, required): The view's revision. A larger revision is a newer view of the session, even across a server restart. It goes up by one with each change, and jumps ahead when a restarted server restores the session.
- `session_id` (string or null, required): The session's ID.
- `label` (string): The session's label.
- `active_window_id` (string or null): The window the session shows. Left out when the session has no windows.
- `windows` (array of objects or null): The session's windows, in order. Rex.app shows them as tabs in this order.
  - `window_id` (string or null, required): The window's ID.
  - `label` (string): The window's label.
  - `focused_block_id` (string or null): The window's focused block, which receives typing while the window is active. Left out if the window has none.
  - `zoomed_block_id` (string or null): The block zoomed to fill the window, if any. Layouts keep their normal sizes, so draw this block over the whole window.
  - `layers` (array of objects or null, required): The window's layers, from back to front. The first is the tiled layer, which holds the window's layout and fills the window.
    - `layer_id` (string or null, required): The layer's ID.
    - `kind` (string, required): How the layer is placed: 'tiled' for the layer that fills the window, or 'floating' for a floating layer. Draw a kind you don't know as 'floating'.
    - `bounds` (array of 4 numbers, required): Where the layer sits, as [x, y, w, h] fractions of the window's width and height.
    - `state` (string): How the layer is presented: 'minimized' means don't draw it, though its blocks keep running, and only a floating layer can be minimized. Left out for a normal layer. Draw a state you don't know as normal.
    - `layout` (object or null, required): How the layer divides its space between blocks, as a tree of splits. Null when the layer is empty.
      - `rect` (array of 4 numbers, required): Where this node sits, as [x, y, w, h] fractions of the window's width and height.
      - `block_id` (string or null): The block at this node. Set only when split isn't.
      - `split` (object or null): How this node divides in two. Set only when block_id isn't.
        - `split_id` (string or null, required): The split's ID. It stays the same while the split exists, so you can match splits across views.
        - `direction` (string, required): How the space is divided: 'horizontal' puts before on the left and after on the right, and 'vertical' puts before above after. Draw a direction you don't know as 'horizontal'. One of `horizontal` or `vertical`.
        - `ratio` (number, required): The fraction of the space that before gets, between 0 and 1.
        - `before` (object, same fields as `layout`, required): The node on the left or top.
        - `after` (object, same fields as `layout`, required): The node on the right or bottom.
    - `blocks` (array of objects or null, required): The blocks in the layer, in layout order. The layout field says where each one sits.
      - `block_id` (string or null, required): The block's ID.
      - `label` (string): The block's label.
      - `creator_name` (string): The creator that made the block, such as com.superlogical.terminal.
      - `flavor` (string): The flavor the block was made from, such as com.superlogical.terminal.shell.
      - `host_id` (string): Identifies the machine and account the block runs as. If it equals your local server's host_id, the block sees the same files you do. Otherwise treat the block as remote.
      - `owner` (string or null): The client that owns the block. Left out when the block has no owner.

Notes:

- `revision` starts at zero for a new session and goes up by one each time the view changes. Compare revisions only within one session, and keep the view with the highest revision.
- A `session_view_changed` event carries only the new revision, not the view. These events can arrive out of revision order, so call `session.view` only when an event's revision is higher than the one in the view you hold.
- `window_created`, `window_closed`, `window_label_changed`, `active_window_changed`, and `session_label_changed` arrive before the `session_view_changed` event for the same change.
- The view holds only blocks that are in a window. `session.list_blocks` also lists detached blocks.
- The view describes structure, not content. To read what a block shows, call one of its own methods, such as `com.superlogical.terminal.format`.

Example arguments:

```json
{
  "session_id": "session:<uuid>"
}
```

Example result:

```json
{
  "revision": 7,
  "session_id": "session:<uuid>",
  "label": "work",
  "active_window_id": "window:<uuid>",
  "windows": [
    {
      "window_id": "window:<uuid>",
      "label": "editor",
      "focused_block_id": "block:01234567-89ab-cdef-0123-456789abcde1",
      "layers": [
        {
          "layer_id": "layer:<uuid>",
          "kind": "tiled",
          "bounds": [0, 0, 1, 1],
          "layout": {
            "rect": [0, 0, 1, 1],
            "split": {
              "split_id": "split:<uuid>",
              "direction": "horizontal",
              "ratio": 0.5,
              "before": {
                "rect": [0, 0, 0.5, 1],
                "block_id": "block:01234567-89ab-cdef-0123-456789abcde1"
              },
              "after": {
                "rect": [0.5, 0, 0.5, 1],
                "block_id": "block:01234567-89ab-cdef-0123-456789abcde2"
              }
            }
          },
          "blocks": [
            {
              "block_id": "block:01234567-89ab-cdef-0123-456789abcde1",
              "label": "shell",
              "creator_name": "com.superlogical.terminal",
              "flavor": "com.superlogical.terminal.shell",
              "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10",
              "owner": "client:<uuid>"
            },
            {
              "block_id": "block:01234567-89ab-cdef-0123-456789abcde2",
              "label": "logs",
              "creator_name": "com.superlogical.terminal",
              "flavor": "com.superlogical.terminal.shell",
              "host_id": "0e9d6c1f-4a3b-4a52-9c85-2f6d3f6f2f10",
              "owner": "client:<uuid>"
            }
          ]
        }
      ]
    }
  ]
}
```

### `session.zoom_block`

Zoom a block to fill its window, or undo the zoom.

This method runs in order with the other ordered methods on its connection. See "Order of
Requests" above.

Arguments:

- `session_id` (string, required): The session to act on.
- `block_id` (string or null): The block to zoom. It takes focus, and its window becomes active. Leave it out to use the focused block of window_id, or of the active window.
- `window_id` (string or null): A window whose focused block to zoom. The active window doesn't change. Send it only when block_id is left out.
- `zoom` (boolean or null): Set true to zoom or false to restore the layout. Leave it out to switch between the two.

Result:

- `block_id` (string or null, required): The block this call zoomed or restored. If the request didn't name one, this is the focused block the server used.
- `zoomed` (boolean, required): Whether the block is zoomed after this call.
- `revision` (integer, required): The session's revision after this call. It is unchanged if the call changed nothing that session.view shows.

Notes:

- With `block_id`, the server first focuses the block and makes its window active, as `session.focus_block` does. With `window_id`, focus and the active window don't change.
- Zoom changes only what is shown. The layout stays the same, and the zoom ends when focus moves to another block in the window.
- If the session doesn't exist, the call fails with `session_not_found`. If `block_id` names a block that isn't in any window, or `window_id` names a window that isn't in the session, it fails with `not_found`. Sending both fails with `bad_request`.
- If both `block_id` and `window_id` are left out and the session has no windows, the call fails with `conflict`.

Example arguments:

```json
{
  "session_id": "session:<uuid>",
  "block_id": "block:<uuid>",
  "zoom": true
}
```

Example result:

```json
{
  "block_id": "block:<uuid>",
  "zoomed": true,
  "revision": 42
}
```

### `target.complete`

List the labels, window positions, and short IDs that start with a prefix. Use it to complete a name as someone types it.

Arguments:

- `kinds` (array of strings or null, required): What to complete: session, window, block, or client, with the same rules as in target.resolve. Completions come back in this order.
- `prefix` (string, required): The text typed so far. It matches the start of labels (ignoring case), window positions, and short IDs. An empty prefix matches everything.
- `context` (object or null): Where the caller is. Only session_id is used: without scope.session, windows and blocks come from that session.
  - `session_id` (string): The caller's session. Without scope.session, windows and blocks are searched only in this session, and an empty session query selects it.
  - `block_id` (string): The caller's block, such as the terminal a script runs in. In target.resolve, empty block and window queries default to it and its window.
  - `client_id` (string): The caller's own client. In target.resolve, an empty client query selects it if it's among the clients searched.
- `scope` (object or null): Limits completion to one session, or block completion to one window, as in target.resolve.
  - `session` (string): The session to search in, named by ID, label, or the end of its ID. A client search then covers only clients attached to it.
  - `window` (string): The window to search in, named by ID, label, position, or the end of its ID. Allowed only when kinds is just block.

Result:

- `completions` (array of objects or null, required): Every match, grouped by kind in the order of kinds. Empty if nothing matches or a scope doesn't resolve.
  - `value` (string, required): The text to offer, such as a label, a window position, or a short ID.
  - `form` (string, required): What value is: 'label', 'ordinal' (a window position), or 'id' (a short ID, the last six hexadecimal digits of an ID).
  - `target` (object, required): The session, window, block, or client that value names.
    - `kind` (string, required): What this is: session, window, block, or client. Skip a candidate whose kind you don't know.
    - `session_id` (string): The session's ID, or the ID of the session that holds the window or block. Left out for a client.
    - `window_id` (string): The window's ID, or the ID of the window that holds the block. Left out for sessions, clients, and detached blocks.
    - `block_id` (string): The block's ID. Set only for blocks.
    - `client_id` (string): The client's ID. Set only for clients.
    - `label` (string, required): Its label, or an empty string if it has none.
    - `ordinal` (integer): A window's position in its session, counting from 1, in the order session.view lists windows. Left out for everything else.
    - `where` (string): Where it is, for showing to a person, such as 'window 2' or 'detached'. For a client, the program, such as 'Rex.app 1.6.0'. May be empty. Don't parse it.

Notes:

- `kinds`, `scope`, and `context.session_id` choose what to search the same way as in `target.resolve`, and the call fails with `bad_request` in the same cases.
- `prefix` matches the start of labels, ignoring case, and label completions keep the label's own case. White space around `prefix` is ignored. A control character inside it, such as a tab, fails the call with `bad_request`.
- Windows also match by position, so the prefix `1` matches windows 1, 10, 11, and so on. An empty prefix matches every position.
- An empty prefix, or one made only of hexadecimal digits, also matches the start of short IDs. A short ID is the last six hexadecimal digits of an ID, such as `12bef5`, and `target.resolve` accepts it as the end of an ID.
- One thing can appear more than once, such as a window whose label and short ID both start with the prefix.
- Completions are grouped by kind, in the order of `kinds`. Within each kind, labels come first, sorted ignoring case, then window positions in order, then short IDs in sorted order.
- If `scope.session` or `scope.window` matches nothing or several things, `completions` is empty. The call doesn't fail.
- Detached blocks are included unless `scope.window` is set.
- To complete a client, set `kinds` to `["client"]`. Clients complete by label and short ID, and each target's `where` names the program, such as `Rex.app 1.6.0`.

Example arguments:

```json
{
  "kinds": ["block", "window"],
  "prefix": "s",
  "context": { "session_id": "session:<uuid>" }
}
```

Example result:

```json
{
  "completions": [
    {
      "value": "shell",
      "form": "label",
      "target": {
        "kind": "block",
        "session_id": "session:<uuid>",
        "window_id": "window:01234567-89ab-cdef-0123-456789abcde1",
        "block_id": "block:<uuid>",
        "label": "shell",
        "where": "window 1"
      }
    },
    {
      "value": "server",
      "form": "label",
      "target": {
        "kind": "window",
        "session_id": "session:<uuid>",
        "window_id": "window:01234567-89ab-cdef-0123-456789abcde2",
        "label": "server",
        "ordinal": 2
      }
    }
  ]
}
```

### `target.resolve`

Look up sessions, windows, blocks, or clients by ID, label, or position. Each query gets its own result, which says whether it matched one thing, nothing, or several.

Arguments:

- `kinds` (array of strings or null, required): What each query may name: session, window, block, or client. List each kind once, and list client only on its own.
- `queries` (array of strings or null, required): The names to look up. Each is a full ID, a label, a window position, the end of an ID, or an empty string for the default.
- `context` (object or null): Where the caller is, such as the terminal a script runs in. IDs in it that no longer exist are ignored.
  - `session_id` (string): The caller's session. Without scope.session, windows and blocks are searched only in this session, and an empty session query selects it.
  - `block_id` (string): The caller's block, such as the terminal a script runs in. In target.resolve, empty block and window queries default to it and its window.
  - `client_id` (string): The caller's own client. In target.resolve, an empty client query selects it if it's among the clients searched.
- `scope` (object or null): Limits the search to one session, or a block search to one window. Not allowed when kinds includes session.
  - `session` (string): The session to search in, named by ID, label, or the end of its ID. A client search then covers only clients attached to it.
  - `window` (string): The window to search in, named by ID, label, position, or the end of its ID. Allowed only when kinds is just block.

Result:

- `results` (array of objects or null, required): One result for each query, in the same order as queries.
  - `query` (string, required): The query this result answers, exactly as sent.
  - `status` (string, required): Whether the query matched: 'resolved' for one thing, 'not_found' for nothing, or 'ambiguous' for several. Treat a value you don't know as 'not_found'.
  - `target` (object or null): What the query matched. Set only when status is resolved.
    - `kind` (string, required): What this is: session, window, block, or client. Skip a candidate whose kind you don't know.
    - `session_id` (string): The session's ID, or the ID of the session that holds the window or block. Left out for a client.
    - `window_id` (string): The window's ID, or the ID of the window that holds the block. Left out for sessions, clients, and detached blocks.
    - `block_id` (string): The block's ID. Set only for blocks.
    - `client_id` (string): The client's ID. Set only for clients.
    - `label` (string, required): Its label, or an empty string if it has none.
    - `ordinal` (integer): A window's position in its session, counting from 1, in the order session.view lists windows. Left out for everything else.
    - `where` (string): Where it is, for showing to a person, such as 'window 2' or 'detached'. For a client, the program, such as 'Rex.app 1.6.0'. May be empty. Don't parse it.
  - `reason` (string): Why the query didn't resolve, for showing to a person. May be empty.
  - `candidates` (array of objects or null): When status is ambiguous, the choices for a person to pick from. Check each one's kind.
    - `kind` (string, required): What this is: session, window, block, or client. Skip a candidate whose kind you don't know.
    - `session_id` (string): The session's ID, or the ID of the session that holds the window or block. Left out for a client.
    - `window_id` (string): The window's ID, or the ID of the window that holds the block. Left out for sessions, clients, and detached blocks.
    - `block_id` (string): The block's ID. Set only for blocks.
    - `client_id` (string): The client's ID. Set only for clients.
    - `label` (string, required): Its label, or an empty string if it has none.
    - `ordinal` (integer): A window's position in its session, counting from 1, in the order session.view lists windows. Left out for everything else.
    - `where` (string): Where it is, for showing to a person, such as 'window 2' or 'detached'. For a client, the program, such as 'Rex.app 1.6.0'. May be empty. Don't parse it.

Notes:

- List at least one kind in `kinds`, each only once, and `client` only on its own, or the call fails with `bad_request`. A kind the server doesn't know fails the same way, which can happen when the server is older than your client.
- Send at least one query, or the call fails with `bad_request`. Each query gets one entry in `results`, in the same order. A query that matches nothing or several things doesn't fail the call: its `status` is `not_found` or `ambiguous`.
- The server tries each query in four steps and stops at the first step that matches anything: full ID, label (ignoring case), window position, then the end of an ID (4 to 32 hexadecimal digits). So a window labeled `2` wins over the second window. If the winning step matches more than one thing, the result is `ambiguous`.
- A query that starts like a full ID, such as `block:`, is only tried as a full ID. Its result is `not_found` if the ID is malformed, if `kinds` doesn't include that kind, or if nothing has that ID.
- Only windows have positions. `1` is the first window in its session, in the order Rex.app shows them as tabs. When several sessions are searched, a position can match a window in each one.
- When `kinds` lists more than one kind, one query can match different kinds of things, such as a session and a window with the same label. The result is then `ambiguous`, and each candidate's `kind` says what it is.
- Without `scope.session`, windows and blocks are searched only in the session named by `context.session_id`. If that's left out or the session no longer exists, every session is searched. This applies to full IDs too, so the full ID of a block in another session is `not_found`.
- If a scope matches nothing or several things, every result gets that `not_found` or `ambiguous` status, and its `reason` says which scope failed. For an ambiguous scope, `candidates` lists what the scope could mean.
- An empty query, or one of only spaces, asks for a default, such as the block a script runs in. When `kinds` lists several kinds, an empty query gets the default for the most specific one: block, then window, then session.
- An empty session query selects `context.session_id`, or the only session if there's just one. With no sessions it's `not_found`, and with several it's `ambiguous`.
- An empty window query selects the window that holds `context.block_id`, or else the active window of the session being searched. If several sessions are searched, it's `ambiguous` even with `context.block_id` set, and `candidates` lists the sessions to choose from.
- An empty block query selects `context.block_id` if that block is among those searched. Otherwise it selects the focused block of the window an empty window query would choose. With `scope.window`, it ignores `context.block_id` and selects that window's focused block.
- Block searches include detached blocks, which are running but aren't placed in any window. `scope.window` leaves them out. A block's `where` reads `window 2` or `detached`. When several sessions are searched, it names the session too, as in `window 2 of work`, and a window's `where` reads `in work`.
- To look up a connected client, such as Rex.app, set `kinds` to `["client"]`. A client query matches a full ID, a label, or the end of an ID, since clients have no positions. Every connected client is searched, unless `scope.session` limits the search to clients attached to that session.
- An empty client query selects `context.client_id` if that client is among those searched. Otherwise it selects the only client that reported its kind as `app` when it connected, such as Rex.app. With no app it's `not_found`, and with several it's `ambiguous`.
- `scope.window` is looked up among the sessions being searched. The call fails with `bad_request` if `kinds` includes `session` and `scope` is sent, even as an empty object, or if `scope.window` is set and `kinds` isn't just `block`. A query or scope that contains a control character, such as a newline, fails the same way.

Example arguments:

```json
{
  "kinds": ["block"],
  "queries": ["shell", "build"],
  "scope": { "session": "work" }
}
```

Example result:

```json
{
  "results": [
    {
      "query": "shell",
      "status": "resolved",
      "target": {
        "kind": "block",
        "session_id": "session:<uuid>",
        "window_id": "window:<uuid>",
        "block_id": "block:<uuid>",
        "label": "shell",
        "where": "window 1"
      }
    },
    {
      "query": "build",
      "status": "not_found"
    }
  ]
}
```

## `com.superlogical.onboarding` Block Methods

Blocks made by `com.superlogical.onboarding` provide these methods. Call each one with `session_id`, `block_id`,
and its own arguments under `args`, as "Calling Block Methods" above describes.

This creator makes blocks of these flavors:

- `com.superlogical.onboarding.onboarding`

### `com.superlogical.onboarding.set_step`

Move the walkthrough to another step. Emits step_changed when the step changes.

Arguments under `args`:

- `step` (string, required): The step to move to. Empty means the first step.

Result:

- `generation` (integer, required): Increases with every change to the state.
- `step` (string): The step the walkthrough is on. Empty means the first step.

Notes:

- Call it as `com.superlogical.onboarding.set_step` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.onboarding.state`

Return the step the walkthrough is on.

Arguments under `args`:

None.

Result:

- `generation` (integer, required): Increases with every change to the state.
- `step` (string): The step the walkthrough is on. Empty means the first step.

Notes:

- Call it as `com.superlogical.onboarding.state` with `session_id`, `block_id`, and this method's own arguments under `args`.

## `com.superlogical.terminal` Block Methods

Blocks made by `com.superlogical.terminal` provide these methods. Call each one with `session_id`, `block_id`,
and its own arguments under `args`, as "Calling Block Methods" above describes.

This creator makes blocks of these flavors:

- `com.superlogical.terminal.shell`

### `com.superlogical.terminal.blob_read`

Read a result that was too large for one reply or event, such as the output of format with max_bytes set, or a selection_copied event without text. The server keeps each result for about five minutes.

Arguments under `args`:

- `id` (integer): The result to read, from the reply or event that named it, such as the id in a format reply or a selection_copied event. Zero or left out reads the newest result, whatever produced it. If the server no longer has the result, the call fails with not_found.
- `offset` (integer): Where to start reading, in bytes from the start of the result. To read the next part, add the length in bytes of the data you received. Must not be negative.
- `max_bytes` (integer): The most bytes of data to return, measured once it is encoded as a JSON string. Zero or left out means 49152 (48 KiB), and values above 983040 (960 KiB) are lowered to that. Must not be negative.

Result:

- `id` (integer, required): The result that was read. Send it in later requests so that every part comes from the same result.
- `data` (string, required): The text read, starting at offset. It never ends partway through a character. It can be shorter than max_bytes even when more text remains.
- `total` (integer, required): The length of the whole result, in bytes.
- `eof` (boolean, required): True if data reaches the end of the result.

Notes:

- Call it as `com.superlogical.terminal.blob_read` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.clear`

Clear the screen and scrollback, keeping the current line at the top. It changes nothing while a full-screen program, such as an editor, is running.

Arguments under `args`:

None.

Result:

An empty object, `{}`.

Actions that call it:

- `com.superlogical.terminal.clear` (Clear Terminal): Clear the screen and scrollback, keeping the current line.

Notes:

- Call it as `com.superlogical.terminal.clear` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.clipboard_read`

Read text that a program in the terminal copied to the clipboard. The server keeps each copy for about a minute, so a client can read text too large to send in an event.

Arguments under `args`:

- `id` (integer): The copy to read, from a clipboard_written event or an earlier reply. Zero or left out reads the newest copy. If the server no longer has the copy, the call fails with not_found.
- `offset` (integer): Where to start reading, in bytes from the start of the text. To read the next part, add the length in bytes of the data you received. Must not be negative.
- `max_bytes` (integer): The most bytes of data to return, measured once it is encoded as a JSON string. Zero or left out means 49152 (48 KiB), and values above 983040 (960 KiB) are lowered to that. Must not be negative.

Result:

- `id` (integer, required): The copy that was read. Send it in later requests so that every part comes from the same copy.
- `data` (string, required): The text read, starting at offset. It never ends partway through a character. It can be shorter than max_bytes even when more text remains.
- `total` (integer, required): The length of the whole copied text, in bytes.
- `eof` (boolean, required): True if data reaches the end of the text.

Notes:

- Call it as `com.superlogical.terminal.clipboard_read` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.download_begin`

Open a file on the terminal's machine for download, and get a ticket for the rex.data.bytes.v1 data stream that carries its bytes from the terminal. A relative path is relative to the working directory of the terminal's foreground process. Only regular files can be downloaded.

Arguments under `args`:

- `ref` (string, required): The data reference for the file's stream: download: followed by a new UUID in lower-case canonical form, which the client chooses.
- `path` (string, required): The file to download. A relative path is relative to the working directory of the terminal's foreground process, and a leading ~ is the home directory.
- `if_none_match` (string): The version of the copy the client already has, from an earlier result for the same path. If the file still has it, the result has not_modified and no ticket.

Result:

- `ticket` (string): A single-use ticket to send in the HELLO message that opens the data connection. Only a data connection from the same user can use it. Left out with not_modified.
- `expires_in_ms` (integer): How long the ticket stays valid after the server issues it, in milliseconds. Left out with not_modified.
- `not_modified` (boolean): The file still has the version named in if_none_match, so the client's copy is current. There is no ticket and nothing to download.
- `path` (string, required): The absolute path of the file, with symlinks left as they are.
- `name` (string, required): The file's name: the last element of path.
- `size` (integer, required): The file's size in bytes when it was opened, for showing progress only. The stream may carry more or fewer bytes if the file changes.
- `modified_at` (string, required): The file's modification time when it was opened, as an RFC 3339 timestamp. Programs can set it to anything, so use version to tell whether the file changed.
- `version` (string): An opaque string that changes whenever the file's contents may have, for caching. The same path with the same version has the same contents. Left out when the file changed within the last second, or when the server can't tell, and then the download must not be cached.

Notes:

- Call it as `com.superlogical.terminal.download_begin` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.format`

Get the screen and scrollback as plain text, HTML, or escape sequences.

Arguments under `args`:

- `format` (string): The format to return: 'text' for plain text, 'html' for HTML with inline styles, or 'vt' for escape sequences that redraw the screen with its colors and styles. Defaults to 'text'.
- `trim` (boolean): Remove spaces from the end of each line.
- `unwrap` (boolean): Join lines that the terminal wrapped because they were too long, so each line of output is one line the program wrote.
- `extra_charsets` (boolean): Include the selected character sets. Only used with the vt format.
- `extra_cursor` (boolean): Include the cursor position. Only used with the vt format.
- `extra_hyperlink` (boolean): Include the hyperlink in effect at the cursor. Only used with the vt format.
- `extra_keyboard` (boolean): Include keyboard modes such as modifyOtherKeys. Only used with the vt format.
- `extra_kitty_keyboard` (boolean): Include the state of the Kitty keyboard protocol. Only used with the vt format.
- `extra_modes` (boolean): Include terminal modes that differ from their defaults. Only used with the vt format.
- `extra_palette` (boolean): Include the color palette. Only used with the vt format.
- `extra_protection` (boolean): Include the character protection mode. Only used with the vt format.
- `extra_pwd` (boolean): Include the reported working directory. Only used with the vt format.
- `extra_scrolling_region` (boolean): Include the scrolling region. Only used with the vt format.
- `extra_style` (boolean): Include the text style in effect at the cursor. Only used with the vt format.
- `extra_tabstops` (boolean): Include the tab stops. Only used with the vt format.
- `max_bytes` (integer): The most bytes of content to return, measured once it is encoded as a JSON string. If more output remains, the reply has an id, and you read the rest with blob_read. Values above 983040 (960 KiB) are lowered to that. Zero or left out returns the whole output in one reply, and the call fails with too_large if the output doesn't fit. Must not be negative.

Result:

- `content` (string, required): The screen and scrollback in the requested format. If id is set, this is only the first part.
- `id` (integer): Set only when more output remains. To read the rest, call blob_read with this id and with offset set to the length of content in bytes. A reply without id holds the whole output.
- `total` (integer): The length of the whole output in bytes. Sent only when the request set max_bytes, and left out when it is 0.
- `eof` (boolean): True if content is the whole output. Sent only when the request set max_bytes. Older servers never send it, so check id instead to tell whether more output remains.

Notes:

- Call it as `com.superlogical.terminal.format` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.input_mode`

Get how the terminal handles typed input, such as whether it is echoed. On Windows the call fails with not_found.

Arguments under `args`:

None.

Result:

- `echo` (boolean, required): True if typed characters appear on the screen. A program usually turns this off while it reads a password.
- `canonical` (boolean, required): True if input reaches the program a line at a time, with backspace and similar keys handled before the program sees it.
- `signals` (boolean, required): True if keys such as Ctrl+C and Ctrl+Z send signals to the foreground process instead of reaching it as input.

Notes:

- Call it as `com.superlogical.terminal.input_mode` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.list_dir`

List the subdirectories of a directory, and optionally its files. A relative path or ~ is resolved the way a program in the terminal would resolve it.

Arguments under `args`:

- `path` (string): The directory to list: an absolute path, ~ or a path under ~/ for the home directory, or a path relative to the working directory of the terminal's foreground process. Empty lists that working directory.
- `prefix` (string): Keep only entries whose names start with this text, ignoring case. Use it to narrow a listing that came back truncated.
- `limit` (integer): The most entries to return. Zero, negative, or left out means 2000.
- `files` (boolean): Include entries that aren't directories, such as files, and mark each with file set to true.

Result:

- `path` (string, required): The directory that was listed, as an absolute path. It is set even when the directory couldn't be read.
- `home` (string, required): The home directory of the terminal's program. Empty if the server can't tell.
- `status` (string, required): Whether the directory could be read: 'ok', 'missing' (it doesn't exist), 'not_dir' (it, or a directory on its path, isn't a directory), or 'denied' (the server isn't allowed to read it).
- `entries` (array of objects or null, required): The matching entries, hidden ones included, in the order the filesystem returns them. Sorting and hiding are up to the client. Empty unless status is 'ok'.
  - `name` (string, required): The entry's name within the directory.
  - `symlink` (boolean): True if the entry is a symbolic link. A link that leads nowhere is left out of the listing.
  - `file` (boolean): True if the entry isn't a directory, or is a link to something that isn't. Only set when the request sets files.
- `truncated` (boolean, required): True if some matching entries were left out because the listing reached limit or grew too large for one reply. To see the rest, list again with a prefix.

Notes:

- Call it as `com.superlogical.terminal.list_dir` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.modes`

List the terminal modes that programs can turn on, such as the alternate screen, bracketed paste, and mouse reporting, with each mode's name, number, and whether it is on. It doesn't wake an idle terminal.

Arguments under `args`:

None.

Result:

- `screen` (string, required): Which screen the terminal shows: 'primary', or 'alternate' while a full-screen program such as an editor has switched to it with mode 47, 1047, or 1049. Treat any other value as 'primary'.
- `modes` (array of objects or null, required): Every mode the terminal tracks, ANSI modes first, each group by number. Newer servers can add modes, so look modes up by name or number and skip any you don't know.
  - `name` (string, required): The mode's name, as Ghostty names it, such as 'bracketed_paste' or 'alt_screen_save_cursor_clear_enter'.
  - `number` (integer, required): The number a program sends to set or reset the mode, such as 2004 for bracketed paste.
  - `ansi` (boolean): True for an ANSI mode, which a program sets with ESC [ number h. Left out for a DEC private mode, which a program sets with ESC [ ? number h. The two kinds are numbered separately, so the same number can name one mode of each kind.
  - `set` (boolean, required): True if the mode is on.
  - `default` (boolean): True if the mode is on in a new terminal and after a reset. Left out if it is off. A mode whose set differs from its default was changed by a program.

Notes:

- Call it as `com.superlogical.terminal.modes` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.ports`

List the TCP ports that programs in the terminal are listening on, such as a development server's, with each port's address and the process that holds it. status is 'partial' when some processes couldn't be looked at, so a port missing from a partial reply may still be open. A truncated reply has an id, and blob_read with that id returns the whole list. It doesn't wake an idle terminal. To follow changes, call it once and then follow the ports_changed event.

Arguments under `args`:

None.

Result:

- `status` (string, required): How complete ports is: 'ok' if every process in the terminal was looked at, 'partial' if some could not be, so ports may be missing some, or 'unsupported' if this server cannot list ports for this terminal. Treat any other value as 'partial'.
- `ports` (array of objects, required): The ports that programs in the terminal listen on, sorted by port and then by address, with one entry for each protocol, address and port. An empty array when nothing is listening.
  - `port` (integer, required): The port number.
  - `address` (string, required): The address the socket is bound to, as an IP literal with no brackets, such as 127.0.0.1, ::1, 0.0.0.0, or ::. An IPv4 address mapped into IPv6 is given as plain IPv4. An IPv6 link-local address keeps its zone, an interface index, as in fe80::1%1.
  - `protocol` (string, required): The transport protocol, 'tcp' today. Skip an entry whose protocol you don't know.
  - `first_seen_at` (string, required): When the server first saw this address and port held by this process in this terminal, as an RFC 3339 timestamp. The server looks in the background, every second while there is output or input in any terminal and every 5 minutes at most while nothing happens, and also when asked, so this can be minutes after the port opened. It changes if the port closes and opens again or another process holds it.
  - `pid` (integer): The ID of the process that holds the socket, or the first started of several that share it. The process is described by the entry in processes with the same pid. Left out when the server can't tell which process holds the socket, such as a server started with sudo, and the reply is then partial.
- `processes` (array of objects, required): The processes that hold the ports, sorted by pid, with one entry for each, described as the process method describes them. A pid in ports with no entry here is a process the server couldn't describe. Each is described when its first port is found, so fields such as cwd show their value from then.
  - `pid` (integer, required): The process ID.
  - `user_id` (integer, required): The user ID the process runs as on Unix, which shows when a command runs as another user, such as with sudo. Zero on Windows.
  - `user` (string): The name of the account the process runs as. Left out if it can't be looked up.
  - `name` (string, required): The file name of the executable, such as zsh. When path is left out, the system's own name for the process, which Linux limits to 15 bytes. To label a process, use the first of script, argv0, and name that is present.
  - `path` (string): The absolute path of the executable, with symlinks resolved. Left out if it can't be read, as for another user's process on Linux.
  - `invoked_path` (string): The absolute path the executable was started through, before symlinks were resolved, such as a link in a bin directory. Present only when Rex can confirm it leads to the same file as path.
  - `argv0` (string): The first argument as the process shows it now, such as -zsh for a login shell, cut to 256 bytes. Rex never returns the other arguments, because they can hold secrets.
  - `script` (string): For an interpreter such as node or python, the absolute path of the script it is running, as the user named it. Left out for other programs.
  - `cwd` (string): The process's working directory, as an absolute path. Left out if it can't be read, as for another user's process.
- `truncated` (boolean): True if entries were left out from the end of ports so that the reply fits in one message. processes then describes only the processes that the remaining entries name. Left out when false.
- `id` (integer): Set only when truncated is true. Names a stored copy of the whole reply, from the same scan. Read it with blob_read from offset 0 until eof, and decode the text as a ports reply. The server keeps it for a few minutes. Left out if the server couldn't store the copy.

Notes:

- Call it as `com.superlogical.terminal.ports` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.process`

Get the processes running in the terminal and how its program exited.

Arguments under `args`:

None.

Result:

- `last_exit` (object or null, required): How the terminal's program exited. Null while it is still running, and for a terminal that didn't start a program of its own.
  - `exit_code` (integer, required): The program's exit code, or -1 if it has none, as when a signal ended it.
  - `error` (string): Why the program failed, such as 'exit status 1'. Left out when it exited successfully.
- `child` (object or null): The process the terminal started, usually a shell. On macOS a login shell runs under login, and this describes login. Left out if there is no such process or it can't be read.
  - `pid` (integer, required): The process ID.
  - `user_id` (integer, required): The user ID the process runs as on Unix, which shows when a command runs as another user, such as with sudo. Zero on Windows.
  - `user` (string): The name of the account the process runs as. Left out if it can't be looked up.
  - `name` (string, required): The file name of the executable, such as zsh. When path is left out, the system's own name for the process, which Linux limits to 15 bytes. To label a process, use the first of script, argv0, and name that is present.
  - `path` (string): The absolute path of the executable, with symlinks resolved. Left out if it can't be read, as for another user's process on Linux.
  - `invoked_path` (string): The absolute path the executable was started through, before symlinks were resolved, such as a link in a bin directory. Present only when Rex can confirm it leads to the same file as path.
  - `argv0` (string): The first argument as the process shows it now, such as -zsh for a login shell, cut to 256 bytes. Rex never returns the other arguments, because they can hold secrets.
  - `script` (string): For an interpreter such as node or python, the absolute path of the script it is running, as the user named it. Left out for other programs.
  - `cwd` (string): The process's working directory, as an absolute path. Left out if it can't be read, as for another user's process.
- `foreground` (object or null): The process in the foreground, such as the shell at its prompt or the command it is running. For a wrapper such as sudo, git, or cargo, it is the command the wrapper runs. Left out if it can't be found or read. It has the same fields as `child`.
- `ancestors` (array of objects or null): The processes between foreground and child, nearest first: the parent of foreground, then its parent, and so on, ending with child. Left out when foreground is child, when there are more than 16, or when one of them has exited.
  - `pid` (integer, required): The process ID.
  - `user_id` (integer, required): The user ID the process runs as on Unix, which shows when a command runs as another user, such as with sudo. Zero on Windows.
  - `user` (string): The name of the account the process runs as. Left out if it can't be looked up.
  - `name` (string, required): The file name of the executable, such as zsh. When path is left out, the system's own name for the process, which Linux limits to 15 bytes. To label a process, use the first of script, argv0, and name that is present.
  - `path` (string): The absolute path of the executable, with symlinks resolved. Left out if it can't be read, as for another user's process on Linux.
  - `invoked_path` (string): The absolute path the executable was started through, before symlinks were resolved, such as a link in a bin directory. Present only when Rex can confirm it leads to the same file as path.
  - `argv0` (string): The first argument as the process shows it now, such as -zsh for a login shell, cut to 256 bytes. Rex never returns the other arguments, because they can hold secrets.
  - `script` (string): For an interpreter such as node or python, the absolute path of the script it is running, as the user named it. Left out for other programs.
  - `cwd` (string): The process's working directory, as an absolute path. Left out if it can't be read, as for another user's process.
- `home` (string): The home directory that the terminal's program inherited, for showing and expanding ~ in paths.

Notes:

- Call it as `com.superlogical.terminal.process` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.program_status`

List the status that programs in the terminal have reported. Programs report a status, such as working or blocked, with OSC 7501 escape sequences.

Arguments under `args`:

None.

Result:

- `records` (array of objects, required): Every record, sorted by id. The root record comes first, and each parent comes before its children. Empty if no program has reported a status.
  - `id` (string): The record's ID. A slash separates a parent from a child, as in build/test. Left out for the root record, which holds the reports that give no ID.
  - `state` (string, required): What the program says it is doing: 'idle', 'working', 'done', 'blocked' (waiting for the user), or 'error'. Treat a value you don't know as 'idle'.
  - `kind` (string): What a blocked program is waiting for: 'permission', 'question', or 'auth' (a password or other credential). Left out if the program didn't say. Treat a value you don't know as if it were left out.
  - `progress` (integer or null): How far along the program is, as a percentage from 0 to 100. Only working and blocked records have one, and only if the program reported it.
  - `app` (string): The program's name, such as cargo or terraform, exactly as it reported it.
  - `effective_app` (string): The name of the program the record belongs to: app if it is set, or else the app of the nearest parent record that has one. Left out if no record in the chain has an app.
  - `title` (string): A short label for the record, such as the name of a task. It comes from the program, so show it as plain text.
  - `msg` (string): One line of text describing the status. Show it as plain text. A client may shorten it but shouldn't try to interpret it.
  - `updated_at` (string, required): When the report arrived, by the server's clock, as an RFC 3339 timestamp.
  - `owner` (object or null): The terminal's foreground process when the report arrived. Left out if the server couldn't tell.
    - `pid` (integer, required): The process ID.
    - `name` (string, required): The process's name, found the same way as name in the result of process. Compare it as well as pid, because the system reuses process IDs.

Notes:

- Call it as `com.superlogical.terminal.program_status` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.pwd`

Get the working directory that a program in the terminal last reported. To find where each process actually is, call process instead.

Arguments under `args`:

None.

Result:

- `pwd` (string): The reported directory: a file:// URL when the program used OSC 7, or a plain path otherwise. Left out if no directory has been reported or a reset cleared it.
- `owner` (object or null): The process that reported the directory. Left out if pwd is left out or the server couldn't tell.
  - `pid` (integer, required): The process ID.
  - `name` (string, required): The process's name, found the same way as name in the result of process. Compare it as well as pid, because the system reuses process IDs.

Notes:

- Call it as `com.superlogical.terminal.pwd` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.reset`

Reset the terminal to its starting state and clear the screen and scrollback. The program keeps running, and the size and default colors stay the same.

Arguments under `args`:

None.

Result:

An empty object, `{}`.

Actions that call it:

- `com.superlogical.terminal.reset` (Reset Terminal): Return the terminal to its starting state and clear the screen and scrollback.

Notes:

- Call it as `com.superlogical.terminal.reset` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.resize`

Request a terminal size in columns and rows. It applies at once if the caller owns the block or no client does, and otherwise when the caller becomes the owner.

Arguments under `args`:

- `columns` (integer, required): The width in columns, that is, characters per line, from 1 to 65535.
- `rows` (integer, required): The height in rows, from 1 to 65535.
- `cell_width_px` (integer or null): The width of one character cell in pixels, from 1 to 65535. Send it together with cell_height_px, or leave both out to keep the current cell size.
- `cell_height_px` (integer or null): The height of one character cell in pixels, from 1 to 65535. Send it together with cell_width_px, or leave both out to keep the current cell size.

Result:

An empty object, `{}`.

Notes:

- Call it as `com.superlogical.terminal.resize` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.set_theme`

Replace the terminal's default colors. As with resize, they apply at once if the caller owns the block or no client does, and otherwise when the caller becomes the owner.

Arguments under `args`:

- `foreground` (string): The default text color, as hex RRGGBB or #RRGGBB, such as #d6deeb. Empty or left out uses the built-in color.
- `background` (string): The default background color, as hex RRGGBB or #RRGGBB, such as #011627. Empty or left out uses the built-in color.
- `cursor` (string): The cursor color, as hex RRGGBB or #RRGGBB, such as #80a4c2. Empty or left out uses the built-in color.
- `palette` (array of strings or null): Changes to the 256-color palette, each written as index=RRGGBB or index=#RRGGBB with an index from 0 to 255, such as 1=#ef5350. Colors not listed keep their built-in values.
- `scheme` (string): Whether the theme is 'light' or 'dark', which programs in the terminal can ask for. If empty, the terminal decides from how bright background is, and treats no background as dark.

Result:

An empty object, `{}`.

Notes:

- Call it as `com.superlogical.terminal.set_theme` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.size`

Get the terminal's size, its owner, and the size each client last requested.

Arguments under `args`:

None.

Result:

- `columns` (integer, required): The terminal's width in columns.
- `rows` (integer, required): The terminal's height in rows.
- `cell_width_px` (integer, required): The width of one character cell in pixels.
- `cell_height_px` (integer, required): The height of one character cell in pixels.
- `owner` (string or null, required): The client that owns the block, whose size the terminal uses. Null if the block has no owner.
- `desired` (array of objects, required): The last size each client requested, the owner's included, newest first.
  - `client_id` (string or null, required): The client that requested this size.
  - `columns` (integer, required): The requested width in columns.
  - `rows` (integer, required): The requested height in rows.
  - `updated_at` (string, required): When the server received the request, as an RFC 3339 timestamp.

Notes:

- Call it as `com.superlogical.terminal.size` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.title`

Get the terminal's title and the process that set it.

Arguments under `args`:

None.

Result:

- `title` (string, required): The terminal's title, as a program last set it. Empty if no title is set.
- `owner` (object or null): The process that set the title, as title_changed reported it. Left out if no title is set, a reset cleared it, or the server couldn't tell.
  - `pid` (integer, required): The process ID.
  - `name` (string, required): The process's name, found the same way as name in the result of process. Compare it as well as pid, because the system reuses process IDs.

Notes:

- Call it as `com.superlogical.terminal.title` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.upload_begin`

Reserve a file to upload to the terminal's machine, such as one the user dropped on the terminal, and get a ticket for the rex.data.bytes.v1 data stream that carries its bytes. Once every file of the batch has arrived, the terminal types their paths as if the user had pasted them.

Arguments under `args`:

- `ref` (string, required): The data reference for the file's stream: upload: followed by a new UUID in lower-case canonical form, which the client chooses.
- `name` (string, required): The file's name. Only its last path component is used, without control characters.
- `size` (integer, required): The file's exact size in bytes.
- `batch` (string): An ID the client chooses for a group of files whose paths are typed together. Left out, the file is uploaded on its own.
- `index` (integer): The file's position in its batch, from 0. Paths are typed in this order.
- `count` (integer): How many files the batch has. Required with batch.

Result:

- `ticket` (string, required): A single-use ticket to send in the HELLO message that opens the data connection. Only a data connection from the same user can use it.
- `expires_in_ms` (integer, required): How long the ticket stays valid after the server issues it, in milliseconds.

Notes:

- Call it as `com.superlogical.terminal.upload_begin` with `session_id`, `block_id`, and this method's own arguments under `args`.

### `com.superlogical.terminal.write`

Type text into the terminal as if the user had typed it. The call returns once the text is queued, without waiting for the program to read it.

Arguments under `args`:

- `data` (string, required): The text to type. It can hold escape sequences for keys that aren't text, such as \u001b[A for the up arrow.

Result:

- `bytes_written` (integer, required): The number of bytes queued, which is the length of data in UTF-8 bytes, not characters.

Notes:

- Call it as `com.superlogical.terminal.write` with `session_id`, `block_id`, and this method's own arguments under `args`.

## `com.superlogical.tunnel` Block Methods

Blocks made by `com.superlogical.tunnel` provide these methods. Call each one with `session_id`, `block_id`,
and its own arguments under `args`, as "Calling Block Methods" above describes.

This creator makes blocks of these flavors:

- `com.superlogical.tunnel.dial`
- `com.superlogical.tunnel.listen`

### `com.superlogical.tunnel.info`

Return what the tunnel forwards and how many connections it is relaying.

Arguments under `args`:

None.

Result:

- `flavor` (string, required): How the tunnel runs: dial or listen.
- `target` (string): For a dial tunnel, the host and port it connects to. Left out for a listen tunnel.
- `address` (string): For a listen tunnel, the IP address and port it listens on, including the port it picked if it was asked for port 0. Left out for a dial tunnel.
- `client_address` (string): For a dial tunnel, where the owner said it listens, if it said. Left out for a listen tunnel.
- `client_target` (string): For a listen tunnel, where the owner said it connects to, if it said. Left out for a dial tunnel.
- `last_owner` (string): The client that owned the tunnel most recently. It stays set after the tunnel loses its owner, as it does as soon as the owner's connection drops, so it can be the owner of a tunnel that has none now. Left out if the tunnel has had no owner since it was created or restored.
- `connections` (integer, required): How many connections the tunnel is relaying now.

Notes:

- Call it as `com.superlogical.tunnel.info` with `session_id`, `block_id`, and this method's own arguments under `args`.
