package nurgling;

import haven.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Map pings shared through chat.
 *
 * <p>A ping is one map tile, addressed on the wire as the server's grid id plus the
 * tile's offset inside that grid. That pair is the only coordinate both ends agree on:
 * {@code gob.rc} is local to a session and the minimap's segment tiles are local to one
 * client's map file, while the grid id is assigned by the server and reads the same for
 * everyone.
 *
 * <p>Resolution therefore happens on the receiving side and can fail in two ways that
 * both have to be tolerated rather than treated as errors:
 * <ul>
 *   <li>the grid is not loaded in {@link MCache}, so there is no world position to draw
 *       at. The ping still shows on the minimap and in the map window.</li>
 *   <li>the grid is not in the segment the player is standing in, so it has to come out
 *       of the map file. That touches disk, so it runs on the loader thread instead of
 *       the UI thread.</li>
 * </ul>
 * Either resolution is retried until it succeeds or the ping expires, because both can
 * start failing and later succeed as the player walks toward the pinged spot.
 */
public class PingService {
    /** How long a ping stays up, in seconds. */
    public static final double DURATION = 20.0;
    /**
     * Fraction of the lifetime a ping stays at full brightness before it starts fading.
     * A ping that dims linearly from the moment it lands spends most of its life as a
     * faint smudge; holding and then dropping keeps it readable and makes the ending read
     * as deliberate rather than as the effect running out of steam.
     */
    private static final double HOLD = 0.7;
    /** Pings kept per sender, so nobody can bury the map by spamming. */
    private static final int MAX_PER_SENDER = 3;
    /** Seconds between retries of a resolution that has not succeeded yet. */
    private static final double RETRY = 0.5;
    /** Colour for a ping with no sender colour to go on. */
    public static final Color DEFAULT_COLOR = new Color(125, 211, 252);
    /** Sender key the chat layer uses for our own lines. */
    public static final String SELF = "self";
    /** Cue played when someone else pings; the game's own minimap-marker bell. */
    private static final String CUE_RES = "sfx/hud/mmap/bell1";
    /** Minimum seconds between cues, matching GobIcon's notification limiter. */
    private static final double CUE_INTERVAL = 0.5;

    private final NGameUI gui;
    private final List<Ping> pings = new ArrayList<>();
    private double lastCue = Double.NEGATIVE_INFINITY;

    public PingService(NGameUI gui) {
        this.gui = gui;
    }

    /** One live ping. Resolved positions are filled in lazily; see {@link #resolve}. */
    public static class Ping {
        public final long gridId;
        public final Coord local;
        public final Color col;
        /** Opaque per-sender key, used only for the spam cap - never displayed. */
        public final String sender;
        public final double start;

        /** Session world position, or null while the grid is not loaded here. */
        private volatile Coord2d wc = null;
        /** Position in the local map file, or null while it is still being looked up. */
        private volatile MiniMap.Location loc = null;
        private double lastTry = Double.NEGATIVE_INFINITY;
        private volatile boolean filePending = false;

        Ping(long gridId, Coord local, Color col, String sender) {
            this.gridId = gridId;
            this.local = local;
            this.col = (col == null) ? DEFAULT_COLOR : col;
            this.sender = sender;
            this.start = Utils.rtime();
        }

        public Coord2d wc() {return(wc);}

        public MiniMap.Location loc() {return(loc);}

        /** 0 when the ping arrived, 1 when it expires. */
        public double age() {
            return(Math.min(1.0, (Utils.rtime() - start) / DURATION));
        }

        /** Seconds since the ping landed. Animation phases key off this, not off the wall
         *  clock, so every ping starts its rings from the centre however long it has run. */
        public double since() {
            return(Utils.rtime() - start);
        }

        /** Overall opacity: full for the first {@link #HOLD} of the life, then eased out. */
        public double alpha() {
            double age = age();
            if(age <= HOLD)
                return(1.0);
            double u = (age - HOLD) / (1.0 - HOLD);
            return(Math.pow(1.0 - u, 1.5));
        }

        boolean expired() {
            return(Utils.rtime() - start >= DURATION);
        }
    }

    /**
     * Record a ping received over chat. {@code local} is the tile offset inside the grid;
     * {@code col} is the colour that chat channel gives the sender, and {@code sender} an
     * opaque key identifying them for the per-sender cap.
     */
    public void add(long gridId, Coord local, Color col, String sender) {
        Ping p = new Ping(gridId, local, col, sender);
        if(!SELF.equals(sender))
            cue();
        synchronized(pings) {
            if(sender != null) {
                // Walk newest-first and drop everything past the cap, so the newest ping
                // always survives and an older one of the sender's own makes way for it.
                int seen = 0;
                for(int i = pings.size() - 1; i >= 0; i--) {
                    if(sender.equals(pings.get(i).sender) && (++seen >= MAX_PER_SENDER))
                        pings.remove(i);
                }
            }
            pings.add(p);
        }
    }

    /**
     * Live pings, freshest last, with expired ones dropped and unresolved ones given
     * another go at resolving. Called from the render passes, so it must not block.
     */
    public List<Ping> snapshot() {
        List<Ping> ret;
        synchronized(pings) {
            if(pings.isEmpty())
                return(Collections.emptyList());
            pings.removeIf(Ping::expired);
            ret = new ArrayList<>(pings);
        }
        for(Ping p : ret)
            resolve(p);
        return(ret);
    }

    public void clear() {
        synchronized(pings) {
            pings.clear();
        }
    }

    /**
     * Short cue when someone else pings. A ping is a notification, and one that arrives
     * while the player is looking at their inventory may as well not have happened.
     * Loaded and played the way GobIcon plays its marker notifications: off the loader
     * thread, and a resource that will not load is dropped rather than raised, because a
     * missing sound must never cost anyone a ping.
     */
    private void cue() {
        if(!Boolean.TRUE.equals(NConfig.get(NConfig.Key.pingSound)))
            return;
        // Several people pinging the same spot at once should be one bell, not a peal.
        double now = Utils.rtime();
        if(now - lastCue < CUE_INTERVAL)
            return;
        lastCue = now;
        UI ui = gui.ui;
        if(ui == null)
            return;
        Indir<Resource> resid = Resource.local().load(CUE_RES);
        ui.sess.glob.loader.defer(() -> {
            Resource res;
            try {
                res = resid.get();
            } catch(Loading l) {
                throw(l);
            } catch(RuntimeException e) {
                return;
            }
            ui.sfx(Audio.fromres(res));
        }, null);
    }

    /** Fill in whichever of the two positions is still missing, on a retry throttle. */
    private void resolve(Ping p) {
        if((p.wc != null) && (p.loc != null))
            return;
        double now = Utils.rtime();
        if(now - p.lastTry < RETRY)
            return;
        p.lastTry = now;

        if(p.wc == null)
            p.wc = gui.ui.sess.glob.map.gridToScene(p.local, p.gridId);

        if(p.loc == null) {
            // Fast path: the grid is in the segment the player is standing in, which the
            // map file has already loaded in full, so this is a plain in-memory lookup.
            MiniMap.Location sessloc = (gui.mmap != null) ? gui.mmap.sessloc : null;
            if(sessloc != null) {
                Coord sgc = sessloc.seg.map.reverse().get(p.gridId);
                if(sgc != null)
                    p.loc = new MiniMap.Location(sessloc.seg, sgc.mul(MCache.cmaps).add(p.local));
            }
            if((p.loc == null) && !p.filePending)
                deferFileLookup(p);
        }
    }

    /**
     * Slow path for a ping in some other segment: ask the map file which segment the grid
     * belongs to. That reads from disk, so it goes to the loader rather than blocking the
     * frame. Runs at most once per ping - a grid the map file has never heard of will not
     * turn up on a retry either.
     */
    private void deferFileLookup(Ping p) {
        MiniMap mmap = gui.mmap;
        if(mmap == null)
            return;
        MapFile file = mmap.file;
        if(file == null)
            return;
        p.filePending = true;
        gui.ui.sess.glob.loader.defer(() -> {
            file.lock.readLock().lock();
            try {
                MapFile.GridInfo info = file.gridinfo.get(p.gridId);
                if(info == null)
                    return;
                MapFile.Segment seg = file.segments.get(info.seg);
                if(seg == null)
                    return;
                p.loc = new MiniMap.Location(seg, info.sc.mul(MCache.cmaps).add(p.local));
            } finally {
                file.lock.readLock().unlock();
            }
        }, null);
    }
}
