package nurgling;

import haven.*;
import haven.WoundWnd.*;
import haven.res.ui.tt.attrmod.*;
import nurgling.i18n.L10n;
import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import static haven.CharWnd.*;
import static haven.PUtils.*;

public class NWoundBox extends WoundWnd.WoundBox {
    private static final Text.Foundry nameFnd = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold(), 14, Color.WHITE).aa(true);

    private static final java.awt.Font descFont =
	nurgling.conf.FontSettings.getOpenSans().deriveFont(
	    (float)Math.floor(UI.scale(11.0)));

    private static final Text.Foundry effectFnd = new Text.Foundry(descFont).aa(true);

    /* Text.Foundry(Font, int, Color) UI-scales psz itself -- pass the unscaled size. */
    private static final Text.Foundry captionFnd = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold(), 11, new Color(0x9F, 0xB0, 0xB1)).aa(true);

    private static final RichText.Foundry descFnd = new RichText.Foundry(
	RichText.IMAGESRC, RichText.ImageSource.legacy,
	TextAttribute.FONT, descFont).aa(true);

    private static final Coord EFFECT_ICON_SZ = UI.scale(new Coord(11, 11));

    /* Treatment strip metrics */
    private static final Coord SLOT_SZ  = UI.scale(new Coord(24, 24));
    private static final Coord TICON_SZ = UI.scale(new Coord(20, 20));
    private static final int SLOT_GAP   = UI.scale(6);
    private static final int ROW_GAP    = UI.scale(3);
    private static final int SEC_GAP    = UI.scale(11);
    private static final int CAP_GAP    = UI.scale(6);
    private static final int NOTE_GAP   = UI.scale(5);

    private static final Color SLOT_BG     = NStyle.rowOdd;
    private static final Color SLOT_BORDER = new Color(0x35, 0x40, 0x3F);
    private static final Color NOTE_COL    = NStyle.border;
    private static final Color NAME_COL    = new Color(0xE8, 0xE8, 0xE8);

    /* Resolved item resources, shared across all wound boxes. */
    private static final Map<String, Resource.Saved> icores = new HashMap<>();
    private static final Set<String> badres = new HashSet<>();

    /* Hover targets of the last render, in rendered-image coordinates. */
    private List<Hover> hovers = Collections.emptyList();

    private static class Hover {
	final Area area;
	final String name, note;

	Hover(Area area, String name, String note) {
	    this.area = area;
	    this.name = name;
	    this.note = note;
	}
    }

    /** A treatment with its resource resolved (or known-unresolvable). */
    private static class Treat {
	BufferedImage icon;
	String name;
	String noteShort, noteLong;
    }

    public NWoundBox(int id) {
	super(id);
    }

    @Override
    public void drawbg(GOut g) {
	g.chcolor(NStyle.infoBg);
	g.frect(Coord.z, sz);
	g.chcolor();
    }

    /**
     * Resolve an item resource, or null when the path is bad. Loading is allowed to propagate --
     * WoundBox.tick() catches it and retries, which is how the strip fills in as icons stream in.
     * A missing resource is NOT a Loading, so it has to be caught here or it kills the UI thread.
     */
    private static Resource itemres(String path) {
	synchronized(icores) {
	    if(badres.contains(path))
		return(null);
	}
	Resource.Saved sv;
	synchronized(icores) {
	    sv = icores.get(path);
	    if(sv == null)
		icores.put(path, sv = new Resource.Saved(Resource.remote(), path, -1));
	}
	try {
	    return(sv.get());
	} catch(Loading l) {
	    throw(l);
	} catch(Resource.LoadException | Resource.BadResourceException e) {
	    synchronized(icores) {
		badres.add(path);
	    }
	    System.out.println("[NWoundBox] cannot resolve treatment resource: " + path + " (" + e.getMessage() + ")");
	    return(null);
	}
    }

    private static Treat resolve(NWoundTreatments.Treatment tr) {
	Treat e = new Treat();
	if(tr.res != null) {
	    Resource res = itemres(tr.res);
	    if(res != null) {
		Resource.Image img = res.layer(Resource.imgc);
		if(img != null)
		    e.icon = convolvedown(img.scaled(), TICON_SZ, iconfilter);
		Resource.Tooltip tt = res.layer(Resource.tooltip);
		e.name = (tt != null) ? tt.t : basename(tr.res);
	    } else {
		e.name = basename(tr.res);
	    }
	} else {
	    e.name = L10n.get(tr.textKey);
	}
	if(tr.noteKey != null) {
	    e.noteShort = L10n.get(tr.noteKey + ".short");
	    e.noteLong = L10n.get(tr.noteKey + ".long");
	}
	return(e);
    }

    private static String basename(String path) {
	int i = path.lastIndexOf('/');
	return((i < 0) ? path : path.substring(i + 1));
    }

    private static void drawSlot(Graphics2D g, int x, int y, BufferedImage icon) {
	g.setColor(SLOT_BG);
	g.fillRect(x, y, SLOT_SZ.x, SLOT_SZ.y);
	g.setColor(SLOT_BORDER);
	g.drawRect(x, y, SLOT_SZ.x - 1, SLOT_SZ.y - 1);
	if(icon != null) {
	    g.drawImage(icon, x + ((SLOT_SZ.x - icon.getWidth()) / 2),
			      y + ((SLOT_SZ.y - icon.getHeight()) / 2), null);
	} else {
	    g.setColor(new Color(0x6E, 0x7C, 0x7C));
	    Text.Line q = effectFnd.render("?", new Color(0x6E, 0x7C, 0x7C));
	    g.drawImage(q.img, x + ((SLOT_SZ.x - q.sz().x) / 2),
			       y + ((SLOT_SZ.y - q.sz().y) / 2), null);
	}
    }

    @Override
    public BufferedImage renderinfo(int width) {
	Wound wnd = wound();
	List<ItemInfo> info = wnd.info();
	Coord iconSz = UI.scale(new Coord(76, 76));
	BufferedImage icon = convolvedown(wnd.icon(), iconSz, iconfilter);
	ItemInfo.Name nm = ItemInfo.find(ItemInfo.Name.class, info);
	String name = (nm != null) ? nm.str.text : "";
	Text.Line nameLine = nameFnd.render(name);

	// Scan for first visible row in name for top-alignment with icon
	int nameAdj = 0;
	findName:
	for(int row = 0; row < nameLine.img.getHeight(); row++) {
	    for(int col = 0; col < nameLine.img.getWidth(); col++) {
		if((nameLine.img.getRGB(col, row) & 0xFF000000) != 0) {
		    nameAdj = row;
		    break findName;
		}
	    }
	}

	int titleX = iconSz.x + UI.scale(10);

	// Collect AttrMod effects — two-pass for tabular alignment
	List<Mod> mods = new ArrayList<>();
	for(ItemInfo inf : info) {
	    if(inf instanceof AttrMod)
		for(Entry en : ((AttrMod)inf).tab)
		    if(en instanceof Mod)
			mods.add((Mod)en);
	}

	int iconGap = UI.scale(5);
	int valGap = UI.scale(5);
	BufferedImage[] eIcons = new BufferedImage[mods.size()];
	BufferedImage[] eNames = new BufferedImage[mods.size()];
	BufferedImage[] eVals  = new BufferedImage[mods.size()];
	int maxNameW = 0;
	int eLineH = 0;

	for(int i = 0; i < mods.size(); i++) {
	    Mod mod = mods.get(i);
	    eNames[i] = effectFnd.render(mod.attr.name()).img;
	    Color valCol = (mod.mod < 0) ? new Color(255, 128, 128) : new Color(128, 255, 128);
	    String sign = (mod.mod < 0) ? "-" : "+";
	    eVals[i] = effectFnd.render(String.format("%s%d", sign, Math.round(Math.abs(mod.mod))), valCol).img;
	    eIcons[i] = mod.attr.icon();
	    if(eIcons[i] != null)
		eIcons[i] = convolvedown(eIcons[i], EFFECT_ICON_SZ, iconfilter);
	    maxNameW = Math.max(maxNameW, eNames[i].getWidth());
	    eLineH = Math.max(eLineH, Math.max(eNames[i].getHeight(), EFFECT_ICON_SZ.y));
	}

	// Render description text (pagina)
	Resource.Pagina pag = wnd.res.get().layer(Resource.pagina);
	String pagText = (pag != null) ? pag.text : "";
	RichText descRt = null;
	if(!pagText.isEmpty())
	    descRt = descFnd.render(resdoc(wnd.res.get(), pagText), width);

	// Resolve the treatment strip. Anything still loading throws out of here and we retry
	// next tick with `info` left unset, so the guard in WoundBox.tick() re-runs us.
	List<NWoundTreatments.Treatment> treats = NWoundTreatments.forWound(wnd.res.get().name);
	List<Treat> entries = null;
	Text.Line noneLine = null;
	if(treats != null) {
	    entries = new ArrayList<>(treats.size());
	    for(NWoundTreatments.Treatment tr : treats)
		entries.add(resolve(tr));
	    if(entries.isEmpty())
		noneLine = effectFnd.render(L10n.get("char.wound.treat.none"), new Color(0x8F, 0xA3, 0xA4));
	}

	// Compute layout
	int nameBottom = -nameAdj + nameLine.sz().y;
	int nameEffectGap = 6; // ~10px visual from name baseline to effect top

	int effectsBottom = nameBottom + nameEffectGap;
	effectsBottom += mods.size() * eLineH;

	int headerH = Math.max(iconSz.y, effectsBottom + nameAdj);
	int y = headerH + 11;

	if(descRt != null)
	    y += descRt.sz().y;

	// Treatment strip layout
	Text.Line capLine = null;
	Text.Line[] tNames = null, tNotes = null;
	boolean[] tInline = null;
	int[] tRowY = null, tRowH = null;
	int stripTop = 0, textX = SLOT_SZ.x + SLOT_GAP;
	int textW = width - textX;
	if(treats != null) {
	    capLine = captionFnd.render(L10n.get("char.wound.treatments"));
	    stripTop = y + SEC_GAP;
	    int ty = stripTop + 1 + CAP_GAP + capLine.sz().y + CAP_GAP;
	    if(noneLine != null) {
		y = ty + noneLine.sz().y;
	    } else {
		int n = entries.size();
		tNames = new Text.Line[n];
		tNotes = new Text.Line[n];
		tInline = new boolean[n];
		tRowY = new int[n];
		tRowH = new int[n];
		for(int i = 0; i < n; i++) {
		    Treat e = entries.get(i);
		    tNames[i] = effectFnd.render(e.name, NAME_COL);
		    if(e.noteShort != null)
			tNotes[i] = effectFnd.render(e.noteShort, NOTE_COL);
		    int lineH = tNames[i].sz().y;
		    if(tNotes[i] == null) {
			tInline[i] = true;
			tRowH[i] = Math.max(SLOT_SZ.y, lineH);
		    } else {
			tInline[i] = (tNames[i].sz().x + NOTE_GAP + tNotes[i].sz().x) <= textW;
			tRowH[i] = Math.max(SLOT_SZ.y, tInline[i] ? lineH : (lineH * 2));
		    }
		    tRowY[i] = ty;
		    ty += tRowH[i] + ROW_GAP;
		}
		y = ty - ROW_GAP;
	    }
	}

	BufferedImage result = TexI.mkbuf(new Coord(width, y));
	Graphics2D g = result.createGraphics();
	g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
	    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

	// Draw icon at top-left
	g.drawImage(icon, 0, 0, null);

	// Draw name to the right, top-aligned with icon
	g.drawImage(nameLine.img, titleX, -nameAdj, null);

	// Draw effects with tabular alignment
	int eIconW = (eIcons.length > 0 && eIcons[0] != null) ? eIcons[0].getWidth() : 0;
	int eNameX = titleX + eIconW + iconGap;
	int eValX  = titleX + eIconW + iconGap + maxNameW + valGap;
	int ey = nameBottom + nameEffectGap;
	for(int i = 0; i < mods.size(); i++) {
	    int textH = eNames[i].getHeight();
	    if(eIcons[i] != null) {
		int iconY = ey + (textH - EFFECT_ICON_SZ.y) / 2;
		g.drawImage(eIcons[i], titleX, iconY, null);
	    }
	    g.drawImage(eNames[i], eNameX, ey, null);
	    g.drawImage(eVals[i], eValX, ey, null);
	    ey += eLineH;
	}

	// Draw description below header
	if(descRt != null)
	    g.drawImage(descRt.img, 0, headerH + 11, null);

	// Draw the treatment strip
	List<Hover> hv = Collections.emptyList();
	if(treats != null) {
	    g.setColor(NStyle.separator);
	    g.fillRect(0, stripTop, width, 1);
	    g.drawImage(capLine.img, 0, stripTop + 1 + CAP_GAP, null);
	    if(noneLine != null) {
		g.drawImage(noneLine.img, 0, stripTop + 1 + CAP_GAP + capLine.sz().y + CAP_GAP, null);
	    } else {
		hv = new ArrayList<>(entries.size());
		for(int i = 0; i < entries.size(); i++) {
		    Treat e = entries.get(i);
		    int ry = tRowY[i], rh = tRowH[i];
		    drawSlot(g, 0, ry + ((rh - SLOT_SZ.y) / 2), e.icon);
		    int lineH = tNames[i].sz().y;
		    if(tInline[i]) {
			int ly = ry + ((rh - lineH) / 2);
			g.drawImage(tNames[i].img, textX, ly, null);
			if(tNotes[i] != null)
			    g.drawImage(tNotes[i].img, textX + tNames[i].sz().x + NOTE_GAP, ly, null);
		    } else {
			int ly = ry + ((rh - (lineH * 2)) / 2);
			g.drawImage(tNames[i].img, textX, ly, null);
			g.drawImage(tNotes[i].img, textX, ly + lineH, null);
		    }
		    hv.add(new Hover(Area.sized(Coord.of(0, ry), Coord.of(width, rh)), e.name, e.noteLong));
		}
	    }
	}

	g.dispose();
	this.hovers = hv;
	/* Arms the re-render guard in WoundBox.tick(). Must stay last: anything above may throw
	 * Loading, and then we want to be called again. */
	this.info = info;
	return result;
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
	List<Hover> hv = this.hovers;
	if(!hv.isEmpty()) {
	    Coord cc = c.sub(marg()).add(0, sb.val);
	    for(Hover h : hv) {
		if(h.area.contains(cc))
		    return(tiptex(h));
	    }
	}
	return(super.tooltip(c, prev));
    }

    private static Tex tiptex(Hover h) {
	Text.Line nl = effectFnd.render(h.name, Color.WHITE);
	if(h.note == null)
	    return(new TexI(nl.img));
	Text.Line ntl = effectFnd.render(h.note, NOTE_COL);
	int w = Math.max(nl.sz().x, ntl.sz().x);
	BufferedImage buf = TexI.mkbuf(Coord.of(w, nl.sz().y + ntl.sz().y));
	Graphics2D g = buf.createGraphics();
	g.drawImage(nl.img, 0, 0, null);
	g.drawImage(ntl.img, 0, nl.sz().y, null);
	g.dispose();
	return(new TexI(buf));
    }
}
