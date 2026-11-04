# Waits

Block until something happens, with a timeout.

## `wait.cursor`

<small>since 1.0 · wait</small>

```python
nc.wait.cursor(name, timeout=None) -> str
```

Waits until the mouse cursor's resource name contains `name`, e.g. "mine", "dig", "arw" (normal).

| Parameter | Type | Description |
|---|---|---|
| `name` | `str` | Substring of the cursor resource. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 5.* |

**Returns** `str`: The cursor resource name.

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.raw.menu_act(["dig"])
nc.wait.cursor("dig")
```

## `wait.energy`

<small>since 1.0 · wait</small>

```python
nc.wait.energy(at_least, timeout=None) -> float
```

Waits until energy is at least `at_least` (0..1; 1 = 10000 energy points).

| Parameter | Type | Description |
|---|---|---|
| `at_least` | `float` | Energy fraction 0..1. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 60.* |

**Returns** `float`: Energy afterwards.

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.wait.energy(0.5)
```

## `wait.flower_menu`

<small>since 1.0 · wait</small>

```python
nc.wait.flower_menu(timeout=None) -> list[str]
```

Waits until a flower (right-click) menu is open and returns its options. Choose one with raw.flower_choose.

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 5.* |

**Returns** `list[str]`: The menu's options.

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.raw.click_gob(gob)
opts = nc.wait.flower_menu()
```

## `wait.flower_menu_closed`

<small>since 1.0 · wait</small>

```python
nc.wait.flower_menu_closed(timeout=None) -> null
```

Waits until no flower menu is open. Returns at once if none is.

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 5.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.raw.flower_choose("Pick")
nc.wait.flower_menu_closed()
```

## `wait.gob_appears`

<small>since 1.0 · wait</small>

```python
nc.wait.gob_appears(name, radius=None, timeout=None) -> Gob
```

Waits until a game object whose resource name matches `name` exists within `radius` of the player, and returns the nearest one. Returns at once if one is already there.

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Resource name match, e.g. "gfx/terobjs/items/" or "stockpile". |
| `radius` | `float` | Distance from the player in world units (a tile is 11). *Optional, default 100.* |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 10.* |

**Returns** `Gob`: The nearest matching object.

**Raises** [`Timeout`](errors.md#timeout)

```python
log = nc.wait.gob_appears("gfx/terobjs/trees/oaklog", radius=50)
```

## `wait.gob_attr`

<small>since 1.0 · wait</small>

```python
nc.wait.gob_attr(gob, value=None, timeout=None) -> int
```

Waits until a game object's model attribute (the `attr` field of its snapshot: fill level, stage, lit state...) equals `value`, or, without `value`, until it changes from what it is now.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | The object. |
| `value` | `int` | Wanted value; omit to wait for any change. *Optional.* |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 30.* |

**Returns** `int`: The attribute afterwards.

**Raises** [`GobGone`](errors.md#gobgone), [`Timeout`](errors.md#timeout)

```python
nc.wait.gob_attr(crucible, value=0, timeout=600)
```

## `wait.gob_gone`

<small>since 1.0 · wait</small>

```python
nc.wait.gob_gone(gob, timeout=None) -> null
```

Waits until a game object no longer exists (destroyed, picked up, felled, or out of view). Returns at once if it is already gone.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | The object. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 30.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.wait.gob_gone(tree, timeout=120)
```

## `wait.hand_free`

<small>since 1.0 · wait</small>

```python
nc.wait.hand_free(timeout=None) -> null
```

Waits until nothing is on the cursor (the hand is empty). Returns at once if it already is.

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 5.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.actions.use_on_gob(trough, item=carrot)
nc.wait.hand_free()
```

## `wait.inventory_free`

<small>since 1.0 · wait</small>

```python
nc.wait.inventory_free(cells=None, inventory=None, timeout=None) -> int
```

Waits until an inventory has at least `cells` free cells.

| Parameter | Type | Description |
|---|---|---|
| `cells` | `int` | Free cells needed. *Optional, default 1.* |
| `inventory` | `Inventory` or `str` | Inventory; your own if omitted. *Optional, default "main".* |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 10.* |

**Returns** `int`: Free cells afterwards.

**Raises** [`NotFound`](errors.md#notfound), [`WidgetGone`](errors.md#widgetgone), [`Timeout`](errors.md#timeout)

```python
nc.wait.inventory_free(cells=4)
```

## `wait.item_in_hand`

<small>since 1.0 · wait</small>

```python
nc.wait.item_in_hand(name=None, timeout=None) -> Item
```

Waits until an item is on the cursor (the hand), optionally one whose name matches.

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Name match; any item if omitted. *Optional.* |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 5.* |

**Returns** `Item`: The item in hand.

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.raw.item_take(bucket)
nc.wait.item_in_hand("Bucket")
```

## `wait.items`

<small>since 1.0 · wait</small>

```python
nc.wait.items(name, count=None, inventory=None, timeout=None) -> int
```

Waits until an inventory holds at least `count` items matching `name` (stacked items count one by one).

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Which items. |
| `count` | `int` | Minimum number. *Optional, default 1.* |
| `inventory` | `Inventory` or `str` | Inventory; your own if omitted. *Optional, default "main".* |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 10.* |

**Returns** `int`: The number of matching items.

**Raises** [`NotFound`](errors.md#notfound), [`WidgetGone`](errors.md#widgetgone), [`Timeout`](errors.md#timeout)

```python
nc.wait.items("Block", count=5)
```

## `wait.items_at_most`

<small>since 1.0 · wait</small>

```python
nc.wait.items_at_most(name, count=None, inventory=None, timeout=None) -> int
```

Waits until an inventory holds at most `count` items matching `name` (e.g. until they are used up or moved away).

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Which items. |
| `count` | `int` | Maximum number. *Optional, default 0.* |
| `inventory` | `Inventory` or `str` | Inventory; your own if omitted. *Optional, default "main".* |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 10.* |

**Returns** `int`: The number of matching items.

**Raises** [`NotFound`](errors.md#notfound), [`WidgetGone`](errors.md#widgetgone), [`Timeout`](errors.md#timeout)

```python
nc.wait.items_at_most("Seed", count=0, timeout=120)
```

## `wait.near`

<small>since 1.0 · wait</small>

```python
nc.wait.near(pos, dist=None, timeout=None) -> null
```

Waits until the player is within `dist` of a position or game object (e.g. after a walk started with raw.click_ground).

| Parameter | Type | Description |
|---|---|---|
| `pos` | `Coord` or `Gob` | Position or object. |
| `dist` | `float` | Distance in world units. *Optional, default 2.* |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 30.* |

**Returns** `null`

**Raises** [`GobGone`](errors.md#gobgone), [`Timeout`](errors.md#timeout)

```python
nc.raw.click_ground([x, y])
nc.wait.near([x, y])
```

## `wait.not_moving`

<small>since 1.0 · wait</small>

```python
nc.wait.not_moving(timeout=None) -> null
```

Waits until the player has stopped moving (no movement and no walking pose).

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 30.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.wait.not_moving()
```

## `wait.player_idle`

<small>since 1.0 · wait</small>

```python
nc.wait.player_idle(timeout=None) -> null
```

Waits until the player is idle: not moving, no hourglass progress bar, and the pose is one of the idle poses (standing, or standing while carrying something).

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 60.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.actions.flower(bush, "Pick")
nc.wait.player_idle()
```

## `wait.plob_ready`

<small>since 1.0 · wait</small>

```python
nc.wait.plob_ready(timeout=None) -> null
```

Waits until a placement ghost (a building blueprint or a carried object being placed) is loaded and ready to place with raw.place.

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 10.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.raw.pagina_use("Palisade")
nc.wait.plob_ready()
nc.raw.place([x, y])
```

## `wait.pose`

<small>since 1.0 · wait</small>

```python
nc.wait.pose(gob, pose, timeout=None) -> str
```

Waits until a game object's (usually the player's) animation pose contains one of the given texts, e.g. "chop", "shoveldig", "gfx/borka/idle".

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Whose pose; usually nc.player(). |
| `pose` | `str` or `list[str]` | Substring(s) of the pose resource. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 10.* |

**Returns** `str`: The pose that matched.

**Raises** [`GobGone`](errors.md#gobgone), [`Timeout`](errors.md#timeout)

```python
nc.actions.flower(tree, "Chop")
nc.wait.pose(nc.player(), "chop")
```

## `wait.pose_not`

<small>since 1.0 · wait</small>

```python
nc.wait.pose_not(gob, pose, timeout=None) -> str|null
```

Waits until a game object's pose no longer contains any of the given texts: the work it shows has ended (chopping stopped because the tree fell, or stamina ran out). Also ends if the object disappears.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Whose pose; usually nc.player(). |
| `pose` | `str` or `list[str]` | Substring(s) of the pose resource. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 60.* |

**Returns** `str|null`: The pose afterwards, or null if the object is gone.

**Raises** [`GobGone`](errors.md#gobgone), [`Timeout`](errors.md#timeout)

```python
nc.wait.pose_not(nc.player(), "chop", timeout=120)
```

## `wait.progress`

<small>since 1.0 · wait</small>

```python
nc.wait.progress(timeout=None) -> null
```

Waits until the hourglass progress bar appears: a timed action (crafting, building, butchering) has started.

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 5.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.wait.progress()
```

## `wait.progress_done`

<small>since 1.0 · wait</small>

```python
nc.wait.progress_done(timeout=None) -> null
```

Waits until no hourglass progress bar is shown: the timed action finished or was interrupted. Returns at once if none is shown. For Craft All, which shows a new bar per item, prefer craft.wait_done.

| Parameter | Type | Description |
|---|---|---|
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 120.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.wait.progress()
nc.wait.progress_done()
```

## `wait.seconds`

<small>since 1.0 · wait</small>

```python
nc.wait.seconds(seconds) -> null
```

Sleeps for a number of seconds of wall-clock time. Unlike Python's time.sleep it ends at once when the script is stopped.

| Parameter | Type | Description |
|---|---|---|
| `seconds` | `float` | How long; 0 or less returns at once. |

**Returns** `null`

```python
nc.wait.seconds(2.5)
```

## `wait.stamina`

<small>since 1.0 · wait</small>

```python
nc.wait.stamina(at_least, timeout=None) -> float
```

Waits until stamina is at least `at_least` (0..1), e.g. while resting.

| Parameter | Type | Description |
|---|---|---|
| `at_least` | `float` | Stamina fraction 0..1. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 60.* |

**Returns** `float`: Stamina afterwards.

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.wait.stamina(0.9, timeout=300)
```

## `wait.ticks`

<small>since 1.0 · wait</small>

```python
nc.wait.ticks(count, timeout=None) -> null
```

Waits for a number of client ticks (frames of the game logic, roughly the frame rate).

| Parameter | Type | Description |
|---|---|---|
| `count` | `int` | Number of ticks. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 30.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.wait.ticks(5)
```

## `wait.window`

<small>since 1.0 · wait</small>

```python
nc.wait.window(caption, timeout=None) -> Window
```

Waits until a window with the given caption is open. A caption ending in `*` matches a prefix ("Cupboard*").

| Parameter | Type | Description |
|---|---|---|
| `caption` | `str` | Window caption, exact or with a trailing `*`. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 10.* |

**Returns** `Window`: The window.

**Raises** [`Timeout`](errors.md#timeout)

```python
wnd = nc.wait.window("Barter Stand")
```

## `wait.window_closed`

<small>since 1.0 · wait</small>

```python
nc.wait.window_closed(window, timeout=None) -> null
```

Waits until a window is gone. Pass a handle for one particular window, or a caption to wait until no window with it is open.

| Parameter | Type | Description |
|---|---|---|
| `window` | `Window` or `Inventory` or `str` | Window handle, an inventory in it, or a caption. |
| `timeout` | `float` | Seconds to wait; 0 waits forever. *Optional, default 10.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.wait.window_closed("Crafting")
```
