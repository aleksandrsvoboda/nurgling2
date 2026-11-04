# nurgling: name="Both fill waterskins", description="This character and a second logged-in one fill waterskins at the same time", icon=filwater
# nurgling-param: alt str "" "Second character (empty: any other logged-in one)"
"""Drive a second logged-in character from the same script (nc.session)."""
import threading

from nurgling import Stopped, script


def fill(client, results):
    try:
        results[client.character] = client.bots.run("filwaterzone")
    except Stopped:                      # its Gear was stopped on its own
        results[client.character] = None


@script
def main(nc):
    name = nc.run.params().get("alt")
    if not name:
        others = [s["character"] for s in nc.sessions.list() if s["character"] != nc.character]
        if not others:
            nc.chat.notify("Log in a second character first", error=True)
            return False
        name = others[0]

    alt = nc.session(name)               # a second run, with its own Gear on that character
    results = {}
    t = threading.Thread(target=fill, args=(alt, results))
    t.start()
    fill(nc, results)                    # both work in parallel
    t.join()

    # World positions are per session; compare them through global coordinates.
    a = nc.map.to_global(nc.gobs.player())
    b = alt.map.to_global(alt.gobs.player()) if not alt.stopped else None
    if a and b and a["grid"] == b["grid"]:
        print(f"Both in grid {a['grid']}, {((a['x'] - b['x']) ** 2 + (a['y'] - b['y']) ** 2) ** 0.5 / 11:.0f} tiles apart")

    if not alt.stopped:
        alt.close_session()
    print(results)
    return all(results.values())
