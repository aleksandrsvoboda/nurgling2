# Several characters

Nurgling2 can keep several characters logged in at once, one per tab; background tabs keep running.
One script can drive all of them.

## Opening a second character

```python
# nurgling: name="Two hands"
from nurgling import script

@script
def main(nc):
    print("main:", nc.character)
    alt = nc.session("Nevolia")        # character name, username or session id
    print("alt:", alt.character)
    alt.bots.run("filwaterzone")
```

`nc.session(name)` returns another client object, `alt`, for that character. It has every namespace
`nc` has. Behind it:

- The client starts a **second run**: a new bot thread bound to that character's session, with its
  own Gear in **that** session's Bot Status strip, named `py: <script>`.
- Both clients share one connection. Each request carries the run it is for, so `alt.*` calls
  execute on the other character and `nc.*` calls on yours.
- `nc.session(...)` raises `NotFound` if no such character is logged in, and `NotSupported` if that
  session's Bot Status strip is full.

List who is logged in with `nc.sessions.list()`: each entry has `id`, `character`, `username`,
`headless` (a background tab) and `active` (the visible tab).

## Doing things at the same time

Each run executes its requests one at a time, but different runs are independent. To make two
characters work at the same time, call them from two Python threads:

```python
import threading

def fill(client):
    client.bots.run("filwaterzone")

t = threading.Thread(target=fill, args=(alt,))
t.start()
fill(nc)           # both characters work in parallel
t.join()
```

Calls from one thread run one after another; a long `alt` call does not block `nc`.

## Stopping

- Pressing stop on the **second** character's Gear ends only that run. Calls on `alt` then raise
  `Stopped`; the rest of the script keeps going. Catch `Stopped` around the `alt` work if the script
  should continue without it.
- Pressing stop on the **main** Gear (or the script exiting) ends the whole script, including all
  runs it opened.
- `alt.close_session()` ends the second run when you are done with it, and removes its Gear.

## Things to keep in mind

- **Positions are per session.** `gob.rc` is relative to that character's own map. A position from
  `nc` means nothing to `alt`, even when both stand in the same field. Convert it with
  `nc.map.to_global(pos)` (a grid id plus an offset, the same for every character) and back with
  `alt.map.from_global(grid, x, y)`, or find the target again through `alt` (by name, by area).
- **Snapshots belong to their session.** Don't pass a `Gob` or `Item` you got from `nc` to an `alt`
  op; ask `alt` for its own.
- **Events are per session.** `alt.events(...)` gets events from the other character's UI;
  `nc.events(...)` from yours.
- **Background tabs can't answer questions.** Bots marked *interactive* raise `NotSupported` there.
  [Forms](script-ui.md) appear on the session they are opened from, so open them from a visible one.
- **Parameters** (`run.params()`) belong to the main run; the second run has none.
