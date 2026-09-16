package nurgling.actions.bots.forager;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Homing;
import haven.Line2d;
import haven.LinMove;
import haven.MCache;
import haven.MiniMap;
import haven.Moving;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.actions.PathFinder;
import nurgling.conf.NAreaRad;
import nurgling.routes.ForagerPath;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Per-route geometry limits (Forager Settings > Routes), consulted by Forager's detour/chase logic. */
public class ForagerRouteConstraints {
    private final ForagerPath path;
    private final int maxDistanceTiles;
    private final int maxBranches;
    private final int maxBranchDistanceTiles;
    private final boolean avoidCliffs;
    private final int cliffBufferTiles;
    // Ring Settings as of the run's start - dangerZones() also runs on the task thread (PathFinder's mid-walk check), which mustn't read NConfig.
    private final List<NAreaRad> animalRads;

    // Caps worst-case corridor-check cost for a very distant candidate, matching CliffCorridorChecker's own cap.
    private static final int MAX_EXCLUSION_CORRIDOR_SAMPLE_TILES = 300;

    // A danger zone is this many times an animal's Ring Settings radius, stretched along DANGER_LOOKAHEAD_S of its movement.
    private static final double AVOID_RADIUS_MULT = 2.0;
    private static final double DANGER_LOOKAHEAD_S = 3.0;

    public ForagerRouteConstraints(ForagerPath path) {
        this.path = path;
        this.maxDistanceTiles = path.maxDistance;
        this.maxBranches = path.maxBranches;
        this.maxBranchDistanceTiles = path.maxBranchDistance;
        this.avoidCliffs = path.avoidCliffs;
        this.cliffBufferTiles = path.cliffBufferTiles;
        @SuppressWarnings("unchecked")
        ArrayList<NAreaRad> rads = (ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad);
        this.animalRads = (rads != null) ? new ArrayList<>(rads) : new ArrayList<>();
    }

    /** No-walk zones around every loaded dangerous animal (Ring Settings' dangerous flag, ignoreBats respected): AVOID_RADIUS_MULT
     *  x its ring radius, from where it stands to where it's heading DANGER_LOOKAHEAD_S from now. Never blocks - an animal whose
     *  name hasn't loaded yet is skipped - so it's safe on the task thread. */
    public List<PathFinder.AvoidZone> dangerZones(NGameUI gui, boolean ignoreBats) {
        List<PathFinder.AvoidZone> zones = new ArrayList<>();
        forEachThreat(gui, ignoreBats, (gob, rad) ->
                zones.add(new PathFinder.AvoidZone(gob.rc, headingPoint(gob), rad.radius * AVOID_RADIUS_MULT, label(gob))));
        return zones;
    }

    /** Testing aid: one line per dangerous animal within 3x its ring of the player - distance against the guard's trigger and the zone radius, and how it's moving. */
    public List<String> describeNearbyThreats(NGameUI gui, boolean ignoreBats, Gob player) {
        List<String> lines = new ArrayList<>();
        forEachThreat(gui, ignoreBats, (gob, rad) -> {
            double d = gob.rc.dist(player.rc);
            if (d < rad.radius * 3) {
                lines.add(String.format("%s dist=%.0f trigger=%.0f zone=%.0f %s",
                        label(gob), d, rad.triggerDist(), rad.radius * AVOID_RADIUS_MULT, movement(gob, player)));
            }
        });
        return lines;
    }

    /** Calls fn for every loaded dangerous animal per the run's Ring Settings (ignoreBats respected), with its ring. Never blocks. */
    private void forEachThreat(NGameUI gui, boolean ignoreBats, BiConsumer<Gob, NAreaRad> fn) {
        List<NAreaRad> threats = new ArrayList<>();
        List<NAlias> aliases = new ArrayList<>();
        for (NAreaRad rad : animalRads) {
            if (rad.name != null && rad.isActiveThreat(ignoreBats)) {
                threats.add(rad);
                aliases.add(new NAlias(rad.name));
            }
        }
        if (threats.isEmpty()) return;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (gob.ngob == null || gob.ngob.name == null) continue;
                for (int k = 0; k < threats.size(); k++) {
                    if (NParser.checkName(gob.ngob.name, aliases.get(k))) {
                        // A knocked-out or dead one is no threat - no zone, however close to the route.
                        if (!NAreaRad.isDownOrDead(gob))
                            fn.accept(gob, threats.get(k));
                        break;
                    }
                }
            }
        }
    }

    /** e.g. "boar#12345" - a zone's name in the logs. */
    private static String label(Gob gob) {
        String name = gob.ngob.name;
        return name.substring(name.lastIndexOf('/') + 1) + "#" + gob.id;
    }

    /** "idle", "homing on YOU" / "homing on <id>", or "moving <speed>/s toward you|away|across". */
    private static String movement(Gob gob, Gob player) {
        Moving m = gob.getattr(Moving.class);
        if (m instanceof Homing) {
            long tgt = ((Homing) m).tgt;
            return "homing on " + (tgt == player.id ? "YOU" : String.valueOf(tgt));
        }
        if (m instanceof LinMove) {
            Coord2d v = ((LinMove) m).v;
            Coord2d toPlayer = player.rc.sub(gob.rc);
            double speed = v.abs();
            double cos = (speed > 0.01 && toPlayer.abs() > 0.01) ? (v.x * toPlayer.x + v.y * toPlayer.y) / (speed * toPlayer.abs()) : 0;
            return String.format("moving %.0f/s %s", speed, cos > 0.5 ? "toward you" : (cos < -0.5 ? "away" : "across"));
        }
        return "idle";
    }

    /** Where gob will be DANGER_LOOKAHEAD_S from now on its current movement - its own position when it's standing still. */
    private static Coord2d headingPoint(Gob gob) {
        Moving m = gob.getattr(Moving.class);
        if (m instanceof LinMove) {
            return gob.rc.add(((LinMove) m).v.mul(DANGER_LOOKAHEAD_S));
        }
        if (m instanceof Homing) {
            Homing h = (Homing) m;
            Gob tgt = h.tgt();
            Coord2d tc = (tgt != null) ? tgt.rc : h.tc;
            if (tc != null) {
                Coord2d d = tc.sub(gob.rc);
                double len = d.abs();
                if (len > 0.01) {
                    return gob.rc.add(d.mul(Math.min(len, h.v * DANGER_LOOKAHEAD_S) / len));
                }
            }
        }
        return gob.rc;
    }

    /** True if this gob's tile is inside a brush-painted exclusion zone. */
    public boolean isGobExcluded(MiniMap.Location sessloc, Gob gob) {
        if (sessloc == null || gob == null) return false;
        return isExcludedAt(sessloc, gob.rc);
    }

    /** True if any tile in the straight-line corridor from `from` to `to` is inside a brush-painted
     *  exclusion zone - so a candidate on the far side of a zone (but not itself excluded) still
     *  gets rejected, the same corridor-check treatment cliffCorridorBlocked already gets. */
    public boolean corridorExcluded(MiniMap.Location sessloc, Coord2d from, Coord2d to) {
        if (sessloc == null || from == null || to == null) return false;

        double dist = from.dist(to);
        if (dist < 0.01) {
            return isExcludedAt(sessloc, from);
        }
        Coord2d cappedTo = to;
        double maxDist = MAX_EXCLUSION_CORRIDOR_SAMPLE_TILES * MCache.tilesz.x;
        if (dist > maxDist) {
            cappedTo = from.add(to.sub(from).mul(maxDist / dist));
        }

        Coord2d prev = null;
        for (Coord2d p : new Line2d.GridIsect(from, cappedTo, MCache.tilesz, true)) {
            if (prev != null && isExcludedAt(sessloc, prev.add(p).div(2))) {
                return true;
            }
            prev = p;
        }
        return false;
    }

    private boolean isExcludedAt(MiniMap.Location sessloc, Coord2d worldPos) {
        Coord tc = worldPos.floor(MCache.tilesz).add(sessloc.tc);
        return path.isExcluded(sessloc.seg.id, tc);
    }

    /** True if maxDistance is uncapped, or candidate is within maxDistance tiles of anchor (held fixed per episode). */
    public boolean withinLeash(Coord2d anchor, Coord2d candidate) {
        if (maxDistanceTiles < 0 || anchor == null || candidate == null) return true;
        return anchor.dist(candidate) <= maxDistanceTiles * MCache.tilesz.x;
    }

    /** True (no constraint) unless avoidCliffs is on for this route. */
    public boolean cliffCorridorBlocked(MCache map, Coord2d from, Coord2d to) {
        if (!avoidCliffs) return false;
        return CliffCorridorChecker.corridorBlocked(map, from, to, cliffBufferTiles);
    }

    /** True (no constraint) unless waterMode is on for this walk - a coracle can't cross land, so
     *  any land tile along the corridor rules the candidate out the same way a cliff would. */
    public boolean landCorridorBlocked(MCache map, Coord2d from, Coord2d to, boolean waterMode) {
        if (!waterMode) return false;
        return LandCorridorChecker.corridorBlocked(map, from, to);
    }

    /** True if a dangerous animal (per Ring Settings' own per-species radius/dangerous flag - the
     *  same config DangerousAnimalTrigger uses, respecting ignoreBats the same way, via
     *  NAreaRad.isActiveThreat/triggerDist) is within its own danger radius of any point along the
     *  corridor from `from` to `to` - not just the candidate's own position, so a walk that merely
     *  passes near one is also rejected. Same corridor-check spirit as
     *  cliffCorridorBlocked/corridorExcluded, checking distance to a live gob instead of a static
     *  tile property. */
    @SuppressWarnings("unchecked")
    public boolean dangerousAnimalNearCorridor(Coord2d from, Coord2d to, boolean ignoreBats) throws InterruptedException {
        if (from == null || to == null) return false;
        ArrayList<NAreaRad> rads = (ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad);
        if (rads == null) return false;

        Coord2d mid = from.add(to).div(2);
        double halfLen = from.dist(to) / 2.0;

        for (NAreaRad rad : rads) {
            if (!rad.isActiveThreat(ignoreBats)) continue;

            double triggerDist = rad.triggerDist();
            for (Gob animal : Finder.findGobs(mid, new NAlias(rad.name), null, halfLen + triggerDist)) {
                if (NAreaRad.isDownOrDead(animal)) continue;
                if (distToSegment(animal.rc, from, to) <= triggerDist) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double distToSegment(Coord2d p, Coord2d a, Coord2d b) {
        Coord2d ab = b.sub(a);
        double abLenSq = ab.x * ab.x + ab.y * ab.y;
        if (abLenSq < 0.0001) return p.dist(a);
        double t = ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / abLenSq;
        t = Math.max(0, Math.min(1, t));
        Coord2d closest = new Coord2d(a.x + ab.x * t, a.y + ab.y * t);
        return p.dist(closest);
    }

    /** -1 = unlimited hops per detour episode. */
    public int maxBranches() {
        return maxBranches;
    }

    /** -1 = unlimited cumulative distance per detour episode, in tiles. */
    public int maxBranchDistanceTiles() {
        return maxBranchDistanceTiles;
    }
}
