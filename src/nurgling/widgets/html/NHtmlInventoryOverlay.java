package nurgling.widgets.html;

import haven.*;
import nurgling.*;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xhtmlrenderer.simple.Graphics2DRenderer;

/**
 * HTML/CSS rendered inventory grid background.
 *
 * Renders only the grid cells, borders, and slot numbers using HTML/CSS.
 * Draws at z=-1 so native WItem children (with their overlays for quality,
 * stack count, etc.) render on top via the normal widget tree.
 */
public class NHtmlInventoryOverlay extends Widget {
    private final NInventory inv;
    private Tex htmlTex;
    private boolean dirty = true;
    private Coord lastIsz = Coord.z;

    private static final Coord sqsz = Inventory.sqsz;

    private DocumentBuilder xmlBuilder;

    public NHtmlInventoryOverlay(NInventory inv) {
        super(inv.sz);
        this.inv = inv;
        this.lastIsz = inv.isz;
        z(-1);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            xmlBuilder = factory.newDocumentBuilder();
        } catch (Exception e) {
            System.err.println("NHtmlInventoryOverlay: Failed to create XML parser: " + e.getMessage());
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        // Fill the entire window content area, not just the inventory
        Window wnd = getparent(Window.class);
        Coord target = (wnd != null) ? wnd.csz() : inv.sz;
        if (!sz.equals(target)) {
            resize(target);
            dirty = true;
        }
        if (!lastIsz.equals(inv.isz)) {
            lastIsz = inv.isz;
            dirty = true;
        }
    }

    @Override
    public void draw(GOut g) {
        if (dirty || htmlTex == null) {
            renderToTexture();
        }
        if (htmlTex != null) {
            g.image(htmlTex, Coord.z);
        }
    }

    private void renderToTexture() {
        try {
            String xhtml = buildGridHtml();
            Document doc = xmlBuilder.parse(new InputSource(new StringReader(xhtml)));

            BufferedImage img = new BufferedImage(sz.x, sz.y, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = img.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Graphics2DRenderer renderer = new Graphics2DRenderer();
            renderer.setDocument(doc, null);
            renderer.layout(g2d, new java.awt.Dimension(sz.x, sz.y));
            renderer.render(g2d);
            g2d.dispose();

            if (htmlTex != null) htmlTex.dispose();
            htmlTex = new TexI(img);
            dirty = false;
        } catch (Exception e) {
            System.err.println("NHtmlInventoryOverlay: Render failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String buildGridHtml() {
        // Cells inset by 1px at top/left; grid bg fills the 1px gaps as borders
        int cellW = sqsz.x - 1;
        int cellH = sqsz.y - 1;

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head><style type=\"text/css\">\n");
        sb.append("* { margin: 0; padding: 0; }\n");
        sb.append("body { font-family: SansSerif; }\n");
        // Grid bg = cell border color (matches NHtmlDeco window bg so padding is seamless)
        sb.append(".g { position: relative; width: ").append(sz.x).append("px; height: ").append(sz.y)
          .append("px; background-color: #161d15; }\n");
        // Cell fill — approximates RGBA(36,52,38,125) over dark window bg
        sb.append(".c { position: absolute; background-color: #1e2b1f; }\n");
        // Masked cell
        sb.append(".m { position: absolute; background-color: #222222; }\n");
        // Slot number centered in cell
        sb.append(".n { color: #3a4a36; font-size: ").append(UI.scale(11)).append("px; text-align: center; ")
          .append("line-height: ").append(cellH).append("px; }\n");
        sb.append("</style></head>\n<body><div class=\"g\">\n");

        int slotNum = 1;
        for (int y = 0; y < inv.isz.y; y++) {
            for (int x = 0; x < inv.isz.x; x++) {
                int px = x * sqsz.x + 1;
                int py = y * sqsz.y + 1;
                boolean masked = inv.sqmask != null
                        && (y * inv.isz.x + x) < inv.sqmask.length
                        && inv.sqmask[y * inv.isz.x + x];
                String cls = masked ? "m" : "c";

                sb.append("<div class=\"").append(cls)
                  .append("\" style=\"left:").append(px)
                  .append("px;top:").append(py)
                  .append("px;width:").append(cellW)
                  .append("px;height:").append(cellH).append("px;\">");
                sb.append("<div class=\"n\">").append(slotNum).append("</div>");
                sb.append("</div>\n");
                slotNum++;
            }
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    @Override
    public void dispose() {
        if (htmlTex != null) htmlTex.dispose();
        super.dispose();
    }
}
