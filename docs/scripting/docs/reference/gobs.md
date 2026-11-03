# Game objects

Find and inspect objects in the world.

## `gobs.find`

<small>since 1.0 · query</small>

```python
nc.gobs.find(name=None, area=None, radius=None, near=None, pose=None, attr=None, exclude=None, limit=None, sort=None) -> list[Gob]
```

Finds game objects in the loaded part of the world. Filters combine (all must hold). Only objects whose resource has loaded are matched by `name`; the player's own object is never included (use `gobs.player`). To react to objects appearing or vanishing, subscribe to the `gob.new` event ({gob, name, rc}, sent once the object's resource is known; call `gobs.get` for the full snapshot) and `gob.gone` ({gob, name}). Without `radius` and `area` every loaded object is considered. `name` matches the resource name by substring ("trees/", "gfx/terobjs/chest"). Raises NotFound if `area` is given but is not loaded around the player (more than ~1000 units away or hidden): walk there first, an unloaded area would otherwise look empty.

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Resource-name pattern(s), e.g. "trees/" or {"include": ["trees/"], "exclude": ["stump"]}. *Optional.* |
| `area` | `Area` | Only objects inside this area's rectangle. *Optional.* |
| `radius` | `float` | Only objects closer than this to `near` (world units; a tile is 11). *Optional.* |
| `near` | `Coord\|Gob` | Centre for `radius` and for sorting; defaults to the player position. *Optional.* |
| `pose` | `Alias` | Pose resource pattern(s), e.g. "knocked" or "dead"; objects without a pose never match. *Optional.* |
| `attr` | `int` | Model attribute (crop stage, container fill marker, ...) must equal this. Note markers >= 128 read negative. *Optional.* |
| `exclude` | `list[int]` | Gob ids to skip (e.g. ones already processed). *Optional.* |
| `limit` | `int` | At most this many results (after sorting); 0 = no limit. *Optional, default 0.* |
| `sort` | `str` | "dist": nearest to `near` first; "none": cache order. *Optional, default "dist".* One of: `dist`, `none`. |

**Returns** `list[Gob]`: Gob snapshots, nearest first unless sort="none".

**Raises** [`NoSuchArea`](errors.md#nosucharea), [`NotFound`](errors.md#notfound), [`GobGone`](errors.md#gobgone)

```python
trees = nc.gobs.find(name="trees/", radius=200, limit=5)
for t in trees:
    print(t["name"], round(t["dist"]))
```

## `gobs.get`

<small>since 1.0 · query</small>

```python
nc.gobs.get(gob) -> Gob|null
```

A fresh snapshot of one game object, or null if it no longer exists in this session (out of view, destroyed, picked up). Never raises GobGone; use it to test whether a handle is still valid.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Gob handle (snapshot) or id. |

**Returns** `Gob|null`: Snapshot: gob, name, hash, rc, a, pose, attr, overlays, dist.

```python
tree = nc.gobs.get(tree)
if tree is None:
    print("it fell")
```

## `gobs.hitbox`

<small>since 1.0 · query</small>

```python
nc.gobs.hitbox(gob) -> dict|null
```

The object's collision box as the client's pathfinder sees it, or null if it has none (or its resource has not loaded). `begin`/`end` are the box corners in the object's own frame; `corners` are the four world-space corners after rotation; `parts` lists one corner set per part for objects with several solid parts (gates, passages).

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Gob handle or id. |

**Returns** `dict|null`: {begin: [x, y], end: [x, y], a, corners: [[x, y] x4], parts: [[[x, y] x4], ...]}

**Raises** [`GobGone`](errors.md#gobgone)

```python
hb = nc.gobs.hitbox(chest)
print(hb["corners"] if hb else "no hitbox")
```

## `gobs.lifted`

<small>since 1.0 · query</small>

```python
nc.gobs.lifted() -> Gob|null
```

The object the player is carrying overhead (a lifted log, barrel, chest...), or null.

**Returns** `Gob|null`

```python
barrel = nc.gobs.lifted()
if barrel is not None:
    print("carrying", barrel["name"])
```

## `gobs.nearest`

<small>since 1.0 · query</small>

```python
nc.gobs.nearest(name=None, area=None, radius=None, near=None, pose=None, attr=None, exclude=None) -> Gob|null
```

The nearest game object matching the same filters as `gobs.find`, or null. Filters combine (all must hold). Only objects whose resource has loaded are matched by `name`; the player's own object is never included (use `gobs.player`). To react to objects appearing or vanishing, subscribe to the `gob.new` event ({gob, name, rc}, sent once the object's resource is known; call `gobs.get` for the full snapshot) and `gob.gone` ({gob, name}).

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Resource-name pattern(s). *Optional.* |
| `area` | `Area` | Only objects inside this area. *Optional.* |
| `radius` | `float` | Only objects closer than this to `near`. *Optional.* |
| `near` | `Coord\|Gob` | Point to measure from; defaults to the player position. *Optional.* |
| `pose` | `Alias` | Pose resource pattern(s). *Optional.* |
| `attr` | `int` | Model attribute must equal this. *Optional.* |
| `exclude` | `list[int]` | Gob ids to skip. *Optional.* |

**Returns** `Gob|null`

**Raises** [`NoSuchArea`](errors.md#nosucharea), [`NotFound`](errors.md#notfound), [`GobGone`](errors.md#gobgone)

```python
chest = nc.gobs.nearest(name="gfx/terobjs/chest", radius=100)
```

## `gobs.player`

<small>since 1.0 · query</small>

```python
nc.gobs.player() -> Gob
```

The player's own game object. For character state (stamina, hand, speed) use `player.get`.

**Returns** `Gob`

**Raises** [`NotFound`](errors.md#notfound)

```python
me = nc.gobs.player()
print(me["rc"])
```
