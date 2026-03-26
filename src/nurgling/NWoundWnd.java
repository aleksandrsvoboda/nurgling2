package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;
import nurgling.i18n.L10n;

public class NWoundWnd extends WoundWnd {
    private static final Color ROW_EVEN = new Color(51, 62, 64);
    private static final Color ROW_ODD  = new Color(40, 52, 54);
    private static final Color INFO_BG  = new Color(0x1C, 0x25, 0x26);

    private static final int INFO_W = UI.scale(267);
    private static final int INFO_H = UI.scale(348);
    private static final int LIST_W = UI.scale(257);
    private static final int LIST_H = UI.scale(348);
    private static final int SECTION_GAP = UI.scale(17);
    private static final int TITLE_GAP = UI.scale(5);
    private static final int WOUND_ITEM_H = UI.scale(26);

    public NWoundWnd() {
	super();
    }

    @Override
    protected void buildLayout() {
	Coord nbisz = NFrame.nbox.bisz();
	Coord nbtl = NFrame.nbox.btloff();
	int infoInnerW = INFO_W - nbisz.x;
	int infoInnerH = INFO_H - nbisz.y;

	// Title
	Widget prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.wound.title")).tex()), "gfx/hud/chr/tips/wounds"), 0, 0);
	int contentY = prev.pos("bl").y + TITLE_GAP;

	// Description box (left) — NFrame orange border, bg handled by NWoundBox
	woundbox = add(new Widget(new Coord(infoInnerW, infoInnerH)) {
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
	    }, nbtl.x, contentY + nbtl.y);
	NFrame.around(this, Collections.singletonList(woundbox));

	// Wound list (right) — no orange border, dark bg
	int listX = INFO_W + SECTION_GAP;
	add(new Widget(new Coord(LIST_W, LIST_H)) {
	    public void draw(GOut g) {
		g.chcolor(INFO_BG);
		g.frect(Coord.z, sz);
		g.chcolor();
		super.draw(g);
	    }
	}, listX, contentY);
	this.wounds = add(new WoundList(new Coord(LIST_W, LIST_H), WOUND_ITEM_H) {
	    @Override
	    protected void drawslot(GOut g, Wound w, int idx, Area area) {
		g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		g.frect2(area.ul, area.br);
		g.chcolor();
		if((wound != null) && (wound.woundid() == w.id))
		    drawsel(g, w, idx, area);
	    }
	}, listX, contentY);
	pack();
    }
}
