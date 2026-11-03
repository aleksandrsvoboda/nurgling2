# Widgets

Layer 3: the widget tree and raw widget messages.

## `ui.click_button`

<small>since 1.0 · action</small>

```python
nc.ui.click_button(text, window=None) -> null
```

Clicks the first button whose text equals `text` (inside `window` if given).

| Parameter | Type | Description |
|---|---|---|
| `text` | `str` | Button text, e.g. "Build". |
| `window` | `Window\|str` | Window handle or caption. *Optional.* |

**Returns** `null`

**Raises** [`NotFound`](errors.md#notfound)

## `ui.find`

<small>since 1.0 · query</small>

```python
nc.ui.find(cls=None, text=None, root=None) -> list[Widget]
```

Searches the widget tree for widgets whose Java class name ends with `cls` and/or whose caption or text contains `text`.

| Parameter | Type | Description |
|---|---|---|
| `cls` | `str` | Class name suffix, e.g. "Button" or "NFlowerMenu". *Optional.* |
| `text` | `str` | Substring of a window caption, button or label text. *Optional.* |
| `root` | `Widget` | Search below this widget only. *Optional.* |

**Returns** `list[Widget]`

```python
ok = nc.ui.find(cls="Button", text="Build")
```

## `ui.tree`

<small>since 1.0 · query</small>

```python
nc.ui.tree(root=None, depth=None) -> dict
```

The widget tree below a widget (default: the game UI). Each node is a Widget snapshot plus `children`, and `text` for buttons and labels. Use `depth` to keep it small; the full tree has thousands of nodes.

| Parameter | Type | Description |
|---|---|---|
| `root` | `Widget` | Widget to start from. *Optional.* |
| `depth` | `int` | Levels below the root; -1 for all. *Optional, default 2.* |

**Returns** `dict`

**Raises** [`WidgetGone`](errors.md#widgetgone)

```python
tree = nc.ui.tree(depth=1)
print([c["class"] for c in tree["children"]])
```

## `ui.wdgmsg`

<small>since 1.0 · action</small>

```python
nc.ui.wdgmsg(wdg, msg, args=None) -> null
```

Sends a raw widget message to the server, exactly as the widget would. Coordinates in `args` are passed as [x, y] lists and converted to Coord; use `raw.*` ops for world positions, which need server units.

| Parameter | Type | Description |
|---|---|---|
| `wdg` | `Widget` | Sending widget (handle or id). |
| `msg` | `str` | Message name, e.g. "close", "click", "transfer". |
| `args` | `list[any]` | Message arguments. *Optional, default [].* |

**Returns** `null`

**Raises** [`WidgetGone`](errors.md#widgetgone)

```python
nc.ui.wdgmsg(wnd, "close")
```

## `ui.widget`

<small>since 1.0 · query</small>

```python
nc.ui.widget(wdg) -> Widget|null
```

One widget's snapshot, or null if it no longer exists.

| Parameter | Type | Description |
|---|---|---|
| `wdg` | `Widget` | Widget handle or id. |

**Returns** `Widget|null`
