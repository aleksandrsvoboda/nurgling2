package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;
import static haven.PUtils.*;
import nurgling.i18n.L10n;

public class NBAttrWnd extends BAttrWnd {
    private static final int nattrw = UI.scale(263);
    private static final Color HEADER_VAL = new Color(255, 255, 255, 128);

    public static class NAttr extends BAttrWnd.Attr {
	private static final int ICON_SZ = UI.scale(20);
	private final Tex nimg;
	private Text nct;
	private double nlvlt = 0.0;
	private int ncbv = -1, nccv = -1;

	private NAttr(Glob glob, String attr, Color bg) {
	    super(Coord.of(nattrw, UI.scale(26)), glob, attr, bg);
	    Resource res = Loading.waitfor(this.attr.res());
	    this.nimg = new TexI(convolve(res.flayer(Resource.imgc).img, Coord.of(ICON_SZ, ICON_SZ), iconfilter));
	}

	@Override
	public void tick(double dt) {
	    super.tick(dt);
	    if((attr.base != ncbv) || (attr.comp != nccv)) {
		ncbv = attr.base; nccv = attr.comp;
		Color c = Color.WHITE;
		if(nccv > ncbv) c = buff;
		else if(nccv < ncbv) c = debuff;
		nct = NStyle.nattrf.render(Integer.toString(nccv), c);
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
	    int iconX = (sz.y - ICON_SZ) / 2;
	    g.aimage(nimg, cn.add(iconX, 0), 0, 0.5);
	    g.aimage(rnm.tex(), cn.add(sz.y + UI.scale(5), 1), 0, 0.5);
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
	private static final int ICON_SZ = UI.scale(20);

	public NConstipations(Coord sz) {
	    super(sz, UI.scale(26));
	}

	@Override
	protected int pctInset() { return UI.scale(8); }

	@Override
	protected void drawslot(GOut g, El el, int idx, Area area) {
	    g.chcolor(((idx % 2) == 0) ? NStyle.rowEven : NStyle.rowOdd);
	    g.frect2(area.ul, area.br);
	    g.chcolor();
	}

	@Override
	protected Widget makeitem(El el, int idx, Coord sz) {
	    return new NItem(sz, el);
	}

	private static class NItemIcon extends ItemIcon {
	    public NItemIcon(Coord sz, ItemSpec spec) {
		super(sz, spec);
	    }
	    @Override
	    protected int margin() {
		return (sz.y - ICON_SZ) / 2;
	    }
	}

	public class NItem extends Widget {
	    public final El el;
	    private Widget nm, a;
	    private double da = Double.NaN;

	    public NItem(Coord sz, El el) {
		super(sz);
		this.el = el;
		update();
	    }

	    private void update() {
		if(el.a != da) {
		    if(nm != null) {nm.reqdestroy(); nm = null;}
		    if( a != null) { a.reqdestroy();  a = null;}
		    Label a = adda(new Label(String.format("%d%%", Math.max((int)Math.round((1.0 - el.a) * 100), 1)), NStyle.nattrf),
				   sz.x - NConstipations.this.pctInset(), sz.y / 2, 1.0, 0.5);
		    a.setcolor((el.a > 1.0) ? buffed : Utils.blendcol(none, full, el.a));
		    nm = adda(new NItemIcon(Coord.of(a.c.x - UI.scale(5), sz.y), new ItemSpec(OwnerContext.uictx.curry(NConstipations.this.ui), el.t, null)),
			      0, sz.y / 2, 0.0, 0.5);
		    this.a = a;
		    da = el.a;
		}
	    }

	    public void draw(GOut g) {
		update();
		super.draw(g);
	    }
	}
    }

    public NBAttrWnd(Glob glob) {
	super(glob);
    }

    @Override
    protected void buildLayout(Glob glob) {
	Widget prev;
	int catfDescent = NStyle.ncatf.m.getDescent();
	Coord nbtl = NFrame.nbox.btloff();
	int leftColX = 0;
	int rightColX = (nattrw + NFrame.nbox.bisz().x) + UI.scale(15);

	prev = add(CharWnd.settip(new Img(NStyle.ncatf.render(L10n.get("char.battr.title")).tex()), "gfx/hud/chr/tips/base"),
		   new Coord(leftColX, 0));
	attrs = new ArrayList<>();
	NAttr aw;
	attrs.add(aw = add(new NAttr(glob, "str", NStyle.rowEven), prev.pos("bl").add(0, UI.scale(10) - catfDescent).add(nbtl)));
	attrs.add(aw = add(new NAttr(glob, "agi", NStyle.rowOdd), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "int", NStyle.rowEven), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "con", NStyle.rowOdd), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "prc", NStyle.rowEven), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "csm", NStyle.rowOdd), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "dex", NStyle.rowEven), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "wil", NStyle.rowOdd), aw.pos("bl")));
	attrs.add(aw = add(new NAttr(glob, "psy", NStyle.rowEven), aw.pos("bl")));
	Widget lframe = NFrame.around(this, attrs);

	prev = add(CharWnd.settip(new Img(NStyle.ncatf.render(L10n.get("char.battr.fep")).tex()), "gfx/hud/chr/tips/fep"),
		   lframe.pos("bl").x(leftColX).add(0, UI.scale(19)));
	feps = add(new NFoodMeter(), prev.pos("bl").add(0, UI.scale(10) - catfDescent));
	Label fepLbl = add(new Label("") {
	    private String last = "";
	    public void tick(double dt) {
		if(feps == null) return;
		double sum = 0;
		for(BAttrWnd.FoodMeter.El el : feps.els) sum += el.a;
		String s = String.format("%.0f/%.0f", sum, feps.cap);
		if(!s.equals(last)) { settext(s); last = s; }
	    }
	}, prev.pos("ur").add(UI.scale(5), 0));
	fepLbl.f = NStyle.ncatf;
	fepLbl.setcolor(HEADER_VAL);

	int ah = attrs.get(attrs.size() - 1).pos("bl").y - attrs.get(0).pos("ul").y;
	prev = add(CharWnd.settip(new Img(NStyle.ncatf.render(L10n.get("char.battr.satiation")).tex()), "gfx/hud/chr/tips/constip"),
		   new Coord(rightColX, 0));
	cons = add(new NConstipations(Coord.of(nattrw, ah)), prev.pos("bl").add(0, UI.scale(10) - catfDescent).add(nbtl));
	Widget rframe = NFrame.around(this, Collections.singletonList(cons));

	prev = add(CharWnd.settip(new Img(NStyle.ncatf.render(L10n.get("char.battr.hunger")).tex()), "gfx/hud/chr/tips/hunger"),
		   rframe.pos("bl").x(rightColX).add(0, UI.scale(19)));
	glut = add(new NGlutMeter(), prev.pos("bl").add(0, UI.scale(10) - catfDescent));
	Label glutLbl = add(new Label("") {
	    private String last = "";
	    public void tick(double dt) {
		if(glut == null) return;
		String s = String.format("%d%%", Math.round(glut.gmod * 100));
		if(!s.equals(last)) { settext(s); last = s; }
	    }
	}, prev.pos("ur").add(UI.scale(5), 0));
	glutLbl.f = NStyle.ncatf;
	glutLbl.setcolor(HEADER_VAL);

	pack();
		resize(sz.add(UI.scale(2), UI.scale(21) + UI.scale(8)));
    }
}
