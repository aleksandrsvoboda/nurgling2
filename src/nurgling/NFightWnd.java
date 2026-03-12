package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;
import static haven.Window.wbox;
import nurgling.i18n.L10n;

public class NFightWnd extends FightWnd {
    private static final Color ROW_EVEN = new Color(51, 62, 64);
    private static final Color ROW_ODD  = new Color(40, 52, 54);
    private static final Color INFO_BG  = new Color(22, 39, 51);

    public NFightWnd(int nsave, int nact, int max) {
	super(nsave, nact, max);
    }

    @Override
    protected void buildLayout() {
	Coord nbtl = NFrame.nbox.btloff();
	Widget p;

	info = add(new ImageInfoBox(UI.scale(new Coord(223, 160))) {
	    @Override
	    public void drawbg(GOut g) {
		g.chcolor(INFO_BG);
		g.frect(Coord.z, sz);
		g.chcolor();
	    }
	}, UI.scale(new Coord(5, 35)).add(nbtl));
	NFrame.around(this, Collections.singletonList(info));

	add(CharWnd.settip(new Img(CharWnd.catf.render(L10n.get("char.fight.title")).tex()), "gfx/hud/chr/tips/combat"), 0, 0);
	actlist = add(new Actions(UI.scale(250, 160)) {
	    @Override
	    protected void drawslot(GOut g, Action item, int idx, Area area) {
		g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		g.frect2(area.ul, area.br);
		g.chcolor();
		if((sel != null) && (sel == item))
		    drawsel(g, item, idx, area);
	    }
	}, UI.scale(new Coord(245, 35)).add(nbtl));
	NFrame.around(this, Collections.singletonList(actlist));

	p = add(new BView(), UI.scale(5, 208));
	count = add(new Label(""), p.pos("ur").adds(10, 0));

	savelist = add(new Savelist(UI.scale(370, 60)) {
	    @Override
	    protected void drawslot(GOut g, Integer item, int idx, Area area) {
		g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		g.frect2(area.ul, area.br);
		g.chcolor();
		if((sel != null) && (sel == item))
		    drawsel(g, item, idx, area);
	    }
	}, p.pos("bl").adds(0, 2).add(nbtl));
	p = NFrame.around(this, Collections.singletonList(savelist));
	p = add(new Button(UI.scale(110), L10n.get("char.fight.load"), false).action(() -> {
		    load(savelist.sel);
		    use(savelist.sel);
	}), p.pos("ur").adds(10, 0));
	p = add(new Button(UI.scale(110), L10n.get("char.fight.save"), false).action(() -> {
		    if(savelist.sel < 0) {
			getparent(GameUI.class).error(L10n.get("char.fight.no_save_selected"));
		    } else {
			save(savelist.sel);
			use(savelist.sel);
		    }
	}), p.pos("bl").adds(0, 2));
	pack();
    }
}
