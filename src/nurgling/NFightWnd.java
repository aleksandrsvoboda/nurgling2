package nurgling;

import haven.*;
import java.util.*;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import static haven.CharWnd.*;
import static haven.PUtils.*;
import static haven.Inventory.invsq;
import nurgling.conf.NCombatCategory;
import nurgling.i18n.L10n;

public class NFightWnd extends FightWnd {
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
    private static final int CATEGORY_W = UI.scale(31);
    private static final int CATEGORY_H = UI.scale(28);
    private static final int CATEGORY_ICON = UI.scale(20);
    private static final int CATEGORY_GAP = UI.scale(1);
    /* Washed over the tab's normal background rather than replacing it, so the selected tab reads
     * as the same surface as the list below it. Matches the save-slot border treatment. */
    private static final Color CATEGORY_SEL = new Color(233, 156, 84, 48);
    private static final Color UPGRADE_GLOW = new Color(28, 255, 73, 150);

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

    /* None of these may carry a field initializer: buildLayout() runs from the FightWnd
     * constructor, which is before this subclass's field initializers, so anything assigned here
     * would silently overwrite what buildLayout() set. Everything below is created on first use
     * instead, all of it from tick/draw, i.e. after construction. */
    private NCombatCategory selcat;
    private List<Action> filtcache;
    private List<Action> filtsrc;
    private List<Action> othersrc;
    private boolean othersome;
    private Map<NCombatCategory, Tex> caticons;
    private Set<NCombatCategory> caticonfail;
    private Map<NCombatCategory, Tex> catletters;

    public NFightWnd(int nsave, int nact, int max) {
	super(nsave, nact, max);
    }

    private static abstract class DropWidget extends Widget implements DropTarget {
	DropWidget(Coord sz) { super(sz); }
    }

    /* One tab of the category filter. Sits on the title row beside the window title, so the
     * filter costs the move list no height. */
    private class CategoryButton extends Widget {
	private final NCombatCategory cat;
	private final int iconsz;
	private boolean hovering;

	CategoryButton(NCombatCategory cat, Coord sz, int iconsz) {
	    super(sz);
	    this.cat = cat;
	    this.iconsz = iconsz;
	}

	public void tick(double dt) {
	    if(cat == NCombatCategory.OTHER) {
		/* Every move the client knows about is classified, so a permanently empty Other
		 * tab would just look broken. It appears the day the game ships a move we have
		 * no category for -- which is the whole point of having it. */
		boolean vis = hasUnclassified();
		if(vis != visible()) {
		    if(!vis && (category() == cat))
			selectCategory(NCombatCategory.ALL);
		    show(vis);
		}
	    }
	    super.tick(dt);
	}

	public void draw(GOut g) {
	    boolean sel = (category() == cat);
	    g.chcolor(NStyle.infoBg);
	    g.frect(Coord.z, sz);
	    if(sel) {
		g.chcolor(CATEGORY_SEL);
		g.frect(Coord.z, sz);
	    }
	    g.chcolor();
	    Tex icon = caticon(cat, iconsz);
	    if(icon != null)
		g.aimage(icon, sz.div(2), 0.5, 0.5);
	    else if(iconfailed(cat))
		g.aimage(catletter(cat), sz.div(2), 0.5, 0.5);
	    int alpha = (sel || hovering) ? 255 : 128;
	    g.chcolor(NStyle.border.getRed(), NStyle.border.getGreen(), NStyle.border.getBlue(), alpha);
	    g.rect(Coord.z, sz);
	    g.chcolor();
	    super.draw(g);
	}

	public boolean mousedown(MouseDownEvent ev) {
	    if((ev.b == 1) && ev.c.isect(Coord.z, sz)) {
		selectCategory(cat);
		return(true);
	    }
	    return(super.mousedown(ev));
	}

	public boolean mousehover(MouseHoverEvent ev, boolean on) {
	    hovering = on;
	    return(false);
	}

	public Object tooltip(Coord c, Widget prev) {
	    return(cat.label());
	}
    }

    /* The + under a slot, glowing while pressing it would actually do something. */
    private class UpgradeButton extends NCloseButton {
	private final int slot;
	private boolean glowing;

	UpgradeButton(int slot) {
	    super(NStyle.plusbtni[0], NStyle.plusbtni[1], NStyle.plusbtni[2]);
	    this.slot = slot;
	}

	public void tick(double dt) {
	    Action act = order[slot];
	    boolean next = (act != null) && (act.u < act.a);
	    if(next != glowing) {
		glowing = next;
		redraw();
	    }
	    super.tick(dt);
	}

	public void draw(BufferedImage buf) {
	    super.draw(buf);
	    if(glowing) {
		Graphics2D g = buf.createGraphics();
		/* SrcAtop tints only what the button already painted, so the glow follows the
		 * plus glyph instead of filling its bounding box. */
		g.setComposite(AlphaComposite.SrcAtop);
		g.setColor(UPGRADE_GLOW);
		g.fillRect(0, 0, buf.getWidth(), buf.getHeight());
		g.dispose();
	    }
	}
    }

    private NCombatCategory category() {
	return((selcat == null) ? NCombatCategory.ALL : selcat);
    }

    private boolean iconfailed(NCombatCategory cat) {
	return((caticonfail != null) && caticonfail.contains(cat));
    }

    /**
     * Tab icon, or null while the resource is still on its way. Loaded here rather than in the
     * button's constructor for two reasons: a missing resource throws NoSuchResourceException,
     * which is not a Loading and would take the UI thread down with it, and the window is built
     * in contexts (tests, headless) that have no resource pool at all.
     */
    private Tex caticon(NCombatCategory cat, int isz) {
	if(caticons == null)
	    caticons = new EnumMap<>(NCombatCategory.class);
	Tex ret = caticons.get(cat);
	if((ret != null) || iconfailed(cat))
	    return(ret);
	try {
	    Resource.Pool pool = cat.localIcon() ? Resource.local() : Resource.remote();
	    BufferedImage img = pool.load(cat.iconRes()).get().flayer(Resource.imgc).scaled();
	    caticons.put(cat, ret = new TexI(convolvedown(img, Coord.of(isz, isz), iconfilter)));
	} catch(Loading l) {
	    /* Not fetched yet: the tab draws empty and picks the icon up on a later frame. */
	} catch(RuntimeException e) {
	    if(caticonfail == null)
		caticonfail = EnumSet.noneOf(NCombatCategory.class);
	    caticonfail.add(cat);
	}
	return(ret);
    }

    /** Fallback for a tab whose icon resource is gone: the first letter of its name. */
    private Tex catletter(NCombatCategory cat) {
	if(catletters == null)
	    catletters = new EnumMap<>(NCombatCategory.class);
	Tex ret = catletters.get(cat);
	if(ret == null) {
	    String label = cat.label();
	    String ch = ((label == null) || label.isEmpty()) ? "?" : label.substring(0, 1).toUpperCase();
	    catletters.put(cat, ret = NStyle.nattrf.render(ch).tex());
	}
	return(ret);
    }

    /**
     * The moves the current tab shows. Called from the list's tick, so the result is cached and
     * only rebuilt when the tab changes or the server replaces the move list. A partial result --
     * one built while some move's resource was still loading -- is returned but not cached, so
     * the move reappears as soon as its name is known.
     */
    private List<Action> filteredActions() {
	NCombatCategory cat = category();
	if(cat == NCombatCategory.ALL)
	    return(acts);
	if((filtcache != null) && (filtsrc == acts))
	    return(filtcache);
	List<Action> filtered = new ArrayList<>(acts.size());
	boolean complete = true;
	for(Action act : acts) {
	    String name;
	    try {
		name = act.res.get().name;
	    } catch(Loading l) {
		complete = false;
		continue;
	    } catch(RuntimeException e) {
		/* Unloadable move: it stays reachable under All, which is where it can still be
		 * dragged onto a slot. */
		continue;
	    }
	    if(cat.matches(name))
		filtered.add(act);
	}
	if(complete) {
	    filtcache = filtered;
	    filtsrc = acts;
	}
	return(filtered);
    }

    /**
     * Whether any of the character's moves falls outside every named category -- that is, whether
     * the Other tab has anything to show. Recomputed only when the server replaces the move list,
     * or while a move's resource has yet to resolve.
     */
    private boolean hasUnclassified() {
	if(othersrc == acts)
	    return(othersome);
	boolean any = false, complete = true;
	for(Action act : acts) {
	    String name;
	    try {
		name = act.res.get().name;
	    } catch(Loading l) {
		complete = false;
		continue;
	    } catch(RuntimeException e) {
		continue;
	    }
	    if(NCombatCategory.OTHER.matches(name)) {
		any = true;
		break;
	    }
	}
	othersome = any;
	if(any || complete)
	    othersrc = acts;
	return(any);
    }

    private void selectCategory(NCombatCategory cat) {
	if(cat == null)
	    cat = NCombatCategory.ALL;
	if(selcat == cat)
	    return;
	selcat = cat;
	filtcache = null;
	filtsrc = null;
	if(actlist != null) {
	    actlist.change(null);
	    /* The old scroll offset means nothing in a list of a different length, and reset()
	     * alone leaves it untouched. */
	    actlist.scrollval(0);
	    actlist.reset();
	}
    }

    /**
     * Select a move in the list and scroll to it, widening the filter first if the current tab
     * hides it -- otherwise clicking an equipped slot would silently select nothing.
     */
    private void showAction(Action act) {
	if((act != null) && (category() != NCombatCategory.ALL) && !filteredActions().contains(act))
	    selectCategory(NCombatCategory.ALL);
	actlist.change(act);
	actlist.display();
    }

    public void destroy() {
	if(caticons != null) {
	    for(Tex tex : caticons.values())
		tex.dispose();
	    caticons = null;
	}
	if(catletters != null) {
	    for(Tex tex : catletters.values())
		tex.dispose();
	    catletters = null;
	}
	super.destroy();
    }

    /* Move-list row: must be a DTarget so that interacting with a held item
     * (right-click with e.g. a parchment in hand) reaches the move. */
    private static abstract class MoveItemWidget extends Widget implements DTarget {
	MoveItemWidget(Coord sz) { super(sz); }
    }

    private BufferedImage renderMoveInfo(Action act, int width) {
	Resource res = act.res.get();
	Coord imgSz = UI.scale(new Coord(76, 76));
	BufferedImage scaledImg = convolvedown(act.rendericon(), imgSz, iconfilter);
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
	Widget prev = add(CharWnd.settip(new Img(NStyle.ncatf.render(L10n.get("char.fight.title")).tex()), "gfx/hud/chr/tips/combat"), 0, 0);
	int contentY = prev.pos("bl").y + TITLE_GAP;

	// --- Section 1: Description box (left) — NFrame orange border ---
	ImageInfoBox infoBox = add(new ImageInfoBox(new Coord(descInnerW, descInnerH)) {
	    @Override
	    public void drawbg(GOut g) {
		g.chcolor(NStyle.infoBg);
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

	/* Category tabs, centered over the move list and tucked into the title row: the strip
	 * takes its height out of the space beside the title, not out of the list. */
	selcat = NCombatCategory.ALL;
	NCombatCategory[] cats = NCombatCategory.values();
	/* Centered on the tabs that are normally up. Other, the last one, hides itself while it
	 * has nothing to show, and grows the row to the right when it does -- there is room for
	 * it inside the list's width either way. */
	int catShown = cats.length - 1;
	int catRowW = catShown * CATEGORY_W + (catShown - 1) * CATEGORY_GAP;
	int catBottom = contentY - UI.scale(2);
	int catH = Math.min(CATEGORY_H, Math.max(UI.scale(12), catBottom));
	int catIconSz = Math.max(UI.scale(8), Math.min(CATEGORY_ICON, catH - UI.scale(4)));
	/* Centered over the list, but pushed clear of the title should a translation run long --
	 * never past the list's own right edge, so the row can't widen the window. */
	int catX = Math.min(Math.max(movesX + (MOVES_W - catRowW) / 2, prev.pos("ur").x + UI.scale(6)),
			    movesX + MOVES_W - catRowW);
	int catY = Math.max(0, catBottom - catH);
	for(int i = 0; i < cats.length; i++) {
	    add(new CategoryButton(cats[i], new Coord(CATEGORY_W, catH), catIconSz),
		catX + i * (CATEGORY_W + CATEGORY_GAP), catY);
	}

	add(new Widget(new Coord(MOVES_W, MOVES_H)) {
	    public void draw(GOut g) {
		g.chcolor(NStyle.infoBg);
		g.frect(Coord.z, sz);
		g.chcolor();
		super.draw(g);
	    }
	}, movesX, contentY);
	actlist = add(new Actions(new Coord(MOVES_W, MOVES_H), MOVE_ITEM_H) {
	    @Override
	    protected List<Action> items() {
		return filteredActions();
	    }

	    @Override
	    protected void drawslot(GOut g, Action item, int idx, Area area) {
		g.chcolor(((idx % 2) == 0) ? NStyle.rowEven : NStyle.rowOdd);
		g.frect2(area.ul, area.br);
		g.chcolor();
		if((sel != null) && (sel == item))
		    drawsel(g, item, idx, area);
	    }

	    @Override
	    protected Widget makeitem(Action act, int idx, Coord sz) {
		Actions al = this;
		return new MoveItemWidget(sz) {
		    private UI.Grab mgrab;
		    private Coord dp;
		    private final Label use;
		    private int pu = -1, pa = -1;
		    {
			use = adda(new Label("0/0", NStyle.nattrf), sz.x - UI.scale(5), sz.y / 2, 1.0, 0.5);
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
		    public boolean iteminteract(Coord cc, Coord ul) {
			itemact(act);
			return true;
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

	Widget skillBar = add(new DropWidget(new Coord(saveRowW, isz.y)) {
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
			showAction(order[s]);
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

	    public boolean dropthing(Coord c, Object thing) {
		if(thing instanceof Action) {
		    Action act = (Action)thing;
		    int s = slotAt(c);
		    if(s < 0)
			return false;
		    if(order[s] != act) {
			int cp = findorder(act);
			if(cp >= 0)
			    order[cp] = order[s];
			if(order[s] != null) {
			    if(cp < 0)
				order[s].u(0);
			}
			order[s] = act;
			if(act.u < 1)
			    act.u(1);
		    }
		    return true;
		}
		return false;
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
	    Widget addw = adda(new UpgradeButton(si).action(() -> {
		Action act = order[si];
		if(act != null) {
		    int nu = Utils.clip(act.u + 1, 0, act.a);
		    act.u(nu);
		}
	    }), cx + BTN_BTN_GAP / 2, btnY, 0.0, 0.0);
	    plusH = Math.max(plusH, addw.sz.y);
	}

	// "Used X/Y" label — 22px gap, top-aligned with skill bar (offset for text leading).
	// Rendered at its widest before pack() so the window reserves room for the real text,
	// which only arrives later through recount().
	count = add(new Label(String.format(L10n.get("char.fight.used"), maxact, maxact), NStyle.nattrf),
		    skillBar.pos("ur").adds(22, -5));

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
		    g.chcolor(NStyle.infoBg);
		    g.frect(Coord.z, sz);
		    g.chcolor();

		    int bw = Math.max(2, UI.scale(2));
		    int alpha = (n == usesave) ? 255 : 128;
		    g.chcolor(NStyle.border.getRed(), NStyle.border.getGreen(), NStyle.border.getBlue(), alpha);
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
			Text.Line t1 = NStyle.nattrf.render(line1);
			if(line2.isEmpty()) {
			    g.aimage(t1.tex(), sz.div(2), 0.5, 0.5);
			} else {
			    Text.Line t2 = NStyle.nattrf.render(line2);
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
	int btnX = saveRowW + UI.scale(22) + 3;
	add(new Button(UI.scale(104), L10n.get("char.fight.load"), false).action(() -> {
		    load(savelist.sel);
		    use(savelist.sel);
	}), btnX, saveRowY - 1);
	adda(new Button(UI.scale(104), L10n.get("char.fight.save"), false).action(() -> {
		    if(savelist.sel < 0) {
			getparent(GameUI.class).error(L10n.get("char.fight.no_save_selected"));
		    } else {
			save(savelist.sel);
			use(savelist.sel);
		    }
	}), btnX, saveRowY + SAVE_H + 1, 0.0, 1.0);
	pack();
	recount();
    }
}
