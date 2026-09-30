package nurgling.overlays.map;

import haven.*;
import haven.render.*;
import nurgling.*;
import static nurgling.NMapView.MINING_OVERLAY;
import nurgling.areas.*;
import nurgling.overlays.*;
import nurgling.tools.*;

import java.awt.*;
import java.util.*;

public class NMiningOverlay extends NOverlay
{
    public Gob dummy = null;
    Coord2d oldDummy = null;
    double oldDummyA = Double.NaN;
    final ArrayList<Long> curGobs = new ArrayList<>();

    public NMiningOverlay()
    {
        super(MINING_OVERLAY);
        bc = new Color(200, 200, 200, 100);
    }

    public void addDummySupp(Gob gob)
    {
        synchronized (curGobs)
        {
            curGobs.add(gob.id);
            synchronized (forAdd)
            {
                forAdd.add(gob.id);
            }
            dummy = gob;
        }
    }

    public void addMineSupp(Long gob)
    {
        synchronized (curGobs)
        {
            curGobs.add(gob);
            synchronized (forAdd)
            {
                forAdd.add(gob);
            }
        }
    }

    final ArrayList<Long> forClear = new ArrayList<>();
    final ArrayList<Long> forAdd = new ArrayList<>();

    @Override
    public void tick()
    {
        ArrayList<Long> snapshot;
        synchronized (curGobs)
        {
            snapshot = new ArrayList<>(curGobs);
        }
        ArrayList<Long> dead = new ArrayList<>();
        for (Long id : snapshot)
        {
            if (Finder.findGob(id) == null)
            {
                dead.add(id);
            }
        }
        synchronized (forClear)
        {
            forClear.clear();
            forClear.addAll(dead);
        }
        if (!dead.isEmpty())
        {
            synchronized (curGobs)
            {
                curGobs.removeAll(dead);
            }
        }

        requpdate2 = requpdate();

        super.tick();
    }

    /* The support coverage over one map section (padded by a tile on
     * each side), built for each section on its own: sections are
     * built on separate worker threads, so a mask kept on the overlay
     * could be replaced by another section's between makenol and
     * makenolol. */
    private boolean[][] maskForCut(MapMesh mm)
    {
        boolean[][] buf2 = new boolean[mm.sz.x + 2][mm.sz.y + 2];
        if (!Boolean.TRUE.equals(NConfig.get(NConfig.Key.miningol)))
            return buf2;
        ArrayList<Long> snapshot;
        synchronized (curGobs) {
            snapshot = new ArrayList<>(curGobs);
        }
        for (Long id : snapshot) {
            Gob g = Finder.findGob(id);
            if (g == null)
                continue;
            NMiningSupport nms = null;
            for (Gob.Overlay ol : g.ols) {
                if (ol.spr instanceof NMiningSupport) {
                    nms = (NMiningSupport) ol.spr;
                    break;
                }
            }
            if (nms == null)
                continue;
            NMiningSupport.Mask mask = nms.getMask();
            if ((mask == null) || (mask.data == null))
                continue;
            int x0 = Math.max(0, mask.begin.x - mm.ul.x + 1), x1 = Math.min(mm.sz.x + 2, mask.end.x - mm.ul.x + 1);
            int y0 = Math.max(0, mask.begin.y - mm.ul.y + 1), y1 = Math.min(mm.sz.y + 2, mask.end.y - mm.ul.y + 1);
            for (int x = x0; x < x1; x++) {
                int sx = x + mm.ul.x - 1 - mask.begin.x;
                if (sx >= mask.data.length)
                    continue;
                for (int y = y0; y < y1; y++) {
                    int sy = y + mm.ul.y - 1 - mask.begin.y;
                    if ((sy < mask.data[sx].length) && mask.data[sx][sy])
                        buf2[x][y] = true;
                }
            }
        }
        return buf2;
    }

    public RenderTree.Node makenol(MapMesh mm, Long grid_id, Coord grid_ul)
    {
        mm.olvert();
        class Buf implements Tiler.MCons
        {
            short[] fl = new short[16];
            int fn = 0;

            public void faces(MapMesh m, Tiler.MPart d)
            {
                while (fn + d.f.length > fl.length)
                    fl = Utils.extend(fl, fl.length * 2);
                for (int fi : d.f)
                    fl[fn++] = (short) mm.olvert.vl[d.v[fi].vi];
            }
        }
        Coord t = new Coord();
        Buf buf = new Buf();
        boolean[][] buf2 = maskForCut(mm);

        for (t.y = 0; t.y < mm.sz.y; t.y++)
        {
            for (t.x = 0; t.x < mm.sz.x; t.x++)
            {

                if (buf2[t.x + 1][t.y + 1])
                {
                    Coord gc = t.add(mm.ul);
                    mm.map.tiler(mm.map.gettile(gc)).lay(mm, t, gc, buf, false);
                }
            }
        }

        if (buf.fn == 0)
            return (null);
        haven.render.Model mod = new haven.render.Model(haven.render.Model.Mode.TRIANGLES, mm.olvert.dat,
                new haven.render.Model.Indices(buf.fn, NumberFormat.UINT16, DataBuffer.Usage.STATIC,
                        DataBuffer.Filler.of(Arrays.copyOf(buf.fl, buf.fn))));
        return (new MapMesh.ShallowWrap(mod, new MapMesh.NOLOrder(id)));
    }

    public RenderTree.Node makenolol(MapMesh mm, Long grid_id, Coord grid_ul)
    {
        mm.olvert();
        class Buf implements Tiler.MCons
        {
            int mask;
            short[] fl = new short[16];
            int fn = 0;

            public void faces(MapMesh m, Tiler.MPart d)
            {
                byte[] ef = new byte[d.v.length];
                for (int i = 0; i < d.v.length; i++)
                {
                    if (d.tcy[i] == 0.0f) ef[i] |= 1;
                    if (d.tcx[i] == 1.0f) ef[i] |= 2;
                    if (d.tcy[i] == 1.0f) ef[i] |= 4;
                    if (d.tcx[i] == 0.0f) ef[i] |= 8;
                }
                while (fn + (d.f.length * 2) > fl.length)
                    fl = Utils.extend(fl, fl.length * 2);
                for (int i = 0; i < d.f.length; i += 3)
                {
                    for (int a = 0; a < 3; a++)
                    {
                        int b = (a + 1) % 3;
                        if ((ef[d.f[i + a]] & ef[d.f[i + b]] & mask) != 0)
                        {
                            fl[fn++] = (short) mm.olvert.vl[d.v[d.f[i + a]].vi];
                            fl[fn++] = (short) mm.olvert.vl[d.v[d.f[i + b]].vi];
                        }
                    }
                }
            }
        }
        Area a = Area.sized(mm.ul, mm.sz);
        boolean[][] buf2 = maskForCut(mm);

        Buf buf = new Buf();
        for(Coord t : a) {
                buf.mask = 0;
            if (buf2[t.x - mm.ul.x + 1][t.y - mm.ul.y + 1])
            {
                for(int d = 0; d < 4; d++) {
                    Coord pos = t.add(Coord.uecw[d]).sub(mm.ul).add(1,1);
                    if(!buf2[pos.x][pos.y])
                        buf.mask |= 1 << d;
                }
                if(buf.mask != 0)
                    mm.map.tiler(mm.map.gettile(t)).lay(mm, t.sub(a.ul), t, buf, false);
            }
        }

        if (buf.fn == 0)
            return (null);
        haven.render.Model mod = new haven.render.Model(haven.render.Model.Mode.LINES, mm.olvert.dat,
                new haven.render.Model.Indices(buf.fn, NumberFormat.UINT16, DataBuffer.Usage.STATIC,
                        DataBuffer.Filler.of(Arrays.copyOf(buf.fl, buf.fn))));

        // Check if short walls are enabled
        boolean shortWalls = false;
        Boolean sw = (Boolean) NConfig.get(NConfig.Key.shortWalls);
        shortWalls = (sw != null && sw);

        if (shortWalls) {
            // When short walls enabled, use depth override to render through caps
            return (new MapMesh.ShallowWrap(mod, Pipe.Op.compose(
                    new MapMesh.NOLOrder(id),
                    new States.LineWidth(2),
                    Rendered.last,
                    States.Depthtest.none,
                    States.maskdepth
            )));
        } else {
            return (new MapMesh.ShallowWrap(mod, Pipe.Op.compose(new MapMesh.NOLOrder(id), new States.LineWidth(2))));
        }
    }

    boolean isVisible = (Boolean)NConfig.get(NConfig.Key.miningol);
    boolean shortWallsEnabled = false;

    @Override
    public boolean requpdate()
    {
        boolean res = false;
        synchronized (forAdd)
        {
            if (!forAdd.isEmpty())
            {
                res = true;
                forAdd.clear();
            }
        }
        synchronized (forClear)
        {
            if (!forClear.isEmpty())
            {
                res = true;
                forClear.clear();
            }
        }
        if(dummy== null && oldDummy!=null)
        {
            res = true;
            oldDummy = null;
            oldDummyA = Double.NaN;
        }
        /* A placement preview moved, or turned (a tunnel's coverage
         * turns with it). */
        if(dummy!= null && (oldDummy==null || !oldDummy.equals(dummy.rc.x,dummy.rc.y) || (dummy.a != oldDummyA)))
        {
            res = true;
            oldDummy = new Coord2d(dummy.rc.x, dummy.rc.y);
            oldDummyA = dummy.a;
        }

        if(isVisible!=(Boolean)NConfig.get(NConfig.Key.miningol))
        {
            res = true;
            isVisible=(Boolean)NConfig.get(NConfig.Key.miningol);
        }

        // Check if short walls setting changed
        boolean currentShortWalls = false;
        Boolean sw = (Boolean) NConfig.get(NConfig.Key.shortWalls);
        currentShortWalls = (sw != null && sw);

        if (shortWallsEnabled != currentShortWalls) {
            res = true;
            shortWallsEnabled = currentShortWalls;
        }

        return res;
    }
}
