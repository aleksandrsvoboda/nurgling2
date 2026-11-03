# This run

Outcome and start parameters of the current run.

## `run.finish`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.run.finish(success) -> null
```

Reports the script's outcome before it exits. A scenario step that runs this script continues only on success. Without this call, exit code 0 counts as success.

| Parameter | Type | Description |
|---|---|---|
| `success` | `bool` | True if the script did its job. |

**Returns** `null`

```python
nc.run.finish(success=False)
```

## `run.params`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.run.params() -> dict
```

Parameter values this run was started with: the answers to the script's header form, or a scenario step's settings.

**Returns** `dict`

```python
count = nc.run.params().get("count", 10)
```
