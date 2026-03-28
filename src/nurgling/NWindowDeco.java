package nurgling;

import haven.*;
import java.awt.Color;

public class NWindowDeco extends Window.DragDeco {
    private static final int TITLE_H = 21;

    public final boolean lg;
    public final IButton cbtn;
    public boolean dragsize;
    public Area aa, ca;
    private Text cap;
    private boolean cfocus;
    private final Coord customMrgn;
    private static Text.Foundry ftitlef, nftitlef;

    public NWindowDeco(boolean lg) {
        this(lg, null);
    }

    public NWindowDeco(boolean lg, Coord customMrgn) {
        this.lg = lg;
        this.customMrgn = customMrgn;
        cbtn = add(new NCloseButton(NStyle.cbtni[0], NStyle.cbtni[1], NStyle.cbtni[2]))
                   .action(() -> ((Window)parent).reqclose());
    }
    public NWindowDeco() { this(false); }

    public NWindowDeco dragsize(boolean v) {
        this.dragsize = v;
        return this;
    }

    private static Text.Foundry titlef() {
        if(ftitlef == null)
            ftitlef = new Text.Foundry(nurgling.conf.FontSettings.getOpenSansSemibold(), 11, Color.WHITE).aa(true);
        return ftitlef;
    }

    private static Text.Foundry ntitlef() {
        if(nftitlef == null)
            nftitlef = new Text.Foundry(nurgling.conf.FontSettings.getOpenSansSemibold(), 11, new Color(160, 160, 160)).aa(true);
        return nftitlef;
    }

    @Override
    public void iresize(Coord isz) {
        int titleH = UI.scale(TITLE_H);
        Coord mrgn = customMrgn != null ? customMrgn : (lg ? Window.dlmrgn : Window.dsmrgn);
        Coord csz = isz.add(mrgn.mul(2));
        Coord wsz = new Coord(csz.x, csz.y + titleH);
        resize(wsz);
        ca = Area.sized(new Coord(0, titleH), csz);
        aa = Area.sized(ca.ul.add(mrgn), isz);
        cbtn.c = Coord.of(wsz.x - cbtn.sz.x - UI.scale(5),
                          (titleH - cbtn.sz.y) / 2);
    }

    @Override
    public Area contarea() { return aa; }

    protected void cdraw(GOut g) {
        ((Window)parent).cdraw(g);
    }

    @Override
    public boolean checkhit(Coord c) {
        if(ca == null) return false;
        return c.x >= 0 && c.x < sz.x && c.y >= 0 && c.y < sz.y;
    }

    protected void drawbg(GOut g) {
        if(ui instanceof NUI) {
            NUI nui = (NUI)ui;
            float opacity = nui.getUIOpacity();
            if(nui.getUseSolidBackground()) {
                Color bgColor = nui.getWindowBackgroundColor();
                int alpha = (int)(255 * opacity);
                g.chcolor(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), alpha);
                g.frect(new Coord(0, ca.ul.y), ca.sz());
                g.chcolor();
                return;
            }
        }
        g.chcolor(NStyle.windowBg);
        g.frect(new Coord(0, ca.ul.y), ca.sz());
        g.chcolor();
    }

    @Override
    public void draw(GOut g, boolean strict) {
        if(ca == null || aa == null) { super.draw(g, strict); return; }
        Window wnd = (Window)parent;

        if(cap == null || cap.text != wnd.cap || cfocus != wnd.hasfocus) {
            cfocus = wnd.hasfocus;
            cap = (wnd.cap != null) ? (cfocus ? titlef() : ntitlef()).render(wnd.cap) : null;
        }

        int bw     = Math.max(1, UI.scale(1));
        int titleH = ca.ul.y;

        // 1. Content background
        drawbg(g);

        // 2. Title bar
        g.chcolor(NStyle.titleBg);
        g.frect(Coord.z, new Coord(sz.x, titleH));
        g.chcolor();

        // 3. Title text
        if(cap != null) {
            Coord textPos = new Coord(UI.scale(10), (titleH - cap.sz().y) / 2);
            g.image(cap.tex(), textPos);
        }

        // 4. Separator
        g.chcolor(NStyle.separator);
        g.frect(new Coord(0, titleH - bw), new Coord(sz.x, bw));

        // 5. Outer border
        g.chcolor(NStyle.border);
        g.frect(Coord.z,                  new Coord(sz.x, bw));
        g.frect(new Coord(0, sz.y - bw),  new Coord(sz.x, bw));
        g.frect(Coord.z,                  new Coord(bw, sz.y));
        g.frect(new Coord(sz.x - bw, 0),  new Coord(bw, sz.y));
        g.chcolor();

        // 6. Sizer handle
        if(dragsize)
            g.image(Window.sizer, ca.br.sub(Window.sizer.sz()));

        // 7. Content drawing
        cdraw(g.reclip(aa.ul, aa.sz()));

        // 8. Children (close button, sort button, etc.)
        super.draw(g, strict);
    }

    // Drag-to-resize support
    private UI.Grab szdrag;
    private Coord szdragc;

    public boolean mousedown(MouseDownEvent ev) {
        if(dragsize) {
            Coord c = ev.c;
            if((ev.b == 1) && (c.x < ca.br.x) && (c.y < ca.br.y) && (c.y >= ca.br.y - UI.scale(25) + (ca.br.x - c.x))) {
                szdrag = ui.grabmouse(this);
                szdragc = aa.sz().sub(c);
                return(true);
            }
        }
        return(super.mousedown(ev));
    }

    public void mousemove(MouseMoveEvent ev) {
        if(szdrag != null)
            ((Window)parent).resize(ev.c.add(szdragc));
        super.mousemove(ev);
    }

    public boolean mouseup(MouseUpEvent ev) {
        if((ev.b == 1) && (szdrag != null)) {
            szdrag.remove();
            szdrag = null;
            return(true);
        }
        return(super.mouseup(ev));
    }
}
