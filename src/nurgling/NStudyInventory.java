package nurgling;

import haven.*;
import nurgling.widgets.CurioFinishedAlert;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The study report inventory. NUI creates it in place of a plain NInventory for the "study" slot.
 * <p>
 * Remembers which curiosity last sat in each slot and draws it as a faded "ghost" once the slot is
 * empty, like Hurricane's StudyInventory. Items are recorded as they are added and again as they
 * are removed, so curiosities the server removes right after login are caught too. The layout
 * is kept per character:
 * <pre>
 * studyReportGhosts: { "characters": { "&lt;chrid&gt;": [ {"res": ..., "x": 0, "y": 0, "w": 1, "h": 1}, ... ] } }
 * </pre>
 * Unlike Hurricane, a new item clears every ghost its footprint touches, not only ghosts
 * anchored inside it.
 */
public class NStudyInventory extends NInventory {
    private static final Color TINT = new Color(238, 238, 238, 160);
    private static final String CHARACTERS_KEY = "characters";

    private final List<Ghost> ghosts = new ArrayList<>();
    private String chrid;
    /** Some item couldn't be recorded yet because its resource is still loading. */
    private boolean pending = false;

    private static class Ghost {
        final String res;
        final Coord ul, span;
        final Indir<Resource> ind;
        Resource.Image img = null;
        boolean broken = false;

        Ghost(String res, Coord ul, Coord span) {
            this.res = res;
            this.ul = ul;
            this.span = span;
            this.ind = Resource.remote().load(res);
        }

        boolean overlaps(Coord oul, Coord ospan) {
            return (ul.x < oul.x + ospan.x) && (oul.x < ul.x + span.x) &&
                   (ul.y < oul.y + ospan.y) && (oul.y < ul.y + span.y);
        }

        Resource.Image image() {
            if (img == null && !broken) {
                try {
                    img = ind.get().layer(Resource.imgc);
                    broken = (img == null);
                } catch (Loading l) {
                    return null;
                } catch (Resource.LoadException | Resource.BadResourceException e) {
                    broken = true;
                }
            }
            return img;
        }
    }

    public NStudyInventory(Coord sz) {
        super(sz);
    }

    @Override
    protected void added() {
        super.added();
        chrid = (ui.gui != null) ? ui.gui.chrid : null;
        load();
    }

    /** Slots covered by a sprite of the given pixel size, rounded the way WItem sizes itself. */
    private static Coord cells(Coord px) {
        return Coord.of(Math.max(1, (px.x + sqsz.x / 2) / sqsz.x), Math.max(1, (px.y + sqsz.y / 2) / sqsz.y));
    }

    /** Records the item's slot; returns whether the ghost layout changed. Throws Loading until the item's resource is in. */
    private boolean record(WItem w) {
        Resource res = w.item.getres();
        Resource.Image img = res.layer(Resource.imgc);
        if (img == null)
            return false;
        Coord ul = w.c.sub(1, 1).div(sqsz);
        Coord span = cells(img.ssz);
        for (Ghost g : ghosts) {
            if (g.res.equals(res.name) && g.ul.equals(ul) && g.span.equals(span))
                return false;
        }
        ghosts.removeIf(g -> g.overlaps(ul, span));
        ghosts.add(new Ghost(res.name, ul, span));
        return true;
    }

    private void recordAll() {
        boolean changed = false;
        pending = false;
        for (WItem w : children(WItem.class)) {
            try {
                changed |= record(w);
            } catch (Loading l) {
                pending = true;
            }
        }
        if (changed)
            save();
    }

    @Override
    public void addchild(Widget child, Object... args) {
        super.addchild(child, args);
        if (child instanceof GItem)
            recordAll();
    }

    @Override
    public void cdestroy(Widget w) {
        super.cdestroy(w);
        if (w instanceof WItem) {
            try {
                if (record((WItem) w))
                    save();
            } catch (Loading l) {
                // Left before its resource loaded; nothing to show for it.
            }
            CurioFinishedAlert.removed((WItem) w);
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (pending)
            recordAll();
    }

    @Override
    public void draw(GOut g) {
        if (Boolean.TRUE.equals(NConfig.get(NConfig.Key.showStudyReportGhosts))) {
            g.chcolor(TINT);
            for (Ghost gh : ghosts) {
                Resource.Image img = gh.image();
                if (img != null)
                    g.image(img, gh.ul.mul(sqsz).add(1, 1));
            }
            g.chcolor();
        }
        super.draw(g);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (chrid == null || chrid.isEmpty())
            return;
        Object chars = NConfig.getAsMap(NConfig.Key.studyReportGhosts).get(CHARACTERS_KEY);
        if (!(chars instanceof Map))
            return;
        Object list = ((Map<String, Object>) chars).get(chrid);
        if (!(list instanceof List))
            return;
        for (Object o : (List<Object>) list) {
            if (!(o instanceof Map))
                continue;
            Map<String, Object> e = (Map<String, Object>) o;
            if ((e.get("res") instanceof String) && (e.get("x") instanceof Number) && (e.get("y") instanceof Number) &&
                (e.get("w") instanceof Number) && (e.get("h") instanceof Number)) {
                ghosts.add(new Ghost((String) e.get("res"),
                        Coord.of(((Number) e.get("x")).intValue(), ((Number) e.get("y")).intValue()),
                        Coord.of(((Number) e.get("w")).intValue(), ((Number) e.get("h")).intValue())));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void save() {
        if (chrid == null || chrid.isEmpty())
            return;
        List<Object> list = new ArrayList<>();
        for (Ghost g : ghosts) {
            Map<String, Object> e = new HashMap<>();
            e.put("res", g.res);
            e.put("x", g.ul.x);
            e.put("y", g.ul.y);
            e.put("w", g.span.x);
            e.put("h", g.span.y);
            list.add(e);
        }
        Object old = NConfig.getAsMap(NConfig.Key.studyReportGhosts).get(CHARACTERS_KEY);
        Map<String, Object> chars = (old instanceof Map) ? new HashMap<>((Map<String, Object>) old) : new HashMap<>();
        chars.put(chrid, list);
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put(CHARACTERS_KEY, chars);
        NConfig.set(NConfig.Key.studyReportGhosts, wrapper);
    }
}
