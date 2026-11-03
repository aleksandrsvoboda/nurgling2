# Equipment

Equipped items and the belt.

## `equip.find`

<small>since 1.0 · query</small>

```python
nc.equip.find(name) -> Item|null
```

The first equipped item whose display name matches `name` (substring, case-insensitive), or null. Items still loading are skipped.

| Parameter | Type | Description |
|---|---|---|
| `name` | `Alias` | Display-name pattern(s), e.g. "Axe" or ["Axe", "Saw"]. |

**Returns** `Item|null`

**Raises** [`NotSupported`](errors.md#notsupported)

```python
if nc.equip.find("Axe") is None:
    nc.chat.notify("equip an axe", error=True)
```

## `equip.slots`

<small>since 1.0 · query</small>

```python
nc.equip.slots() -> dict
```

Every equipment slot with the item in it, or null when empty. Slot names: head, accessory, shirt, armor_body, gloves, belt, hand_left, hand_right, ring_left, ring_right, robe, back, pants, armor_leg, cape, boots, store_hat, eyes, mouth, lfoot, rfoot, shoulder, coat. A two-handed tool appears in both hand slots.

**Returns** `dict`: {slot name: Item|null}

**Raises** [`NotSupported`](errors.md#notsupported)

```python
eq = nc.equip.slots()
print(eq["hand_right"]["name"] if eq["hand_right"] else "empty hand")
```
