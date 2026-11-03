# Areas

Saved areas (zones) and their specialisations.

## `areas.at`

<small>since 1.0 · query</small>

```python
nc.areas.at(pos) -> list[Area]
```

The visible areas containing a world position (areas can overlap). Only areas loaded around the player are considered.

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord\|Gob` | World position, or a gob for its position. |

**Returns** `list[Area]`

**Raises** [`GobGone`](errors.md#gobgone)

```python
here = nc.areas.at(nc.gobs.player())
```

## `areas.by_spec`

<small>since 1.0 · query</small>

```python
nc.areas.by_spec(spec, subtype=None) -> list[Area]
```

Enabled areas carrying a specialisation, optionally with a subtype (e.g. spec "crop", subtype "Carrot"). The names are those of `areas.specs`.

| Parameter | Type | Description |
|---|---|---|
| `spec` | `str` | Specialisation name, e.g. "smelter" or "water". |
| `subtype` | `str` | Exact subtype. *Optional.* |

**Returns** `list[Area]`

```python
for field in nc.areas.by_spec("crop", subtype="Carrot"):
    print(field["name"])
```

## `areas.containers`

<small>since 1.0 · query</small>

```python
nc.areas.containers(area) -> list[Gob]
```

The storage containers inside an area that the client's bots know how to use (chests, crates, cupboards, sheds, baskets, tables, study desks, ...), nearest first. Each Gob snapshot has `cap`, the caption of the window it opens, which is what `inv.items(inventory=...)` accepts once it is open. Stockpiles and barrels are not included (use `gobs.find`). Raises NotFound if the area is not loaded around the player.

| Parameter | Type | Description |
|---|---|---|
| `area` | `Area\|int\|str` | Area id, name or snapshot. |

**Returns** `list[Gob]`: Gob snapshots plus `cap`.

**Raises** [`NoSuchArea`](errors.md#nosucharea), [`NotFound`](errors.md#notfound)

```python
for box in nc.areas.containers("Cheese"):
    print(box["cap"], box["rc"])
```

## `areas.for_item`

<small>since 1.0 · query</small>

```python
nc.areas.for_item(name, direction) -> list[Area]
```

Enabled areas configured to take in (`direction="in"`) or hand out (`"out"`) an item, by the item's exact display name, as set in the area's item lists. Loaded areas come first, nearest first; areas elsewhere in the world follow (their `rect` is null). For "out" areas each snapshot has `th`, the quality threshold of the matching entry (1 if none).

| Parameter | Type | Description |
|---|---|---|
| `name` | `str` | Item display name, e.g. "Oak Block". |
| `direction` | `str` | Which list to search. One of: `in`, `out`. |

**Returns** `list[Area]`: Area snapshots ("out": plus `th`).

```python
dest = nc.areas.for_item("Oak Block", direction="in")
if not dest:
    print("no area takes oak blocks")
```

## `areas.get`

<small>since 1.0 · query</small>

```python
nc.areas.get(area) -> Area
```

One area by id or name.

| Parameter | Type | Description |
|---|---|---|
| `area` | `Area\|int\|str` | Area id, exact name, or snapshot. |

**Returns** `Area`

**Raises** [`NoSuchArea`](errors.md#nosucharea)

```python
logs = nc.areas.get("Logs")
```

## `areas.list`

<small>since 1.0 · query</small>

```python
nc.areas.list() -> list[Area]
```

Every saved area of this world, enabled or not. `rect` is null for areas that are not loaded around the player; `disabled` areas are ignored by the client's bots.

**Returns** `list[Area]`: Area snapshots {area, name, specs, rect, in, out, disabled}, by id.

```python
for a in nc.areas.list():
    print(a["area"], a["name"], [s["name"] for s in a["specs"]])
```

## `areas.specs`

<small>since 1.0 · query</small>

```python
nc.areas.specs() -> list[str]
```

All specialisation names the client knows (the values `areas.by_spec` accepts).

**Returns** `list[str]`

```python
print(nc.areas.specs())
```
