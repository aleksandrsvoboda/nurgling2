package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import static haven.CharWnd.*;
import static haven.PUtils.*;
import nurgling.i18n.L10n;

public class NSkillWnd extends SkillWnd {
    private static final Color INFO_BG = new Color(22, 39, 51);
    private static final int INFO_W = UI.scale(267);
    private static final int INFO_H = UI.scale(348);
    private static final int ENTRIES_W = UI.scale(265);
    private static final int ENTRIES_H = UI.scale(297);
    private static final int SECTION_GAP = UI.scale(15);
    private static final int TITLE_GAP = UI.scale(10);
    private static final int IMG_GAP = UI.scale(10);

    private static final Coord CREDO_IMG_SZ = UI.scale(new Coord(80, 100));
    private static final Coord SKILL_IMG_SZ = UI.scale(new Coord(40, 40));

    private static final Text.Foundry titleFnd = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold(), 12, Color.WHITE).aa(true);

    private static final RichText.Foundry descFnd = new RichText.Foundry(
	RichText.IMAGESRC, RichText.ImageSource.legacy,
	TextAttribute.FONT,
	nurgling.conf.FontSettings.getOpenSans().deriveFont(
	    (float)Math.floor(UI.scale(11.0)))).aa(true);

    public NSkillWnd() {
	super();
    }

    private static String reorderBonusesFirst(String pagText) {
	int colIdx = pagText.indexOf("$col[");
	if(colIdx <= 0)
	    return pagText;
	String description = pagText.substring(0, colIdx).trim();
	String bonuses = pagText.substring(colIdx).trim();
	if(description.isEmpty())
	    return pagText;
	return bonuses + "\n\n" + description;
    }

    private BufferedImage renderInfo(Resource res, Coord imgSz, String extra, boolean reorderBonuses) {
	BufferedImage resImg = res.flayer(Resource.imgc).img;
	BufferedImage scaledImg = convolvedown(resImg, imgSz, iconfilter);
	String title = res.flayer(Resource.tooltip).t;
	Text.Line titleLine = titleFnd.render(title);

	Resource.Pagina pag = res.layer(Resource.pagina);
	String pagText = (pag != null) ? pag.text : "";

	if(reorderBonuses && !pagText.isEmpty())
	    pagText = reorderBonusesFirst(pagText);

	int marg = UI.scale(10);
	int contentW = INFO_W - NFrame.nbox.bisz().x - (marg * 2);

	// Render body below header
	StringBuilder descBuf = new StringBuilder();
	if(extra != null && !extra.isEmpty()) {
	    descBuf.append(extra);
	    descBuf.append("\n\n");
	}
	if(!pagText.isEmpty())
	    descBuf.append(pagText);

	RichText descRt = null;
	if(descBuf.length() > 0) {
	    RichText.Document doc = resdoc(res, descBuf.toString());
	    descRt = descFnd.render(doc, contentW);
	}

	// Compute layout — title top-aligned with image
	int headerH = imgSz.y;
	int titleX = imgSz.x + IMG_GAP;
	int titleY = 0;
	int descY = headerH + IMG_GAP;
	int descH = (descRt != null) ? descRt.sz().y : 0;
	int totalH = descY + descH;

	BufferedImage result = TexI.mkbuf(new Coord(contentW, totalH));
	Graphics2D g = result.createGraphics();
	g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
	    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

	g.drawImage(scaledImg, 0, 0, null);
	g.drawImage(titleLine.img, titleX, titleY, null);

	if(descRt != null)
	    g.drawImage(descRt.img, 0, descY, null);

	g.dispose();
	return result;
    }

    @Override
    protected void buildLayout() {
	Widget prev;
	Coord nbisz = NFrame.nbox.bisz();
	Coord nbtl = NFrame.nbox.btloff();

	int innerW = INFO_W - nbisz.x;
	int innerH = INFO_H - nbisz.y;

	// Section 1: "Lore & Skills" info box with NFrame border
	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.skill.title")).tex()), "gfx/hud/chr/tips/skills"), Coord.z);
	ImageInfoBox info = add(new ImageInfoBox(new Coord(innerW, innerH)) {
	    @Override
	    public void drawbg(GOut g) {
		g.chcolor(INFO_BG);
		g.frect(Coord.z, sz);
		g.chcolor();
	    }
	}, prev.pos("bl").add(nbtl.x, TITLE_GAP + nbtl.y));
	NFrame.around(this, Collections.singletonList(info));

	// Section 2: "Entries" — no border
	int entriesX = INFO_W + SECTION_GAP;
	prev = add(new Img(catf.render(L10n.get("char.skill.entries")).tex()), entriesX, 0);
	Tabs lists = new Tabs(prev.pos("bl").add(0, TITLE_GAP), new Coord(ENTRIES_W, 0), this);

	int buyH = UI.scale(44);
	int gridGap = UI.scale(5);
	int skGridH = ENTRIES_H - buyH - gridGap;

	// Skills tab
	Tabs.Tab sktab = lists.add();
	{
	    skg = sktab.add(new SkillGrid(new Coord(ENTRIES_W, skGridH)) {
		    public void change(Skill sk) {
			Skill p = sel;
			super.change(sk);
			NSkillWnd.this.exps.sel = null;
			NSkillWnd.this.credos.sel = null;
			if(sk != null) {
			    info.set(() -> {
				Resource res = sk.res.get();
				String extra = (sk.cost > 0) ? "Cost: " + sk.cost : null;
				return new TexI(renderInfo(res, SKILL_IMG_SZ, extra, false));
			    });
			} else if(p != null) {
			    info.set((Tex)null);
			}
		    }
		}, 0, 0);
	    skg.catf = attrf;
	    Widget bf = sktab.adda(new Widget(new Coord(ENTRIES_W, buyH)), 0, ENTRIES_H, 0.0, 1.0);
	    Button bbtn = sktab.adda(new Button(UI.scale(50), L10n.get("char.skill.buy")).action(() -> {
			if(skg.sel != null)
			    skill.wdgmsg("buy", skg.sel.nm);
	    }), bf.pos("br").subs(10, 0).y(bf.pos("mid").y), 1.0, 0.5);
	    Label clbl = sktab.adda(new Label(L10n.get("char.skill.cost")), bf.pos("ul").adds(10, 0).y(bf.pos("mid").y), 0, 0.5);
	    sktab.adda(new RLabel<Pair<Integer, Integer>>(() -> new Pair<>(((skg.sel == null) || skg.sel.has) ? null : skg.sel.cost, this.chr.exp),
							  n -> (n.a == null) ? "N/A" : String.format("%,d / %,d LP", n.a, n.b),
							  n -> ((n.a != null) && (n.a > n.b)) ? debuff : Color.WHITE),
		       bbtn.pos("ul").subs(10, 0).y(bf.pos("mid").y), 1.0, 0.5);
	}

	// Credos tab
	Tabs.Tab credos = lists.add();
	{
	    this.credos = credos.add(new CredoGrid(new Coord(ENTRIES_W, ENTRIES_H)) {
		    {
			pcrc = new Img(attrf.render(L10n.get("char.skill.pursuing")).tex());
			ncrc = new Img(attrf.render(L10n.get("char.skill.credos_available")).tex());
			ccrc = new Img(attrf.render(L10n.get("char.skill.credos_acquired")).tex());
			prsf = attrf;
		    }
		    public void change(Credo cr) {
			Credo p = sel;
			super.change(cr);
			NSkillWnd.this.skg.sel = null;
			NSkillWnd.this.exps.sel = null;
			if(cr != null) {
			    info.set(() -> {
				Resource res = cr.res.get();
				return new TexI(renderInfo(res, CREDO_IMG_SZ, null, true));
			    });
			} else if(p != null) {
			    info.set((Tex)null);
			}
		    }
		}, 0, 0);
	}

	// Lore tab
	Tabs.Tab exps = lists.add();
	{
	    this.exps = exps.add(new ExpGrid(new Coord(ENTRIES_W, ENTRIES_H)) {
		    public void change(Experience exp) {
			Experience p = sel;
			super.change(exp);
			NSkillWnd.this.skg.sel = null;
			NSkillWnd.this.credos.sel = null;
			if(exp != null) {
			    info.set(() -> {
				Resource res = exp.res.get();
				String extra = (exp.score > 0) ?
				    String.format(L10n.get("char.skill.exp_points"), Utils.thformat(exp.score)) : null;
				return new TexI(renderInfo(res, SKILL_IMG_SZ, extra, false));
			    });
			} else if(p != null) {
			    info.set((Tex)null);
			}
		    }
		}, 0, 0);
	}
	lists.pack();
	addhlp(lists.c.add(0, lists.sz.y + UI.scale(5)), UI.scale(5), lists.sz.x,
	      lists.new TabButton(0, L10n.get("char.skill.tab_skills"), sktab),
	      lists.new TabButton(0, L10n.get("char.skill.tab_credos"), credos),
	      lists.new TabButton(0, L10n.get("char.skill.tab_lore"),   exps));
	pack();
    }
}
