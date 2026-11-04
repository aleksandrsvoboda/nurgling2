# API reference

Generated from the client's annotations (API 1.0). Every op is listed with its wire name, which is also the SDK call: `gobs.find` is `nc.gobs.find(...)`.

| Namespace | What it covers | Ops |
|---|---|---|
| [Core](core.md) | Connection checks and the API's own description. | 3 |
| [Sessions](sessions.md) | Logged-in characters. | 1 |
| [Runs](runs.md) | Bot threads of this script; see multi-session scripts. | 3 |
| [This run](run.md) | Outcome and start parameters of the current run. | 2 |
| [Events](events.md) | Event subscriptions. Use `nc.events(...)` and `nc.on(...)` instead of these ops. | 2 |
| [Bots](bots.md) | Layer 1: run the client's own bots. | 3 |
| [Scenarios](scenarios.md) | Layer 1: run saved scenarios. | 2 |
| [Player](player.md) | The player character: position, stats, meters, buffs, speed. | 5 |
| [Game objects](gobs.md) | Find and inspect objects in the world. | 6 |
| [Inventories](inv.md) | Items in the player's and containers' inventories, the hand, stockpiles. | 9 |
| [Equipment](equip.md) | Equipped items and the belt. | 2 |
| [Windows](windows.md) | Open windows: find, read, close, press buttons. | 5 |
| [Areas](areas.md) | Saved areas (zones) and their specialisations. | 7 |
| [Map](map.md) | Tiles, reachability and coordinates. | 6 |
| [Chat & messages](chat.md) | Chat channels and the game's message area. | 4 |
| [Actions](actions.md) | High-level actions built on the client's own Java actions: walking, containers, items, building. | 39 |
| [Crafting](craft.md) | Recipes, the craft window, and menu actions. | 6 |
| [Waits](wait.md) | Block until something happens, with a timeout. | 25 |
| [Combat](combat.md) | Fight state, attacking and combat moves. | 5 |
| [Forms & pickers](form.md) | Script UI: settings forms, area and object pickers. | 6 |
| [Overlays](overlay.md) | Script UI: highlights, shapes, labels and markers drawn in the world. Removed when the run ends. | 9 |
| [Widgets](ui.md) | Layer 3: the widget tree and raw widget messages. | 5 |
| [Raw input](raw.md) | Layer 3: low-level clicks and item messages, exactly as the mouse would send them. | 17 |
| [Java (unstable)](java.md) | Layer 4: reflective access to the client's Java code. No stability guarantees; enable with `java=True`. | 10 |

See also: [snapshot types](types.md), [errors](errors.md), [bot catalogue](bot-catalogue.md), [wire protocol](../protocol.md).
