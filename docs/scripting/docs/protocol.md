# Wire protocol

This page specifies the protocol between a script and the client, for writing a client library in
another language or debugging at the socket level. The Python SDK is one implementation of it; only
the Python SDK is official.

Protocol version: **API 1.0**. The machine-readable form of every op is
[`api-schema.json`](https://github.com/aleksandrsvoboda/nurgling2/blob/master/docs/scripting/schema/api-schema.json),
which a connected script can also fetch with the `core.describe` op.

## Transport

- TCP to `127.0.0.1` on the port from the [info file](#finding-the-client) or `NURGLING_PORT`.
  The client only listens on the loopback interface.
- One connection per script. A connection can serve several [runs](#runs).
- Raw TCP, no HTTP, no TLS (traffic never leaves the machine).

## Framing

Every message in both directions is a **frame**:

```
+--------------------+---------------------------------+
| length: 4 bytes    | payload: `length` bytes         |
| unsigned, big end. | one JSON object, UTF-8          |
+--------------------+---------------------------------+
```

- The length counts payload bytes only, not the 4 header bytes.
- The payload is a single JSON object. No trailing newline is needed.
- Maximum payload: 16 MiB (16 777 216 bytes). The client closes the connection on a larger or
  negative length, and on a payload that is not valid JSON.

## Handshake

The first frame from the script must be a `hello` request, sent within **5 seconds** of connecting.

```json
{"id": 1, "op": "hello", "args": {
    "token": "9f2c...e1",
    "api": "1.0",
    "client": "my_tool.js",
    "java": false,
    "session": "Karasique"
}}
```

| Argument | Required | Meaning |
|---|---|---|
| `token` | yes | The token from the info file or `NURGLING_TOKEN`. |
| `api` | no | The API version the script speaks, `"major.minor"`. The **major** part must equal the client's; the minor part may differ. Omitted means "same as the client". |
| `client` | no | A name for the script, shown on its Gear as `py: <client>`. Default `"script"`. |
| `java` | no | `true` enables the layer-4 `java.*` ops on this connection. Default `false`. |
| `run` | no | For scripts the client launched: the value of `NURGLING_RUN`. Claims the run the client prepared. |
| `session` | no | For attaching: the session to bind to (character name, username or session id, case-insensitive). Omitted means the visible tab. Ignored when `run` is given. |

On success:

```json
{"id": 1, "ok": {
    "api": "1.0",
    "client": "a1b2c3d",
    "run": "r7",
    "session": {"id": "s1", "character": "Karasique", "username": "karasique", "headless": false, "active": true},
    "java": false,
    "params": {}
}}
```

| Field | Meaning |
|---|---|
| `api` | The client's API version. |
| `client` | The client build (git revision), or `"unknown"`. |
| `run` | Id of the connection's **primary run**. |
| `session` | The session the primary run is bound to. |
| `java` | Whether layer 4 is enabled. |
| `params` | Parameter values for a managed run (from the start form or scenario step); `{}` when attaching. |

On failure the client answers with an error response and closes the connection:

| Error type | When |
|---|---|
| `AuthFailed` | The first frame is not `hello`, or the token is wrong. |
| `NotSupported` | The `api` major version differs, or no bot thread could be started (the Bot Status strip is full). |
| `NotFound` | `run` does not name a pending managed run (it was already claimed, or stopped), or no session matches `session` / no character is logged in. |

## Requests

```json
{"id": 2, "op": "ui.find", "args": {"cls": "Button", "text": "Build"}, "run": "r9"}
```

| Field | Meaning |
|---|---|
| `id` | Any JSON value; echoed in the response. Use unique integers. Omit it (or send `null`) to get no response at all. |
| `op` | The op name, `namespace.verb`. |
| `args` | An object with the op's arguments. Optional; defaults to `{}`. A `null` value counts as not given. Unknown argument names are rejected with `BadArgs`. |
| `run` | Optional. The run that executes the request. Omitted or `null` means the primary run. |

## Responses

Exactly one per request that has an `id`:

```json
{"id": 2, "ok": [{"wdg": 4711, "class": "haven.Button", "text": "Build", "parent": 4700, "visible": true, "c": [10, 50], "sz": [80, 24]}]}
{"id": 3, "err": {"type": "GobGone", "msg": "gob 123456 no longer exists"}}
```

- `ok` holds the result (`null` for ops that return nothing).
- `err` holds an [error object](#errors).
- **Match responses by `id`, not by order.** Requests to one run are executed in the order they
  arrive, but ops marked *runs off the bot thread* in the reference (`core.*`, `sessions.*`,
  `runs.*`, `run.*`, `events.*`, `bots.list`, `bots.get`) are answered immediately, ahead of slower
  requests, and different runs work in parallel.
- An error in a request without an `id` is reported as an `error` event instead.

## Runs

A run is one bot thread in the client, bound to one session, with its own Gear in that session's Bot
Status strip. Requests that touch the game execute on their run's thread, one at a time.

- The `hello` creates or claims the **primary run**.
- `runs.open {"session": name}` starts another run on another session and returns
  `{"run": id, "session": {...}}`. Send requests with `"run": id` to use it.
- `runs.close {"run": id}` ends a run after its queued requests.
- A request for a run that has ended, or that does not exist, fails with `Stopped`.

When a run ends (stop button, `runs.close`, session closed), the client:

1. answers every request still queued on that run, including the one executing, with a `Stopped` error;
2. removes the run's event subscriptions;
3. sends a `run.ended` event;
4. if it was the primary run: stops all other runs of the connection and **closes the connection**
   after flushing pending frames.

When the script closes the connection, the client ends all of its runs; requests still queued are
dropped without responses.

## Events

Events are frames without an `id`:

```json
{"ev": "ui.msg", "sub": 7, "data": {"wdg": 91, "class": "haven.Window", "msg": "tt", "args": []}}
{"ev": "ui.msg", "sub": 7, "data": {"wdg": 91, "class": "haven.Window", "msg": "tt", "args": []}, "dropped": 120}
```

| Field | Meaning |
|---|---|
| `ev` | Event kind. |
| `sub` | The subscription it was delivered for. |
| `data` | Event payload; its fields depend on the kind. |
| `dropped` | Present when the client discarded events because the script was not reading fast enough: the number lost since the last delivered event. |

Subscribe with `events.subscribe {"kinds": [...], "filter": {...}}`, which returns the subscription
id. `kinds` are exact names or a family with `.*` (`"ui.*"`). `filter` keeps events whose `data`
fields equal the given values; a string value ending in `*` is a prefix match, numbers compare
numerically, anything else compares as text. A subscription only sees events from its run's
session. End it with `events.unsubscribe {"sub": id}`.

| Kind | Data |
|---|---|
| `ui.new` | `wdg`, `type`, `class`, `args`, `caption` (windows): the server created a widget. |
| `ui.add` | `wdg`, `parent`, `class`, `args`, `caption` (windows): a widget was attached to a parent. |
| `ui.msg` | `wdg`, `class`, `msg`, `args`: the server sent a widget message. |
| `ui.destroy` | `wdg`, `class`: a widget was removed. |
| `ui.out` | `wdg`, `class`, `msg`, `args`: the client sent a widget message to the server. |
| `combat.used` | `action`: a combat move was used. |
| `chat.msg`, `gob.new`, `gob.gone`, `form.*` | See the [Events](reference/events.md) reference. |

Widgets inside `args` are sent as their ids, coordinates as `[x, y]`.

Two events are sent without `sub`, to every connection they concern:

| Kind | Data |
|---|---|
| `run.ended` | `run` (id), `stopped` (true if it was interrupted rather than closed normally). |
| `error` | `type`, `msg`: an error for a request that had no `id`. |

The client queues events per connection. If more than about 5000 frames are waiting, new events are
dropped and counted in `dropped`.

## Errors

```json
{"type": "PathNotFound", "msg": "no route to gob 123456"}
```

`type` is one of the names below; `msg` is for people. Treat an unknown `type` as `Internal`: newer
clients may add types.

| Type | Meaning |
|---|---|
| `Stopped` | The run has ended. Every later request to it fails the same way. |
| `Timeout` | A wait or blocking call did not finish within its timeout. |
| `GobGone`, `ItemGone`, `WidgetGone` | The object behind a handle no longer exists. |
| `PathNotFound` | The pathfinder found no route. |
| `NotFound` | Something the op needed was not found. |
| `NoSuchArea` | No area with that id, name or specialisation. |
| `BadArgs` | Missing, unknown or wrongly typed arguments, or a frame without `op`. |
| `ActionFailed` | A game action ran and reported failure. |
| `NotSupported` | The op cannot run here (layer 4 not enabled, interactive bot on a background session...). |
| `Cancelled` | The player dismissed a form or picker. |
| `AuthFailed` | Bad handshake. |
| `UnknownOp` | No op with that name in this client. |
| `JavaError` | A Java exception inside a `java.*` op. |
| `Internal` | A bug in the client; the run continues. |

## Values

| Schema type | JSON |
|---|---|
| `int`, `float`, `bool`, `str`, `dict`, `list[T]`, `null` | as in JSON |
| `Coord` | `[x, y]` numbers, world units (11 per tile). Ops taking a `Coord` also accept a `Gob`. |
| `Tile` | `[x, y]` integers |
| `Rect` | `[[x1, y1], [x2, y2]]` |
| `Alias` | `"text"`, `["a", "b"]` or `{"include": [...], "exclude": [...]}` |
| `Gob`, `Item`, `Inventory`, `Widget`, `Area` | snapshot objects (see [types](reference/types.md)) |

A snapshot's **handle field** (`gob`, `item`, `wdg` for inventories and widgets, `area`) identifies the
live object. Wherever an op takes one of these types, send the snapshot, an object with just the
handle field (`{"gob": 123456}`), or the bare id (`123456`). Inventories also accept `"main"` or a
window caption; areas an id or a name.

## Finding the client

### The info file

While scripting is enabled, the client keeps a JSON file with the connection details:

```json
{"port": 51234, "token": "9f2c...e1", "api": "1.0", "pid": 12345}
```

- Windows: `%APPDATA%\Haven and Hearth\scripting.json`
- Elsewhere: `~/.haven/scripting.json`

The client rewrites it each time its scripting server starts (the token and, by default, the port
change each time) and deletes it when the server is stopped. A file left behind by a client that is
no longer running is stale: the connection is refused, or `pid` is not running.

### Environment of managed scripts

Scripts the client starts itself (the Scripts folder, scenario steps) get these variables:

| Variable | Value |
|---|---|
| `NURGLING_PORT` | The server port. |
| `NURGLING_TOKEN` | The token. |
| `NURGLING_RUN` | The run id to send as `run` in `hello`. It is valid once, and only until the client gives up waiting (30 s). |
| `NURGLING_SCRIPT` | The script's path relative to the scripts folder (`hello.py`, `chopper/main.py`); a good `client` name. |
| `NURGLING_JAVA` | `1` if the header says `java=true`, else `0`. Send it as `java`. |
| `PYTHONPATH` | Starts with the SDK bundled with the client. |
| `PYTHONIOENCODING` | `utf-8` |

The working directory is the script's folder, and stdout and stderr go to the script's log file.

The SDK also reads `NURGLING_INFO`: a path that overrides the info file location. The client does
not set it; it is for tests and unusual setups.

A library should use, in this order: `NURGLING_PORT` + `NURGLING_TOKEN` (+ `NURGLING_RUN`) if set,
else the info file (and then never `NURGLING_RUN`).

## A minimal client: Python, no SDK

About fifty lines with only the standard library: connect, hello, a few calls.

```python
import json, os, socket, struct, sys
from pathlib import Path


def info_path():
    if os.environ.get("NURGLING_INFO"):
        return Path(os.environ["NURGLING_INFO"])
    appdata = os.environ.get("APPDATA")
    if appdata and (Path(appdata) / "Haven and Hearth").is_dir():
        return Path(appdata) / "Haven and Hearth" / "scripting.json"
    return Path.home() / ".haven" / "scripting.json"


def send(sock, frame):
    data = json.dumps(frame).encode("utf-8")
    sock.sendall(struct.pack(">I", len(data)) + data)


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf += chunk
    return buf


def recv(sock):
    (n,) = struct.unpack(">I", recv_exact(sock, 4))
    return json.loads(recv_exact(sock, n).decode("utf-8"))


def call(sock, rid, op, args=None):
    send(sock, {"id": rid, "op": op, "args": args or {}})
    while True:
        f = recv(sock)
        if "ev" in f:                       # an event; this simple client ignores them
            continue
        if f.get("id") != rid:
            continue
        if "err" in f:
            raise RuntimeError(f"{op}: {f['err']['type']}: {f['err']['msg']}")
        return f["ok"]


info = json.loads(info_path().read_text(encoding="utf-8"))
sock = socket.create_connection(("127.0.0.1", info["port"]))
hello = call(sock, 1, "hello", {"token": info["token"], "api": "1.0", "client": "raw.py"})
print("connected as", hello["session"]["character"], "run", hello["run"])
print("client time:", call(sock, 2, "core.ping"))
for w in call(sock, 3, "ui.find", {"cls": "Window"}):
    print("window", w["wdg"], w.get("caption"))
sock.close()                                # ends the run and removes its Gear
```

## A minimal client: JavaScript (Node)

The same in Node 18 or newer, no packages. It handles out-of-order responses and prints events.

```javascript
// Node 18+, no packages. Run: node raw.js
const net = require("net"), fs = require("fs"), path = require("path"), os = require("os");

function infoPath() {
  if (process.env.NURGLING_INFO) return process.env.NURGLING_INFO;
  const appdata = process.env.APPDATA;
  if (appdata && fs.existsSync(path.join(appdata, "Haven and Hearth")))
    return path.join(appdata, "Haven and Hearth", "scripting.json");
  return path.join(os.homedir(), ".haven", "scripting.json");
}

const info = JSON.parse(fs.readFileSync(infoPath(), "utf8"));
const sock = net.connect(info.port, "127.0.0.1");
const pending = new Map();
let nextId = 1, buf = Buffer.alloc(0);

function call(op, args = {}) {
  const id = nextId++;
  const body = Buffer.from(JSON.stringify({ id, op, args }), "utf8");
  const head = Buffer.alloc(4);
  head.writeUInt32BE(body.length);
  sock.write(Buffer.concat([head, body]));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject, op }));
}

sock.on("data", (chunk) => {
  buf = Buffer.concat([buf, chunk]);
  while (buf.length >= 4 && buf.length >= 4 + buf.readUInt32BE(0)) {
    const n = buf.readUInt32BE(0);
    const frame = JSON.parse(buf.subarray(4, 4 + n).toString("utf8"));
    buf = buf.subarray(4 + n);
    if ("ev" in frame) { console.log("event", frame.ev, frame.data); continue; }
    const p = pending.get(frame.id);
    if (!p) continue;
    pending.delete(frame.id);
    if ("err" in frame) p.reject(new Error(`${p.op}: ${frame.err.type}: ${frame.err.msg}`));
    else p.resolve(frame.ok);
  }
});
sock.on("close", () => pending.forEach((p) => p.reject(new Error("connection closed"))));

sock.on("connect", async () => {
  try {
    const hello = await call("hello", { token: info.token, api: "1.0", client: "raw.js" });
    console.log("connected as", hello.session.character, "run", hello.run);
    console.log("client time:", await call("core.ping"));
    for (const w of await call("ui.find", { cls: "Window" }))
      console.log("window", w.wdg, w.caption);
  } catch (e) {
    console.error(e.message);
  } finally {
    sock.end();                                // ends the run and removes its Gear
  }
});
```

A full library adds: a reader that runs independently of callers, `run` fields for multi-session,
subscription routing by `sub`, `run.ended` handling, and mapping error types to exceptions. The
Python SDK's `_transport.py` and `client.py` do all of this in about 400 lines and are a good model.
