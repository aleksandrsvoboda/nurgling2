# Crafting

Recipes, the craft window, and menu actions.

## `craft.close`

<small>since 1.0 · action</small>

```python
nc.craft.close() -> null
```

Closes the crafting window if it is open.

**Returns** `null`

```python
nc.craft.close()
```

## `craft.list`

<small>since 1.0 · query</small>

```python
nc.craft.list(filter=None, all=None) -> list[dict]
```

Crafting recipes your character knows (the menu grid's craft entries), with their display name and resource. `filter` keeps entries whose name or resource contains the text (case-insensitive). With `all`, every menu entry is listed (actions, buildings...), not only crafts. Entries whose resources are still loading are skipped.

| Parameter | Type | Description |
|---|---|---|
| `filter` | `str` | Substring of the name or resource. *Optional.* |
| `all` | `bool` | List every menu entry, not only craft recipes. *Optional, default false.* |

**Returns** `list[dict]`: [{name, res, act}] where `act` is the entry's action path, e.g. ["craft", "cheesetray"].

```python
for r in nc.craft.list(filter="tray"):
    print(r["name"], r["res"])
```

## `craft.make`

<small>since 1.0 · action</small>

```python
nc.craft.make(all=None, wait=None, timeout=None) -> bool
```

Presses Craft (or Craft All) in the open crafting window. Waits up to 5 seconds for the work to start (the hourglass progress bar); a game error such as missing ingredients raises ActionFailed. With `wait` (the default) it then waits until the progress bar has been gone for 1.5 s, so a Craft All run is finished, not just its first item.

| Parameter | Type | Description |
|---|---|---|
| `all` | `bool` | Craft All instead of one. *Optional, default false.* |
| `wait` | `bool` | Wait for the crafting to finish. *Optional, default true.* |
| `timeout` | `float` | Seconds to wait for it to finish. *Optional, default 120.* |

**Returns** `bool`: True if crafting started; false if nothing happened within 5 s (some recipes finish instantly without a progress bar).

**Raises** [`NotFound`](errors.md#notfound), [`ActionFailed`](errors.md#actionfailed), [`Timeout`](errors.md#timeout)

```python
nc.craft.open("Cheese Tray")
nc.craft.make(all=True)
```

## `craft.open`

<small>since 1.0 · action</small>

```python
nc.craft.open(name, timeout=None) -> dict
```

Opens the crafting window for a recipe, by its display name or resource name (see craft.list), and waits until the window shows that recipe with its ingredient names loaded.

| Parameter | Type | Description |
|---|---|---|
| `name` | `str` | Recipe display name ("Cheese Tray") or resource ("paginae/craft/cheesetray"). |
| `timeout` | `float` | Seconds to wait for the window. *Optional, default 10.* |

**Returns** `dict`: The window as craft.window describes it.

**Raises** [`NotFound`](errors.md#notfound), [`Timeout`](errors.md#timeout)

```python
w = nc.craft.open("Cheese Tray")
print(w["inputs"])
```

## `craft.wait_done`

<small>since 1.0 · wait</small>

```python
nc.craft.wait_done(quiet=None, timeout=None) -> null
```

Waits until the hourglass progress bar has been gone for `quiet` seconds: the current craft (or any timed action) is over. Returns at once if nothing is in progress and has not been for `quiet` seconds.

| Parameter | Type | Description |
|---|---|---|
| `quiet` | `float` | Seconds without a progress bar that count as done. *Optional, default 1.5.* |
| `timeout` | `float` | Seconds to wait. *Optional, default 120.* |

**Returns** `null`

**Raises** [`Timeout`](errors.md#timeout)

```python
nc.craft.make(all=True, wait=False)
nc.craft.wait_done(timeout=600)
```

## `craft.window`

<small>since 1.0 · query</small>

```python
nc.craft.window() -> dict|null
```

Describes the open crafting window: the recipe, its inputs and outputs with counts, and the tools it needs. `chosen` is the ingredient picked for a generic input (e.g. which "Raw Meat"), if any. Returns null if no crafting window is open.

**Returns** `dict|null`: {wdg, recipe, res, inputs: [{name, res, count, chosen}], outputs: [...], tools: [res], loaded}

```python
w = nc.craft.window()
if w: print(w["recipe"], [i["name"] for i in w["inputs"]])
```
