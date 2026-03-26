package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import static haven.CharWnd.*;
import static haven.PUtils.*;
import static haven.Inventory.invsq;
import nurgling.i18n.L10n;

public class NFightWnd extends FightWnd {
    private static final Color ROW_EVEN = new Color(51, 62, 64);
    private static final Color ROW_ODD  = new Color(40, 52, 54);
    private static final Color INFO_BG  = new Color(0x1C, 0x25, 0x26);

    private static final int DESC_W = UI.scale(267);
    private static final int DESC_H = UI.scale(208);
    private static final int MOVES_W = UI.scale(267);
    private static final int MOVES_H = UI.scale(208);
    private static final int SAVE_W = UI.scale(77);
    private static final int SAVE_H = UI.scale(60);
    private static final int MOVE_ITEM_H = UI.scale(26);

    private static final int TITLE_GAP = UI.scale(5);
    private static final int DESC_MOVES_GAP = UI.scale(17);
    private static final int DESC_SKILL_GAP = UI.scale(15);
    private static final int SLOT_BTN_GAP = UI.scale(4);
    private static final int BTN_BTN_GAP = UI.scale(3);
    private static final int BTN_SAVE_GAP = UI.scale(15);
    private static final int SAVE_GAP = UI.scale(9);
    private static final Coord NUM_BOX = UI.scale(new Coord(13, 14));

    private static final Text.Foundry titleFnd = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold(), 14, Color.WHITE).aa(true);

    private static final java.awt.Font descFont =
	nurgling.conf.FontSettings.getOpenSans().deriveFont(
	    (float)Math.floor(UI.scale(11.0)));

    private static final RichText.Foundry descFnd = new RichText.Foundry(
	RichText.IMAGESRC, RichText.ImageSource.legacy,
	TextAttribute.FONT, descFont).aa(true);

    private static final Text.Foundry numFnd = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold(), 14, Color.WHITE).aa(true);

    public NFightWnd(int nsave, int nact, int max) {
	super(nsave, nact, max);
    }

    private BufferedImage renderMoveInfo(Action act, int width) {
	Resource res = act.res.get();
	BufferedImage scaledImg = act.rendericon();
	Coord imgSz = Utils.imgsz(scaledImg);
	String title = res.flayer(Resource.tooltip).text();
	Text.Line titleLine = titleFnd.render(title);

	Resource.Pagina pag = res.layer(Resource.pagina);
	String pagText = (pag != null) ? pag.text : "";

	int visibleBottom = imgSz.y;
	outer:
	for(int row = imgSz.y - 1; row >= 0; row--) {
	    for(int col = 0; col < imgSz.x; col++) {
		if((scaledImg.getRGB(col, row) & 0xFF000000) != 0) {
		    visibleBottom = row + 1;
		    break outer;
		}
	    }
	}
	int titleX = imgSz.x + UI.scale(10);
	int y = visibleBottom + 11;

	RichText descRt = null;
	if(!pagText.isEmpty()) {
	    descRt = descFnd.render(resdoc(res, pagText), width);
	    y += descRt.sz().y;
	}

	BufferedImage result = TexI.mkbuf(new Coord(width, y));
	Graphics2D g = result.createGraphics();
	g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
	    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

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

	if(descRt != null)
	    g.drawImage(descRt.img, 0, visibleBottom + 11, null);

	g.dispose();
	return result;
    }

    @Override
    protected void buildLayout() {
	Coord nbisz = NFrame.nbox.bisz();
	Coord nbtl = NFrame.nbox.btloff();
	int descInnerW = DESC_W - nbisz.x;
	int descInnerH = DESC_H - nbisz.y;

	// Title
	Widget prev = add(CharWnd.settip(new Img(CharWnd.catf.render(L10n.get("char.fight.title")).tex()), "gfx/hud/chr/tips/combat"), 0, 0);
	int contentY = prev.pos("bl").y + TITLE_GAP;

	// --- Section 1: Description box (left) — NFrame orange border ---
	ImageInfoBox infoBox = add(new ImageInfoBox(new Coord(descInnerW, descInnerH)) {
	    @Override
	    public void drawbg(GOut g) {
		g.chcolor(INFO_BG);
		g.frect(Coord.z, sz);
		g.chcolor();
	    }
	    @Override
	    public Coord marg() { return UI.scale(15, 15); }
	}, nbtl.x, contentY + nbtl.y);
	info = infoBox;
	NFrame.around(this, Collections.singletonList(info));

	// --- Section 2: Moves box (right) — background matches Actions exactly ---
	int movesX = DESC_W + DESC_MOVES_GAP;
	add(new Widget(new Coord(MOVES_W, MOVES_H)) {
	    public void draw(GOut g) {
		g.chcolor(INFO_BG);
		g.frect(Coord.z, sz);
		g.chcolor();
		super.draw(g);
	    }
	}, movesX, contentY);
	actlist = add(new Actions(new Coord(MOVES_W, MOVES_H), MOVE_ITEM_H) {
	    @Override
	    protected void drawslot(GOut g, Action item, int idx, Area area) {
		g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		g.frect2(area.ul, area.br);
		g.chcolor();
		if((sel != null) && (sel == item))
		    drawsel(g, item, idx, area);
	    }

	    @Override
	    protected Widget makeitem(Action act, int idx, Coord sz) {
		Actions al = this;
		return new Widget(sz) {
		    private UI.Grab mgrab;
		    private Coord dp;
		    private final Label use;
		    private int pu = -1, pa = -1;
		    {
			use = adda(new Label("0/0", attrf), sz.x - UI.scale(5), sz.y / 2, 1.0, 0.5);
			add(IconText.of(Coord.of(use.c.x - UI.scale(2), sz.y), act::rendericon,
			    () -> act.res.get().flayer(Resource.tooltip).text()), Coord.z);
		    }
		    public void tick(double dt) {
			if(act.u != pu || act.a != pa)
			    use.settext(String.format("%d/%d", pu = act.u, pa = act.a));
			super.tick(dt);
		    }
		    public boolean mousedown(MouseDownEvent ev) {
			if(ev.propagate(this) || super.mousedown(ev)) return true;
			if(ev.b == 1) {
			    al.change(act);
			    mgrab = ui.grabmouse(this);
			    dp = ev.c;
			}
			return true;
		    }
		    public void mousemove(MouseMoveEvent ev) {
			super.mousemove(ev);
			if(mgrab != null && ev.c.dist(dp) > 5) {
			    mgrab.remove();
			    mgrab = null;
			    al.drag(act);
			}
		    }
		    public boolean mouseup(MouseUpEvent ev) {
			if(mgrab != null && ev.b == 1) {
			    mgrab.remove();
			    mgrab = null;
			    return true;
			}
			return super.mouseup(ev);
		    }
		};
	    }

	    @Override
	    public void change(Action act) {
		if(act != null)
		    infoBox.set(() -> new TexI(renderMoveInfo(act, infoBox.sz.x - UI.scale(20))));
		else if(sel != null)
		    infoBox.set((Tex)null);
		sel = act;
	    }
	}, movesX, contentY);

	// --- Compute save row width (drives skill bar width) ---
	int saveRowW = nsave * SAVE_W + (nsave - 1) * SAVE_GAP;

	// --- Section 3: Custom skill bar evenly spaced across save row width ---
	int skillBarY = contentY + DESC_H + DESC_SKILL_GAP;
	Coord isz = invsq.sz();
	int nslots = order.length;

	Widget skillBar = add(new Widget(new Coord(saveRowW, isz.y)) {
	    private UI.Grab grab;
	    private Action drag;
	    private Coord dp;

	    private Coord slotPos(int i) {
		// Distribute rounding evenly so last slot right edge == saveRowW
		int x = (nslots > 1) ? (int)((long)i * (saveRowW - isz.x) / (nslots - 1)) : 0;
		return Coord.of(x, 0);
	    }

	    private int slotAt(Coord c) {
		for(int i = 0; i < nslots; i++) {
		    if(c.isect(slotPos(i), isz))
			return i;
		}
		return -1;
	    }

	    public void draw(GOut g) {
		for(int i = 0; i < nslots; i++) {
		    Coord sc = slotPos(i);
		    g.image(invsq, sc);
		    Action act = order[i];
		    try {
			if(act != null) {
			    Tex tex = act.res.get().flayer(Resource.imgc).tex();
			    g.image(tex, sc.add(UI.scale(1), UI.scale(1)));
			    // Numeric overlay
			    Coord boxTL = sc.add(isz.x - NUM_BOX.x, 0);
			    g.chcolor(0, 0, 0, 204);
			    g.frect(boxTL, NUM_BOX);
			    g.chcolor();
			    Text.Line nt = numFnd.render(Integer.toString(act.u));
			    g.aimage(nt.tex(), boxTL.add(NUM_BOX.div(2)).add(1, -1), 0.5, 0.5);
			}
		    } catch(Loading l) {}
		    // Key label
		    g.chcolor(156, 180, 158, 255);
		    g.aimage(Text.render(keys[i]).tex(), sc.add(isz.sub(UI.scale(2), 0)), 1, 1);
		    g.chcolor();
		}
		super.draw(g);
	    }

	    public boolean mousedown(MouseDownEvent ev) {
		if(ev.b == 1) {
		    int s = slotAt(ev.c);
		    if(s >= 0) {
			actlist.change(order[s]);
			actlist.display();
			if(order[s] != null) {
			    grab = ui.grabmouse(this);
			    drag = order[s];
			    dp = ev.c;
			}
			return true;
		    }
		} else if(ev.b == 3) {
		    int s = slotAt(ev.c);
		    if(s >= 0 && order[s] != null) {
			order[s].u(0);
			order[s] = null;
			return true;
		    }
		}
		return super.mousedown(ev);
	    }

	    public void mousemove(MouseMoveEvent ev) {
		super.mousemove(ev);
		if(dp != null && ev.c.dist(dp) > 5) {
		    if(grab != null) { grab.remove(); grab = null; }
		    actlist.drag(drag);
		    drag = null;
		    dp = null;
		}
	    }

	    public boolean mouseup(MouseUpEvent ev) {
		if(grab != null) {
		    grab.remove();
		    grab = null;
		    drag = null;
		    dp = null;
		    return true;
		}
		return super.mouseup(ev);
	    }
	}, 0, skillBarY);

	// Make skill bar a drop target by adding DropTarget support
	// (DropTarget.dropthing is checked by the framework on the widget tree)

	// +/- buttons below each slot
	int btnY = skillBarY + isz.y + SLOT_BTN_GAP;
	int plusH = 0;
	for(int i = 0; i < nslots; i++) {
	    int slotX = (nslots > 1) ? (int)((long)i * (saveRowW - isz.x) / (nslots - 1)) : 0;
	    int cx = slotX + isz.x / 2;
	    final int si = i;
	    Widget sub = adda(new NCloseButton(NStyle.minusbtni[0], NStyle.minusbtni[1], NStyle.minusbtni[2]).action(() -> {
		Action act = order[si];
		if(act != null) {
		    int nu = act.u - 1;
		    if(nu <= 0) { act.u(0); order[si] = null; }
		    else act.u(nu);
		}
	    }), cx - BTN_BTN_GAP / 2, btnY, 1.0, 0.0);
	    Widget addw = adda(new NCloseButton(NStyle.plusbtni[0], NStyle.plusbtni[1], NStyle.plusbtni[2]).action(() -> {
		Action act = order[si];
		if(act != null) {
		    int nu = Utils.clip(act.u + 1, 0, act.a);
		    act.u(nu);
		}
	    }), cx + BTN_BTN_GAP / 2, btnY, 0.0, 0.0);
	    plusH = Math.max(plusH, addw.sz.y);
	}

	// "Used X/Y" label — 22px gap, top-aligned with skill bar (offset for text leading)
	count = add(new Label("", attrf), skillBar.pos("ur").adds(22, -5));

	// --- Section 4: Save slots as boxes ---
	int saveRowY = btnY + plusH + BTN_SAVE_GAP;

	// Hidden savelist for protocol handling
	savelist = add(new Savelist(Coord.of(1, 1)) {
	    @Override protected void drawslot(GOut g, Integer i, int idx, Area a) {}
	}, -10, -10);

	// Visual save slot boxes
	for(int i = 0; i < nsave; i++) {
	    final int n = i;
	    int sx = i * (SAVE_W + SAVE_GAP);
	    add(new Widget(new Coord(SAVE_W, SAVE_H)) {
		public void draw(GOut g) {
		    g.chcolor(INFO_BG);
		    g.frect(Coord.z, sz);
		    g.chcolor();

		    int bw = Math.max(2, UI.scale(2));
		    int alpha = (n == usesave) ? 255 : 128;
		    g.chcolor(233, 156, 84, alpha);
		    g.frect(Coord.z, new Coord(sz.x, bw));
		    g.frect(new Coord(0, sz.y - bw), new Coord(sz.x, bw));
		    g.frect(Coord.z, new Coord(bw, sz.y));
		    g.frect(new Coord(sz.x - bw, 0), new Coord(bw, sz.y));
		    g.chcolor();

		    if(saves[n] != null) {
			String txt = saves[n].text;
			String line1, line2;
			int sp = txt.indexOf(' ');
			if(sp > 0) {
			    line1 = txt.substring(0, sp);
			    line2 = txt.substring(sp + 1);
			} else {
			    line1 = txt;
			    line2 = "";
			}
			Text.Line t1 = attrf.render(line1);
			if(line2.isEmpty()) {
			    g.aimage(t1.tex(), sz.div(2), 0.5, 0.5);
			} else {
			    Text.Line t2 = attrf.render(line2);
			    int gap = UI.scale(2);
			    int totalH = t1.sz().y + gap + t2.sz().y;
			    int y0 = (sz.y - totalH) / 2;
			    g.aimage(t1.tex(), Coord.of(sz.x / 2, y0), 0.5, 0.0);
			    g.aimage(t2.tex(), Coord.of(sz.x / 2, y0 + t1.sz().y + gap), 0.5, 0.0);
			}
		    }

		    if(savelist.sel != null && savelist.sel == n) {
			int bw2 = Math.max(2, UI.scale(2));
			g.chcolor(255, 255, 0, 64);
			g.frect(Coord.of(bw2, bw2), sz.sub(bw2 * 2, bw2 * 2));
			g.chcolor();
		    }
		}

		private Coord lc = null;
		private double lt = 0;
		public boolean mousedown(MouseDownEvent ev) {
		    if(ev.b == 1) {
			double now = Utils.rtime();
			savelist.change(n);
			if(((now - lt) < 0.5) && lc != null && (ev.c.dist(lc) < 10) && (saves[n] != unused)) {
			    if(n != usesave) {
				load(n);
				use(n);
			    }
			} else {
			    lt = now;
			    lc = ev.c;
			}
			return true;
		    }
		    return super.mousedown(ev);
		}
	    }, sx, saveRowY);
	}

	// Load / Save buttons — 22px gap, Load top-aligned, Save bottom-aligned with save slots
	int btnX = saveRowW + UI.scale(22);
	add(new Button(UI.scale(104), L10n.get("char.fight.load"), false).action(() -> {
		    load(savelist.sel);
		    use(savelist.sel);
	}), btnX, saveRowY);
	adda(new Button(UI.scale(104), L10n.get("char.fight.save"), false).action(() -> {
		    if(savelist.sel < 0) {
			getparent(GameUI.class).error(L10n.get("char.fight.no_save_selected"));
		    } else {
			save(savelist.sel);
			use(savelist.sel);
		    }
	}), btnX, saveRowY + SAVE_H, 0.0, 1.0);
	pack();
    }
}
