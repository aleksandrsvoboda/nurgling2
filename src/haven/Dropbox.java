package haven;

import java.awt.Color;

public abstract class Dropbox<T> extends ListWidget<T> {
    public static final Tex drop = Resource.loadtex("gfx/hud/drop");
    public final int listh;
    private final Coord dropc;
    private Droplist dl;

    public Dropbox(int w, int listh, int itemh) {
        super(new Coord(w, itemh), itemh);
        this.listh = listh;
        dropc = new Coord(sz.x - drop.sz().x, 0);
    }

    private class Droplist extends Listbox<T> {
        private UI.Grab grab = null;

        private Droplist() {
            super(Dropbox.this.sz.x, Math.min(listh, Dropbox.this.listitems()), Dropbox.this.itemh);
            sel = Dropbox.this.sel;
            Dropbox.this.ui.root.add(this, Dropbox.this.rootpos().add(0, Dropbox.this.sz.y));
            z(1000);
            raise();
            grab = ui.grabmouse(this);
            display();
        }

        protected T listitem(int i) {return(Dropbox.this.listitem(i));}
        protected int listitems() {return(Dropbox.this.listitems());}
        protected void drawitem(GOut g, T item, int idx) {Dropbox.this.drawlistitem(g, item, idx);}

        /* New UI: darker than the window behind it and outlined, so the popup's extent is clear. */
        protected void drawbg(GOut g) {
            if(!nurgling.styles.UITheme.on()) {
                super.drawbg(g);
                return;
            }
            g.chcolor(nurgling.styles.UITheme.INPUT);
            g.frect(Coord.z, sz);
            g.chcolor();
        }

        public void draw(GOut g) {
            super.draw(g);
            if(nurgling.styles.UITheme.on())
                nurgling.styles.UITheme.panel(g, Coord.z, sz, null, nurgling.styles.UITheme.ACCENT);
        }

        protected void itemclick(T item, Coord c, int button) {
            int width = sz.x - (sb.vis() ? sb.sz.x : 0);
            if(Dropbox.this.listitemclick(item, c, button, width))
                reqdestroy();
            else
                super.itemclick(item, c, button);
        }

        public void destroy() {
            grab.remove();
            super.destroy();
            dl = null;
        }

        public void change(T item) {
            Dropbox.this.change(item);
            reqdestroy();
        }
    }

    /** Popup rows may have actions separate from selecting the item. */
    protected void drawlistitem(GOut g, T item, int idx) { drawitem(g, item, idx); }
    protected boolean listitemclick(T item, Coord c, int button, int width) { return false; }

    public static Color bgColor = Color.BLACK;
    public void draw(GOut g) {
        boolean flat = nurgling.styles.UITheme.on();
        if(flat) {
            nurgling.styles.GeneratedButtons.plate(g, Coord.z, sz,
                dl != null ? nurgling.styles.GeneratedButtons.State.SELECTED : nurgling.styles.GeneratedButtons.State.NORMAL);
        } else {
            g.chcolor(bgColor);
            g.frect(Coord.z, sz);
            g.chcolor();
        }
        if(sel != null)
            drawitem(g.reclip(Coord.z, new Coord(sz.x - drop.sz().x, itemh)), sel, 0);
        if(flat) {
            int side = UI.scale(10);
            nurgling.styles.GeneratedButtons.icon(g, "down",
                new Coord(sz.x - drop.sz().x / 2 - side / 2, (sz.y - side) / 2), side);
        } else {
            g.image(drop, dropc);
        }
        super.draw(g);
    }

    public boolean mousedown(MouseDownEvent ev) {
        if(super.mousedown(ev))
            return(true);
        if((dl == null) && (ev.b == 1)) {
            dl = new Droplist();
            return(true);
        }
        return(true);
    }
}
