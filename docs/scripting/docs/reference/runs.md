# Runs

Bot threads of this script; see multi-session scripts.

## `runs.close`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.runs.close(run) -> bool
```

Ends a run opened with runs.open after its queued requests finish. Closing the primary run ends the script's connection.

| Parameter | Type | Description |
|---|---|---|
| `run` | `str` | Run id from runs.open. |

**Returns** `bool`: False if no such run.

## `runs.list`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.runs.list() -> list[dict]
```

This connection's runs: the primary one and any opened with runs.open.

**Returns** `list[dict]`: [{run, primary, session}]

## `runs.open`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.runs.open(session) -> dict
```

Starts a second bot thread bound to another session, for scripts that drive several characters. Requests carrying its run id execute on that session. The SDK wraps this as `nc.session(name)`, which returns a client for that character. The new run shows as its own row in that session's Bot Status strip and can be stopped on its own.

| Parameter | Type | Description |
|---|---|---|
| `session` | `str` | Character name, username or session id. |

**Returns** `dict`: {run, session}

**Raises** [`NotFound`](errors.md#notfound), [`NotSupported`](errors.md#notsupported)

```python
alt = nc.session("Karasique")
alt.actions.path_to(gob)
```
