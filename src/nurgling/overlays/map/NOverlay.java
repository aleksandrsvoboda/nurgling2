package nurgling.overlays.map;

import haven.*;
import haven.render.*;
import nurgling.*;
import nurgling.areas.*;

import java.awt.*;
import java.util.*;

public class NOverlay extends MapView.MapRaster
{
    public final Integer id;
    public boolean requpdate2 = false;

    public boolean requpdate(){
        return false;
    }
    Color bc;
    public final Grid base = new Grid<RenderTree.Node>() {
        public RenderTree.Node getcut(Coord cc) {
            return(map.getnolcut(NOverlay.this, cc));
        }
    };
    public final Grid outl = new Grid<RenderTree.Node>() {
        public RenderTree.Node getcut(Coord cc) {
            return(map.getnedgecut(NOverlay.this, cc));
        }
    };

    public NOverlay(Integer id) {
        super(NUtils.getGameUI().map.glob.map, NUtils.getGameUI().map.view);
        if(id>=0) {
            NArea area = NUtils.getArea(id);
            bc = (area != null) ? area.color : java.awt.Color.GRAY;
        }
        this.id = id;
    }

    /* Grids by id, snapshotted once per frame by NMapView.oltick for areaCuts.
     * Per overlay, not static: with several sessions, a background
     * session's oltick would otherwise swap in its own grids. */
    public Map<Long, MCache.Grid> gridsById = Collections.emptyMap();

    public void tick() {
        super.tick();
        if(area != null) {
            if(id >= 0)
                area = areaCuts(area);
            base.tick();
            outl.tick();
        }
        requpdate2 = false;
    }

    /* An area covers a few tiles, usually a single cut, yet the view window spans
     * 5x5 cuts. Ticking only the cuts the area overlaps keeps the per-frame cost from
     * scaling with the number of saved areas. Grid.tick removes every cut outside the
     * returned area, so an area that moves, shrinks or leaves the view is cleaned up. */
    private Area areaCuts(Area win) {
        NArea narea = map.areas.get(id);
        NArea.Space space = (narea == null) ? null : narea.space;
        if(space == null)
            return(win);
        Map<Long, MCache.Grid> grids = gridsById;
        Coord ul = null, br = null;
        try {
            for(Map.Entry<Long, NArea.VArea> e : space.space.entrySet()) {
                Area va = e.getValue().area;
                if(!va.positive())
                    continue;
                MCache.Grid g = grids.get(e.getKey());
                if(g == null)
                    continue;
                Coord cul = g.ul.add(va.ul).div(MCache.cutsz);
                Coord cbr = g.ul.add(va.br).sub(1, 1).div(MCache.cutsz).add(1, 1);
                ul = (ul == null) ? cul : Coord.of(Math.min(ul.x, cul.x), Math.min(ul.y, cul.y));
                br = (br == null) ? cbr : Coord.of(Math.max(br.x, cbr.x), Math.max(br.y, cbr.y));
            }
        } catch(ConcurrentModificationException e) {
            /* A bot is editing the area's geometry in place; tick the whole window this frame. */
            return(win);
        }
        Area cuts = (ul == null) ? null : new Area(ul, br).overlap(win);
        return((cuts == null) ? new Area(win.ul, win.ul) : cuts);
    }

    public void added(RenderTree.Slot slot) {
//			Material overlay_mat = new Material(new BaseColor(194,194,65,56));
        slot.add(base,new BaseColor(bc));
        slot.add(outl, new BaseColor(200,200,200,200));
        super.added(slot);
    }

    public Loading loading() {
        Loading ret = super.loading();
        if(ret != null)
            return(ret);
        if((ret = base.lastload) != null)
            return(ret);
        return(null);
    }

    public void remove() {

        slot.remove();
        for(MCache.Grid.Cut cut : cuts)
        {
            cut.nols.remove(id);
            cut.nedgs.remove(id);
        }
    }

    public RenderTree.Node makenol(MapMesh mm, Long grid_id, Coord grid_ul) {
        mm.olvert();
        class Buf implements Tiler.MCons {
            short[] fl = new short[16];
            int fn = 0;

            public void faces(MapMesh m, Tiler.MPart d) {
                while(fn + d.f.length > fl.length)
                    fl = Utils.extend(fl, fl.length * 2);
                for(int fi : d.f)
                    fl[fn++] = (short)mm.olvert.vl[d.v[fi].vi];
            }
        }
        Coord t = new Coord();
        Buf buf = new Buf();
        /* This overlay's own session's area: NUtils.getArea goes through
         * the active session, which with several sessions may not be the
         * one this mesh is built for. */
        NArea area = map.areas.get(id);
        if(area == null || area.space == null || area.space.space == null)
            return(null);
        NArea.VArea space = area.space.space.get(grid_id);
        if(space == null)
            return(null);
        Area curArea = space.area.xl(grid_ul);
        for(t.y = 0; t.y < mm.sz.y; t.y++) {
            for(t.x = 0; t.x < mm.sz.x; t.x++) {
                Coord gc = t.add(mm.ul);
                if(curArea.contains(gc))
                {
                    Tiler tl = mm.map.tiler(mm.map.gettile(gc));
                    if(tl != null)
                        tl.lay(mm, t, gc, buf, false);
                }
            }
        }

        if(buf.fn == 0)
            return(null);
        haven.render.Model mod = new haven.render.Model(haven.render.Model.Mode.TRIANGLES, mm.olvert.dat,
                new haven.render.Model.Indices(buf.fn, NumberFormat.UINT16, DataBuffer.Usage.STATIC,
                        DataBuffer.Filler.of(Arrays.copyOf(buf.fl, buf.fn))));
        return(new MapMesh.ShallowWrap(mod, new MapMesh.NOLOrder(id)));
    }

    public RenderTree.Node makenolol(MapMesh mm, Long grid_id, Coord grid_ul) {
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
        NArea area = map.areas.get(id);
        if (area == null || area.space == null || area.space.space == null) {
            return null;
        }
        NArea.VArea space = area.space.space.get(grid_id);
        if (space == null) {
            return null;
        }
        Area curArea = space.area.xl(grid_ul);
        Area fullarea = area.getArea(map);
        if (fullarea == null) {
            return null;
        }
        for(Coord t : a) {
            if(curArea.contains(t))
            {
                buf.mask = 0;
                for(int d = 0; d < 4; d++) {
                    if(!fullarea.contains(t.add(Coord.uecw[d])))
                        buf.mask |= 1 << d;
                }
                if(buf.mask != 0) {
                    Tiler tl = mm.map.tiler(mm.map.gettile(t));
                    if(tl != null)
                        tl.lay(mm, t.sub(a.ul), t, buf, false);
                }
            }
        }
        if(buf.fn == 0)
            return(null);
        haven.render.Model mod = new haven.render.Model(haven.render.Model.Mode.LINES, mm.olvert.dat,
                new haven.render.Model.Indices(buf.fn, NumberFormat.UINT16, DataBuffer.Usage.STATIC,
                        DataBuffer.Filler.of(Arrays.copyOf(buf.fl, buf.fn))));
        return(new MapMesh.ShallowWrap(mod, Pipe.Op.compose(new MapMesh.NOLOrder(id), new States.LineWidth(2))));
    }

    public ArrayList<MCache.Grid.Cut> cuts = new ArrayList<>();
}