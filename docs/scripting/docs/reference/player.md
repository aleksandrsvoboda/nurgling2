# Player

The player character: position, stats, meters, buffs, speed.

## `player.attributes`

<small>since 1.0 · query</small>

```python
nc.player.attributes() -> dict
```

Base attributes and skills from the character sheet. `base` holds strength, agility, intelligence, constitution, perception, charisma, dexterity, will and psyche under their short names (str, agi, int, con, prc, csm, dex, wil, psy); `skills` holds unarmed, melee, ranged, explore, stealth, sewing, smithing, masonry, carpentry, cooking, farming, survive, lore. Each entry is {base, comp}: `base` the trained value, `comp` the value after equipment and buffs. The values arrive with the character sheet at login; before that the dicts are empty.

**Returns** `dict`: {base: {name: {base, comp}}, skills: {name: {base, comp}}}

```python
print(nc.player.attributes()["base"]["str"]["comp"])
```

## `player.buffs`

<small>since 1.0 · query</small>

```python
nc.player.buffs() -> list[dict]
```

The buffs and debuffs shown in the buff bar: `res` (resource name), `name` (its tooltip name, null if none), and `meter` (the buff's progress meter in percent, -1 when it has none).

**Returns** `list[dict]`: [{res, name, meter, wdg}]

```python
if any("drunk" in b["res"] for b in nc.player.buffs()):
    nc.chat.notify("sober up first")
```

## `player.get`

<small>since 1.0 · query</small>

```python
nc.player.get() -> dict
```

The character's current state in one call: the player's gob snapshot fields plus `character` (name), `stamina` and `energy` (fractions 0..1, null if the meter is not shown), `hp` {current, max, fraction, soft_fraction}, `speed` {cur, max} (0 crawl .. 3 sprint), `pose`, `moving`, `cursor` (cursor resource name, e.g. "gfx/hud/curs/arw") and `hand` (the item held on the cursor, or null). `hp.current`/`hp.max` come from the meter's tooltip and only update when it is hovered; use `hp.fraction` for safety checks.

**Returns** `dict`

**Raises** [`NotFound`](errors.md#notfound)

```python
me = nc.player.get()
if me["stamina"] < 0.3:
    nc.actions.drink()
```

## `player.meters`

<small>since 1.0 · query</small>

```python
nc.player.meters() -> dict
```

Every meter on the HUD by its resource base name ("hp", "stam", "nrj", and others the server shows, such as the happiness/insanity meters). Each value is the list of bar fractions (0..1) in the meter, e.g. hp is [hard, soft] while wounded. Meters whose resource has not loaded are left out.

**Returns** `dict`: {name: [fraction, ...]}

```python
m = nc.player.meters()
print(m.get("stam"), m.get("nrj"))
```

## `player.set_speed`

<small>since 1.0 · action</small>

```python
nc.player.set_speed(speed) -> null
```

Sets the movement speed: 0 crawl, 1 walk, 2 run, 3 sprint. Values above the allowed maximum are clamped by the server.

| Parameter | Type | Description |
|---|---|---|
| `speed` | `int` | Speed level 0..3. |

**Returns** `null`

**Raises** [`NotSupported`](errors.md#notsupported)

```python
nc.player.set_speed(2)
```
