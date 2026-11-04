# nurgling: name="Lookout", description="Tells you when someone says your name or an animal shows up; can record UI messages", icon=discord
# nurgling-param: record bool false "Also print every UI message I send (to discover messages)"
"""React to events instead of polling. Runs until you press stop."""
import time

from nurgling import script


@script
def main(nc):
    me = (nc.character or "").lower()

    def on_chat(ev):
        d = ev.data
        if me and me in (d.get("text") or "").lower():
            nc.chat.notify(f"{d.get('from') or 'Someone'} in {d.get('channel')}: {d.get('text')}")

    def on_animal(ev):
        nc.chat.notify(f"Animal nearby: {ev.data['name'].rsplit('/', 1)[-1]}")

    def on_out(ev):
        d = ev.data
        print(f"wdg={d['wdg']} class={d['class']} msg={d['msg']!r} args={d['args']}")

    nc.on("chat.msg", on_chat)
    nc.on("gob.new", on_animal, name="gfx/kritter/*")    # filtered in the client
    if nc.run.params().get("record"):
        nc.on("ui.out", on_out)

    # Wait on the Python side, not with nc.sleep(): the handlers' calls run on this script's bot
    # thread too, and would queue behind a long wait there.
    while not nc.stopped:
        time.sleep(0.5)
