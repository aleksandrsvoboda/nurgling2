package nurgling.overlays;

import haven.*;
import haven.render.BaseColor;
import haven.render.States;
import nurgling.NConfig;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Fish nodes on the water, worked out live from the jumping-fish splashes.
 *
 * <p>The server never sends a node's extent. It replays the splash ({@code gfx/fx/fishjump}, an overlay on a throwaway
 * gob with no data) at a fixed set of points, several per node. So every distinct splash point is kept, points within
 * the grouping distance of each other form one node, and the node is drawn as the water tiles nearest its points.
 *
 * <p>Memory only, one instance per session ({@link MCache#fishNodes}): nodes can move, so nothing is saved. A point is
 * kept while out of view (there is no evidence either way) and forgotten once it has been in view for
 * {@link #FORGET_MS} without splashing. Points are keyed by grid id plus offset, since gob coordinates are
 * session-local.
 */
public final class FishNodes {
    public static final String JUMP = "gfx/fx/fishjump";

    /** A point this close to the player (tiles) is in view: a splash there would have been seen. */
    static final double VIEW_TILES = 35;
    /** In-view time without a splash after which a point is forgotten. */
    static final long FORGET_MS = 20 * 60_000;
    static final long TICK_MS = 1000;
    /** A splash whose grid never loads is dropped after this long. */
    static final long RESOLVE_MS = 60_000;

    private static final Color[] PALETTE = {
            new Color(40, 200, 255), new Color(255, 170, 40), new Color(120, 230, 90),
            new Color(230, 90, 220), new Color(255, 240, 80), new Color(255, 90, 90),
    };

    private final MCache map;
    private final Layer[] layers = new Layer[PALETTE.length];

    /** Filled from the network thread, drained by {@link #tick}. */
    private final ConcurrentLinkedQueue<Splash> incoming = new ConcurrentLinkedQueue<>();
    /* Everything below is touched by tick() only. */
    private final List<Splash> unresolved = new ArrayList<>();
    private final Map<Key, Point> points = new HashMap<>();
    private long lastTick = 0;
    private boolean dirty = false;
    private int lastGridsHash = 0;
    private int lastLink = -1;
    private volatile boolean shown = false;

    public FishNodes(MCache map) {
        this.map = map;
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new Layer(PALETTE[i]);
            map.add(layers[i]);
        }
    }

    /** True for the splash resource; decided from the name the server already sent, without loading it. */
    public static boolean isJump(Indir<Resource> res) {
        return (res instanceof Session.CachedRes.Ref) && JUMP.equals(((Session.CachedRes.Ref) res).resnm());
    }

    /** Network thread: the server put a splash on {@code host}. */
    public void splashed(Gob host) {
        Coord2d rc = host.rc;
        if (rc != null)
            incoming.add(new Splash(rc, System.currentTimeMillis()));
    }

    /** Called from the map view's tick with the player position (null when there is no player). */
    public void tick(Coord2d player) {
        long now = System.currentTimeMillis();
        boolean show = (Boolean) NConfig.get(NConfig.Key.showFishNodes);
        if (show != shown) {
            shown = show;
            map.olseq++;
        }
        for (Splash s; (s = incoming.poll()) != null; )
            unresolved.add(s);
        if (unresolved.isEmpty() && now - lastTick < TICK_MS)
            return;
        long dt = (lastTick == 0) ? 0 : Math.min(now - lastTick, 5000);
        lastTick = now;

        Map<Long, Coord> loaded = loadedGrids();
        resolve(now);
        if (player != null)
            forget(loaded, player, dt);

        int link = linkTiles();
        int gh = loaded.keySet().hashCode();
        if (dirty || gh != lastGridsHash || link != lastLink) {
            dirty = false;
            lastGridsHash = gh;
            lastLink = link;
            rebuild(loaded, link);
        }
    }

    private static int linkTiles() {
        Object v = NConfig.get(NConfig.Key.fishNodeLink);
        int l = (v instanceof Number) ? ((Number) v).intValue() : 14;
        return Math.max(4, Math.min(40, l));
    }

    /** Grid id -> grid's tile origin, for the grids loaded now. */
    private Map<Long, Coord> loadedGrids() {
        Map<Long, Coord> ret = new HashMap<>();
        synchronized (map.grids) {
            for (MCache.Grid g : map.grids.values())
                ret.put(g.id, g.ul);
        }
        return ret;
    }

    private void resolve(long now) {
        for (Iterator<Splash> i = unresolved.iterator(); i.hasNext(); ) {
            Splash s = i.next();
            Coord tc = s.rc.floor(MCache.tilesz);
            MCache.Grid g;
            try {
                g = map.getgridt(tc);
            } catch (Loading e) {
                if (now - s.at > RESOLVE_MS)
                    i.remove();
                continue;
            }
            i.remove();
            Coord2d off = s.rc.sub(Coord2d.of(g.ul).mul(MCache.tilesz));
            Key k = new Key(g.id, Math.round(off.x * 64), Math.round(off.y * 64));
            Point p = points.get(k);
            if (p == null) {
                points.put(k, new Point(k, off));
                dirty = true;
            } else {
                p.unseen = 0;
            }
        }
    }

    /** Points in view that have stayed silent too long are gone. Out of view their clock is paused. */
    private void forget(Map<Long, Coord> loaded, Coord2d player, long dt) {
        double view = VIEW_TILES * MCache.tilesz.x;
        for (Iterator<Point> i = points.values().iterator(); i.hasNext(); ) {
            Point p = i.next();
            Coord2d rc = p.local(loaded);
            if (rc == null || rc.dist(player) > view)
                continue;
            p.unseen += dt;
            if (p.unseen > FORGET_MS) {
                i.remove();
                dirty = true;
            }
        }
    }

    private void rebuild(Map<Long, Coord> loaded, int link) {
        List<Coord2d> pts = new ArrayList<>();
        for (Point p : points.values()) {
            Coord2d rc = p.local(loaded);
            if (rc != null)
                pts.add(rc);
        }
        int n = pts.size();

        /* Nodes: points linked within the grouping distance. */
        int[] par = new int[n];
        for (int i = 0; i < n; i++)
            par[i] = i;
        double ld = link * MCache.tilesz.x;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < i; j++)
                if (pts.get(i).dist(pts.get(j)) <= ld)
                    par[find(par, i)] = find(par, j);

        /* Each water tile within reach goes to its nearest point's node. */
        int reach = Math.max(3, link / 2);
        double rd = reach * MCache.tilesz.x;
        Map<Coord, Integer> owner = new HashMap<>();
        Map<Coord, Double> best = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Coord2d p = pts.get(i);
            Coord pc = p.floor(MCache.tilesz);
            int node = find(par, i);
            for (int y = -reach; y <= reach; y++) {
                for (int x = -reach; x <= reach; x++) {
                    Coord tc = pc.add(x, y);
                    double d = Coord2d.of(tc).add(0.5, 0.5).mul(MCache.tilesz).dist(p);
                    if (d > rd)
                        continue;
                    Double b = best.get(tc);
                    if (b != null && b <= d)
                        continue;
                    if (b == null && !water(tc))
                        continue;
                    best.put(tc, d);
                    owner.put(tc, node);
                }
            }
        }

        /* Neighbouring nodes get different colours. */
        Map<Integer, Set<Integer>> adj = new HashMap<>();
        Map<Integer, Integer> size = new HashMap<>();
        for (Map.Entry<Coord, Integer> e : owner.entrySet()) {
            int a = e.getValue();
            size.merge(a, 1, Integer::sum);
            for (Coord d : Coord.uecw) {
                Integer b = owner.get(e.getKey().add(d));
                if (b != null && b != a) {
                    adj.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                    adj.computeIfAbsent(b, k -> new HashSet<>()).add(a);
                }
            }
        }
        List<Integer> order = new ArrayList<>(size.keySet());
        order.sort((a, b) -> size.get(b) - size.get(a));
        Map<Integer, Integer> colour = new HashMap<>();
        for (int node : order) {
            boolean[] used = new boolean[PALETTE.length];
            for (int o : adj.getOrDefault(node, Collections.emptySet())) {
                Integer c = colour.get(o);
                if (c != null)
                    used[c] = true;
            }
            int c = 0;
            while (c < used.length - 1 && used[c])
                c++;
            colour.put(node, c);
        }

        List<Set<Coord>> tiles = new ArrayList<>();
        for (int i = 0; i < layers.length; i++)
            tiles.add(new HashSet<>());
        for (Map.Entry<Coord, Integer> e : owner.entrySet())
            tiles.get(colour.get(e.getValue())).add(e.getKey());
        /* Bumping olseq rebuilds every map overlay in view, so only when the painted tiles really changed. */
        boolean changed = false;
        for (int i = 0; i < layers.length; i++) {
            Snap old = layers[i].snap;
            Set<Coord> now = tiles.get(i);
            if ((old == null) ? !now.isEmpty() : !old.tiles.equals(now)) {
                layers[i].snap = Snap.of(now);
                changed = true;
            }
        }
        if (changed)
            map.olseq++;
    }

    private static int find(int[] par, int i) {
        while (par[i] != i)
            i = par[i] = par[par[i]];
        return i;
    }

    private boolean water(Coord tc) {
        String n;
        try {
            n = map.tilesetname(map.gettile(tc));
        } catch (Loading e) {
            return false;
        }
        return n != null && (n.startsWith("gfx/tiles/water") || n.startsWith("gfx/tiles/owater")
                || n.equals("gfx/tiles/deep") || n.equals("gfx/tiles/odeep") || n.equals("gfx/tiles/odeeper")
                || n.equals("gfx/tiles/deeptangle") || n.equals("gfx/tiles/warmdepth"));
    }

    private static final class Splash {
        final Coord2d rc;
        final long at;

        Splash(Coord2d rc, long at) {
            this.rc = rc;
            this.at = at;
        }
    }

    /** Grid id plus offset in 1/64 units: the exact spot the server replays. */
    private static final class Key {
        final long grid, x, y;

        Key(long grid, long x, long y) {
            this.grid = grid;
            this.x = x;
            this.y = y;
        }

        public boolean equals(Object o) {
            return (o instanceof Key) && ((Key) o).grid == grid && ((Key) o).x == x && ((Key) o).y == y;
        }

        public int hashCode() {
            return Objects.hash(grid, x, y);
        }
    }

    private static final class Point {
        final Key key;
        final Coord2d off;
        /** In-view time since the last splash, ms. */
        long unseen = 0;

        Point(Key key, Coord2d off) {
            this.key = key;
            this.off = off;
        }

        /** Session-local position, or null while the point's grid isn't loaded. */
        Coord2d local(Map<Long, Coord> loaded) {
            Coord ul = loaded.get(key.grid);
            return (ul == null) ? null : Coord2d.of(ul).mul(MCache.tilesz).add(off);
        }
    }

    private static final class Snap {
        final Set<Coord> tiles;
        final Area bounds;

        private Snap(Set<Coord> tiles, Area bounds) {
            this.tiles = tiles;
            this.bounds = bounds;
        }

        static Snap of(Set<Coord> tiles) {
            if (tiles.isEmpty())
                return null;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for (Coord c : tiles) {
                x0 = Math.min(x0, c.x); y0 = Math.min(y0, c.y);
                x1 = Math.max(x1, c.x); y1 = Math.max(y1, c.y);
            }
            return new Snap(tiles, Area.corni(Coord.of(x0, y0), Coord.of(x1, y1)));
        }
    }

    /** One colour of the palette: every node painted in it. */
    private final class Layer implements MCache.LocalOverlay {
        final MCache.OverlayInfo info;
        volatile Snap snap;

        Layer(Color c) {
            Material mat = new Material(new BaseColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 70)), States.maskdepth);
            Material omat = new Material(new BaseColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 220)), States.maskdepth);
            info = new MCache.OverlayInfo() {
                public Collection<String> tags() {
                    return Collections.singletonList("show");
                }

                public Material mat() {
                    return mat;
                }

                public Material omat() {
                    return omat;
                }
            };
        }

        public MCache.OverlayInfo id() {
            return info;
        }

        public boolean filter(Area a) {
            Snap s = snap;
            return !shown || s == null || s.bounds.overlap(a) == null;
        }

        public void fill(Area a, boolean[] buf) {
            Snap s = snap;
            if (!shown || s == null)
                return;
            Area o = s.bounds.overlap(a);
            if (o == null)
                return;
            for (Coord c : o)
                if (s.tiles.contains(c))
                    buf[a.ri(c)] = true;
        }
    }
}
