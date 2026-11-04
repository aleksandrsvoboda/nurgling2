# Concepts

This page explains the handful of ideas every script relies on. Read it once; the reference assumes
you know them.

## The client object

Every script works through one object, called `nc` by convention. `@script` passes it to your
`main`; `nurgling.connect()` returns it. Each API namespace is an attribute:

```python
nc.core.ping()                       # namespace "core", op "ping"
nc.ui.find(cls="Button", text="Build")
nc.bots.run("goto_area", params={"areaId": 12})
```

The name of the attribute and method is the op's wire name: `nc.ui.find(...)` sends the op
`ui.find`. Any op can also be called by name, which is useful for ops your SDK does not know yet:

```python
nc.call("ui.find", {"cls": "Button"}, rtype="list[Widget]")
```

Arguments you pass as `None` are left out, so the op's default applies.

## Runs and bot threads

A **run** is one bot thread inside the client, with its own Gear in the Bot Status strip. When your
script connects, the client gives it a run:

- **Managed** scripts (started from the bot menu or a scenario) get the run the client prepared for
  them. Its Gear has the script's name.
- **Attached** scripts (started from a terminal, IDE or notebook) get a new run named
  `py: <file name>` on the character they asked for.

Your requests are executed **on that bot thread, one at a time, in the order they arrive**. This is
what makes scripts behave like Java bots: the same thread binding, the same pathfinder, the same
stop button. It also means:

- A call that takes long (a walk, a wait, a bot) holds up the calls behind it on the same run. If you
  call from several Python threads at once, the calls are queued, not run in parallel.
- A few plumbing ops answer immediately even while a long call runs: everything in `core`,
  `sessions`, `runs`, `run` and `events`, plus `bots.list` and `bots.get`. The reference marks them
  *runs off the bot thread*.
- The Bot Status strip has room for nine Gears. Every run uses one. If it is full, connecting fails
  with `NotSupported`.

The run ends when the script closes its connection (or exits), when you press stop, or when the
character's session closes. When the script's main run ends, the client closes the connection and
ends every other run the script opened.

## Sessions

A **session** is one logged-in character (a tab in the client; background tabs keep running). A run
is bound to exactly one session for its whole life. `nc.character` is that character's name and
`nc.session_info` has the details:

```python
>>> nc.session_info
{'id': 's1', 'character': 'Karasique', 'username': 'karasique', 'headless': False, 'active': True}
>>> [s["character"] for s in nc.sessions.list()]
['Karasique', 'Nevolia']
```

`headless` is true for a background tab. Bots that stop to ask you something cannot run there (they
fail with `NotSupported`).

To control another character from the same script, open a second client with `nc.session(name)`.
See [Several characters](guides/multi-session.md).

## Snapshots and handles

Queries return **snapshots**: copies of the game state at the moment of the call. A snapshot is a
Python `dict` with attribute access on top, so `gob.name` and `gob["name"]` are the same thing, and
`print(gob)` shows everything in it.

| Type | Handle field | Some other fields |
|---|---|---|
| `Gob` (game object) | `gob` | `name`, `rc`, `a`, `pose`, `overlays`, `dist` |
| `Item` | `item` | `name`, `q`, `num`, `slot`, `size`, `contents` |
| `Inventory` | `wdg` | `window`, `size` |
| `Widget` / `Window` | `wdg` | `class`, `caption`, `visible`, `c`, `sz` |
| `Area` | `area` | `name`, `specs`, `rect`, `in`, `out` |

The full field lists are on the [snapshot types](reference/types.md) page.

The **handle** field identifies the live object. When you pass a snapshot back to an op, the client
uses only its handle to find the object again. You can also pass the bare id
(`nc.ui.widget(1234)`). Two snapshots of the same object compare equal, so they work in sets and as
dict keys.

Snapshots do not update themselves. If you need the current state, ask again (`gob.refresh()` returns
a fresh snapshot, or `None` if the object is gone). If the object no longer exists when you use the
handle, the op raises `GobGone`, `ItemGone` or `WidgetGone`.

!!! warning "Don't hold handles across big changes"
    Objects disappear: a stockpile you empty is destroyed, an item you eat is gone, a container's
    items vanish when you close it. Find things again after an action that may have changed them,
    instead of keeping a list from the start of the script.

## Coordinates

| Kind | Python value | Unit |
|---|---|---|
| `Coord` | `(x, y)` floats | **World units.** One tile is 11 units. This is what `gob.rc` holds. |
| `Tile` | `(x, y)` ints | A tile, an inventory cell, or a size in cells. |
| `Rect` | `((x1, y1), (x2, y2))` | A world rectangle, corner to corner, in world units. |

Convert a world position to its tile with `(int(x // 11), int(y // 11))`. Distances (`gob.dist`)
are in world units too: divide by 11 for tiles.

World coordinates are relative to the current session's map. They are not comparable between two
characters, and they can shift after a long-distance teleport. Areas are stored in a way that
survives this; use them for anything you want to keep.

Ops that take a position (a `Coord` parameter) also accept a `Gob`, meaning that object's position.

## Aliases: matching by name

Ops that find things by name take an **alias**:

| Form | Matches |
|---|---|
| `"oak"` | anything whose name contains `oak` |
| `["oak", "birch"]` | either |
| `{"include": ["log"], "exclude": ["block"]}` | contains `log` but not `block` |

Matching is by substring and ignores case, like the client's own bots. Be specific enough: `"board"` also matches
`"Cupboard"`. Game objects are matched by their **resource name**
(`"gfx/terobjs/trees/oak"`, so `"trees/"` finds every tree). Items in inventories are matched by
their **display name** as shown in the tooltip (`"Block of Oak"`), not the resource path.

## Waits and timeouts

Game actions take time: walking, a progress bar, an item appearing in your hand. Don't poll in a loop
with sleeps. Use a wait op: it blocks on the bot thread until the condition holds and raises
`Timeout` if it does not happen in time. Timeouts are in **seconds**, measured on the clock (not game
ticks).

```python
from nurgling import Timeout

try:
    nc.wait.seconds(2)          # also available as nc.sleep(2)
except Timeout:
    ...
```

The `wait` namespace has the named conditions (see [Waits](reference/wait.md)); many actions also
wait for their own result before they return.

Queries retry by themselves while the game is still loading a resource, so you won't see half-loaded
objects.

Every generated method also takes `_timeout=` (seconds). That is a **local** limit: the SDK stops
waiting and raises `Timeout`, but the client keeps working on the request, and later calls on the same
run queue up behind it. Use it to guard against bugs, not to cut an action short. To stop an action,
the player presses stop.

## Errors

Every error the client reports has a type, and the SDK raises the exception class of the same name.
All of them derive from `NurglingError`:

```python
from nurgling import GobGone, PathNotFound, Timeout

try:
    nc.actions.path_to(tree)
except PathNotFound:
    nc.msg("Can't reach that tree, skipping it")
```

Each exception has `.type` (the wire name), `.msg` and `.op` (the op that failed). The
[errors](reference/errors.md) page lists them all. The ones you will meet most:

| Error | Meaning |
|---|---|
| `GobGone`, `ItemGone`, `WidgetGone` | The object behind a handle no longer exists. |
| `PathNotFound` | The pathfinder found no route. |
| `ActionFailed` | A game action ran and reported failure. |
| `NotFound`, `NoSuchArea` | A window, menu option, item or area wasn't there. |
| `Timeout` | A wait did not finish in time. |
| `BadArgs` | Missing, unknown or wrongly typed arguments. |
| `UnknownOp` | This client has no such op (it is older than your SDK). |
| `Stopped` | The run is over. See below. |

### Stopped

`Stopped` means the run has ended: the player pressed stop, the session closed, or the client went
away (`ConnectionLost` is a kind of `Stopped`). Every call after that raises `Stopped` too, so the
only sensible thing is to let it propagate and end the script. `@script` catches it and exits
quietly.

You can clean up in `finally:` blocks, but don't make game calls there: they will raise `Stopped`
again. The client removes your overlays and forms by itself when the run ends.

```python
# Wrong: the stop button can no longer stop this script
while True:
    try:
        work(nc)
    except Exception:
        continue
```

Catch the specific errors you can handle (`except (GobGone, PathNotFound):`), never a bare
`except Exception` around game calls.

## Events

Some things are better pushed to you than polled: chat messages, windows opening, combat moves.
Subscribe with `nc.events(kind, ...)` and iterate:

```python
with nc.events("ui.new", caption="Cupboard*") as windows:
    for ev in windows:
        print("opened", ev.data["caption"], "widget", ev.data["wdg"])
        break
```

Or have a function called on a background thread:

```python
def on_chat(ev):
    print(ev.data)

nc.on("chat.msg", on_chat)
```

- `kind` is a name like `ui.msg`, or a whole family like `ui.*`. The kinds are listed on the
  [Events](reference/events.md) page: raw UI traffic (`ui.new`, `ui.add`, `ui.msg`, `ui.destroy`,
  `ui.out`), `combat.used`, `chat.msg`, `gob.new`, `gob.gone`, `form.*`.
- Keyword arguments filter on fields of the event's `data`. The filtering happens in the client, so
  unwanted events never cross the socket. A string ending in `*` matches a prefix.
- Each event has `ev.kind`, `ev.data` and `ev.sub` (the subscription id).
- `sub.get(timeout=5)` returns the next event or `None`.
- Events come from the run's session only.
- If your script falls far behind (thousands of undelivered events), the client drops new events
  and the next delivered one carries a `dropped` count. Filter narrowly instead of subscribing to
  everything.
- Subscriptions end when you close them, when their run ends, or when the connection closes.

Handlers run on a background thread. Keep them short; if a handler calls ops on the same run, the
calls queue behind whatever the main thread is doing.

## Parameters

A script can declare parameters in its header. The client asks for missing values with a form
before it starts the script (or takes them from a scenario step's settings) and the script reads them
with `nc.run.params()`:

```python
# nurgling: name="Chop trees"
# nurgling-param: count int 10 "How many trees"
# nurgling-param: zone area - "Where to chop"
from nurgling import script

@script
def main(nc):
    p = nc.run.params()
    count = p.get("count", 10)
    zone = p["zone"]            # an area id
```

The [script header](guides/script-header.md) page has the exact syntax and every type. Attached
scripts have no parameters (`nc.run.params()` is `{}`); read your own command line arguments instead.

## Scenario steps

Scripts in the Scripts folder can be steps in a **scenario**, like any bot. Their bot id is
`script:<file>` (`script:hello.py`, or `script:chopper/main.py` for a folder script). The step's
settings supply the parameter values.

A scenario continues only when a step succeeds. A script's outcome is:

1. what it reported with `nc.run.finish(success=...)`, if it called it;
2. otherwise **failure** if `main` raised an exception or returned `False` (`@script` reports both),
   or if the process exited with a non-zero code;
3. otherwise **success**.

```python
@script
def main(nc):
    if not find_work(nc):
        nc.msg("Nothing to do")
        return False            # the scenario stops here
```

A stopped script counts as stopped, not as a success, so the scenario stops with it.
