# Java (unstable)

Layer 4: reflective access to the client's Java code. No stability guarantees; enable with `java=True`.

## `java.call`

<small>since 1.0 · action · **unstable**</small>

```python
nc.java.call(target, member, args=None) -> any
```

Calls a method on an object, or reads a field when there is no such method and no arguments. The target is a reference, a snapshot (Gob, Item, Widget, Area), or one of "gui", "ui", "map", "player", "glob", "inventory", "run".

| Parameter | Type | Description |
|---|---|---|
| `target` | `any` | Object to call on. |
| `member` | `str` | Method or field name. |
| `args` | `list[any]` | Arguments. *Optional, default [].* |

**Returns** `any`

**Raises** [`JavaError`](errors.md#javaerror), [`GobGone`](errors.md#gobgone), [`WidgetGone`](errors.md#widgetgone)

```python
nc.java.call("gui", "msg", ["hello from Python"])
```

## `java.get`

<small>since 1.0 · query · **unstable**</small>

```python
nc.java.get(target, field) -> any
```

Reads a field (any visibility) of an object, or a static field when `target` is a class name prefixed with "class:".

| Parameter | Type | Description |
|---|---|---|
| `target` | `any` | Object, or "class:fully.qualified.Name". |
| `field` | `str` | Field name. |

**Returns** `any`

**Raises** [`JavaError`](errors.md#javaerror)

```python
hp = nc.java.get("gui", "chrid")
```

## `java.items`

<small>since 1.0 · query · **unstable**</small>

```python
nc.java.items(target, limit=None) -> list[any]
```

Converts a referenced Collection, Map (its entries as [key, value]), Iterable or array to a list, element by element.

| Parameter | Type | Description |
|---|---|---|
| `target` | `any` | Reference to a collection. |
| `limit` | `int` | Maximum number of elements. *Optional, default 1000.* |

**Returns** `list[any]`

**Raises** [`JavaError`](errors.md#javaerror)

## `java.members`

<small>since 1.0 · query · **unstable**</small>

```python
nc.java.members(target, filter=None) -> dict
```

Lists the methods and fields of a class or of a referenced object's class, for exploring the client from a REPL.

| Parameter | Type | Description |
|---|---|---|
| `target` | `any` | Reference, snapshot, special name, or "class:Name". |
| `filter` | `str` | Only members whose name contains this. *Optional.* |

**Returns** `dict`: {class, methods: [str], fields: [str]}

## `java.new`

<small>since 1.0 · action · **unstable**</small>

```python
nc.java.new(cls, args=None) -> any
```

Constructs an object and returns a reference to it.

| Parameter | Type | Description |
|---|---|---|
| `cls` | `str` | Fully qualified class name. |
| `args` | `list[any]` | Constructor arguments. *Optional, default [].* |

**Returns** `any`

**Raises** [`JavaError`](errors.md#javaerror)

```python
alias = nc.java.new("nurgling.tools.NAlias", [["log"]])
```

## `java.ref`

<small>since 1.0 · query · **unstable**</small>

```python
nc.java.ref(target) -> dict
```

Turns a snapshot or special name into a reference, e.g. to call several methods on the same object.

| Parameter | Type | Description |
|---|---|---|
| `target` | `any` | Snapshot or special name. |

**Returns** `dict`

## `java.release`

<small>since 1.0 · query · **unstable**</small>

```python
nc.java.release(refs) -> int
```

Releases references so the objects can be garbage collected. All references are released when the script disconnects.

| Parameter | Type | Description |
|---|---|---|
| `refs` | `list[int]` | Reference numbers. |

**Returns** `int`: How many were released.

## `java.run_action`

<small>since 1.0 · action · **unstable**</small>

```python
nc.java.run_action(cls, args=None) -> bool
```

Constructs any nurgling.actions.Action by class name and runs it on this script's bot thread, as Java bots do: `new X(args...).run(gui)`. Gives scripts every reusable action in the client, including ones the curated API does not wrap.

| Parameter | Type | Description |
|---|---|---|
| `cls` | `str` | Action class, e.g. "nurgling.actions.FreeInventory2". |
| `args` | `list[any]` | Constructor arguments. *Optional, default [].* |

**Returns** `bool`: True if the action returned SUCCESS.

**Raises** [`JavaError`](errors.md#javaerror)

```python
ctx = nc.java.new("nurgling.areas.NContext", [nc.java.ref("gui")])
nc.java.run_action("nurgling.actions.FreeInventory2", [ctx])
```

## `java.set`

<small>since 1.0 · action · **unstable**</small>

```python
nc.java.set(target, field, value) -> null
```

Writes a field (any visibility, not final) of an object, or a static field with "class:Name".

| Parameter | Type | Description |
|---|---|---|
| `target` | `any` | Object, or "class:fully.qualified.Name". |
| `field` | `str` | Field name. |
| `value` | `any` | New value. |

**Returns** `null`

**Raises** [`JavaError`](errors.md#javaerror)

## `java.static`

<small>since 1.0 · action · **unstable**</small>

```python
nc.java.static(cls, member, args=None) -> any
```

Calls a static method, or reads a static field when there is no method of that name and no arguments.

| Parameter | Type | Description |
|---|---|---|
| `cls` | `str` | Fully qualified class name, e.g. "nurgling.NUtils". |
| `member` | `str` | Method or field name. |
| `args` | `list[any]` | Arguments. *Optional, default [].* |

**Returns** `any`

**Raises** [`JavaError`](errors.md#javaerror)

```python
stamina = nc.java.static("nurgling.NUtils", "getStamina")
```
