# Sessions

Logged-in characters.

## `sessions.list`

<small>since 1.0 · control · runs off the bot thread</small>

```python
nc.sessions.list() -> list[dict]
```

All logged-in sessions (tabs), visible and background.

**Returns** `list[dict]`: [{id, character, username, headless, active}]

```python
for s in nc.sessions.list(): print(s["character"])
```
