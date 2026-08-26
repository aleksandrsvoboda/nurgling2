package nurgling;

import haven.*;
import nurgling.db.dao.KinPositionDao;
import nurgling.tools.GridLocator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds where this session's kin were last seen, and works out what this session publishes about
 * itself.
 *
 * <p>One of these per {@link NGameUI}. The sync worker writes into it from its own thread and the
 * render passes read from it on the UI thread; the only shared state is a concurrent map of
 * immutable-enough records, and nothing here ever blocks on the UI thread or touches an inventory.
 */
public class KinPositionService {
    /**
     * Republish even when standing still, so a receiver can tell "AFK in the barn" from "logged
     * out". Without it a stationary character's row would age out and they would vanish.
     */
    private static final double HEARTBEAT = 30.0;

    private final NGameUI gui;
    private final Map<String, KinPosition> kin = new ConcurrentHashMap<>();

    /* Last thing we published, so a walking character writes on tile changes and a stationary one
     * writes twice a minute instead of every tick. */
    private long lastGid = -1;
    private Coord lastLocal = null;
    private double lastPush = Double.NEGATIVE_INFINITY;

    public KinPositionService(NGameUI gui) {
        this.gui = gui;
    }

    /* -------------------- read side -------------------- */

    /**
     * Live kin positions, with expired ones dropped and unresolved ones given another go at
     * resolving. Called from the render passes, so it must not block.
     */
    public List<KinPosition> snapshot() {
        if(kin.isEmpty())
            return(Collections.emptyList());
        List<KinPosition> ret = new ArrayList<>(kin.size());
        for(KinPosition kp : kin.values()) {
            if(kp.expired()) {
                kin.remove(kp.charName, kp);
                continue;
            }
            GridLocator.resolve(gui, kp.ref);
            ret.add(kp);
        }
        return(ret);
    }

    /**
     * Replace what we know with one poll's worth of rows.
     *
     * <p>{@code self} is this session's own character, which is filtered out here rather than in
     * SQL: the other characters this player is logged in as should absolutely show up on the map,
     * and only the one doing the drawing should not.
     */
    public void apply(List<KinPositionDao.Row> rows, String self) {
        Set<String> seen = new HashSet<>();
        for(KinPositionDao.Row row : rows) {
            if((row.charName == null) || row.charName.equals(self))
                continue;
            if(row.ageMillis >= KinPosition.DROP_MS)
                continue;
            seen.add(row.charName);
            KinPosition prev = kin.get(row.charName);
            /* A kin who has not moved keeps the record we already resolved, and is only handed the
             * newer age. Replacing it would drop back to an unresolved copy and the marker would
             * blink out until the map-file lookup finished - once per poll, for anyone standing
             * still. */
            if((prev != null) && (prev.ref.gid == row.gid)
               && prev.ref.local.equals(new Coord(row.ox, row.oy))) {
                prev.refresh(row.ageMillis, row.angle);
                continue;
            }
            kin.put(row.charName, new KinPosition(row.charName, row.gid,
                                                  new Coord(row.ox, row.oy), row.angle, row.ageMillis));
        }
        // Anyone whose row is gone has withdrawn it - on logout, or by turning sharing off.
        kin.keySet().retainAll(seen);
    }

    public void clear() {
        kin.clear();
    }

    /* -------------------- write side -------------------- */

    /**
     * What this session should publish, or null if there is nothing to say yet.
     *
     * <p>Returns null when nothing has changed and the heartbeat is not due, which is what keeps a
     * bot looping in one spot from rewriting its row every tick.
     *
     * <p>Called from the sync worker with this session's UI bound, never from the UI thread.
     */
    public KinPositionDao.Push ownPush() {
        if(!Boolean.TRUE.equals(NConfig.get(NConfig.Key.shareKinPosition)))
            return(null);
        String name = gui.chrid;
        if((name == null) || name.isEmpty())
            return(null);
        double angle;
        Coord tc;
        MCache.Grid grid;
        try {
            Gob player = NUtils.player();
            if(player == null)
                return(null);
            /* Every read of the player has to sit inside this guard: the gob's position is as
             * capable of not being ready yet as the gob itself. */
            angle = player.a;
            tc = player.rc.floor(MCache.tilesz);
            synchronized(gui.ui.sess.glob.map.grids) {
                grid = gui.ui.sess.glob.map.grids.get(tc.div(MCache.cmaps));
            }
        } catch(Loading l) {
            return(null);
        }
        if(grid == null)
            return(null);   // standing somewhere not loaded yet; next tick will do
        Coord local = tc.sub(grid.ul);

        double now = Utils.rtime();
        boolean moved = (grid.id != lastGid) || !local.equals(lastLocal);
        if(!moved && (now - lastPush < HEARTBEAT))
            return(null);
        lastGid = grid.id;
        lastLocal = local;
        lastPush = now;
        return(new KinPositionDao.Push(name, grid.id, local.x, local.y, angle));
    }

    /** Force the next {@link #ownPush()} to publish, whatever the heartbeat says. */
    public void resetPush() {
        lastGid = -1;
        lastLocal = null;
        lastPush = Double.NEGATIVE_INFINITY;
    }

    public String charName() {
        return(gui.chrid);
    }
}
