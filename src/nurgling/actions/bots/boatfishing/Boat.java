package nurgling.actions.bots.boatfishing;

import haven.Coord;
import haven.Coord2d;
import haven.Following;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;

import static haven.OCache.posres;

/** The boat the player sits in. Any seat can fish; only the steering seat can row. */
final class Boat {
    private Boat() {}

    /** The boat the player is aboard (any seat), or null. */
    static Gob aboard(Gob player) {
        if (player == null)
            return null;
        Following f = player.getattr(Following.class);
        if (f == null)
            return null;
        Gob v = player.glob.oc.getgob(f.tgt);
        if (v == null || v.ngob == null || v.ngob.name == null)
            return null;
        String n = v.ngob.name;
        if (n.endsWith("/vehicle/rowboat") || n.endsWith("/vehicle/dugout") || n.endsWith("/vehicle/coracle")
                || n.endsWith("/vehicle/snekkja") || n.endsWith("/vehicle/knarr"))
            return v;
        return null;
    }

    /** True when the player sits where map clicks steer the boat. Same seats as WorldExplorer.vessel. */
    static boolean steering(Gob player) {
        Gob v = aboard(player);
        if (v == null)
            return false;
        Following f = player.getattr(Following.class);
        String n = v.ngob.name;
        if (n.endsWith("/vehicle/rowboat"))
            return "d".equals(f.xfname);
        if (n.endsWith("/vehicle/snekkja") || n.endsWith("/vehicle/knarr"))
            return "m0".equals(f.xfname);
        return true;
    }

    /** Rows toward {@code to} until the player is within {@code stop} of {@code target}, the boat stalls, or time runs out.
     *  Leaves the boat stopped. Returns true when the target ended up within cast range. */
    static boolean rowCloser(NGameUI gui, Coord2d target) throws InterruptedException {
        Gob player = NUtils.player();
        Coord2d to = CastGeometry.rowTo(player.rc, target);
        System.out.println("[BoatFisher] rowing from " + player.rc + " to " + to + " (aim " + target + ")");
        NUtils.lclick(to);
        Waits.Result r = Waits.until(new Waits.Probe() {
            Coord2d last = null;
            long still = 0;

            @Override
            public boolean done() {
                Gob p = NUtils.player();
                if (p == null || aboard(p) == null)
                    return true;
                if (p.rc.dist(target) <= CastGeometry.ROW_STOP)
                    return true;
                long now = System.currentTimeMillis();
                if (last == null || p.rc.dist(last) > 0.5) {
                    last = p.rc;
                    still = now;
                }
                // Snagged on shore or another obstacle: no movement for 3 s (after giving it time to start).
                return still != 0 && now - still > 3000;
            }
        }, 90_000);
        halt(gui);
        Gob p = NUtils.player();
        boolean ok = p != null && aboard(p) != null && CastGeometry.inRange(p.rc, target);
        System.out.println("[BoatFisher] rowing ended at " + (p == null ? "?" : p.rc) + ", timedOut=" + r.timedOut + ", inRange=" + ok);
        return ok;
    }

    /** Stops the boat where it is. */
    static void halt(NGameUI gui) {
        Gob player = NUtils.player();
        if (player != null && gui.map != null)
            gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 1, 0);
    }
}
