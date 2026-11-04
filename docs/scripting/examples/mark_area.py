# nurgling: name="Mark objects in an area", description="Asks for an area and a name, then highlights and counts the matching objects", icon=measuring_length
"""A settings form plus overlays. Overlays vanish by themselves when the script ends or is stopped."""
from nurgling import Cancelled, NotFound, script


@script
def main(nc):
    try:
        ans = nc.form.ask("Mark objects", [
            {"name": "area", "type": "area", "label": "Area", "required": True, "map": False},
            {"name": "what", "type": "str", "label": "Object name contains", "default": "trees/"},
            {"name": "color", "type": "choice", "label": "Colour", "values": ["orange", "teal", "red"], "default": "orange"},
        ])
    except Cancelled:
        return False

    color = {"orange": "#e99c54", "teal": "#40c0a0", "red": "#e04040"}[ans["color"]]
    area = nc.areas.get(ans["area"])
    try:
        gobs = nc.gobs.find(name=ans["what"], area=area)
    except NotFound:                         # the area is not loaded around you
        nc.form.message(f"Walk closer to {area.name} first.")
        return False

    nc.overlay.rect(area.rect, color=color, label=area.name)
    for g in gobs:
        nc.overlay.highlight(g, color=color + "a0")
    if gobs:
        nc.overlay.label(gobs[0], f"nearest of {len(gobs)}", color=color)

    nc.form.confirm(f"{len(gobs)} object(s) matching '{ans['what']}' in {area.name}. Done looking?", title="Mark objects")
    nc.overlay.clear()                       # optional: ending the run clears them anyway
