package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.conf.FontSettings;
import nurgling.i18n.L10n;
import nurgling.styles.GeneratedButtons;
import nurgling.styles.UITheme;

import java.awt.Color;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Calendar HUD.
 *
 * Compact mode draws the sky/land/glass graphic centered, with the event icons
 * beside it. Verbose mode (NConfig.Key.verboseCal) flanks the graphic with two
 * text columns:
 *
 *   [world time column] [graphic] [server status column]
 *                        [ icons ]
 *
 * The world time column comes from Glob/Astronomy; the status column from the
 * srv-mon player count, the connection's own round-trip time, and the province
 * and realm the server reports for the character's location.
 */
public class NCal extends Cal {
    /* Sizes handed to the hosting NDraggableWidget. Verbose mode needs room for
     * the two text columns; compact mode keeps the original footprint. */
    public static final Coord COMPACT_SZ = UI.scale(400, 90);
    public static final Coord VERBOSE_SZ = UI.scale(650, 130);

    /* Width reserved for the right-aligned time column left of the graphic. The
     * season line ("Summer 47 (58.0 left (17.8 RL))") is the longest thing that
     * has to fit. */
    private static final int TIME_COL_W = UI.scale(230);

    /* Same bundled Open Sans the custom tooltips and window titles use, blurred
     * against black so it stays legible over the map. */
    private static final Text.Furnace fnd = new PUtils.BlurFurn(
        new Text.Foundry(FontSettings.getOpenSansSemibold(), 12, Color.WHITE).aa(true), 2, 1, Color.BLACK);

    /* Square status buttons scale with the rest of the HUD. */
    private static final int ICON_SZ = UI.scale(24);
    private static final int ICON_BORDER = Math.max(1, UI.scale(1));
    private static final int ICON_STEP = UI.scale(28);
    private static final int ICON_COLS = 2;
    private static final int ICON_GAP = UI.scale(2);
    private static final java.util.Map<String, Tex> EVENT_ICONS = new java.util.HashMap<>();
    static {
        for(String key : new String[]{"rain", "wolf", "dawn", "mantle"})
            EVENT_ICONS.put(key, new TexI(GeneratedButtons.squareButtonImage("calendar-" + key, ICON_SZ - 2 * ICON_BORDER)));
    }
    private static final int PAD = UI.scale(8);
    private static final int COLPAD = UI.scale(16);
    private static final int LINE_H = UI.scale(16);
    /* Both text columns are top-aligned so their first lines share a baseline
     * regardless of how many lines each one ends up with. */
    private static final int TOP_PAD = UI.scale(2);
    private static final String UNKNOWN = "?";

    private final CachedLine dayLine = new CachedLine();
    private final CachedLine seasonLine = new CachedLine();
    private final CachedLine moonLine = new CachedLine();
    private final CachedLine playersLine = new CachedLine();
    private final CachedLine pingLine = new CachedLine();
    private final CachedLine provinceLine = new CachedLine();
    private final CachedLine realmLine = new CachedLine();

    private Boolean lastVerbose = null;
    private HttpStatus srvstat = null;
    private boolean loggedNoProvince = false;

    /**
     * Rasterizing seven lines of text every frame is wasteful when most of them
     * change once a minute or never; keep the last texture around and only
     * re-render when the string actually differs.
     */
    private static class CachedLine {
        private String txt = null;
        private Tex tex = null;

        Tex get(String s) {
            if(tex == null || !s.equals(txt)) {
                if(tex != null)
                    tex.dispose();
                txt = s;
                tex = fnd.render(s).tex();
            }
            return tex;
        }
    }

    private static boolean verboseMode() {
        Object v = NConfig.get(NConfig.Key.verboseCal);
        return (v instanceof Boolean) && (Boolean)v;
    }

    /**
     * Center point of the calendar graphic, which doubles as the sun/moon orbit
     * center. Verbose mode sits it between the two text columns and stacks the
     * event icons underneath, so the whole graphic+icons group is what gets
     * centered vertically.
     */
    private Coord imgCenter(boolean verbose) {
        if(!verbose)
            return sz.div(2);
        int top = (sz.y - (bg.sz().y + ICON_GAP + ICON_SZ)) / 2;
        return new Coord(TIME_COL_W + PAD + (bg.sz().x / 2), top + (bg.sz().y / 2));
    }

    @Override
    public boolean checkhit(Coord c) {
        Coord ul = imgCenter(verboseMode()).sub(bg.sz().div(2));
        return eventAt(c) != null || Utils.checkhit(dsky.scaled(), c.sub(ul).sub(dsky.o));
    }

    @Override
    public void draw(GOut g) {
        Astronomy a = ui.sess.glob.ast;
        if(a == null)
            return;
        boolean verbose = verboseMode();
        Coord ic = imgCenter(verbose);
        int mp = (int)Math.round(a.mp * (double)moon.f.length) % moon.f.length;

        drawGraphic(g, a, ic, mp);
        if(verbose) {
            drawEvents(g, true);
            drawTimeColumn(g, a, mp);
            drawStatusColumn(g, ic.x + (bg.sz().x / 2) + COLPAD);
        } else {
            drawEvents(g, false);
        }
    }

    private void drawGraphic(GOut g, Astronomy a, Coord ic, int mp) {
        long now = System.currentTimeMillis();
        Coord ul = ic.sub(bg.sz().div(2));
        g.image(a.night ? nsky : dsky, ul);
        Resource.Image mimg = Cal.moon.f[mp][0];
        Resource.Image simg = Cal.sun.f[(int)((now / Cal.sun.d) % Cal.sun.f.length)][0];
        g.chcolor(a.mc);
        g.image(mimg, Coord.sc((a.dt + 0.25) * 2 * Math.PI, hbr).add(ic).sub(mimg.ssz.div(2)));
        g.chcolor();
        g.image(simg, Coord.sc((a.dt + 0.75) * 2 * Math.PI, hbr).add(ic).sub(simg.ssz.div(2)));
        g.image((a.night ? nlnd : dlnd)[a.is], ul);
        g.image(bg, ul);
    }

    /** Shared coordinates keep the drawn squares and their tooltip hit areas aligned. */
    private Coord eventPosition(int index, boolean verbose) {
        Coord ic = imgCenter(verbose);
        if(verbose) {
            int width = (eventNames.size() - 1) * ICON_STEP + ICON_SZ;
            return new Coord(ic.x - width / 2 + index * ICON_STEP,
                             ic.y + bg.sz().y / 2 + ICON_GAP);
        }
        return new Coord(ic.x + bg.sz().x / 2 - ICON_SZ + (index % ICON_COLS) * ICON_STEP,
                         ic.y - ICON_SZ - ICON_GAP + (index / ICON_COLS) * ICON_STEP);
    }

    private void drawEvents(GOut g, boolean verbose) {
        for(int i = 0; i < eventNames.size(); i++) {
            Coord pos = eventPosition(i, verbose);
            g.image(EVENT_ICONS.get(eventNames.get(i)), pos.add(ICON_BORDER, ICON_BORDER));
            UITheme.panel(g, pos, new Coord(ICON_SZ, ICON_SZ), null, UITheme.ACCENT);
        }
    }

    private String eventAt(Coord c) {
        boolean verbose = verboseMode();
        for(int i = 0; i < eventNames.size(); i++) {
            if(c.isect(eventPosition(i, verbose), new Coord(ICON_SZ, ICON_SZ)))
                return eventNames.get(i);
        }
        return null;
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        String event = eventAt(c);
        if(event != null)
            return L10n.get("calendar.event." + event);
        return super.tooltip(c, prev);
    }
    /** World time, right-aligned so it reads as pointing at the calendar beside it. */
    private void drawTimeColumn(GOut g, Astronomy a, int mp) {
        int y = TOP_PAD;
        g.aimage(dayLine.get(dayTime()), new Coord(TIME_COL_W, y), 1, 0);
        g.aimage(seasonLine.get(seasonText(a)), new Coord(TIME_COL_W, y + LINE_H), 1, 0);
        g.aimage(moonLine.get(Astronomy.phase[mp]), new Coord(TIME_COL_W, y + (LINE_H * 2)), 1, 0);
    }

    private void drawStatusColumn(GOut g, int x) {
        int y = TOP_PAD;
        g.image(playersLine.get(playersText()), new Coord(x, y));
        g.image(pingLine.get(pingText()), new Coord(x, y + LINE_H));
        g.image(provinceLine.get(provinceText()), new Coord(x, y + (LINE_H * 2)));
        g.image(realmLine.get(realmText()), new Coord(x, y + (LINE_H * 3)));
    }

    private String provinceText() {
        String v = (ui instanceof NUI) ? ((NUI)ui).province : null;
        if(v == null && !loggedNoProvince) {
            loggedNoProvince = true;
            System.out.println("[NCal] no province on ui@"
                               + Integer.toHexString(System.identityHashCode(ui))
                               + " (" + ui.getClass().getSimpleName() + ")");
        }
        return String.format(L10n.get("serverinfo.province"), (v == null) ? "-" : v);
    }

    private String realmText() {
        String v = (ui instanceof NUI) ? ((NUI)ui).realm : null;
        return String.format(L10n.get("serverinfo.realm"), (v == null) ? "-" : v);
    }


    /* ---- world time ---- */

    private String dayTime() {
        long s = (long)ui.sess.glob.globtime();
        return String.format(L10n.get("calendar.day_time"),
                             s / 86400, (s % 86400) / 3600, (s % 3600) / 60, s % 60);
    }

    private String seasonText(Astronomy a) {
        /* srday/srhh/srmm are the game time left in the season, which is what
         * Astronomy derives from the season length and its progress. */
        double left = a.srday + (a.srhh / 24.0) + (a.srmm / 1440.0);
        if(left < 1.0)
            return String.format(L10n.get("calendar.last_day"), a.season());
        double rl = Math.max(left / NGameUI.worldSpeed, 0.1);
        return String.format(L10n.get("calendar.season_line"), a.season(), a.scday + 1, left, rl);
    }

    /* ---- server status ---- */

    private String playersText() {
        String v = UNKNOWN;
        HttpStatus stat = srvstat;
        if(stat != null) {
            synchronized(stat) {
                if(stat.syn && "up".equals(stat.status))
                    v = Integer.toString(stat.users);
            }
        }
        return String.format(L10n.get("serverinfo.players"), v);
    }

    private String pingText() {
        String v = UNKNOWN;
        Session sess = ui.sess;
        if(sess != null && sess.conn instanceof Connection) {
            Connection.Stats stats = ((Connection)sess.conn).stats;
            if(stats.hasrtt())
                v = Integer.toString((int)Math.round(stats.srtt() * 1000));
        }
        return String.format(L10n.get("serverinfo.ping"), v);
    }

    /* ---- lifecycle ---- */

    @Override
    public void tick(double dt) {
        super.tick(dt);
        boolean verbose = verboseMode();
        if(lastVerbose == null || lastVerbose != verbose) {
            lastVerbose = verbose;
            if(parent instanceof NDraggableWidget)
                parent.resize(verbose ? VERBOSE_SZ : COMPACT_SZ);
        }
        /* Only start polling the server monitor once someone actually wants to
         * look at the numbers. */
        if(verbose && srvstat == null)
            startSrvStat();
    }

    private void startSrvStat() {
        HttpStatus stat;
        try {
            stat = new HttpStatus(new URI("http", Bootstrap.authserv.get().host, "/mt/srv-mon", null));
        } catch(URISyntaxException e) {
            System.out.println("[NCal] could not build srv-mon URI: " + e.getMessage());
            return;
        }
        srvstat = stat;
        stat.start();
    }

    @Override
    public void dispose() {
        HttpStatus stat = srvstat;
        if(stat != null) {
            srvstat = null;
            stat.quit();
        }
        super.dispose();
    }
}
