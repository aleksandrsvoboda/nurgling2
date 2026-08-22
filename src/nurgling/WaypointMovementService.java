package nurgling;

import haven.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;
import static haven.OCache.posres;

/**
 * Centralized service for managing waypoint-based movement queues.
 * Both the full map window and corner minimap use this service to share the same movement queue.
 *
 * The queue is a plain ordered list: index 0 is the waypoint the character is currently
 * walking to, the rest follow in order. Keeping it index-addressable lets the UI hit-test
 * and drag individual waypoints (see NMiniMap / NMapView waypoint dragging).
 */
public class WaypointMovementService {
    private final NGameUI gui;

    /**
     * A queued waypoint. The id is stable across relocation, so a drag started on
     * a given node keeps addressing that node even when the queue shifts because
     * the character reached the waypoint ahead of it.
     */
    public static class Waypoint {
        public final long id;
        public final MiniMap.Location loc;

        Waypoint(long id, MiniMap.Location loc) {
            this.id = id;
            this.loc = loc;
        }
    }

    /** All queued waypoints, index 0 = current movement target. Guarded by itself. */
    private final ArrayList<Waypoint> waypoints = new ArrayList<>();
    private long nextId = 1;
    /** Whether a move command has already been sent for waypoints[0]. */
    private boolean commanded = false;

    public Coord lastPlayerPos = null;
    public double lastMovementTime = 0;

    /** Rate limit for re-issuing move commands while a waypoint is being dragged. */
    private static final double DRAG_CMD_INTERVAL = 0.2;
    private double lastDragCommandTime = 0;

    /** True while the player steers by hand (hold-to-move); the queue stands still meanwhile. */
    private boolean steerPaused = false;

    public WaypointMovementService(NGameUI gui) {
        this.gui = gui;
    }

    /**
     * Add a waypoint to the movement queue.
     * If no movement is in progress, starts moving to the new waypoint.
     */
    public void addWaypoint(MiniMap.Location loc, MiniMap.Location sessloc) {
        synchronized(waypoints) {
            waypoints.add(new Waypoint(nextId++, loc));
            if((waypoints.size() == 1) && !commanded && (sessloc != null)) {
                lastPlayerPos = null;
                lastMovementTime = Utils.rtime();
                sendMovementCommand(loc, sessloc);
                commanded = true;
            }
        }
    }

    /**
     * Clear the movement queue and stop current movement.
     */
    public void clearQueue() {
        synchronized(waypoints) {
            waypoints.clear();
            commanded = false;
            lastPlayerPos = null;
        }
    }

    /** Snapshot of the queue in walking order, index 0 = current target. */
    public List<Waypoint> snapshot() {
        synchronized(waypoints) {
            if(waypoints.isEmpty())
                return(Collections.emptyList());
            return(new ArrayList<>(waypoints));
        }
    }

    /**
     * Relocate an already queued waypoint, used while the user drags it around.
     *
     * If the dragged waypoint is the one the character is currently running to, a fresh
     * move command is issued so the character immediately follows the new position.
     * Intermediate drag updates are rate limited; pass commit=true (on mouse release)
     * to force the final command through.
     *
     * @return true if the waypoint still exists (and was moved).
     */
    public boolean setWaypoint(long id, MiniMap.Location loc, MiniMap.Location sessloc, boolean commit) {
        if(loc == null)
            return(false);
        synchronized(waypoints) {
            int idx = indexOf(id);
            if(idx < 0)
                return(false);
            MiniMap.Location prev = waypoints.get(idx).loc;
            if(prev.tc.equals(loc.tc) && (prev.seg == loc.seg) && !commit)
                return(true);
            waypoints.set(idx, new Waypoint(id, loc));
            if((idx == 0) && (sessloc != null) && (loc.seg.id == sessloc.seg.id)) {
                double now = Utils.rtime();
                if(commit || (now - lastDragCommandTime > DRAG_CMD_INTERVAL)) {
                    lastDragCommandTime = now;
                    sendMovementCommand(loc, sessloc);
                    commanded = true;
                    // The character is being re-routed on purpose - don't let the
                    // stuck detector fire on the standstill the turnaround causes.
                    lastPlayerPos = null;
                    lastMovementTime = now;
                }
            }
            return(true);
        }
    }

    private int indexOf(long id) {
        for(int i = 0; i < waypoints.size(); i++) {
            if(waypoints.get(i).id == id)
                return(i);
        }
        return(-1);
    }

    /**
     * Pause the queue while the player steers manually with the left button held down.
     *
     * Without this the two fight: the character walks away from waypoint 0, the stuck
     * detector fires and drags him back mid-steer. Unpausing re-commands the head of the
     * queue, so he returns to the route he was walking before the manual detour.
     */
    public void setSteerPaused(boolean paused) {
        synchronized(waypoints) {
            if(steerPaused == paused)
                return;
            steerPaused = paused;
            if(!paused) {
                commanded = false;
                lastPlayerPos = null;
                lastMovementTime = Utils.rtime();
            }
        }
    }

    /**
     * Process the movement queue - should be called from tick().
     * Advances to next waypoint when current one is reached.
     */
    public void processMovementQueue(MapFile file, MiniMap.Location sessloc) {
        synchronized(waypoints) {
            if(steerPaused)
                return;
            MiniMap.Location target = waypoints.isEmpty() ? null : waypoints.get(0).loc;
            if((target != null) && commanded && (sessloc != null) && (target.seg.id == sessloc.seg.id)) {
                try {
                    MapView mv = gui.map;
                    if(mv == null) return;

                    // Get player's current location in the same coordinate system as the target
                    Coord mc = new Coord2d(mv.getcc()).floor(tilesz);
                    MCache.Grid plg = mv.ui.sess.glob.map.getgrid(mc.div(cmaps));
                    MapFile.GridInfo info = file.gridinfo.get(plg.id);

                    if(info != null && info.seg == target.seg.id) {
                        // Convert to segment-relative tile coordinates
                        Coord playerTc = info.sc.mul(cmaps).add(mc.sub(plg.ul));

                        // Track player movement for interruption detection
                        double currentTime = Utils.rtime();
                        if(lastPlayerPos == null || !lastPlayerPos.equals(playerTc)) {
                            // Player moved
                            lastPlayerPos = playerTc;
                            lastMovementTime = currentTime;
                        } else {
                            // Player hasn't moved - check if stuck
                            double timeSinceMove = currentTime - lastMovementTime;
                            if(timeSinceMove > 2.0) {  // 2 seconds without movement
                                // Check config to determine retry behavior
                                boolean shouldRetry = (Boolean) NConfig.get(NConfig.Key.waypointRetryOnStuck);

                                if(shouldRetry) {
                                    sendMovementCommand(target, sessloc);
                                    lastMovementTime = currentTime;  // Reset timer after retry
                                } else {
                                    waypoints.clear();
                                    commanded = false;
                                    lastPlayerPos = null;
                                    return;  // Exit immediately after clearing
                                }
                            }
                        }

                        // Calculate distance in tile coordinates
                        double dx = target.tc.x - playerTc.x;
                        double dy = target.tc.y - playerTc.y;
                        double dist = Math.sqrt(dx * dx + dy * dy);

                        // If we're within a tile of the target, consider it reached
                        if(dist < 1.0) {
                            waypoints.remove(0);
                            commanded = false;
                            lastPlayerPos = null;
                        }
                    }
                } catch(Loading l) {
                    // Player position not available yet, skip this tick
                }
            }

            // Nothing commanded yet - start walking to the head of the queue
            if(!commanded && !waypoints.isEmpty()) {
                MiniMap.Location next = waypoints.get(0).loc;
                if(sessloc != null && next.seg.id == sessloc.seg.id) {
                    // Reset movement tracking
                    lastPlayerPos = null;
                    lastMovementTime = Utils.rtime();
                    sendMovementCommand(next, sessloc);
                    commanded = true;
                }
            }
        }
    }

    /**
     * Send a movement command to the specified location.
     */
    private void sendMovementCommand(MiniMap.Location target, MiniMap.Location sessloc) {
        MapView mv = gui.map;
        if(mv == null || gui.ui == null) return;

        Coord mc = gui.ui.mc;
        mv.wdgmsg("click", mc,
                  target.tc.sub(sessloc.tc).mul(tilesz).add(tilesz.div(2)).floor(posres),
                  1, 0);  // button=1, modflags=0
    }
}
