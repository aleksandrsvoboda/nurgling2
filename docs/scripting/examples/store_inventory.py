# nurgling: name="Store my inventory", description="Puts everything away into its areas, then fetches one item"
# nurgling-param: fetch str "" "Item to fetch afterwards (empty: nothing)"
# nurgling-param: fetch_count int 0 "How many to fetch"
"""Store and fetch with your areas and specialisations, the way the built-in bots do.

Set up areas first: give each storage area the items it takes (or a specialisation).
"""
from nurgling import script


def names(nc):
    return sorted({i.name for i in nc.inv.items() if i.name})


@script
def main(nc):
    p = nc.run.params()

    before = names(nc)
    homeless = [n for n in before if not nc.areas.for_item(n, "out")]
    if homeless:
        print("No area takes:", ", ".join(homeless))

    nc.actions.free_inventory()            # walks to every area it needs, then returns
    left = names(nc)
    print("Still carrying:", ", ".join(left) or "nothing")

    fetch, n = (p.get("fetch") or "").strip(), int(p.get("fetch_count") or 0)
    if fetch and n > 0:
        have = nc.actions.take_items(fetch, n)
        nc.chat.notify(f"Now carrying {have} x {fetch}")
        return have >= n
    nc.chat.notify(f"Stored {len(before) - len(left)} kind(s) of items")
    return True
