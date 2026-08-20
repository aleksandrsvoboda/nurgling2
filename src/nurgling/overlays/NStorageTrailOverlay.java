package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.NConfig;
import nurgling.NMapView;
import nurgling.navigation.StorageTrailService;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays the route to a searched-for container on the ground.
 *
 * Search the inventory for "blueberry" and every container the database says holds one
 * becomes a target; this draws the way there, routed by ChunkNav rather than aimed
 * straight through whatever is in between. The ribbon stops where the client stops
 * knowing the terrain - at a door, or at the edge of what is loaded - and the terminus
 * ring marks the thing to head for. Walk through, and it redraws on the other side.
 *
 * All the geometry lives in {@link NGroundPathOverlay}; the routing lives in
 * {@link StorageTrailService}. This class is the join between them.
 */
public class NStorageTrailOverlay extends NGroundPathOverlay implements PView.Render2D {
    private static final double STEM_H = 6.0;
    /** Terminus ring, drawn a little wider than a movement waypoint so the two don't read alike. */
    private static final double TERM_IN = 5.2, TERM_OUT = 7.4;

    private static final Text.Foundry labelf = new Text.Foundry(Text.dfont, 11).aa(true);

    private final StorageTrailService service;
    private long lastSig = Long.MIN_VALUE;
    private double lastBuild = 0;

    private final Map<String, Text> labels = new HashMap<>();

    public NStorageTrailOverlay(NMapView mv, StorageTrailService service) {
        super(mv);
        this.service = service;
    }

    public static Color trailColor() {
        return(NConfig.getColor(NConfig.Key.storageTrailColor, new Color(126, 232, 143)));
    }

    /** Trails past the first are dimmed, so the nearest one still reads as the answer. */
    private static double alphaFor(int idx) {
        return(idx == 0 ? 0.95 : 0.45);
    }

    /* ------------------------------------------------------------------ *
     *  Geometry
     * ------------------------------------------------------------------ */

    private long signature(List<StorageTrailService.Trail> trails) {
        long h = 1125899906842597L;
        for(StorageTrailService.Trail t : trails) {
            h = h * 31 + t.containerHash.hashCode();
            h = h * 31 + t.points.size();
            for(Coord2d p : t.points) {
                h = h * 31 + (long)p.x;
                h = h * 31 + (long)p.y;
            }
        }
        h = h * 31 + trailColor().getRGB();
        // Toggling flat world changes every vertex, so it has to force a rebuild.
        h = h * 31 + (flat ? 1 : 0);
        return(h);
    }

    /**
     * Rebuild when the route actually changed. The first point of every trail is the
     * player, so this signature moves as they walk - hence the throttle, which keeps the
     * terrain sampling off the per-frame path.
     */
    public void update() {
        updateFlat();
        List<StorageTrailService.Trail> trails = service.trails();
        if(trails.isEmpty()) {
            if(lastSig != Long.MIN_VALUE) {
                clearGeometry();
                lastSig = Long.MIN_VALUE;
            }
            return;
        }

        long sig = signature(trails);
        double now = Utils.rtime();
        if((sig == lastSig) || (now - lastBuild < 0.2))
            return;

        double baseZ;
        try {
            baseZ = baseZ();
        } catch(Loading l) {
            return;
        }

        Color col = trailColor();
        Buf buf = new Buf();
        for(int i = 0; i < trails.size(); i++) {
            StorageTrailService.Trail t = trails.get(i);
            double a = alphaFor(i);
            float[] core = rgba(col, a);
            List<Coord2d> pts = t.points;
            for(int k = 1; k < pts.size(); k++)
                ribbon(buf, pts.get(k - 1), pts.get(k), core, baseZ);
            ring(buf, t.terminus, rgba(col, a), rgba(col, a * 0.2), baseZ, TERM_IN, TERM_OUT);
        }

        setGeometry(buf);
        lastSig = sig;
        lastBuild = now;
    }

    /* ------------------------------------------------------------------ *
     *  2D pass: terminus label and off-screen arrow
     * ------------------------------------------------------------------ */

    public void draw(GOut g, Pipe state) {
        // The 2D pass runs on the UI thread; a Loading escaping here would take the whole
        // frame down, so anything not yet paged in just skips a frame.
        try {
            draw2d(g, state);
        } catch(Loading ignored) {
        }
    }

    private void draw2d(GOut g, Pipe state) {
        updateFlat();
        List<StorageTrailService.Trail> trails = service.trails();
        if(trails.isEmpty())
            return;
        Area va = Area.sized(g.sz());
        double baseZ;
        try {
            baseZ = baseZ();
        } catch(Loading l) {
            return;
        }

        Color col = trailColor();
        for(int i = 0; i < trails.size(); i++) {
            StorageTrailService.Trail t = trails.get(i);
            Coord2d wc = t.terminus;
            double z = cz(wc.x, wc.y, baseZ);
            Coord sc = proj(state, va, wc, z + Z_RING);
            Coord top = proj(state, va, wc, z + STEM_H);

            boolean out = (sc == null) || (top == null) ||
                    (!sc.isect(Coord.z, g.sz()) && !top.isect(Coord.z, g.sz()));
            if(out) {
                // Only the nearest trail gets an edge arrow; several at once would ring the
                // viewport with markers that all mean roughly "over there".
                if(i == 0) {
                    Coord head = edgeArrow(g, wc, col);
                    if(head != null)
                        plate(g, head, label(t), col);
                }
                continue;
            }

            if(i == 0)
                pulse(g, state, va, wc, z, col);

            g.chcolor(0, 0, 0, 160);
            g.line(sc, top, 3);
            g.chcolor(col);
            g.line(sc, top, 1);
            plate(g, top, label(t), col);
        }
        g.chcolor();
    }

    /**
     * What the terminus is. A trail that stopped at a door is not an arrival, and saying so
     * is the difference between "you're there" and "keep going through here".
     */
    private String label(StorageTrailService.Trail t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.count);
        if(t.maxQuality > 0)
            sb.append(" · Q").append(Utils.odformat2(t.maxQuality, 1));
        if(!t.atContainer)
            sb.append(" ▸");
        return(sb.toString());
    }

    private void plate(GOut g, Coord c, String text, Color col) {
        Text cached = labels.get(text);
        if(cached == null) {
            // Labels are "count · quality", so the set of distinct strings is small and
            // bounded by what the current search matched; clearing it on a miss keeps a long
            // session from accumulating textures for queries that are long gone.
            if(labels.size() > 16) {
                for(Text old : labels.values())
                    old.dispose();
                labels.clear();
            }
            cached = labelf.render(text, new Color(225, 245, 230));
            labels.put(text, cached);
        }
        Tex t = cached.tex();
        Coord psz = t.sz().add(UI.scale(10), UI.scale(4));
        Coord ul = c.sub(psz.div(2));
        g.chcolor(12, 16, 18, 215);
        g.frect(ul, psz);
        g.chcolor(col);
        g.rect(ul, psz);
        g.aimage(t, c, 0.5, 0.5);
        g.chcolor();
    }

    /** Slow expanding ring on the nearest terminus, so a static trail still draws the eye. */
    private void pulse(GOut g, Pipe state, Area va, Coord2d wc, double z, Color col) {
        double period = 1.6;
        double t = (Utils.rtime() % period) / period;
        double r = TERM_OUT + (t * 11.0);
        int a = (int)(130 * (1 - t));
        if(a < 8)
            return;
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), a);
        circle(g, state, va, wc, r, z + Z_RING, 2, 1);
        g.chcolor();
    }
}
