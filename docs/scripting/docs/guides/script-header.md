# Script header

The header is a few comment lines at the top of a script. The client reads them, without running the
script, to put it in the bot menu and to build its start form. A script without a header still works;
it is listed under its file name.

```python
# nurgling: name="Chop trees", description="Chops the nearest trees", icon=choper, java=false
# nurgling-param: count int 10 "How many trees"
# nurgling-param: zone area - "Where to chop"
# nurgling-param: mode choice[fast,careful] fast "Mode"
```

## Where the client looks

- **Which files.** Every top-level `*.py` file in the scripts folder, and every top-level folder
  that has a `main.py` (the header goes in `main.py`). Names starting with `.` or `_`, and the `logs`
  folder, are skipped. Nothing deeper than one level is scanned, so helper modules in a script
  folder are not listed as scripts.
- **Which lines.** The first **40** lines of the file. Leading and trailing spaces are ignored, so the
  header may be indented, but each header line must start with exactly `# nurgling:` or
  `# nurgling-param:` (a `#`, one space, then the keyword). `#nurgling:` is not a header line.
- Header lines can be mixed with other lines (a docstring, imports, blank lines) as long as they
  are within the first 40.
- **Order.** Scripts appear in the menu sorted by file name, ignoring case.

## `# nurgling:` — the script itself

A list of `key=value` pairs. Separate them with commas, spaces, or both.

| Key | Value | Default |
|---|---|---|
| `name` | Title in the bot menu, the start form and the Gear. | The file name without `.py` (the folder name for a folder script). |
| `description` | Tooltip text. | `Python script <file>` |
| `icon` | The icon of one of the client's built-in bots, by its icon name (for example `goto`, `choper`, `butcher`, `barrel`). Only icons that a built-in bot uses count; the names are the `iconPath` values in the client's `BotRegistry.java`. | The generic script icon. Also used when the name is not a built-in bot's icon. |
| `java` | `true` enables the [Java layer](java-layer.md) for this script. | `false` |

Value syntax:

- A value in double quotes may contain spaces and commas: `name="Chop, then stack"`. It cannot
  contain a double quote; there are no escapes.
- A value without quotes ends at the first comma or space: `icon=choper`.
- `java` is true only for the word `true` (any case). Anything else, including `yes` or `1`, is false.
- Unknown keys are ignored without a warning, so check your spelling.
- You can spread the keys over several `# nurgling:` lines. If a key appears twice, the last one wins.

## `# nurgling-param:` — parameters

One line per parameter:

```
# nurgling-param: <name> <type> <default> ["<label>"]
```

| Part | Rules |
|---|---|
| `name` | Letters, digits and `_`. This is the key in `nc.run.params()` and in a scenario step's settings. |
| `type` | One of the types below. |
| `default` | A single word, or a value in double quotes (for strings with spaces). `-` means **no default: the parameter is required**. |
| `label` | Optional, in double quotes. Shown in the start form. Defaults to the name. |

| Type | Python value | Default must be |
|---|---|---|
| `int` | `int` | a whole number, `10` |
| `float` | `float` | a number, `2.5` |
| `bool` | `bool` | `true` or `false` (anything other than `true` means false) |
| `str` | `str` | a word or `"a quoted string"` |
| `area` | `int`, the area id | an area id, or `-` (the usual choice: there is no portable default area) |
| `choice[a,b,c]` | `str`, one of the listed values | one of the values |

Choice values are separated by commas, with spaces around them trimmed (`choice[fast, careful]` is
fine). A value cannot contain `]` or `,`. There must be no space between `choice` and `[`.

Lines that do not parse, and lines with an unknown type or a default that does not fit the type
(`int abc`), are **skipped**; the client prints the reason to its own log
(`[Scripting] chop.py: cannot parse parameter line: ...`). The script still loads, without that
parameter.

### How values reach the script

When the script is started, the client collects a value for every parameter:

- From a **scenario step**: the step's settings.
- From the **bot menu**: there are no stored values, so the form below always appears.

If any parameter has no value, the client shows a form with all of the script's parameters,
pre-filled with the defaults. Required parameters must be filled in. Dismissing the form cancels the
start; the script is not run. A script without `# nurgling-param:` lines never shows a form.

The script reads the values with `nc.run.params()`, a `dict` keyed by parameter name. The same dict
is in `nc.hello["params"]`. Scripts you start from a terminal get `{}`.

```python
p = nc.run.params()
count = int(p.get("count", 10))
mode = p.get("mode", "fast")
```

Use `.get()` with a default for optional parameters, so the script also works when attached from a
terminal.

## The bot id

Each script is registered as a bot with the id `script:<path>`, where the path is relative to the
scripts folder: `script:hello.py`, `script:chopper/main.py`. Scenarios store this id, so renaming or
moving the file breaks the scenario steps that use it; changing the `name` in the header does not.
