package nurgling.sessions;

import haven.*;
import haven.Widget.*;
import nurgling.NConfig;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.NStyle;
import nurgling.NUI;
import nurgling.conf.FontSettings;
import nurgling.conf.NDragProp;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.util.*;

/**
 * A draggable widget that displays buttons for all active sessions.
 * Allows switching between sessions, closing sessions, and adding new accounts.
 */
public class SessionTabBar extends Widget {
    /** Button dimensions */
    public static final int BUTTON_HEIGHT = UI.scale(18);
    public static final int BUTTON_WIDTH = UI.scale(120);
    /** Close button size */
    public static final int CLOSE_BTN_SIZE = UI.scale(10);
    public static final int CLOSE_BTN_MARGIN = UI.scale(3);
    /** Plus button dimensions */
    public static final int PLUS_BTN_SIZE = UI.scale(18);
    public static final int PLUS_BTN_MARGIN = UI.scale(5);
    /** Padding between buttons */
    public static final int BUTTON_PADDING = UI.scale(3);
    /** Icon size and margin */
    public static final int ICON_SIZE = UI.scale(12);
    public static final int ICON_MARGIN = UI.scale(3);

    /** Colors for different states */
    private static final Color BUTTON_BG = new Color(0x25, 0x2B, 0x29, 0xE5);  // #252B29E5
    private static final Color BUTTON_BG_HOVER = new Color(0x35, 0x3B, 0x39, 0xE5);  // Lighter for hover
    private static final Color ACTIVE_BORDER = new Color(0x99, 0xFF, 0x84);    // #99FF84
    private static final Color ACTIVE_TEXT = new Color(255, 255, 255);         // White
    private static final Color BOT_BORDER = new Color(0xE9, 0x9C, 0x54);       // #E99C54
    private static final Color BOT_TEXT = new Color(0xE9, 0x9C, 0x54);         // #E99C54
    private static final Color IDLE_BORDER = new Color(0x91, 0x60, 0x2E);      // #91602E
    private static final Color IDLE_TEXT = new Color(150, 150, 150);           // Gray
    private static final Color COMBAT_BORDER = new Color(0xFF, 0x64, 0x64);    // #FF6464
    private static final Color COMBAT_TEXT = new Color(0xFF, 0x64, 0x64);      // #FF6464
    private static final Color CLOSE_BTN_COLOR = new Color(180, 80, 80);
    private static final Color CLOSE_BTN_HOVER = new Color(220, 100, 100);
    private static final Color PLUS_BTN_BG = new Color(0x25, 0x2B, 0x29, 0xE5);
    private static final Color PLUS_BTN_HOVER = new Color(0x35, 0x3B, 0x39, 0xE5);
    private static final Color PLUS_BTN_BORDER = new Color(0x91, 0x60, 0x2E);  // #91602E

    /** Font for character names */
    private Text.Foundry nameFont;

    /** Currently hovered button index (-1 = none, -2 = plus button) */
    private int hoveredButton = -1;
    /** Currently hovered close button index (-1 = none) */
    private int hoveredCloseButton = -1;

    /** Drag state */
    private UI.Grab dm = null;
    private Coord doff;
    private Coord dragStartPos;
    private int dragStartButton = -1;
    private static final int DRAG_THRESHOLD = 3; // pixels to move before starting drag

    /** Keybindings - static so they can be accessed from NGameUI.globtype() */
    public static final KeyBinding kb_session1 = KeyBinding.get("session-1", KeyMatch.forcode(KeyEvent.VK_1, KeyMatch.M));
    public static final KeyBinding kb_session2 = KeyBinding.get("session-2", KeyMatch.forcode(KeyEvent.VK_2, KeyMatch.M));
    public static final KeyBinding kb_session3 = KeyBinding.get("session-3", KeyMatch.forcode(KeyEvent.VK_3, KeyMatch.M));
    public static final KeyBinding kb_session4 = KeyBinding.get("session-4", KeyMatch.forcode(KeyEvent.VK_4, KeyMatch.M));
    public static final KeyBinding kb_session5 = KeyBinding.get("session-5", KeyMatch.forcode(KeyEvent.VK_5, KeyMatch.M));
    public static final KeyBinding kb_session6 = KeyBinding.get("session-6", KeyMatch.forcode(KeyEvent.VK_6, KeyMatch.M));
    public static final KeyBinding kb_session7 = KeyBinding.get("session-7", KeyMatch.forcode(KeyEvent.VK_7, KeyMatch.M));
    public static final KeyBinding kb_session8 = KeyBinding.get("session-8", KeyMatch.forcode(KeyEvent.VK_8, KeyMatch.M));
    public static final KeyBinding kb_session9 = KeyBinding.get("session-9", KeyMatch.forcode(KeyEvent.VK_9, KeyMatch.M));
    public static final KeyBinding kb_session10 = KeyBinding.get("session-10", KeyMatch.forcode(KeyEvent.VK_0, KeyMatch.M));
    public static final KeyBinding kb_session_next = KeyBinding.get("session-next", KeyMatch.forcode(KeyEvent.VK_CLOSE_BRACKET, KeyMatch.M));
    public static final KeyBinding kb_session_prev = KeyBinding.get("session-prev", KeyMatch.forcode(KeyEvent.VK_OPEN_BRACKET, KeyMatch.M));

    /** Array of session keybindings for easy iteration */
    public static final KeyBinding[] SESSION_BINDINGS = {
        kb_session1, kb_session2, kb_session3, kb_session4, kb_session5,
        kb_session6, kb_session7, kb_session8, kb_session9, kb_session10
    };

    /** Callback for when add account is clicked */
    private Runnable onAddAccount;

    /** Drag mode controls */
    private ICheckBox btnLock;
    private ICheckBox btnVis;
    private TexI label;

    /** Drag mode resources */
    public static final IBox box = Window.wbox;
    private static final Tex ctl = Resource.loadtex("nurgling/hud/box/tl");
    private static final Coord controlOffset = UI.scale(10, 10);
    public static Text.Furnace labelFont = new PUtils.BlurFurn(
        new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 14, Color.YELLOW).aa(true),
        UI.scale(1), UI.scale(2), Color.BLACK
    );

    public SessionTabBar() {
        super(Coord.z);

        // Load Open Sans Semibold font (11px)
        try {
            FontSettings fontSettings = (FontSettings) NConfig.get(NConfig.Key.fonts);
            Font openSansSemibold = fontSettings.getFont("Open Sans Semibold");
            nameFont = new Text.Foundry(openSansSemibold, UI.scale(11));
        } catch (Exception e) {
            // Fallback to default if Open Sans Semibold not available
            nameFont = Text.std;
        }

        // Create drag mode label
        label = new TexI(labelFont.render("Sessions").img);

        // Create lock button
        add(btnLock = new ICheckBox(NStyle.locki[0], NStyle.locki[1], NStyle.locki[2], NStyle.locki[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                saveDragState();
            }
        }, new Coord(0, 0)); // Position will be updated in updateSize()

        // Create visibility button
        add(btnVis = new ICheckBox(NStyle.visi[0], NStyle.visi[1], NStyle.visi[2], NStyle.visi[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                // Don't set this.visible - just save the state
                // The draw method will check btnVis.a to decide what to show
                saveDragState();
            }
        }, new Coord(0, 0)); // Position will be updated in updateSize()

        // Hide buttons initially (shown in drag mode)
        btnLock.hide();
        btnVis.hide();

        // Load saved position and state
        loadPosition();
        loadDragState();

        // Calculate initial size
        updateSize();
    }

    /**
     * Load widget position from preferences.
     */
    private void loadPosition() {
        String posStr = Utils.getpref("sessionbar-pos", "100,100");
        try {
            String[] parts = posStr.split(",");
            if (parts.length == 2) {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                this.c = new Coord(x, y);
            }
        } catch (Exception e) {
            this.c = new Coord(100, 100);
        }
    }

    /**
     * Save widget position to preferences.
     */
    private void savePosition() {
        Utils.setpref("sessionbar-pos", c.x + "," + c.y);
    }

    /**
     * Update widget size based on number of sessions.
     * In drag mode, use fixed size. Otherwise, size to fit content.
     */
    private void updateSize() {
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;

        if (dragMode) {
            // Fixed size in drag mode - wider than buttons, tall as 10 buttons
            // Width includes: close button + spacing + session button + plus button
            int dragWidth = CLOSE_BTN_SIZE + UI.scale(3) + BUTTON_WIDTH + PLUS_BTN_SIZE + PLUS_BTN_MARGIN + UI.scale(40);
            int dragHeight = 10 * (BUTTON_HEIGHT + BUTTON_PADDING);
            this.sz = new Coord(dragWidth, dragHeight);
        } else {
            // Size to fit content in normal mode
            SessionManager sm = SessionManager.getInstance();
            int sessionCount = sm.getSessionCount();
            if (sessionCount == 0) {
                // Width includes: close button + spacing + session button + plus button
                int width = CLOSE_BTN_SIZE + UI.scale(3) + BUTTON_WIDTH + PLUS_BTN_SIZE + PLUS_BTN_MARGIN;
                this.sz = new Coord(width, BUTTON_HEIGHT);
            } else {
                int height = sessionCount * (BUTTON_HEIGHT + BUTTON_PADDING) - BUTTON_PADDING;
                int width = CLOSE_BTN_SIZE + UI.scale(3) + BUTTON_WIDTH + PLUS_BTN_SIZE + PLUS_BTN_MARGIN;
                this.sz = new Coord(width, height);
            }
        }

        // Update button positions (top-right corner)
        if (btnLock != null && btnVis != null) {
            int iconSize = NStyle.locki[0].sz().x;
            btnLock.move(new Coord(sz.x - iconSize - iconSize / 2, iconSize / 2));
            btnVis.move(new Coord(sz.x - iconSize - iconSize / 2, iconSize + controlOffset.y));
        }
    }

    /**
     * Set the callback for when "Add Account" is clicked.
     */
    public void setOnAddAccount(Runnable callback) {
        this.onAddAccount = callback;
    }

    @Override
    public void draw(GOut g) {
        SessionManager sm = SessionManager.getInstance();
        Collection<SessionContext> sessions = sm.getAllSessions();
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;

        updateSize();

        // Draw overall background and border in drag mode
        if (dragMode) {
            drawDragBackground(g, sz);
            box.draw(g, Coord.z, sz);
            // Draw label centered
            g.aimage(label, sz.div(2), 0.5, 0.5);
        }

        // Draw lock/eye buttons on top
        super.draw(g);

        // Only draw session buttons if visible or in drag mode
        if (!btnVis.a && !dragMode) {
            return;
        }

        // Calculate offset for buttons (inside the drag mode frame)
        Coord buttonOffset = dragMode ? new Coord(UI.scale(15), UI.scale(35)) : Coord.z;

        if (sessions.isEmpty()) {
            // Just draw plus button
            drawPlusButton(g, buttonOffset.y, hoveredButton == -2);
            return;
        }

        // Draw session buttons
        int y = buttonOffset.y;
        int buttonIndex = 0;
        boolean canClose = sessions.size() > 1; // Can only close if more than one session
        for (SessionContext ctx : sessions) {
            boolean isActive = ctx == sm.getActiveSession();
            boolean hovered = (buttonIndex == hoveredButton);
            boolean closeHovered = canClose && (buttonIndex == hoveredCloseButton);

            // Draw close button to the left (disabled if only one session)
            drawCloseButton(g, buttonOffset.x, y, closeHovered, !canClose);

            // Draw session button after close button
            int sessionButtonX = buttonOffset.x + CLOSE_BTN_SIZE + UI.scale(3);
            drawSessionButton(g, sessionButtonX, y, ctx, hovered, isActive);

            // Draw plus button next to first session
            if (buttonIndex == 0) {
                drawPlusButton(g, y, hoveredButton == -2);
            }

            y += BUTTON_HEIGHT + BUTTON_PADDING;
            buttonIndex++;
        }

        g.chcolor();
    }

    private void drawDragBackground(GOut g, Coord sz) {
        Coord bgUl = new Coord(ctl.sz().x / 2, ctl.sz().y / 2);
        Coord bgSz = new Coord(sz.x - ctl.sz().x, sz.y - ctl.sz().y);

        if (ui instanceof NUI) {
            NUI nui = (NUI)ui;
            float opacity = nui.getUIOpacity();
            int alpha = (int)(255 * opacity);

            if (nui.getUseSolidBackground()) {
                Color bgColor = nui.getWindowBackgroundColor();
                g.chcolor(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), alpha);
                g.frect(bgUl, bgSz);
                g.chcolor();
            } else {
                g.chcolor(255, 255, 255, alpha);
                Coord bgc = new Coord();
                Coord ca_ul = bgUl;
                Coord ca_br = bgUl.add(bgSz);
                for(bgc.y = ca_ul.y; bgc.y < ca_br.y; bgc.y += Window.bg.sz().y) {
                    for(bgc.x = ca_ul.x; bgc.x < ca_br.x; bgc.x += Window.bg.sz().x)
                        g.image(Window.bg, bgc, ca_ul, ca_br);
                }
                g.chcolor();
            }
        }
    }

    private void drawCloseButton(GOut g, int x, int y, boolean hovered, boolean disabled) {
        int closeY = y + (BUTTON_HEIGHT - CLOSE_BTN_SIZE) / 2;

        // Draw close button background (grayed out if disabled)
        if (disabled) {
            g.chcolor(new Color(80, 80, 80)); // Dark gray for disabled
        } else {
            g.chcolor(hovered ? CLOSE_BTN_HOVER : CLOSE_BTN_COLOR);
        }
        g.frect(new Coord(x, closeY), new Coord(CLOSE_BTN_SIZE, CLOSE_BTN_SIZE));

        // Draw X (dimmed if disabled)
        if (disabled) {
            g.chcolor(new Color(120, 120, 120)); // Gray X for disabled
        } else {
            g.chcolor(new Color(255, 255, 255));
        }
        g.atext("×", new Coord(x + CLOSE_BTN_SIZE / 2, closeY + CLOSE_BTN_SIZE / 2), 0.5, 0.5);

        g.chcolor();
    }

    private void drawSessionButton(GOut g, int x, int y, SessionContext ctx, boolean hovered,
                                    boolean isActive) {
        // Determine state
        boolean inCombat = ctx.isInCombat();
        boolean runningBot = ctx.isRunningBot();

        // Choose colors and icons based on state priority:
        // Combat > Bot > Active > Idle
        Color borderColor;
        Color textColor;
        String iconLeft = null;
        String iconRight = null;

        if (inCombat) {
            borderColor = COMBAT_BORDER;
            textColor = COMBAT_TEXT;
            iconLeft = iconRight = "\u26A0"; // ⚠ warning triangle
        } else if (runningBot) {
            borderColor = BOT_BORDER;
            textColor = BOT_TEXT;
            iconLeft = iconRight = "\u2699"; // ⚙ gear/cog
        } else if (isActive) {
            borderColor = ACTIVE_BORDER;
            textColor = ACTIVE_TEXT;
        } else {
            borderColor = IDLE_BORDER;
            textColor = IDLE_TEXT;
        }

        // Draw button background
        g.chcolor(hovered ? BUTTON_BG_HOVER : BUTTON_BG);
        g.frect(new Coord(x, y), new Coord(BUTTON_WIDTH, BUTTON_HEIGHT));

        // Draw button border (2px) - draw two rectangles for 2px border
        g.chcolor(borderColor);
        g.rect(new Coord(x, y), new Coord(BUTTON_WIDTH, BUTTON_HEIGHT));
        g.rect(new Coord(x + 1, y + 1), new Coord(BUTTON_WIDTH - 2, BUTTON_HEIGHT - 2));

        // Calculate text area (between icons)
        int textStartX = x + ICON_MARGIN;
        int textEndX = x + BUTTON_WIDTH - ICON_MARGIN;

        // Draw left icon if present
        if (iconLeft != null) {
            g.chcolor(textColor);
            g.atext(iconLeft, new Coord(textStartX, y + BUTTON_HEIGHT / 2), 0, 0.5);
            textStartX += ICON_SIZE;
        }

        // Draw right icon if present
        if (iconRight != null) {
            g.chcolor(textColor);
            g.atext(iconRight, new Coord(textEndX, y + BUTTON_HEIGHT / 2), 1.0, 0.5);
            textEndX -= ICON_SIZE;
        }

        // Draw character name (max 67px width, truncated with "..." if needed)
        String name = ctx.getDisplayName();
        final int MAX_NAME_WIDTH = UI.scale(67);

        // Truncate name if too long
        Text nameText = nameFont.render(name);
        if (nameText.sz().x > MAX_NAME_WIDTH) {
            // Binary search for maximum length that fits
            int maxLen = name.length();
            while (maxLen > 0) {
                String truncated = name.substring(0, maxLen) + "...";
                nameText = nameFont.render(truncated);
                if (nameText.sz().x <= MAX_NAME_WIDTH) {
                    break;
                }
                maxLen--;
            }
        }

        g.chcolor(textColor);
        int textX = textStartX + (textEndX - textStartX) / 2;
        g.aimage(nameText.tex(), new Coord(textX, y + BUTTON_HEIGHT / 2), 0.5, 0.5);

        g.chcolor();
    }

    private void drawPlusButton(GOut g, int y, boolean hovered) {
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;
        int xOffset = dragMode ? UI.scale(15) : 0;
        // Plus button is after: close button + spacing + session button
        int x = xOffset + CLOSE_BTN_SIZE + UI.scale(3) + BUTTON_WIDTH + PLUS_BTN_MARGIN;
        int btnY = y + (BUTTON_HEIGHT - PLUS_BTN_SIZE) / 2;

        // Draw background
        g.chcolor(hovered ? PLUS_BTN_HOVER : PLUS_BTN_BG);
        g.frect(new Coord(x, btnY), new Coord(PLUS_BTN_SIZE, PLUS_BTN_SIZE));

        // Draw border
        g.chcolor(PLUS_BTN_BORDER);
        g.rect(new Coord(x, btnY), new Coord(PLUS_BTN_SIZE, PLUS_BTN_SIZE));
        g.rect(new Coord(x + 1, btnY + 1), new Coord(PLUS_BTN_SIZE - 2, PLUS_BTN_SIZE - 2));

        // Draw "+" text
        Text plusText = nameFont.render("+");
        g.chcolor(IDLE_TEXT);
        g.aimage(plusText.tex(), new Coord(x + PLUS_BTN_SIZE / 2, btnY + PLUS_BTN_SIZE / 2), 0.5, 0.5);

        g.chcolor();
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;

        if (dragMode) {
            // In drag mode, check if buttons handled the event
            if (!btnLock.mousedown(ev) && !btnVis.mousedown(ev)) {
                // Buttons didn't handle it, allow dragging if not locked
                if (ev.c.isect(Coord.z, sz)) {
                    if (ui.grabs.isEmpty()) {
                        if (!btnLock.a) {
                            if (ev.b == 1) {
                                dm = ui.grabmouse(this);
                                doff = ev.c;
                            }
                        }
                    } else {
                        if (ev.b == 1) {
                            dm = ui.grabmouse(this);
                            doff = ev.c;
                        }
                        parent.setfocus(this);
                    }
                }
            }
            return super.mousedown(ev);
        }

        // Normal mode - handle session button clicks
        if (ev.b != 1) return super.mousedown(ev);

        SessionManager sm = SessionManager.getInstance();
        List<SessionContext> sessions = new ArrayList<>(sm.getAllSessions());

        // Check if clicking on plus button
        if (isPlusButtonHit(ev.c)) {
            if (onAddAccount != null) {
                onAddAccount.run();
            }
            return true;
        }

        // Check if clicking any close button first (they're separate from session buttons)
        // Only allow closing if there's more than one session
        if (sessions.size() > 1) {
            for (int i = 0; i < sessions.size(); i++) {
                if (isCloseButtonHit(ev.c, i)) {
                    sm.requestCloseSession(sessions.get(i).sessionId);
                    return true;
                }
            }
        }

        // Check which session button was clicked
        int buttonIndex = getButtonAt(ev.c);
        if (buttonIndex >= 0 && buttonIndex < sessions.size()) {

            // Otherwise, prepare for potential drag or click
            dragStartPos = ev.c;
            dragStartButton = buttonIndex;
            return true;
        }

        return super.mousedown(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;

        if (dm != null && dragMode) {
            // Save drag mode position
            saveDragState();
            dm.remove();
            dm = null;
            return true;
        } else if (dm != null) {
            // Normal mode drag ended
            dm.remove();
            dm = null;
            savePosition();
            dragStartPos = null;
            dragStartButton = -1;
            return true;
        }

        // If we had a mousedown on a button but didn't drag, treat as click
        if (ev.b == 1 && dragStartButton >= 0 && dragStartPos != null) {
            SessionManager sm = SessionManager.getInstance();
            List<SessionContext> sessions = new ArrayList<>(sm.getAllSessions());
            if (dragStartButton < sessions.size()) {
                SessionContext ctx = sessions.get(dragStartButton);
                if (ctx != sm.getActiveSession()) {
                    sm.switchToSession(ctx.sessionId);
                }
            }
            dragStartPos = null;
            dragStartButton = -1;
            return true;
        }

        return super.mouseup(ev);
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;

        if (dragMode) {
            // Handle active dragging in drag mode
            if (dm != null) {
                this.c = this.c.add(ev.c.sub(doff));
            } else {
                // Not dragging, handle button hover
                if (ev.c.isect(Coord.z, sz)) {
                    btnLock.mousemove(ev);
                    btnVis.mousemove(ev);
                }
            }
        } else {
            // Normal mode
            if (dm != null) {
                // Handle dragging
                this.c = this.c.add(ev.c.sub(doff));
                return;
            }

            // Check if we should start dragging (mouse moved enough from start position)
            if (dragStartPos != null && dragStartButton >= 0) {
                int dx = Math.abs(ev.c.x - dragStartPos.x);
                int dy = Math.abs(ev.c.y - dragStartPos.y);
                if (dx > DRAG_THRESHOLD || dy > DRAG_THRESHOLD) {
                    // Start dragging
                    dm = ui.grabmouse(this);
                    doff = dragStartPos;
                    dragStartButton = -1;
                    return;
                }
            }

            // Update hover state
            if (isPlusButtonHit(ev.c)) {
                hoveredButton = -2;
                hoveredCloseButton = -1;
            } else {
                int buttonIndex = getButtonAt(ev.c);
                hoveredButton = buttonIndex;

                if (buttonIndex >= 0 && isCloseButtonHit(ev.c, buttonIndex)) {
                    hoveredCloseButton = buttonIndex;
                } else {
                    hoveredCloseButton = -1;
                }
            }

            super.mousemove(ev);
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;

        // Show/hide drag mode controls
        if (dragMode) {
            if (!btnLock.visible()) {
                btnLock.show();
                btnVis.show();
            }
        } else {
            if (btnLock.visible()) {
                btnLock.hide();
                btnVis.hide();
            }
        }
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        if (!hovering) {
            hoveredButton = -1;
            hoveredCloseButton = -1;
        }
        return false;
    }

    /**
     * Start dragging the widget.
     */
    private void drag(Coord off) {
        dm = ui.grabmouse(this);
        doff = off;
    }

    /**
     * Get the button index at the given coordinate.
     * Session buttons now start after the close button.
     */
    private int getButtonAt(Coord c) {
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;
        int xOffset = dragMode ? UI.scale(15) : 0;

        // Session button starts after close button
        int sessionButtonX = xOffset + CLOSE_BTN_SIZE + UI.scale(3);

        if (c.x < sessionButtonX || c.x > sessionButtonX + BUTTON_WIDTH) {
            return -1;
        }

        SessionManager sm = SessionManager.getInstance();
        int sessionCount = sm.getSessionCount();

        for (int i = 0; i < sessionCount; i++) {
            int y = i * (BUTTON_HEIGHT + BUTTON_PADDING);
            if (c.y >= y && c.y < y + BUTTON_HEIGHT) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Check if coordinate is over close button of given button.
     * Close button is now to the left of the session button.
     */
    private boolean isCloseButtonHit(Coord c, int buttonIndex) {
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;
        int xOffset = dragMode ? UI.scale(15) : 0;

        int y = buttonIndex * (BUTTON_HEIGHT + BUTTON_PADDING);
        int closeX = xOffset;  // Close button starts at left edge
        int closeY = y + (BUTTON_HEIGHT - CLOSE_BTN_SIZE) / 2;

        return c.x >= closeX && c.x < closeX + CLOSE_BTN_SIZE &&
               c.y >= closeY && c.y < closeY + CLOSE_BTN_SIZE;
    }

    /**
     * Check if coordinate is over plus button.
     * Plus button is now after: close button + spacing + session button.
     */
    private boolean isPlusButtonHit(Coord c) {
        boolean dragMode = ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;
        int xOffset = dragMode ? UI.scale(15) : 0;

        // Plus button starts after close button, spacing, and session button
        int x = xOffset + CLOSE_BTN_SIZE + UI.scale(3) + BUTTON_WIDTH + PLUS_BTN_MARGIN;
        int y = (BUTTON_HEIGHT - PLUS_BTN_SIZE) / 2;

        return c.x >= x && c.x < x + PLUS_BTN_SIZE &&
               c.y >= y && c.y < y + PLUS_BTN_SIZE;
    }

    /**
     * Load drag state from preferences.
     */
    private void loadDragState() {
        String lockedStr = Utils.getpref("sessionbar-locked", "false");
        String visibleStr = Utils.getpref("sessionbar-visible", "true");
        btnLock.a = Boolean.parseBoolean(lockedStr);
        btnVis.a = Boolean.parseBoolean(visibleStr);
        // Don't set this.visible - the draw method checks btnVis.a instead
    }

    /**
     * Save drag state to preferences.
     */
    private void saveDragState() {
        Utils.setpref("sessionbar-locked", String.valueOf(btnLock.a));
        Utils.setpref("sessionbar-visible", String.valueOf(btnVis.a));
        savePosition();
    }

}
