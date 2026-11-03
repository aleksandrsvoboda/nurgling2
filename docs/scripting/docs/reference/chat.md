# Chat & messages

Chat channels and the game's message area.

## `chat.channels`

<small>since 1.0 · query</small>

```python
nc.chat.channels() -> list[str]
```

The names of the open chat channels: "Area Chat", "Village", "Party", private chats by the other player's name, and log channels such as "System".

**Returns** `list[str]`

```python
print(nc.chat.channels())
```

## `chat.history`

<small>since 1.0 · query</small>

```python
nc.chat.history(channel, limit=None) -> list[dict]
```

The most recent lines of a channel that the client still holds (everything since the channel opened in this session), oldest first. `from` is the sender's name where the client knows it (kin list names; null for system lines and unknown senders), `time` is the client's clock in seconds when the line arrived. For new lines as they arrive, subscribe to the `chat.msg` event ({channel, from, sender, text}).

| Parameter | Type | Description |
|---|---|---|
| `channel` | `str` | Channel name (case-insensitive). |
| `limit` | `int` | At most this many lines (the newest). *Optional, default 50.* |

**Returns** `list[dict]`: [{text, from, time}]

**Raises** [`NotFound`](errors.md#notfound)

```python
for line in nc.chat.history("Village", limit=10):
    print(line["from"], line["text"])
for ev in nc.events("chat.msg", channel="Village"):
    print(ev.data["from"], ev.data["text"])
```

## `chat.notify`

<small>since 1.0 · action</small>

```python
nc.chat.notify(text, error=None) -> null
```

Shows a message in the game's message area, the line above the hotbar where "You found..." messages appear. With `error=True` it is shown in red with the error sound. Only this player sees it; nothing is sent to the server.

| Parameter | Type | Description |
|---|---|---|
| `text` | `str` | Message text. |
| `error` | `bool` | Show as an error. *Optional, default false.* |

**Returns** `null`

```python
nc.chat.notify("Done: 12 logs chopped")
```

## `chat.send`

<small>since 1.0 · action</small>

```python
nc.chat.send(channel, text) -> null
```

Sends a line to a chat channel, as if typed there. Other players see it. Log channels cannot be written to.

| Parameter | Type | Description |
|---|---|---|
| `channel` | `str` | Channel name (case-insensitive), from `chat.channels`. |
| `text` | `str` | The line to send. |

**Returns** `null`

**Raises** [`NotFound`](errors.md#notfound), [`NotSupported`](errors.md#notsupported)

```python
nc.chat.send("Village", "Smelters are lit")
```
