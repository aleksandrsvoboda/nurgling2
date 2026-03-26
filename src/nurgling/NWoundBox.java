package nurgling;

import haven.*;
import haven.WoundWnd.*;
import haven.res.ui.tt.attrmod.*;
import java.util.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import static haven.CharWnd.*;
import static haven.PUtils.*;

public class NWoundBox extends WoundWnd.WoundBox {
    private static final Color INFO_BG = new Color(0x1C, 0x25, 0x26);

    private static final Text.Foundry nameFnd = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold(), 14, Color.WHITE).aa(true);

    private static final java.awt.Font descFont =
	nurgling.conf.FontSettings.getOpenSans().deriveFont(
	    (float)Math.floor(UI.scale(11.0)));

    private static final RichText.Foundry descFnd = new RichText.Foundry(
	RichText.IMAGESRC, RichText.ImageSource.legacy,
	TextAttribute.FONT, descFont).aa(true);

    public NWoundBox(int id) {
	super(id);
    }

    @Override
    public void drawbg(GOut g) {
	g.chcolor(INFO_BG);
	g.frect(Coord.z, sz);
	g.chcolor();
    }

    @Override
    public BufferedImage renderinfo(int width) {
	Wound wnd = wound();
	List<ItemInfo> info = wnd.info();
	BufferedImage icon = wnd.icon();
	Coord iconSz = Utils.imgsz(icon);
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
	int titleAreaW = width - titleX;

	// Collect AttrMod effects — two-pass for tabular alignment
	List<Mod> mods = new ArrayList<>();
	for(ItemInfo inf : info) {
	    if(inf instanceof AttrMod)
		for(Entry e : ((AttrMod)inf).tab)
		    if(e instanceof Mod)
			mods.add((Mod)e);
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
	    eNames[i] = Text.render(mod.attr.name()).img;
	    String col = (mod.mod < 0) ? AttrMod.debuff : AttrMod.buff;
	    String sign = (mod.mod < 0) ? "-" : "+";
	    eVals[i] = RichText.render(String.format("$col[%s]{%s%d}", col, sign,
		Math.round(Math.abs(mod.mod))), 0).img;
	    eIcons[i] = mod.attr.icon();
	    if(eIcons[i] != null)
		eIcons[i] = convolvedown(eIcons[i],
		    Coord.of(eNames[i].getHeight(), eNames[i].getHeight()), iconfilter);
	    maxNameW = Math.max(maxNameW, eNames[i].getWidth());
	    eLineH = Math.max(eLineH, eNames[i].getHeight());
	}

	// Render description text (pagina)
	Resource.Pagina pag = wnd.res.get().layer(Resource.pagina);
	String pagText = (pag != null) ? pag.text : "";
	RichText descRt = null;
	if(!pagText.isEmpty())
	    descRt = descFnd.render(resdoc(wnd.res.get(), pagText), width);

	// Compute layout
	int nameBottom = -nameAdj + nameLine.sz().y;
	int nameEffectGap = 6; // ~10px visual from name baseline to effect top

	// Effects below name, right of icon
	int effectsBottom = nameBottom + nameEffectGap;
	effectsBottom += mods.size() * eLineH;

	// Body starts below whichever is taller: icon or name+effects area
	int headerH = Math.max(iconSz.y, effectsBottom + nameAdj);
	int y = headerH + 11;

	if(descRt != null)
	    y += descRt.sz().y;

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
	    if(eIcons[i] != null)
		g.drawImage(eIcons[i], titleX, ey, null);
	    g.drawImage(eNames[i], eNameX, ey, null);
	    g.drawImage(eVals[i], eValX, ey, null);
	    ey += eLineH;
	}

	// Draw description below header
	if(descRt != null)
	    g.drawImage(descRt.img, 0, headerH + 11, null);

	g.dispose();
	return result;
    }
}
