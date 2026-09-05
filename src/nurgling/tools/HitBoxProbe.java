package nurgling.tools;

import haven.*;
import haven.res.gfx.terobjs.consobj.Consobj;
import nurgling.NHitBox;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.pf.CellsArray;
import nurgling.pf.PolyRects;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prints the collision geometry the server actually sent for an object.
 *
 * <p>None of the game's own resources live in this repo, so the only way to learn what a footprint
 * really looks like is to read it off a live session. That matters because
 * {@link NHitBox#isCompoundName} is a hardcoded opt-in list: before a resource goes on it, someone
 * has to confirm that its obstacle data genuinely describes several rectangles with a walkable gap,
 * and that the gap survives being rasterized onto the 5.5-unit pathfinding grid.
 *
 * <p>What comes out, per resource:
 * <ul>
 *   <li>every {@code neg} and {@code obst} layer, including the obstacle layer's id - a resource can
 *       carry more than one, and they need not mean the same thing;</li>
 *   <li>the rectangles {@link PolyRects} would decompose that geometry into, whether or not the name
 *       is opted in - so you can see what you would get before committing to it;</li>
 *   <li>the {@link NHitBox} actually in use, and the pathfinding cells it occupies, drawn as text.
 *       If the middle column of that drawing is solid, the gap did not survive.</li>
 * </ul>
 *
 * <p>Hooked into debug inspect (once per resource per session), and available as the {@code hbdump}
 * and {@code hbsweep} console commands on the map view.
 */
public class HitBoxProbe {
    private static final Set<String> seen = ConcurrentHashMap.newKeySet();

    /** Dump this gob's geometry the first time its resource is encountered this session. */
    public static void dumpOnce(Gob gob) {
        if (gob == null || gob.ngob == null || gob.ngob.name == null)
            return;
        if (!seen.add(gob.ngob.name))
            return;
        dump(gob);
    }

    /** Dump every distinct resource currently loaded in the object cache. */
    public static void sweep() {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null)
            return;
        List<Gob> gobs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (gob.ngob != null && gob.ngob.name != null && names.add(gob.ngob.name))
                    gobs.add(gob);
            }
        }
        System.out.println("=== HITBOX SWEEP: " + gobs.size() + " distinct resources in view");
        for (Gob gob : gobs)
            dump(gob);
        System.out.println("=== HITBOX SWEEP end");
    }

    /** Dump every distinct loaded resource whose name contains {@code match}. */
    public static void sweep(String match) {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null)
            return;
        List<Gob> gobs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (gob.ngob != null && gob.ngob.name != null && gob.ngob.name.contains(match)
                        && names.add(gob.ngob.name))
                    gobs.add(gob);
            }
        }
        if (gobs.isEmpty()) {
            System.out.println("=== HITBOX: nothing loaded matching \"" + match + "\"");
            return;
        }
        for (Gob gob : gobs)
            dump(gob);
    }

    public static void dump(Gob gob) {
        if (gob == null || gob.ngob == null)
            return;
        StringBuilder sb = new StringBuilder();
        String name = gob.ngob.name;
        sb.append("=== HITBOX ").append(name).append("  id=").append(gob.id)
                .append(" a=").append(String.format("%.3f", gob.a));
        if (NHitBox.isCompoundName(name))
            sb.append("  [opted in as compound]");
        sb.append('\n');

        Resource res = geomRes(gob);
        if (res == null) {
            sb.append("  (no drawable resource yet - hover again once it has loaded)\n");
        } else {
            if (!res.name.equals(name))
                sb.append("  geometry from built resource: ").append(res.name).append('\n');
            appendLayers(sb, res);
        }

        NHitBox hb = gob.ngob.hitBox;
        sb.append("  in use: ").append(hb == null ? "null (object is invisible to pathfinding)" : hb.toString()).append('\n');
        if (hb != null)
            appendRaster(sb, hb, gob.a, gob.rc);
        System.out.print(sb);
    }

    private static void appendLayers(StringBuilder sb, Resource res) {
        Collection<Resource.Layer> layers = res.getLayers();
        if (layers == null) {
            sb.append("  (resource has no layers)\n");
            return;
        }
        int negs = 0, obsts = 0;
        for (Resource.Layer lay : layers) {
            if (lay instanceof Resource.Neg) {
                negs++;
                Resource.Neg neg = (Resource.Neg) lay;
                sb.append("  neg   ac=").append(neg.ac).append(" bc=").append(neg.bc).append('\n');
            } else if (lay instanceof Resource.Obstacle) {
                obsts++;
                Resource.Obstacle obst = (Resource.Obstacle) lay;
                sb.append("  obst  id=\"").append(obst.id).append("\" polys=").append(obst.p.length).append('\n');
                for (int i = 0; i < obst.p.length; i++) {
                    Coord2d[] loop = obst.p[i];
                    sb.append("    [").append(i).append("] ").append(loop == null ? 0 : loop.length).append("v");
                    if (loop != null) {
                        sb.append(PolyRects.isAxisRect(loop) ? " axis-rect " : " free-form ");
                        for (Coord2d v : loop)
                            sb.append(String.format("(%.2f,%.2f) ", v.x, v.y));
                    }
                    sb.append('\n');
                }
                List<Coord2d[]> rects = PolyRects.decompose(obst.p);
                sb.append("    -> decomposes to ").append(rects.size()).append(" rect(s)");
                if (rects.size() > 1)
                    sb.append("  <== MULTI-PART CANDIDATE");
                sb.append('\n');
                for (Coord2d[] r : rects)
                    sb.append(String.format("       (%.2f,%.2f)-(%.2f,%.2f)%n", r[0].x, r[0].y, r[1].x, r[1].y));
            }
        }
        if (negs == 0 && obsts == 0)
            sb.append("  (no neg or obst layer - hitbox can only come from NHitBox.custom)\n");
        else if (obsts > 1)
            sb.append("  NOTE: ").append(obsts).append(" obstacle layers; NGob's loop lets the last one win.\n");
    }

    /**
     * Draw the footprint as pathfinding cells. '#' is blocked, '.' is free, '+' marks the object's
     * own centre. A compound footprint that worked shows free cells between the blocked ones.
     */
    private static void appendRaster(StringBuilder sb, NHitBox hb, double angle, Coord2d rc) {
        CellsArray ca = new CellsArray(hb, angle, rc);
        Coord centre = nurgling.pf.Utils.toPfGrid(rc);
        sb.append("  cells (").append(ca.x_len).append("x").append(ca.y_len)
                .append(" at ").append(MCache.tilehsz.x).append("/cell, x rightward, y downward):\n");
        for (int j = 0; j < ca.y_len; j++) {
            sb.append("    ");
            for (int i = 0; i < ca.x_len; i++) {
                boolean isCentre = (ca.begin.x + i == centre.x) && (ca.begin.y + j == centre.y);
                sb.append(ca.cells[i][j] != 0 ? '#' : (isCentre ? '+' : '.'));
            }
            sb.append('\n');
        }
    }

    private static Resource geomRes(Gob gob) {
        Drawable d = gob.getattr(Drawable.class);
        if (d == null)
            return null;
        try {
            if (d instanceof ResDrawable && ((ResDrawable) d).spr instanceof Consobj) {
                Consobj co = (Consobj) ((ResDrawable) d).spr;
                if (co.built != null && co.built.res instanceof Session.CachedRes.Ref) {
                    Resource built = ((Session.CachedRes.Ref) co.built.res).res;
                    if (built != null)
                        return built;
                }
            }
            return d.getres();
        } catch (Loading l) {
            return null;
        }
    }
}
