package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NAlarmManager;
import nurgling.NConfig;
import nurgling.i18n.L10n;
import nurgling.timers.TimerNotifier;
import nurgling.widgets.cookbook.PillButton;

/**
 * Settings for timer notifications: which sound each kind of timer plays, flashing the taskbar when the
 * game is in the background, and holding banners during a fight.
 */
public class TimerSettings extends Panel {
    private final NConfig.Key[] keys = {NConfig.Key.timerSoundResource, NConfig.Key.timerSoundPin, NConfig.Key.timerSoundReminder};
    private final String[] sounds = new String[keys.length];
    private final PillButton[] soundButtons = new PillButton[keys.length];
    private final CheckBox flash;
    private final CheckBox combatQuiet;
    private final TextEntry myCharacters;
    private final PillButton headsUp;
    private final CheckBox notifyDone;
    private static final int[] HEADS_UP_MINUTES = {0, 15, 60, 180};
    private int headsUpMinutes = 60;

    public TimerSettings() {
        super(L10n.get("timers.settings.title"));
        int margin = UI.scale(10);
        int y = UI.scale(40);
        add(new Label(L10n.get("timers.settings.sounds")), new Coord(margin, y));
        y += UI.scale(24);
        String[] labels = {"timers.settings.sound_resource", "timers.settings.sound_pin", "timers.settings.sound_reminder"};
        for(int i = 0; i < keys.length; i++) {
            final int idx = i;
            add(new Label(L10n.get(labels[i])), new Coord(margin, y + UI.scale(4)));
            soundButtons[i] = add(new PillButton(L10n.get("timers.settings.sound"), true, () -> cycle(idx)).minWidth(UI.scale(170)),
                new Coord(margin + UI.scale(140), y));
            add(new PillButton(L10n.get("timers.settings.test"), false, () -> play(idx)),
                new Coord(margin + UI.scale(320), y));
            y += UI.scale(30);
        }
        y += UI.scale(10);
        flash = add(new CheckBox(L10n.get("timers.settings.flash")), new Coord(margin, y));
        y += UI.scale(26);
        combatQuiet = add(new CheckBox(L10n.get("timers.settings.combat_quiet")), new Coord(margin, y));
        y += UI.scale(34);

        add(new Label(L10n.get("tasks.settings.heading")), new Coord(margin, y));
        y += UI.scale(24);
        add(new Label(L10n.get("tasks.settings.heads_up")), new Coord(margin, y + UI.scale(4)));
        headsUp = add(new PillButton(L10n.get("tasks.settings.heads_up_button"), true, this::cycleHeadsUp).minWidth(UI.scale(120)),
            new Coord(margin + UI.scale(200), y));
        y += UI.scale(30);
        notifyDone = add(new CheckBox(L10n.get("tasks.settings.notify_done")), new Coord(margin, y));
        y += UI.scale(30);
        add(new Label(L10n.get("tasks.settings.my_characters")), new Coord(margin, y));
        y += UI.scale(20);
        myCharacters = add(new TextEntry(UI.scale(420), ""), new Coord(margin, y));
        y += UI.scale(22);
        add(new Label(L10n.get("tasks.settings.my_characters_help")), new Coord(margin, y));
        y += UI.scale(34);
        add(new Label(L10n.get("timers.settings.help")), new Coord(margin, y));
    }

    /** Next sound in the list; plays it so the choice can be heard. */
    private void cycle(int idx) {
        String[] all = TimerNotifier.SOUNDS;
        int cur = java.util.Arrays.asList(all).indexOf(sounds[idx]);
        sounds[idx] = all[(cur + 1) % all.length];
        showSound(idx);
        play(idx);
    }

    private void cycleHeadsUp() {
        int i = 0;
        while(i < HEADS_UP_MINUTES.length && HEADS_UP_MINUTES[i] != headsUpMinutes)
            i++;
        headsUpMinutes = HEADS_UP_MINUTES[(i + 1) % HEADS_UP_MINUTES.length];
        showHeadsUp();
    }

    private void showHeadsUp() {
        headsUp.suffix(headsUpMinutes == 0 ? L10n.get("tasks.settings.off")
            : nurgling.timers.TimerDurations.formatShort(headsUpMinutes * 60_000L));
    }

    private void play(int idx) {
        if(!TimerNotifier.SOUND_NONE.equals(sounds[idx]))
            NAlarmManager.play(sounds[idx]);
    }

    private void showSound(int idx) {
        String s = sounds[idx];
        soundButtons[idx].suffix(TimerNotifier.SOUND_NONE.equals(s) ? L10n.get("timers.settings.silent") : s.substring(s.indexOf('/') + 1));
    }

    @Override
    public void load() {
        for(int i = 0; i < keys.length; i++) {
            Object v = NConfig.get(keys[i]);
            sounds[i] = (v instanceof String) ? (String) v : TimerNotifier.SOUNDS[0];
            showSound(i);
        }
        flash.a = Boolean.TRUE.equals(NConfig.get(NConfig.Key.timerFlashTaskbar));
        combatQuiet.a = Boolean.TRUE.equals(NConfig.get(NConfig.Key.timerCombatQuiet));
        Object hu = NConfig.get(NConfig.Key.taskHeadsUpMinutes);
        headsUpMinutes = (hu instanceof Number) ? ((Number) hu).intValue() : 60;
        showHeadsUp();
        notifyDone.a = Boolean.TRUE.equals(NConfig.get(NConfig.Key.taskNotifyDone));
        Object mc = NConfig.get(NConfig.Key.timerMyCharacters);
        myCharacters.settext((mc instanceof String) ? (String) mc : "");
    }

    @Override
    public void save() {
        for(int i = 0; i < keys.length; i++)
            NConfig.set(keys[i], sounds[i]);
        NConfig.set(NConfig.Key.timerFlashTaskbar, flash.a);
        NConfig.set(NConfig.Key.timerCombatQuiet, combatQuiet.a);
        NConfig.set(NConfig.Key.taskHeadsUpMinutes, headsUpMinutes);
        NConfig.set(NConfig.Key.taskNotifyDone, notifyDone.a);
        NConfig.set(NConfig.Key.timerMyCharacters, myCharacters.text().trim());
        NConfig.needUpdate();
    }
}
