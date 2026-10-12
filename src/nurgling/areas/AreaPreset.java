package nurgling.areas;

import haven.Area;
import haven.Coord;
import haven.MCache;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.util.*;

/**
 * A group of areas saved as a layout that can be placed anywhere, in any world.
 *
 * Areas themselves are stored as pieces of particular grids ({@link NArea.Space}), which ties them
 * to one spot. A preset keeps plain tile offsets from the group's top-left corner instead, plus
 * everything that makes an area useful to the bots: specialisations, Take/Put rules, fill direction.
 */
public class AreaPreset
{
    public static final int FORMAT = 1;
    /** {@link NArea.Space} only splits an area over two grids per axis, so no side may pass a grid's width. */
    public static final int MAX_AREA_SIDE = 100;

    public String name;
    public String description = "";
    /** Groups built-in presets in the Presets window, using the bot menu's categories ("farming"). */
    public String category = "";
    /** Size of the bounding box, in tiles. */
    public int w, h;
    public final List<Entry> areas = new ArrayList<>();
    /** Shipped with the client: shown, placed and exported, but never renamed or deleted. */
    public boolean builtin = false;
    /** File name inside the presets folder, for user presets. */
    public String file = null;

    public static class Entry
    {
        public String name;
        /** Folder relative to the preset: "" for its top level, "/Racks" for a sub-folder. */
        public String path = "";
        /** Tile offset from the preset's top-left corner and size. */
        public int x, y, w, h;
        public Color color = new Color(194, 194, 65, 56);
        public boolean hide = false;
        public JSONArray spec = new JSONArray();
        public JSONArray in = new JSONArray();
        public JSONArray out = new JSONArray();
        public PileFillDirection fill = PileFillDirection.DEFAULT;

        Entry copy()
        {
            Entry e = new Entry();
            e.name = name;
            e.path = path;
            e.x = x;
            e.y = y;
            e.w = w;
            e.h = h;
            e.color = color;
            e.hide = hide;
            e.spec = new JSONArray(spec.toString());
            e.in = new JSONArray(in.toString());
            e.out = new JSONArray(out.toString());
            e.fill = fill;
            return e;
        }
    }

    /** Thrown when a selection can't be saved; the message is shown to the player. */
    public static class CaptureException extends Exception
    {
        public CaptureException(String message)
        {
            super(message);
        }
    }

    public static AreaPreset fromJson(JSONObject obj)
    {
        AreaPreset p = new AreaPreset();
        p.name = obj.getString("name");
        p.description = obj.optString("description", "");
        p.category = obj.optString("category", "");
        JSONArray jareas = obj.getJSONArray("areas");
        for (int i = 0; i < jareas.length(); i++) {
            JSONObject ja = jareas.getJSONObject(i);
            Entry e = new Entry();
            e.name = ja.getString("name");
            e.path = ja.optString("path", "");
            e.x = ja.getInt("x");
            e.y = ja.getInt("y");
            e.w = ja.getInt("w");
            e.h = ja.getInt("h");
            if (e.w <= 0 || e.h <= 0 || e.w > MAX_AREA_SIDE || e.h > MAX_AREA_SIDE)
                throw new IllegalArgumentException("Bad size for area " + e.name);
            JSONObject jc = ja.optJSONObject("color");
            if (jc != null)
                e.color = new Color(jc.getInt("r"), jc.getInt("g"), jc.getInt("b"), jc.getInt("a"));
            e.hide = ja.optBoolean("hide", false);
            if (ja.has("spec"))
                e.spec = ja.getJSONArray("spec");
            if (ja.has("in"))
                e.in = ja.getJSONArray("in");
            if (ja.has("out"))
                e.out = ja.getJSONArray("out");
            e.fill = PileFillDirection.fromStored(ja.opt(NArea.PILE_FILL_DIRECTION_JSON));
            p.areas.add(e);
        }
        if (p.areas.isEmpty())
            throw new IllegalArgumentException("Preset has no areas");
        p.normalize();
        return p;
    }

    public JSONObject toJson()
    {
        JSONObject res = new JSONObject();
        res.put("format", FORMAT);
        res.put("name", name);
        if (!description.isEmpty())
            res.put("description", description);
        if (!category.isEmpty())
            res.put("category", category);
        JSONObject size = new JSONObject();
        size.put("w", w);
        size.put("h", h);
        res.put("size", size);
        JSONArray jareas = new JSONArray();
        for (Entry e : areas) {
            JSONObject ja = new JSONObject();
            ja.put("name", e.name);
            ja.put("path", e.path);
            ja.put("x", e.x);
            ja.put("y", e.y);
            ja.put("w", e.w);
            ja.put("h", e.h);
            JSONObject jc = new JSONObject();
            jc.put("r", e.color.getRed());
            jc.put("g", e.color.getGreen());
            jc.put("b", e.color.getBlue());
            jc.put("a", e.color.getAlpha());
            ja.put("color", jc);
            ja.put("hide", e.hide);
            ja.put("spec", e.spec);
            ja.put("in", e.in);
            ja.put("out", e.out);
            ja.put(NArea.PILE_FILL_DIRECTION_JSON, e.fill.name());
            jareas.put(ja);
        }
        res.put("areas", jareas);
        return res;
    }

    /** Moves the areas so the box starts at (0,0) and recomputes the box size. */
    void normalize()
    {
        int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE;
        int maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE;
        for (Entry e : areas) {
            minx = Math.min(minx, e.x);
            miny = Math.min(miny, e.y);
            maxx = Math.max(maxx, e.x + e.w);
            maxy = Math.max(maxy, e.y + e.h);
        }
        for (Entry e : areas) {
            e.x -= minx;
            e.y -= miny;
        }
        w = maxx - minx;
        h = maxy - miny;
    }

    /** Number of distinct sub-folders, the preset's own top level not counted. */
    public int folderCount()
    {
        Set<String> paths = new HashSet<>();
        for (Entry e : areas)
            paths.add(e.path);
        return AreaPaths.allFolders(paths).size();
    }

    /**
     * A copy turned {@code turns} quarter turns clockwise, then mirrored left to right if asked.
     * Fill directions turn along with the rectangles.
     */
    public AreaPreset transformed(int turns, boolean mirror)
    {
        AreaPreset p = new AreaPreset();
        p.name = name;
        p.description = description;
        p.category = category;
        p.builtin = builtin;
        p.file = file;
        int t = Math.floorMod(turns, 4);
        int bw = w, bh = h;
        for (Entry src : areas) {
            Entry e = src.copy();
            int cw = bw, ch = bh;
            for (int i = 0; i < t; i++) {
                int nx = ch - e.y - e.h;
                int ny = e.x;
                int nw = e.h;
                e.x = nx;
                e.y = ny;
                e.h = e.w;
                e.w = nw;
                e.fill = turn(e.fill);
                int tmp = cw;
                cw = ch;
                ch = tmp;
            }
            if (mirror) {
                e.x = cw - e.x - e.w;
                e.fill = mirror(e.fill);
            }
            p.areas.add(e);
        }
        p.normalize();
        return p;
    }

    /** The direction a zone is worked in after the layout turns a quarter clockwise. */
    static PileFillDirection turn(PileFillDirection d)
    {
        switch (d) {
            case LEFT_TO_RIGHT: return PileFillDirection.TOP_TO_BOTTOM;
            case TOP_TO_BOTTOM: return PileFillDirection.RIGHT_TO_LEFT;
            case RIGHT_TO_LEFT: return PileFillDirection.BOTTOM_TO_TOP;
            case BOTTOM_TO_TOP: return PileFillDirection.LEFT_TO_RIGHT;
            default: return d;
        }
    }

    static PileFillDirection mirror(PileFillDirection d)
    {
        switch (d) {
            case LEFT_TO_RIGHT: return PileFillDirection.RIGHT_TO_LEFT;
            case RIGHT_TO_LEFT: return PileFillDirection.LEFT_TO_RIGHT;
            default: return d;
        }
    }

    /** The deepest folder holding every given area path and folder. */
    static String commonRoot(Collection<String> areaPaths, Collection<String> folders)
    {
        List<String> all = new ArrayList<>(areaPaths);
        for (String f : folders)
            all.add(AreaPaths.parent(f));
        String root = null;
        for (String p : all) {
            if (root == null) {
                root = p;
                continue;
            }
            while (!AreaPaths.isUnder(p, root))
                root = AreaPaths.parent(root);
        }
        return (root == null) ? AreaPaths.ROOT : root;
    }

    /**
     * Saves the given areas and every area under the given folders as a preset. Each area must be
     * fully loaded on {@code map}: an area missing a grid would otherwise be saved cut short.
     */
    public static AreaPreset capture(String name, Collection<NArea> picked, Collection<String> folders,
                                     Collection<NArea> all, MCache map) throws CaptureException
    {
        LinkedHashMap<Integer, NArea> chosen = new LinkedHashMap<>();
        for (NArea a : picked)
            chosen.put(a.id, a);
        for (NArea a : all) {
            for (String f : folders) {
                if (AreaPaths.isUnder(a.path, f))
                    chosen.put(a.id, a);
            }
        }
        if (chosen.isEmpty())
            throw new CaptureException(nurgling.i18n.L10n.get("area.preset.capture_empty"));

        List<String> paths = new ArrayList<>();
        for (NArea a : picked)
            paths.add(a.path);
        String root = commonRoot(paths, folders);

        AreaPreset p = new AreaPreset();
        p.name = name;
        for (NArea a : chosen.values()) {
            boolean loaded = !a.space.space.isEmpty();
            for (Long gid : a.space.space.keySet()) {
                if (map.findGrid(gid) == null)
                    loaded = false;
            }
            Area bounds = loaded ? a.getArea(map) : null;
            if (bounds == null)
                throw new CaptureException(nurgling.i18n.L10n.get("area.preset.capture_unloaded", a.name));
            Entry e = new Entry();
            e.name = a.name;
            e.path = AreaPaths.reparent(a.path, root, AreaPaths.ROOT);
            e.x = bounds.ul.x;
            e.y = bounds.ul.y;
            Coord sz = bounds.sz();
            e.w = sz.x;
            e.h = sz.y;
            e.color = a.color;
            e.hide = a.hide;
            JSONObject ja = a.toJson();
            e.spec = new JSONArray(ja.getJSONArray("spec").toString());
            e.in = new JSONArray(a.jin.toString());
            e.out = new JSONArray(a.jout.toString());
            e.fill = (a.pileFillDirection == null) ? PileFillDirection.DEFAULT : a.pileFillDirection;
            p.areas.add(e);
        }
        p.normalize();
        return p;
    }
}
