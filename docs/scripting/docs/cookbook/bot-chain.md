# Run built-in bots in sequence

Chains three of the client's own bots: go to an area, fill waterskins, empty the inventory. It stops
at the first bot that fails and reports the outcome, so it can itself be a step in a scenario.

```python
--8<-- "bot_chain.py"
```

How it works:

- Bot ids come from the [bot catalogue](../reference/bot-catalogue.md) or `nc.bots.list()`.
  `goto_area` declares a required `areaId` parameter; `bots.run` checks it before the bot starts.
- The `target` header parameter is an area, so the start form offers your saved areas. Started from a
  terminal there are no parameters, so the script asks with `form.ask` instead.
- `bots.get` on each id first means a typo fails right away (`NotFound`) instead of after the walk.
- `bots.run` returns `False` when a bot fails; it does not raise. `run.finish(success=False)` tells
  the client the script failed, which stops a scenario running it.
