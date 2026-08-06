package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.i18n.L10n;

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

    private static final Text.Furnace fnd =
        new PUtils.BlurFurn(new Text.Foundry(Text.sans, 12, Color.WHITE).aa(true), 2, 1, Color.BLACK);

    /* The event icons are plain 32x32 TexI, not UI-scaled resources, so their
     * grid spacing is in raw pixels the way Cal has always drawn them. */
    private static final int ICON_SZ = 32;
    private static final int ICON_STEP = 30;
    private static final int ICON_COLS = 2;
    private static final int ICON_GAP = UI.scale(2);
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
        return Utils.checkhit(dsky.scaled(), c.sub(ul).sub(dsky.o));
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
            drawIconRow(g, ic);
            drawTimeColumn(g, a, mp);
            drawStatusColumn(g, ic.x + (bg.sz().x / 2) + COLPAD);
        } else {
            drawIconGrid(g, ic);
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

    /** Compact mode: 2-column event icon grid beside the graphic. */
    private void drawIconGrid(GOut g, Coord ic) {
        int x0 = ic.x + (bg.sz().x / 2) + PAD;
        int y0 = sz.y / 2 - UI.scale(10);
        int i = 0;
        for(String key : eventNames) {
            TexI icon = events.get(key);
            if(icon == null)
                continue;
            g.aimage(icon, new Coord(x0 + ((i % ICON_COLS) * ICON_STEP), y0 + ((i / ICON_COLS) * ICON_STEP)), 0.5, 0.5);
            i++;
        }
    }

    /** Verbose mode: single centered row of event icons under the graphic, so
     *  they don't push the status column away from the calendar. */
    private void drawIconRow(GOut g, Coord ic) {
        int n = 0;
        for(String key : eventNames) {
            if(events.get(key) != null)
                n++;
        }
        if(n == 0)
            return;
        int y = ic.y + (bg.sz().y / 2) + ICON_GAP + (ICON_SZ / 2);
        int x = ic.x - (((n - 1) * ICON_STEP) / 2);
        for(String key : eventNames) {
            TexI icon = events.get(key);
            if(icon == null)
                continue;
            g.aimage(icon, new Coord(x, y), 0.5, 0.5);
            x += ICON_STEP;
        }
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
