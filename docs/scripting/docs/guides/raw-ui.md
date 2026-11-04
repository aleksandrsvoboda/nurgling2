# Raw UI (layer 3)

Everything you do in the game by hand ends up as a **widget message**: the client tells the server
"widget 1234 says `click` with these arguments". Layer 3 lets a script read the widget tree and send
those messages itself. If you can do it with the mouse and keyboard, a script can do it here, even
when the curated API has no op for it yet.

The price: what a message means is decided by the game server, not by Nurgling. Message names and
arguments can change with a game update, and nothing checks that the arguments make sense before
they are sent. Prefer a layer-2 op when there is one.

## The widget tree

The game UI is a tree of widgets: windows, buttons, labels, inventories, the map. Each widget has an
id (`wdg`), a Java class, and windows have a caption.

```python
# The top of the tree; depth keeps the answer small (the whole tree has thousands of nodes)
tree = nc.ui.tree(depth=1)
for child in tree["children"]:
    print(child["wdg"], child["class"], child.get("caption"))

# Search: class name ends with cls, and/or caption / button text / label text contains text
for w in nc.ui.find(cls="Window"):
    print(w.wdg, w.get("caption"), w.visible)

build = nc.ui.find(cls="Button", text="Build")

# One widget, or None if it is gone
w = nc.ui.widget(1234)
```

`ui.tree` and `ui.find` take a `root` widget to look only inside one window. Nodes are `Widget`
snapshots (`wdg`, `class`, `caption`, `parent`, `visible`, `c` position, `sz` size); buttons and
labels also have `text`.

## Sending messages

```python
nc.ui.wdgmsg(window, "close")                 # what the window's X button sends
nc.ui.click_button("Build", window="Palisade") # find a button by text and click it
```

`ui.wdgmsg(wdg, msg, args)` sends `msg` from widget `wdg` with the list `args`, exactly as that
widget would. Argument conversion:

| Python | Sent as |
|---|---|
| `int` | integer |
| `float` | float |
| `str`, `bool`, `None` | as is |
| any 2-element list, e.g. `[3, 4]` | a `Coord` (integer pair) |

Because every pair becomes a `Coord`, you cannot send a plain two-item list. Map positions in
messages are in the server's own fine units, not world units; for anything on the map use the
`raw.*` ops below, which convert for you.

## Typed primitives

These are the messages bots use most, with the conversions done for you. Positions are in world
units; modifiers are `1` shift, `2` ctrl, `4` alt, added together.

| Op | What it does |
|---|---|
| `raw.click_gob(gob, button=3, mods=0)` | Click an object. Button 3 (right) opens its flower menu. Does not walk there first. |
| `raw.click_ground(pos, button=1)` | Click the ground. A left click walks in a straight line (no pathfinding). |
| `raw.flower_options()` / `raw.flower_choose(option)` | Read the open flower menu and pick an entry. |
| `raw.menu_act(["carry"])` | Trigger a menu-grid action by internal name (`["destroy"]`, `["dig"]`, `["bp", "palisade"]`); then click a target. |
| `raw.pagina_use("Drink")` | Use a menu-grid entry by display or resource name. Recipes open their craft window. |
| `raw.place(pos, angle)` | Place what you are placing (a lifted object, a build ghost). |
| `raw.item_take(item)` | Pick an item up onto the cursor. |
| `raw.item_transfer(item)` | Shift-click: move it to the other open inventory. |
| `raw.item_drop(item)` | Drop it at your feet. |
| `raw.item_interact(item)` | Right-click an item (opens its flower menu). |
| `raw.itemact_item(item)` / `raw.itemact_gob(gob)` / `raw.itemact_ground(pos)` | Use the item in your hand on an item, an object or the ground. |
| `raw.inv_drop(inventory, slot)` | Put the item in your hand into an inventory cell. |
| `raw.invxf(from, to, count)` | Move items between open inventories like shift+wheel. |
| `raw.cursor()` | The current cursor resource, e.g. to see whether an action is armed. |

These ops **send and return**: they do not wait for the result. After a click, wait for what you
expect (the flower menu, the item in your hand, the window) before the next step. The
[Raw input](../reference/raw.md) reference has every parameter.

```python
nc.raw.click_gob(tree, button=3)            # right-click opens the flower menu
options = nc.wait.flower_menu(timeout=5)    # waits for it and returns its options
if "Chop" in options:
    nc.raw.flower_choose("Chop")
```

(`nc.actions.flower(tree, "Chop")` does all of this in one layer-2 call.)

When there is no ready-made wait, subscribe to the widget you expect **before** you send the message,
then wait for its event:

```python
with nc.events("ui.new", caption="Cupboard*") as opened:
    nc.raw.click_gob(cupboard, button=3)
    if opened.get(timeout=5) is None:
        raise RuntimeError("the cupboard did not open")
```

## Discovering messages with `ui.out`

You don't need to read the game's source to learn which message does what. Do the thing by hand
while a script listens, and the client shows you the exact message:

```python
from nurgling import connect

with connect() as nc:
    print("Do the action by hand now; Ctrl+C to stop")
    try:
        for ev in nc.events("ui.out"):
            d = ev.data
            print(f"wdg={d['wdg']} class={d['class']} msg={d['msg']!r} args={d['args']}")
    except KeyboardInterrupt:
        pass
```

Typical output after clicking *Build* in a recipe window and closing it:

```
wdg=4711 class=haven.Button msg='activate' args=[]
wdg=4702 class=haven.Window msg='close' args=[]
```

Then replay it:

```python
nc.ui.wdgmsg(4702, "close")
```

Widget ids are assigned at runtime and differ every time a window opens. Find the widget again
(`ui.find` by caption or class) each time instead of hard-coding the id.

Other event kinds help too:

| Kind | Fires when | Data |
|---|---|---|
| `ui.out` | The client sends a message to the server (everything you do). | `wdg`, `class`, `msg`, `args` |
| `ui.msg` | The server sends a message to a widget. | `wdg`, `class`, `msg`, `args` |
| `ui.new` | The server creates a widget. | `wdg`, `type`, `class`, `args`, `caption` (windows) |
| `ui.add` | A widget is added to a parent. | `wdg`, `parent`, `class`, `args`, `caption` (windows) |
| `ui.destroy` | A widget is removed. | `wdg`, `class` |

Widgets inside the arguments are given as their ids; positions as `[x, y]`. Map positions in `ui.out`
are in the server's fine units, so replay map clicks with `ui.wdgmsg` as recorded, or convert them
to world units and use `raw.click_ground`.

Filter to keep the stream readable: `nc.events("ui.out", msg="click")`,
`nc.events("ui.new", caption="Cupboard*")`.

!!! tip "Found something useful?"
    If you need layer 3 for something common, open an issue: frequently needed raw sequences are the
    candidates for new layer-2 ops, which come with waits and stability guarantees.
