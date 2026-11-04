# Map

Tiles, reachability and coordinates.

## `map.from_global`

<small>since 1.0 · query</small>

```python
nc.map.from_global(grid, x, y) -> Coord|null
```

Converts a global position (grid id + offset, from `map.to_global`) back into this session's world coordinates, or null if that grid is not loaded here.

| Parameter | Type | Description |
|---|---|---|
| `grid` | `int` | Map grid id. |
| `x` | `float` | Offset inside the grid, world units. |
| `y` | `float` | Offset inside the grid, world units. |

**Returns** `Coord|null`

```python
pos = nc.map.from_global(spot["grid"], spot["x"], spot["y"])
if pos:
    nc.actions.path_to(pos)
```

## `map.path_length`

<small>since 1.0 · query</small>

```python
nc.map.path_length(target) -> float|null
```

How far the player would walk to reach a gob or position along the pathfinder's route, in world units (0 when already there), or null when there is no route. Never shorter than the straight-line distance.

| Parameter | Type | Description |
|---|---|---|
| `target` | `Gob` or `Coord` | A gob (handle or id) or a world position [x, y]. |

**Returns** `float|null`

**Raises** [`GobGone`](errors.md#gobgone)

```python
trees = nc.gobs.find(name="trees/", radius=300)
best = min(trees, key=lambda t: nc.map.path_length(t) or 1e9)
```

## `map.reachable`

<small>since 1.0 · query</small>

```python
nc.map.reachable(target) -> bool
```

Whether the client's pathfinder finds a walkable route from the player to a game object (to a spot next to it) or to a position. Runs the pathfinder once without moving; it only knows the loaded area around the player, so far targets read as unreachable.

| Parameter | Type | Description |
|---|---|---|
| `target` | `Gob` or `Coord` | A gob (handle or id) or a world position [x, y]. |

**Returns** `bool`

**Raises** [`GobGone`](errors.md#gobgone)

```python
if nc.map.reachable(chest):
    nc.actions.path_to(chest)
```

## `map.tile`

<small>since 1.0 · query</small>

```python
nc.map.tile(pos) -> str|null
```

The terrain under a world position as its tile resource name, e.g. "gfx/tiles/grass", "gfx/tiles/water", "gfx/tiles/rocks/...". Null if that part of the map is not loaded.

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord` or `Gob` | World position, or a gob for its position. |

**Returns** `str|null`

**Raises** [`GobGone`](errors.md#gobgone)

```python
if "water" in (nc.map.tile(nc.gobs.player()) or ""):
    print("swimming")
```

## `map.tiles`

<small>since 1.0 · query</small>

```python
nc.map.tiles(rect, name=None) -> list[dict]
```

The tiles covering a world rectangle, optionally only those whose resource name matches `name`. At most 10000 tiles (100x100) per call; split larger rectangles. Tiles in unloaded grids are left out. `tile` is the tile coordinate (world position / 11, floored) and `rc` its centre in world units.

| Parameter | Type | Description |
|---|---|---|
| `rect` | `Rect` | World rectangle [[x1, y1], [x2, y2]]. |
| `name` | `Alias` | Tile resource pattern(s), e.g. "water" or ["grass", "moor"]. *Optional.* |

**Returns** `list[dict]`: [{tile: [x, y], rc: [x, y], name}]

**Raises** [`BadArgs`](errors.md#badargs)

```python
me = nc.gobs.player()["rc"]
water = nc.map.tiles([[me[0]-110, me[1]-110], [me[0]+110, me[1]+110]], name="water")
```

## `map.to_global`

<small>since 1.0 · query</small>

```python
nc.map.to_global(pos) -> dict|null
```

Converts a world position of this session into a session-independent one: the map grid's id and the offset inside it (world units, 0..1100). World coordinates (`rc`) are only valid within one session and change after a relog; global ones can be stored and shared between characters. Null if the position's grid is not loaded.

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord` or `Gob` | World position, or a gob for its position. |

**Returns** `dict|null`: {grid, x, y}

**Raises** [`GobGone`](errors.md#gobgone)

```python
spot = nc.map.to_global(nc.gobs.player())
# later, maybe in another session:
pos = nc.map.from_global(spot["grid"], spot["x"], spot["y"])
```
