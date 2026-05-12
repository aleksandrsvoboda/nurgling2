package nurgling.db.service;

import nurgling.NUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Process-wide bus for area sync events. Drops a toast into the GameUI when
 * an event is published and retains a bounded history that the recent-changes
 * widget can display.
 */
public final class AreaSyncEvents {
    private static final int MAX_HISTORY = 50;
    private static final Deque<AreaSyncEvent> history = new ArrayDeque<>();
    private static final Object lock = new Object();

    private AreaSyncEvents() {}

    public static void publish(AreaSyncEvent event) {
        if (event == null) return;
        synchronized (lock) {
            history.addFirst(event);
            while (history.size() > MAX_HISTORY) {
                history.removeLast();
            }
        }
        try {
            if (NUtils.getGameUI() != null) {
                NUtils.getGameUI().msg(event.toToast());
            }
        } catch (Exception ignore) {
            // GUI not available - history still recorded
        }
    }

    public static List<AreaSyncEvent> recent() {
        synchronized (lock) {
            return new ArrayList<>(history);
        }
    }

    public static void clear() {
        synchronized (lock) {
            history.clear();
        }
    }
}
