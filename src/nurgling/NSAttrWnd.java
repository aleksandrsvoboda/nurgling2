package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;
import static haven.PUtils.*;
import nurgling.i18n.L10n;

public class NSAttrWnd extends SAttrWnd {
    private static final int nsattrw = UI.scale(263);
    private static final Color ROW_ODD = new Color(51, 62, 64);    // #333E40
    private static final Color ROW_EVEN  = new Color(40, 52, 54);    // #283436
    private static final Text.Foundry overviewf = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold().deriveFont((float)Math.floor(UI.scale(12.0)))
    ).aa(true);

    private static final int ICON_SZ = UI.scale(20);

    public class NSAttr extends SAttr {
	private final Tex nimg;
	private Text nct;
	private int ncbv = -1, nccv = -1;
	private final IButton nadd, nsub;

	public NSAttr(Coord sz, Glob glob, String attr, Color bg) {
	    super(sz, glob, attr, bg);
	    Resource res = Loading.waitfor(this.attr.res());
	    this.nimg = new TexI(convolve(res.flayer(Resource.imgc).img, Coord.of(ICON_SZ, ICON_SZ), iconfilter));
	    List<Widget> old = new ArrayList<>();
	    for(Widget w = child; w != null; w = w.next) {
		if(w instanceof IButton)
		    old.add(w);
	    }
	    for(Widget w : old)
		w.destroy();
	    nadd = adda(new NCloseButton(NStyle.plusbtni[0], NStyle.plusbtni[1], NStyle.plusbtni[2]).action(() -> adj(1)),
			sz.x - UI.scale(5), sz.y / 2, 1, 0.5);
	    nsub = adda(new NCloseButton(NStyle.minusbtni[0], NStyle.minusbtni[1], NStyle.minusbtni[2]).action(() -> adj(-1)),
			nadd.c.x - UI.scale(5), sz.y / 2, 1, 0.5);
	}

	@Override
	public void adj(int a) {
	    if(tbv + a < 0) a = -tbv;
	    tbv += a;
	    nccv = 0;
	    nupdcost();
	}

	@Override
	public void reset() {
	    tbv = 0;
	    nccv = 0;
	    nupdcost();
	}

	private void nupdcost() {
	    int cv = attr.base, nv = cv + tbv;
	    int cost = 100 * ((nv + (nv * nv)) - (cv + (cv * cv))) / 2;
	    scost += cost - this.cost;
	    this.cost = cost;
	}

	@Override
	public void tick(double dt) {
	    if(attr.base != ncbv) {
		tbv = 0;
		nccv = 0;
		ncbv = attr.base;
	    }
	    if(attr.comp != nccv) {
		nccv = attr.comp;
		Color c = Color.WHITE;
		if(nccv > ncbv) c = buff;
		else if(nccv < ncbv) c = debuff;
		if(tbv > 0) c = tbuff;
		nct = attrf.render(Integer.toString(nccv + tbv), c);
		nupdcost();
	    }
	}

	@Override
	public void draw(GOut g) {
	    g.chcolor(bg);
	    g.frect(Coord.z, sz);
	    g.chcolor();
	    draw(g, true);
	    Coord cn = new Coord(0, sz.y / 2);
	    int iconX = (sz.y - ICON_SZ) / 2;
	    g.aimage(nimg, cn.add(iconX, 0), 0, 0.5);
	    g.aimage(rnm.tex(), cn.add(sz.y + UI.scale(5), 1), 0, 0.5);
	    if(nct != null)
		g.aimage(nct.tex(), cn.add(nsub.c.x - UI.scale(8), 1), 1, 0.5);
	}
    }

    public static class NStudyInfo extends Widget {
	public final Widget study;
	public int texp, tw, tenc, tlph;
	private static final Text.Foundry sif = new Text.Foundry(
	    nurgling.conf.FontSettings.getOpenSans().deriveFont((float)Math.floor(UI.scale(11.0)))
	).aa(true);

	public NStudyInfo(Coord sz, Widget study) {
	    super(sz);
	    this.study = study;
	    Widget plbl;
	    RLabel<?> pval;
	    plbl = add(new Label(L10n.get("char.sattr.attention"), sif), UI.scale(2, 0));
	    pval = new RLabel<Pair<Integer, Integer>>(
		() -> new Pair<>(tw, (ui == null) ? 0 : ui.sess.glob.getcattr("int").comp),
		n -> String.format("%,d/%,d", n.a, n.b),
		new Color(255, 148, 232, 255));
	    pval.f = sif;
	    adda(pval, new Coord(sz.x - UI.scale(2), plbl.c.y), 1.0, 0.0);
	    plbl = add(new Label(L10n.get("char.sattr.exp_cost"), sif), plbl.pos("bl").adds(0, 2).xs(2));
	    pval = new RLabel<Integer>(() -> tenc, Utils::thformat, new Color(255, 255, 130, 255));
	    pval.f = sif;
	    adda(pval, new Coord(sz.x - UI.scale(2), plbl.c.y), 1.0, 0.0);
	    plbl = add(new Label(L10n.get("char.sattr.lph"), sif), plbl.pos("bl").adds(0, 2).xs(2));
	    pval = new RLabel<Integer>(() -> tlph, Utils::thformat, new Color(0, 238, 255, 255));
	    pval.f = sif;
	    adda(pval, new Coord(sz.x - UI.scale(2), plbl.c.y), 1.0, 0.0);
	    plbl = add(new Label(L10n.get("char.sattr.recieve_lp"), sif), plbl.pos("bl").adds(0, 2).xs(2));
	    pval = new RLabel<Integer>(() -> texp, Utils::thformat, new Color(210, 178, 255, 255));
	    pval.f = sif;
	    adda(pval, new Coord(sz.x - UI.scale(2), plbl.c.y), 1.0, 0.0);
	}

	private void upd() {
	    int texp = 0, tw = 0, tenc = 0, tlph = 0;
	    for(GItem item : study.children(GItem.class)) {
		try {
		    nurgling.iteminfo.NCuriosity ci = ItemInfo.find(nurgling.iteminfo.NCuriosity.class, item.info());
		    if(ci != null) {
			texp += ci.exp;
			tw += ci.mw;
			tenc += ci.enc;
			tlph += nurgling.iteminfo.NCuriosity.lph(ci.lph);
		    }
		} catch(Loading l) {
		}
	    }
	    this.texp = texp; this.tw = tw; this.tenc = tenc; this.tlph = tlph;
	}

	public void tick(double dt) {
	    upd();
	    super.tick(dt);
	}
    }

    public NSAttrWnd(Glob glob) {
	super(glob);
    }

    @Override
    public RLabel<?> explabel() {
	return(new RLabel<Integer>(() -> chr.exp, Utils::thformat, new Color(210, 178, 255)));
    }

    @Override
    public RLabel<?> enclabel() {
	return(new RLabel<Integer>(() -> chr.enc, Utils::thformat, new Color(255, 255, 130)));
    }

    @Override
    protected void buildLayout(Glob glob) {
	Widget prev;
	int catfDescent = ((Text.Foundry) catf).m.getDescent();
	Coord nbtl = NFrame.nbox.btloff();
	int leftColX = 0;
	int rightColX = (nsattrw + NFrame.nbox.bisz().x) + UI.scale(15);

	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.sattr.title")).tex()), "gfx/hud/chr/tips/sattr"),
		   new Coord(leftColX, 0));
	attrs = new ArrayList<>();
	SAttr aw;
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "unarmed", ROW_EVEN), prev.pos("bl").add(0, UI.scale(10) - catfDescent).add(nbtl)));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "melee", ROW_ODD), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "ranged", ROW_EVEN), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "explore", ROW_ODD), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "stealth", ROW_EVEN), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "sewing", ROW_ODD), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "smithing", ROW_EVEN), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "masonry", ROW_ODD), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "carpentry", ROW_EVEN), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "cooking", ROW_ODD), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "farming", ROW_EVEN), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "survive", ROW_ODD), aw.pos("bl")));
	attrs.add(aw = add(new NSAttr(Coord.of(nsattrw, UI.scale(26)), glob, "lore", ROW_EVEN), aw.pos("bl")));
	Widget lframe = NFrame.around(this, attrs);

	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.sattr.study_report")).tex()), "gfx/hud/chr/tips/study"),
		   new Coord(rightColX, 0));
	studyc = prev.pos("bl").add(0, UI.scale(10) - catfDescent);

	int rcBottom = lframe.pos("br").y;
	int rx = rightColX + nsattrw + NFrame.nbox.bisz().x;
	Label expLbl = new Label(L10n.get("char.sattr.exp_points"), overviewf);
	expLbl.setcolor(new Color(255, 255, 130));
	prev = add(expLbl, new Coord(rightColX, rcBottom - UI.scale(100)));
	RLabel<?> encVal = enclabel();
	encVal.f = overviewf;
	adda(encVal, new Coord(rx, prev.pos("ul").y), 1.0, 0.0);
	Label lpLbl = new Label(L10n.get("char.sattr.lp"), overviewf);
	lpLbl.setcolor(new Color(210, 178, 255));
	prev = add(lpLbl, prev.pos("bl").adds(0, 2));
	RLabel<?> expVal = explabel();
	expVal.f = overviewf;
	adda(expVal, new Coord(rx, prev.pos("ul").y), 1.0, 0.0);
	prev = add(new Label(L10n.get("char.sattr.learn_cost"), overviewf), prev.pos("bl").adds(0, 2));
	RLabel<?> costVal = new RLabel<Integer>(() -> scost, Utils::thformat, n -> (n > chr.exp) ? debuff : Color.WHITE);
	costVal.f = overviewf;
	adda(costVal, new Coord(rx, prev.pos("ul").y), 1.0, 0.0);
	prev = adda(new Button(UI.scale(75), L10n.get("char.sattr.buy")).action(this::buy),
		    new Coord(rx, rcBottom), 1.0, 1.0);
	adda(new Button(UI.scale(75), L10n.get("char.sattr.reset")).action(this::reset), prev.pos("bl").subs(5, 0), 1.0, 1.0);
	pack();
	resize(sz.add(0, UI.scale(20)));
    }

    @Override
    public void addchild(Widget child, Object... args) {
	String place = (args[0] instanceof String) ? (((String)args[0]).intern()) : null;
	if(place == "study") {
	    Coord nbtl = NFrame.nbox.btloff();
	    add(child, studyc.add(nbtl));
	    NFrame.around(this, Collections.singletonList(child));
	    Widget inf = add(new NStudyInfo(
		new Coord(nsattrw - child.sz.x - NFrame.nbox.bisz().x - UI.scale(5), child.sz.y), child),
		child.pos("ur").add(NFrame.nbox.bisz().x + UI.scale(5), -UI.scale(6)));
	    pack();

	    if(ui.gui instanceof nurgling.NGameUI) {
		nurgling.NGameUI ngui = (nurgling.NGameUI)ui.gui;
		if(ngui.studyReportWidget == null) {
		    nurgling.widgets.NStudyReport report = new nurgling.widgets.NStudyReport(child);
		    Coord widgetSize = report.sz.add(nurgling.widgets.NDraggableWidget.delta);
		    ngui.studyReportWidget = ngui.add(new nurgling.widgets.NDraggableWidget(report, "StudyReport", widgetSize));

		    nurgling.conf.NDragProp prop = nurgling.conf.NDragProp.get("StudyReport");
		    if(prop.c == Coord.z) {
			Coord defaultPos = new Coord(UI.scale(200), UI.scale(200));
			ngui.studyReportWidget.c = defaultPos;
			ngui.studyReportWidget.target_c = defaultPos;
		    }

		    ngui.studyReportWidget.show();
		} else {
		    if(ngui.studyReportWidget.content instanceof nurgling.widgets.NStudyReport) {
			((nurgling.widgets.NStudyReport)ngui.studyReportWidget.content).study = child;
			ngui.studyReportWidget.show();
		    }
		}
	    }
	} else {
	    super.addchild(child, args);
	}
    }
}
