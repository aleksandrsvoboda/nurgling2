# nurgling: name="Morning round", description="Goes to an area, fills waterskins and empties the inventory, stopping at the first failure"
# nurgling-param: target area - "Area to go to first"
"""Built-in bots as building blocks (layer 1)."""
from nurgling import script


@script
def main(nc):
    target = nc.run.params().get("target")
    if target is None:                      # started from a terminal: ask instead
        target = nc.form.ask("Morning round", [
            {"name": "target", "type": "area", "label": "Area to go to first", "required": True, "map": False},
        ])["target"]

    steps = [
        ("goto_area", {"areaId": target}),
        ("filwaterzone", {}),
        ("freeinv", {}),
    ]
    for bot_id, _ in steps:                 # fail early on a typo, before walking anywhere
        nc.bots.get(bot_id)

    for bot_id, params in steps:
        print("running", bot_id, params)
        if not nc.bots.run(bot_id, params=params):
            nc.chat.notify(f"{bot_id} failed; stopping", error=True)
            nc.run.finish(success=False)    # a scenario that runs this script stops here too
            return
    nc.run.finish(success=True)
