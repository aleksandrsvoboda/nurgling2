# Cookbook

Complete scripts you can copy into your scripts folder and adapt. Each page shows the whole script,
says what it needs (areas, tools, setup) and explains how it works. The same files are in
[`docs/scripting/examples/`](https://github.com/aleksandrsvoboda/nurgling2/tree/master/docs/scripting/examples)
in the repository, and the client ships them: they are in the bot menu under **Scripts > Examples**.

| Recipe | Shows |
|---|---|
| [Take items from the nearest container](take-from-container.md) | Header parameters, finding a container, open / take / close in one cycle. |
| [Chop nearby trees](chop-trees.md) | Aliases with exclusions, the flower menu, pose waits, stamina and drinking, which errors to skip. |
| [Store your inventory](store-inventory.md) | Areas and specialisations: `free_inventory`, `take_items`, `areas.for_item`. |
| [Run built-in bots in sequence](bot-chain.md) | `bots.run` with parameters, stopping on failure, `run.finish` for scenarios. |
| [A settings form with overlays](form-and-overlays.md) | `form.ask`, rectangles, highlights and labels, cleanup when the script ends. |
| [React to events](events.md) | `nc.on` with `chat.msg` and `gob.new`, recording `ui.out` to discover messages. |
| [Two characters at once](two-characters.md) | `nc.session`, parallel work with threads, global coordinates. |
| [Run a Java action](java-action.md) | Layer 4: `java.run_action` and references, with the stability warning. |

Start with [`hello.py`](../getting-started.md#3-your-first-script) if you haven't run a script yet.
