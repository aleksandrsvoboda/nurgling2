package nurgling.sessions.commander;

import haven.Coord;
import haven.Widget;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.sessions.SessionManager;
import nurgling.widgets.NDraggableWidget;
import nurgling.widgets.NResizableWidget;
import org.json.JSONObject;

import java.util.*;

/**
 * Commander mode's own layout, one for the whole client: where each HUD widget that shows in the mode (the Commander
 * panels, the minimap, the bot menu, the action menu, the main menu) sits, and how big the resizable ones are. Every
 * session's game UI has its own copies of these widgets, so without this a switch would show the other copy where
 * that character left it. Every session takes this layout when it enters the mode and again whenever it changes; a
 * widget moved in the mode moves here, never in the character's own layout, which comes back when the mode ends.
 * Kept in the config ({@code commanderLayout}).
 */
public final class CommanderLayout {
    static final class Pos {
        final Coord c, sz;

        Pos(Coord c, Coord sz) {
            this.c = c;
            this.sz = sz;
        }
    }

    private static Map<String, Pos> layout;
    private static volatile int version = 0;

    private CommanderLayout() {}

    private static synchronized Map<String, Pos> layout() {
        if (layout == null) {
            layout = new HashMap<>();
            Object v = NConfig.getGlobal(NConfig.Key.commanderLayout);
            if (v instanceof String && !((String) v).isEmpty()) {
                try {
                    JSONObject o = new JSONObject((String) v);
                    for (String k : o.keySet()) {
                        JSONObject p = o.getJSONObject(k);
                        Coord sz = p.has("w") ? Coord.of(p.getInt("w"), p.getInt("h")) : null;
                        layout.put(k, new Pos(Coord.of(p.getInt("x"), p.getInt("y")), sz));
                    }
                } catch (org.json.JSONException e) {
                    /* A broken value: start over from where the widgets are. */
                }
            }
        }
        return layout;
    }

    private static synchronized void save() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Pos> e : layout().entrySet()) {
            JSONObject p = new JSONObject();
            p.put("x", e.getValue().c.x);
            p.put("y", e.getValue().c.y);
            if (e.getValue().sz != null) {
                p.put("w", e.getValue().sz.x);
                p.put("h", e.getValue().sz.y);
            }
            o.put(e.getKey(), p);
        }
        NConfig.set(NConfig.Key.commanderLayout, o.toString());
    }

    static int version() {
        return version;
    }

    /** True when this widget belongs to the Commander layout right now (its game UI is in the mode). */
    public static boolean owns(NDraggableWidget w) {
        if (!SessionManager.getInstance().squad().commander() || w.ui == null)
            return false;
        NGameUI gui = ((NUI) w.ui).gui;
        return gui != null && w.parent == gui && gui.commanderScreen.on();
    }

    /**
     * A widget was moved or resized in Commander mode: the Commander layout takes it (and every session follows),
     * instead of the character's own layout. True when it did.
     */
    public static boolean record(NDraggableWidget w, Coord at) {
        if (!owns(w))
            return false;
        synchronized (CommanderLayout.class) {
            layout().put(w.getName(), new Pos(new Coord(at), (w instanceof NResizableWidget) ? new Coord(w.sz) : null));
            version++;
            save();
        }
        return true;
    }

    /**
     * Put the game UI's mode widgets where the Commander layout says, remembering in {@code own} where they were (the
     * first time only). A widget the layout doesn't know yet joins it where it is.
     */
    static void apply(NGameUI gui, Map<NDraggableWidget, Pos> own, java.util.function.Predicate<Widget> inMode) {
        boolean added = false;
        synchronized (CommanderLayout.class) {
            Map<String, Pos> l = layout();
            for (Widget w = gui.child; w != null; w = w.next) {
                if (!(w instanceof NDraggableWidget) || !inMode.test(w))
                    continue;
                NDraggableWidget d = (NDraggableWidget) w;
                String name = d.getName();
                if (name == null)
                    continue;
                Pos p = l.get(name);
                if (p == null) {
                    l.put(name, new Pos(new Coord(d.target_c), (d instanceof NResizableWidget) ? new Coord(d.sz) : null));
                    added = true;
                    continue;
                }
                if (!own.containsKey(d))
                    own.put(d, new Pos(new Coord(d.target_c), new Coord(d.sz)));
                d.target_c = new Coord(p.c);
                if (p.sz != null && d instanceof NResizableWidget && !p.sz.equals(d.sz))
                    ((NResizableWidget) d).setSize(p.sz);
            }
            if (added)
                save();
        }
    }

    /** The mode ended: every widget back where the character had it. */
    static void restore(Map<NDraggableWidget, Pos> own) {
        for (Map.Entry<NDraggableWidget, Pos> e : own.entrySet()) {
            NDraggableWidget d = e.getKey();
            d.target_c = new Coord(e.getValue().c);
            if (d instanceof NResizableWidget && !e.getValue().sz.equals(d.sz))
                ((NResizableWidget) d).setSize(e.getValue().sz);
        }
        own.clear();
    }
}
