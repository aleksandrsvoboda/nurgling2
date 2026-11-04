# Run a Java action (layer 4)

!!! danger "No stability guarantees"
    This recipe uses the [Java layer](../guides/java-layer.md). It calls the client's internals by
    class name, and any client update may rename or change them. When that happens the script fails
    with `JavaError` instead of working. Prefer a stable op when one exists.

Sorts your main inventory with the client's own `SortInventory` action, which the stable API does not
wrap.

```python
--8<-- "sort_inventory_java.py"
```

How it works:

- `java=true` in the header enables the `java.*` ops for this script; without it they raise
  `NotSupported`.
- `nc.java.ref("inventory")` returns a reference to your main inventory. As an argument, a name like
  `"inventory"` would just be a string, so pass the reference.
- `nc.java.run_action(class, args)` constructs the action and runs it on the script's bot thread,
  exactly like a Java bot does, and returns `True` on success. The stop button stops it.
- References keep their objects alive until released or until the script ends.
- To find other actions, read the client source (`nurgling.actions`) or explore at runtime with
  `nc.java.members("class:nurgling.actions.SortInventory")`.
