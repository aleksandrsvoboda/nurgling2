# Scenarios

Layer 1: run saved scenarios.

## `scenarios.list`

<small>since 1.0 · query</small>

```python
nc.scenarios.list() -> list[dict]
```

Saved scenarios (bot chains) with their steps.

**Returns** `list[dict]`: [{id, name, steps: [{id, params}]}]

## `scenarios.run`

<small>since 1.0 · action</small>

```python
nc.scenarios.run(scenario) -> bool
```

Runs a saved scenario step by step on this script's bot thread; stops at the first step that fails.

| Parameter | Type | Description |
|---|---|---|
| `scenario` | `int` or `str` | Scenario id or name. |

**Returns** `bool`: True if every step succeeded.

**Raises** [`NotFound`](errors.md#notfound)

```python
nc.scenarios.run("Morning chores")
```
