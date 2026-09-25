package nurgling.widgets;

import haven.*;
import nurgling.NConfig;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Faded "ghost" images of the curiosities that last sat in each study report slot, so a
 * finished (or taken out) curiosity leaves a reminder of what to put back there.
 * <p>
 * Lives as the bottom-most child of the study inventory, so the ghosts draw above the slot
 * squares and below the live items, and the Study Report mirror widget shows them too.
 * The layout is remembered per character:
 * <pre>
 * studyReportGhosts: { "characters": { "&lt;chrid&gt;": [ {"res": ..., "x": 0, "y": 0, "w": 1, "h": 1}, ... ] } }
 * </pre>
 * Ported from Hurricane's StudyInventory; unlike it, a new item clears every ghost its
 * footprint touches, not only ghosts anchored inside it.
 */
public class StudyReportGhosts extends Widget {
    private static final Color TINT = new Color(238, 238, 238, 160);
    private static final String CHARACTERS_KEY = "characters";

    private final Inventory study;
    private final List<Ghost> ghosts = new ArrayList<>();
    private String chrid;

    private static class Ghost {
        final String res;
        final Coord ul, span;
        final Indir<Resource> ind;
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
            if (broken)
                return null;
            try {
                return ind.get().layer(Resource.imgc);
            } catch (Loading l) {
                return null;
            } catch (Resource.LoadException | Resource.BadResourceException e) {
                broken = true;
                return null;
            }
        }
    }

    public StudyReportGhosts(Inventory study) {
        super(study.sz);
        this.study = study;
    }

    @Override
    protected void added() {
        super.added();
        chrid = (ui.gui != null) ? ui.gui.chrid : null;
        load();
    }

    /** Cells covered by an item image of the given (UI-scaled) size. */
    private static Coord span(Coord ssz) {
        return ssz.div(Inventory.sqsz).add(1, 1);
    }

    private static Coord cell(WItem w) {
        return w.c.sub(1, 1).div(Inventory.sqsz);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        boolean changed = false;
        for (WItem w : study.children(WItem.class)) {
            Resource res;
            try {
                res = w.item.getres();
            } catch (Loading l) {
                continue;
            }
            Resource.Image img = res.layer(Resource.imgc);
            if (img != null)
                changed |= record(res.name, cell(w), span(img.ssz));
        }
        if (changed)
            save();
    }

    private boolean record(String res, Coord ul, Coord span) {
        for (Ghost g : ghosts) {
            if (g.res.equals(res) && g.ul.equals(ul) && g.span.equals(span))
                return false;
        }
        ghosts.removeIf(g -> g.overlaps(ul, span));
        ghosts.add(new Ghost(res, ul, span));
        return true;
    }

    @Override
    public void draw(GOut g) {
        if (!(Boolean) NConfig.get(NConfig.Key.showStudyReportGhosts))
            return;
        g.chcolor(TINT);
        for (Ghost gh : ghosts) {
            if (occupied(gh))
                continue;
            Resource.Image img = gh.image();
            if (img != null)
                g.image(img, gh.ul.mul(Inventory.sqsz).add(1, 1));
        }
        g.chcolor();
    }

    private boolean occupied(Ghost gh) {
        for (Widget w = study.child; w != null; w = w.next) {
            if ((w instanceof WItem) && gh.overlaps(cell((WItem) w), span(w.sz)))
                return true;
        }
        return false;
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
