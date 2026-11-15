package nurgling.navigation;

import haven.Area;
import haven.Coord;
import nurgling.areas.NArea;
import nurgling.navigation.legacy.LegacyAreaNav;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Offline A/B of area navigation planning on a real recorded chunk graph and real areas, no game client.
 *
 *   A = the old four-corner planner (nurgling.navigation.legacy), run single-threaded
 *   B = the edge goal-set planner (ChunkNavPlanner.planToArea / nearestOf)
 *   R = an independent plain Dijkstra over the same move rules, as the ground truth for optimal cost
 *
 * Reads the chunk directory read-only. Usage:
 *   AreaNavABHarness <chunknav dir> <areas.tsv: id \t name \t hide \t data-json> <out dir> [pairs] [seed] [refPairs]
 *
 * TEMPORARY: goes away with the legacy package once the A/B comparison is done.
 */
public class AreaNavABHarness {
    static final int TILES = ChunkNavConfig.CHUNK_SIZE * ChunkNavConfig.CHUNK_SIZE;

    static final class AreaRec {
        int id;
        String name;
        boolean hide;
        Map<Long, Area> pieces = new HashMap<>();
        Map<Long, NArea.VArea> vspace = new HashMap<>();
        List<String> specs = new ArrayList<>();
        boolean recorded;
    }

    public static void main(String[] args) throws Exception {
        Path chunkDir = Paths.get(args[0]);
        Path areasTsv = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        int pairs = args.length > 3 ? Integer.parseInt(args[3]) : 400;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : 20261114L;
        int refPairs = args.length > 5 ? Integer.parseInt(args[5]) : 80;
        Files.createDirectories(out);

        ChunkNavGraph graph = loadGraph(chunkDir);
        List<AreaRec> areas = loadAreas(areasTsv, graph);
        List<AreaRec> recorded = new ArrayList<>();
        for (AreaRec a : areas) if (a.recorded) recorded.add(a);
        System.out.printf("chunks=%d areas=%d recorded=%d%n", graph.getChunkCount(), areas.size(), recorded.size());

        ChunkNavPlanner planner = new ChunkNavPlanner(graph);
        Random rnd = new Random(seed);

        // Start tiles: a walkable goal tile of a recorded area (where a bot stands after arriving there).
        List<long[]> starts = new ArrayList<>();
        List<AreaRec> startAreas = new ArrayList<>();
        for (AreaRec a : recorded) {
            long[] s = startTile(graph, a, rnd);
            if (s != null) { starts.add(s); startAreas.add(a); }
        }
        System.out.printf("start tiles=%d%n", starts.size());

        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("pairs.csv"), StandardCharsets.UTF_8))) {
            csv.println("i,from,to,toRecorded,aReach,bReach,aCost,bCost,refCost,aMs,bMs,bPops,bValid,note");
            Stats st = new Stats();
            // Warm up both planners so JIT doesn't bias the first rows.
            for (int w = 0; w < 5 && !starts.isEmpty(); w++) {
                long[] s = starts.get(w % starts.size());
                AreaRec t = recorded.get(w % recorded.size());
                LegacyAreaNav.shortestToCorners(graph, t.vspace, s[0], new Coord((int) s[1], (int) s[2]));
                planner.planToArea(t.pieces, s[0], new Coord((int) s[1], (int) s[2]));
            }
            for (int i = 0; i < pairs; i++) {
                int si = rnd.nextInt(starts.size());
                long[] s = starts.get(si);
                Coord sl = new Coord((int) s[1], (int) s[2]);
                AreaRec from = startAreas.get(si);
                // 70% recorded targets (should mostly succeed), 30% any area (unrecorded ones must fail cleanly)
                AreaRec to = rnd.nextDouble() < 0.7 ? recorded.get(rnd.nextInt(recorded.size())) : areas.get(rnd.nextInt(areas.size()));
                if (to == from) continue;

                long t0 = System.nanoTime();
                ChunkPath a = LegacyAreaNav.shortestToCorners(graph, to.vspace, s[0], sl);
                long t1 = System.nanoTime();
                ChunkPath b = planner.planToArea(to.pieces, s[0], sl);
                long t2 = System.nanoTime();
                int pops = planner.getTilePathfinder().lastPops;
                String valid = "-";
                String note = "";
                if (a != null) {
                    String va = validate(graph, a, null, s[0], sl);
                    if (va != null) { st.aIllegal++; note += " A_ILLEGAL(" + va + ")"; }
                }
                if (b != null) {
                    String v = validate(graph, b, AreaGoalSet.ofArea(graph, to.pieces), s[0], sl);
                    valid = v == null ? "ok" : "BAD";
                    if (v != null) { note = v; st.invalid++; }
                }
                double ref = Double.NaN;
                if (i < refPairs) {
                    ref = refDijkstra(graph, s[0], sl, AreaGoalSet.ofArea(graph, to.pieces));
                }
                st.add(a, b, ref, (t1 - t0) / 1e6, (t2 - t1) / 1e6, pops);
                if (a != null && b == null) note += " LOST_ROUTE";
                if (!Double.isNaN(ref) && b != null && Math.abs(b.totalCost - ref) > 0.05) note += String.format(" SUBOPT(%.2f vs %.2f)", b.totalCost, ref);
                if (!Double.isNaN(ref) && b == null && ref < Double.MAX_VALUE) note += " B_MISSED_REF";
                if (!Double.isNaN(ref) && b != null && ref == Double.MAX_VALUE) note += " B_FOUND_REF_DIDNT";
                csv.printf(Locale.ROOT, "%d,%d,%d,%b,%b,%b,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%s,%s%n", i, from.id, to.id, to.recorded,
                        a != null, b != null, a != null ? a.totalCost : -1, b != null ? b.totalCost : -1,
                        Double.isNaN(ref) ? -2 : (ref == Double.MAX_VALUE ? -1 : ref),
                        (t1 - t0) / 1e6, (t2 - t1) / 1e6, pops, valid, note.trim());
                if (!note.isEmpty() && !note.trim().startsWith("A_ILLEGAL")) System.out.println("pair " + i + " " + from.id + "->" + to.id + ": " + note.trim());
            }
            st.print();
        }

        selectionAB(graph, planner, recorded, starts, rnd, out);
    }

    // ------------------------------------------------------------------ selection (which area is nearest)

    static void selectionAB(ChunkNavGraph graph, ChunkNavPlanner planner, List<AreaRec> recorded, List<long[]> starts,
                            Random rnd, Path out) throws IOException {
        Map<String, List<AreaRec>> bySpec = new TreeMap<>();
        for (AreaRec a : recorded) for (String sp : a.specs) bySpec.computeIfAbsent(sp, k -> new ArrayList<>()).add(a);
        int groups = 0, agree = 0, bBetter = 0, bWorse = 0, aOnly = 0, bOnly = 0, none = 0;
        double aMs = 0, bMs = 0;
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("selection.csv"), StandardCharsets.UTF_8))) {
            csv.println("spec,candidates,aPick,aCost,bPick,bCost,aMs,bMs");
            for (Map.Entry<String, List<AreaRec>> e : bySpec.entrySet()) {
                List<AreaRec> cands = e.getValue();
                if (cands.size() < 2) continue;
                for (int rep = 0; rep < 3; rep++) {
                    long[] s = starts.get(rnd.nextInt(starts.size()));
                    Coord sl = new Coord((int) s[1], (int) s[2]);
                    long t0 = System.nanoTime();
                    AreaRec aPick = null;
                    double aCost = Double.MAX_VALUE;
                    for (AreaRec c : cands) {
                        ChunkPath p = LegacyAreaNav.shortestToCorners(graph, c.vspace, s[0], sl);
                        if (p != null && p.totalCost < aCost) { aCost = p.totalCost; aPick = c; }
                    }
                    long t1 = System.nanoTime();
                    List<Map<Long, Area>> ps = new ArrayList<>();
                    for (AreaRec c : cands) ps.add(c.pieces);
                    ChunkNavPlanner.Nearest n = planner.nearestOf(ps, s[0], sl);
                    long t2 = System.nanoTime();
                    AreaRec bPick = n == null ? null : cands.get(n.index);
                    double bCost = n == null ? Double.MAX_VALUE : n.path.totalCost;
                    groups++;
                    aMs += (t1 - t0) / 1e6;
                    bMs += (t2 - t1) / 1e6;
                    if (aPick == null && bPick == null) none++;
                    else if (aPick == null) bOnly++;
                    else if (bPick == null) aOnly++;
                    else if (aPick == bPick) agree++;
                    else if (bCost < aCost - 0.01) bBetter++;
                    else bWorse++;
                    csv.printf(Locale.ROOT, "%s,%d,%s,%.2f,%s,%.2f,%.2f,%.2f%n", e.getKey().replace(',', ';'), cands.size(),
                            aPick == null ? "-" : aPick.id, aCost == Double.MAX_VALUE ? -1 : aCost,
                            bPick == null ? "-" : bPick.id, bCost == Double.MAX_VALUE ? -1 : bCost, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
                }
            }
        }
        System.out.printf("%nSELECTION  queries=%d samePick=%d bCheaperPick=%d bWorsePick=%d onlyA=%d onlyB=%d neither=%d%n",
                groups, agree, bBetter, bWorse, aOnly, bOnly, none);
        System.out.printf("SELECTION  total ms: A=%.0f B=%.0f (x%.1f)%n", aMs, bMs, aMs / Math.max(0.001, bMs));
    }

    // ------------------------------------------------------------------ stats

    static final class Stats {
        int n, aReach, bReach, both, lost, gained, invalid, refN, refOpt, refSub, refMiss, aIllegal;
        double aMsReach, bMsReach, aMsFail, bMsFail, aMaxFail, bMaxFail;
        int nFail;
        List<Double> costDiff = new ArrayList<>();
        List<Double> aTimes = new ArrayList<>(), bTimes = new ArrayList<>();
        long bPops;

        void add(ChunkPath a, ChunkPath b, double ref, double aMs, double bMs, int pops) {
            n++;
            aTimes.add(aMs);
            bTimes.add(bMs);
            bPops += pops;
            if (a != null) aReach++;
            if (b != null) bReach++;
            if (a != null && b != null) { both++; costDiff.add((double) (b.totalCost - a.totalCost)); }
            if (a != null && b == null) lost++;
            if (a == null && b != null) gained++;
            if (a == null && b == null) { nFail++; aMsFail += aMs; bMsFail += bMs; aMaxFail = Math.max(aMaxFail, aMs); bMaxFail = Math.max(bMaxFail, bMs); }
            else { aMsReach += aMs; bMsReach += bMs; }
            if (!Double.isNaN(ref)) {
                refN++;
                if (ref == Double.MAX_VALUE) { if (b != null) refMiss++; }
                else if (b == null) refMiss++;
                else if (Math.abs(b.totalCost - ref) <= 0.05) refOpt++;
                else refSub++;
            }
        }

        void print() {
            System.out.printf("%nPAIRS n=%d  A reached=%d  B reached=%d  both=%d  A-only(LOST)=%d  B-only(GAINED)=%d  B invalid paths=%d  A paths with an illegal step=%d%n",
                    n, aReach, bReach, both, lost, gained, invalid, aIllegal);
            Collections.sort(costDiff);
            if (!costDiff.isEmpty()) {
                int cheaper = 0, same = 0, dearer = 0;
                for (double d : costDiff) { if (d < -0.5) cheaper++; else if (d > 0.5) dearer++; else same++; }
                System.out.printf("COST B-A (tiles)  cheaper=%d same=%d dearer=%d  median=%.1f  p10=%.1f p90=%.1f  min=%.1f max=%.1f%n",
                        cheaper, same, dearer, pct(costDiff, 50), pct(costDiff, 10), pct(costDiff, 90), costDiff.get(0), costDiff.get(costDiff.size() - 1));
            }
            Collections.sort(aTimes);
            Collections.sort(bTimes);
            System.out.printf("TIME ms  A: median=%.1f p90=%.1f max=%.1f total=%.0f | B: median=%.2f p90=%.2f max=%.1f total=%.0f%n",
                    pct(aTimes, 50), pct(aTimes, 90), aTimes.get(aTimes.size() - 1), sum(aTimes),
                    pct(bTimes, 50), pct(bTimes, 90), bTimes.get(bTimes.size() - 1), sum(bTimes));
            System.out.printf("TIME ms  reachable pairs: A=%.0f B=%.0f | unreachable pairs (%d): A=%.0f (max %.0f) B=%.0f (max %.0f)%n",
                    aMsReach, bMsReach, nFail, aMsFail, aMaxFail, bMsFail, bMaxFail);
            System.out.printf("B pops avg=%.0f%n", n == 0 ? 0 : (double) bPops / n);
            System.out.printf("REFERENCE (plain Dijkstra) on %d pairs: B optimal=%d  B suboptimal=%d  reachability mismatch=%d%n", refN, refOpt, refSub, refMiss);
        }

        static double pct(List<Double> l, int p) {
            if (l.isEmpty()) return 0;
            return l.get(Math.min(l.size() - 1, (int) Math.floor(p / 100.0 * (l.size() - 1) + 0.5)));
        }

        static double sum(List<Double> l) {
            double s = 0;
            for (double d : l) s += d;
            return s;
        }
    }

    // ------------------------------------------------------------------ path validation

    /** null when the path is legal: starts at the start, every move is legal, ends in the goal, cost adds up. */
    static String validate(ChunkNavGraph graph, ChunkPath path, AreaGoalSet goal, long startChunk, Coord start) {
        List<long[]> steps = new ArrayList<>();
        for (ChunkPath.PathSegment seg : path.segments)
            for (ChunkPath.TileStep st : seg.steps) steps.add(new long[] {seg.gridId, st.localCoord.x, st.localCoord.y});
        if (steps.isEmpty()) return "empty path";
        long[] f = steps.get(0);
        if (f[0] != startChunk || f[1] != start.x || f[2] != start.y) return "does not start at the start";
        long[] l = steps.get(steps.size() - 1);
        if (goal != null && !goal.contains(l[0], (int) l[1], (int) l[2])) return "does not end in the goal set";
        double cost = 0;
        for (int i = 1; i < steps.size(); i++) {
            long[] p = steps.get(i - 1), c = steps.get(i);
            ChunkNavData cc = graph.getChunk(c[0]);
            if (cc == null || !AreaGoalSet.isTileWalkable(cc, (int) c[1], (int) c[2])) return "step " + i + " not walkable";
            if (p[0] == c[0]) {
                long dx = Math.abs(p[1] - c[1]), dy = Math.abs(p[2] - c[2]);
                if (dx <= 1 && dy <= 1) { cost += Math.sqrt(dx * dx + dy * dy); continue; }
            } else if (crossesTo(graph, graph.getChunk(p[0]), (int) p[1], (int) p[2], c)) {
                cost += 1.5;
                continue;
            }
            // otherwise it must be a portal
            ChunkNavData pc = graph.getChunk(p[0]);
            boolean portal = false;
            for (ChunkPortal po : pc.portals) {
                if (po.localCoord != null && po.connectsToGridId == c[0]) {
                    int prox = ChunkPortal.isBuildingExterior(po.gobName) ? 6 : 2;
                    if (Math.hypot(p[1] - po.localCoord.x, p[2] - po.localCoord.y) <= prox) { portal = true; break; }
                }
            }
            if (!portal) return "step " + i + " is not a legal move";
            cost += Objects.equals(pc.layer, cc.layer) ? (p[0] == c[0] ? Math.hypot(p[1] - c[1], p[2] - c[2]) : 1.5) : ChunkNavConfig.PORTAL_TRAVERSAL_COST;
        }
        if (Math.abs(cost - path.totalCost) > 0.05) return String.format("cost %.2f != reported %.2f", cost, path.totalCost);
        return null;
    }

    static boolean crossesTo(ChunkNavGraph graph, ChunkNavData from, int x, int y, long[] c) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++) {
                long[] t = cross(graph, from, x + dx, y + dy);
                if (t != null && t[0] == c[0] && t[1] == c[1] && t[2] == c[2]) return true;
            }
        return false;
    }

    // ------------------------------------------------------------------ reference Dijkstra (independent)

    /** The edge-crossing rule, written out again on its own. */
    static long[] cross(ChunkNavGraph graph, ChunkNavData from, int nx, int ny) {
        // Written out separately from the planner: a corner step goes through either side neighbour.
        int n = ChunkNavConfig.CHUNK_SIZE;
        int sx = nx < 0 ? -1 : nx >= n ? 1 : 0, sy = ny < 0 ? -1 : ny >= n ? 1 : 0;
        if (sx == 0 && sy == 0) return null;
        int x = nx - sx * n, y = ny - sy * n;
        long id;
        if (sx != 0 && sy != 0) {
            ChunkNavData h = graph.getChunk(sx < 0 ? from.neighborWest : from.neighborEast);
            id = h == null ? -1 : (sy < 0 ? h.neighborNorth : h.neighborSouth);
            if (id == -1) {
                ChunkNavData v = graph.getChunk(sy < 0 ? from.neighborNorth : from.neighborSouth);
                id = v == null ? -1 : (sx < 0 ? v.neighborWest : v.neighborEast);
            }
        } else if (sx != 0) {
            id = sx < 0 ? from.neighborWest : from.neighborEast;
        } else {
            id = sy < 0 ? from.neighborNorth : from.neighborSouth;
        }
        if (id == -1) return null;
        ChunkNavData to = graph.getChunk(id);
        if (to == null || !Objects.equals(from.layer, to.layer) || from.instanceId == 0 || to.instanceId == 0 || from.instanceId != to.instanceId)
            return null;
        if (!AreaGoalSet.isTileWalkable(to, x, y)) return null;
        return new long[] {id, x, y};
    }

    static double refDijkstra(ChunkNavGraph graph, long startChunk, Coord start, AreaGoalSet goal) {
        if (goal.isEmpty() || graph.getChunk(startChunk) == null) return Double.MAX_VALUE;
        // node key: gridId -> int index map built lazily
        Map<Long, Integer> idx = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        java.util.function.Function<Long, Integer> ix = g -> idx.computeIfAbsent(g, k -> { ids.add(k); return ids.size() - 1; });
        Map<Long, Double> dist = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        long s = (long) ix.apply(startChunk) * TILES + start.y * 100L + start.x;
        dist.put(s, 0.0);
        pq.add(new double[] {0, s});
        Set<Long> done = new HashSet<>();
        int pops = 0;
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            long node = (long) cur[1];
            if (!done.add(node)) continue;
            if (++pops > 3_000_000) return Double.NaN;
            long gid = ids.get((int) (node / TILES));
            int t = (int) (node % TILES), x = t % 100, y = t / 100;
            if (goal.contains(gid, x, y)) return cur[0];
            ChunkNavData c = graph.getChunk(gid);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx, ny = y + dy;
                    long ngid;
                    int tx, ty;
                    double w;
                    if (nx >= 0 && nx < 100 && ny >= 0 && ny < 100) {
                        if (!AreaGoalSet.isTileWalkable(c, nx, ny)) continue;
                        ngid = gid; tx = nx; ty = ny; w = Math.sqrt(dx * dx + dy * dy);
                    } else {
                        long[] r = cross(graph, c, nx, ny);
                        if (r == null) continue;
                        ngid = r[0]; tx = (int) r[1]; ty = (int) r[2]; w = 1.5;
                    }
                    relaxRef(dist, pq, done, (long) ix.apply(ngid) * TILES + ty * 100L + tx, cur[0] + w);
                }
            for (ChunkPortal p : c.portals) {
                if (p.localCoord == null || p.connectsToGridId == -1) continue;
                int prox = ChunkPortal.isBuildingExterior(p.gobName) ? 6 : 2;
                if (Math.hypot(x - p.localCoord.x, y - p.localCoord.y) > prox) continue;
                ChunkNavData d = graph.getChunk(p.connectsToGridId);
                if (d == null) continue;
                // exit + layer validity: reuse the planner's helpers through a one-off search is not possible, so
                // mirror them: stored exit, else matching portal back, else chunk centre.
                Coord exit = refExit(d, p, gid);
                if (exit == null || !refLayerValid(p.type, d.layer)) continue;
                double w = Objects.equals(c.layer, d.layer) ? (d.gridId == gid ? Math.hypot(x - exit.x, y - exit.y) : 1.5) : ChunkNavConfig.PORTAL_TRAVERSAL_COST;
                relaxRef(dist, pq, done, (long) ix.apply(d.gridId) * TILES + exit.y * 100L + exit.x, cur[0] + w);
            }
        }
        return Double.MAX_VALUE;
    }

    static void relaxRef(Map<Long, Double> dist, PriorityQueue<double[]> pq, Set<Long> done, long n, double nd) {
        if (done.contains(n)) return;
        Double old = dist.get(n);
        if (old == null || nd < old) {
            dist.put(n, nd);
            pq.add(new double[] {nd, n});
        }
    }

    static boolean refLayerValid(ChunkPortal.PortalType type, String layer) {
        if (type == null || layer == null) return true;
        switch (type) {
            case MINEHOLE: case MINE_ENTRANCE: case LADDER: case CAVEIN: case CAVEOUT: return "outside".equals(layer);
            case CELLAR: return "cellar".equals(layer) || "inside".equals(layer);
            case STAIRS_UP: case STAIRS_DOWN: return "inside".equals(layer);
            case DOOR: return "inside".equals(layer) || "outside".equals(layer);
            default: return true;
        }
    }

    static Coord refExit(ChunkNavData dest, ChunkPortal entry, long source) {
        if (entry.exitLocalCoord != null) return near(dest, entry.exitLocalCoord);
        Coord best = null;
        for (ChunkPortal p : dest.portals) {
            if (p.localCoord == null || p.connectsToGridId != source) continue;
            boolean pairMatch = false;
            if (entry.gobName != null && p.gobName != null) {
                String pair = nurgling.tasks.GateDetector.getDoorPair(entry.gobName);
                pairMatch = nurgling.tasks.GateDetector.isSameDoor(p.gobName, pair) || entry.gobName.equals(p.gobName);
            }
            if (pairMatch) return near(dest, p.localCoord);
            if (entry.gobName != null && entry.gobName.equals(p.gobName)) best = p.localCoord;
        }
        if (best != null) return near(dest, best);
        return new Coord(50, 50);
    }

    static Coord near(ChunkNavData chunk, Coord t) {
        if (t.x >= 0 && t.x < 100 && t.y >= 0 && t.y < 100 && AreaGoalSet.isTileWalkable(chunk, t.x, t.y)) return t;
        int[][] dirs = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int r = 1; r <= 3; r++)
            for (int[] d : dirs) {
                int nx = t.x + d[0] * r, ny = t.y + d[1] * r;
                if (nx >= 0 && nx < 100 && ny >= 0 && ny < 100 && AreaGoalSet.isTileWalkable(chunk, nx, ny)) return new Coord(nx, ny);
            }
        return t;
    }

    // ------------------------------------------------------------------ loading

    static ChunkNavGraph loadGraph(Path dir) throws IOException {
        ChunkNavGraph graph = new ChunkNavGraph();
        int bad = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.chunk")) {
            for (Path f : ds) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f, StandardOpenOption.READ)))) {
                    ChunkNavData c = ChunkNavBinaryFormat.readChunk(in);
                    if (c != null) graph.addChunk(c);
                } catch (IOException e) {
                    bad++;
                }
            }
        }
        graph.rebuildAllConnections();
        if (bad > 0) System.out.println("unreadable chunk files: " + bad);
        return graph;
    }

    static List<AreaRec> loadAreas(Path tsv, ChunkNavGraph graph) throws IOException {
        List<AreaRec> res = new ArrayList<>();
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split("\t", 4);
            if (p.length < 4) continue;
            AreaRec a = new AreaRec();
            a.id = Integer.parseInt(p[0]);
            a.name = p[1];
            a.hide = "t".equals(p[2]) || "true".equals(p[2]);
            JSONObject data = new JSONObject(p[3]);
            JSONArray space = data.optJSONArray("space");
            if (space != null) {
                for (int k = 0; k < space.length(); k++) {
                    JSONObject sp = space.getJSONObject(k);
                    Area ar = new Area(new Coord(sp.getInt("begin_x"), sp.getInt("begin_y")), new Coord(sp.getInt("end_x"), sp.getInt("end_y")));
                    long gid = sp.getLong("id");
                    a.pieces.put(gid, ar);
                    a.vspace.put(gid, new NArea.VArea(ar));
                    if (graph.getChunk(gid) != null) a.recorded = true;
                }
            }
            JSONArray spec = data.optJSONArray("spec");
            if (spec != null) {
                for (int k = 0; k < spec.length(); k++) {
                    JSONObject s = spec.getJSONObject(k);
                    a.specs.add(s.optString("name") + (s.has("subtype") ? "/" + s.optString("subtype") : ""));
                }
            }
            res.add(a);
        }
        return res;
    }

    static long[] startTile(ChunkNavGraph graph, AreaRec a, Random rnd) {
        AreaGoalSet g = AreaGoalSet.ofArea(graph, a.pieces);
        List<long[]> cand = new ArrayList<>();
        for (Map.Entry<Long, BitSet> e : g.tiles.entrySet()) {
            BitSet b = e.getValue();
            for (int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) cand.add(new long[] {e.getKey(), i % 100, i / 100});
        }
        return cand.isEmpty() ? null : cand.get(rnd.nextInt(cand.size()));
    }
}
