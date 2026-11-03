# Overlays

Script UI: highlights, shapes, labels and markers drawn in the world. Removed when the run ends.

## `overlay.clear`

<small>since 1.0 · action</small>

```python
nc.overlay.clear() -> int
```

Removes every overlay this run created. The client does this by itself when the run ends.

**Returns** `int`: How many overlays were removed.

```python
nc.overlay.clear()
```

## `overlay.ghost`

<small>since 1.0 · action</small>

```python
nc.overlay.ghost(res, pos, angle=None) -> int
```

Shows a translucent placement ghost of a resource's model at a position, like the build bots' previews. The resource is loaded and checked first; a name that does not exist or has nothing to draw is rejected.

| Parameter | Type | Description |
|---|---|---|
| `res` | `str` | Resource name, e.g. "gfx/terobjs/cupboard". |
| `pos` | `Coord\|Gob` | World position [x, y], or a gob's position. |
| `angle` | `float` | Rotation in radians. *Optional, default 0.* |

**Returns** `int`: Overlay id.

**Raises** [`NotFound`](errors.md#notfound), [`ActionFailed`](errors.md#actionfailed)

```python
nc.overlay.ghost("gfx/terobjs/cupboard", [x, y], angle=math.pi / 2)
```

## `overlay.highlight`

<small>since 1.0 · action</small>

```python
nc.overlay.highlight(gob, color=None) -> int
```

Tints a game object, like the client's item-search highlight. Colour as "#rrggbb", "#rrggbbaa" or [r, g, b] / [r, g, b, a] (0-255). The colour's alpha is the strength of the tint (255 paints a flat silhouette). The highlight lasts while the object stays loaded; it is removed with overlay.remove, overlay.clear or when the run ends.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | The object. |
| `color` | `str\|list[int]` | Tint colour. *Optional, default "#e99c54a0".* |

**Returns** `int`: Overlay id.

**Raises** [`GobGone`](errors.md#gobgone)

```python
oid = nc.overlay.highlight(tree, color="#40ff40")
```

## `overlay.label`

<small>since 1.0 · action</small>

```python
nc.overlay.label(at, text, color=None, height=None) -> int
```

Shows outlined text above a game object or at a world position. Colour as "#rrggbb", "#rrggbbaa" or [r, g, b] / [r, g, b, a] (0-255).

| Parameter | Type | Description |
|---|---|---|
| `at` | `Gob\|Coord` | An object (the label follows it) or a world position [x, y]. |
| `text` | `str` | The text. |
| `color` | `str\|list[int]` | Text colour. *Optional, default "#ffffff".* |
| `height` | `float` | Height above the ground, in world units. *Optional, default 15.* |

**Returns** `int`: Overlay id.

**Raises** [`GobGone`](errors.md#gobgone)

```python
nc.overlay.label(barrel, "water: 87 l", color="#80c0ff")
```

## `overlay.line`

<small>since 1.0 · action</small>

```python
nc.overlay.line(points, color=None, closed=None, width=None) -> int
```

Draws a line through world points on the ground (a route, a fence plan). It is drawn on top of everything and follows the terrain. Colour as "#rrggbb", "#rrggbbaa" or [r, g, b] / [r, g, b, a] (0-255).

| Parameter | Type | Description |
|---|---|---|
| `points` | `list[Coord]` | World positions [[x, y], ...] (gob snapshots work too); at least two. |
| `color` | `str\|list[int]` | Line colour. *Optional, default "#e99c54".* |
| `closed` | `bool` | Join the last point back to the first. *Optional, default false.* |
| `width` | `float` | Line width in pixels. *Optional, default 2.* |

**Returns** `int`: Overlay id.

```python
nc.overlay.line([me.rc, tree.rc, barrel.rc], color="#ffff00")
```

## `overlay.marker`

<small>since 1.0 · action</small>

```python
nc.overlay.marker(pos, text=None, color=None) -> int
```

Puts a temporary marker with a label on the minimap and the map window (it is not saved as a map marker). Colour as "#rrggbb", "#rrggbbaa" or [r, g, b] / [r, g, b, a] (0-255).

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord\|Gob` | World position [x, y], or a gob's position. |
| `text` | `str` | Label under the marker. *Optional, default "".* |
| `color` | `str\|list[int]` | Marker colour. *Optional, default "#e99c54".* |

**Returns** `int`: Overlay id.

```python
nc.overlay.marker(boar, "boar", color="#ff4040")
```

## `overlay.rect`

<small>since 1.0 · action</small>

```python
nc.overlay.rect(rect, color=None, label=None, fill=None) -> int
```

Draws a rectangle on the ground like a saved area: a translucent fill over the tiles it covers and a border at its exact edges, with an optional label in the middle. Colour as "#rrggbb", "#rrggbbaa" or [r, g, b] / [r, g, b, a] (0-255). The fill uses the colour at about a third of its alpha.

| Parameter | Type | Description |
|---|---|---|
| `rect` | `Rect` | World rectangle [[x1, y1], [x2, y2]], e.g. from form.pick_area or an area snapshot. |
| `color` | `str\|list[int]` | Border colour. *Optional, default "#e99c54".* |
| `label` | `str` | Text shown in the middle. *Optional.* |
| `fill` | `bool` | Fill the covered tiles. *Optional, default true.* |

**Returns** `int`: Overlay id.

```python
r = nc.form.pick_area()
nc.overlay.rect(r, color="#40c0ff", label="stockpiles")
```

## `overlay.remove`

<small>since 1.0 · action</small>

```python
nc.overlay.remove(id) -> bool
```

Removes one overlay this run created.

| Parameter | Type | Description |
|---|---|---|
| `id` | `int` | Overlay id returned by an overlay.* call. |

**Returns** `bool`: False if there was no such overlay (already removed).

```python
nc.overlay.remove(oid)
```

## `overlay.tiles`

<small>since 1.0 · action</small>

```python
nc.overlay.tiles(tiles, color=None) -> int
```

Fills a set of map tiles with a translucent colour, e.g. to show planned plots or the tiles a bot will dig. Colour as "#rrggbb", "#rrggbbaa" or [r, g, b] / [r, g, b, a] (0-255). At most 20000 tiles per overlay.

| Parameter | Type | Description |
|---|---|---|
| `tiles` | `list[Tile]` | Tile coordinates [[tx, ty], ...] (world position / 11, floored). |
| `color` | `str\|list[int]` | Fill colour; its alpha is the opacity. *Optional, default "#e99c5460".* |

**Returns** `int`: Overlay id.

```python
tc = [[x, y] for x in range(10, 20) for y in range(5, 8)]
nc.overlay.tiles(tc, color=[64, 192, 255, 80])
```
