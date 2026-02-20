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
    /** Close button size */
    public static final int CLOSE_BTN_SIZE = UI.scale(14);
    /** Close button margin from tab edge */
    public static final int CLOSE_BTN_MARGIN = UI.scale(4);

    /** Colors */
    private static final Color ACTIVE_BG = new Color(60, 120, 60, 220);
    private static final Color HOVER_BG = new Color(80, 80, 100, 220);
    private static final Color ADD_BG = new Color(50, 50, 70, 220);
    private static final Color BORDER_COLOR = new Color(100, 100, 120);
    private static final Color TEXT_COLOR = new Color(220, 220, 220);
    private static final Color ACTIVE_TEXT = new Color(180, 255, 180);
    private static final Color CLOSE_BTN_COLOR = new Color(180, 80, 80);
    private static final Color CLOSE_BTN_HOVER = new Color(220, 100, 100);
    /** Yellow for sessions running bots */
    private static final Color BOT_RUNNING_BG = new Color(180, 150, 50, 220);
    private static final Color BOT_RUNNING_TEXT = new Color(255, 230, 150);
    /** Blue for idle sessions */
    private static final Color IDLE_BG = new Color(50, 80, 130, 220);
    private static final Color IDLE_TEXT = new Color(150, 180, 220);

    /** Currently hovered tab index (-1 = none, -2 = add button) */
    private int hoveredTab = -1;
    /** Currently hovered close button tab index (-1 = none) */
    private int hoveredCloseTab = -1;

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
        boolean canClose = sessions.size() > 1; // Can close any session if more than one exists
        for (SessionContext ctx : sessions) {
            boolean isActive = ctx == sm.getActiveSession();
            boolean closeHovered = (tabIndex == hoveredCloseTab);
            drawTab(g, x, ctx, tabIndex == hoveredTab, isActive, canClose, closeHovered);
            x += TAB_WIDTH + TAB_PADDING;
            tabIndex++;
        }

        // Draw "+" button
        drawAddButton(g, x, hoveredTab == -2);
    }

    private void drawTab(GOut g, int x, SessionContext ctx, boolean hovered, boolean active,
                         boolean canClose, boolean closeHovered) {
        int y = TAB_PADDING;
        int h = BAR_HEIGHT - TAB_PADDING * 2;

        // Determine status: running bot or idle
        boolean runningBot = ctx.isRunningBot();

        // Background color based on status
        Color bgColor;
        Color textColor;
        if (active) {
            bgColor = ACTIVE_BG;
            textColor = ACTIVE_TEXT;
        } else if (hovered) {
            bgColor = HOVER_BG;
            textColor = TEXT_COLOR;
        } else if (runningBot) {
            bgColor = BOT_RUNNING_BG;
            textColor = BOT_RUNNING_TEXT;
        } else {
            bgColor = IDLE_BG;
            textColor = IDLE_TEXT;
        }
        g.chcolor(bgColor);
        g.frect(new Coord(x, y), new Coord(TAB_WIDTH, h));

        // Border
        g.chcolor(active ? ACTIVE_TEXT : BORDER_COLOR);
        g.rect(new Coord(x, y), new Coord(TAB_WIDTH, h));

        // Mode indicator - show bot icon if running, otherwise play/gear icon
        String modeIcon;
        if (runningBot) {
            modeIcon = "\u2699"; // ⚙ gear for running bot
        } else if (active) {
            modeIcon = "\u25B6"; // ▶ for active
        } else {
            modeIcon = "\u23F8"; // ⏸ pause for idle headless
        }
        g.chcolor(textColor);
        g.atext(modeIcon, new Coord(x + UI.scale(8), y + h/2), 0, 0.5);

        // Character name (truncate shorter if close button visible)
        String name = ctx.getDisplayName();
        int maxNameLen = canClose ? 9 : 12;
        if (name.length() > maxNameLen) {
            name = name.substring(0, maxNameLen - 1) + "...";
        }
        g.chcolor(textColor);
        g.atext(name, new Coord(x + UI.scale(22), y + h/2), 0, 0.5);

        // Close button (only if more than one session)
        if (canClose) {
            int closeX = x + TAB_WIDTH - CLOSE_BTN_SIZE - CLOSE_BTN_MARGIN;
            int closeY = y + (h - CLOSE_BTN_SIZE) / 2;

            // Close button background
            g.chcolor(closeHovered ? CLOSE_BTN_HOVER : CLOSE_BTN_COLOR);
            g.frect(new Coord(closeX, closeY), new Coord(CLOSE_BTN_SIZE, CLOSE_BTN_SIZE));

            // X symbol
            g.chcolor(TEXT_COLOR);
            g.atext("×", new Coord(closeX + CLOSE_BTN_SIZE/2, closeY + CLOSE_BTN_SIZE/2), 0.5, 0.5);
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

        SessionManager sm = SessionManager.getInstance();
        List<SessionContext> sessions = new ArrayList<>(sm.getAllSessions());

        int tabIndex = getTabAt(ev.c);
        if (tabIndex >= 0) {
            if (tabIndex < sessions.size()) {
                SessionContext ctx = sessions.get(tabIndex);
                boolean isActive = ctx == sm.getActiveSession();

                // Check if click is on close button (only if >1 session exists)
                if (sessions.size() > 1 && isOverCloseButton(ev.c, tabIndex)) {
                    sm.requestCloseSession(ctx.sessionId);
                    return true;
                }

                // Clicked on tab body - switch to it
                if (!isActive) {
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
        // Check if hovering over a close button
        if (hoveredTab >= 0) {
            SessionManager sm = SessionManager.getInstance();
            if (sm.getSessionCount() > 1 && isOverCloseButton(ev.c, hoveredTab)) {
                hoveredCloseTab = hoveredTab;
            } else {
                hoveredCloseTab = -1;
            }
        } else {
            hoveredCloseTab = -1;
        }
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        if (!hovering) {
            hoveredTab = -1;
            hoveredCloseTab = -1;
        }
        return false;
    }

    /**
     * Check if the coordinate is over the close button of the given tab.
     */
    private boolean isOverCloseButton(Coord c, int tabIndex) {
        int tabX = TAB_PADDING + tabIndex * (TAB_WIDTH + TAB_PADDING);
        int closeX = tabX + TAB_WIDTH - CLOSE_BTN_SIZE - CLOSE_BTN_MARGIN;
        int y = TAB_PADDING;
        int h = BAR_HEIGHT - TAB_PADDING * 2;
        int closeY = y + (h - CLOSE_BTN_SIZE) / 2;

        return c.x >= closeX && c.x < closeX + CLOSE_BTN_SIZE &&
               c.y >= closeY && c.y < closeY + CLOSE_BTN_SIZE;
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
                boolean isActive = ctx == sm.getActiveSession();

                // Check if hovering over close button
                if (sessions.size() > 1 && isOverCloseButton(c, tabIndex)) {
                    return "Close this session";
                }

                StringBuilder sb = new StringBuilder();
                sb.append(ctx.getDisplayName());
                if (ctx.username != null) {
                    sb.append("\nAccount: ").append(ctx.username);
                }
                sb.append("\nMode: ").append(isActive ? "Visual (Active)" : "Headless (Bot)");
                String botName = ctx.getCurrentBotName();
                if (botName != null) {
                    sb.append("\nBot: ").append(botName);
                }
                if (!isActive) {
                    sb.append("\n\nClick to switch");
                }
                return sb.toString();
            }
        } else if (tabIndex == -2) {
            return "Add another account";
        }
        return null;
    }
}
