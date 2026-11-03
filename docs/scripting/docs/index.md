# Nurgling2 scripting

Nurgling2 can run bots written in Python. A script is an ordinary `.py` file: the client lists it in
the bot menu, starts it like any other bot, shows it in the Bot Status strip, and stops it with the
same stop button. You can also drive the client from a terminal, an IDE or a Jupyter notebook while
you play.

```python
# nurgling: name="Say hello"
from nurgling import script

@script
def main(nc):
    nc.msg(f"Hello from Python, {nc.character}!")
```

The script talks to the client over a local socket. Everything it does goes through the client, on
a real bot thread bound to your character, so it behaves like the built-in bots do: it walks with
the client's pathfinder, uses your areas and specialisations, and stops when you press stop.

## The four layers

The API is split into layers. Use the highest one that does the job: the higher the layer, the
stronger the promise that your script keeps working after a client update.

| Layer | What it gives you | Stability |
|---|---|---|
| **1 · Bots and scenarios** | Run any of the client's own bots (`nc.bots.run("...")`) or a saved scenario. List them with their parameters. | Bot ids and parameters are stable. |
| **2 · Curated API** | Queries that return plain data (player, game objects, inventories, equipment, windows, areas, map, chat), actions that wrap the client's tested routines (walk to, open, transfer, take, flower menu, equip, craft...), and waits with timeouts. | Covered by the [stability policy](stability.md): additions only within a major version. |
| **3 · Raw UI** | The widget tree, raw widget messages, low-level clicks, and a stream of every UI message. Anything you can do with the mouse and keyboard. | The framing is stable; what the messages mean is decided by the game server. |
| **4 · Java** | Reflection into the client itself: call any method, read any field, run any Java action by class name. Off unless the script asks for it. | **None.** May break with any client update. |

See [Concepts](concepts.md) for how runs, sessions, snapshots and errors work, and the
[reference](reference/index.md) for every op.

## What you can build

- **Gathering, farming, crafting, container and storage bots.** Layer 2 covers finding objects,
  walking, flower menus, inventories, containers, areas and crafting. This is most bots.
- **Bots that combine existing bots.** Call the client's own bots and scenarios as building blocks
  and add your own logic between them.
- **Long-running helpers.** A script can run for hours, react to events (`nc.on("chat.msg", ...)`)
  and show up as a Gear in the Bot Status strip while it runs.
- **Bots with a settings form**, built from a few header lines or with `nc.form`, and bots that draw
  in the world (highlights, rectangles, labels, ghosts) with `nc.overlay`. Everything a script draws
  is removed when it stops. See [Forms and overlays](guides/script-ui.md).
- **Bots that drive several characters** from one script. See [Several characters](guides/multi-session.md).
- **Combat helpers** that react to fight events. Events arrive in well under a millisecond, which is
  much faster than the server tick, but they must be event driven, not polling loops.
- **Tools in other languages.** The [wire protocol](protocol.md) is plain JSON over TCP. Only the
  Python SDK is official; anything that can open a socket can talk to the client.

## What you cannot build

Scripts **drive** the client. They do not **change** it. These are out of scope:

- New renderers, shaders, map layers or anything that has to run inside the render or UI tick.
- Hiding game objects, rewriting incoming server messages, or changing how existing windows look.
- New permanent client features. That is what client plugins and pull requests are for.

Layer 4 can set fields and call methods inside the client, so you can poke at some of this, but your
code still runs outside the client, one request at a time. It cannot hook into a frame.

Scripts also cannot do anything the game itself does not allow. The client sends the same messages a
player would; the server decides what happens.

## Where to go next

- [Getting started](getting-started.md): install Python, turn scripting on, write and run your first script.
- [Cookbook](cookbook/index.md): complete scripts you can copy.
- [Security](guides/security.md): read this before running scripts someone else wrote.
