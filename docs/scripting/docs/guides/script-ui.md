# Forms and overlays

Built-in bots ask for settings in a small window and draw things in the world: build ghosts, area
highlights, route lines. Scripts can do both, in the client's own style, without writing any UI
code. Everything a script shows belongs to its run and **disappears when the run ends**, also when
it is stopped or crashes, so a script never leaves stray windows or markers behind.

All exact parameters are in the [Forms](../reference/form.md) and [Overlays](../reference/overlay.md)
reference.

## The start form from the header

The simplest form needs no code. Declare parameters in the [script header](script-header.md) and the
client asks for them before the script starts:

```python
# nurgling: name="Chop trees"
# nurgling-param: count int 10 "How many trees"
# nurgling-param: zone area - "Where to chop"
# nurgling-param: mode choice[fast,careful] fast "Mode"
```

The form has one field per parameter, pre-filled with the defaults. The values arrive in
`nc.run.params()`. If the player closes the form, the script is not started.

## Forms at runtime

A script can also ask while it runs, for example after it has looked around. `form.ask` shows a
settings form and blocks until the player presses OK; it returns the values by field name:

```python
from nurgling import Cancelled

try:
    answer = nc.form.ask("Sort the cellar", [
        {"name": "target", "type": "area", "label": "Put everything into", "required": True},
        {"name": "keep_best", "type": "bool", "label": "Keep the best in my inventory", "default": True},
        {"name": "min_q", "type": "int", "label": "Ignore items below quality", "default": 10},
        {"name": "mode", "type": "choice", "values": ["fast", "careful"], "default": "fast"},
    ])
except Cancelled:
    return False          # the player closed the form
```

| Field type | Shows | Returns |
|---|---|---|
| `int`, `float` | a number entry | the number; `None` if an optional field is left empty |
| `bool` | a check box | `True` / `False` |
| `str` | a text entry | the text |
| `choice` | a dropdown of `values` | the chosen value |
| `area` | your saved areas, plus *Select on map...* (leave that out with `"map": False`) | the area id, or a `Rect` if the player dragged one on the map |
| `gob` | a button to click an object in the world | the object id; `None` if optional and not picked |
| `label` | static text (give the text as `label`) | nothing |

Every field takes `name`, and optionally `label` (defaults to the name), `default` and `required`
(the form does not accept OK until required fields are filled).

Smaller dialogs for single questions:

| Op | Asks |
|---|---|
| `nc.form.confirm(text, title=None)` | Yes or no; returns a `bool`. Closing the window counts as no. |
| `nc.form.text(prompt, default="")` | A line of text. |
| `nc.form.pick_gob(prompt)` | Click an object; returns a `Gob`. |
| `nc.form.pick_area(prompt)` | Drag a rectangle on the map; returns a `Rect`, snapped to tiles. |
| `nc.form.message(text, title=None)` | Nothing: an information window with OK. Returns at once. |

- If the player dismisses a form, text prompt or picker, the call raises `Cancelled`
  (`confirm` returns `False` instead).
- Each one takes `timeout=` in seconds; the default `0` waits as long as it takes. On timeout the
  call raises `Timeout`.
- Forms need a **visible** session. A script driving a background character should ask through the
  client of a visible one.

## Overlays

Overlays draw in the world and on the map. Each call returns an overlay id.

| Op | Draws |
|---|---|
| `nc.overlay.highlight(gob, color=...)` | Tints a game object, like the item-search highlight. |
| `nc.overlay.rect(rect, color=..., label=None, fill=True)` | A rectangle on the ground, drawn like a saved area. |
| `nc.overlay.tiles(tiles, color=...)` | Fills a set of tiles (up to 20 000 per overlay). |
| `nc.overlay.line(points, color=..., closed=False, width=2)` | A line through world points: a route, a fence plan. |
| `nc.overlay.ghost(res, pos, angle=0)` | A translucent placement ghost of a model, like the build bots' previews. |
| `nc.overlay.label(at, text, color=...)` | Text above an object or at a position. |
| `nc.overlay.marker(pos, text="", color=...)` | A temporary marker on the minimap and map window (not saved). |

Colours are `"#rrggbb"`, `"#rrggbbaa"` or `[r, g, b]` / `[r, g, b, a]` with 0-255 values.

Remove one with `nc.overlay.remove(id)`, or all of this run's with `nc.overlay.clear()`. Whatever is
still showing when the run ends is removed by the client.

```python
trees = nc.gobs.find(name="trees/", radius=200)
marks = [nc.overlay.highlight(t, color="#40c0a0a0") for t in trees]
if not nc.form.confirm(f"Chop these {len(trees)} trees?"):
    return False
nc.overlay.clear()
```

## Design tips

- Show the player what the script is about to do, then ask once. Don't open forms in a loop.
- Keep forms short; put rarely changed options in header parameters with defaults.
- Prefer `area` fields and `pick_area` over asking the player to type coordinates.
- Mark objects the script skipped (unreachable, wrong quality) so the player can look at them.
- Draw a rectangle instead of thousands of single-tile overlays.
