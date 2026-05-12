package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.Build;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitCheckable;
import nurgling.tools.NParser;
import org.json.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Editor bot: hooks into the map to place individual ghost buildings at pixel-precise
 * world positions, save/load relative layouts as blueprints, and dispatch a Build pass
 * over the current ghost set when the user clicks Build All.
 */
public class WorldBlueprintEditor implements Action {

    private static final String BLUEPRINTS_FILE = "world_blueprints.json";

    private enum Mode { PLACING, LOAD_ANCHOR }

    private Mode mode = Mode.PLACING;
    private int rotation = 0;
    private Gob placingPreview = null;
    private String placingPreviewType = null;
    private int placingPreviewRot = -1;
    private final List<LoadPreview> loadPreviews = new ArrayList<>();
    private final List<BlueprintItem> pendingLoadItems = new ArrayList<>();
    private MixedGhostStore store;
    private nurgling.widgets.bots.WorldBlueprintEditor widget;
    private EditorGrabber grabber;
    private MapView mapView;

    private static class LoadPreview {
        Gob gob;
        String type;
        int baseRot;
        double dx;
        double dy;
    }

    private static class BlueprintItem {
        String type;
        String kind;
        double dx;
        double dy;
        int rot;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        mapView = gui.map;
        Gob player = NUtils.player();
        if (player == null) return Results.ERROR("Player not found");

        // Set up store
        MixedGhostStore existing = player.getattr(MixedGhostStore.class);
        if (existing != null) {
            existing.dispose();
            player.delattr(MixedGhostStore.class);
        }
        store = new MixedGhostStore(player);
        player.setattr(store);

        widget = new nurgling.widgets.bots.WorldBlueprintEditor();
        NUtils.getGameUI().add(widget, UI.scale(new Coord(40, 80)));

        grabber = new EditorGrabber();
        mapView.grab(grabber);

        try {
            while (true) {
                widget.resetCommand();
                NUtils.getUI().core.addTask(new EditorPollingTask());

                nurgling.widgets.bots.WorldBlueprintEditor.Command cmd = widget.getCommand();
                switch (cmd) {
                    case SELECT_TYPE:
                        rotation = 0;
                        widget.setRotation(rotation);
                        // Selection updates the preview on next tick
                        break;
                    case SAVE:
                        handleSave(widget.getSaveName());
                        widget.refreshBlueprintNames();
                        break;
                    case LOAD:
                        handleLoad(widget.getLoadTarget());
                        break;
                    case CLEAR:
                        store.clear();
                        break;
                    case BUILD:
                        return handleBuild(gui);
                    case EXIT:
                    case NONE:
                    default:
                        return Results.SUCCESS();
                }
            }
        } finally {
            cleanup(player);
        }
    }

    private void cleanup(Gob player) {
        try {
            if (mapView != null && grabber != null) mapView.release(grabber);
        } catch (Exception e) { /* ignore */ }
        disposePlacingPreview();
        disposeLoadPreviews();
        if (player != null) {
            MixedGhostStore s = player.getattr(MixedGhostStore.class);
            if (s != null) {
                s.dispose();
                player.delattr(MixedGhostStore.class);
            }
        }
        try { if (widget != null) widget.destroy(); } catch (Exception e) { /* ignore */ }
    }

    private void disposePlacingPreview() {
        if (placingPreview != null) {
            try { mapView.glob.oc.remove(placingPreview); } catch (Exception e) { /* ignore */ }
            placingPreview = null;
            placingPreviewType = null;
            placingPreviewRot = -1;
        }
    }

    private void disposeLoadPreviews() {
        for (LoadPreview p : loadPreviews) {
            try { mapView.glob.oc.remove(p.gob); } catch (Exception e) { /* ignore */ }
        }
        loadPreviews.clear();
        pendingLoadItems.clear();
    }

    // ----- Save -----
    private void handleSave(String name) {
        if (name == null || name.isEmpty() || store.getEntries().isEmpty()) {
            NUtils.getUI().msg("Nothing to save");
            return;
        }
        // Compute bbox-top-left anchor in world coords
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        for (MixedGhostStore.Entry e : store.getEntries()) {
            minX = Math.min(minX, e.pos.x);
            minY = Math.min(minY, e.pos.y);
        }
        try {
            JSONObject root = readOrInit();
            JSONArray bps = root.getJSONArray("blueprints");
            int existingIdx = -1;
            for (int i = 0; i < bps.length(); i++) {
                if (name.equals(bps.getJSONObject(i).getString("name"))) {
                    existingIdx = i;
                    break;
                }
            }
            JSONObject bp = new JSONObject();
            bp.put("name", name);
            JSONArray items = new JSONArray();
            for (MixedGhostStore.Entry e : store.getEntries()) {
                JSONObject it = new JSONObject();
                it.put("kind", e.kind);
                it.put("type", e.type);
                it.put("dx", e.pos.x - minX);
                it.put("dy", e.pos.y - minY);
                it.put("rot", e.rotation);
                items.put(it);
            }
            bp.put("items", items);
            if (existingIdx >= 0) bps.put(existingIdx, bp); else bps.put(bp);
            root.put("current", name);
            nurgling.tools.NFileUtils.writeAtomically(new File(BLUEPRINTS_FILE).getAbsolutePath(), root.toString(2));
            NUtils.getUI().msg("Saved blueprint: " + name + " (" + store.getEntries().size() + " items)");
        } catch (Exception ex) {
            NUtils.getUI().msg("Save failed: " + ex.getMessage());
        }
    }

    private JSONObject readOrInit() throws Exception {
        File f = new File(BLUEPRINTS_FILE);
        if (f.exists()) {
            String content = new String(Files.readAllBytes(f.toPath()));
            JSONObject root = new JSONObject(content);
            if (!root.has("blueprints")) root.put("blueprints", new JSONArray());
            return root;
        }
        JSONObject root = new JSONObject();
        root.put("current", "");
        root.put("blueprints", new JSONArray());
        return root;
    }

    // ----- Load -----
    private void handleLoad(String name) {
        List<BlueprintItem> items = readBlueprint(name);
        if (items.isEmpty()) {
            NUtils.getUI().msg("Blueprint empty or not found: " + name);
            return;
        }
        disposeLoadPreviews();
        pendingLoadItems.clear();
        pendingLoadItems.addAll(items);
        rotation = 0;
        widget.setRotation(rotation);
        mode = Mode.LOAD_ANCHOR;
        NUtils.getUI().msg("Anchor the blueprint with left-click. Wheel to rotate. Right-click to cancel.");
    }

    private void materializeLoadPreviews(Coord2d anchorWorld) {
        for (BlueprintItem it : pendingLoadItems) {
            BuildCatalog.BuildingDef def = BuildCatalog.get(it.type);
            if (def == null) continue;
            try {
                Coord2d off = rotateOffset(it.dx, it.dy, rotation);
                Coord2d wpos = anchorWorld.add(off.x, off.y);
                int finalRot = (it.rot + rotation) & 3;
                Gob g = new Gob(mapView.glob, wpos);
                g.setattr(new GhostAlpha(g));
                Indir<Resource> res = Resource.remote().load(def.resName);
                g.setattr(new ResDrawable(g, res, Message.nil));
                g.a = finalRot * Math.PI / 2.0;
                mapView.glob.oc.add(g);
                LoadPreview lp = new LoadPreview();
                lp.gob = g;
                lp.type = it.type;
                lp.baseRot = it.rot;
                lp.dx = it.dx;
                lp.dy = it.dy;
                loadPreviews.add(lp);
            } catch (Exception ex) {
                // skip
            }
        }
        pendingLoadItems.clear();
    }

    private List<BlueprintItem> readBlueprint(String name) {
        List<BlueprintItem> out = new ArrayList<>();
        try {
            File f = new File(BLUEPRINTS_FILE);
            if (!f.exists()) return out;
            String content = new String(Files.readAllBytes(f.toPath()));
            JSONObject root = new JSONObject(content);
            JSONArray bps = root.getJSONArray("blueprints");
            for (int i = 0; i < bps.length(); i++) {
                JSONObject bp = bps.getJSONObject(i);
                if (!name.equals(bp.getString("name"))) continue;
                JSONArray items = bp.optJSONArray("items");
                if (items == null) break;
                for (int j = 0; j < items.length(); j++) {
                    JSONObject it = items.getJSONObject(j);
                    BlueprintItem b = new BlueprintItem();
                    b.kind = it.optString("kind", "building");
                    b.type = it.getString("type");
                    b.dx = it.getDouble("dx");
                    b.dy = it.getDouble("dy");
                    b.rot = it.optInt("rot", 0);
                    out.add(b);
                }
                break;
            }
        } catch (Exception ex) {
            // ignore
        }
        return out;
    }

    private Coord2d rotateOffset(double dx, double dy, int rot) {
        switch (rot & 3) {
            case 0: return new Coord2d(dx, dy);
            case 1: return new Coord2d(-dy, dx);
            case 2: return new Coord2d(-dx, -dy);
            case 3: return new Coord2d(dy, -dx);
            default: return new Coord2d(dx, dy);
        }
    }

    private void commitLoadAtAnchor(Coord2d anchor) {
        // Try to place each item as a real store entry. Skip any that collide. If the
        // user clicks before previews have materialized, iterate the pending list.
        int placed = 0, skipped = 0;
        if (!loadPreviews.isEmpty()) {
            for (LoadPreview p : loadPreviews) {
                Coord2d off = rotateOffset(p.dx, p.dy, rotation);
                Coord2d worldPos = anchor.add(off.x, off.y);
                int finalRot = (p.baseRot + rotation) & 3;
                MixedGhostStore.Entry e = store.tryPlaceBuilding(p.type, worldPos, finalRot);
                if (e != null) placed++; else skipped++;
            }
        } else {
            for (BlueprintItem it : pendingLoadItems) {
                Coord2d off = rotateOffset(it.dx, it.dy, rotation);
                Coord2d worldPos = anchor.add(off.x, off.y);
                int finalRot = (it.rot + rotation) & 3;
                MixedGhostStore.Entry e = store.tryPlaceBuilding(it.type, worldPos, finalRot);
                if (e != null) placed++; else skipped++;
            }
        }
        disposeLoadPreviews();
        mode = Mode.PLACING;
        if (skipped > 0) NUtils.getUI().msg("Placed " + placed + " ghost(s), skipped " + skipped + " due to collisions");
        else NUtils.getUI().msg("Placed " + placed + " ghost(s)");
    }

    // ----- Build -----
    private Results handleBuild(NGameUI gui) throws InterruptedException {
        List<MixedGhostStore.Entry> entries = new ArrayList<>(store.getEntries());
        if (entries.isEmpty()) {
            NUtils.getUI().msg("No ghosts to build");
            return Results.SUCCESS();
        }
        NContext context = new NContext(gui);

        // Build a per-grid space using the grid IDs captured at placement time. This is
        // robust on multi-floor structures (GH upstairs) where re-resolving chunks from
        // world coords after the bot moves between floors can grab the wrong grid.
        NArea buildArea = new NArea("world_blueprint_build");
        buildArea.space = new NArea.Space();
        Map<Long, int[]> perGrid = new LinkedHashMap<>();  // gridId -> {minX,minY,maxX,maxY} in grid-local tile coords
        for (MixedGhostStore.Entry e : entries) {
            if (e.gridId == 0L) continue;
            MCache.Grid g = gui.map.glob.map.findGrid(e.gridId);
            if (g == null) continue;
            Coord absTile = e.pos.floor(MCache.tilesz);
            Coord localTile = absTile.sub(g.ul);
            int[] box = perGrid.get(e.gridId);
            if (box == null) {
                perGrid.put(e.gridId, new int[]{localTile.x, localTile.y, localTile.x, localTile.y});
            } else {
                box[0] = Math.min(box[0], localTile.x);
                box[1] = Math.min(box[1], localTile.y);
                box[2] = Math.max(box[2], localTile.x);
                box[3] = Math.max(box[3], localTile.y);
            }
        }
        if (perGrid.isEmpty()) {
            NUtils.getUI().msg("Ghosts have no recorded grid IDs; can't build");
            return Results.ERROR("No grid ids on ghosts");
        }
        for (Map.Entry<Long, int[]> g : perGrid.entrySet()) {
            int[] b = g.getValue();
            // pad by 2 tiles for navigation, clip to grid bounds 0..100
            Coord ul = new Coord(Math.max(0, b[0] - 2), Math.max(0, b[1] - 2));
            Coord br = new Coord(Math.min(MCache.cmaps.x, b[2] + 2 + 1), Math.min(MCache.cmaps.y, b[3] + 2 + 1));
            buildArea.space.space.put(g.getKey(), new NArea.VArea(new Area(ul, br)));
        }
        buildArea.markDirty(nurgling.areas.AreaFieldGroup.GEOMETRY);
        buildArea.grids_id.clear();
        buildArea.grids_id.addAll(buildArea.space.space.keySet());

        // Group by (type, rotation)
        Map<String, List<MixedGhostStore.Entry>> groups = new LinkedHashMap<>();
        for (MixedGhostStore.Entry e : entries) {
            String key = e.type + "#" + e.rotation;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        try {
            for (Map.Entry<String, List<MixedGhostStore.Entry>> g : groups.entrySet()) {
                String key = g.getKey();
                String type = key.substring(0, key.indexOf('#'));
                int rot = Integer.parseInt(key.substring(key.indexOf('#') + 1));
                List<MixedGhostStore.Entry> groupEntries = g.getValue();
                ArrayList<Coord2d> positions = new ArrayList<>();
                for (MixedGhostStore.Entry e : groupEntries) positions.add(e.pos);

                Build.Command command = BuildCatalog.commandFor(type, context, gui);
                if (command == null) {
                    NUtils.getUI().msg("Unknown building type: " + type);
                    continue;
                }
                Results r = new Build(context, command, buildArea, rot, positions, null).run(gui);
                // Remove ghosts where a real building exists now
                for (MixedGhostStore.Entry e : groupEntries) {
                    Gob there = nurgling.tools.Finder.findGob(e.pos);
                    if (there != null && !NParser.checkName(there.ngob.name, "gfx/terobjs/consobj")
                            && there.getattr(GhostAlpha.class) == null) {
                        store.remove(e);
                    }
                }
                if (!r.IsSuccess()) return r;
            }
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception ex) {
            return Results.ERROR("Build failed: " + ex.getMessage());
        }
        return Results.SUCCESS();
    }

    private static BuildCatalog.BuildingDef defFor(String type) { return BuildCatalog.get(type); }

    // ----- Polling task: cursor preview + widget readiness -----
    private class EditorPollingTask extends NTask {
        @Override
        public boolean check() {
            try {
                updatePreviewFromCursor();
                store.suppressHitboxes();
                if (placingPreview != null && placingPreview.ngob != null) {
                    placingPreview.ngob.hitBox = null;
                }
                for (LoadPreview p : loadPreviews) {
                    if (p.gob != null && p.gob.ngob != null) {
                        p.gob.ngob.hitBox = null;
                    }
                }
            } catch (Exception e) {
                // ignore preview errors per frame
            }
            if (widget != null) widget.setGhostCount(store.getEntries().size());
            return widget != null && widget.check();
        }
    }

    private volatile Coord2d cursorWorld = null;

    private void updatePreviewFromCursor() {
        NMapView nm = (NMapView) mapView;
        if (nm.ui == null || nm.ui.mc == null) return;
        Coord mc = nm.ui.mc;
        if (!mc.isect(nm.rootpos(), nm.sz)) return;
        Coord pc = mc.sub(nm.rootpos());

        // Submit a Maptest; the callback fires on the render thread and does the
        // actual preview update there. Cache the world coord for the grabber.
        nm.new Maptest(pc) {
            public void hit(Coord hitPc, Coord2d worldCoord) {
                Coord2d snapped = snap(worldCoord);
                cursorWorld = snapped;
                applyPreviewAtWorld(snapped);
            }
        }.run();
    }

    /** Mirror MapView.StdPlace snapping: tile center, or sub-tile if shift held. */
    private Coord2d snap(Coord2d mc) {
        if (mc == null) return null;
        boolean shift = NUtils.getUI() != null && NUtils.getUI().modshift;
        if (!shift) {
            return mc.floor(MCache.tilesz).mul(MCache.tilesz).add(MCache.tilesz.div(2));
        }
        double gran = MapView.plobpgran;
        if (gran > 0) {
            return mc.div(MCache.tilesz).mul(gran).roundf().div(gran).mul(MCache.tilesz);
        }
        return mc;
    }

    private void applyPreviewAtWorld(Coord2d world) {
        if (world == null) return;
        if (mode == Mode.PLACING) {
            String type = widget.getSelectedType();
            if (type == null) {
                disposePlacingPreview();
                return;
            }
            BuildCatalog.BuildingDef def = BuildCatalog.get(type);
            if (def == null) {
                disposePlacingPreview();
                return;
            }
            if (placingPreview == null || !type.equals(placingPreviewType) || rotation != placingPreviewRot) {
                disposePlacingPreview();
                try {
                    placingPreview = new Gob(mapView.glob, world);
                    placingPreview.setattr(new GhostAlpha(placingPreview));
                    Indir<Resource> res = Resource.remote().load(def.resName);
                    placingPreview.setattr(new ResDrawable(placingPreview, res, Message.nil));
                    placingPreview.a = rotation * Math.PI / 2.0;
                    mapView.glob.oc.add(placingPreview);
                    placingPreviewType = type;
                    placingPreviewRot = rotation;
                } catch (Exception ex) {
                    placingPreview = null;
                }
            } else {
                placingPreview.move(world, rotation * Math.PI / 2.0);
            }
        } else if (mode == Mode.LOAD_ANCHOR) {
            // Lazily create the preview gobs the first time the cursor lands on the map,
            // so they're inserted at a valid in-view position rather than at origin.
            if (loadPreviews.isEmpty() && !pendingLoadItems.isEmpty()) {
                materializeLoadPreviews(world);
                return;
            }
            for (LoadPreview p : loadPreviews) {
                Coord2d off = rotateOffset(p.dx, p.dy, rotation);
                Coord2d wpos = world.add(off.x, off.y);
                int finalRot = (p.baseRot + rotation) & 3;
                p.gob.move(wpos, finalRot * Math.PI / 2.0);
            }
        }
    }

    /** Last known cursor world position (updated async by Maptest callback). */
    private Coord2d cursorWorldPos() {
        return cursorWorld;
    }

    // ----- Grabber: routes map mouse events -----
    private class EditorGrabber implements MapView.Grabber {
        @Override
        public boolean mmousedown(Coord mc, int button) {
            Coord2d world = cursorWorldPos();
            if (world == null) return false;

            if (mode == Mode.LOAD_ANCHOR) {
                if (button == 1) {
                    commitLoadAtAnchor(world);
                    return true;
                } else if (button == 3) {
                    disposeLoadPreviews();
                    mode = Mode.PLACING;
                    NUtils.getUI().msg("Load cancelled");
                    return true;
                }
                return false;
            }

            // PLACING mode
            if (button == 1) {
                String type = widget.getSelectedType();
                if (type != null) {
                    MixedGhostStore.Entry e = store.tryPlaceBuilding(type, world, rotation);
                    if (e == null) NUtils.getUI().msg("Can't place here (collision)");
                    return true;
                }
                return false;
            }
            if (button == 3) {
                boolean shift = NUtils.getUI().modshift;
                // First: target any ghost under cursor
                MixedGhostStore.Entry hit = store.findContaining(world);
                if (hit == null) {
                    hit = store.findNear(world, MCache.tilesz.x * 1.5);
                }
                if (hit != null) {
                    if (shift) {
                        store.remove(hit);
                    } else {
                        if (!store.rotate(hit)) NUtils.getUI().msg("Can't rotate (collision)");
                    }
                    return true;
                }
                // No ghost: if a type is selected, treat as deselect
                if (widget.getSelectedType() != null) {
                    widget.setSelectedType(null);
                    disposePlacingPreview();
                    return true;
                }
                return false;
            }
            return false;
        }

        @Override
        public boolean mmouseup(Coord mc, int button) { return false; }

        @Override
        public boolean mmousewheel(Coord mc, int amount) {
            rotation = (rotation + amount) & 3;
            widget.setRotation(rotation);
            return true;
        }

        @Override
        public void mmousemove(Coord mc) {
            // Position updates handled in EditorPollingTask
        }
    }
}
