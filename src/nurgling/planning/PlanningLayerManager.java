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
import nurgling.NCore;
import nurgling.NUtils;
import nurgling.db.DatabaseManager;
import nurgling.db.service.PlanningService;
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

/**
 * Owns the persistent planning tree for the current profile.
 *
 * Two storage backends:
 * <ul>
 *   <li><b>File mode</b> (ndbenable=false): JSON v2 file at
 *       {@code planning_layer.nurgling.json}, debounce-saved.</li>
 *   <li><b>DB mode</b> (ndbenable=true): rows in {@code planning_folders},
 *       {@code planning_layers}, {@code planning_ghosts} via
 *       {@link PlanningService}. The file is left alone and ignored.</li>
 * </ul>
 *
 * Visibility is governed by per-node eye toggles (independent of window state).
 * The {@code windowOpen} flag only gates user-driven interactions (capture,
 * delete, clone) that originate in the Base Planner window.
 */
public class PlanningLayerManager implements ProfileAwareService, PlanningService.PlanningSyncCallback {

    public static final int FILE_VERSION = 2;

    private final List<PlanningNode> roots = new ArrayList<>();
    private final Map<String, PlanningNode> byId = new HashMap<>();
    private final Map<String, PlanningGhost> ghostById = new HashMap<>();
    private final Map<String, String> layerByGhostId = new HashMap<>();
    private final Map<String, Gob> materialized = new HashMap<>();
    /** Per-user, per-profile visibility preferences. Always loaded/saved locally. */
    private final Map<String, Boolean> localVisibility = new HashMap<>();
    private String activeLayerId;
    private String genus;
    private String configPath;     // tree file (file mode only)
    private String viewPath;       // local view file (always)
    private boolean dirty = false;
    private long lastChangeTime = 0;
    private boolean windowOpen = false;
    public static final long DEBOUNCE_MS = 3000;

    /** Coarse lock guarding the tree against concurrent sync-worker mutations. */
    private final Object treeLock = new Object();

    public PlanningLayerManager() {
        NConfig cfg = NConfig.getGlobalInstance();
        this.configPath = (cfg != null) ? cfg.getPlanningLayerPath() : null;
        this.viewPath = (cfg != null) ? cfg.getPlanningViewPath() : null;
        loadViewFile();
        if (this.configPath != null) {
            load();
        }
    }

    @Override
    public void initializeForProfile(String genus) {
        this.genus = genus;
        NConfig cfg = ConfigFactory.getConfig(genus);
        this.configPath = cfg.getPlanningLayerPath();
        this.viewPath = cfg.getPlanningViewPath();
        synchronized (treeLock) {
            destroyAllMaterialized();
            roots.clear();
            byId.clear();
            ghostById.clear();
            layerByGhostId.clear();
            localVisibility.clear();
            activeLayerId = null;
        }
        loadViewFile();
        if (!isDbMode()) {
            load();
        }
        // In DB mode, the sync scheduler bulk-loads on first tick.
    }

    @Override
    public String getGenus() { return genus; }

    /* ---------- DB mode helper ---------- */

    /** True iff the user has enabled the database AND the manager is up. */
    private boolean isDbMode() {
        try {
            Object v = NConfig.get(NConfig.Key.ndbenable);
            if (!(v instanceof Boolean) || !(Boolean) v) return false;
            return NCore.databaseManager != null && NCore.databaseManager.isReady()
                && NCore.databaseManager.getPlanningService() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private PlanningService dbService() {
        try {
            return NCore.databaseManager.getPlanningService();
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- File persistence (file mode only) ---------- */

    @Override
    public void load() {
        synchronized (treeLock) {
            roots.clear();
            byId.clear();
            ghostById.clear();
            layerByGhostId.clear();
            activeLayerId = null;
        }
        if (configPath == null) return;
        if (isDbMode()) return; // file ignored in DB mode
        String content = NFileUtils.readWithBackupFallback(configPath);
        if (content == null || content.isEmpty()) return;
        try {
            JSONObject main = new JSONObject(content);
            int version = main.optInt("version", 0);
            if (version != FILE_VERSION) {
                System.err.println("[PlanningLayerManager] ignoring incompatible v" + version + " file at " + configPath);
                return;
            }
            JSONArray tree = main.optJSONArray("tree");
            synchronized (treeLock) {
                if (tree != null) {
                    for (int i = 0; i < tree.length(); i++) {
                        PlanningNode node = parseNode(tree.getJSONObject(i), null);
                        if (node != null) {
                            roots.add(node);
                            index(node);
                        }
                    }
                }
                // Overlay per-user visibility from the view file (overrides
                // whatever was inline in the tree file).
                applyLocalVisibility();
                String aid = main.optString("activeLayerId", null);
                if (aid != null && byId.get(aid) instanceof PlanningLayer) {
                    activeLayerId = aid;
                } else if (activeLayerId == null) {
                    activeLayerId = firstLayerId();
                }
            }
        } catch (JSONException e) {
            System.err.println("[PlanningLayerManager] failed to parse " + configPath + ": " + e.getMessage());
        }
    }

    /** Read planning_view.nurgling.json into {@code localVisibility} + {@code activeLayerId}. */
    private void loadViewFile() {
        if (viewPath == null) return;
        String content = NFileUtils.readWithBackupFallback(viewPath);
        if (content == null || content.isEmpty()) return;
        try {
            JSONObject main = new JSONObject(content);
            String aid = main.optString("activeLayerId", null);
            JSONObject vis = main.optJSONObject("visibility");
            synchronized (treeLock) {
                if (vis != null) {
                    for (String key : vis.keySet()) {
                        localVisibility.put(key, vis.optBoolean(key, true));
                    }
                }
                if (aid != null && !aid.isEmpty()) activeLayerId = aid;
            }
        } catch (JSONException e) {
            System.err.println("[PlanningLayerManager] failed to parse view file " + viewPath + ": " + e.getMessage());
        }
    }

    private void saveViewFile() {
        if (viewPath == null) return;
        JSONObject main = new JSONObject();
        JSONObject vis = new JSONObject();
        synchronized (treeLock) {
            // Persist the current in-memory visibility of every known node.
            for (PlanningNode n : byId.values()) {
                vis.put(n.id, n.visible);
                localVisibility.put(n.id, n.visible);
            }
            // Plus any entries we know about but aren't in the tree (preserve
            // across temporary absences during sync).
            for (Map.Entry<String, Boolean> e : localVisibility.entrySet()) {
                if (!vis.has(e.getKey())) vis.put(e.getKey(), e.getValue());
            }
            if (activeLayerId != null) main.put("activeLayerId", activeLayerId);
        }
        main.put("visibility", vis);
        try {
            NFileUtils.writeAtomically(viewPath, main.toString());
        } catch (IOException e) {
            System.err.println("[PlanningLayerManager] view save failed: " + e.getMessage());
        }
    }

    /** Overlay {@code localVisibility} onto current in-memory nodes. */
    private void applyLocalVisibility() {
        for (PlanningNode n : byId.values()) {
            Boolean v = localVisibility.get(n.id);
            if (v != null) n.visible = v;
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
            return layer;
        }
        return null;
    }

    /** Index a tree fragment into byId/ghostById/layerByGhostId. */
    private void index(PlanningNode node) {
        byId.put(node.id, node);
        if (node instanceof PlanningFolder) {
            for (PlanningLayer layer : ((PlanningFolder) node).layers) {
                layer.parentId = node.id;
                byId.put(layer.id, layer);
                for (PlanningGhost g : layer.ghosts) {
                    ghostById.put(g.id, g);
                    layerByGhostId.put(g.id, layer.id);
                }
            }
        } else if (node instanceof PlanningLayer) {
            PlanningLayer layer = (PlanningLayer) node;
            for (PlanningGhost g : layer.ghosts) {
                ghostById.put(g.id, g);
                layerByGhostId.put(g.id, layer.id);
            }
        }
    }

    @Override
    public void save() {
        // The view file is always written (visibility + active layer are
        // per-user local state regardless of mode).
        saveViewFile();
        dirty = false;
        lastChangeTime = 0;
        if (isDbMode()) return; // DB is authoritative for the tree
        if (configPath == null) return;
        JSONObject main;
        synchronized (treeLock) {
            main = buildJson(roots);
            if (activeLayerId != null) main.put("activeLayerId", activeLayerId);
        }
        try {
            NFileUtils.writeAtomically(configPath, main.toString());
        } catch (IOException e) {
            System.err.println("[PlanningLayerManager] save failed, will retry: " + e.getMessage());
            dirty = true;
            lastChangeTime = System.currentTimeMillis();
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
        // Always set in both modes — at minimum the view file needs writing.
        dirty = true;
        lastChangeTime = System.currentTimeMillis();
    }

    /* ---------- Tree accessors ---------- */

    public List<PlanningNode> getRoots() {
        synchronized (treeLock) {
            return new ArrayList<>(roots);
        }
    }

    public PlanningNode getNode(String id) {
        synchronized (treeLock) { return byId.get(id); }
    }

    public PlanningLayer getLayer(String id) {
        synchronized (treeLock) {
            PlanningNode n = byId.get(id);
            return (n instanceof PlanningLayer) ? (PlanningLayer) n : null;
        }
    }

    public PlanningFolder getFolder(String id) {
        synchronized (treeLock) {
            PlanningNode n = byId.get(id);
            return (n instanceof PlanningFolder) ? (PlanningFolder) n : null;
        }
    }

    public boolean hasGhostById(String id) {
        synchronized (treeLock) { return ghostById.containsKey(id); }
    }

    public String getActiveLayerId() { return activeLayerId; }

    public PlanningLayer getActiveLayer() { return getLayer(activeLayerId); }

    public void setActiveLayer(String layerId) {
        synchronized (treeLock) {
            if (layerId == null || byId.get(layerId) instanceof PlanningLayer) {
                this.activeLayerId = layerId;
                markDirty();
            }
        }
    }

    /* ---------- Tree mutations ---------- */

    public PlanningFolder createFolder(String name) {
        PlanningFolder f = new PlanningFolder(UUID.randomUUID().toString(), name, true, null);
        synchronized (treeLock) {
            roots.add(f);
            byId.put(f.id, f);
            f.orderIndex = roots.size() - 1;
            localVisibility.put(f.id, true);
            // Initial state is "new" — we mark dirty groups so the OCC INSERT actually runs.
            f.markDirty(PlanningFieldGroup.IDENTITY);
            f.markDirty(PlanningFieldGroup.STRUCTURE);
            markDirty();
        }
        if (isDbMode()) dbService().saveFolderAsync(f, profileOrGlobal());
        return f;
    }

    public PlanningLayer createLayer(String name, String parentFolderId) {
        PlanningLayer layer = new PlanningLayer(UUID.randomUUID().toString(), name, true, null);
        synchronized (treeLock) {
            PlanningFolder parent = (parentFolderId != null) ? getFolderUnlocked(parentFolderId) : null;
            if (parent != null) {
                layer.parentId = parent.id;
                parent.layers.add(layer);
                layer.orderIndex = parent.layers.size() - 1;
            } else {
                roots.add(layer);
                layer.orderIndex = roots.size() - 1;
            }
            byId.put(layer.id, layer);
            localVisibility.put(layer.id, true);
            if (activeLayerId == null) activeLayerId = layer.id;
            layer.markDirty(PlanningFieldGroup.IDENTITY);
            layer.markDirty(PlanningFieldGroup.STRUCTURE);
            markDirty();
        }
        if (isDbMode()) dbService().saveLayerAsync(layer, profileOrGlobal());
        return layer;
    }

    public boolean deleteNode(String id) {
        PlanningNode removed = null;
        List<String> deletedLayerIds = new ArrayList<>();
        List<String> deletedGhostIds = new ArrayList<>();
        synchronized (treeLock) {
            PlanningNode n = byId.get(id);
            if (n == null) return false;
            if (n instanceof PlanningFolder) {
                PlanningFolder f = (PlanningFolder) n;
                for (PlanningLayer layer : f.layers) {
                    for (PlanningGhost g : layer.ghosts) {
                        destroyMaterialized(g.id);
                        ghostById.remove(g.id);
                        layerByGhostId.remove(g.id);
                        deletedGhostIds.add(g.id);
                    }
                    byId.remove(layer.id);
                    if (layer.id.equals(activeLayerId)) activeLayerId = null;
                    deletedLayerIds.add(layer.id);
                }
                f.layers.clear();
                roots.remove(f);
                byId.remove(f.id);
                removed = f;
            } else if (n instanceof PlanningLayer) {
                PlanningLayer layer = (PlanningLayer) n;
                for (PlanningGhost g : layer.ghosts) {
                    destroyMaterialized(g.id);
                    ghostById.remove(g.id);
                    layerByGhostId.remove(g.id);
                    deletedGhostIds.add(g.id);
                }
                if (layer.parentId == null) {
                    roots.remove(layer);
                } else {
                    PlanningFolder parent = getFolderUnlocked(layer.parentId);
                    if (parent != null) parent.layers.remove(layer);
                }
                byId.remove(layer.id);
                if (layer.id.equals(activeLayerId)) activeLayerId = firstLayerId();
                deletedLayerIds.add(layer.id);
                removed = layer;
            }
            markDirty();
        }
        if (isDbMode() && removed != null) {
            String profile = profileOrGlobal();
            PlanningService svc = dbService();
            for (String gid : deletedGhostIds) svc.deleteGhostAsync(gid, profile);
            for (String lid : deletedLayerIds) svc.deleteLayerAsync(lid, profile);
            if (removed instanceof PlanningFolder) svc.deleteFolderAsync(removed.id, profile);
        }
        return removed != null;
    }

    public void setVisible(String id, boolean visible) {
        synchronized (treeLock) {
            PlanningNode n = byId.get(id);
            if (n == null) return;
            n.visible = visible;
            localVisibility.put(id, visible);
            markDirty();
        }
        // Intentionally NO DB push — visibility is a per-user preference.
    }

    public void renameNode(String id, String name) {
        if (name == null || name.isEmpty()) return;
        PlanningNode n;
        synchronized (treeLock) {
            n = byId.get(id);
            if (n == null) return;
            n.name = name;
            n.markDirty(PlanningFieldGroup.IDENTITY);
            markDirty();
        }
        pushNodeAsync(n);
    }

    public boolean anyVisible() {
        synchronized (treeLock) {
            for (PlanningNode n : byId.values()) {
                if (n instanceof PlanningLayer && effectiveVisibleUnlocked(n)) return true;
            }
            return false;
        }
    }

    public void masterToggleVisibility() {
        synchronized (treeLock) {
            boolean any = false;
            for (PlanningNode n : byId.values()) {
                if (n instanceof PlanningLayer && effectiveVisibleUnlocked(n)) { any = true; break; }
            }
            boolean newState = !any;
            for (PlanningNode n : byId.values()) {
                if (n.visible != newState) {
                    n.visible = newState;
                    localVisibility.put(n.id, newState);
                }
            }
            markDirty();
        }
        // Local-only — no DB push.
    }

    public boolean effectiveVisible(PlanningNode n) {
        synchronized (treeLock) { return effectiveVisibleUnlocked(n); }
    }

    private boolean effectiveVisibleUnlocked(PlanningNode n) {
        if (n == null) return false;
        if (!n.visible) return false;
        if (n.parentId == null) return true;
        return effectiveVisibleUnlocked(byId.get(n.parentId));
    }

    private PlanningFolder getFolderUnlocked(String id) {
        PlanningNode n = byId.get(id);
        return (n instanceof PlanningFolder) ? (PlanningFolder) n : null;
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

    public String suggestNextLayerName() {
        synchronized (treeLock) {
            int max = -1;
            for (PlanningNode n : byId.values()) {
                if (!(n instanceof PlanningLayer)) continue;
                String name = n.name;
                if (name != null && name.startsWith("Layer ")) {
                    try {
                        int v = Integer.parseInt(name.substring(6).trim());
                        if (v > max) max = v;
                    } catch (NumberFormatException ignore) {}
                }
            }
            return String.format("Layer %02d", max + 1);
        }
    }

    /* ---------- Window-open gating ---------- */

    public void setWindowOpen(boolean open) { this.windowOpen = open; }
    public boolean isWindowOpen() { return windowOpen; }

    /* ---------- Ghost ops ---------- */

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
                PlanningGhost.newId(), resName, sdt, grid.id, off.x, off.y, angleRadians);
        PlanningLayer layer;
        synchronized (treeLock) {
            layer = getActiveLayer();
            if (layer == null) return null;
            layer.ghosts.add(g);
            ghostById.put(g.id, g);
            layerByGhostId.put(g.id, layer.id);
            markDirty();
            if (effectiveVisibleUnlocked(layer)) materializeOne(g, glob);
        }
        if (isDbMode()) dbService().saveGhostAsync(g, layer.id, profileOrGlobal());
        return g;
    }

    public boolean removeGhostAt(Coord2d worldPos, double tolerance) {
        PlanningGhost target = null;
        Glob glob = activeGlob();
        if (glob == null) return false;
        synchronized (treeLock) {
            double minDist = tolerance;
            for (PlanningNode n : byId.values()) {
                if (!(n instanceof PlanningLayer)) continue;
                PlanningLayer layer = (PlanningLayer) n;
                if (!effectiveVisibleUnlocked(layer)) continue;
                for (PlanningGhost g : layer.ghosts) {
                    Coord2d wp = resolveWorldPos(g, glob);
                    if (wp == null) continue;
                    double d = wp.dist(worldPos);
                    if (d < minDist) { minDist = d; target = g; }
                }
            }
        }
        if (target == null) return false;
        return removeGhost(target);
    }

    public boolean removeGhost(PlanningGhost g) {
        if (g == null) return false;
        String layerId;
        boolean removed = false;
        synchronized (treeLock) {
            layerId = layerByGhostId.remove(g.id);
            ghostById.remove(g.id);
            destroyMaterialized(g.id);
            if (layerId != null) {
                PlanningLayer layer = (PlanningLayer) byId.get(layerId);
                if (layer != null) removed = layer.ghosts.remove(g);
            }
            if (!removed) {
                // Fall back to a slow scan if the index lost it (shouldn't happen).
                for (PlanningNode n : byId.values()) {
                    if (n instanceof PlanningLayer && ((PlanningLayer) n).ghosts.remove(g)) { removed = true; break; }
                }
            }
            if (removed) markDirty();
        }
        if (removed && isDbMode()) {
            dbService().deleteGhostAsync(g.id, profileOrGlobal());
        }
        return removed;
    }

    public PlanningGhost getGhostAt(Coord2d worldPos, double tolerance) {
        Glob glob = activeGlob();
        if (glob == null) return null;
        synchronized (treeLock) {
            PlanningGhost best = null;
            double minDist = tolerance;
            for (PlanningNode n : byId.values()) {
                if (!(n instanceof PlanningLayer)) continue;
                PlanningLayer layer = (PlanningLayer) n;
                if (!effectiveVisibleUnlocked(layer)) continue;
                for (PlanningGhost g : layer.ghosts) {
                    Coord2d wp = resolveWorldPos(g, glob);
                    if (wp == null) continue;
                    double d = wp.dist(worldPos);
                    if (d < minDist) { minDist = d; best = g; }
                }
            }
            return best;
        }
    }

    public int removeInArea(Coord2d minWorld, Coord2d maxWorld) {
        PlanningLayer layer = getActiveLayer();
        if (layer == null) return 0;
        Glob glob = activeGlob();
        if (glob == null) return 0;
        List<String> removedIds = new ArrayList<>();
        synchronized (treeLock) {
            Iterator<PlanningGhost> it = layer.ghosts.iterator();
            while (it.hasNext()) {
                PlanningGhost g = it.next();
                Coord2d wp = resolveWorldPos(g, glob);
                if (wp == null) continue;
                if (wp.x >= minWorld.x && wp.x < maxWorld.x && wp.y >= minWorld.y && wp.y < maxWorld.y) {
                    it.remove();
                    destroyMaterialized(g.id);
                    ghostById.remove(g.id);
                    layerByGhostId.remove(g.id);
                    removedIds.add(g.id);
                }
            }
            if (!removedIds.isEmpty()) markDirty();
        }
        if (!removedIds.isEmpty() && isDbMode()) {
            PlanningService svc = dbService();
            String profile = profileOrGlobal();
            for (String id : removedIds) svc.deleteGhostAsync(id, profile);
        }
        return removedIds.size();
    }

    /* ---------- Reconciliation ---------- */

    public void tick() {
        Glob glob = activeGlob();
        if (glob == null) return;
        synchronized (treeLock) {
            Set<String> shouldBeVisible = new HashSet<>();
            for (PlanningNode n : byId.values()) {
                if (!(n instanceof PlanningLayer)) continue;
                PlanningLayer layer = (PlanningLayer) n;
                if (!effectiveVisibleUnlocked(layer)) continue;
                for (PlanningGhost g : layer.ghosts) {
                    shouldBeVisible.add(g.id);
                    if (!materialized.containsKey(g.id)) materializeOne(g, glob);
                }
            }
            Iterator<Map.Entry<String, Gob>> it = materialized.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Gob> e = it.next();
                if (!shouldBeVisible.contains(e.getKey())) {
                    try { glob.oc.remove(e.getValue()); } catch (Exception ignore) {}
                    it.remove();
                }
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
            // Resource not loadable now; the entry stays for next tick.
        }
    }

    private void destroyMaterialized(String ghostId) {
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
        Iterator<Map.Entry<String, Gob>> it = materialized.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Gob> e = it.next();
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

    private String profileOrGlobal() {
        try {
            if (NUtils.getGameUI() != null) {
                String g = NUtils.getGameUI().getGenus();
                if (g != null && !g.isEmpty()) return g;
            }
        } catch (Exception ignore) {}
        return (genus != null && !genus.isEmpty()) ? genus : "global";
    }

    private void pushNodeAsync(PlanningNode n) {
        if (!isDbMode() || n == null) return;
        String profile = profileOrGlobal();
        if (n instanceof PlanningFolder) {
            dbService().saveFolderAsync((PlanningFolder) n, profile);
        } else if (n instanceof PlanningLayer) {
            dbService().saveLayerAsync((PlanningLayer) n, profile);
        }
    }

    /* ---------- DB sync callback ---------- */

    @Override
    public void onFullSync(PlanningService.TreeSnapshot snap) {
        synchronized (treeLock) {
            destroyAllMaterialized();
            roots.clear();
            byId.clear();
            ghostById.clear();
            layerByGhostId.clear();
            for (PlanningFolder f : snap.folders) {
                roots.add(f);
                byId.put(f.id, f);
            }
            for (PlanningLayer l : snap.layers) {
                byId.put(l.id, l);
                if (l.parentId != null) {
                    PlanningNode parent = byId.get(l.parentId);
                    if (parent instanceof PlanningFolder) {
                        ((PlanningFolder) parent).layers.add(l);
                    } else {
                        l.parentId = null;
                        roots.add(l);
                    }
                } else {
                    roots.add(l);
                }
            }
            for (PlanningGhost g : snap.ghosts) {
                String layerId = snap.ghostLayerByGhostId.get(g.id);
                if (layerId == null) continue;
                PlanningNode parent = byId.get(layerId);
                if (parent instanceof PlanningLayer) {
                    ((PlanningLayer) parent).ghosts.add(g);
                    ghostById.put(g.id, g);
                    layerByGhostId.put(g.id, layerId);
                }
            }
            // Overlay this player's local visibility preferences (they're not
            // in the DB — service returned everything as visible=true).
            applyLocalVisibility();
            if (activeLayerId == null || !(byId.get(activeLayerId) instanceof PlanningLayer)) {
                activeLayerId = firstLayerId();
            }
            System.out.println("[Planning] onFullSync done: roots=" + roots.size()
                + " byId=" + byId.size() + " activeLayerId=" + activeLayerId
                + " managerId=" + System.identityHashCode(this));
        }
        pokePlannerWindow();
    }

    @Override
    public void onSyncDelta(PlanningService.SyncDelta delta) {
        synchronized (treeLock) {
            // Folders.
            for (PlanningFolder f : delta.upsertedFolders) {
                PlanningFolder existing = (PlanningFolder) byId.get(f.id);
                if (existing != null) {
                    // Preserve local visibility — never sync it.
                    existing.name = f.name;
                    existing.orderIndex = f.orderIndex;
                    existing.version = f.version;
                    existing.baselineVersion = f.baselineVersion;
                    existing.baselineSnapshot = f.baselineSnapshot;
                    existing.lastTouchedBy = f.lastTouchedBy;
                    existing.lastTouchedAt = f.lastTouchedAt;
                    existing.dirtyGroups.clear();
                } else {
                    Boolean v = localVisibility.get(f.id);
                    if (v != null) f.visible = v;
                    roots.add(f);
                    byId.put(f.id, f);
                }
            }
            for (PlanningLayer l : delta.upsertedLayers) {
                PlanningLayer existing = (PlanningLayer) byId.get(l.id);
                if (existing != null) {
                    if (!eqNullable(existing.parentId, l.parentId)) {
                        removeLayerFromCurrentParent(existing);
                        existing.parentId = l.parentId;
                        attachLayer(existing);
                    }
                    existing.name = l.name;
                    existing.orderIndex = l.orderIndex;
                    existing.version = l.version;
                    existing.baselineVersion = l.baselineVersion;
                    existing.baselineSnapshot = l.baselineSnapshot;
                    existing.lastTouchedBy = l.lastTouchedBy;
                    existing.lastTouchedAt = l.lastTouchedAt;
                    existing.dirtyGroups.clear();
                } else {
                    Boolean v = localVisibility.get(l.id);
                    if (v != null) l.visible = v;
                    byId.put(l.id, l);
                    attachLayer(l);
                }
            }
            for (PlanningGhost g : delta.upsertedGhosts) {
                String layerId = delta.ghostLayerByGhostId.get(g.id);
                if (layerId == null) continue;
                PlanningNode parent = byId.get(layerId);
                if (!(parent instanceof PlanningLayer)) continue;
                PlanningLayer layer = (PlanningLayer) parent;
                // Treat as add (existence already checked in computeDelta).
                layer.ghosts.add(g);
                ghostById.put(g.id, g);
                layerByGhostId.put(g.id, layerId);
            }
            // Apply deletes.
            for (String id : delta.deletedGhostIds) {
                String layerId = layerByGhostId.remove(id);
                PlanningGhost g = ghostById.remove(id);
                destroyMaterialized(id);
                if (layerId != null && g != null) {
                    PlanningLayer layer = (PlanningLayer) byId.get(layerId);
                    if (layer != null) layer.ghosts.remove(g);
                }
            }
            for (String id : delta.deletedLayerIds) {
                PlanningLayer layer = (PlanningLayer) byId.remove(id);
                if (layer == null) continue;
                for (PlanningGhost g : layer.ghosts) {
                    destroyMaterialized(g.id);
                    ghostById.remove(g.id);
                    layerByGhostId.remove(g.id);
                }
                layer.ghosts.clear();
                removeLayerFromCurrentParent(layer);
                if (id.equals(activeLayerId)) activeLayerId = firstLayerId();
            }
            for (String id : delta.deletedFolderIds) {
                PlanningFolder folder = (PlanningFolder) byId.remove(id);
                if (folder == null) continue;
                // Any layers underneath should already have been tombstoned by the originator;
                // if not, demote them to root.
                for (PlanningLayer layer : new ArrayList<>(folder.layers)) {
                    layer.parentId = null;
                    folder.layers.remove(layer);
                    roots.add(layer);
                }
                roots.remove(folder);
            }
        }
        pokePlannerWindow();
    }

    /** Best-effort UI refresh after sync mutates the tree. */
    private void pokePlannerWindow() {
        try {
            nurgling.NGameUI gui = NUtils.getGameUI();
            if (gui == null) {
                System.out.println("[Planning] pokePlannerWindow: gui is null");
                return;
            }
            if (gui.basePlanner == null) {
                System.out.println("[Planning] pokePlannerWindow: basePlanner is null");
                return;
            }
            gui.basePlanner.refresh();
            System.out.println("[Planning] pokePlannerWindow: refresh() called");
        } catch (Exception e) {
            System.err.println("[Planning] pokePlannerWindow failed: " + e);
        }
    }

    private void removeLayerFromCurrentParent(PlanningLayer layer) {
        if (layer.parentId == null) {
            roots.remove(layer);
        } else {
            PlanningFolder parent = (PlanningFolder) byId.get(layer.parentId);
            if (parent != null) parent.layers.remove(layer);
            else roots.remove(layer);
        }
    }

    private void attachLayer(PlanningLayer layer) {
        if (layer.parentId == null) {
            roots.add(layer);
        } else {
            PlanningFolder parent = (PlanningFolder) byId.get(layer.parentId);
            if (parent != null) parent.layers.add(layer);
            else { layer.parentId = null; roots.add(layer); }
        }
    }

    private static boolean eqNullable(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    /* ---------- Import / Export (file mode only; DB mode users round-trip via DB) ---------- */

    public boolean exportToFile(String path, String optionalNodeId) {
        List<PlanningNode> subset;
        synchronized (treeLock) {
            if (optionalNodeId == null) {
                subset = new ArrayList<>(roots);
            } else {
                PlanningNode n = byId.get(optionalNodeId);
                if (n == null) return false;
                subset = Collections.singletonList(n);
            }
            JSONObject main = buildJson(subset);
            try {
                NFileUtils.writeAtomically(path, main.toString());
                return true;
            } catch (IOException e) {
                System.err.println("[PlanningLayerManager] export failed: " + e.getMessage());
                return false;
            }
        }
    }

    public int importFromFile(String path) {
        String content = NFileUtils.readWithBackupFallback(path);
        if (content == null || content.isEmpty()) return 0;
        try {
            JSONObject main = new JSONObject(content);
            JSONArray tree = main.optJSONArray("tree");
            if (tree == null) return 0;
            List<PlanningNode> fresh = new ArrayList<>();
            synchronized (treeLock) {
                for (int i = 0; i < tree.length(); i++) {
                    PlanningNode parsed = parseNode(tree.getJSONObject(i), null);
                    if (parsed == null) continue;
                    PlanningNode cloned = cloneWithFreshIds(parsed, null);
                    cloned.name = uniqueName(cloned.name);
                    roots.add(cloned);
                    index(cloned);
                    // Force initial push in DB mode by marking all groups dirty.
                    if (cloned instanceof PlanningFolder || cloned instanceof PlanningLayer) {
                        cloned.markDirty(PlanningFieldGroup.IDENTITY);
                        cloned.markDirty(PlanningFieldGroup.STRUCTURE);
                    }
                    fresh.add(cloned);
                }
                if (!fresh.isEmpty()) markDirty();
            }
            if (isDbMode()) {
                PlanningService svc = dbService();
                String profile = profileOrGlobal();
                for (PlanningNode n : fresh) {
                    if (n instanceof PlanningFolder) {
                        PlanningFolder f = (PlanningFolder) n;
                        svc.saveFolderAsync(f, profile);
                        for (PlanningLayer layer : f.layers) {
                            layer.markDirty(PlanningFieldGroup.IDENTITY);
                            layer.markDirty(PlanningFieldGroup.STRUCTURE);
                            svc.saveLayerAsync(layer, profile);
                            for (PlanningGhost g : layer.ghosts) svc.saveGhostAsync(g, layer.id, profile);
                        }
                    } else if (n instanceof PlanningLayer) {
                        PlanningLayer layer = (PlanningLayer) n;
                        svc.saveLayerAsync(layer, profile);
                        for (PlanningGhost g : layer.ghosts) svc.saveGhostAsync(g, layer.id, profile);
                    }
                }
            }
            return fresh.size();
        } catch (JSONException e) {
            System.err.println("[PlanningLayerManager] import failed: " + e.getMessage());
            return 0;
        }
    }

    private PlanningNode cloneWithFreshIds(PlanningNode src, String parentId) {
        if (src instanceof PlanningFolder) {
            PlanningFolder srcF = (PlanningFolder) src;
            PlanningFolder fresh = new PlanningFolder(UUID.randomUUID().toString(), srcF.name, srcF.visible, parentId);
            fresh.orderIndex = srcF.orderIndex;
            for (PlanningLayer layer : srcF.layers) {
                PlanningLayer copy = (PlanningLayer) cloneWithFreshIds(layer, fresh.id);
                fresh.layers.add(copy);
            }
            return fresh;
        } else {
            PlanningLayer srcL = (PlanningLayer) src;
            PlanningLayer fresh = new PlanningLayer(UUID.randomUUID().toString(), srcL.name, srcL.visible, parentId);
            fresh.orderIndex = srcL.orderIndex;
            for (PlanningGhost g : srcL.ghosts) {
                fresh.ghosts.add(new PlanningGhost(
                        PlanningGhost.newId(),
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

    /* ---------- Mid-session ndbenable toggle (called by NCore) ---------- */

    /** Push the entire in-memory tree to DB. Used right after enabling DB mode. */
    public void exportTreeToDatabase() {
        if (!isDbMode()) return;
        try {
            List<PlanningNode> snapshot;
            Map<String, String> ghostLayer;
            synchronized (treeLock) {
                snapshot = new ArrayList<>(roots);
                ghostLayer = new HashMap<>(layerByGhostId);
                // Mark all nodes dirty so the OCC INSERT actually fires.
                for (PlanningNode n : byId.values()) {
                    n.baselineVersion = 0;
                    n.baselineSnapshot = null;
                    n.dirtyGroups.clear();
                    n.markDirty(PlanningFieldGroup.IDENTITY);
                    n.markDirty(PlanningFieldGroup.STRUCTURE);
                }
            }
            dbService().exportTreeToDatabase(snapshot, ghostLayer, profileOrGlobal());
        } catch (Exception e) {
            System.err.println("[PlanningLayerManager] initial DB export failed: " + e.getMessage());
        }
    }
}
