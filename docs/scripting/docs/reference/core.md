# Core

Connection checks and the API's own description.

## `core.describe`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.core.describe(op=None) -> dict
```

The API schema: every op with its parameters, types and docs. With `op`, only that op.

| Parameter | Type | Description |
|---|---|---|
| `op` | `str` | Op name, e.g. "gobs.find". *Optional.* |

**Returns** `dict`

**Raises** [`NotFound`](errors.md#notfound)

```python
print(nc.core.describe(op="gobs.find")["doc"])
```

## `core.ping`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.core.ping() -> int
```

Round-trip check. Returns the client's time in milliseconds.

**Returns** `int`

```python
nc.core.ping()
```

## `core.version`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.core.version() -> dict
```

API version and client build.

**Returns** `dict`: {api, client}
