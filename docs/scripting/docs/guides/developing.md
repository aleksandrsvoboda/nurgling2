# Developing a script: edit, run, repeat

You never need to restart the game while writing a script. A script is a separate Python program:
every run reads your file fresh from disk, so you can change it and run it again as often as you
like while your character stays logged in.

There are three ways to work, from simplest to fastest.

## 1. Edit and click (the bot menu)

1. Open your script in any editor and save your changes.
2. Click the script in the bot menu (**Scripts > My scripts**) again.

The new code runs. That includes changed header parameters: the start form asks for the new ones.

Two things need a **Rescan scripts** (Options > Python Scripting) or the next login to show up,
because they change the menu itself rather than the code:

- a new script file;
- a changed `name`, `description` or icon (see [icon and tooltip](script-header.md#icon-and-tooltip)).

When the script fails, the last line of the error appears in the game's message area and the whole
traceback is in `scripts/logs/<script>-<date>-<time>.log`. Anything you `print()` is in the same log.

## 2. Run it from a terminal or your IDE (best while developing)

With the game running and scripting on, start the script yourself:

```text
python my_script.py
```

It attaches to the character in the visible tab (or `connect(session="Name")` picks one), gets its
own row in the Bot Status strip, and prints straight to your terminal. The loop becomes:

1. edit and save;
2. run `python my_script.py`;
3. watch it in the game, read the output;
4. press **Ctrl+C** in the terminal, or the stop button on its Gear, and go back to 1.

Scripts written with `@script` work both ways: the decorator connects to the run the client prepared
when the bot menu starts them, and attaches when you start them yourself. Header parameters are
not filled in when you attach, so give `nc.run.params().get(...)` a default.

To run it from **VS Code** or **PyCharm**, open the script and use the normal Run or Debug command.
The debugger works as usual: set a breakpoint, step through, inspect the snapshots your calls return.
The game simply waits while you are paused, since nothing is sent to it.

For autocomplete and type hints in your editor, install the SDK into the Python your editor uses:

```text
pip install nurgling
```

(or point the editor at the copy the client ships in `scripting/sdk`).

## 3. Try things live (Python prompt or Jupyter)

For exploring, connect from an interactive prompt and run one line at a time against the live game:

```pycon
>>> from nurgling import connect
>>> nc = connect()
>>> tree = nc.gobs.nearest(name="trees/")
>>> tree
<Gob 123456 'gfx/terobjs/trees/oak'>
>>> nc.overlay.highlight(tree)          # see which one it found
1
>>> nc.actions.path_to(tree)            # walk there
>>> nc.raw.click_gob(tree)              # what does its menu offer?
>>> nc.wait.flower_menu()
['Chop', 'Take bark', 'Take branch']
>>> nc.raw.flower_choose(None)          # close it again
```

Every call returns immediately with real data, so you can poke at objects, try an action, look at the
result and adjust. When a few lines do what you want, paste them into your script. A Jupyter notebook
works the same way and keeps the history of what you tried.

`nc.core.describe(op="gobs.find")` prints an op's documentation, and `help(nc.gobs.find)` shows the
same from the SDK. Overlays and anything else the session created disappear when you call
`nc.close()` or the prompt exits.

## What does not reload

Changes to a script take effect on its **next run**: a run that is already going keeps the code it
started with. Stop it and start it again; that takes a second and your character stays where it is.

## Tips

- Keep scripts small while developing: a function per step, called from `main`, so you can run one
  step at a time from the prompt (`import my_script; my_script.chop(nc, tree)`).
- Use `nc.msg("...")` to see progress in the game, `print()` for details in the log or terminal.
- [Overlays](script-ui.md) are a quick way to check what the script "sees": highlight the objects it
  found or draw the area it is working in.
- Discover the raw messages the game uses with the `ui.out` event while you do the action by hand;
  see [Raw UI](raw-ui.md).
