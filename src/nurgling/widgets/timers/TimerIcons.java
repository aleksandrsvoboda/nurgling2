package nurgling.widgets.timers;

import haven.*;
import nurgling.NStyle;
import nurgling.timers.Timer;
import nurgling.widgets.cookbook.CookbookTheme;

import java.awt.Color;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared drawing for timers: the progress ring, map pins, the small icons in banners and the panel, and a
 * cache for the countdown labels so the map does not render text every frame.
 */
public final class TimerIcons {
    public static final Color READY = new Color(122, 209, 122);
    public static final Color TRACK = new Color(0, 0, 0, 110);
    public static final Color DIM = new Color(255, 255, 255, 110);

    /** Pin colours, in the order the popover offers them. The key is what gets stored. */
    public static final String[] PIN_COLORS = {"orange", "blue", "green", "red", "purple", "yellow"};
    private static final Map<String, Color> PIN = new HashMap<>();
    static {
        PIN.put("orange", NStyle.border);
        PIN.put("blue", new Color(143, 169, 217));
        PIN.put("green", new Color(122, 209, 122));
        PIN.put("red", new Color(230, 96, 84));
        PIN.put("purple", new Color(179, 140, 255));
        PIN.put("yellow", new Color(240, 210, 96));
    }

    private static final Text.Furnace LABEL_ACTIVE = new PUtils.BlurFurn(
        new Text.Foundry(Text.dfont, UI.scale(9), Color.WHITE).aa(true), 2, 1, Color.BLACK);
    private static final Text.Furnace LABEL_READY = new PUtils.BlurFurn(
        new Text.Foundry(Text.dfont, UI.scale(9), READY).aa(true), 2, 1, Color.BLACK);

    /** Labels change at most once a minute, so a keyed cache covers every frame in between. */
    private static final Map<String, Tex> labels = new LinkedHashMap<String, Tex>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Tex> e) {
            if(size() > 512) {
                e.getValue().dispose();
                return true;
            }
            return false;
        }
    };

    private static Tex timerIcon, restartIcon;
    private static final Map<String, Tex> resIcons = new HashMap<>();

    private TimerIcons() {
    }

    public static Color pinColor(String key) {
        Color c = (key == null) ? null : PIN.get(key);
        return (c == null) ? NStyle.border : c;
    }

    /** A map label with a dark outline, like marker names. */
    public static synchronized Tex label(String text, boolean ready) {
        String key = (ready ? "r|" : "a|") + text;
        Tex t = labels.get(key);
        if(t == null) {
            t = (ready ? LABEL_READY : LABEL_ACTIVE).render(text).tex();
            labels.put(key, t);
        }
        return t;
    }

    /**
     * Plain text in a cookbook font, cached. The banners and the panel redraw every frame with text that
     * mostly stays the same, and rendering it afresh each time would leak a texture per frame.
     */
    public static synchronized Tex text(Text.Foundry f, String s, Color c) {
        String key = System.identityHashCode(f) + "|" + c.getRGB() + "|" + s;
        Tex t = labels.get(key);
        if(t == null) {
            t = f.render(s, c).tex();
            labels.put(key, t);
        }
        return t;
    }

    /** The minimap button's icon, used for the bell and the notify toggle. */
    public static Tex timerIcon() {
        if(timerIcon == null)
            timerIcon = new TexI(Resource.loadsimg("nurgling/hud/buttons/toggle_panel/timer/u"));
        return timerIcon;
    }

    public static Tex restartIcon() {
        if(restartIcon == null)
            restartIcon = new TexI(Resource.loadsimg("nurgling/hud/buttons/reload/u"));
        return restartIcon;
    }

    /** The resource's own minimap icon, or null while it is still loading. */
    public static Tex resourceIcon(String resType) {
        if(resType == null)
            return null;
        synchronized(resIcons) {
            if(resIcons.containsKey(resType))
                return resIcons.get(resType);
        }
        Tex tex;
        try {
            Resource.Image img = Resource.remote().load(resType).get().layer(Resource.imgc);
            tex = (img == null) ? null : img.tex();
        } catch(Loading l) {
            return null;
        }
        synchronized(resIcons) {
            resIcons.put(resType, tex);
        }
        return tex;
    }

    /**
     * The icon for a timer in a list or banner: the resource's map icon, a pin in its colour, or a clock
     * face for a reminder.
     */
    public static void drawKindIcon(GOut g, Timer t, Coord ul, int size) {
        CookbookTheme.fill(g, ul, Coord.of(size, size), new Color(0x4a, 0x3b, 0x28));
        CookbookTheme.frame(g, ul, Coord.of(size, size), new Color(0x5b, 0x4a, 0x33));
        Coord c = ul.add(size / 2, size / 2);
        int r = size / 2 - UI.scale(4);
        if(t.kind == Timer.Kind.RESOURCE) {
            Tex icon = resourceIcon(t.resType);
            if(icon != null) {
                int in = size - UI.scale(4);
                g.image(icon, ul.add(UI.scale(2), UI.scale(2)), Coord.of(in, in));
                return;
            }
            pin(g, c, r, NStyle.border);
        } else if(t.kind == Timer.Kind.PIN) {
            pin(g, c, r, pinColor(t.icon));
        } else {
            clock(g, c, r);
        }
    }

    /** A filled dot with a dark rim, the map marker for a pin. */
    public static void pin(GOut g, Coord c, int r, Color col) {
        g.chcolor(Color.BLACK);
        g.fellipse(c, Coord.of(r + 1, r + 1));
        g.chcolor(col);
        g.fellipse(c, Coord.of(r, r));
        g.chcolor(Color.WHITE);
        int d = Math.max(1, r / 3);
        g.fellipse(c, Coord.of(d, d));
        g.chcolor();
    }

    private static void clock(GOut g, Coord c, int r) {
        g.chcolor(new Color(0xE6, 0xE1, 0xD6));
        g.fellipse(c, Coord.of(r, r));
        g.chcolor(new Color(0x28, 0x34, 0x36));
        g.line(c, c.add(0, -r + UI.scale(2)), UI.scale(1.5));
        g.line(c, c.add(r - UI.scale(3), 0), UI.scale(1.5));
        g.chcolor();
    }

    /** A check mark, for Dismiss. */
    public static void check(GOut g, Coord ul, int size, Color col) {
        g.chcolor(col);
        double w = Math.max(1.5, UI.scale(2.0));
        Coord a = ul.add(size * 2 / 10, size / 2);
        Coord b = ul.add(size * 4 / 10, size * 7 / 10);
        Coord c = ul.add(size * 8 / 10, size * 3 / 10);
        g.line(a, b, w);
        g.line(b, c, w);
        g.chcolor();
    }

    /**
     * A ring that fills clockwise from the top as the timer runs. Drawn as short thick segments: GOut can
     * fill a pie but not cut a hole in one.
     */
    public static void ring(GOut g, Coord c, int r, double progress, Color col) {
        double w = Math.max(2, UI.scale(3.0));
        arc(g, c, r, 0, 1, TRACK, w);
        if(progress > 0)
            arc(g, c, r, 0, progress, col, w);
    }

    private static void arc(GOut g, Coord c, int r, double from, double to, Color col, double w) {
        g.chcolor(col);
        int steps = Math.max(2, (int) Math.ceil((to - from) * 36));
        Coord prev = null;
        for(int i = 0; i <= steps; i++) {
            double f = from + (to - from) * i / steps;
            double a = Math.PI / 2 - f * Math.PI * 2;
            Coord p = Coord.of((int) Math.round(c.x + Math.cos(a) * r), (int) Math.round(c.y - Math.sin(a) * r));
            if(prev != null)
                g.line(prev, p, w);
            prev = p;
        }
        g.chcolor();
    }
}
