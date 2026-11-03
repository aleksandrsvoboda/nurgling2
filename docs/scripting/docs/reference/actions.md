# Actions

High-level actions built on the client's own Java actions: walking, containers, items, building.

## `actions.close`

<small>since 1.0 · action</small>

```python
nc.actions.close(window, timeout=None) -> null
```

Closes a window (a container, a crafting window, any window) and waits until it is gone. Closing a window that is already gone does nothing.

| Parameter | Type | Description |
|---|---|---|
| `window` | `Window\|Inventory\|str` | Window handle, an inventory inside it, or its caption. |
| `timeout` | `float` | Seconds to wait for it to close. *Optional, default 5.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.actions.close("Cupboard")
```

## `actions.collect_to_pile`

<small>since 1.0 · action</small>

```python
nc.actions.collect_to_pile(source, dest, items) -> null
```

Picks up matching items lying on the ground in one area and stockpiles them in another, emptying the inventory into piles whenever it fills. Runs until the source area is clear, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `source` | `Area` | Area to pick items up from. |
| `dest` | `Area` | Area for the piles. |
| `items` | `Alias` | Which ground items to collect. |

**Returns** `null`

**Raises** [`NoSuchArea`](errors.md#nosucharea), [`PathNotFound`](errors.md#pathnotfound), [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.collect_to_pile("Chop site", "Blocks", "Block")
```

## `actions.destroy`

<small>since 1.0 · action</small>

```python
nc.actions.destroy(gob, timeout=None) -> null
```

Destroys an object with the Destroy action (stumps, ruins, your own buildings) and waits until it is gone. Needs the right tool equipped (e.g. a shovel for stumps). Work stops when stamina runs out, so drink first for large objects. The cursor is reset afterwards, also on timeout.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Object to destroy. |
| `timeout` | `float` | Seconds to wait for it to disappear. *Optional, default 300.* |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone), [`Timeout`](errors.md#timeout)

```python
nc.actions.equip("Shovel")
nc.actions.destroy(stump)
```

## `actions.dig`

<small>since 1.0 · action</small>

```python
nc.actions.dig(timeout=None) -> bool
```

Starts digging at the character's feet (soil, clay, sand). Needs a shovel equipped. Returns once the digging pose starts; wait for it to end with wait.pose_not(player, "shoveldig").

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait for the digging pose. *Optional, default 5.* |

**Returns** `bool`: True if digging started.

```python
if nc.actions.dig():
    nc.wait.pose_not(nc.player(), "shoveldig", timeout=60)
```

## `actions.drink`

<small>since 1.0 · action</small>

```python
nc.actions.drink(level=None) -> null
```

Drinks from your waterskins or other vessels until stamina reaches `level` (0..1). Does nothing if stamina is already there.

| Parameter | Type | Description |
|---|---|---|
| `level` | `float` | Target stamina, 0..1. *Optional, default 0.9.* |

**Returns** `null`

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
if nc.player.stamina() < 0.4:
    nc.actions.drink()
```

## `actions.drop`

<small>since 1.0 · action</small>

```python
nc.actions.drop(items, inventory=None, timeout=None) -> int
```

Drops items on the ground at the character's feet and waits until they are gone from the inventory. Pass item handles, or an alias to drop every matching item of an inventory. Drops are paced to stay under the server's flood limit.

| Parameter | Type | Description |
|---|---|---|
| `items` | `Item\|list[Item]\|Alias` | Items, or an alias such as "Stone". |
| `inventory` | `Inventory\|str` | Where to look up an alias; your own if omitted. *Optional, default "main".* |
| `timeout` | `float` | Seconds to wait for the items to disappear. *Optional, default 10.* |

**Returns** `int`: Number of items dropped.

**Raises** [`ItemGone`](errors.md#itemgone), [`Timeout`](errors.md#timeout)

```python
nc.actions.drop("Stone")
```

## `actions.drop_hand`

<small>since 1.0 · action</small>

```python
nc.actions.drop_hand(timeout=None) -> null
```

Drops the item in hand on the ground at the character's feet and waits until the hand is empty. Does nothing if the hand is empty.

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait. *Optional, default 5.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.actions.drop_hand()
```

## `actions.drop_hand_to_inventory`

<small>since 1.0 · action</small>

```python
nc.actions.drop_hand_to_inventory(inventory=None, timeout=None) -> null
```

Puts the item in hand into the first free cell of an inventory and waits until the hand is empty. Does nothing if the hand is empty.

| Parameter | Type | Description |
|---|---|---|
| `inventory` | `Inventory\|str` | Target inventory; your own if omitted. *Optional, default "main".* |
| `timeout` | `float` | Seconds to wait. *Optional, default 5.* |

**Returns** `null`

**Raises** [`NotFound`](errors.md#notfound), [`ActionFailed`](errors.md#actionfailed), [`Timeout`](errors.md#timeout)

```python
nc.actions.drop_hand_to_inventory()
```

## `actions.eat`

<small>since 1.0 · action</small>

```python
nc.actions.eat(items, energy=None) -> null
```

Eats the listed foods until energy reaches `energy` (0..1): first from your inventory, then from containers in the area with the "eat" specialisation, walking there if needed.

| Parameter | Type | Description |
|---|---|---|
| `items` | `list[str]` | Food names, in order of preference. |
| `energy` | `float` | Target energy, 0..1 (1 = 10000 energy points). *Optional, default 0.8.* |

**Returns** `null`

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.eat(["Bread", "Apple"])
```

## `actions.equip`

<small>since 1.0 · action</small>

```python
nc.actions.equip(name) -> null
```

Equips a tool or weapon in the hands, taking it from the belt or the inventory and putting away what was held if needed. Does nothing if it is already in a hand.

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Item name, e.g. "Axe" or ["Pickaxe", "Stone Axe"]. |

**Returns** `null`

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.equip("Shovel")
```

## `actions.face`

<small>since 1.0 · action</small>

```python
nc.actions.face(target, timeout=None) -> null
```

Turns the character to face a position or game object without walking there, and waits until it does.

| Parameter | Type | Description |
|---|---|---|
| `target` | `Gob\|Coord` | What to face. |
| `timeout` | `float` | Seconds to wait for the turn. *Optional, default 3.* |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone), [`Timeout`](errors.md#timeout), [`NotFound`](errors.md#notfound)

```python
nc.actions.face(nc.gobs.find(name="anvil")[0])
```

## `actions.fill_troughs`

<small>since 1.0 · action</small>

```python
nc.actions.fill_troughs(troughs, items) -> null
```

Feeds matching items from your inventory into troughs, moving on to the next trough when one is full. Runs until done, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `troughs` | `Gob\|list[Gob]` | Trough(s), filled in order. |
| `items` | `Alias` | Food items to put in. |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone), [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.fill_troughs(nc.gobs.find(name="trough"), ["Carrot", "Beetroot"])
```

## `actions.fill_waterskins`

<small>since 1.0 · action</small>

```python
nc.actions.fill_waterskins(global_=None) -> null
```

Refills your waterskins and other water containers at the nearest water area (the "water" specialisation). Runs until done, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `global` | `bool` | Use global navigation to reach a far water area. *Optional, default false.* |

**Returns** `null`

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.fill_waterskins(global=True)
```

## `actions.flower`

<small>since 1.0 · action</small>

```python
nc.actions.flower(gob, option, timeout=None) -> str
```

Right-clicks a game object and chooses an option from its flower menu, then waits for the menu to close. Pass a list to try several options in order (e.g. ["Chop", "Chop into blocks"]); the first one the menu offers is chosen. The server walks the character to the object by itself, so no path_to is needed for reachable objects. This op returns once the option is chosen, not when the work is finished: follow it with a wait such as wait.pose_not.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Object to right-click. |
| `option` | `str\|list[str]` | Option text, or candidates in order of preference. Exact match. |
| `timeout` | `float` | Seconds to wait for the menu to appear. *Optional, default 5.* |

**Returns** `str`: The option that was chosen.

**Raises** [`GobGone`](errors.md#gobgone), [`NotFound`](errors.md#notfound), [`Timeout`](errors.md#timeout)

```python
nc.actions.flower(tree, "Chop")
nc.wait.pose_not(nc.player(), "chop", timeout=120)
```

## `actions.flower_item`

<small>since 1.0 · action</small>

```python
nc.actions.flower_item(item, option, timeout=None) -> str
```

Right-clicks an inventory item and chooses an option from its flower menu (e.g. "Eat", "Split", "Drink"), then waits for the menu to close.

| Parameter | Type | Description |
|---|---|---|
| `item` | `Item` | Item to right-click. |
| `option` | `str\|list[str]` | Option text, or candidates in order of preference. Exact match. |
| `timeout` | `float` | Seconds to wait for the menu to appear. *Optional, default 5.* |

**Returns** `str`: The option that was chosen.

**Raises** [`ItemGone`](errors.md#itemgone), [`NotFound`](errors.md#notfound), [`Timeout`](errors.md#timeout)

```python
apple = nc.inv.items(name="Apple")[0]
nc.actions.flower_item(apple, "Eat")
```

## `actions.free_inventory`

<small>since 1.0 · action</small>

```python
nc.actions.free_inventory() -> null
```

Puts everything in your inventory away into the areas set up for it (the item's "out" areas and specialisations), like the bots do at the end of a cycle. Items with no area stay in the inventory. Walks wherever needed; runs until done, no timeout.

**Returns** `null`

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.free_inventory()
```

## `actions.go_to_area`

<small>since 1.0 · action</small>

```python
nc.actions.go_to_area(area, enter=None) -> Area
```

Travels to an area using the client's navigation: the local pathfinder when the area is near, otherwise the global chunk navigation (roads, doors, stairs) the bots use. With `enter` (the default) it also walks onto the area so its objects are loaded; without it, an area that is already reachable is not walked to. Runs until the navigation finishes; there is no timeout.

| Parameter | Type | Description |
|---|---|---|
| `area` | `Area` | Area handle, id or name. |
| `enter` | `bool` | Walk onto the area even if it is already reachable. *Optional, default true.* |

**Returns** `Area`: The area's snapshot after arriving.

**Raises** [`NoSuchArea`](errors.md#nosucharea), [`PathNotFound`](errors.md#pathnotfound)

```python
nc.actions.go_to_area("Logs")
```

## `actions.harvest`

<small>since 1.0 · action</small>

```python
nc.actions.harvest(field, seed, crop, trough=None) -> null
```

Harvests every ripe plant of a crop in a field area, like the farmer bots: products go to their out areas, seeds to the seed area's barrels or containers, leftovers to troughs. Runs until the field is done, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `field` | `Area` | The field. |
| `seed` | `Area` | Area holding the seed barrels / containers. |
| `crop` | `Alias` | The plant, e.g. "plants/carrot" or "carrot". |
| `trough` | `Area` | Area with troughs for leftovers. *Optional.* |

**Returns** `null`

**Raises** [`NoSuchArea`](errors.md#nosucharea), [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.harvest("Carrot field", "Carrot seeds", "plants/carrot")
```

## `actions.lift`

<small>since 1.0 · action</small>

```python
nc.actions.lift(gob, timeout=None) -> Gob
```

Walks to an object and lifts it (carry), then waits until the character holds it.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Liftable object (log, barrel, chest, cart...). |
| `timeout` | `float` | Seconds to wait for the lift after arriving. *Optional, default 10.* |

**Returns** `Gob`: The carried object.

**Raises** [`GobGone`](errors.md#gobgone), [`PathNotFound`](errors.md#pathnotfound), [`Timeout`](errors.md#timeout)

```python
barrel = nc.actions.lift(barrel)
nc.actions.place([100, 200])
```

## `actions.light`

<small>since 1.0 · action</small>

```python
nc.actions.light(gob) -> null
```

Lights a fireplace, oven, kiln, smelter or other burnable workstation with whatever the client can use (a torch or candelabrum, or fire from branches), like the right-click "Light" menu. Runs until done, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Object to light. |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone), [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.light(kiln)
```

## `actions.mine`

<small>since 1.0 · action</small>

```python
nc.actions.mine(pos, timeout=None) -> bool
```

Walks next to a rock tile (or a cave wall) and mines it, then waits until the tile changes. Needs a pickaxe equipped. Mining stops when stamina runs out; drink and call again. The cursor is reset afterwards.

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord` | World position anywhere on the tile. |
| `timeout` | `float` | Seconds to wait for the tile to change. *Optional, default 60.* |

**Returns** `bool`: True if the tile changed (was mined out), false on timeout.

**Raises** [`PathNotFound`](errors.md#pathnotfound)

```python
while not nc.actions.mine([x, y]):
    nc.actions.drink()
```

## `actions.open`

<small>since 1.0 · action</small>

```python
nc.actions.open(gob, timeout=None) -> Inventory|Window
```

Walks to a container (chest, cupboard, cart, stockpile, barrel...) and opens it. If its window is already open it is reused. A container that answers with an "Open" flower menu (cauldrons, study desks) is opened through it. Close it again with actions.close when done.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | The container. |
| `timeout` | `float` | Seconds to wait for the window after arriving. *Optional, default 10.* |

**Returns** `Inventory|Window`: The container's inventory, or its window if it has no item grid (stockpiles, barrels).

**Raises** [`GobGone`](errors.md#gobgone), [`PathNotFound`](errors.md#pathnotfound), [`Timeout`](errors.md#timeout)

```python
inv = nc.actions.open(chest)
print(nc.inv.items(inventory=inv))
nc.actions.close(inv)
```

## `actions.path_to`

<small>since 1.0 · action</small>

```python
nc.actions.path_to(target, hard=None) -> null
```

Walks to a game object or a world position with the client's pathfinder, avoiding obstacles. For a game object it stops next to it, close enough to interact. `hard` mode walks right up to the object's edge (what container and pile bots use). Only works within the loaded area around the player; use actions.go_to_area for long distances. Runs until the pathfinder arrives or gives up; there is no timeout.

| Parameter | Type | Description |
|---|---|---|
| `target` | `Gob\|Coord` | Game object, or a world position [x, y]. |
| `hard` | `bool` | Walk right up to the object's hitbox instead of the nearest free spot. *Optional, default false.* |

**Returns** `null`

**Raises** [`PathNotFound`](errors.md#pathnotfound), [`GobGone`](errors.md#gobgone)

```python
tree = nc.gobs.find(name="trees/")[0]
nc.actions.path_to(tree)
```

## `actions.place`

<small>since 1.0 · action</small>

```python
nc.actions.place(pos, angle=None, gob=None, timeout=None) -> Gob
```

Walks next to a spot and puts the carried object down there at the given angle, then waits until it is placed.

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord` | World position for the object's centre. |
| `angle` | `float` | Rotation in radians. *Optional, default 0.* |
| `gob` | `Gob` | The carried object; found automatically if omitted. *Optional.* |
| `timeout` | `float` | Seconds to wait for placement after arriving. *Optional, default 10.* |

**Returns** `Gob`: The placed object.

**Raises** [`NotFound`](errors.md#notfound), [`GobGone`](errors.md#gobgone), [`PathNotFound`](errors.md#pathnotfound), [`Timeout`](errors.md#timeout)

```python
nc.actions.place([1024.0, -88.0], angle=1.5708)
```

## `actions.restore_resources`

<small>since 1.0 · action</small>

```python
nc.actions.restore_resources() -> null
```

Restores stamina and energy the way the bots do: drinks below 50% stamina (refilling waterskins at a water area if needed), eats from the eating area below 35% energy, then walks back. Runs until done, no timeout.

**Returns** `null`

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.restore_resources()
```

## `actions.seed`

<small>since 1.0 · action</small>

```python
nc.actions.seed(field, seed, crop) -> null
```

Plants a crop on every free tile of a field area, taking seeds from the seed area. Runs until the field is planted, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `field` | `Area` | The field. |
| `seed` | `Area` | Area holding the seed barrels / containers. |
| `crop` | `Alias` | The plant, e.g. "plants/carrot". |

**Returns** `null`

**Raises** [`NoSuchArea`](errors.md#nosucharea), [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.seed("Carrot field", "Carrot seeds", "plants/carrot")
```

## `actions.stop`

<small>since 1.0 · action</small>

```python
nc.actions.stop() -> null
```

Stops the character where it stands (a left click on its own position). Cancels walking and most ongoing work.

**Returns** `null`

```python
nc.actions.stop()
```

## `actions.take_from`

<small>since 1.0 · action</small>

```python
nc.actions.take_from(container, items, count=None, quality_min=None, timeout=None) -> int
```

Takes items from a container into your inventory. With a game object, walks to it, opens it, takes and closes it again; with an open inventory (or its window caption), takes directly. Takes as many as fit, up to `count`.

| Parameter | Type | Description |
|---|---|---|
| `container` | `Gob\|Inventory\|str` | Container object, or an open inventory / window caption. |
| `items` | `Alias` | Which items to take. |
| `count` | `int` | Maximum number of items; all matching if omitted. *Optional.* |
| `quality_min` | `float` | Only items of at least this quality. *Optional.* |
| `timeout` | `float` | Seconds to wait for the container window when opening a game object. *Optional, default 10.* |

**Returns** `int`: Number of items that arrived in your inventory.

**Raises** [`GobGone`](errors.md#gobgone), [`PathNotFound`](errors.md#pathnotfound), [`NotFound`](errors.md#notfound), [`Timeout`](errors.md#timeout), [`NotSupported`](errors.md#notsupported)

```python
nc.actions.take_from(cupboard, "Wheat Flour", count=10, quality_min=20)
```

## `actions.take_from_barrel`

<small>since 1.0 · action</small>

```python
nc.actions.take_from_barrel(barrel, items, count=None) -> null
```

Walks to a barrel and takes its contents into your inventory, `count` items or as many as fit. Runs until done, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `barrel` | `Gob` | The barrel. |
| `items` | `Alias` | Expected contents, e.g. "Wheat Seed". |
| `count` | `int` | How many; as many as fit if omitted. *Optional.* |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone), [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.take_from_barrel(barrel, "Flax Seed", count=20)
```

## `actions.take_from_pile`

<small>since 1.0 · action</small>

```python
nc.actions.take_from_pile(pile, count=None) -> int
```

Walks to a stockpile, opens it and takes up to `count` items into your inventory, then closes it. A pile that is emptied disappears from the world.

| Parameter | Type | Description |
|---|---|---|
| `pile` | `Gob` | The stockpile. |
| `count` | `int` | Maximum number of items; as many as fit if omitted. *Optional.* |

**Returns** `int`: Number of items taken.

**Raises** [`GobGone`](errors.md#gobgone), [`PathNotFound`](errors.md#pathnotfound)

```python
n = nc.actions.take_from_pile(pile, count=10)
```

## `actions.take_items`

<small>since 1.0 · action</small>

```python
nc.actions.take_items(name, count) -> int
```

Fetches items from the areas where they are stored (the item's "in" areas) into your inventory, walking there as needed. Counts include what you already carry. Runs until done, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `name` | `str` | Exact item name, e.g. "Block of Pine". |
| `count` | `int` | How many you want to have. |

**Returns** `int`: How many matching items are in your inventory afterwards.

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.take_items("Wheat Flour", 12)
```

## `actions.take_to_hand`

<small>since 1.0 · action</small>

```python
nc.actions.take_to_hand(item, timeout=None) -> Item
```

Picks an item up onto the cursor (the hand) and waits until it is there. If something is already in the hand the two are swapped, as in the game.

| Parameter | Type | Description |
|---|---|---|
| `item` | `Item` | Item to pick up. |
| `timeout` | `float` | Seconds to wait. *Optional, default 5.* |

**Returns** `Item`: The item now in the hand.

**Raises** [`ItemGone`](errors.md#itemgone), [`Timeout`](errors.md#timeout)

```python
bucket = nc.actions.take_to_hand(nc.inv.items(name="Bucket")[0])
```

## `actions.to_piles`

<small>since 1.0 · action</small>

```python
nc.actions.to_piles(area, items) -> null
```

Puts matching items from your inventory into stockpiles inside an area, filling existing piles first and starting new ones where there is room. Walks onto the area first. Runs until done, no timeout. Use an area that only holds piles of this item.

| Parameter | Type | Description |
|---|---|---|
| `area` | `Area` | Area for the piles. |
| `items` | `Alias` | Which items to pile. |

**Returns** `null`

**Raises** [`NoSuchArea`](errors.md#nosucharea), [`PathNotFound`](errors.md#pathnotfound), [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.to_piles("Stones", "Stone")
```

## `actions.transfer`

<small>since 1.0 · action</small>

```python
nc.actions.transfer(to, items, count=None, timeout=None) -> int
```

Moves items from your inventory into a container. With a game object, walks to it, opens it, transfers and closes it again; with an open inventory (or its window caption), transfers directly. Moves as many as fit, up to `count`. Different item kinds matching the alias are moved one kind after another.

| Parameter | Type | Description |
|---|---|---|
| `to` | `Gob\|Inventory\|str` | Container object, or an open inventory / window caption. |
| `items` | `Alias` | Which items to move (name match, as everywhere). |
| `count` | `int` | Maximum number of items to move; all matching if omitted. *Optional.* |
| `timeout` | `float` | Seconds to wait for the container window when opening a game object. *Optional, default 10.* |

**Returns** `int`: Number of items that left your inventory.

**Raises** [`GobGone`](errors.md#gobgone), [`PathNotFound`](errors.md#pathnotfound), [`NotFound`](errors.md#notfound), [`Timeout`](errors.md#timeout), [`NotSupported`](errors.md#notsupported)

```python
moved = nc.actions.transfer(chest, "Block", count=20)
```

## `actions.transfer_items`

<small>since 1.0 · action</small>

```python
nc.actions.transfer_items(names) -> null
```

Delivers the named items from your inventory to their "out" areas, walking as needed. Unlike free_inventory, only the listed item names are moved. Runs until done, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `names` | `list[str]` | Exact item names. |

**Returns** `null`

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.transfer_items(["Block of Pine", "Branch"])
```

## `actions.transfer_to_barrel`

<small>since 1.0 · action</small>

```python
nc.actions.transfer_to_barrel(barrel, items) -> null
```

Walks to a barrel and pours matching items from your inventory into it (seeds, flour, water from buckets...). Runs until done, no timeout.

| Parameter | Type | Description |
|---|---|---|
| `barrel` | `Gob` | The barrel. |
| `items` | `Alias` | Which items to put in. |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone), [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.transfer_to_barrel(barrel, "Wheat Seed")
```

## `actions.travel_to_hearth`

<small>since 1.0 · action</small>

```python
nc.actions.travel_to_hearth() -> null
```

Travels to your hearth fire (the "Travel home" action) and waits until you arrive.

**Returns** `null`

**Raises** [`ActionFailed`](errors.md#actionfailed)

```python
nc.actions.travel_to_hearth()
```

## `actions.use_on_gob`

<small>since 1.0 · action</small>

```python
nc.actions.use_on_gob(gob, item=None, all=None, timeout=None) -> null
```

Uses the item in hand on a game object, like clicking the object while holding it (water into a trough, seeds into a pot, a log into a chipping block). If `item` is given it is first picked up into the hand. Does not wait for the result; follow it with a wait (wait.hand_free, wait.pose_not...).

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Target object. |
| `item` | `Item` | Item to pick up first; otherwise the item already in hand is used. *Optional.* |
| `all` | `bool` | Shift-click: repeat with every matching item (e.g. fill all seeds). *Optional, default false.* |
| `timeout` | `float` | Seconds to wait for the item to reach the hand. *Optional, default 5.* |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone), [`ItemGone`](errors.md#itemgone), [`NotFound`](errors.md#notfound), [`Timeout`](errors.md#timeout)

```python
nc.actions.use_on_gob(trough, item=nc.inv.items(name="Carrot")[0], all=True)
```

## `actions.use_on_item`

<small>since 1.0 · action</small>

```python
nc.actions.use_on_item(target, source=None, all=None, timeout=None) -> null
```

Uses the item in hand on another item (pour a bucket into a jar, put a whetstone on a tool, combine). If `source` is given that item is picked up first. Does not wait for the result.

| Parameter | Type | Description |
|---|---|---|
| `target` | `Item` | Item to use the hand on. |
| `source` | `Item` | Item to pick up first; otherwise the item already in hand is used. *Optional.* |
| `all` | `bool` | Shift: apply to all. *Optional, default false.* |
| `timeout` | `float` | Seconds to wait for the source to reach the hand. *Optional, default 5.* |

**Returns** `null`

**Raises** [`ItemGone`](errors.md#itemgone), [`NotFound`](errors.md#notfound), [`Timeout`](errors.md#timeout)

```python
nc.actions.use_on_item(jar, source=bucket)
```
