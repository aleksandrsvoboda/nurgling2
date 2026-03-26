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

	// Collect AttrMod effects
	List<BufferedImage> effectImgs = new ArrayList<>();
	for(ItemInfo inf : info) {
	    if(inf instanceof AttrMod) {
		AttrMod am = (AttrMod)inf;
		for(Entry e : am.tab) {
		    if(e instanceof Mod) {
			Mod mod = (Mod)e;
			String col = (mod.mod < 0) ? AttrMod.debuff : AttrMod.buff;
			String sign = (mod.mod < 0) ? "-" : "+";
			String txt = String.format("%s %s%d", mod.attr.name(), sign, Math.round(Math.abs(mod.mod)));
			BufferedImage line = RichText.render(String.format("$col[%s]{%s}", col, txt), 0).img;
			effectImgs.add(line);
		    }
		}
	    }
	}

	// Render description text (pagina)
	Resource.Pagina pag = wnd.res.get().layer(Resource.pagina);
	String pagText = (pag != null) ? pag.text : "";
	RichText descRt = null;
	if(!pagText.isEmpty())
	    descRt = descFnd.render(resdoc(wnd.res.get(), pagText), width);

	// Compute layout
	// Header: icon left, name + effects to the right
	int rightY = -nameAdj;
	int nameBottom = rightY + nameLine.sz().y;

	// Effects below name, still right of icon
	int effectsY = nameBottom;
	for(BufferedImage ei : effectImgs)
	    effectsY += ei.getHeight();

	// Body starts below whichever is taller: icon or name+effects area
	int headerH = Math.max(iconSz.y, effectsY + nameAdj);
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

	// Draw effects below name, right of icon
	int ey = nameBottom;
	for(BufferedImage ei : effectImgs) {
	    g.drawImage(ei, titleX, ey, null);
	    ey += ei.getHeight();
	}

	// Draw description below header
	if(descRt != null)
	    g.drawImage(descRt.img, 0, headerH + 11, null);

	g.dispose();
	return result;
    }
}
