package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NConfig;
import nurgling.i18n.L10n;
import nurgling.widgets.AutoLogoutPopup;

/**
 * Settings panel for configuring auto-logout on low energy.
 */
public class AutoLogoutSettings extends Panel {

    private CheckBox masterEnable;
    private TextEntry thresholdEntry;
    private TextEntry countdownEntry;
    private TextEntry delayEntry;

    public AutoLogoutSettings() {
        super(L10n.get("autologout.settings_title"));

        int margin = UI.scale(10);
        int labelWidth = UI.scale(200);
        int entryWidth = UI.scale(80);
        int y = UI.scale(40);
        int lineHeight = UI.scale(28);
        int sectionGap = UI.scale(15);

        // Description
        add(new Label(L10n.get("autologout.description")), new Coord(margin, y));
        y += lineHeight;

        // Master enable
        masterEnable = add(new CheckBox(L10n.get("autologout.enable")) {
            public void set(boolean val) {
                a = val;
            }
        }, new Coord(margin, y));
        y += lineHeight + sectionGap;

        // Threshold
        add(new Label(L10n.get("autologout.threshold_label")), new Coord(margin, y));
        y += UI.scale(22);

        add(new Label(L10n.get("autologout.threshold_at")), new Coord(margin, y));
        thresholdEntry = add(new TextEntry(entryWidth, ""), new Coord(margin + labelWidth, y));
        add(new Label(L10n.get("autologout.energy_unit")), new Coord(margin + labelWidth + entryWidth + UI.scale(5), y));
        y += lineHeight + sectionGap;

        // Countdown timer
        add(new Label(L10n.get("autologout.timing_label")), new Coord(margin, y));
        y += UI.scale(22);

        add(new Label(L10n.get("autologout.countdown_label")), new Coord(margin, y));
        countdownEntry = add(new TextEntry(entryWidth, ""), new Coord(margin + labelWidth, y));
        add(new Label(L10n.get("autologout.seconds_unit")), new Coord(margin + labelWidth + entryWidth + UI.scale(5), y));
        y += lineHeight;

        // Delay duration
        add(new Label(L10n.get("autologout.delay_label")), new Coord(margin, y));
        delayEntry = add(new TextEntry(entryWidth, ""), new Coord(margin + labelWidth, y));
        add(new Label(L10n.get("autologout.seconds_unit")), new Coord(margin + labelWidth + entryWidth + UI.scale(5), y));
        y += lineHeight + sectionGap;

        // Test button
        add(new Label(L10n.get("autologout.test_section")), new Coord(margin, y));
        y += UI.scale(22);

        add(new Button(UI.scale(150), L10n.get("autologout.test_button")) {
            @Override
            public void click() {
                testPopup();
            }
        }, new Coord(margin, y));

        y += lineHeight + sectionGap;

        // Info notes
        add(new Label(L10n.get("autologout.note1")), new Coord(margin, y));
        y += UI.scale(18);
        add(new Label(L10n.get("autologout.note2")), new Coord(margin, y));
    }

    private void testPopup() {
        if (ui == null || ui.root == null) return;

        int countdown = parseIntSafe(countdownEntry.text(), 30);
        int delay = parseIntSafe(delayEntry.text(), 60);

        AutoLogoutPopup popup = new AutoLogoutPopup(
            countdown,
            delay,
            () -> { /* no-op for test */ },
            () -> { /* no-op for test */ }
        );

        Coord screenSz = ui.root.sz;
        Coord popupSz = popup.sz;
        Coord pos = screenSz.sub(popupSz).div(2);
        ui.root.add(popup, pos);
    }

    @Override
    public void load() {
        Boolean enabled = (Boolean) NConfig.get(NConfig.Key.autoLogoutEnabled);
        masterEnable.a = enabled != null && enabled;

        thresholdEntry.settext(String.valueOf(getConfigInt(NConfig.Key.autoLogoutThreshold, 0)));
        countdownEntry.settext(String.valueOf(getConfigInt(NConfig.Key.autoLogoutCountdown, 30)));
        delayEntry.settext(String.valueOf(getConfigInt(NConfig.Key.autoLogoutDelay, 60)));
    }

    @Override
    public void save() {
        NConfig.set(NConfig.Key.autoLogoutEnabled, masterEnable.a);
        NConfig.set(NConfig.Key.autoLogoutThreshold, parseIntSafe(thresholdEntry.text(), 0));
        NConfig.set(NConfig.Key.autoLogoutCountdown, parseIntSafe(countdownEntry.text(), 30));
        NConfig.set(NConfig.Key.autoLogoutDelay, parseIntSafe(delayEntry.text(), 60));
        NConfig.needUpdate();
    }

    private int getConfigInt(NConfig.Key key, int defaultValue) {
        Object val = NConfig.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return defaultValue;
    }

    private int parseIntSafe(String text, int defaultValue) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
