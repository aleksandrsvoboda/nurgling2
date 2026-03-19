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
    private static final Color INFO_BG = new Color(0x1C, 0x25, 0x26);
    private static final int INFO_W = UI.scale(267);
    private static final int INFO_H = UI.scale(348);
    private static final int ENTRIES_W = UI.scale(265);
    private static final int ENTRIES_H = UI.scale(297);
    private static final int SECTION_GAP = UI.scale(17);
    private static final int TITLE_GAP = UI.scale(5);
    private static final int IMG_TEXT_GAP = 11;
    private static final int TEXT_TEXT_GAP = 9;
    private static final int TEXT_W = 227;

    private static final Coord CREDO_IMG_SZ = UI.scale(new Coord(80, 100));
    private static final Coord SKILL_IMG_SZ = UI.scale(new Coord(40, 40));

    private static final Text.Foundry titleFnd = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold(), 12, Color.WHITE).aa(true);

    private static final java.awt.Font descFont =
	nurgling.conf.FontSettings.getOpenSans().deriveFont(
	    (float)Math.floor(UI.scale(11.0)));

    private static final RichText.Foundry descFnd = new RichText.Foundry(
	RichText.IMAGESRC, RichText.ImageSource.legacy,
	TextAttribute.FONT, descFont).aa(true);

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

	// Find actual visible bottom of the image (skip transparent padding)
	int visibleBottom = scaledImg.getHeight();
	outer:
	for(int row = scaledImg.getHeight() - 1; row >= 0; row--) {
	    for(int col = 0; col < scaledImg.getWidth(); col++) {
		if((scaledImg.getRGB(col, row) & 0xFF000000) != 0) {
		    visibleBottom = row + 1;
		    break outer;
		}
	    }
	}
	int titleX = imgSz.x + UI.scale(10);

	int y = visibleBottom + IMG_TEXT_GAP;

	RichText bonusRt = null;
	RichText descRt = null;
	int bonusY = 0, descY = 0;

	if(reorderBonuses && !pagText.isEmpty()) {
	    // Split pagina into bonuses (colored) and description (plain)
	    int colIdx = pagText.indexOf("$col[");
	    String bonuses = null, description = null;
	    if(colIdx > 0) {
		description = pagText.substring(0, colIdx).trim();
		bonuses = pagText.substring(colIdx).trim();
	    } else if(colIdx == 0) {
		bonuses = pagText.trim();
	    } else {
		description = pagText.trim();
	    }

	    // Strip leading/trailing newlines inside $col[]{...} blocks
	    if(bonuses != null) {
		bonuses = bonuses.replace("{\n", "{").replace("\n}", "}");
	    }

	    // Render bonuses block
	    if(bonuses != null && !bonuses.isEmpty()) {
		bonusRt = descFnd.render(resdoc(res, bonuses), TEXT_W);
		bonusY = y;
		y += bonusRt.sz().y;
	    }

	    // 15px baseline-to-top: 9px gap + ~6px descent = 15px visual
	    if(bonusRt != null && description != null && !description.isEmpty())
		y += TEXT_TEXT_GAP;

	    // Render description block
	    if(description != null && !description.isEmpty()) {
		descRt = descFnd.render(resdoc(res, description), TEXT_W);
		descY = y;
		y += descRt.sz().y;
	    }
	} else {
	    // Single block: extra + pagina
	    StringBuilder buf = new StringBuilder();
	    if(extra != null && !extra.isEmpty()) {
		buf.append(extra);
		buf.append("\n\n");
	    }
	    if(!pagText.isEmpty())
		buf.append(pagText);

	    if(buf.length() > 0) {
		descRt = descFnd.render(resdoc(res, buf.toString()), TEXT_W);
		descY = y;
		y += descRt.sz().y;
	    }
	}

	BufferedImage result = TexI.mkbuf(new Coord(TEXT_W, y));
	Graphics2D g = result.createGraphics();
	g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
	    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

	// Find first visible row in title to align text top with image top
	int titleAdj = 0;
	findTitle:
	for(int row = 0; row < titleLine.img.getHeight(); row++) {
	    for(int col = 0; col < titleLine.img.getWidth(); col++) {
		if((titleLine.img.getRGB(col, row) & 0xFF000000) != 0) {
		    titleAdj = row;
		    break findTitle;
		}
	    }
	}
	g.drawImage(scaledImg, 0, 0, null);
	g.drawImage(titleLine.img, titleX, -titleAdj, null);

	if(bonusRt != null)
	    g.drawImage(bonusRt.img, 0, bonusY, null);
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
	    @Override
	    public Coord marg() { return UI.scale(15, 15); }
	}, prev.pos("bl").add(nbtl.x, TITLE_GAP + nbtl.y));
	NFrame.around(this, Collections.singletonList(info));

	// Section 2: "Entries" — dark background, no orange border
	int entriesX = INFO_W + SECTION_GAP;
	prev = add(new Img(catf.render(L10n.get("char.skill.entries")).tex()), entriesX, 0);
	Coord entriesPos = prev.pos("bl").add(0, TITLE_GAP);
	add(new Widget(new Coord(ENTRIES_W, ENTRIES_H)) {
	    public void draw(GOut g) {
		g.chcolor(INFO_BG);
		g.frect(Coord.z, sz);
		g.chcolor();
		super.draw(g);
	    }
	}, entriesPos);
	int ep = UI.scale(10);
	Tabs lists = new Tabs(entriesPos, new Coord(ENTRIES_W, 0), this);

	int paddedW = ENTRIES_W - ep * 2;
	int paddedH = ENTRIES_H - ep * 2;
	int buyH = UI.scale(44);
	int gridGap = UI.scale(5);
	int skGridH = paddedH - buyH - gridGap;

	// Skills tab
	Tabs.Tab sktab = lists.add();
	{
	    skg = sktab.add(new SkillGrid(new Coord(paddedW, skGridH)) {
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
		}, ep, ep);
	    skg.catf = attrf;
	    Widget bf = sktab.adda(new Widget(new Coord(paddedW, buyH)), ep, ENTRIES_H - ep, 0.0, 1.0);
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
			m = UI.scale(10);
		    }
		    protected int topPad() { return UI.scale(5); }
		    protected int labelGap() { return UI.scale(5); }
		    protected int textYAdj() { return UI.scale(5); }
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
	    this.exps = exps.add(new ExpGrid(new Coord(paddedW, paddedH)) {
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
		}, ep, ep);
	}
	lists.pack();
	int boxBottom = info.c.y + info.sz.y + NFrame.nbox.bbroff().y;
	int btnY = boxBottom - Button.hs;
	addhlp(new Coord(entriesPos.x, btnY), UI.scale(5), ENTRIES_W,
	      lists.new TabButton(0, L10n.get("char.skill.tab_skills"), sktab),
	      lists.new TabButton(0, L10n.get("char.skill.tab_credos"), credos),
	      lists.new TabButton(0, L10n.get("char.skill.tab_lore"),   exps));
	pack();
    }
}
