# Inventories

Items in the player's and containers' inventories, the hand, stockpiles.

## `inv.count`

<small>since 1.0 · query</small>

```python
nc.inv.count(name, inventory=None) -> int
```

How many items match `name`, counting each stack member as one item. This counts items, not amounts: a pile of seeds that is one item counts 1; sum `num` from `inv.items` for amounts.

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Display-name pattern(s). |
| `inventory` | `Inventory\|str` | Inventory: "main" (default), a window caption such as "Cupboard", or an Inventory handle. *Optional, default "main".* |

**Returns** `int`

**Raises** [`NotFound`](errors.md#notfound)

```python
if nc.inv.count("Branch") < 5:
    print("need branches")
```

## `inv.find`

<small>since 1.0 · query</small>

```python
nc.inv.find(name, inventory=None) -> Item|null
```

The first item whose display name matches `name` (stack members included), or null.

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Display-name pattern(s). |
| `inventory` | `Inventory\|str` | Inventory: "main" (default), a window caption such as "Cupboard", or an Inventory handle. *Optional, default "main".* |

**Returns** `Item|null`

**Raises** [`NotFound`](errors.md#notfound)

```python
knife = nc.inv.find("Stone Axe")
```

## `inv.free_slots`

<small>since 1.0 · query</small>

```python
nc.inv.free_slots(size, inventory=None) -> int
```

How many items of the given size still fit, placed without overlapping (the client's own space check, as Java bots use it). A 1x1 item is [1, 1]; a cheese tray is [1, 2]. Use the `size` field of an item snapshot.

| Parameter | Type | Description |
|---|---|---|
| `size` | `Tile` | Item size in cells, [w, h]. |
| `inventory` | `Inventory\|str` | Inventory: "main" (default), a window caption such as "Cupboard", or an Inventory handle. *Optional, default "main".* |

**Returns** `int`

**Raises** [`NotFound`](errors.md#notfound)

```python
trays = nc.inv.free_slots([1, 2])
```

## `inv.free_space`

<small>since 1.0 · query</small>

```python
nc.inv.free_space(inventory=None) -> int
```

Number of empty cells in an inventory.

| Parameter | Type | Description |
|---|---|---|
| `inventory` | `Inventory\|str` | Inventory: "main" (default), a window caption such as "Cupboard", or an Inventory handle. *Optional, default "main".* |

**Returns** `int`

**Raises** [`NotFound`](errors.md#notfound)

```python
if nc.inv.free_space() < 4:
    nc.bots.run("free_inventory")
```

## `inv.hand`

<small>since 1.0 · query</small>

```python
nc.inv.hand() -> Item|null
```

The item held on the cursor, or null.

**Returns** `Item|null`

```python
held = nc.inv.hand()
if held:
    print("holding", held["name"])
```

## `inv.item`

<small>since 1.0 · query</small>

```python
nc.inv.item(item) -> Item|null
```

A fresh snapshot of one item, or null if its widget no longer exists (moved to another inventory, used up, container closed). Works for items in inventories, stacks, equipment and the hand. Never raises ItemGone.

| Parameter | Type | Description |
|---|---|---|
| `item` | `Item` | Item handle (snapshot) or widget id. |

**Returns** `Item|null`

```python
it = nc.inv.item(it)
if it is None:
    print("gone")
```

## `inv.items`

<small>since 1.0 · query</small>

```python
nc.inv.items(inventory=None, name=None, quality_min=None, expand_stacks=None) -> list[Item]
```

Items in an inventory, optionally filtered. `name` matches the item's display name ("Oak Block", "Cheese Tray"), case-insensitively by substring; resource paths such as "items/x" do not match. With `expand_stacks` (the default) the members of item stacks are listed one by one and the stack itself is not, which is what Java bots see; with `expand_stacks=False` you get the top-level grid items, stacks included as one item each (`stack` > 0). `quality_min` drops items below that quality (items without a quality are dropped too).

| Parameter | Type | Description |
|---|---|---|
| `inventory` | `Inventory\|str` | Inventory: "main" (default), a window caption such as "Cupboard", or an Inventory handle. *Optional, default "main".* |
| `name` | `Alias` | Display-name pattern(s). *Optional.* |
| `quality_min` | `float` | Minimum quality (exact float, not the rounded display). *Optional.* |
| `expand_stacks` | `bool` | List stack members instead of stacks. *Optional, default true.* |

**Returns** `list[Item]`

**Raises** [`NotFound`](errors.md#notfound)

```python
logs = nc.inv.items(name="Block", quality_min=30)
print(len(logs), "good blocks")
```

## `inv.list`

<small>since 1.0 · query</small>

```python
nc.inv.list() -> list[Inventory]
```

Every open inventory: the player's main inventory first, then those inside open windows (containers, belt, cupboards). Each snapshot carries `free`, the number of empty cells.

**Returns** `list[Inventory]`: Inventory snapshots {wdg, window, size, free}.

```python
for inv in nc.inv.list():
    print(inv["window"], inv["free"])
```

## `inv.stockpile`

<small>since 1.0 · query</small>

```python
nc.inv.stockpile() -> dict|null
```

The open stockpile window's state, or null if none is open. `count` items are in the pile, `free` more fit, `capacity` = count + free; `res` is the piled item's resource name. Counts are null if the window has not shown them yet.

**Returns** `dict|null`: {wdg, res, count, free, capacity}

```python
sp = nc.inv.stockpile()
if sp and sp["free"] == 0:
    print("pile is full")
```
