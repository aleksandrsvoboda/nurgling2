package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;

/**
 * Zero-size widget that monitors player energy and triggers auto-logout
 * when energy falls below a configured threshold.
 * Shows a countdown popup before logging out, with an option to delay.
 */
public class AutoLogoutWidget extends Widget {

    private int lastEnergy = 10000;
    private boolean triggered = false;
    private AutoLogoutPopup currentPopup = null;

    // Delay tracking
    private boolean delaying = false;
    private long delayUntil = 0;

    public AutoLogoutWidget() {
        super(Coord.z);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);

        if (NUtils.getGameUI() == null) {
            return;
        }

        Boolean enabled = (Boolean) NConfig.get(NConfig.Key.autoLogoutEnabled);
        if (enabled == null || !enabled) {
            return;
        }

        double energyRaw = NUtils.getEnergy();
        if (energyRaw < 0) {
            return;
        }

        int currentEnergy = (int) (energyRaw * 10000);
        int threshold = getConfigInt(NConfig.Key.autoLogoutThreshold, 0);

        // Feature disabled if threshold is 0
        if (threshold <= 0) {
            lastEnergy = currentEnergy;
            return;
        }

        // Energy rose above threshold — cancel everything
        if (currentEnergy > threshold) {
            reset();
            lastEnergy = currentEnergy;
            return;
        }

        // Check if we're in a delay period
        if (delaying) {
            if (System.currentTimeMillis() >= delayUntil) {
                // Delay expired, show popup again
                delaying = false;
                showPopup();
            }
            lastEnergy = currentEnergy;
            return;
        }

        // Threshold crossing downward — trigger popup
        if (currentEnergy <= threshold && !triggered) {
            triggered = true;
            showPopup();
        }

        lastEnergy = currentEnergy;
    }

    private void showPopup() {
        if (ui == null || ui.root == null) return;

        // Close existing popup if any
        if (currentPopup != null) {
            currentPopup.close();
            currentPopup = null;
        }

        int countdown = getConfigInt(NConfig.Key.autoLogoutCountdown, 30);
        int delay = getConfigInt(NConfig.Key.autoLogoutDelay, 60);

        currentPopup = new AutoLogoutPopup(
            countdown,
            delay,
            this::doLogout,
            this::doDelay
        );

        Coord screenSz = ui.root.sz;
        Coord popupSz = currentPopup.sz;
        Coord pos = screenSz.sub(popupSz).div(2);
        ui.root.add(currentPopup, pos);
    }

    private void doLogout() {
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if (gui != null) {
            // Interrupt all running bots before logging out
            if (gui.biw != null && gui.biw.hasRunningBots()) {
                gui.biw.interruptAll();
            }
            gui.act("lo");
        }
    }

    private void doDelay() {
        int delaySec = getConfigInt(NConfig.Key.autoLogoutDelay, 60);
        delaying = true;
        delayUntil = System.currentTimeMillis() + delaySec * 1000L;
        currentPopup = null;
    }

    private void reset() {
        triggered = false;
        delaying = false;
        delayUntil = 0;
        if (currentPopup != null) {
            currentPopup.close();
            currentPopup = null;
        }
    }

    private int getConfigInt(NConfig.Key key, int defaultValue) {
        Object val = NConfig.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return defaultValue;
    }

    @Override
    public void destroy() {
        if (currentPopup != null) {
            currentPopup.close();
            currentPopup = null;
        }
        super.destroy();
    }

    /**
     * Reset state — useful for testing
     */
    public void resetAlerts() {
        reset();
        lastEnergy = 10000;
    }
}
