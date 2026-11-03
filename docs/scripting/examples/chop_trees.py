# nurgling: name="Chop nearby trees", description="Chops the nearest grown trees, drinking when stamina runs low", icon=choper
# nurgling-param: count int 5 "How many trees"
# nurgling-param: radius int 15 "Search radius in tiles"
"""Chop trees one after another. Needs an axe (in hand, on the belt or in the inventory) and water in a waterskin."""
from nurgling import ActionFailed, GobGone, NotFound, PathNotFound, Timeout, script

TILE = 11.0
TREES = {"include": ["gfx/terobjs/trees/"], "exclude": ["stump", "log", "trunk"]}
CHOPPING = "treechop"          # part of the player's pose while chopping


def stamina(nc):
    s = nc.player.get()["stamina"]
    return 1.0 if s is None else s


def felled(nc, tree):
    now = nc.gobs.get(tree)    # None when the object is gone
    return now is None or "stump" in now.name or "log" in now.name


def chop(nc, tree):
    """Chops one tree; True when it is down."""
    me = nc.gobs.player()
    while True:
        if stamina(nc) < 0.4:
            nc.actions.drink(level=0.9)
        nc.actions.flower(tree, "Chop")             # the server walks there by itself
        nc.wait.pose(me, CHOPPING, timeout=20)     # started chopping
        nc.wait.pose_not(me, CHOPPING, timeout=900)
        if felled(nc, tree):
            return True
        if stamina(nc) >= 0.4:                     # stopped for another reason
            return False
        # out of stamina: drink and continue on the same tree


@script
def main(nc):
    p = nc.run.params()
    count = int(p.get("count", 5))
    radius = int(p.get("radius", 15)) * TILE
    skipped, done = [], 0

    try:
        nc.actions.equip("Axe")                     # from the belt or inventory; no-op if already held
    except (ActionFailed, NotFound):
        nc.chat.notify("You need an axe in hand, on your belt or in your inventory", error=True)
        return False

    while done < count:
        tree = nc.gobs.nearest(name=TREES, radius=radius, exclude=skipped)
        if tree is None:
            break
        try:
            if chop(nc, tree):
                done += 1
            else:
                skipped.append(tree.id)
        except (GobGone, NotFound, PathNotFound, Timeout) as e:
            # NotFound: the menu had no "Chop" (a sapling); PathNotFound: unreachable
            print(f"skipping {tree.name}: {e}")
            skipped.append(tree.id)
        except ActionFailed as e:
            nc.chat.notify(f"Stopping: {e.msg}", error=True)   # e.g. nothing left to drink
            return False

    nc.chat.notify(f"Chopped {done} tree(s)")
    return done > 0
