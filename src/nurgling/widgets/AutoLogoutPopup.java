package nurgling.widgets;

import haven.*;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.awt.event.KeyEvent;

/**
 * Countdown popup warning the user about imminent auto-logout.
 * Shows a live countdown timer with a Delay button.
 * When the countdown reaches 0, calls the onTimeout callback (logout).
 * Close/ESC/Delay all trigger the onDelay callback.
 */
public class AutoLogoutPopup extends haven.Window {

    private static final int POPUP_WIDTH = 350;
    private static final int POPUP_HEIGHT = 160;
    private static final int MARGIN = 15;

    private final Label countdownLabel;
    private final Runnable onTimeout;
    private final Runnable onDelay;
    private double remainingSeconds;
    private boolean fired = false;

    /**
     * @param countdownSeconds Initial countdown in seconds
     * @param delaySeconds     How many seconds the delay button grants (for display)
     * @param onTimeout        Called when countdown reaches 0
     * @param onDelay          Called when user clicks Delay or closes the window
     */
    public AutoLogoutPopup(int countdownSeconds, int delaySeconds, Runnable onTimeout, Runnable onDelay) {
        super(new Coord(UI.scale(POPUP_WIDTH), UI.scale(POPUP_HEIGHT)), L10n.get("autologout.title"));
        this.onTimeout = onTimeout;
        this.onDelay = onDelay;
        this.remainingSeconds = countdownSeconds;

        int y = UI.scale(10);

        // Warning header
        Label warningLabel = add(new Label("!! " + L10n.get("autologout.warning") + " !!"), new Coord(UI.scale(MARGIN), y));
        warningLabel.setcolor(new Color(255, 80, 80));
        y += UI.scale(24);

        // Message
        add(new Label(L10n.get("autologout.message")), new Coord(UI.scale(MARGIN), y));
        y += UI.scale(20);

        // Countdown label
        countdownLabel = add(new Label(formatCountdown()), new Coord(UI.scale(MARGIN), y));
        countdownLabel.setcolor(new Color(255, 200, 200));
        y += UI.scale(28);

        // Buttons
        int btnWidth = UI.scale(120);
        int btnGap = UI.scale(20);
        int totalBtnWidth = btnWidth * 2 + btnGap;
        int btnStartX = (UI.scale(POPUP_WIDTH) - totalBtnWidth) / 2;

        // Delay button
        add(new Button(btnWidth, L10n.get("autologout.delay", delaySeconds)) {
            @Override
            public void click() {
                doDelay();
            }
        }, new Coord(btnStartX, y));

        // Cancel/close button
        add(new Button(btnWidth, L10n.get("autologout.cancel")) {
            @Override
            public void click() {
                doDelay();
            }
        }, new Coord(btnStartX + btnWidth + btnGap, y));

        pack();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (fired) return;

        remainingSeconds -= dt;
        if (remainingSeconds <= 0) {
            remainingSeconds = 0;
            fired = true;
            countdownLabel.settext(formatCountdown());
            if (onTimeout != null) {
                onTimeout.run();
            }
            close();
            return;
        }
        countdownLabel.settext(formatCountdown());
    }

    private String formatCountdown() {
        int secs = (int) Math.ceil(remainingSeconds);
        return L10n.get("autologout.countdown", secs);
    }

    private void doDelay() {
        if (!fired && onDelay != null) {
            onDelay.run();
        }
        close();
    }

    @Override
    public boolean keydown(Widget.KeyDownEvent ev) {
        if (ev.code == KeyEvent.VK_ESCAPE || ev.code == KeyEvent.VK_ENTER) {
            doDelay();
            return true;
        }
        return super.keydown(ev);
    }

    public void close() {
        hide();
        destroy();
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            doDelay();
        } else {
            super.wdgmsg(msg, args);
        }
    }
}
