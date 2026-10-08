package monitoring;

import haven.Coord2d;
import haven.Gob;
import nurgling.NCore;
import nurgling.NGameUI;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clears the stored items of a stockpile that is emptied without its window: shift + right-click
 * takes a whole inventory straight from the pile, and taking the last item destroys the pile.
 *
 * <p>Every right-clicked stockpile is watched for a while. If its gob goes away while the player
 * still stands next to where it was, it was destroyed, not merely unloaded (unloading happens
 * at the edge of view, far from the player), and its rows are deleted.
 *
 * <p>One per session, fed from NMapView.wdgmsg and ticked from NGameUI.tick.
 */
public class PileWatch {
    /** How long after the click the pile is watched: covers walking to it first. */
    private static final long WATCH_MS = 60_000;
    /** A pile can only be emptied from right next to it. */
    private static final double NEAR = 33;

    private final NGameUI gui;
    private final Map<Long, Watched> watched = new ConcurrentHashMap<>();

    private static final class Watched {
        final String hash;
        final Coord2d rc;
        final long until;

        Watched(String hash, Coord2d rc, long until) {
            this.hash = hash;
            this.rc = rc;
            this.until = until;
        }
    }

    public PileWatch(NGameUI gui) {
        this.gui = gui;
    }

    public void noteMapClick(Object[] args) {
        if (args.length < 6 || !(args[2] instanceof Integer) || (Integer) args[2] != 3 || !(args[5] instanceof Integer))
            return;
        long id = Integer.toUnsignedLong((Integer) args[5]);
        Gob gob = gui.ui.sess.glob.oc.getgob(id);
        if (gob == null || gob.ngob == null || gob.ngob.storageHash() == null || gob.ngob.name == null
                || !gob.ngob.name.startsWith("gfx/terobjs/stockpile"))
            return;
        watched.put(id, new Watched(gob.ngob.storageHash(), gob.rc, System.currentTimeMillis() + WATCH_MS));
    }

    public void tick() {
        if (watched.isEmpty())
            return;
        long now = System.currentTimeMillis();
        Gob player = (gui.map != null) ? gui.map.player() : null;
        for (Iterator<Map.Entry<Long, Watched>> it = watched.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Watched> e = it.next();
            Watched w = e.getValue();
            if (gui.ui.sess.glob.oc.getgob(e.getKey()) != null) {
                if (now > w.until)
                    it.remove();
                continue;
            }
            it.remove();
            if (player != null && player.rc.dist(w.rc) <= NEAR) {
                System.out.println("[BulkStorage] pile emptied without its window -> 0 row(s) for " + w.hash.substring(0, 8));
                NCore.writeBulkStorage(w.hash, 0, null, Collections.emptyList());
            }
        }
    }
}
