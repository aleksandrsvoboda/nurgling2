# A settings form with overlays

Asks for an area, an object name and a colour, then draws the area, highlights every matching object
in it and labels the nearest one. Everything it draws disappears when you press *Done*, when you
stop the script, or if it crashes.

```python
--8<-- "mark_area.py"
```

How it works:

- `form.ask` shows the form and returns the values by field name. The `area` field with
  `"map": False` offers saved areas only and returns the area id. Closing the form raises
  `Cancelled`.
- `gobs.find(area=...)` only sees loaded objects, and raises `NotFound` when the area is too far away
  to be loaded; the script tells the player to walk closer.
- `overlay.rect`, `overlay.highlight` and `overlay.label` each return an id. `overlay.clear()` removes
  all of this run's overlays; it is only there to clear them before the script ends, because the
  client removes them anyway when the run ends.
- `form.confirm` keeps the overlays on screen until the player answers.

See [Forms and overlays](../guides/script-ui.md) for every field type and overlay.
