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
    }

    @Override
    public void save() {
        for(int i = 0; i < keys.length; i++)
            NConfig.set(keys[i], sounds[i]);
        NConfig.set(NConfig.Key.timerFlashTaskbar, flash.a);
        NConfig.set(NConfig.Key.timerCombatQuiet, combatQuiet.a);
        NConfig.needUpdate();
    }
}
