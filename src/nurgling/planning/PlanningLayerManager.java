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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the persistent planning tree for the current profile.
 *
 * Structure: a forest of {@link PlanningNode}s ordered in {@code roots}, where
 * each {@link PlanningFolder} may contain {@link PlanningLayer} children (v1
 * permits one level of nesting). Layers hold {@link PlanningGhost} entries.
 *
 * Visibility is governed entirely by per-node eye toggles; the {@code
 * windowOpen} flag only gates user-driven interactions (capture, delete,
 * clone) that originate in the Base Planner window. Rendering of existing
 * ghosts continues even with the window closed, controlled by the eye
 * toggles in the tree.
 *
 * v2 JSON schema (no migration from v1 — bad version starts fresh):
 * <pre>
 * {
 *   "version": 2,
 *   "activeLayerId": "...",
 *   "tree": [ {folder|layer}, ... ]
 * }
 * </pre>
 */
public class PlanningLayerManager implements ProfileAwareService {

    public static final int FILE_VERSION = 2;

    private final List<PlanningNode> roots = new ArrayList<>();
    private final Map<String, PlanningNode> byId = new HashMap<>();
    private final Map<Long, Gob> materialized = new HashMap<>();
    private final AtomicLong ghostIdGen = new AtomicLong(System.currentTimeMillis());
    private String activeLayerId;
    private String genus;
    private String configPath;
    private boolean dirty = false;
    private long lastChangeTime = 0;
    private boolean windowOpen = false;
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
        destroyAllMaterialized();
        roots.clear();
        byId.clear();
        activeLayerId = null;
        load();
    }

    @Override
    public String getGenus() {
        return genus;
    }

    /* ---------- Persistence ---------- */

    @Override
    public void load() {
        roots.clear();
        byId.clear();
        activeLayerId = null;
        if (configPath == null) return;
        String content = NFileUtils.readWithBackupFallback(configPath);
        if (content == null || content.isEmpty()) return;
        try {
            JSONObject main = new JSONObject(content);
            int version = main.optInt("version", 0);
            if (version != FILE_VERSION) {
                // No migration: bad/old file is ignored. Existing data on disk
                // is left untouched until the next save overwrites it.
                System.err.println("[PlanningLayerManager] ignoring incompatible v" + version + " file at " + configPath);
                return;
            }
            JSONArray tree = main.optJSONArray("tree");
            if (tree != null) {
                for (int i = 0; i < tree.length(); i++) {
                    PlanningNode node = parseNode(tree.getJSONObject(i), null);
                    if (node != null) {
                        roots.add(node);
                        index(node);
                    }
                }
            }
            String aid = main.optString("activeLayerId", null);
            if (aid != null && byId.get(aid) instanceof PlanningLayer) {
                activeLayerId = aid;
            } else {
                activeLayerId = firstLayerId();
            }
        } catch (JSONException e) {
            System.err.println("[PlanningLayerManager] failed to parse " + configPath + ": " + e.getMessage());
        }
    }

    private PlanningNode parseNode(JSONObject o, String parentId) {
        String type = o.optString("type", "");
        if ("folder".equals(type)) {
            PlanningFolder f = PlanningFolder.fromJson(o);
            f.parentId = parentId;
            return f;
        } else if ("layer".equals(type)) {
            PlanningLayer layer = PlanningLayer.fromJson(o);
            layer.parentId = parentId;
            // Advance the ghost id generator past any persisted ids so we don't collide.
            for (PlanningGhost g : layer.ghosts) {
                if (g.id >= ghostIdGen.get()) ghostIdGen.set(g.id + 1);
            }
            return layer;
        }
        return null;
    }

    private void index(PlanningNode node) {
        byId.put(node.id, node);
        if (node instanceof PlanningFolder) {
            for (PlanningLayer layer : ((PlanningFolder) node).layers) {
                layer.parentId = node.id;
                byId.put(layer.id, layer);
            }
        }
    }

    @Override
    public void save() {
        if (configPath == null) return;
        JSONObject main = buildJson(roots);
        if (activeLayerId != null) main.put("activeLayerId", activeLayerId);
        try {
            NFileUtils.writeAtomically(configPath, main.toString());
            dirty = false;
            lastChangeTime = 0;
        } catch (IOException e) {
            System.err.println("[PlanningLayerManager] save failed, will retry: " + e.getMessage());
        }
    }

    private JSONObject buildJson(List<PlanningNode> nodes) {
        JSONObject main = new JSONObject();
        main.put("version", FILE_VERSION);
        JSONArray arr = new JSONArray();
        for (PlanningNode n : nodes) arr.put(n.toJson());
        main.put("tree", arr);
        return main;
    }

    public boolean isDirty() {
        if (!dirty) return false;
        return (System.currentTimeMillis() - lastChangeTime) >= DEBOUNCE_MS;
    }

    private void markDirty() {
        dirty = true;
        lastChangeTime = System.currentTimeMillis();
    }

    /* ---------- Tree ops ---------- */

    public List<PlanningNode> getRoots() {
        return Collections.unmodifiableList(roots);
    }

    public PlanningNode getNode(String id) {
        return byId.get(id);
    }

    public PlanningLayer getLayer(String id) {
        PlanningNode n = byId.get(id);
        return (n instanceof PlanningLayer) ? (PlanningLayer) n : null;
    }

    public PlanningFolder getFolder(String id) {
        PlanningNode n = byId.get(id);
        return (n instanceof PlanningFolder) ? (PlanningFolder) n : null;
    }

    public String getActiveLayerId() { return activeLayerId; }

    public PlanningLayer getActiveLayer() { return getLayer(activeLayerId); }

    public void setActiveLayer(String layerId) {
        if (layerId == null || byId.get(layerId) instanceof PlanningLayer) {
            this.activeLayerId = layerId;
            markDirty();
        }
    }

    public PlanningFolder createFolder(String name) {
        PlanningFolder f = new PlanningFolder(UUID.randomUUID().toString(), name, true, null);
        roots.add(f);
        byId.put(f.id, f);
        markDirty();
        return f;
    }

    /**
     * Create a layer; if {@code parentFolderId} resolves to a folder, the new
     * layer becomes its child, otherwise it's a root. The new layer becomes
     * the active layer when no other layer is currently active.
     */
    public PlanningLayer createLayer(String name, String parentFolderId) {
        PlanningLayer layer = new PlanningLayer(UUID.randomUUID().toString(), name, true, null);
        PlanningFolder parent = (parentFolderId != null) ? getFolder(parentFolderId) : null;
        if (parent != null) {
            layer.parentId = parent.id;
            parent.layers.add(layer);
        } else {
            roots.add(layer);
        }
        byId.put(layer.id, layer);
        if (activeLayerId == null) activeLayerId = layer.id;
        markDirty();
        return layer;
    }

    /**
     * Delete a folder (with its layers) or a single layer. Materialized gobs
     * for any affected ghosts are torn down.
     */
    public boolean deleteNode(String id) {
        PlanningNode n = byId.get(id);
        if (n == null) return false;
        if (n instanceof PlanningFolder) {
            PlanningFolder f = (PlanningFolder) n;
            for (PlanningLayer layer : f.layers) {
                for (PlanningGhost g : layer.ghosts) destroyMaterialized(g.id);
                byId.remove(layer.id);
                if (layer.id.equals(activeLayerId)) activeLayerId = null;
            }
            f.layers.clear();
            roots.remove(f);
            byId.remove(f.id);
        } else if (n instanceof PlanningLayer) {
            PlanningLayer layer = (PlanningLayer) n;
            for (PlanningGhost g : layer.ghosts) destroyMaterialized(g.id);
            if (layer.parentId == null) {
                roots.remove(layer);
            } else {
                PlanningFolder parent = getFolder(layer.parentId);
                if (parent != null) parent.layers.remove(layer);
            }
            byId.remove(layer.id);
            if (layer.id.equals(activeLayerId)) activeLayerId = firstLayerId();
        }
        markDirty();
        return true;
    }

    public void setVisible(String id, boolean visible) {
        PlanningNode n = byId.get(id);
        if (n == null) return;
        n.visible = visible;
        markDirty();
    }

    public void renameNode(String id, String name) {
        PlanningNode n = byId.get(id);
        if (n == null || name == null || name.isEmpty()) return;
        n.name = name;
        markDirty();
    }

    /** True iff any layer in the tree is currently visible (taking parents into account). */
    public boolean anyVisible() {
        for (PlanningNode n : byId.values()) {
            if (n instanceof PlanningLayer && effectiveVisible(n)) return true;
        }
        return false;
    }

    /** Master visibility macro: if anything is visible, hide all; otherwise show all. */
    public void masterToggleVisibility() {
        boolean any = anyVisible();
        boolean newState = !any;
        for (PlanningNode n : byId.values()) {
            n.visible = newState;
        }
        markDirty();
    }

    public boolean effectiveVisible(PlanningNode n) {
        if (n == null) return false;
        if (!n.visible) return false;
        if (n.parentId == null) return true;
        return effectiveVisible(byId.get(n.parentId));
    }

    private String firstLayerId() {
        for (PlanningNode n : roots) {
            if (n instanceof PlanningLayer) return n.id;
            if (n instanceof PlanningFolder) {
                PlanningFolder f = (PlanningFolder) n;
                if (!f.layers.isEmpty()) return f.layers.get(0).id;
            }
        }
        return null;
    }

    /** Suggest the next "Layer NN" name (global counter across the tree). */
    public String suggestNextLayerName() {
        int max = -1;
        for (PlanningNode n : byId.values()) {
            if (!(n instanceof PlanningLayer)) continue;
            String name = n.name;
            if (name != null && name.startsWith("Layer ")) {
                try {
                    int v = Integer.parseInt(name.substring(6).trim());
                    if (v > max) max = v;
                } catch (NumberFormatException ignore) { }
            }
        }
        return String.format("Layer %02d", max + 1);
    }

    /* ---------- Window-open gating (for interactions, NOT visibility) ---------- */

    public void setWindowOpen(boolean open) {
        this.windowOpen = open;
    }

    public boolean isWindowOpen() { return windowOpen; }

    /* ---------- Ghost ops ---------- */

    /**
     * Capture a ghost at the given world position into the currently active
     * layer. Returns null if no active layer or the world position can't be
     * anchored to a loaded grid.
     */
    public PlanningGhost addGhost(String resName, byte[] sdt, Coord2d worldPos, double angleRadians) {
        if (resName == null) return null;
        PlanningLayer layer = getActiveLayer();
        if (layer == null) return null;
        Glob glob = activeGlob();
        if (glob == null) return null;
        Coord chunk = worldPos.floor(MCache.tilesz).div(MCache.cmaps);
        MCache.Grid grid = glob.map.grids.get(chunk);
        if (grid == null) return null;
        Coord2d ul = grid.ul.mul(MCache.tilesz);
        Coord2d off = worldPos.sub(ul);
        PlanningGhost g = new PlanningGhost(
                ghostIdGen.getAndIncrement(),
                resName,
                sdt,
                grid.id,
                off.x, off.y,
                angleRadians);
        layer.ghosts.add(g);
        markDirty();
        if (effectiveVisible(layer)) {
            materializeOne(g, glob);
        }
        return g;
    }

    /** Remove the ghost nearest to {@code worldPos} within {@code tolerance}, considering only visible layers. */
    public boolean removeGhostAt(Coord2d worldPos, double tolerance) {
        PlanningLayer owner = null;
        PlanningGhost best = null;
        double minDist = tolerance;
        Glob glob = activeGlob();
        if (glob == null) return false;
        for (PlanningNode n : byId.values()) {
            if (!(n instanceof PlanningLayer)) continue;
            PlanningLayer layer = (PlanningLayer) n;
            if (!effectiveVisible(layer)) continue;
            for (PlanningGhost g : layer.ghosts) {
                Coord2d wp = resolveWorldPos(g, glob);
                if (wp == null) continue;
                double d = wp.dist(worldPos);
                if (d < minDist) {
                    minDist = d;
                    best = g;
                    owner = layer;
                }
            }
        }
        if (best == null) return false;
        owner.ghosts.remove(best);
        destroyMaterialized(best.id);
        markDirty();
        return true;
    }

    /** Remove a specific ghost from its containing layer. */
    public boolean removeGhost(PlanningGhost g) {
        if (g == null) return false;
        for (PlanningNode n : byId.values()) {
            if (!(n instanceof PlanningLayer)) continue;
            PlanningLayer layer = (PlanningLayer) n;
            if (layer.ghosts.remove(g)) {
                destroyMaterialized(g.id);
                markDirty();
                return true;
            }
        }
        return false;
    }

    /** Find the ghost (in any visible layer) closest to {@code worldPos} within {@code tolerance}. */
    public PlanningGhost getGhostAt(Coord2d worldPos, double tolerance) {
        Glob glob = activeGlob();
        if (glob == null) return null;
        PlanningGhost best = null;
        double minDist = tolerance;
        for (PlanningNode n : byId.values()) {
            if (!(n instanceof PlanningLayer)) continue;
            PlanningLayer layer = (PlanningLayer) n;
            if (!effectiveVisible(layer)) continue;
            for (PlanningGhost g : layer.ghosts) {
                Coord2d wp = resolveWorldPos(g, glob);
                if (wp == null) continue;
                double d = wp.dist(worldPos);
                if (d < minDist) {
                    minDist = d;
                    best = g;
                }
            }
        }
        return best;
    }

    /**
     * Remove all ghosts in the active layer that fall within the world-pixel
     * rectangle defined by {@code minWorld} (inclusive) and {@code maxWorld} (exclusive).
     */
    public int removeInArea(Coord2d minWorld, Coord2d maxWorld) {
        PlanningLayer layer = getActiveLayer();
        if (layer == null) return 0;
        Glob glob = activeGlob();
        if (glob == null) return 0;
        int n = 0;
        Iterator<PlanningGhost> it = layer.ghosts.iterator();
        while (it.hasNext()) {
            PlanningGhost g = it.next();
            Coord2d wp = resolveWorldPos(g, glob);
            if (wp == null) continue;
            if (wp.x >= minWorld.x && wp.x < maxWorld.x && wp.y >= minWorld.y && wp.y < maxWorld.y) {
                it.remove();
                destroyMaterialized(g.id);
                n++;
            }
        }
        if (n > 0) markDirty();
        return n;
    }

    /* ---------- Reconciliation ---------- */

    /** Called every NCore tick. Materializes ghosts of effective-visible layers, destroys others. */
    public void tick() {
        Glob glob = activeGlob();
        if (glob == null) {
            // Without a glob we have nothing to render into; leave state alone.
            return;
        }
        Set<Long> shouldBeVisible = new HashSet<>();
        for (PlanningNode n : byId.values()) {
            if (!(n instanceof PlanningLayer)) continue;
            PlanningLayer layer = (PlanningLayer) n;
            if (!effectiveVisible(layer)) continue;
            for (PlanningGhost g : layer.ghosts) {
                shouldBeVisible.add(g.id);
                if (!materialized.containsKey(g.id)) {
                    materializeOne(g, glob);
                }
            }
        }
        Iterator<Map.Entry<Long, Gob>> it = materialized.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Gob> e = it.next();
            if (!shouldBeVisible.contains(e.getKey())) {
                try { glob.oc.remove(e.getValue()); } catch (Exception ignore) {}
                it.remove();
            }
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
            ghost.move(wp, g.angleRadians());
            glob.oc.add(ghost);
            if (ghost.ngob != null) ghost.ngob.hitBox = null;
            materialized.put(g.id, ghost);
        } catch (Exception ex) {
            // Resource not loadable now; the entry stays on disk for next time.
        }
    }

    private void destroyMaterialized(long ghostId) {
        Gob g = materialized.remove(ghostId);
        if (g == null) return;
        Glob glob = activeGlob();
        if (glob != null) {
            try { glob.oc.remove(g); } catch (Exception ignore) {}
        }
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

    private Coord2d resolveWorldPos(PlanningGhost g, Glob glob) {
        MCache.Grid grid = glob.map.findGrid(g.gridId);
        if (grid == null) return null;
        Coord2d ul = grid.ul.mul(MCache.tilesz);
        return ul.add(g.ox, g.oy);
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

    /* ---------- Import / Export ---------- */

    /**
     * Export the entire tree (or a single node + descendants) to a target path.
     * Returns true on success.
     */
    public boolean exportToFile(String path, String optionalNodeId) {
        List<PlanningNode> subset;
        if (optionalNodeId == null) {
            subset = roots;
        } else {
            PlanningNode n = byId.get(optionalNodeId);
            if (n == null) return false;
            subset = Collections.singletonList(n);
        }
        try {
            JSONObject main = buildJson(subset);
            NFileUtils.writeAtomically(path, main.toString());
            return true;
        } catch (IOException e) {
            System.err.println("[PlanningLayerManager] export failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Import a tree file and append it to the current tree at the root level.
     * Imported nodes get fresh UUIDs to avoid id collisions; name collisions
     * with existing nodes are resolved by suffixing " (1)", " (2)", etc.
     */
    public int importFromFile(String path) {
        String content = NFileUtils.readWithBackupFallback(path);
        if (content == null || content.isEmpty()) return 0;
        try {
            JSONObject main = new JSONObject(content);
            JSONArray tree = main.optJSONArray("tree");
            if (tree == null) return 0;
            int added = 0;
            for (int i = 0; i < tree.length(); i++) {
                PlanningNode parsed = parseNode(tree.getJSONObject(i), null);
                if (parsed == null) continue;
                PlanningNode fresh = cloneWithFreshIds(parsed, null);
                fresh.name = uniqueName(fresh.name);
                roots.add(fresh);
                index(fresh);
                added++;
            }
            if (added > 0) markDirty();
            return added;
        } catch (JSONException e) {
            System.err.println("[PlanningLayerManager] import failed: " + e.getMessage());
            return 0;
        }
    }

    /** Deep-clone a subtree, allocating new UUIDs at every node and new ghost ids. */
    private PlanningNode cloneWithFreshIds(PlanningNode src, String parentId) {
        if (src instanceof PlanningFolder) {
            PlanningFolder srcF = (PlanningFolder) src;
            PlanningFolder fresh = new PlanningFolder(UUID.randomUUID().toString(), srcF.name, srcF.visible, parentId);
            for (PlanningLayer layer : srcF.layers) {
                PlanningLayer copy = (PlanningLayer) cloneWithFreshIds(layer, fresh.id);
                fresh.layers.add(copy);
            }
            return fresh;
        } else {
            PlanningLayer srcL = (PlanningLayer) src;
            PlanningLayer fresh = new PlanningLayer(UUID.randomUUID().toString(), srcL.name, srcL.visible, parentId);
            for (PlanningGhost g : srcL.ghosts) {
                fresh.ghosts.add(new PlanningGhost(
                        ghostIdGen.getAndIncrement(),
                        g.resName, g.sdt, g.gridId, g.ox, g.oy, g.angleRadians()));
            }
            return fresh;
        }
    }

    private String uniqueName(String base) {
        if (base == null) base = "Imported";
        Set<String> taken = new HashSet<>();
        for (PlanningNode n : byId.values()) taken.add(n.name);
        if (!taken.contains(base)) return base;
        for (int i = 1; i < 1000; i++) {
            String cand = base + " (" + i + ")";
            if (!taken.contains(cand)) return cand;
        }
        return base + " (" + UUID.randomUUID().toString().substring(0, 4) + ")";
    }
}
