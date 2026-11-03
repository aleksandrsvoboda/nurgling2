# Two characters at once

Makes this character and a second logged-in one fill their waterskins at the same time, then
compares where they are.

```python
--8<-- "two_characters.py"
```

How it works:

- `nc.session(name)` starts a second run on the other character and returns a client for it, with
  its own Gear in that character's Bot Status strip. Without a name the script picks any other
  logged-in character from `sessions.list()`.
- Each run executes its calls in order, but runs are independent: calling them from two Python
  threads makes both characters work at once.
- Stopping the second character's Gear makes its calls raise `Stopped`; the helper catches that so
  the main character finishes. Stopping the main Gear ends both.
- `gob.rc` positions mean nothing across sessions. `map.to_global` turns them into a grid id plus an
  offset, which both characters share.

More in [Several characters](../guides/multi-session.md).
