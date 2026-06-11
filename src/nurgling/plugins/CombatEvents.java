package nurgling.plugins;

import haven.Fightview;
import haven.Indir;
import haven.Resource;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Generic event seam that lets plugins observe local combat actions.
 *
 * The client calls {@link #fireUsed} when the local player uses a combat
 * maneuver (the Fightview "used" message). It carries no behavior of its own.
 */
public class CombatEvents {

    /** Listener notified when the local player uses a maneuver. */
    public interface UsedListener {
        void onUsed(Fightview fv, Indir<Resource> act);
    }

    private static final CopyOnWriteArrayList<UsedListener> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(UsedListener l) {
        if (l != null) listeners.add(l);
    }

    public static void removeListener(UsedListener l) {
        listeners.remove(l);
    }

    /** Called by core (Fightview) when a maneuver is used. */
    public static void fireUsed(Fightview fv, Indir<Resource> act) {
        for (UsedListener l : listeners) {
            try {
                l.onUsed(fv, act);
            } catch (RuntimeException e) {
                // A misbehaving plugin must not break combat handling.
                e.printStackTrace();
            }
        }
    }
}
