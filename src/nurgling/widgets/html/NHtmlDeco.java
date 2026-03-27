package nurgling.widgets.html;

import haven.*;
import nurgling.NConfig;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.SortInventory;

import nurgling.widgets.NSearchWidget;

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
 * Unified HTML/CSS rendered window decoration for the inventory.
 * Renders the ENTIRE window in one HTML document: title bar, close/sort buttons,
 * left/right arrow toggles, inventory grid with slot numbers, and search bar.
 * The only native child widget is a TextEntry for search input.
 * All other interactivity is handled via click region hit-testing.
 */
public class NHtmlDeco extends Window.DragDeco {
    private Tex htmlTex;
    private boolean dirty = true;
    private Area aa;
    private Coord contentIsz = Coord.z;

    // Layout constants
    private static final int TITLE_H = UI.scale(28);
    private static final int PAD_SIDE = UI.scale(20);
    private static final int SEARCH_GAP = UI.scale(6);
    private static final int SEARCH_H = UI.scale(20);
    private static final int PAD_BOT = SEARCH_GAP + SEARCH_H + UI.scale(4);
    private static final int BTN_SZ = UI.scale(18);
    private static final int ARROW_W = UI.scale(14);
    private static final int ARROW_H = UI.scale(24);
    private static final Coord sqsz = Inventory.sqsz;
    private static final String BG_COLOR = "#161d15";

    // Search bar button size
    private static final int SB_SZ = UI.scale(18);

    // Click regions (in Deco coordinates)
    private Area closeRgn, sortRgn, leftArrowRgn, rightArrowRgn;
    private Area searchHelpRgn, searchListRgn, searchSaveRgn;

    // Search text entry — the ONLY native child widget
    private TextEntry searchEntry;

    // Backing NSearchWidget for help/save/history functionality (hidden, not drawn)
    private NSearchWidget backingSearch;

    // Reusable XML parser
    private DocumentBuilder xmlBuilder;

    public NHtmlDeco() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            xmlBuilder = factory.newDocumentBuilder();
        } catch (Exception e) {
            System.err.println("NHtmlDeco: XML parser init failed: " + e.getMessage());
        }
    }

    @Override
    public void iresize(Coord isz) {
        this.contentIsz = isz;
        Coord wsz = new Coord(isz.x + PAD_SIDE * 2, isz.y + TITLE_H + PAD_BOT);
        resize(wsz);
        aa = Area.sized(new Coord(PAD_SIDE, TITLE_H), isz);

        // Close button region (top-right of title bar)
        int cbx = wsz.x - BTN_SZ - UI.scale(6);
        int cby = (TITLE_H - BTN_SZ) / 2;
        closeRgn = Area.sized(Coord.of(cbx, cby), Coord.of(BTN_SZ, BTN_SZ));

        // Sort button region (left of close)
        sortRgn = Area.sized(Coord.of(cbx - BTN_SZ - UI.scale(4), cby), Coord.of(BTN_SZ, BTN_SZ));

        // Arrow regions (side padding, vertically centered on content)
        int arrowY = TITLE_H + isz.y / 2 - ARROW_H / 2;
        leftArrowRgn = Area.sized(Coord.of((PAD_SIDE - ARROW_W) / 2, arrowY), Coord.of(ARROW_W, ARROW_H));
        rightArrowRgn = Area.sized(Coord.of(wsz.x - PAD_SIDE + (PAD_SIDE - ARROW_W) / 2, arrowY), Coord.of(ARROW_W, ARROW_H));

        // Search bar button regions (in Deco coordinates)
        int searchY = TITLE_H + isz.y + SEARCH_GAP;
        int sbY = searchY + (SEARCH_H - SB_SZ) / 2;
        searchHelpRgn = Area.sized(Coord.of(PAD_SIDE, sbY), Coord.of(SB_SZ, SB_SZ));
        searchSaveRgn = Area.sized(Coord.of(PAD_SIDE + isz.x - SB_SZ, sbY), Coord.of(SB_SZ, SB_SZ));
        searchListRgn = Area.sized(Coord.of(PAD_SIDE + isz.x - SB_SZ * 2 - UI.scale(2), sbY), Coord.of(SB_SZ, SB_SZ));

        // TextEntry positioned between help button and list button
        int seX = PAD_SIDE + SB_SZ + UI.scale(3);
        int seY = searchY + UI.scale(1);
        int seW = isz.x - SB_SZ * 3 - UI.scale(10);
        if (searchEntry == null) {
            searchEntry = add(new TextEntry(seW, "") {
                @Override
                public boolean keydown(KeyDownEvent ev) {
                    boolean ret = super.keydown(ev);
                    if (NUtils.getGameUI() != null)
                        NUtils.getGameUI().itemsForSearch.install(text());
                    return ret;
                }
            }, Coord.of(seX, seY));
            searchEntry.setcanfocus(true);
        } else {
            searchEntry.move(Coord.of(seX, seY));
            searchEntry.resize(Coord.of(seW, searchEntry.sz.y));
        }

        dirty = true;
    }

    @Override
    public Area contarea() {
        return aa;
    }

    // ---- Rendering ----

    private void renderHtml() {
        String title = (parent instanceof Window) ? ((Window) parent).cap : "";
        if (title == null) title = "";

        try {
            String xhtml = buildXhtml(title);
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
            System.err.println("NHtmlDeco: Render failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String buildXhtml(String title) {
        int w = sz.x, h = sz.y;
        int gridX = PAD_SIDE, gridY = TITLE_H;
        int gridW = contentIsz.x, gridH = contentIsz.y;
        int cellW = sqsz.x - 1, cellH = sqsz.y - 1;

        // Get grid dimensions from inventory
        Coord isz = Coord.of(1, 1);
        boolean[] mask = null;
        NInventory inv = findInventory();
        if (inv != null) {
            isz = inv.isz;
            mask = inv.sqmask;
        }

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head><style type=\"text/css\">\n");
        sb.append("* { margin: 0; padding: 0; }\n");
        sb.append("body { font-family: SansSerif; }\n");

        // Window
        sb.append(".w { position: relative; width: ").append(w).append("px; height: ").append(h)
          .append("px; background-color: ").append(BG_COLOR).append("; border: 1px solid #3a3828; }\n");

        // Title bar
        sb.append(".tb { position: absolute; left: 0px; top: 0px; width: ").append(w - 2)
          .append("px; height: ").append(TITLE_H - 1).append("px; background-color: #252318; border-bottom: 1px solid #3a3828; ")
          .append("padding: 0 ").append(UI.scale(8)).append("px; color: #c8b878; font-size: ").append(UI.scale(14))
          .append("px; font-weight: bold; line-height: ").append(TITLE_H).append("px; }\n");

        // Title bar button (close, sort)
        sb.append(".btn { position: absolute; width: ").append(BTN_SZ).append("px; height: ").append(BTN_SZ)
          .append("px; background-color: #2a2520; border: 1px solid #4a4030; color: #c8b070; text-align: center; ")
          .append("line-height: ").append(BTN_SZ).append("px; font-size: ").append(UI.scale(12)).append("px; }\n");

        // Grid cells
        sb.append(".c { position: absolute; background-color: #1e2b1f; }\n");
        sb.append(".m { position: absolute; background-color: #222222; }\n");
        sb.append(".n { color: #3a4a36; font-size: ").append(UI.scale(11)).append("px; text-align: center; ")
          .append("line-height: ").append(cellH).append("px; }\n");

        // Arrow buttons
        sb.append(".ar { position: absolute; width: ").append(ARROW_W).append("px; height: ").append(ARROW_H)
          .append("px; background-color: #2a2520; border: 1px solid #4a4030; color: #c8b070; text-align: center; ")
          .append("line-height: ").append(ARROW_H).append("px; font-size: ").append(UI.scale(12)).append("px; }\n");

        // Search bar background
        sb.append(".sr { position: absolute; background-color: #1a1a18; border: 1px solid #3a3828; }\n");
        // Search bar buttons (same style as title buttons)
        sb.append(".sb { position: absolute; width: ").append(SB_SZ).append("px; height: ").append(SB_SZ)
          .append("px; background-color: #2a2520; border: 1px solid #4a4030; color: #c8b070; text-align: center; ")
          .append("line-height: ").append(SB_SZ).append("px; font-size: ").append(UI.scale(11)).append("px; }\n");

        sb.append("</style></head>\n<body><div class=\"w\">\n");

        // ---- Title bar ----
        sb.append("<div class=\"tb\">").append(escapeHtml(title)).append("</div>\n");

        // ---- Close button (✕) ----
        sb.append("<div class=\"btn\" style=\"left:").append(closeRgn.ul.x).append("px;top:").append(closeRgn.ul.y)
          .append("px;\">&#10005;</div>\n");

        // ---- Sort button (↓≡) ----
        sb.append("<div class=\"btn\" style=\"left:").append(sortRgn.ul.x).append("px;top:").append(sortRgn.ul.y)
          .append("px;\">&#8595;</div>\n");

        // ---- Left arrow ◄ ----
        sb.append("<div class=\"ar\" style=\"left:").append(leftArrowRgn.ul.x).append("px;top:").append(leftArrowRgn.ul.y)
          .append("px;\">&#9664;</div>\n");

        // ---- Right arrow ► ----
        sb.append("<div class=\"ar\" style=\"left:").append(rightArrowRgn.ul.x).append("px;top:").append(rightArrowRgn.ul.y)
          .append("px;\">&#9654;</div>\n");

        // ---- Grid cells ----
        int slotNum = 1;
        for (int y = 0; y < isz.y; y++) {
            for (int x = 0; x < isz.x; x++) {
                int px = gridX + x * sqsz.x + 1;
                int py = gridY + y * sqsz.y + 1;
                boolean masked = mask != null && (y * isz.x + x) < mask.length && mask[y * isz.x + x];
                String cls = masked ? "m" : "c";
                sb.append("<div class=\"").append(cls).append("\" style=\"left:").append(px)
                  .append("px;top:").append(py).append("px;width:").append(cellW)
                  .append("px;height:").append(cellH).append("px;\"><div class=\"n\">")
                  .append(slotNum).append("</div></div>\n");
                slotNum++;
            }
        }

        // ---- Search bar background ----
        int searchY = gridY + gridH + SEARCH_GAP;
        sb.append("<div class=\"sr\" style=\"left:").append(gridX).append("px;top:").append(searchY)
          .append("px;width:").append(gridW - 2).append("px;height:").append(SEARCH_H).append("px;\"></div>\n");

        // ---- Search bar buttons: 🔍 help, 📋 list, ⭐ save ----
        sb.append("<div class=\"sb\" style=\"left:").append(searchHelpRgn.ul.x)
          .append("px;top:").append(searchHelpRgn.ul.y).append("px;\">&#128269;</div>\n");
        sb.append("<div class=\"sb\" style=\"left:").append(searchListRgn.ul.x)
          .append("px;top:").append(searchListRgn.ul.y).append("px;\">&#9776;</div>\n");
        sb.append("<div class=\"sb\" style=\"left:").append(searchSaveRgn.ul.x)
          .append("px;top:").append(searchSaveRgn.ul.y).append("px;\">&#9733;</div>\n");

        sb.append("</div></body></html>");
        return sb.toString();
    }

    // ---- Drawing ----

    protected void cdraw(GOut g) {
        ((Window) parent).cdraw(g);
    }

    @Override
    public void draw(GOut g, boolean strict) {
        if (dirty || htmlTex == null) renderHtml();
        if (htmlTex != null) g.image(htmlTex, Coord.z);
        cdraw(g.reclip(aa.ul, aa.sz()));
        super.draw(g, strict);
    }

    // ---- Input Handling ----

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        // First let children handle (TextEntry for search)
        if (ev.propagate(this))
            return true;

        // Check HTML button regions
        if (ev.b == 1) {
            if (closeRgn != null && closeRgn.contains(ev.c)) {
                ((Window) parent).reqclose();
                return true;
            }
            if (sortRgn != null && sortRgn.contains(ev.c)) {
                NInventory inv = findInventory();
                if (inv != null) SortInventory.sort(inv);
                return true;
            }
            if (leftArrowRgn != null && leftArrowRgn.contains(ev.c)) {
                NInventory inv = findInventory();
                if (inv != null) {
                    inv.showPopup = !inv.showPopup;
                    inv.movePopup(((Window) parent).c);
                    if (inv.toggles != null) inv.toggles.raise();
                }
                return true;
            }
            if (rightArrowRgn != null && rightArrowRgn.contains(ev.c)) {
                NInventory inv = findInventory();
                if (inv != null) {
                    inv.showRightPanel = !inv.showRightPanel;
                    NConfig.set(NConfig.Key.inventoryRightPanelShow, inv.showRightPanel);
                    inv.updateRightPanelVisibility();
                    if (inv.rightTogglesExpanded != null) inv.rightTogglesExpanded.raise();
                    if (inv.rightTogglesCompact != null) inv.rightTogglesCompact.raise();
                }
                return true;
            }
            // Search bar buttons — delegate to backing NSearchWidget
            if (searchHelpRgn != null && searchHelpRgn.contains(ev.c)) {
                NSearchWidget sw = getBackingSearch();
                if (sw != null && sw.helpwnd != null) {
                    sw.helpwnd.show();
                    sw.helpwnd.raise();
                }
                return true;
            }
            if (searchSaveRgn != null && searchSaveRgn.contains(ev.c)) {
                NSearchWidget sw = getBackingSearch();
                if (searchEntry != null && !searchEntry.text().isEmpty()) {
                    if (sw != null) {
                        sw.createHistoryItem(searchEntry.text());
                        sw.write();
                    }
                } else {
                    if (NUtils.getGameUI() != null)
                        NUtils.getGameUI().error("Input field is empty");
                }
                return true;
            }
            if (searchListRgn != null && searchListRgn.contains(ev.c)) {
                NSearchWidget sw = getBackingSearch();
                if (sw != null && sw.history != null) {
                    // Toggle the backing checkbox so tick() keeps visibility in sync
                    sw.list.a = !sw.list.a;
                    if (sw.list.a) {
                        // Position the history popup above the search bar
                        Window wnd = (Window) parent;
                        Coord wndPos = wnd.c;
                        Area ca = wnd.ca();
                        int hx = wndPos.x + ca.ul.x + UI.scale(7);
                        int hy = wndPos.y + wnd.sz.y - UI.scale(37);
                        sw.history.move(Coord.of(hx, hy));
                        sw.history.raise();
                    }
                }
                return true;
            }
        }

        // Default: focus, raise, drag (from DragDeco)
        if (checkhit(ev.c)) {
            Window wnd = (Window) parent;
            wnd.parent.setfocus(wnd);
            wnd.raise();
            if (ev.b == 1) wnd.drag(ev.c);
            return true;
        }
        return false;
    }

    @Override
    public boolean checkhit(Coord c) {
        return c.x >= 0 && c.y >= 0 && c.x < sz.x && c.y < sz.y;
    }

    // ---- Utility ----

    private NInventory findInventory() {
        if (parent == null) return null;
        for (Widget w = ((Window) parent).child; w != null; w = w.next) {
            if (w instanceof NInventory) return (NInventory) w;
        }
        return null;
    }

    private NSearchWidget getBackingSearch() {
        if (backingSearch == null && NUtils.getGameUI() != null) {
            NInventory inv = findInventory();
            Coord invSz = (inv != null) ? inv.sz : Coord.of(UI.scale(200), UI.scale(20));
            backingSearch = new NSearchWidget(invSz);
            backingSearch.hide();
            // Add to GameUI so its help window and history popup work
            NUtils.getGameUI().add(backingSearch, Coord.of(-9999, -9999));
        }
        return backingSearch;
    }

    @Override
    public void dispose() {
        if (htmlTex != null) htmlTex.dispose();
        super.dispose();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
