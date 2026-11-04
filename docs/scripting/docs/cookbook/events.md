# React to events

A long-running helper: it tells you when someone says your character's name in chat and when an
animal comes into view. With the `record` parameter on, it also prints every UI message you send,
which is how you find out what to send for something the API doesn't cover.

```python
--8<-- "listen.py"
```

How it works:

- `nc.on(kind, fn, **filter)` calls `fn` on a background thread for each event. `chat.msg` data has
  `channel`, `from`, `sender` and `text`; `gob.new` has `gob`, `name` and `rc`.
- The `name="gfx/kritter/*"` filter runs in the client, so only animals cross the socket. A name
  check inside the handler (as for chat) works too, but every event is sent first.
- `ui.out` shows each widget message the client sends: do something by hand and read the log. The
  [Raw UI](../guides/raw-ui.md) guide explains how to replay what you find.
- The main thread waits with Python's `time.sleep`, not `nc.sleep`: calls from the handlers run on
  the same bot thread and would wait behind a long `nc.sleep`. The loop ends when you press stop.
- Output from `print` goes to the script's log in `scripts/logs/`.
