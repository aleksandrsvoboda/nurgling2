package nurgling.widgets;

import haven.*;
import nurgling.craft.*;
import nurgling.tools.ItemIcons;

/** The same payload as MenuGrid for live actions; offline recipes remain local data. */
public abstract class RecipeListbox extends Listbox<AtlasCatalog.Recipe> {
    private final AtlasSource source;
    private UI.Grab dragGrab;
    private AtlasCatalog.Recipe dragged;
    private Coord start, cursor;
    private boolean dragging;

    public RecipeListbox(AtlasSource source, int width, int rows, int itemHeight) {
        super(width, rows, itemHeight);
        this.source = source;
    }

    private Object payload() {
        return new RecipeTransfer(source, dragged);
    }

    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1 && ev.c.isect(Coord.z, sz) && ev.c.x < sz.x - sb.sz.x) {
            AtlasCatalog.Recipe recipe = itemat(ev.c);
            if(recipe != null) {
                cancelDrag();
                change(recipe);
                dragged = recipe; start = ev.c; cursor = rootpos().add(ev.c);
                dragGrab = ui.grabmouse(this);
                return true;
            }
        }
        return super.mousedown(ev);
    }

    public void mousemove(MouseMoveEvent ev) {
        if(dragGrab != null) {
            cursor = rootpos().add(ev.c);
            if(ev.c.dist(start) > UI.scale(4)) dragging = true;
            if(dragging) DropTarget.drophover(ui.root, cursor, payload());
        }
        super.mousemove(ev);
    }

    public boolean mouseup(MouseUpEvent ev) {
        if(ev.b != 1 || dragGrab == null) return super.mouseup(ev);
        try {
            if(dragging) DropTarget.dropthing(ui.root, rootpos().add(ev.c), payload());
        } finally { cancelDrag(); }
        return true;
    }

    public void cancelDrag() {
        if(dragGrab != null) { dragGrab.remove(); dragGrab = null; }
        if(dragging && ui != null)
            ui.dispatch(ui.root, new DropTarget.Hover(Coord.z, dragged).hovering(false));
        dragged = null; dragging = false;
    }

    public void draw(GOut g) {
        super.draw(g);
        if(dragging) ui.drawafter(out -> {
            if(!dragging || dragged == null) return;
            AtlasCatalog.Material output = dragged.outputs.isEmpty() ? null : dragged.outputs.get(0);
            Tex icon = output == null ? ItemIcons.get("", dragged.name, false)
                : ItemIcons.get(output.resource, output.name, output.category);
            if(icon == null) {
                MenuGrid.Pagina page = source.page(dragged.resource);
                if(page != null) try { icon = ItemIcons.getResource(page.res().name); } catch(Loading ignored) {}
            }
            ItemIcons.draw(out, icon, cursor.sub(UI.scale(16, 16)), UI.scale(32));
        });
    }

    public Object tooltip(Coord c, Widget prev) { return null; }
    public void hide() { cancelDrag(); super.hide(); }
    public void dispose() { cancelDrag(); super.dispose(); }
}
