package nurgling;

import haven.*;
import nurgling.conf.FontSettings;

import java.awt.Color;
import java.util.*;

public class NChatUI extends ChatUI {
    private static final int SIDEBAR_W = UI.scale(131);
    private static final int DIVIDER_W = UI.scale(2);
    private static final int ROW_H = UI.scale(24);
    private static final int TEXT_PAD = UI.scale(6); // horizontal breathing room inside row
    private static final int CLOSE_PAD = UI.scale(6); // gap from right edge to X icon
    private static final String ELLIPSIS = "..";
    private static final int BORDER_W = Math.max(1, UI.scale(1));

    // Close-button icons wrapping the existing NStyle close cross
    private static final TexI closeIconU = new TexI(NStyle.cbtni[0]);
    private static final TexI closeIconD = new TexI(NStyle.cbtni[1]);
    private static final TexI closeIconH = new TexI(NStyle.cbtni[2]);
    private static final int CLOSE_W = closeIconU.sz().x;
    private static final int CLOSE_H = closeIconU.sz().y;

    private static final Color SIDEBAR_BG = new Color(0x28, 0x34, 0x36);
    private static final Color MSG_BG     = new Color(0x1C, 0x25, 0x26);
    private static final Color SEL_BG     = new Color(0x33, 0x3E, 0x40);
    private static final Color SEL_TEXT   = NStyle.border; // #E99C54
    private static final Color NORM_TEXT  = Color.WHITE;

    private static final Text.Foundry chanFont = new Text.Foundry(
	FontSettings.getOpenSansSemibold(), 11, NORM_TEXT).aa(true);
    private static final Text.Foundry chanSelFont = new Text.Foundry(
	FontSettings.getOpenSansSemibold(), 11, SEL_TEXT).aa(true);
    private static final Text.Foundry chanUrgFont = new Text.Foundry(
	FontSettings.getOpenSansSemibold(), 11, new Color(255, 128, 0)).aa(true);

    private final NChatSidebar sidebar;

    public NChatUI() {
	super();
	chansel.visible = false;
	sidebar = new NChatSidebar();
	super.add(sidebar, Coord.z);
    }

    @Override
    protected void added() {
	super.added();
	layoutWidgets();
    }

    /* ---- layout ---- */

    private void layoutWidgets() {
	sidebar.c = Coord.z;
	sidebar.resize(new Coord(SIDEBAR_W, sz.y));
	if(sel != null) {
	    int cx = SIDEBAR_W + DIVIDER_W;
	    sel.c = new Coord(cx, 0);
	    sel.resize(new Coord(sz.x - cx, sz.y));
	}
    }

    @Override
    public void cresize() {
	// Don't call super — we replace the layout entirely
	if(chansel != null)
	    chansel.resize(new Coord(0, 0)); // keep it valid but invisible
	layoutWidgets();
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
    }

    /* ---- channel management ---- */

    @Override
    public void select(Channel chan, boolean focus) {
	Channel prev = sel;
	sel = chan;
	if(prev != null)
	    prev.hide();
	sel.show();
	chansel.show(chan);
	// Suppress the channel's own top-right close button; sidebar shows it instead.
	if(chan != null && chan.cb != null)
	    chan.cb.visible = false;
	cresize();
	if(focus || hasfocus)
	    setfocus(chan);
    }

    /* ---- drawing ---- */

    @Override
    public void draw(GOut g) {
	int alpha = 255;
	if(ui instanceof NUI) {
	    alpha = (int)(255 * ((NUI)ui).getUIOpacity());
	}

	// 1. Sidebar background
	g.chcolor(SIDEBAR_BG.getRed(), SIDEBAR_BG.getGreen(), SIDEBAR_BG.getBlue(), alpha);
	g.frect(Coord.z, new Coord(SIDEBAR_W, sz.y));
	g.chcolor();

	// 2. Message area background
	int cx = SIDEBAR_W + DIVIDER_W;
	g.chcolor(MSG_BG.getRed(), MSG_BG.getGreen(), MSG_BG.getBlue(), alpha);
	g.frect(new Coord(cx, 0), new Coord(sz.x - cx, sz.y));
	g.chcolor();

	// 3. Divider
	g.chcolor(NStyle.border);
	g.frect(new Coord(SIDEBAR_W, 0), new Coord(DIVIDER_W, sz.y));
	g.chcolor();

	// 4. Children (sidebar + selected channel)
	drawChildren(g);

	// 5. Orange border
	g.chcolor(NStyle.border);
	g.frect(Coord.z, new Coord(sz.x, BORDER_W));               // top
	g.frect(new Coord(0, sz.y - BORDER_W), new Coord(sz.x, BORDER_W)); // bottom
	g.frect(Coord.z, new Coord(BORDER_W, sz.y));                // left
	g.frect(new Coord(sz.x - BORDER_W, 0), new Coord(BORDER_W, sz.y)); // right
	g.chcolor();
    }

    private void drawChildren(GOut g) {
	for(Widget wdg = child; wdg != null; wdg = wdg.next) {
	    if(!wdg.visible || wdg == chansel)
		continue;
	    Coord cc = xlate(wdg.c, true);
	    GOut cg = g.reclip(cc, wdg.sz);
	    try {
		wdg.draw(cg);
	    } catch(Loading l) {
		/* skip */
	    }
	}
    }

    /* ---- sidebar widget ---- */

    private class NChatSidebar extends Widget {
	private final Scrollbar sb;
	// Cache keyed by (name + "|" + maxWidth) -> displayed (possibly truncated) string
	private final Map<String, String> truncCache = new HashMap<>();
	// Cache keyed by displayed string -> rendered Text (one map per visual state)
	private final Map<String, Text> nameCache = new HashMap<>();
	private final Map<String, Text> selNameCache = new HashMap<>();
	private final Map<String, Text> urgNameCache = new HashMap<>();
	// Hover/press tracking for per-row close X
	private Channel hoverCloseChan = null;
	private Channel pressCloseChan = null;

	NChatSidebar() {
	    sb = add(new Scrollbar(0, 0, 0));
	    sb.visible = false;
	}

	@Override
	public void draw(GOut g) {
	    List<Selector.DarkChannel> chls;
	    synchronized(chansel.chls) {
		chls = new ArrayList<>(chansel.chls);
	    }

	    int totalH = chls.size() * ROW_H;
	    int scrollOff = 0;
	    if(totalH > sz.y) {
		sb.visible = true;
		sb.max = totalH - sz.y;
		sb.resize(sz.y);
		sb.c = new Coord(sz.x - sb.sz.x, 0);
		scrollOff = sb.val;
	    } else {
		sb.visible = false;
		sb.val = 0;
	    }

	    int nameW = sb.visible ? sz.x - sb.sz.x : sz.x;
	    int y = -scrollOff;
	    for(Selector.DarkChannel dch : chls) {
		if(y + ROW_H > 0 && y < sz.y) {
		    boolean isSel = (dch.chan == sel);
		    boolean closable = dch.chan.closable();
		    // Row background for selected
		    if(isSel) {
			g.chcolor(SEL_BG);
			g.frect(new Coord(0, y), new Coord(nameW, ROW_H));
			g.chcolor();
		    }
		    // Reserve right-side space for the X on closable rows
		    int reservedRight = closable ? (CLOSE_W + CLOSE_PAD * 2) : 0;
		    int textAreaW = nameW - reservedRight;
		    // Channel name (truncated with ".." if needed, then centered in text area)
		    String name = dch.chan.name();
		    int maxTextW = Math.max(0, textAreaW - TEXT_PAD * 2);
		    Text rendered = renderName(name, isSel, dch.chan.urgency, maxTextW);
		    int textX = (textAreaW - rendered.sz().x) / 2;
		    int textY = y + (ROW_H - rendered.sz().y) / 2;
		    g.image(rendered.tex(), new Coord(textX, textY));
		    // Close X for closable rows
		    if(closable) {
			TexI icon = closeIconU;
			if(pressCloseChan == dch.chan)      icon = closeIconD;
			else if(hoverCloseChan == dch.chan) icon = closeIconH;
			int ix = nameW - CLOSE_PAD - CLOSE_W;
			int iy = y + (ROW_H - CLOSE_H) / 2;
			g.image(icon, new Coord(ix, iy));
		    }
		}
		y += ROW_H;
	    }
	    // Draw scrollbar on top
	    if(sb.visible)
		super.draw(g);
	}

	/** Returns the channel whose close-X hit-box contains coord c, or null. */
	private Channel closeAt(Coord c) {
	    int nameW = sb.visible ? sz.x - sb.sz.x : sz.x;
	    int ix = nameW - CLOSE_PAD - CLOSE_W;
	    if(c.x < ix || c.x >= ix + CLOSE_W) return null;
	    int scrollOff = sb.visible ? sb.val : 0;
	    int idx = (c.y + scrollOff) / ROW_H;
	    Channel chan;
	    synchronized(chansel.chls) {
		if(idx < 0 || idx >= chansel.chls.size()) return null;
		chan = chansel.chls.get(idx).chan;
	    }
	    if(!chan.closable()) return null;
	    int rowY = idx * ROW_H - scrollOff;
	    int iy = rowY + (ROW_H - CLOSE_H) / 2;
	    if(c.y < iy || c.y >= iy + CLOSE_H) return null;
	    return chan;
	}

	private Text renderName(String name, boolean selected, int urgency, int maxWidth) {
	    Text.Foundry font = selected ? chanSelFont : (urgency > 0 ? chanUrgFont : chanFont);
	    Map<String, Text> cache = selected ? selNameCache : (urgency > 0 ? urgNameCache : nameCache);
	    String display = truncateName(name, font, maxWidth);
	    return cache.computeIfAbsent(display, n -> font.render(n));
	}

	private String truncateName(String name, Text.Foundry font, int maxWidth) {
	    String key = name + "|" + maxWidth;
	    String cached = truncCache.get(key);
	    if(cached != null)
		return cached;

	    Text.Line full = font.render(name);
	    if(full.sz().x <= maxWidth) {
		truncCache.put(key, name);
		return name;
	    }

	    int ellw = font.strsize(ELLIPSIS).x;
	    int avail = maxWidth - ellw;
	    String result;
	    if(avail <= 0) {
		// Sidebar too narrow even for the ellipsis — render it alone
		result = ELLIPSIS;
	    } else {
		int len = full.charat(avail);
		if(len < 1) len = 1;
		result = name.substring(0, len) + ELLIPSIS;
	    }
	    truncCache.put(key, result);
	    return result;
	}

	@Override
	public boolean mousedown(MouseDownEvent ev) {
	    if(sb.visible && ev.c.x >= sz.x - sb.sz.x) {
		return sb.mousedown(ev);
	    }
	    if(ev.b == 1) {
		Channel closeChan = closeAt(ev.c);
		if(closeChan != null) {
		    pressCloseChan = closeChan;
		    return true;
		}
		Channel chan = channelAt(ev.c);
		if(chan != null)
		    NChatUI.this.select(chan);
		return true;
	    }
	    return super.mousedown(ev);
	}

	@Override
	public boolean mouseup(MouseUpEvent ev) {
	    if(sb.visible)
		sb.mouseup(ev);
	    if(ev.b == 1 && pressCloseChan != null) {
		Channel pressed = pressCloseChan;
		pressCloseChan = null;
		Channel released = closeAt(ev.c);
		if(released == pressed) {
		    pressed.wdgmsg("close");
		    return true;
		}
	    }
	    return super.mouseup(ev);
	}

	@Override
	public void mousemove(MouseMoveEvent ev) {
	    hoverCloseChan = closeAt(ev.c);
	    super.mousemove(ev);
	}

	@Override
	public boolean mousewheel(MouseWheelEvent ev) {
	    sb.ch(ev.a * ROW_H);
	    return true;
	}

	private Channel channelAt(Coord c) {
	    int scrollOff = sb.visible ? sb.val : 0;
	    int idx = (c.y + scrollOff) / ROW_H;
	    synchronized(chansel.chls) {
		if(idx >= 0 && idx < chansel.chls.size())
		    return chansel.chls.get(idx).chan;
	    }
	    return null;
	}
    }
}
