# Windows

Open windows: find, read, close, press buttons.

## `windows.buttons`

<small>since 1.0 · query</small>

```python
nc.windows.buttons(window) -> list[str]
```

The texts of the buttons in a window, in widget order. Click one with `ui.click_button(text, window=...)`. Icon-only buttons have no text and are left out.

| Parameter | Type | Description |
|---|---|---|
| `window` | `Window` or `str` | Window handle or caption. |

**Returns** `list[str]`

**Raises** [`NotFound`](errors.md#notfound), [`WidgetGone`](errors.md#widgetgone)

```python
if "Build" in nc.windows.buttons("Palisade"):
    nc.ui.click_button("Build", window="Palisade")
```

## `windows.close`

<small>since 1.0 · action</small>

```python
nc.windows.close(window) -> bool
```

Closes a window the way its close button does, and waits up to 5 seconds for it to go away. Client-side windows such as the character sheet are hidden rather than destroyed; that counts as closed.

| Parameter | Type | Description |
|---|---|---|
| `window` | `Window` or `str` | Window handle or caption. |

**Returns** `bool`: True once the window is gone or hidden; false if it is still open after 5 s.

**Raises** [`NotFound`](errors.md#notfound), [`WidgetGone`](errors.md#widgetgone)

```python
nc.windows.close("Stockpile")
```

## `windows.get`

<small>since 1.0 · query</small>

```python
nc.windows.get(caption) -> Window|null
```

The newest window whose caption equals `caption` exactly, or null.

| Parameter | Type | Description |
|---|---|---|
| `caption` | `str` | Window caption, e.g. "Cupboard" or "Stockpile". |

**Returns** `Window|null`

```python
if nc.windows.get("Cupboard") is None:
    nc.actions.open(cupboard)
```

## `windows.list`

<small>since 1.0 · query</small>

```python
nc.windows.list(hidden=None) -> list[Window]
```

The open top-level windows (containers, crafting, character sheet, ...), newest last. Windows the client keeps hidden (the character sheet, equipment when closed) are left out unless `hidden` is true.

| Parameter | Type | Description |
|---|---|---|
| `hidden` | `bool` | Include hidden windows. *Optional, default false.* |

**Returns** `list[Window]`: Widget snapshots with `caption`.

```python
print([w["caption"] for w in nc.windows.list()])
```

## `windows.text`

<small>since 1.0 · query</small>

```python
nc.windows.text(window) -> list[str]
```

The texts shown in a window: every label and text field, in widget order. Rich-text boxes and drawn text are not included.

| Parameter | Type | Description |
|---|---|---|
| `window` | `Window` or `str` | Window handle or caption. |

**Returns** `list[str]`

**Raises** [`NotFound`](errors.md#notfound), [`WidgetGone`](errors.md#widgetgone)

```python
for line in nc.windows.text("Milestone"):
    print(line)
```
