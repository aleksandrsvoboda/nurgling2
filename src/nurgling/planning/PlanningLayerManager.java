package nurgling.planning;

import haven.Coord;
import haven.Coord2d;
import haven.Glob;
import haven.Gob;
import haven.Indir;
import haven.MCache;
import haven.Message;
import haven.MessageBuf;
import haven.ResDrawable;
import haven.Resource;
import nurgling.GhostAlpha;
import nurgling.NConfig;
import nurgling.NUtils;
import nurgling.profiles.ConfigFactory;
import nurgling.profiles.ProfileAwareService;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the persistent set of planning-layer ghosts for the current profile.
 *
 * Entries live in memory + on disk (planning_layer.nurgling.json). Materialized
 * ghost gobs are created lazily when the planning toggle is on and the entry's
 * anchor grid is loaded in the player's view. Resources that fail to load are
 * skipped on materialization but their entries remain on disk for future
 * sessions, per the design contract.
 */
public class PlanningLayerManager implements ProfileAwareService {

    private final List<PlanningGhost> entries = new ArrayList<>();
    private final Map<Long, Gob> materialized = new HashMap<>();
    private final AtomicLong idGen = new AtomicLong(System.currentTimeMillis());
    private String genus;
    private String configPath;
    private boolean dirty = false;
    private long lastChangeTime = 0;
    public static final long DEBOUNCE_MS = 3000;

    public PlanningLayerManager() {
        NConfig cfg = NConfig.getGlobalInstance();
        this.configPath = (cfg != null) ? cfg.getPlanningLayerPath() : null;
        if (this.configPath != null) {
            load();
        }
    }

    @Override
    public void initializeForProfile(String genus) {
        this.genus = genus;
        NConfig cfg = ConfigFactory.getConfig(genus);
        this.configPath = cfg.getPlanningLayerPath();
        // Drop any visuals from the previous profile and reload entries.
        destroyAllMaterialized();
        entries.clear();
        load();
    }

    @Override
    public String getGenus() {
        return genus;
    }

    @Override
    public void load() {
        entries.clear();
        if (configPath == null) return;
        String content = NFileUtils.readWithBackupFallback(configPath);
        if (content == null || content.isEmpty()) return;
        try {
            JSONObject main = new JSONObject(content);
            JSONArray arr = main.getJSONArray("ghosts");
            for (int i = 0; i < arr.length(); i++) {
                try {
                    PlanningGhost g = PlanningGhost.fromJson(arr.getJSONObject(i));
                    entries.add(g);
                    if (g.id >= idGen.get()) idGen.set(g.id + 1);
                } catch (JSONException ignore) {
                    // Skip malformed entries individually rather than abandoning the whole file.
                }
            }
        } catch (JSONException e) {
            System.err.println("[PlanningLayerManager] failed to parse " + configPath + ": " + e.getMessage());
        }
    }

    @Override
    public void save() {
        if (configPath == null) return;
        JSONObject main = new JSONObject();
        main.put("version", 1);
        JSONArray arr = new JSONArray();
        for (PlanningGhost g : entries) arr.put(g.toJson());
        main.put("ghosts", arr);
        try {
            NFileUtils.writeAtomically(configPath, main.toString());
            dirty = false;
            lastChangeTime = 0;
        } catch (IOException e) {
            System.err.println("[PlanningLayerManager] save failed, will retry: " + e.getMessage());
        }
    }

    public boolean isDirty() {
        if (!dirty) return false;
        return (System.currentTimeMillis() - lastChangeTime) >= DEBOUNCE_MS;
    }

    private void markDirty() {
        dirty = true;
        lastChangeTime = System.currentTimeMillis();
    }

    public List<PlanningGhost> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Capture a ghost at a world position. Returns the new entry, or null if
     * the placement couldn't be anchored to a known grid.
     */
    public PlanningGhost addGhost(String resName, byte[] sdt, Coord2d worldPos, double angleRadians) {
        if (resName == null) return null;
        Glob glob = activeGlob();
        if (glob == null) return null;
        Coord chunk = worldPos.floor(MCache.tilesz).div(MCache.cmaps);
        MCache.Grid grid = glob.map.grids.get(chunk);
        if (grid == null) return null;
        Coord2d ul = grid.ul.mul(MCache.tilesz);
        Coord2d off = worldPos.sub(ul);
        PlanningGhost g = new PlanningGhost(
                idGen.getAndIncrement(),
                resName,
                sdt,
                grid.id,
                off.x, off.y,
                angleRadians);
        entries.add(g);
        markDirty();
        // If the layer is currently visible, materialize immediately.
        if (isOverlayEnabled()) {
            materializeOne(g, glob);
        }
        return g;
    }

    public boolean removeAt(Coord2d worldPos, double tolerance) {
        PlanningGhost best = findNear(worldPos, tolerance);
        if (best == null) return false;
        return remove(best);
    }

    public boolean remove(PlanningGhost g) {
        if (g == null) return false;
        if (!entries.remove(g)) return false;
        Gob gob = materialized.remove(g.id);
        if (gob != null) {
            Glob glob = activeGlob();
            if (glob != null) {
                try { glob.oc.remove(gob); } catch (Exception ignore) {}
            }
        }
        markDirty();
        return true;
    }

    public PlanningGhost findNear(Coord2d worldPos, double tolerance) {
        PlanningGhost best = null;
        double minDist = tolerance;
        Glob glob = activeGlob();
        if (glob == null) return null;
        for (PlanningGhost g : entries) {
            Coord2d wp = resolveWorldPos(g, glob);
            if (wp == null) continue;
            double d = wp.dist(worldPos);
            if (d < minDist) {
                minDist = d;
                best = g;
            }
        }
        return best;
    }

    /**
     * Reconcile materialized ghost gobs against the current toggle state and
     * loaded grids. Called from the NCore tick loop. Cheap when nothing changes.
     */
    public void tick() {
        boolean enabled = isOverlayEnabled();
        Glob glob = activeGlob();
        if (!enabled) {
            if (!materialized.isEmpty()) destroyAllMaterialized();
            return;
        }
        if (glob == null) return;

        // Materialize any entries whose grid is now loaded but not yet visible.
        for (PlanningGhost g : entries) {
            if (materialized.containsKey(g.id)) continue;
            materializeOne(g, glob);
        }
    }

    private void materializeOne(PlanningGhost g, Glob glob) {
        Coord2d wp = resolveWorldPos(g, glob);
        if (wp == null) return;
        try {
            Indir<Resource> res = Resource.remote().load(g.resName);
            Gob ghost = new Gob(glob, wp);
            ghost.a = g.angleRadians();
            ghost.setattr(new GhostAlpha(ghost));
            Message sdt = (g.sdt != null && g.sdt.length > 0) ? new MessageBuf(g.sdt) : Message.nil;
            ghost.setattr(new ResDrawable(ghost, res, sdt));
            // Re-apply rotation via move() so the Placed.Placement op picks up the
            // angle on its first build; setting the field alone before oc.add was
            // observed to be insufficient for some placers.
            ghost.move(wp, g.angleRadians());
            glob.oc.add(ghost);
            if (ghost.ngob != null) ghost.ngob.hitBox = null;
            materialized.put(g.id, ghost);
        } catch (Exception ex) {
            // Resource not loadable right now — leave the entry on disk and try later.
        }
    }

    private Coord2d resolveWorldPos(PlanningGhost g, Glob glob) {
        MCache.Grid grid = glob.map.findGrid(g.gridId);
        if (grid == null) return null;
        Coord2d ul = grid.ul.mul(MCache.tilesz);
        return ul.add(g.ox, g.oy);
    }

    private void destroyAllMaterialized() {
        if (materialized.isEmpty()) return;
        Glob glob = activeGlob();
        Iterator<Map.Entry<Long, Gob>> it = materialized.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Gob> e = it.next();
            if (glob != null) {
                try { glob.oc.remove(e.getValue()); } catch (Exception ignore) {}
            }
            it.remove();
        }
    }

    private static boolean isOverlayEnabled() {
        Object v = NConfig.get(NConfig.Key.planningLayerOverlay);
        return v instanceof Boolean && (Boolean) v;
    }

    private static Glob activeGlob() {
        try {
            return NUtils.getGameUI() != null && NUtils.getGameUI().ui != null && NUtils.getGameUI().ui.sess != null
                    ? NUtils.getGameUI().ui.sess.glob
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

}
