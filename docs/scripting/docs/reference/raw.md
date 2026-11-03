# Raw input

Layer 3: low-level clicks and item messages, exactly as the mouse would send them.

## `raw.click_gob`

<small>since 1.0 · action</small>

```python
nc.raw.click_gob(gob, button=None, mods=None, meshid=None, offset=None) -> null
```

Clicks a game object like the mouse would. Button 1 is left, 3 is right (opens the flower menu). `mods`: 1 shift, 2 ctrl, 4 alt (add them). Does not walk there first and does not wait.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Target. |
| `button` | `int` | Mouse button. *Optional, default 3.* |
| `mods` | `int` | Modifier flags. *Optional, default 0.* |
| `meshid` | `int` | Model part to click (doors, sub-meshes); -1 for the whole object. *Optional, default -1.* |
| `offset` | `Coord` | Click point relative to the object's centre, in its own rotation. *Optional.* |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone)

## `raw.click_ground`

<small>since 1.0 · action</small>

```python
nc.raw.click_ground(pos, button=None, mods=None) -> null
```

Clicks the ground at a world position. A left click walks there (without pathfinding); with a menu action armed (raw.menu_act) it applies the action there.

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord` | World position. |
| `button` | `int` | Mouse button. *Optional, default 1.* |
| `mods` | `int` | Modifier flags. *Optional, default 0.* |

**Returns** `null`

## `raw.cursor`

<small>since 1.0 · query</small>

```python
nc.raw.cursor() -> str|null
```

Name of the current mouse cursor resource, e.g. "arw" (normal), "dig", "mine", "chi" (carry).

**Returns** `str|null`

## `raw.flower_choose`

<small>since 1.0 · action</small>

```python
nc.raw.flower_choose(option) -> bool
```

Chooses an option of the open flower menu by its exact text. Pass null to close the menu without choosing.

| Parameter | Type | Description |
|---|---|---|
| `option` | `str\|null` | Option text, e.g. "Chop". |

**Returns** `bool`: False if the menu has no such option (the menu is then closed).

**Raises** [`NotFound`](errors.md#notfound)

## `raw.flower_options`

<small>since 1.0 · query</small>

```python
nc.raw.flower_options() -> list[str]
```

Options of the open flower (right-click) menu, or an empty list if none is open.

**Returns** `list[str]`

## `raw.inv_drop`

<small>since 1.0 · action</small>

```python
nc.raw.inv_drop(inventory, slot) -> null
```

Drops the item in hand into an inventory cell.

| Parameter | Type | Description |
|---|---|---|
| `inventory` | `Inventory\|str` | Target inventory ("main", a window caption, or a handle). |
| `slot` | `Tile` | Cell [x, y]. |

**Returns** `null`

**Raises** [`NotFound`](errors.md#notfound)

## `raw.invxf`

<small>since 1.0 · action</small>

```python
nc.raw.invxf(from_, to, count=None) -> null
```

Moves items from one open inventory to another the way shift+mouse-wheel does: `count` items, taken by the server in its own order. Use actions.transfer for name-based moves.

| Parameter | Type | Description |
|---|---|---|
| `from` | `Inventory\|str` | Source inventory. |
| `to` | `Inventory\|str` | Target inventory. |
| `count` | `int` | Number of items. *Optional, default 1.* |

**Returns** `null`

**Raises** [`NotFound`](errors.md#notfound)

## `raw.item_drop`

<small>since 1.0 · action</small>

```python
nc.raw.item_drop(item) -> null
```

Drops an item on the ground at the player's feet (ctrl-click).

| Parameter | Type | Description |
|---|---|---|
| `item` | `Item` | Item. |

**Returns** `null`

**Raises** [`ItemGone`](errors.md#itemgone)

## `raw.item_interact`

<small>since 1.0 · action</small>

```python
nc.raw.item_interact(item) -> null
```

Right-clicks an item: opens its flower menu (Eat, Split, Drink...). Choose with raw.flower_choose or actions.flower_item.

| Parameter | Type | Description |
|---|---|---|
| `item` | `Item` | Item. |

**Returns** `null`

**Raises** [`ItemGone`](errors.md#itemgone)

## `raw.item_take`

<small>since 1.0 · action</small>

```python
nc.raw.item_take(item) -> null
```

Picks an item up onto the cursor (the hand). Does not wait; see wait.item_in_hand.

| Parameter | Type | Description |
|---|---|---|
| `item` | `Item` | Item. |

**Returns** `null`

**Raises** [`ItemGone`](errors.md#itemgone)

## `raw.item_transfer`

<small>since 1.0 · action</small>

```python
nc.raw.item_transfer(item) -> null
```

Shift-clicks an item: moves it to the other open inventory (or out of a container into yours).

| Parameter | Type | Description |
|---|---|---|
| `item` | `Item` | Item. |

**Returns** `null`

**Raises** [`ItemGone`](errors.md#itemgone)

## `raw.itemact_gob`

<small>since 1.0 · action</small>

```python
nc.raw.itemact_gob(gob, mods=None) -> null
```

Uses the item in hand on a game object (like clicking it while holding the item).

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Target. |
| `mods` | `int` | 1 = shift (use all / repeat), 3 = shift+ctrl. *Optional, default 0.* |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone)

## `raw.itemact_ground`

<small>since 1.0 · action</small>

```python
nc.raw.itemact_ground(pos, mods=None) -> null
```

Uses the item in hand on the ground at a position.

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord` | World position. |
| `mods` | `int` | Modifier flags. *Optional, default 0.* |

**Returns** `null`

## `raw.itemact_item`

<small>since 1.0 · action</small>

```python
nc.raw.itemact_item(item, mods=None) -> null
```

Uses the item in hand on another item (e.g. pour water into a bucket, combine).

| Parameter | Type | Description |
|---|---|---|
| `item` | `Item` | Target item. |
| `mods` | `int` | Modifier flags; 1 = shift (all). *Optional, default 0.* |

**Returns** `null`

**Raises** [`ItemGone`](errors.md#itemgone)

## `raw.menu_act`

<small>since 1.0 · action</small>

```python
nc.raw.menu_act(act) -> null
```

Triggers a menu-grid action by its internal name, as if its button or hotkey were used: e.g. ["carry"], ["destroy"], ["mine"], ["dig"], ["aggro"], ["croster"], ["bp", "palisade"]. Most arm the cursor; follow with raw.click_gob or raw.click_ground.

| Parameter | Type | Description |
|---|---|---|
| `act` | `list[str]` | Action path. |

**Returns** `null`

```python
nc.raw.menu_act(["destroy"])
nc.raw.click_gob(stump, button=1)
```

## `raw.pagina_use`

<small>since 1.0 · action</small>

```python
nc.raw.pagina_use(name) -> null
```

Uses a menu-grid entry (pagina) by its display name or resource name, e.g. "Drink" or "paginae/act/drink". Crafting recipes open their craft window this way.

| Parameter | Type | Description |
|---|---|---|
| `name` | `str` | Display name or resource name. |

**Returns** `null`

**Raises** [`NotFound`](errors.md#notfound)

## `raw.place`

<small>since 1.0 · action</small>

```python
nc.raw.place(pos, angle=None) -> null
```

Places the object being placed (a carried object or a build ghost) at a position and angle. Arm it first (e.g. raw.click_gob with shift on a carried object, or a build recipe).

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord` | World position. |
| `angle` | `float` | Rotation in radians. *Optional, default 0.* |

**Returns** `null`
