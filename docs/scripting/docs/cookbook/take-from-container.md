# Take items from the nearest container

Finds the nearest chest, crate or cupboard, opens it once, takes a few items by name and closes it.
The item and the count are header parameters, so the bot menu asks for them.

```python
--8<-- "take_from_container.py"
```

How it works:

- `gobs.nearest` matches containers by **resource name** (`gfx/terobjs/cupboard`); `inv.items` and
  `actions.take_from` match items by their **display name** (`Branch`), as shown in the tooltip.
- `actions.open` walks to the container and returns its `Inventory` once the window is open.
  Stockpiles and barrels return a `Window` without a grid, which is why the script checks the type.
- The script checks the contents and takes in the **same** open/close cycle. `actions.take_from`
  also accepts the container object itself and then opens and closes it on its own; with an inventory
  that is already open it takes directly.
- It checks free space first: `take_from` takes only as many as fit.
- Returning `False` marks the run as failed, so a scenario step using this script stops.
