package nurgling.overlays.map;

import haven.*;
import haven.render.*;
import nurgling.NConfig;
import nurgling.leveling.LevelPlan;
import nurgling.leveling.LevelPlans;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The border of every leveling plan, drawn on the ground in the world: a thin orange line along
 * the plan's outer edge, laid on the terrain mesh like an area's outline. No fill, so the ground
 * itself stays readable; where to dig and where to fill is the map's job.
 *
 * <p>One per map view. Shown while {@link NConfig.Key#levelPlanWorldBorder} is on.
 */
public class NLevelPlanOverlay extends NOverlay {
    public static final int LEVEL_PLAN_OVERLAY = -3;
    private static final Color LINE = new Color(224, 160, 64, 220);
    /** Seconds between rebuilds while a plan is being painted. */
    private static final double MINREBUILD = 0.3;

    private final LevelPlans plans;
    private long seenRev = -1;
    private boolean seenOn;
    private boolean pending = false;
    private double lastRebuild = 0;

    public NLevelPlanOverlay(MapView mv, LevelPlans plans) {
        super(LEVEL_PLAN_OVERLAY, mv);
        this.plans = plans;
        this.seenOn = shown();
    }

    private static boolean shown() {
        return(!Boolean.FALSE.equals(NConfig.get(NConfig.Key.levelPlanWorldBorder)));
    }

    @Override
    public void tick() {
        super.tick();
        boolean on = shown();
        long rev = plans.revision();
        if((on != seenOn) || (on && (rev != seenRev)))
            pending = true;
        double now = Utils.rtime();
        /* Painting changes the plan on every mouse move; rebuilding the cuts that often only flickers. */
        if(pending && ((now - lastRebuild) >= MINREBUILD)) {
            pending = false;
            lastRebuild = now;
            seenOn = on;
            seenRev = rev;
            requpdate2 = true;
        }
    }

    @Override
    public RenderTree.Node makenol(MapMesh mm, Long grid_id, Coord grid_ul) {
        return(null);
    }

    @Override
    public RenderTree.Node makenolol(MapMesh mm, Long grid_id, Coord grid_ul) {
        if(!seenOn || !plans.touches(grid_id))
            return(null);
        List<LevelPlan> snap = plans.plans();
        /* Grid ids around this cut, for neighbour tiles across a grid edge; unknown = not loaded. */
        Map<Coord, Long> ids = new HashMap<>();
        Coord g0 = grid_ul.div(MCache.cmaps);
        synchronized(mm.map.grids) {
            for(int y = -1; y <= 1; y++) {
                for(int x = -1; x <= 1; x++) {
                    MCache.Grid g = mm.map.grids.get(g0.add(x, y));
                    if(g != null)
                        ids.put(g.gc, g.id);
                }
            }
        }
        mm.olvert();
        class Buf implements Tiler.MCons {
            int mask;
            short[] fl = new short[16];
            int fn = 0;

            public void faces(MapMesh m, Tiler.MPart d) {
                byte[] ef = new byte[d.v.length];
                for(int i = 0; i < d.v.length; i++) {
                    if(d.tcy[i] == 0.0f) ef[i] |= 1;
                    if(d.tcx[i] == 1.0f) ef[i] |= 2;
                    if(d.tcy[i] == 1.0f) ef[i] |= 4;
                    if(d.tcx[i] == 0.0f) ef[i] |= 8;
                }
                while(fn + (d.f.length * 2) > fl.length)
                    fl = Utils.extend(fl, fl.length * 2);
                for(int i = 0; i < d.f.length; i += 3) {
                    for(int a = 0; a < 3; a++) {
                        int b = (a + 1) % 3;
                        if((ef[d.f[i + a]] & ef[d.f[i + b]] & mask) != 0) {
                            fl[fn++] = (short)mm.olvert.vl[d.v[d.f[i + a]].vi];
                            fl[fn++] = (short)mm.olvert.vl[d.v[d.f[i + b]].vi];
                        }
                    }
                }
            }
        }
        Area a = Area.sized(mm.ul, mm.sz);
        Buf buf = new Buf();
        for(Coord t : a) {
            int own = planOf(snap, ids, t);
            if(own < 0)
                continue;
            buf.mask = 0;
            for(int d = 0; d < 4; d++) {
                int n = planOf(snap, ids, t.add(Coord.uecw[d]));
                /* A neighbour in a grid that is not loaded draws no edge rather than a false one. */
                if((n != own) && (n != UNKNOWN))
                    buf.mask |= 1 << d;
            }
            if(buf.mask != 0) {
                Tiler tl = mm.map.tiler(mm.map.gettile(t));
                if(tl != null)
                    tl.lay(mm, t.sub(a.ul), t, buf, false);
            }
        }
        if(buf.fn == 0)
            return(null);
        haven.render.Model mod = new haven.render.Model(haven.render.Model.Mode.LINES, mm.olvert.dat,
                new haven.render.Model.Indices(buf.fn, NumberFormat.UINT16, DataBuffer.Usage.STATIC,
                        DataBuffer.Filler.of(Arrays.copyOf(buf.fl, buf.fn))));
        return(new MapMesh.ShallowWrap(mod, Pipe.Op.compose(new MapMesh.NOLOrder(id), new States.LineWidth(2))));
    }

    private static final int UNKNOWN = -2;

    /** Index of the plan holding a session tile, -1 if none, {@link #UNKNOWN} if its grid is not loaded. */
    private static int planOf(List<LevelPlan> plans, Map<Coord, Long> ids, Coord tc) {
        Coord gc = tc.div(MCache.cmaps);
        Long gid = ids.get(gc);
        if(gid == null)
            return(UNKNOWN);
        int lx = tc.x - (gc.x * MCache.cmaps.x), ly = tc.y - (gc.y * MCache.cmaps.y);
        for(int i = 0; i < plans.size(); i++) {
            if(plans.get(i).contains(gid, lx, ly))
                return(i);
        }
        return(-1);
    }

    @Override
    public void added(RenderTree.Slot slot) {
        this.slot = slot;
        slot.add(outl, Pipe.Op.compose(new BaseColor(LINE), Clickable.No));
    }
}
