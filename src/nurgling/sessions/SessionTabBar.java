package nurgling.sessions;

import haven.*;
import haven.Widget.*;
import nurgling.NGameUI;
import nurgling.NUI;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;

/**
 * A widget that displays tabs for all active sessions.
 * Allows switching between sessions and adding new accounts.
 */
public class SessionTabBar extends Widget {
    /** Height of the tab bar */
    public static final int BAR_HEIGHT = UI.scale(28);
    /** Width of each tab */
    public static final int TAB_WIDTH = UI.scale(120);
    /** Width of the add button */
    public static final int ADD_BTN_WIDTH = UI.scale(30);
    /** Padding between tabs */
    public static final int TAB_PADDING = UI.scale(4);
    /** Tab corner radius */
    public static final int CORNER_RADIUS = UI.scale(4);

    /** Colors */
    private static final Color ACTIVE_BG = new Color(60, 120, 60, 220);
    private static final Color HEADLESS_BG = new Color(60, 60, 80, 220);
    private static final Color HOVER_BG = new Color(80, 80, 100, 220);
    private static final Color ADD_BG = new Color(50, 50, 70, 220);
    private static final Color BORDER_COLOR = new Color(100, 100, 120);
    private static final Color TEXT_COLOR = new Color(220, 220, 220);
    private static final Color ACTIVE_TEXT = new Color(180, 255, 180);

    /** Currently hovered tab index (-1 = none, -2 = add button) */
    private int hoveredTab = -1;

    /** Callback for when add account is clicked */
    private Runnable onAddAccount;

    public SessionTabBar() {
        super(Coord.z);
        // Size will be updated based on parent
    }

    /**
     * Set the callback for when "Add Account" is clicked.
     */
    public void setOnAddAccount(Runnable callback) {
        this.onAddAccount = callback;
    }

    @Override
    public void resize(Coord sz) {
        this.sz = new Coord(sz.x, BAR_HEIGHT);
    }

    @Override
    public void draw(GOut g) {
        SessionManager sm = SessionManager.getInstance();
        Collection<SessionContext> sessions = sm.getAllSessions();

        if (sessions.isEmpty()) {
            return; // Don't draw if no sessions
        }

        // Draw background
        g.chcolor(new Color(30, 30, 40, 200));
        g.frect(Coord.z, sz);
        g.chcolor();

        // Draw tabs
        int x = TAB_PADDING;
        int tabIndex = 0;
        for (SessionContext ctx : sessions) {
            drawTab(g, x, ctx, tabIndex == hoveredTab, ctx == sm.getActiveSession());
            x += TAB_WIDTH + TAB_PADDING;
            tabIndex++;
        }

        // Draw "+" button
        drawAddButton(g, x, hoveredTab == -2);
    }

    private void drawTab(GOut g, int x, SessionContext ctx, boolean hovered, boolean active) {
        int y = TAB_PADDING;
        int h = BAR_HEIGHT - TAB_PADDING * 2;

        // Background
        Color bgColor;
        if (active) {
            bgColor = ACTIVE_BG;
        } else if (hovered) {
            bgColor = HOVER_BG;
        } else {
            bgColor = HEADLESS_BG;
        }
        g.chcolor(bgColor);
        g.frect(new Coord(x, y), new Coord(TAB_WIDTH, h));

        // Border
        g.chcolor(active ? ACTIVE_TEXT : BORDER_COLOR);
        g.rect(new Coord(x, y), new Coord(TAB_WIDTH, h));

        // Mode indicator
        String modeIcon = active ? "\u25B6" : "\u2699"; // ▶ for active, ⚙ for headless
        g.chcolor(active ? ACTIVE_TEXT : TEXT_COLOR);
        g.atext(modeIcon, new Coord(x + UI.scale(8), y + h/2), 0, 0.5);

        // Character name
        String name = ctx.getDisplayName();
        if (name.length() > 12) {
            name = name.substring(0, 11) + "...";
        }
        g.chcolor(active ? ACTIVE_TEXT : TEXT_COLOR);
        g.atext(name, new Coord(x + UI.scale(22), y + h/2), 0, 0.5);

        // Bot status (if running)
        String botName = ctx.getCurrentBotName();
        if (botName != null && !active) {
            g.chcolor(new Color(150, 150, 200));
            String shortBot = botName.length() > 10 ? botName.substring(0, 9) + "..." : botName;
            g.atext(shortBot, new Coord(x + TAB_WIDTH - UI.scale(5), y + h/2), 1, 0.5);
        }

        g.chcolor();
    }

    private void drawAddButton(GOut g, int x, boolean hovered) {
        int y = TAB_PADDING;
        int h = BAR_HEIGHT - TAB_PADDING * 2;

        // Background
        g.chcolor(hovered ? HOVER_BG : ADD_BG);
        g.frect(new Coord(x, y), new Coord(ADD_BTN_WIDTH, h));

        // Border
        g.chcolor(BORDER_COLOR);
        g.rect(new Coord(x, y), new Coord(ADD_BTN_WIDTH, h));

        // Plus sign
        g.chcolor(TEXT_COLOR);
        g.atext("+", new Coord(x + ADD_BTN_WIDTH/2, y + h/2), 0.5, 0.5);

        g.chcolor();
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (ev.b != 1) return false;

        int tabIndex = getTabAt(ev.c);
        if (tabIndex >= 0) {
            // Clicked on a session tab - switch to it
            SessionManager sm = SessionManager.getInstance();
            List<SessionContext> sessions = new ArrayList<>(sm.getAllSessions());
            if (tabIndex < sessions.size()) {
                SessionContext ctx = sessions.get(tabIndex);
                if (ctx != sm.getActiveSession()) {
                    sm.switchToSession(ctx.sessionId);
                    return true;
                }
            }
        } else if (tabIndex == -2) {
            // Clicked on add button
            if (onAddAccount != null) {
                onAddAccount.run();
            }
            return true;
        }
        return false;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hoveredTab = getTabAt(ev.c);
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        if (!hovering) {
            hoveredTab = -1;
        }
        return false;
    }

    /**
     * Get the tab index at the given coordinate.
     * @return Tab index (0+), -2 for add button, -1 for none
     */
    private int getTabAt(Coord c) {
        if (c.y < TAB_PADDING || c.y > BAR_HEIGHT - TAB_PADDING) {
            return -1;
        }

        SessionManager sm = SessionManager.getInstance();
        int sessionCount = sm.getSessionCount();

        int x = TAB_PADDING;
        for (int i = 0; i < sessionCount; i++) {
            if (c.x >= x && c.x < x + TAB_WIDTH) {
                return i;
            }
            x += TAB_WIDTH + TAB_PADDING;
        }

        // Check add button
        if (c.x >= x && c.x < x + ADD_BTN_WIDTH) {
            return -2;
        }

        return -1;
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        // Ctrl+Tab to switch sessions
        if (ev.awt.isControlDown() && ev.awt.getKeyCode() == KeyEvent.VK_TAB) {
            SessionManager sm = SessionManager.getInstance();
            List<SessionContext> sessions = new ArrayList<>(sm.getAllSessions());
            if (sessions.size() > 1) {
                SessionContext active = sm.getActiveSession();
                int idx = sessions.indexOf(active);
                int nextIdx;
                if (ev.awt.isShiftDown()) {
                    // Ctrl+Shift+Tab - previous
                    nextIdx = (idx - 1 + sessions.size()) % sessions.size();
                } else {
                    // Ctrl+Tab - next
                    nextIdx = (idx + 1) % sessions.size();
                }
                sm.switchToSession(sessions.get(nextIdx).sessionId);
                return true;
            }
        }
        return super.keydown(ev);
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        int tabIndex = getTabAt(c);
        if (tabIndex >= 0) {
            SessionManager sm = SessionManager.getInstance();
            List<SessionContext> sessions = new ArrayList<>(sm.getAllSessions());
            if (tabIndex < sessions.size()) {
                SessionContext ctx = sessions.get(tabIndex);
                StringBuilder sb = new StringBuilder();
                sb.append(ctx.getDisplayName());
                if (ctx.username != null) {
                    sb.append("\nAccount: ").append(ctx.username);
                }
                sb.append("\nMode: ").append(ctx.isHeadless() ? "Headless (Bot)" : "Visual (Active)");
                String botName = ctx.getCurrentBotName();
                if (botName != null) {
                    sb.append("\nBot: ").append(botName);
                }
                sb.append("\n\nClick to switch");
                return sb.toString();
            }
        } else if (tabIndex == -2) {
            return "Add another account";
        }
        return null;
    }
}
