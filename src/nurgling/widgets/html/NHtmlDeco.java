package nurgling.widgets.html;

import haven.*;
import nurgling.NStyle;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xhtmlrenderer.simple.Graphics2DRenderer;

public class NHtmlDeco extends Window.DragDeco {
    public final IButton cbtn;
    private Tex htmlTex;
    private boolean dirty = true;
    private Area aa;

    // Grid line color — window bg matches this so padding is invisible
    private static final String BG_COLOR = "#161d15";

    private static final int TITLE_H = UI.scale(28);
    private static final int PAD_SIDE = UI.scale(2);
    private static final int PAD_BOT = UI.scale(2);

    public NHtmlDeco() {
        cbtn = add(new IButton(NStyle.cbtni[0], NStyle.cbtni[1], NStyle.cbtni[2]))
                .action(() -> ((Window) parent).reqclose());
    }

    @Override
    public void iresize(Coord isz) {
        Coord wsz = new Coord(
                isz.x + PAD_SIDE * 2,
                isz.y + TITLE_H + PAD_BOT
        );
        resize(wsz);
        aa = Area.sized(new Coord(PAD_SIDE, TITLE_H), isz);
        cbtn.c = new Coord(wsz.x - cbtn.sz.x - UI.scale(6), (TITLE_H - cbtn.sz.y) / 2);
        dirty = true;
    }

    @Override
    public Area contarea() {
        return aa;
    }

    private void renderHtml() {
        String title = "";
        if (parent instanceof Window) {
            title = ((Window) parent).cap;
            if (title == null) title = "";
        }

        String xhtml = buildXhtml(title, sz.x, sz.y);

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xhtml)));

            BufferedImage img = new BufferedImage(sz.x, sz.y, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = img.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Graphics2DRenderer renderer = new Graphics2DRenderer();
            renderer.setDocument(doc, null);
            renderer.layout(g2d, new java.awt.Dimension(sz.x, sz.y));
            renderer.render(g2d);
            g2d.dispose();

            if (htmlTex != null)
                htmlTex.dispose();
            htmlTex = new TexI(img);
            dirty = false;
        } catch (Exception e) {
            System.err.println("NHtmlDeco: Failed to render HTML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String buildXhtml(String title, int w, int h) {
        return "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                "<head><style type=\"text/css\">\n" +
                "* { margin: 0; padding: 0; }\n" +
                "body { font-family: SansSerif; }\n" +

                ".window {\n" +
                "  width: " + w + "px;\n" +
                "  height: " + h + "px;\n" +
                "  background-color: " + BG_COLOR + ";\n" +
                "  border: 1px solid #3a3828;\n" +
                "}\n" +

                ".title-bar {\n" +
                "  height: " + (TITLE_H - 1) + "px;\n" +
                "  background-color: #252318;\n" +
                "  border-bottom: 1px solid #3a3828;\n" +
                "  padding: 0 " + UI.scale(8) + "px;\n" +
                "  color: #c8b878;\n" +
                "  font-size: " + UI.scale(14) + "px;\n" +
                "  font-weight: bold;\n" +
                "  line-height: " + TITLE_H + "px;\n" +
                "}\n" +

                "</style></head>\n" +
                "<body><div class=\"window\">\n" +
                "  <div class=\"title-bar\">" + escapeHtml(title) + "</div>\n" +
                "</div></body></html>";
    }

    protected void cdraw(GOut g) {
        ((Window) parent).cdraw(g);
    }

    @Override
    public void draw(GOut g, boolean strict) {
        if (dirty || htmlTex == null)
            renderHtml();
        if (htmlTex != null)
            g.image(htmlTex, Coord.z);
        cdraw(g.reclip(aa.ul, aa.sz()));
        super.draw(g, strict);
    }

    @Override
    public boolean checkhit(Coord c) {
        return c.x >= 0 && c.y >= 0 && c.x < sz.x && c.y < sz.y;
    }

    @Override
    public void dispose() {
        if (htmlTex != null)
            htmlTex.dispose();
        super.dispose();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
