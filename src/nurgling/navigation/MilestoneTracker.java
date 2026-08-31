package nurgling.navigation;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MiniMap;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tools.MilestoneRegistry;

/**
 * Records milestone (signpost) travel: the player explicitly arms recording on a specific
 * milestone gob (Ctrl+right-click it -&gt; "Record Milestone", see
 * {@code nurgling.contextmenu.RecordMilestoneAction}), then travels it normally (the in-game
 * dialog's "Travel" button) - the resulting teleport is detected and logged into
 * {@link MilestoneRegistry}.
 * <p>
 * This replaced an earlier always-on design that watched every right-click on every
 * milestone-named gob automatically. That was dropped by direct request - it fired on every
 * incidental milestone interaction, not just ones the player actually wanted recorded, and every
 * teleport observed afterward (however unrelated) had to be plausibly attributed back to
 * whichever milestone was clicked most recently. Arming is now the deliberate, single source of
 * "what am I trying to record" - once armed, the class still just watches for the next large
 * player-position jump (a real Travel teleport, as opposed to "Follow" which walks there) and
 * settles non-blockingly across several ticks before recording, since a milestone jump can land
 * somewhere far more expensive to load than an adjacent grid.
 */
public class MilestoneTracker {

    private static final long CHECK_INTERVAL_MS = 100;

    // If armed but no qualifying teleport happens within this window, recording is abandoned -
    // the player likely decided not to travel, or armed the wrong gob. Generous since arming is
    // now a deliberate one-off action, not something that needs to defend against firing on
    // every incidental click.
    private static final long ARM_TIMEOUT_MS = 60_000;

    // Non-blocking settle window after a teleport is detected, before giving up on sessloc ever
    // reflecting the new segment and just trusting whatever it currently is.
    private static final long SETTLE_TIMEOUT_MS = 5_000;

    // A genuine Travel teleport is a server-driven instantaneous position jump - ordinary
    // movement (even sprinting) can't cover this far within one CHECK_INTERVAL_MS tick. World
    // units (same scale as Gob.rc / MCache.tilesz, ~11 units/tile) - comfortably above sprint
    // speed, tune against a real teleport/Follow-walk once tested live.
    private static final double TELEPORT_DELTA_THRESHOLD = 100.0;

    private long lastCheckTime = 0;
    private Coord2d lastPlayerRc = null;

    // The armed milestone, set by RecordMilestoneAction.performUi() via arm(). Cleared once a
    // teleport is detected (moves into the pending/* fields below) or the arm window times out.
    private Gob armedGob = null;
    private MilestoneRegistry.Location armedSrcLocation = null;
    private long armedAt = 0;

    // Set once a qualifying teleport is detected while armed, while non-blockingly waiting for
    // sessloc to settle onto the new segment.
    private String pendingHash = null;
    private String pendingGobName = null;
    private MilestoneRegistry.Location pendingSrcLocation = null;
    private long pendingSince = 0;

    /** Arms recording on this gob - called from the UI thread by RecordMilestoneAction. The next
     *  qualifying teleport (not the next grid-boundary walk) gets attributed to it. */
    public void arm(Gob milestoneGob) {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || milestoneGob == null || milestoneGob.ngob == null) {
            return;
        }
        MilestoneRegistry.Location loc = resolveLocation(gui, milestoneGob.rc);
        if (loc == null) {
            gui.msg("Milestone: couldn't resolve a location for this gob - not armed.");
            return;
        }
        armedGob = milestoneGob;
        armedSrcLocation = loc;
        armedAt = System.currentTimeMillis();
        clearPending();
        gui.msg("Milestone: armed " + milestoneGob.ngob.name + " - use Travel now to record its destination.");
    }

    /** Call every game tick - internally throttled, safe to call frequently. */
    public void tick() {
        Object val = NConfig.get(NConfig.Key.milestoneTracking);
        boolean enabled = !(val instanceof Boolean) || (Boolean) val;
        if (!enabled) {
            reset();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckTime = now;

        try {
            doCheck(now);
        } catch (Exception e) {
            // A passive background observer must never take the render/tick thread down with it.
        }
    }

    private void doCheck(long now) {
        NGameUI gui = NUtils.getGameUI();
        Gob player = NUtils.player();
        if (gui == null || gui.mmap == null || player == null) {
            return;
        }

        Coord2d currentRc = player.rc;

        if (pendingHash != null) {
            // Resolve the player's own CURRENT position via the same authoritative gridinfo
            // lookup used for the milestone's own location (resolveLocation), rather than
            // gui.mmap.sessloc - SessionLocator (what sessloc comes from) doesn't actually pick
            // the grid the player is standing in, it returns whichever loaded grid happens to
            // resolve first, so using it here recorded the wrong destination (reported live).
            MilestoneRegistry.Location destLoc = resolveLocation(gui, currentRc);
            boolean segmentChanged = destLoc != null && destLoc.seg != pendingSrcLocation.seg;
            boolean timedOut = (now - pendingSince) >= SETTLE_TIMEOUT_MS;
            if (segmentChanged || timedOut) {
                if (destLoc != null) {
                    MilestoneRegistry.recordDestination(pendingHash, pendingGobName, pendingSrcLocation, destLoc);
                    gui.msg("Milestone: recorded destination for " + pendingGobName);
                } else {
                    gui.msg("Milestone: gave up waiting to settle for " + pendingGobName);
                }
                clearPending();
            }
            lastPlayerRc = currentRc;
            return;
        }

        if (armedGob != null) {
            if ((now - armedAt) > ARM_TIMEOUT_MS) {
                gui.msg("Milestone: recording window expired for " + armedGob.ngob.name + " - not armed anymore.");
                clearArmed();
            } else {
                boolean bigJump = lastPlayerRc != null && currentRc.dist(lastPlayerRc) >= TELEPORT_DELTA_THRESHOLD;
                if (bigJump && armedGob.ngob != null && armedGob.ngob.hash != null) {
                    pendingHash = armedGob.ngob.hash;
                    pendingGobName = armedGob.ngob.name;
                    pendingSrcLocation = armedSrcLocation;
                    pendingSince = now;
                    gui.msg("Milestone: teleport detected from " + pendingGobName + ", waiting to record destination...");
                    clearArmed();
                }
            }
        }

        lastPlayerRc = currentRc;
    }

    /** Resolves an arbitrary absolute world position into a durable, cross-session Location
     *  (segment id + segment-tile coord) via {@code MapFile.gridinfo} - the same lookup
     *  {@code haven.MiniMap.MapLocator} uses to resolve the *player's own* live position
     *  (src/haven/MiniMap.java:153-171), just generalized to an arbitrary Coord2d instead of the
     *  map view's current center. This replaced an earlier version that instead approximated a
     *  gob's location via delta math against the live sessloc (target.rc - player.rc, converted
     *  to a tile offset from sessloc.tc) - reported live as consistently wrong ("not anywhere
     *  close to where it should be"), because that math has no actual basis: sessloc.tc is a
     *  segment-space coordinate while Gob.rc is an unrelated live-session MCache-space one, and
     *  nothing ties their origins together the way that approximation assumed. Resolving through
     *  MapFile.gridinfo (which is what actually records where each loaded grid sits within a
     *  segment) is the real, authoritative conversion - not an approximation. */
    private static MilestoneRegistry.Location resolveLocation(NGameUI gui, Coord2d worldPos) {
        if (gui.mmap == null || gui.map == null || gui.map.glob == null) {
            return null;
        }
        try {
            MiniMap.Location loc = gui.mmap.resolve(file -> {
                Coord mc = worldPos.floor(MCache.tilesz);
                MCache.Grid plg = gui.map.glob.map.getgrid(mc.div(MCache.cmaps));
                if (plg == null) {
                    throw new Loading("no grid loaded at that position");
                }
                MapFile.GridInfo info = file.gridinfo.get(plg.id);
                if (info == null) {
                    throw new Loading("no gridinfo for that grid yet");
                }
                MapFile.Segment seg = file.segments.get(info.seg);
                if (seg == null) {
                    throw new Loading("no segment for that grid yet");
                }
                return new MiniMap.Location(seg, info.sc.mul(MCache.cmaps).add(mc.sub(plg.ul)));
            });
            return new MilestoneRegistry.Location(loc.seg.id, loc.tc);
        } catch (Loading l) {
            return null;
        }
    }

    private void clearArmed() {
        armedGob = null;
        armedSrcLocation = null;
        armedAt = 0;
    }

    private void clearPending() {
        pendingHash = null;
        pendingGobName = null;
        pendingSrcLocation = null;
        pendingSince = 0;
    }

    public void reset() {
        lastPlayerRc = null;
        clearArmed();
        clearPending();
    }
}
