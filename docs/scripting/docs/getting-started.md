# Getting started

This page takes you from nothing to a running script in about ten minutes.

## 1. Install Python

You need **Python 3.9 or newer**. The SDK uses only the standard library, so there is nothing else to
install for scripts you start from the client.

- **Windows:** install from [python.org](https://www.python.org/downloads/windows/). Tick
  *Add python.exe to PATH* in the installer. Check it in a new terminal with `python --version`.
- **Linux / macOS:** use your system's Python 3 (`python3 --version`) or install a newer one.

The client looks for `python`, then `py -3`, then `python3` (on Linux and macOS: `python3`, then
`python`) and uses the first one that is 3.9 or newer. If it picks the wrong one, set the full path
of the interpreter in **Options > Python Scripting**.

## 2. Turn scripting on

Open **Options > Python Scripting** and tick **Enable scripting**. The first time, a window titled
*Scripts run as you* warns that scripts run with your full permissions; press **I understand** to
tick the box (Cancel leaves scripting off). Read [Security](guides/security.md) if you plan to run
scripts other people wrote.

Scripting is **off by default**. While it is off, the client opens no socket at all.

The page has:

| Control | What it does |
|---|---|
| **Enable scripting** | Starts the local scripting server and lists your scripts in the bot menu. |
| **Python interpreter** + **Test** | Path to the interpreter; empty means auto-detect (see above). **Test** looks for Python with what you typed and shows `Found: ...` or that no Python 3.9+ was found. |
| **Server port** | The local port scripts connect to. `0` (the default) picks a free port; a change applies the next time the server starts. |
| **Scripts folder** | Where your scripts live; empty means the `scripts` folder next to the client. The line below shows the full path. |
| **Open folder** | Opens the scripts folder in your file manager (creating it if needed). |
| **Rescan scripts** | Reads the scripts folder again right away and updates the bot menu; shows how many scripts it found. |
| Status line | *scripting is off*, *server running on 127.0.0.1:&lt;port&gt;*, or *server is not running* (then see the client log). |
| **Documentation** | Opens this site. |

Changes take effect when you press **Save**: turning scripting on starts the server and scans the
folder, turning it off stops the server, and a new scripts
folder is scanned at once.

## Try the examples first

The client ships with example scripts. With scripting on, open the bot menu's **Scripts** folder:
your own scripts are under **My scripts**, and the bundled ones under **Examples**:

| Example | What it does |
|---|---|
| Hello | Shows who and where you are and what is around you. |
| Take from nearest container | Opens the nearest chest, crate or cupboard and takes items by name. |
| Chop nearby trees | Equips an axe and chops the nearest trees, drinking when stamina runs low. |
| Store my inventory | Puts everything away into its areas, then optionally fetches an item. |
| Mark objects in an area | Asks for an area and a name, then highlights the matching objects. |
| Lookout | Tells you when someone says your name or an animal shows up. |
| Morning round | Runs three built-in bots in a row, stopping at the first failure. |
| Both fill waterskins | Drives a second logged-in character at the same time. |
| Sort inventory (Java) | Runs a Java action the stable API does not wrap (layer 4). |

They are read from `scripting/examples` in the client's folder and are replaced by each update.
To change one, copy it into your scripts folder first; the [cookbook](cookbook/index.md) explains each of them.

## 3. Your first script

Create the file `scripts/hello.py` in the client's folder and paste this:

```python
--8<-- "hello.py"
```

The first line is the **header**. The client reads it to list the script in the bot menu:
`name` is the title, `description` the tooltip. The [script header](guides/script-header.md) page
lists every option, including parameters.

`@script` marks the entry point. When the file runs, it connects to the client, calls `main(nc)` with
a connected client object (`nc` by convention), and exits when `main` returns.

!!! tip "Folders"
    A script can also be a folder with a `main.py` in it (`scripts/chopper/main.py`). Put helper
    modules next to `main.py` and import them normally: the script runs with its own folder as the
    working directory. Files and folders whose names start with `_` or `.` are ignored, and so is
    the `logs` folder.

## 4. Run it from the bot menu

Open the bot menu. Your script is in the **Scripts** folder, under the name from its header. Click it.

- A Gear appears in the **Bot Status** strip, like for any bot.
- When the script ends, the Gear goes away. If it failed, the last line of its output (usually the
  error) is shown as a game message.

The client scans the scripts folder when your character's game UI loads (with scripting on), when
you turn scripting on, and when you press **Rescan scripts** in Options > Python Scripting. A **new**
script, or a changed header (name, description, parameters), shows up after the next scan. Changes
to a script's **code** take effect the next time you start it; no rescan needed for those.

### Logs

Everything the script prints, and any traceback, goes to `scripts/logs/<script>-<date>-<time>.log`.
Logs older than a week are deleted automatically.

### Stopping

Press stop on the script's Gear. The client interrupts the script's bot thread: whatever the script
is waiting for (a walk, a wait, a bot) ends at once, and the script's next call raises
[`Stopped`](concepts.md#errors). `@script` treats that as a normal end, so a stopped script exits
quietly. The client gives the process two seconds to exit and then kills it.

A script that ends with an exception, or whose `main` returns `False`, counts as **failed**. That
matters when the script is a [scenario step](concepts.md#scenario-steps).

## 5. Attach from a terminal, IDE or notebook

You don't have to go through the bot menu. With the client running and scripting enabled, run the
same file from a terminal:

```
python scripts/hello.py
```

`@script` notices it was not started by the client and **attaches**: the client creates a Gear named
`py: hello.py` on the visible character, and the output stays in your terminal.

For a REPL or Jupyter, use `connect()` directly:

```python
>>> import nurgling
>>> nc = nurgling.connect()                  # the visible character
>>> nc.character
'Karasique'
>>> nc.core.describe(op="ui.find")["doc"]    # the docs of any op, at runtime
>>> nc.close()                               # ends the Gear
```

- `connect(session="Name")` attaches to another logged-in character (by character name, username or
  session id).
- `connect(java=True)` enables the [Java layer](guides/java-layer.md).
- `with nurgling.connect() as nc:` closes the connection when the block ends.

`connect()` finds the client through a small file the client writes at startup (see
[Protocol](protocol.md#finding-the-client)). If it says the file is not found, the client is not
running or scripting is off.

Stopping the attached Gear in the game makes the next call in your REPL raise `Stopped`; connect again
to continue.

### Install the SDK for your editor

Scripts the client starts always get the SDK the client ships, so you never need to install it for
them. To get autocompletion and type hints in an IDE, or to attach from a different Python, install
it into that environment:

```
pip install nurgling
```

Until the package is on PyPI, install it from a client checkout instead:

```
pip install -e path/to/nurgling2/sdk/python
```

Keep the installed SDK's major version the same as the client's API version (both are `1.x` today).
A newer minor version of the SDK works with an older client: ops the client does not have raise
`UnknownOp`.

## Changing your script

You never restart the game: edit the file, save, and click the script again (or run it from a
terminal). See [Developing: edit, run, repeat](guides/developing.md) for the fastest workflows,
including a live Python prompt and IDE debugging.

## Next

- [Concepts](concepts.md) explains what `nc` is, snapshots, waits, errors and events.
- The [reference](reference/index.md) lists every op.
- The [cookbook](cookbook/index.md) has complete scripts.
