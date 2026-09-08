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

/** Records milestone (signpost) travel: player arms a specific gob, then travels it; the resulting teleport is logged into {@link MilestoneRegistry}. */
public class MilestoneTracker {

    private static final long CHECK_INTERVAL_MS = 100;

    // Abandon recording if no qualifying teleport happens within this window of arming.
    private static final long ARM_TIMEOUT_MS = 60_000;

    // Non-blocking settle window after a teleport before trusting sessloc as-is.
    private static final long SETTLE_TIMEOUT_MS = 5_000;

    // World units a position jump must exceed to count as a teleport, not ordinary movement.
    private static final double TELEPORT_DELTA_THRESHOLD = 100.0;

    private long lastCheckTime = 0;
    private Coord2d lastPlayerRc = null;

    // The armed milestone; cleared once a teleport is detected or the arm window times out.
    private Gob armedGob = null;
    private MilestoneRegistry.Location armedSrcLocation = null;
    private long armedAt = 0;

    // Set once a qualifying teleport is detected, while waiting for sessloc to settle.
    private String pendingHash = null;
    private String pendingGobName = null;
    private MilestoneRegistry.Location pendingSrcLocation = null;
    private long pendingSince = 0;

    /** Arms recording on this gob; the next qualifying teleport gets attributed to it. */
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
            // Use the same authoritative gridinfo lookup as the milestone's own location, not gui.mmap.sessloc.
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
                // The armed gob can have unloaded (out of render range) during the timeout window -
                // ngob would be null then, same as the bigJump branch below already accounts for.
                String armedName = armedGob.ngob != null ? armedGob.ngob.name : "the milestone";
                gui.msg("Milestone: recording window expired for " + armedName + " - not armed anymore.");
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

    /** Resolves an arbitrary world position into a durable, cross-session Location via {@code MapFile.gridinfo}. */
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
