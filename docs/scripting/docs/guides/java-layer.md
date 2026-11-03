# Java layer (layer 4)

!!! danger "No stability guarantees"
    Layer 4 reaches straight into the client's Java code. Class, method and field names change
    whenever the client is refactored, often without notice, and any update can break a script that
    uses it. Nothing here is covered by the [stability policy](../stability.md). Use it to explore,
    to prototype, and to fill a gap until a stable op exists. Don't build a script you depend on
    around it if layers 1 to 3 can do the job.

Layer 4 gives a script reflective access to the running client: call any method, read or write any
field, construct objects, and run any of the client's Java actions by class name, on the script's bot
thread, exactly as a Java bot would.

## Turning it on

It is off for every script unless the script asks for it:

- In the header: `# nurgling: java=true`
- When attaching: `nurgling.connect(java=True)`

Without that, every `java.*` op fails with `NotSupported`. The flag makes scripts that rely on
internals easy to spot: look for `java=true` in the header.

## Values and references

Simple values cross as themselves: `None`, booleans, numbers, strings, enums (as their name) and
`Coord` / `Coord2d` (as `[x, y]`). Everything else comes back as a **reference**:

```python
>>> gui = nc.java.ref("gui")
>>> gui
{'ref': 1, 'class': 'nurgling.NGameUI', 'str': 'nurgling.NGameUI@5b2a...', 'wdg': 2}
```

Pass a reference (the whole dict) back as a target or an argument. Game objects, items and areas
also carry a `snapshot`, and snapshots from the stable API (`Gob`, `Item`, `Widget`, `Area`) are
accepted wherever Java expects that object.

Targets can also be one of these names: `"gui"`, `"ui"`, `"map"`, `"player"` (your character's
`Gob`), `"glob"`, `"inventory"` (your main inventory), `"run"`.

!!! note "Names are only names as targets"
    `"gui"` means the game UI only in the `target` position. Inside `args` it is just the string
    `"gui"`. To pass the game UI as an argument, pass a reference: `nc.java.ref("gui")`.

References keep their objects alive in the client until you release them
(`nc.java.release([ref["ref"]])`) or the script disconnects. A script that creates thousands of
references in a loop should release them.

## The ops

```python
# Static methods and fields
stamina = nc.java.static("nurgling.NUtils", "getStamina")

# Methods and fields of an object
nc.java.call("gui", "msg", ["hello from Python"])
name = nc.java.get("gui", "chrid")
nc.java.set("player", "someField", 1)              # any visibility, not final; "class:Name" for statics

# Constructing objects
alias = nc.java.new("nurgling.tools.NAlias", [["log"]])

# Collections to lists
gobs = nc.java.items(nc.java.call("glob", "oc"), limit=50)

# Exploring
print(nc.java.members("player", filter="pose"))
print(nc.java.members("class:nurgling.NUtils"))
```

Overloads are picked by trying each method of that name with the given arguments; if none fits,
`JavaError` lists the candidates. Any Java exception thrown inside the call comes back as `JavaError`
with the Java class and message.

## Running Java actions

`java.run_action` constructs any `nurgling.actions.Action` and runs it on the script's bot thread,
as Java bots do (`new X(args...).run(gui)`). This gives you every reusable action in the client,
including the ones the curated API doesn't wrap:

```python
gui = nc.java.ref("gui")
ctx = nc.java.new("nurgling.areas.NContext", [gui])
ok = nc.java.run_action("nurgling.actions.FreeInventory2", [ctx])
```

It returns `True` if the action reported success. The stop button stops it like any bot.

## Finding your way around

The client's source is on [GitHub](https://github.com/aleksandrsvoboda/nurgling2). Most useful
starting points: `nurgling.NUtils` (helpers), `nurgling.tools.Finder` (finding objects),
`nurgling.actions` (reusable actions), `nurgling.areas.NContext` (areas and specialisations).
`java.members` shows what a class has at runtime, which is what counts on your client version.

## When you find yourself here often

If a script needs layer 4 for something common, that is a gap in the curated API. Open an issue with
the use case: the most requested ones become stable layer-2 ops.
