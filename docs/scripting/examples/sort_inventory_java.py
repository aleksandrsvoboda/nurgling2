# nurgling: name="Sort inventory (Java)", description="Runs the client's own SortInventory action through the Java layer", java=true, icon=sort_containers
"""Layer 4 example: run a Java action the stable API does not wrap.

WARNING: no stability guarantees. Class names and constructors are the client's internals and
may change in any update; when they do, this script fails with JavaError.
"""
from nurgling import JavaError, script


@script
def main(nc):
    inv = nc.java.ref("inventory")           # your main inventory, as a Java reference
    try:
        ok = nc.java.run_action("nurgling.actions.SortInventory", [inv])
    except JavaError as e:
        nc.chat.notify(f"This client changed; the Java call failed: {e.msg}", error=True)
        return False
    nc.java.release([inv["ref"]])            # references are also released when the script ends
    nc.chat.notify("Inventory sorted" if ok else "Sorting failed")
    return ok
