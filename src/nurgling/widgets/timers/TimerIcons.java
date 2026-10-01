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
    /** Small enough that the badge sits on the icon's corner instead of covering it. */
    private static final Text.Foundry BADGE_FONT =
        new Text.Foundry(nurgling.conf.FontSettings.getOpenSansSemibold(), 9, Color.WHITE).aa(true);

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

    /* Map and list icons, all from resources/src/nurgling/hud/icons/timers. The ring is a dark track plus
     * one of RING_FRAMES white arcs, tinted at draw time; the pin is white and tinted too. */
    private static final int RING_FRAMES = 24;
    /** Height of the pin's head centre as a share of its image size (24 of 64 px). */
    private static final double PIN_HEAD = 24.0 / 64;
    private static Tex ringTrack, pinTex, checkTex, reminderTex, badgeTex, dotTex, taskTex;
    private static final Tex[] ringFill = new Tex[RING_FRAMES];
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

    /** The minimap button's icon, reused for the per-timer notify toggle in the panel. */
    public static Tex timerIcon() {
        if(timerIcon == null)
            timerIcon = new TexI(Resource.loadsimg("nurgling/hud/buttons/toggle_panel/timer/u"));
        return timerIcon;
    }

    private static Tex icon(String name) {
        return new TexI(Resource.loadsimg("nurgling/hud/icons/timers/" + name));
    }

    public static Tex restartIcon() {
        if(restartIcon == null)
            restartIcon = icon("restart");
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
        } catch(Resource.NoSuchResourceException e) {
            // Not a Loading: left uncaught it would take down the UI thread. Fall back to a pin for good.
            tex = null;
        }
        synchronized(resIcons) {
            resIcons.put(resType, tex);
        }
        return tex;
    }

    /**
     * The icon for a timer in a list or banner: the resource's map icon, a pin in its colour, a clock face
     * for a reminder, or the task icon for a To-Do deadline.
     */
    public static void drawKindIcon(GOut g, Timer t, Coord ul, int size) {
        CookbookTheme.fill(g, ul, Coord.of(size, size), new Color(0x4a, 0x3b, 0x28));
        CookbookTheme.frame(g, ul, Coord.of(size, size), new Color(0x5b, 0x4a, 0x33));
        int in = size - UI.scale(4);
        Coord iul = ul.add(UI.scale(2), UI.scale(2));
        if(t.kind == Timer.Kind.RESOURCE) {
            Tex icon = resourceIcon(t.resType);
            if(icon != null) {
                g.image(icon, iul, Coord.of(in, in));
                return;
            }
            pin(g, ul.add(size / 2, size / 2), in, NStyle.border);
        } else if(t.kind == Timer.Kind.PIN) {
            pin(g, ul.add(size / 2, size / 2), in, pinColor(t.icon));
        } else if(t.kind == Timer.Kind.TASK) {
            drawTaskIcon(g, iul, in);
        } else {
            if(reminderTex == null)
                reminderTex = icon("reminder");
            g.image(reminderTex, iul, Coord.of(in, in));
        }
    }

    /** The To-Do task icon, for task deadlines and task news. */
    public static void drawTaskIcon(GOut g, Coord ul, int size) {
        if(taskTex == null)
            taskTex = icon("task");
        g.image(taskTex, ul, Coord.of(size, size));
    }

    /**
     * A phone-style notification badge: a white number on a red disc, its top-right corner at the given
     * point. Two or more digits stretch the disc sideways.
     */
    public static void badge(GOut g, Coord topRight, int count) {
        if(badgeTex == null)
            badgeTex = icon("badge");
        Tex num = text(BADGE_FONT, (count > 99) ? "99+" : String.valueOf(count), Color.WHITE);
        int h = UI.scale(12);
        int w = Math.max(h, num.sz().x + UI.scale(5));
        Coord ul = topRight.sub(w, 0);
        g.image(badgeTex, ul, Coord.of(w, h));
        g.image(num, ul.add((w - num.sz().x) / 2, (h - num.sz().y) / 2));
    }

    /** A map pin in the given colour, its head centred on {@code c}, {@code size} px tall. */
    public static void pin(GOut g, Coord c, int size, Color col) {
        if(pinTex == null)
            pinTex = icon("pin");
        g.chcolor(col);
        g.image(pinTex, c.sub(size / 2, (int) Math.round(size * PIN_HEAD)), Coord.of(size, size));
        g.chcolor();
    }

    /** A small dot in the given colour, centred on {@code c}; marks a session tab with waiting timers. */
    public static void dot(GOut g, Coord c, int size, Color col) {
        if(dotTex == null)
            dotTex = icon("dot");
        g.chcolor(col);
        g.image(dotTex, c.sub(size / 2, size / 2), Coord.of(size, size));
        g.chcolor();
    }

    /** The green check mark, for Dismiss. */
    public static void check(GOut g, Coord ul, int size) {
        if(checkTex == null)
            checkTex = icon("check");
        g.image(checkTex, ul, Coord.of(size, size));
    }

    /** A progress ring centred on {@code c}, {@code size} px across, filled clockwise from the top. */
    public static void ring(GOut g, Coord c, int size, double progress, Color col) {
        if(ringTrack == null)
            ringTrack = icon("ring_track");
        Coord ul = c.sub(size / 2, size / 2);
        Coord sz = Coord.of(size, size);
        g.image(ringTrack, ul, sz);
        int frame = Math.min(RING_FRAMES, (int) Math.ceil(progress * RING_FRAMES));
        if(frame <= 0)
            return;
        if(ringFill[frame - 1] == null)
            ringFill[frame - 1] = icon(String.format("ring_fill/%02d", frame));
        g.chcolor(col);
        g.image(ringFill[frame - 1], ul, sz);
        g.chcolor();
    }
}
