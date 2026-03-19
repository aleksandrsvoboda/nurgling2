package nurgling;

import haven.*;

public class NFrame extends Frame {
    public static final IBox nbox = new IBox() {
        private Coord boff() { return Coord.of(Math.max(2, UI.scale(2)), Math.max(2, UI.scale(2))); }
        public Coord btloff() { return boff(); }
        public Coord ctloff() { return boff(); }
        public Coord bbroff() { return boff(); }
        public Coord cbroff() { return boff(); }
        public Coord bisz() { Coord b = boff(); return Coord.of(b.x * 2, b.y * 2); }
        public Coord cisz() { return bisz(); }
        public void draw(GOut g, Coord tl, Coord sz) {}
    };

    public NFrame(Coord sz, boolean inner, IBox box) {
        super(sz, inner, box);
    }

    public NFrame(Coord sz, boolean inner) {
        super(sz, inner);
    }

    public static NFrame around(Widget parent, Area area, IBox box) {
        NFrame ret = new NFrame(area.sz(), true, box);
        parent.add(ret, area.ul.sub(box.btloff()));
        return ret;
    }

    public static NFrame around(Widget parent, Area area) {
        return around(parent, area, nbox);
    }

    public static NFrame around(Widget parent, Iterable<? extends Widget> wl) {
        Widget f = wl.iterator().next();
        Coord tl = new Coord(f.c), br = new Coord(f.c);
        for(Widget wdg : wl) {
            Coord wbr = wdg.c.add(wdg.sz);
            if(wdg.c.x < tl.x) tl.x = wdg.c.x;
            if(wdg.c.y < tl.y) tl.y = wdg.c.y;
            if(wbr.x > br.x) br.x = wbr.x;
            if(wbr.y > br.y) br.y = wbr.y;
        }
        return around(parent, new Area(tl, br));
    }

    @Override
    public void drawframe(GOut g) {
        int bw = Math.max(2, UI.scale(2));
        g.chcolor(233, 156, 84, 255);  // #E99C54
        g.frect(Coord.z, new Coord(sz.x, bw));
        g.frect(new Coord(0, sz.y - bw), new Coord(sz.x, bw));
        g.frect(Coord.z, new Coord(bw, sz.y));
        g.frect(new Coord(sz.x - bw, 0), new Coord(bw, sz.y));
        g.chcolor();
    }
}
