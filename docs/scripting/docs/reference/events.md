# Events

Event subscriptions. Use `nc.events(...)` and `nc.on(...)` instead of these ops.

!!! note
    The SDK exposes these through `nc.events(...)` and `nc.on(...)`; the raw ops are reachable with `nc.call("events.<verb>", {...})`.

## `events.subscribe`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.events.subscribe(kinds, filter=None) -> int
```

Starts delivering events of the given kinds from this run's session. `kind.*` subscribes to a whole family. Kinds: `ui.new`, `ui.add`, `ui.msg`, `ui.destroy`, `ui.out` (raw widget traffic), `combat.used`, `chat.msg`, `gob.new`, `gob.gone`, `form.*`. The filter keeps events whose data fields equal the given values; a string ending in `*` matches a prefix. Events are delivered asynchronously; use the SDK's `nc.events(...)` iterator or `nc.on(kind, fn)`.

| Parameter | Type | Description |
|---|---|---|
| `kinds` | `list[str]` | Event kinds, e.g. ["ui.msg"] or ["ui.*"]. |
| `filter` | `dict` | Field filter, e.g. {"msg": "tt"} or {"caption": "Cupboard*"}. *Optional.* |

**Returns** `int`: Subscription id, carried by each event as `sub`.

```python
for ev in nc.events("ui.new", caption="Cupboard*"):
    print(ev.data)
```

## `events.unsubscribe`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.events.unsubscribe(sub) -> bool
```

Stops a subscription.

| Parameter | Type | Description |
|---|---|---|
| `sub` | `int` | Subscription id from events.subscribe. |

**Returns** `bool`
