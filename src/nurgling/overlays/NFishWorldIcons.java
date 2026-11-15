package nurgling.overlays;

import haven.*;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;
import nurgling.FishLocation;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.tools.FlatWorld;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Saved fish locations drawn in the world: on each tile that has any, a row of the fish's icons with the best chance
 * saved for each (a fish survey saves the same fish once per gear combination; the best one is shown). Off unless
 * {@link NConfig.Key#showFishWorldIcons} is on.
 *
 * <p>The tile list is rebuilt once a second from the fish-location store (grid id + offset resolved against the loaded
 * grids, so only tiles near the player); the 2D pass only projects those few points.
 */
public class NFishWorldIcons implements RenderTree.Node, PView.Render2D {
    /** Tiles further than this from the player (tiles) are not drawn. */
    private static final double RANGE_TILES = 60;
    private static final long REBUILD_MS = 1000;
    private static final double LIFT = 3;

    private final NMapView mv;
    private volatile List<Spot> spots = Collections.emptyList();
    private long lastBuild = 0;
    private final Map<String, TexI> icons = new HashMap<>();
    private final Map<String, Tex> labels = new HashMap<>();

    private static final class Spot {
        final Coord2d rc;
        /** Best saved chance per fish resource, best fish first. */
        final List<Map.Entry<String, Double>> fish;

        Spot(Coord2d rc, List<Map.Entry<String, Double>> fish) {
            this.rc = rc;
            this.fish = fish;
        }
    }

    public NFishWorldIcons(NMapView mv) {
        this.mv = mv;
    }

    public static boolean enabled() {
        return Boolean.TRUE.equals(NConfig.get(NConfig.Key.showFishWorldIcons));
    }

    /** Called from the map view's tick: rebuilds the tile list at most once a second. */
    public void update() {
        if (!enabled()) {
            spots = Collections.emptyList();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBuild < REBUILD_MS)
            return;
        lastBuild = now;
        NGameUI gui = mv.getparent(NGameUI.class);
        Gob player = mv.player();
        if (gui == null || gui.fishLocationService == null || player == null) {
            spots = Collections.emptyList();
            return;
        }
        Map<Long, Coord> loaded = new HashMap<>();
        synchronized (mv.glob.map.grids) {
            for (MCache.Grid g : mv.glob.map.grids.values())
                loaded.put(g.id, g.ul);
        }
        double range = RANGE_TILES * MCache.tilesz.x;
        Map<Coord, Map<String, Double>> byTile = new HashMap<>();
        for (FishLocation loc : gui.fishLocationService.getAllFishLocations()) {
            if (loc.getGridId() == FishLocation.NO_GRID || loc.getOffset() == null || loc.getFishResource() == null)
                continue;
            Coord ul = loaded.get(loc.getGridId());
            if (ul == null)
                continue;
            Coord tc = ul.add(loc.getOffset());
            if (Coord2d.of(tc).add(0.5, 0.5).mul(MCache.tilesz).dist(player.rc) > range)
                continue;
            byTile.computeIfAbsent(tc, k -> new HashMap<>()).merge(loc.getFishResource(), percent(loc.getPercentage()), Math::max);
        }
        List<Spot> res = new ArrayList<>();
        for (Map.Entry<Coord, Map<String, Double>> e : byTile.entrySet()) {
            List<Map.Entry<String, Double>> fish = new ArrayList<>(e.getValue().entrySet());
            fish.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            res.add(new Spot(Coord2d.of(e.getKey()).add(0.5, 0.5).mul(MCache.tilesz), fish));
        }
        spots = res;
    }

    @Override
    public void draw(GOut g, Pipe state) {
        List<Spot> s = spots;
        if (s.isEmpty())
            return;
        Area va = Area.sized(g.sz());
        boolean flat = FlatWorld.isEnabled();
        int isz = UI.scale(16), gap = UI.scale(2);
        for (Spot spot : s) {
            double z;
            try {
                z = flat ? 0 : mv.glob.map.getcz(spot.rc.x, spot.rc.y);
            } catch (Loading e) {
                continue;
            }
            HomoCoord4f hc = Homo3D.obj2clip(new Coord3f((float) spot.rc.x, (float) -spot.rc.y, (float) (z + LIFT)), state);
            if (hc.w <= 0)
                continue;
            Coord sc = hc.toview(va).round2();
            if (!va.contains(sc))
                continue;
            int n = spot.fish.size();
            int x = sc.x - (n * isz + (n - 1) * gap) / 2;
            for (Map.Entry<String, Double> f : spot.fish) {
                TexI icon = icon(f.getKey());
                if (icon != null)
                    g.image(icon, Coord.of(x, sc.y - isz), Coord.of(isz, isz));
                if (f.getValue() >= 0) {
                    Tex lbl = label(f.getValue());
                    g.aimage(lbl, Coord.of(x + isz / 2, sc.y), 0.5, 0);
                }
                x += isz + gap;
            }
        }
    }

    /** The fish's inventory icon, or null until its resource has loaded (UI thread: never waits). */
    private TexI icon(String res) {
        if (icons.containsKey(res))
            return icons.get(res); // null: a resource that failed for good
        try {
            BufferedImage img = Resource.remote().load(res).get().layer(Resource.imgc).img;
            TexI t = new TexI(img);
            icons.put(res, t);
            return t;
        } catch (Loading e) {
            return null;
        } catch (Resource.LoadFailedException e) {
            // A bad resource name (NoSuchResourceException is not a Loading): skipped for good.
            icons.put(res, null);
            return null;
        }
    }

    private Tex label(double pct) {
        String s = (pct == Math.rint(pct)) ? String.format("%.0f%%", pct) : String.format("%.1f%%", pct);
        Tex t = labels.get(s);
        if (t == null) {
            t = Text.renderstroked(s, Color.WHITE, Color.BLACK, Text.num12boldFnd).tex();
            labels.put(s, t);
        }
        return t;
    }

    /** "23%", "7.5 %" or "Unknown" -> 23, 7.5, -1. */
    public static double percent(String p) {
        if (p == null)
            return -1;
        StringBuilder num = new StringBuilder();
        for (char c : p.toCharArray()) {
            if (Character.isDigit(c) || c == '.')
                num.append(c);
            else if (num.length() > 0)
                break;
        }
        try {
            return num.length() == 0 ? -1 : Double.parseDouble(num.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
