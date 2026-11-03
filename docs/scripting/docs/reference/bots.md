# Bots

Layer 1: run the client's own bots.

## `bots.get`

<small>since 1.0 · query · runs off the bot thread</small>

```python
nc.bots.get(id) -> dict
```

One bot's description.

| Parameter | Type | Description |
|---|---|---|
| `id` | `str` | Bot id, e.g. "goto_area". |

**Returns** `dict`

**Raises** [`NotFound`](errors.md#notfound)

## `bots.list`

<small>since 1.0 · query · runs off the bot thread</small>

```python
nc.bots.list() -> list[dict]
```

Every bot registered in the client, including scripts in the Scripts folder, with their parameters.

**Returns** `list[dict]`: [{id, title, description, type, group, menu, scenario_step, interactive, class, params}]

```python
for b in nc.bots.list():
    print(b["id"], b["title"])
```

## `bots.run`

<small>since 1.0 · action</small>

```python
nc.bots.run(id, params=None) -> bool
```

Runs a registered bot on this script's bot thread and waits for it to finish. The bot behaves exactly as when started from the menu: it uses your areas and specialisations, shows its own dialogs, and stops with the script's stop button. A fresh instance is created for every run. Parameters are checked against the bot's declared settings.

| Parameter | Type | Description |
|---|---|---|
| `id` | `str` | Bot id (see bots.list). |
| `params` | `dict` | Bot settings, e.g. {"areaId": 12}. *Optional, default {}.* |

**Returns** `bool`: True if the bot reported success.

**Raises** [`NotFound`](errors.md#notfound), [`NotSupported`](errors.md#notsupported)

```python
ok = nc.bots.run("goto_area", params={"areaId": 12})
```
