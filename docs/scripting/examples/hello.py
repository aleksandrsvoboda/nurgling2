# nurgling: name="Hello", description="Prints who and where you are, your open windows and what is around you"
"""A first script. Copy it into your scripts folder and start it from the bot menu (Scripts).

Everything it prints goes to scripts/logs/<name>-<date>-<time>.log; a window shows the summary. Run it from a terminal instead
(``python hello.py`` while the client is running) and the output appears right there.
"""
from nurgling import script

TILE = 11.0  # world units per tile


@script
def main(nc):
    info = nc.core.version()
    print(f"Connected as {nc.character} (API {info['api']}, client build {info['client']})")

    for s in nc.sessions.list():
        mark = "*" if s["active"] else " "
        print(f" {mark} {s['character']}{' (background tab)' if s['headless'] else ''}")

    me = nc.gobs.player()
    x, y = me.rc
    print(f"You are at ({x:.1f}, {y:.1f}), tile ({int(x // TILE)}, {int(y // TILE)})")

    windows = [w for w in nc.ui.find(cls="Window") if w.get("caption") and w.visible]
    print("Open windows:", ", ".join(w.caption for w in windows) or "none")

    nearby = nc.gobs.find(radius=10 * TILE, limit=15)  # nearest first, never includes you
    print(f"Nearest objects within 10 tiles ({len(nearby)} shown):")
    for g in nearby:
        print(f"  {g.dist / TILE:5.1f} tiles  {g.name}")

    # print() goes to the log (or your terminal); show the gist in the game too.
    names = ", ".join(sorted({(g.name or "?").rsplit("/", 1)[-1] for g in nearby})) or "nothing"
    nc.form.message(
        f"Hello, {nc.character}!\n"
        f"You are at tile ({int(x // TILE)}, {int(y // TILE)}).\n"
        f"Within 10 tiles: {names}.\n"
        f"The full report is in this script's log (scripts/logs).",
        title="Hello from Python", keep=True)
