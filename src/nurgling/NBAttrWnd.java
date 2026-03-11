package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;
import static haven.PUtils.*;
import nurgling.i18n.L10n;

public class NBAttrWnd extends BAttrWnd {
    private static final int nattrw = UI.scale(263);

    public static class NAttr extends BAttrWnd.Attr {
	private Text nct;
	private double nlvlt = 0.0;
	private int ncbv = -1, nccv = -1;

	public NAttr(Glob glob, String attr, Color bg) {
	    super(Coord.of(nattrw, UI.scale(26)), glob, attr, bg);
	}

	@Override
	public void tick(double dt) {
	    super.tick(dt);
	    if((attr.base != ncbv) || (attr.comp != nccv)) {
		ncbv = attr.base; nccv = attr.comp;
		Color c = Color.WHITE;
		if(nccv > ncbv) c = buff;
		else if(nccv < ncbv) c = debuff;
		nct = attrf.render(Integer.toString(nccv), c);
	    }
	    if((nlvlt > 0.0) && ((nlvlt -= dt) < 0))
		nlvlt = 0.0;
	}

	@Override
	public void lvlup() {
	    super.lvlup();
	    nlvlt = 1.0;
	}

	@Override
	public void draw(GOut g) {
	    if(nlvlt != 0.0)
		g.chcolor(Utils.blendcol(bg, new Color(128, 255, 128, 128), nlvlt));
	    else
		g.chcolor(bg);
	    g.frect(Coord.z, sz);
	    g.chcolor();
	    Coord cn = new Coord(0, sz.y / 2);
	    g.aimage(img, cn.add(5, 0), 0, 0.5);
	    g.aimage(rnm.tex(), cn.add(img.sz().x + UI.scale(10), 1), 0, 0.5);
	    if(nct != null)
		NAttrUtil.drawValue(g, cn, sz, nct, attr.base, attr.comp);
	}
    }

    public static class NFoodMeter extends BAttrWnd.FoodMeter {
	private static final Color METER_BG = new Color(22, 39, 51);

	public NFoodMeter() {
	    super(Coord.of(nattrw + NFrame.nbox.bisz().x, UI.scale(40)));
	}

	@Override
	public void draw(GOut g) {
	    int bw = Math.max(2, UI.scale(2));
	    Coord nmarg = new Coord(bw, bw);
	    g.chcolor(METER_BG);
	    g.frect(nmarg, sz.sub(nmarg.mul(2)));
	    double x = 0;
	    int w = sz.x - (nmarg.x * 2);
	    for(El el : els) {
		int l = (int)Math.floor((x / cap) * w);
		int r = (int)Math.floor(((x += el.a) / cap) * w);
		try {
		    Color col = el.ev().col;
		    g.chcolor(col);
		    g.frect(new Coord(nmarg.x + l, nmarg.y), new Coord(r - l, sz.y - (nmarg.y * 2)));
		} catch(Loading e) {
		}
	    }
	    g.chcolor();
	    NAttrUtil.drawBorder(g, sz);
	}
    }

    public static class NGlutMeter extends BAttrWnd.GlutMeter {
	private static final Color METER_BG = new Color(22, 39, 51);
	private static final Color METER_FG = new Color(127, 236, 58);

	public NGlutMeter() {
	    super(Coord.of(nattrw + NFrame.nbox.bisz().x, UI.scale(40)));
	}

	@Override
	public void draw(GOut g) {
	    int bw = Math.max(2, UI.scale(2));
	    Coord nmarg = new Coord(bw, bw);
	    Coord isz = sz.sub(nmarg.mul(2));
	    g.chcolor(METER_BG);
	    g.frect(nmarg, isz);
	    g.chcolor(METER_FG);
	    g.frect(nmarg, new Coord((int)Math.round(isz.x * (glut - Math.floor(glut))), isz.y));
	    g.chcolor();
	    NAttrUtil.drawBorder(g, sz);
	}
    }

    public static class NConstipations extends BAttrWnd.Constipations {
	public NConstipations(Coord sz) {
	    super(sz, UI.scale(26));
	}
    }

    public NBAttrWnd(Glob glob) {
	super(glob);
    }

    @Override
    protected void buildLayout(Glob glob) {
	Widget prev;
	int catfDescent = ((Text.Foundry) catf).m.getDescent();
	Coord nbtl = NFrame.nbox.btloff();
	int leftColX = 0;
	int rightColX = (nattrw + NFrame.nbox.bisz().x) + UI.scale(15);

	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.battr.title")).tex()), "gfx/hud/chr/tips/base"),
		   new Coord(leftColX, 0));
	attrs = new ArrayList<>();
	NAttr aw;
	attrs.add(aw = add(new NAttr(glob, "str", every), prev.pos("bl").add(0, UI.scale(10) - catfDescent).add(nbtl)));
	attrs.add(aw = add(new NAttr(glob, "agi", other), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "int", every), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "con", other), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "prc", every), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "csm", other), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "dex", every), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "wil", other), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "psy", every), aw.pos("bl")));
	Widget lframe = NFrame.around(this, attrs);

	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.battr.fep")).tex()), "gfx/hud/chr/tips/fep"),
		   lframe.pos("bl").x(leftColX).add(0, UI.scale(19)));
	feps = add(new NFoodMeter(), prev.pos("bl").add(0, UI.scale(10) - catfDescent));

	int ah = attrs.get(attrs.size() - 1).pos("bl").y - attrs.get(0).pos("ul").y;
	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.battr.satiation")).tex()), "gfx/hud/chr/tips/constip"),
		   new Coord(rightColX, 0));
	cons = add(new NConstipations(Coord.of(nattrw, ah)), prev.pos("bl").add(0, UI.scale(10) - catfDescent).add(nbtl));
	Widget rframe = NFrame.around(this, Collections.singletonList(cons));

	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.battr.hunger")).tex()), "gfx/hud/chr/tips/hunger"),
		   rframe.pos("bl").x(rightColX).add(0, UI.scale(19)));
	glut = add(new NGlutMeter(), prev.pos("bl").add(0, UI.scale(10) - catfDescent));

	pack();
	resize(sz.add(0, UI.scale(21)));
    }
}
