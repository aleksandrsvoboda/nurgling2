package nurgling;

import haven.Audio;
import haven.Resource;
import haven.UI;

import java.util.Map;
import java.util.WeakHashMap;

public class NAlarmManager {
    static final long duration = 1000;

    /** Per session, so two sessions alarming at once each get their sound. */
    private static class Last {
        String res = null;
        long startTick = -1000;
    }
    private static final Map<UI, Last> last = new WeakHashMap<>();

    /** Plays through the session the calling thread belongs to (bot and background threads are bound to theirs). */
    public static void play(String resName) {
        play(NUtils.getUI(), resName);
    }

    public static void play(UI ui, String resName) {
        if (ui == null)
            return;
        synchronized (last) {
            Last l = last.computeIfAbsent(ui, k -> new Last());
            if (l.startTick > NUtils.getTickId())
                l.startTick = 0;
            if (l.res != null && !l.res.equals(resName) || NUtils.getTickId() - l.startTick > duration) {
                sfx(ui, Audio.fromres(Resource.local().loadwait(resName)));
                l.res = resName;
                l.startTick = NUtils.getTickId();
            }
        }
    }

    /**
     * Plays an alarm sound for a session even when that session is muted: straight into the shared mixer,
     * at the session's interface volume, instead of through its (mutable) interface channel.
     */
    public static void sfx(UI ui, Audio.CS clip) {
        ui.audio.sys.mixer.add(new Audio.VolAdjust(clip, ui.audio.aui.volume));
    }
}
