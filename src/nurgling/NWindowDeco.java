package nurgling;

import haven.*;
import java.awt.Color;

/**
 * Clean, minimal window decoration.
 * Extends DragDeco (drag-to-move only) — zero inherited drawing code.
 * Every pixel drawn here is intentional.
 */
public class NWindowDeco extends Window.DragDeco {
    private static final Color BG     = new Color(40, 52, 54, 245);  // #283436
    private static final Color TITLE  = new Color(40, 52, 54, 255);  // #283436
    private static final Color BORDER = new Color(55, 75, 78, 255);
    private static final Color SEP    = new Color(60, 88, 92, 255);

    // Fixed window size: 599×471 total
    private static final int WIN_W  = 599;
    private static final int WIN_H  = 492;
    private static final int TITLE_H = 21;

    public final boolean lg;
    private final IButton cbtn;
    public Area aa, ca;
    private Text cap;
    private Text.Foundry titlef;

    public NWindowDeco(boolean lg) {
        this.lg = lg;
        cbtn = add(new IButton(NStyle.cbtni[0], NStyle.cbtni[1], NStyle.cbtni[2]))
                   .action(() -> ((Window)parent).reqclose());
    }
    public NWindowDeco() { this(false); }

    private Text.Foundry titlef() {
        if(titlef == null)
            titlef = new Text.Foundry(nurgling.conf.FontSettings.getOpenSansSemibold(), 11, Color.WHITE).aa(true);
        return titlef;
    }

    @Override
    public void iresize(Coord isz) {
        Coord wsz   = UI.scale(WIN_W, WIN_H);
        int   titleH = UI.scale(TITLE_H);
        Coord mrgn  = lg ? Window.dlmrgn : Window.dsmrgn;

        resize(wsz);

        // ca/aa: start at x=0 so children placed at x=N render at exactly N px from outer left
        ca = Area.sized(new Coord(0, titleH), new Coord(wsz.x, wsz.y - titleH));
        aa = ca;

        // Close button: right-aligned, vertically centered in title bar
        cbtn.c = Coord.of(wsz.x - cbtn.sz.x - UI.scale(10),
                          (titleH - cbtn.sz.y) / 2);
    }

    @Override
    public Area contarea() { return aa; }

    @Override
    public boolean checkhit(Coord c) {
        if(ca == null) return false;
        return c.y >= 0 && c.y < ca.ul.y && c.x >= 0 && c.x < sz.x;
    }

    @Override
    public void draw(GOut g, boolean strict) {
        if(ca == null || aa == null) { super.draw(g, strict); return; }
        Window wnd = (Window)parent;

        // Refresh title text when caption changes
        if(cap == null || cap.text != wnd.cap)
            cap = (wnd.cap != null) ? titlef().render(wnd.cap) : null;

        int bw      = Math.max(1, UI.scale(1));
        int titleH  = ca.ul.y;

        // 1. Dark background — entire window
        g.chcolor(BG);
        g.frect(Coord.z, sz);

        // 2. Title bar (same colour as bg, kept separate for future use)
        g.chcolor(TITLE);
        g.frect(Coord.z, new Coord(sz.x, titleH));
        g.chcolor();

        // 3. Title text — centered horizontally and vertically in the title bar
        if(cap != null) {
            Coord textSz  = cap.sz();
            Coord textPos = new Coord(UI.scale(10),
                                      (titleH - textSz.y) / 2);
            g.image(cap.tex(), textPos);
        }

        // 4. Separator between title bar and content
        g.chcolor(SEP);
        g.frect(new Coord(0, titleH - bw), new Coord(sz.x, bw));

        // 5. 1px outer border
        g.chcolor(BORDER);
        g.frect(Coord.z,                  new Coord(sz.x, bw));
        g.frect(new Coord(0, sz.y - bw),  new Coord(sz.x, bw));
        g.frect(Coord.z,                  new Coord(bw, sz.y));
        g.frect(new Coord(sz.x - bw, 0),  new Coord(bw, sz.y));
        g.chcolor();

        // 6. Children (close button)
        super.draw(g, strict);
    }
}
