package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;
import nurgling.i18n.L10n;

public class NWoundWnd extends WoundWnd {
    private static final Color ROW_EVEN = new Color(51, 62, 64);
    private static final Color ROW_ODD  = new Color(40, 52, 54);
    private static final Color INFO_BG  = new Color(22, 39, 51);

    public NWoundWnd() {
	super();
    }

    @Override
    protected void buildLayout() {
	Widget prev;
	Coord nbtl = NFrame.nbox.btloff();

	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.wound.title")).tex()), "gfx/hud/chr/tips/wounds"), 0, 0);
	this.wounds = add(new WoundList(Coord.of(attrw, height)) {
	    @Override
	    protected void drawslot(GOut g, Wound w, int idx, Area area) {
		g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		g.frect2(area.ul, area.br);
		g.chcolor();
		if((wound != null) && (wound.woundid() == w.id))
		    drawsel(g, w, idx, area);
	    }
	}, prev.pos("bl").x(width + UI.scale(5)).add(nbtl));
	NFrame.around(this, Collections.singletonList(this.wounds));
	woundbox = add(new Widget(new Coord(attrw, this.wounds.sz.y)) {
		public void draw(GOut g) {
		    g.chcolor(INFO_BG);
		    g.frect(Coord.z, sz);
		    g.chcolor();
		    super.draw(g);
		}

		public void cdestroy(Widget w) {
		    if(w == wound)
			wound = null;
		}
	    }, prev.pos("bl").adds(5, 0).add(nbtl));
	NFrame.around(this, Collections.singletonList(woundbox));
	pack();
    }
}
