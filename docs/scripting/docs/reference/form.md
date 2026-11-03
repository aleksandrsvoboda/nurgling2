# Forms & pickers

Script UI: settings forms, area and object pickers.

## `form.ask`

<small>since 1.0 · wait</small>

```python
nc.form.ask(title, fields, timeout=None) -> dict
```

Shows a settings form in the client's own style and waits until the player presses OK. Each field is a dict `{name, type, label, default, values, required}`. Types: `int` and `float` (a number entry), `bool` (a check box), `str` (a text entry), `choice` (a dropdown of `values`), `area` (the saved areas, plus "Select on map..." which lets the player drag a rectangle after OK; set `"map": false` to allow saved areas only), `gob` (a button that lets the player click an object), and `label` (static text, no value; give the text as `label`). `label` defaults to `name`; `required` fields marked with * must be filled before OK is accepted. An `area` field returns the area id, or a Rect `[[x1, y1], [x2, y2]]` when the player chose "Select on map..."; a `gob` field returns the object id (null if none was picked and the field is optional). Empty optional number fields are null. Needs a visible session.

| Parameter | Type | Description |
|---|---|---|
| `title` | `str` | Window caption. |
| `fields` | `list[dict]` | Field specs, shown top to bottom. |
| `timeout` | `float` | Seconds to wait; 0 waits until the player answers. *Optional, default 0.* |

**Returns** `dict`: Values by field name (label fields are left out).

**Raises** [`Cancelled`](errors.md#cancelled), [`NotSupported`](errors.md#notsupported), [`Timeout`](errors.md#timeout)

```python
cfg = nc.form.ask("Chopper", [
    {"name": "area", "type": "area", "label": "Trees", "required": True},
    {"name": "count", "type": "int", "label": "How many", "default": 10},
    {"name": "axe", "type": "choice", "values": ["Stone Axe", "Metal Axe"]},
    {"name": "stack", "type": "bool", "label": "Stack logs", "default": True},
])
print(cfg["count"], cfg["area"])
```

## `form.confirm`

<small>since 1.0 · wait</small>

```python
nc.form.confirm(text, title=None, timeout=None) -> bool
```

Asks a yes/no question in a small window and waits for the answer. Closing the window counts as No. Needs a visible session.

| Parameter | Type | Description |
|---|---|---|
| `text` | `str` | The question. |
| `title` | `str` | Window caption; defaults to the script's name. *Optional.* |
| `timeout` | `float` | Seconds to wait; 0 waits until the player answers. *Optional, default 0.* |

**Returns** `bool`: True for Yes.

**Raises** [`NotSupported`](errors.md#notsupported), [`Timeout`](errors.md#timeout)

```python
if nc.form.confirm("Chop all 40 trees?"):
    chop_all()
```

## `form.message`

<small>since 1.0 · action</small>

```python
nc.form.message(text, title=None, keep=None) -> null
```

Shows an information window with an OK button and returns at once. The window closes when the player presses OK, or when the run ends unless `keep` is true. Needs a visible session.

| Parameter | Type | Description |
|---|---|---|
| `text` | `str` | The message; long text wraps. |
| `title` | `str` | Window caption; defaults to the script's name. *Optional.* |
| `keep` | `bool` | Leave the window open after the run ends (for a final report). *Optional, default false.* |

**Returns** `null`

**Raises** [`NotSupported`](errors.md#notsupported)

```python
nc.form.message(f"Done: {n} logs stacked.", keep=True)
```

## `form.pick_area`

<small>since 1.0 · wait</small>

```python
nc.form.pick_area(prompt=None, timeout=None) -> Rect
```

Asks the player to drag a rectangle on the map, exactly like a bot's "select area" step, and returns it in world coordinates. The rectangle snaps to whole tiles. A small window shows the prompt with a Cancel button. Needs a visible session.

| Parameter | Type | Description |
|---|---|---|
| `prompt` | `str` | What to select. *Optional, default "Drag a rectangle on the map".* |
| `timeout` | `float` | Seconds to wait; 0 waits until the player selects or cancels. *Optional, default 0.* |

**Returns** `Rect`: `[[x1, y1], [x2, y2]]`, top-left and bottom-right corners.

**Raises** [`Cancelled`](errors.md#cancelled), [`NotSupported`](errors.md#notsupported), [`Timeout`](errors.md#timeout), [`ActionFailed`](errors.md#actionfailed)

```python
(x1, y1), (x2, y2) = nc.form.pick_area("Select the field to plough")
```

## `form.pick_gob`

<small>since 1.0 · wait</small>

```python
nc.form.pick_gob(prompt=None, timeout=None) -> Gob
```

Asks the player to click an object on the map and returns it, the same way built-in bots ask for a container or a vehicle. A small window shows the prompt with a Cancel button. Needs a visible session.

| Parameter | Type | Description |
|---|---|---|
| `prompt` | `str` | What to click. *Optional, default "Click an object".* |
| `timeout` | `float` | Seconds to wait; 0 waits until the player picks or cancels. *Optional, default 0.* |

**Returns** `Gob`: The clicked object.

**Raises** [`Cancelled`](errors.md#cancelled), [`NotSupported`](errors.md#notsupported), [`Timeout`](errors.md#timeout), [`ActionFailed`](errors.md#actionfailed)

```python
barrel = nc.form.pick_gob("Click the water barrel")
print(barrel.name)
```

## `form.text`

<small>since 1.0 · wait</small>

```python
nc.form.text(prompt, default=None, title=None, timeout=None) -> str
```

Asks the player to type a line of text and waits for OK. Needs a visible session.

| Parameter | Type | Description |
|---|---|---|
| `prompt` | `str` | What to ask, shown above the entry. |
| `default` | `str` | Initial text. *Optional, default "".* |
| `title` | `str` | Window caption; defaults to the script's name. *Optional.* |
| `timeout` | `float` | Seconds to wait; 0 waits until the player answers. *Optional, default 0.* |

**Returns** `str`: The text as typed (may be empty).

**Raises** [`Cancelled`](errors.md#cancelled), [`NotSupported`](errors.md#notsupported), [`Timeout`](errors.md#timeout)

```python
name = nc.form.text("Name for the new area:", default="Logs")
```
