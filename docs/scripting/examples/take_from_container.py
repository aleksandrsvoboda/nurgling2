# nurgling: name="Take from nearest container", description="Opens the nearest chest, crate or cupboard and takes items by name"
# nurgling-param: item str Branch "Item to take (name as in its tooltip)"
# nurgling-param: count int 5 "How many"
"""Take a few items out of the nearest container: walk, open once, take, close."""
from nurgling import Inventory, script

TILE = 11.0
# Containers are matched by resource name; items by their display name.
CONTAINERS = ["gfx/terobjs/cupboard", "gfx/terobjs/chest", "gfx/terobjs/crate", "gfx/terobjs/largechest"]


@script
def main(nc):
    p = nc.run.params()
    item = p.get("item", "Branch")
    count = int(p.get("count", 5))

    if nc.inv.free_space() == 0:
        nc.chat.notify("Your inventory is full", error=True)
        return False

    box = nc.gobs.nearest(name=CONTAINERS, radius=20 * TILE)
    if box is None:
        nc.chat.notify("No container within 20 tiles", error=True)
        return False

    inv = nc.actions.open(box)              # walks there and waits for the window
    if not isinstance(inv, Inventory):      # a stockpile or barrel opens a window without a grid
        nc.actions.close(inv)
        nc.chat.notify("That container has no item grid", error=True)
        return False

    # Check and act in the same open/close cycle.
    there = nc.inv.items(inventory=inv, name=item)
    taken = nc.actions.take_from(inv, item, count=count) if there else 0
    nc.actions.close(inv)

    nc.chat.notify(f"Took {taken} x {item} ({len(there)} were there)")
    return taken > 0
