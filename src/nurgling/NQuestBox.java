package nurgling;

import haven.*;
import haven.QuestWnd.Quest;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import static haven.CharWnd.*;
import static haven.PUtils.*;

public class NQuestBox extends Quest.DefaultBox {
    private static final Text.Foundry nameFnd = new Text.Foundry(
	nurgling.conf.FontSettings.getOpenSansSemibold(), 14, Color.WHITE).aa(true);

    private static final java.awt.Font descFont =
	nurgling.conf.FontSettings.getOpenSans().deriveFont(
	    (float)Math.floor(UI.scale(11.0)));

    private static final RichText.Foundry descFnd = new RichText.Foundry(
	RichText.IMAGESRC, RichText.ImageSource.legacy,
	TextAttribute.FONT, descFont).aa(true);

    public NQuestBox(int id, Indir<Resource> res, String title) {
	super(id, res, title);
    }

    @Override
    protected void layout(Widget cont) {
	// 1. Header: image left, title right (top-aligned)
	layouth(cont);
	// 2. Conditions (objectives)
	layoutc(cont);
	// 3. Description (pagina text)
	layoutDesc(cont);
	// 4. Options
	layouto(cont);
    }

    @Override
    protected void layouth(Widget cont) {
	Resource r = res.get();
	Coord iconSz = UI.scale(new Coord(76, 76));
	BufferedImage icon = convolvedown(r.flayer(Resource.imgc).img, iconSz, iconfilter);
	Text.Line titleLine = nameFnd.render(title());

	int titleX = iconSz.x + UI.scale(10);

	// Scan for first visible row in title for top-alignment
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

	int headerH = iconSz.y;
	int width = cont.sz.x - UI.scale(20);
	BufferedImage header = TexI.mkbuf(new Coord(width, headerH));
	Graphics2D g = header.createGraphics();
	g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
	    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	g.drawImage(icon, 0, 0, null);
	g.drawImage(titleLine.img, titleX, -titleAdj, null);
	g.dispose();

	cont.add(new Img(new TexI(header)), UI.scale(new Coord(10, 10)));
    }

    private void layoutDesc(Widget cont) {
	Resource r = res.get();
	Resource.Pagina pag = r.layer(Resource.pagina);
	if(pag != null && !pag.text.equals("")) {
	    int y = cont.contentsz().y + UI.scale(10);
	    int width = cont.sz.x - UI.scale(20);
	    RichText text = descFnd.render(resdoc(r, pag.text), width);
	    cont.add(new Img(text.tex()), new Coord(UI.scale(10), y));
	}
    }
}
