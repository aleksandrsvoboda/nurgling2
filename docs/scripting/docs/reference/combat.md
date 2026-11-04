# Combat

Fight state, attacking and combat moves.

## `combat.attack`

<small>since 1.0 · action</small>

```python
nc.combat.attack(gob, timeout=None) -> dict
```

Attacks an object with the Aggro action (as clicking it with the attack cursor does), waits until it appears in the fight list, and puts the normal cursor back.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | Animal or player to attack. |
| `timeout` | `float` | Seconds to wait for the fight to start. *Optional, default 5.* |

**Returns** `dict`: The combat state after the attack (see combat.state).

**Raises** [`GobGone`](errors.md#gobgone), [`Timeout`](errors.md#timeout)

```python
boar = nc.gobs.find(name="kritter/boar")[0]
nc.combat.attack(boar)
```

## `combat.peace`

<small>since 1.0 · action</small>

```python
nc.combat.peace(gob=None, timeout=None) -> int
```

Offers peace (the white flag on the portrait) to one opponent, or to every opponent that has not been offered it yet, and waits until the client shows the flag raised. Animals being tamed and players who accept leave the fight.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | The opponent; omit for all of them. *Optional.* |
| `timeout` | `float` | Seconds to wait for the flags. *Optional, default 3.* |

**Returns** `int`: How many opponents were offered peace.

**Raises** [`NotFound`](errors.md#notfound), [`Timeout`](errors.md#timeout)

```python
nc.combat.attack(horse)
nc.combat.peace()
```

## `combat.state`

<small>since 1.0 · query</small>

```python
nc.combat.state() -> dict|null
```

The current fight as the client sees it, or null when not fighting. Openings are 0-100 per colour (`offbalance` green, `reeling` yellow, `cornered` red, `dizzy` blue). `opponents[].openings` are the openings you have made on that opponent; `me.openings` are theirs on you. `ip`/`oip` are your and their initiative points against that opponent; `give` is the peace-flag state (1 = you offered peace). `cooldown` is the seconds left before you can act again; `moves` is the action bar (slot 0 = key 1, slot 5 = Shift+1). Resource names may be null for a moment while they load.

**Returns** `dict|null`: {target, opponents: [{gob, name, pose, dist, current, ip, oip, give, last_action, last_action_age, openings, stance, buffs}], me: {openings, stance, buffs, last_action}, cooldown, cooldown_total, moves: [{slot, name, cooldown, ready}]}

```python
st = nc.combat.state()
if st and st["cooldown"] == 0:
    tgt = next(o for o in st["opponents"] if o["current"])
    print(tgt["name"], tgt["openings"]["cornered"])
```

## `combat.target`

<small>since 1.0 · action</small>

```python
nc.combat.target(gob, timeout=None) -> bool
```

Makes another opponent in the fight list the current target (what Ctrl+Tab or clicking its portrait does) and waits until the server confirms.

| Parameter | Type | Description |
|---|---|---|
| `gob` | `Gob` | An opponent in the fight list (an id works after it left view). |
| `timeout` | `float` | Seconds to wait for the switch. *Optional, default 3.* |

**Returns** `bool`: True once it is the current target, false if the server did not switch in time.

**Raises** [`NotFound`](errors.md#notfound)

```python
weakest = min(st["opponents"], key=lambda o: o["dist"] or 99)
nc.combat.target(weakest["gob"])
```

## `combat.use`

<small>since 1.0 · action</small>

```python
nc.combat.use(move, wait=None, timeout=None, pos=None) -> dict
```

Uses a move from the combat action bar against the current target, like pressing its key. `move` is a slot number (0-9, see combat.state `moves`) or a move's resource name; a name matches exactly, by its last part ("barrage" for "paginae/atk/barrage"), or as a substring. With `wait` (the default) it first waits for your cooldown and the move's own cooldown to run out.

| Parameter | Type | Description |
|---|---|---|
| `move` | `int` or `str` | Slot or move name. |
| `wait` | `bool` | Wait until the move is ready before using it. *Optional, default true.* |
| `timeout` | `float` | Seconds to wait for the move to be ready. *Optional, default 10.* |
| `pos` | `Coord` | World position for moves aimed at the ground (what the mouse would point at). *Optional.* |

**Returns** `dict`: {slot, name} of the move used.

**Raises** [`NotFound`](errors.md#notfound), [`ActionFailed`](errors.md#actionfailed), [`Timeout`](errors.md#timeout)

```python
nc.combat.use("barrage")
nc.combat.use(0, wait=False)
```
