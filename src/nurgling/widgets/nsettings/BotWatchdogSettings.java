package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NAlarmManager;
import nurgling.NConfig;

/**
 * Settings panel for the bot stall watchdog.
 *
 * The watchdog flags a bot that stops making progress - either parked on an
 * NTask whose condition never becomes true, or spinning in an action loop.
 * Detection is non-fatal unless auto-interrupt is enabled here.
 */
public class BotWatchdogSettings extends Panel {

    private static final int DEF_TIMEOUT = 90;
    private static final int DEF_AUTO_INTERRUPT_DELAY = 300;

    private TextEntry stallTimeout;
    private CheckBox alarm;
    private CheckBox discord;
    private CheckBox autoInterrupt;
    private TextEntry autoInterruptDelay;

    public BotWatchdogSettings() {
        super("Bot watchdog");

        int margin = UI.scale(10);
        int labelWidth = UI.scale(200);
        int entryWidth = UI.scale(80);
        int y = UI.scale(40);
        int lineHeight = UI.scale(28);
        int sectionGap = UI.scale(15);

        add(new Label("Detects bots that stop making progress and marks them as stalled."),
            new Coord(margin, y));
        y += UI.scale(18);
        add(new Label("A stalled bot keeps running unless auto-interrupt is enabled below."),
            new Coord(margin, y));
        y += lineHeight + sectionGap;

        add(new Label("● Detection"), new Coord(margin, y));
        y += UI.scale(22);

        add(new Label("Flag as stalled after:"), new Coord(margin, y));
        stallTimeout = add(new TextEntry(entryWidth, ""), new Coord(margin + labelWidth, y));
        add(new Label("seconds"), new Coord(margin + labelWidth + entryWidth + UI.scale(5), y));
        y += UI.scale(22);
        add(new Label("Tasks that legitimately wait longer (growth, firing, travel) set their own limit."),
            new Coord(margin, y));
        y += lineHeight + sectionGap;

        add(new Label("● Notifications"), new Coord(margin, y));
        y += UI.scale(22);

        alarm = add(new CheckBox("Play an alarm sound") {
            public void set(boolean val) { a = val; }
        }, new Coord(margin, y));
        y += lineHeight;

        discord = add(new CheckBox("Send a Discord notification (uses the \"general\" webhook)") {
            public void set(boolean val) { a = val; }
        }, new Coord(margin, y));
        y += lineHeight;

        add(new Button(UI.scale(130), "Test Sound") {
            @Override
            public void click() {
                NAlarmManager.play("alarm/alarm");
            }
        }, new Coord(margin, y));
        y += lineHeight + sectionGap;

        add(new Label("● Auto-interrupt"), new Coord(margin, y));
        y += UI.scale(22);

        autoInterrupt = add(new CheckBox("Stop a bot that stays stalled") {
            public void set(boolean val) { a = val; }
        }, new Coord(margin, y));
        y += lineHeight;

        add(new Label("Stop after stalled for:"), new Coord(margin, y));
        autoInterruptDelay = add(new TextEntry(entryWidth, ""), new Coord(margin + labelWidth, y));
        add(new Label("seconds"), new Coord(margin + labelWidth + entryWidth + UI.scale(5), y));
    }

    @Override
    public void load() {
        stallTimeout.settext(String.valueOf(getConfigInt(NConfig.Key.botStallTimeout, DEF_TIMEOUT)));
        alarm.a = getConfigBool(NConfig.Key.botStallAlarm, true);
        discord.a = getConfigBool(NConfig.Key.botStallDiscord, false);
        autoInterrupt.a = getConfigBool(NConfig.Key.botStallAutoInterrupt, false);
        autoInterruptDelay.settext(String.valueOf(
            getConfigInt(NConfig.Key.botStallAutoInterruptDelay, DEF_AUTO_INTERRUPT_DELAY)));
    }

    @Override
    public void save() {
        NConfig.set(NConfig.Key.botStallTimeout, parseIntSafe(stallTimeout.text(), DEF_TIMEOUT));
        NConfig.set(NConfig.Key.botStallAlarm, alarm.a);
        NConfig.set(NConfig.Key.botStallDiscord, discord.a);
        NConfig.set(NConfig.Key.botStallAutoInterrupt, autoInterrupt.a);
        NConfig.set(NConfig.Key.botStallAutoInterruptDelay,
            parseIntSafe(autoInterruptDelay.text(), DEF_AUTO_INTERRUPT_DELAY));
        NConfig.needUpdate();
    }

    private int getConfigInt(NConfig.Key key, int defaultValue) {
        Object val = NConfig.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return defaultValue;
    }

    private boolean getConfigBool(NConfig.Key key, boolean defaultValue) {
        Object val = NConfig.get(key);
        return (val instanceof Boolean) ? (Boolean) val : defaultValue;
    }

    private int parseIntSafe(String text, int defaultValue) {
        try {
            int v = Integer.parseInt(text.trim());
            return (v > 0) ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
