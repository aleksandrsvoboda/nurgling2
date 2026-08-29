package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerAction;
import nurgling.routes.ForagerPath;
import nurgling.widgets.ForagerPickupContainer;
import nurgling.widgets.TextInputWindow;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * "Forager Settings" panel under Settings &gt; Bots. Owns editing of Forager's Actions Profiles
 * (named, independently-saved pickup-action lists - see {@link ForagerPickupContainer}, which
 * resolves each dropped/typed item's gob pattern and best-guesses its flower-menu action rather
 * than requiring one picked from a fixed list); the Forager bot-launch window only *selects* one
 * of these to run with, it no longer edits them. Routes and Guarding profiles are planned to join
 * this same panel in later phases of the same refactor.
 */
public class ForagerSettingsPanel extends Panel {

    private static final String ROUTES_DIR = "forager_paths";

    // Short, one-line-each instructions for the map editor below, in display order - replaces a
    // single dense paragraph per direct feedback.
    private static final String[] ROUTES_HELP_KEYS = {
            "forager.settings.routes_help_add",
            "forager.settings.routes_help_move",
            "forager.settings.routes_help_delete",
            "forager.settings.routes_help_paint",
            "forager.settings.routes_help_erase",
            "forager.settings.routes_help_pan_zoom",
            "forager.settings.routes_help_cliffs",
    };

    private NForagerProp prop;
    private final Dropbox<String> actionsProfileDropbox;
    private final ForagerPickupContainer pickupContainer;

    private ForagerPath currentRoute;
    private final List<String> routeNames = new ArrayList<>();
    private final Dropbox<String> routeDropbox;
    // Set around load()'s own programmatic routeDropbox.change() call so that reload (including
    // the Cancel button, which calls load() directly) discards pending route edits instead of
    // auto-saving them - see routeDropbox's change() override.
    private boolean suppressRouteAutoSave = false;

    // Built lazily on first load() (see ensureRouteMapBuilt()), not in the constructor - every
    // Panel in this settings window is constructed eagerly for the whole NSettingsWindow, itself
    // built inside GameUI's own constructor, well before NUtils.getGameUI()/gui.mmap exist yet.
    // Constructing ForagerRouteMap (which needs gui.mmap.file) here would NPE on every login.
    private ForagerRouteMap routeMap;
    private TextEntry brushSizeEntry;
    private CheckBox avoidCliffsCheck;
    private TextEntry maxChainsEntry;
    private TextEntry maxDistanceEntry;
    private TextEntry maxChainDistanceEntry;

    private Scrollport scroll;
    private CollapsibleSection routesSection;
    private Widget routesContent;
    private Widget mapAnchor;

    // Every top-level CollapsibleSection, in display order - collapsing/expanding one only
    // toggles its own content's visibility (CollapsibleSection.content.visible) and shrinks/grows
    // that section's own wrapper; nothing below automatically moves up/down to close the gap or
    // avoid overlapping. relayoutSections() below does that explicitly, and must run after any
    // section's toggle (not just a scroll-range refresh) or after any section's content changes
    // height (e.g. ensureRouteMapBuilt() adding the map widget after construction).
    private final List<CollapsibleSection> sections = new ArrayList<>();

    public ForagerSettingsPanel() {
        super(L10n.get("nsettings.item.forager"));

        // This panel's content (help text + profile row + pickup grid + its button row) already
        // runs past the panel's own 580x580 budget, and later phases (Routes/Guarding) are meant
        // to keep adding to it in the same place - a scrollable viewport rather than a fixed
        // layout, so it degrades to "scroll" instead of "off the bottom of the window" as it grows.
        scroll = add(new Scrollport(UI.scale(new Coord(560, 530))), UI.scale(10, 40));
        Widget cont = scroll.cont;

        // Each of this panel's logically-separate groups (Actions here; Routes/Guarding in later
        // phases) gets its own collapsible section, so the page stays navigable once all three
        // exist rather than always showing everything at once.
        CollapsibleSection actionsSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.actions_section"), UI.scale(540), true), Coord.z);
        actionsSection.setOnToggle(this::relayoutSections);
        sections.add(actionsSection);
        Widget sec = actionsSection.content;

        Widget prev = sec.add(new Label(L10n.get("forager.settings.actions_help"), UI.scale(400)), Coord.z);

        prev = sec.add(new Label(L10n.get("forager.settings.actions_profile")), prev.pos("bl").add(UI.scale(0, 12)));

        Widget profileRow = sec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), prev.pos("bl").add(UI.scale(0, 5)));
        profileRow.add(actionsProfileDropbox = new Dropbox<String>(UI.scale(200), 8, UI.scale(16)) {
            private List<String> names() {
                return prop != null ? new ArrayList<>(new TreeSet<>(prop.actionsProfiles.keySet())) : Collections.emptyList();
            }

            @Override
            protected String listitem(int i) {
                return names().get(i);
            }

            @Override
            protected int listitems() {
                return names().size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null && prop != null) {
                    prop.currentActionsProfile = item;
                    pickupContainer.load(prop.actionsProfiles.get(item));
                }
            }
        }, new Coord(0, 0));

        profileRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                addProfile();
            }
        }, new Coord(UI.scale(210), 0)).settip(L10n.get("forager.settings.new_profile_tip"));

        profileRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/remove/u"),
                Resource.loadsimg("nurgling/hud/buttons/remove/d"),
                Resource.loadsimg("nurgling/hud/buttons/remove/h")) {
            @Override
            public void click() {
                super.click();
                deleteProfile();
            }
        }, new Coord(UI.scale(240), 0)).settip(L10n.get("forager.settings.delete_profile_tip"));

        profileRow.add(new IButton(
                NStyle.importb[0].back, NStyle.importb[1].back, NStyle.importb[2].back) {
            @Override
            public void click() {
                super.click();
                importProfile();
            }
        }, new Coord(UI.scale(275), 0)).settip(L10n.get("forager.settings.import_profile_tip"));

        profileRow.add(new IButton(
                NStyle.exportb[0].back, NStyle.exportb[1].back, NStyle.exportb[2].back) {
            @Override
            public void click() {
                super.click();
                exportProfile();
            }
        }, new Coord(UI.scale(305), 0)).settip(L10n.get("forager.settings.export_profile_tip"));

        pickupContainer = new ForagerPickupContainer();
        pickupContainer.resize(UI.scale(new Coord(400, 320)));

        Widget pickupButtonsRow = sec.add(new Widget(new Coord(UI.scale(300), UI.scale(24))), profileRow.pos("bl").add(UI.scale(0, 10)));
        pickupButtonsRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                pickupContainer.promptAddCustom();
            }
        }, new Coord(0, 0)).settip(L10n.get("forager.pickup.add_custom"));

        pickupButtonsRow.add(new IButton(
                NStyle.catmenu[0].back, NStyle.catmenu[1].back, NStyle.catmenu[2].back) {
            @Override
            public void click() {
                super.click();
                pickupContainer.openCatalogue();
            }
        }, new Coord(UI.scale(30), 0)).settip(L10n.get("forager.pickup.catalogue"));

        sec.add(pickupContainer, pickupButtonsRow.pos("bl").add(UI.scale(0, 5)));

        actionsSection.pack();

        // ---- Routes ----
        routesSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.routes_section"), UI.scale(540), true), actionsSection.pos("bl").add(UI.scale(0, 10)));
        routesSection.setOnToggle(this::relayoutSections);
        sections.add(routesSection);
        Widget rsec = routesContent = routesSection.content;

        Widget rprev = null;
        for (String key : ROUTES_HELP_KEYS) {
            rprev = rsec.add(new Label("• " + L10n.get(key)), rprev == null ? Coord.z : rprev.pos("bl").add(UI.scale(0, 3)));
        }

        rprev = rsec.add(new Label(L10n.get("forager.settings.route")), rprev.pos("bl").add(UI.scale(0, 12)));

        Widget routeRow = rsec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), rprev.pos("bl").add(UI.scale(0, 5)));
        routeRow.add(routeDropbox = new Dropbox<String>(UI.scale(200), 8, UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return routeNames.get(i);
            }

            @Override
            protected int listitems() {
                return routeNames.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null) {
                    // Switching which route is being edited must not silently drop in-progress
                    // edits (waypoints/exclusion brush/caps) to the one being switched away from -
                    // only the panel's Save button persists "whatever currentRoute currently is",
                    // and loadRoute() below replaces that reference with a fresh disk-load.
                    // Suppressed during load()'s own programmatic selection (Cancel calls load(),
                    // which must discard rather than save, matching every other panel's Cancel
                    // semantics) and skipped entirely when nothing's actually changing.
                    if (!suppressRouteAutoSave && currentRoute != null && !currentRoute.name.equals(item)) {
                        saveCurrentRoute();
                    }
                    loadRoute(item);
                }
            }
        }, new Coord(0, 0));

        routeRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                addRoute();
            }
        }, new Coord(UI.scale(210), 0)).settip(L10n.get("forager.settings.new_route_tip"));

        routeRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/remove/u"),
                Resource.loadsimg("nurgling/hud/buttons/remove/d"),
                Resource.loadsimg("nurgling/hud/buttons/remove/h")) {
            @Override
            public void click() {
                super.click();
                deleteRoute();
            }
        }, new Coord(UI.scale(240), 0)).settip(L10n.get("forager.settings.delete_route_tip"));

        Widget brushRow = rsec.add(new Widget(new Coord(UI.scale(340), UI.scale(20))), routeRow.pos("bl").add(UI.scale(0, 10)));
        brushRow.add(new Label(L10n.get("forager.settings.brush_size")), new Coord(0, UI.scale(4)));
        brushSizeEntry = brushRow.add(new TextEntry(UI.scale(50), String.valueOf(ForagerRouteMap.DEFAULT_BRUSH_SIZE_TILES)) {
            @Override
            public void done(ReadLine buf) {
                super.done(buf);
                applyBrushSize();
            }
        }, new Coord(UI.scale(270), 0));

        Widget cliffRow = rsec.add(new Widget(new Coord(UI.scale(300), UI.scale(20))), brushRow.pos("bl").add(UI.scale(0, 8)));
        avoidCliffsCheck = cliffRow.add(new CheckBox(L10n.get("forager.settings.avoid_cliffs")) {
            @Override
            public void set(boolean val) {
                a = val;
                if (currentRoute != null) {
                    currentRoute.avoidCliffs = val;
                    routeMap.markDirty();
                }
            }
        }, Coord.z);
        avoidCliffsCheck.settip(L10n.get("forager.settings.avoid_cliffs_tip"));

        mapAnchor = cliffRow;

        // ForagerRouteMap itself (needs gui.mmap.file, not available yet here) is built lazily -
        // see ensureRouteMapBuilt(), called from load().
        routesSection.pack();

        relayoutSections();
    }

    /** Repositions every top-level section directly below the current bottom edge of the one
     *  before it, so collapsing/expanding a section (or a section's content changing height,
     *  e.g. ensureRouteMapBuilt() adding the map widget after construction) actually moves
     *  everything below it up or down to close/open the gap - CollapsibleSection.pack() alone
     *  only resizes that one section's own wrapper, it has no idea what else is on the page. */
    private void relayoutSections() {
        Coord next = Coord.z;
        for (CollapsibleSection s : sections) {
            s.move(next);
            next = s.pos("bl").add(UI.scale(0, 10));
        }
        scroll.cont.update();
    }

    /** Builds the embedded route-editing map (and the caps row positioned relative to it) the
     *  first time this panel is actually opened, not in the constructor - see the field javadoc
     *  on {@link #routeMap} for why. Idempotent; safe to call on every load(). */
    private void ensureRouteMapBuilt() {
        if (routeMap != null) return;

        routeMap = routesContent.add(new ForagerRouteMap(UI.scale(new Coord(520, 360)), NUtils.getGameUI().mmap.file), mapAnchor.pos("bl").add(UI.scale(0, 10)));
        routeMap.onResetRequested = this::resetCurrentRoute;
        applyBrushSize();

        Widget capsRow = routesContent.add(new Widget(new Coord(UI.scale(520), UI.scale(24))), routeMap.pos("bl").add(UI.scale(0, 10)));
        capsRow.add(new Label(L10n.get("forager.settings.max_chains")), new Coord(0, UI.scale(4)));
        maxChainsEntry = capsRow.add(new TextEntry(UI.scale(50), ""), new Coord(UI.scale(80), 0));
        capsRow.add(new Label(L10n.get("forager.settings.max_distance")), new Coord(UI.scale(160), UI.scale(4)));
        maxDistanceEntry = capsRow.add(new TextEntry(UI.scale(50), ""), new Coord(UI.scale(280), 0));
        capsRow.add(new Label(L10n.get("forager.settings.max_chain_distance")), new Coord(UI.scale(360), UI.scale(4)));
        maxChainDistanceEntry = capsRow.add(new TextEntry(UI.scale(50), ""), new Coord(UI.scale(500), 0));

        routesSection.pack();
        relayoutSections();
    }

    // MouseWheelEvent dispatch tries each widget on the way DOWN to whatever's under the cursor,
    // not the deepest one first - so the outer Scrollport (an ancestor of routeMap, several
    // levels up) always got first refusal and unconditionally consumed every wheel event for
    // page-scrolling, before routeMap (which wants it for zoom) ever saw it. This panel is
    // itself an ancestor of that Scrollport, so intercepting here - forwarding straight to
    // routeMap when the cursor is actually over it - runs before the Scrollport gets a turn.
    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        if (routeMap != null) {
            Coord rel = ev.c.sub(routeMap.parentpos(this));
            if (rel.isect(Coord.z, routeMap.sz)) {
                return routeMap.mousewheel(ev.derive(rel));
            }
        }
        return super.mousewheel(ev);
    }

    @Override
    public void load() {
        ensureRouteMapBuilt();

        prop = NForagerProp.get(NUtils.getUI().sessInfo);
        if (prop == null) return;

        if (prop.actionsProfiles.isEmpty()) {
            prop.actionsProfiles.put("Default", new ArrayList<>());
        }
        if (prop.currentActionsProfile == null || !prop.actionsProfiles.containsKey(prop.currentActionsProfile)) {
            prop.currentActionsProfile = prop.actionsProfiles.keySet().iterator().next();
        }

        actionsProfileDropbox.change(prop.currentActionsProfile);
        pickupContainer.load(prop.actionsProfiles.get(prop.currentActionsProfile));

        loadAvailableRoutes();
        suppressRouteAutoSave = true;
        try {
            if (!routeNames.isEmpty()) {
                routeDropbox.change(routeNames.get(0));
            } else {
                clearRouteUI();
            }
        } finally {
            suppressRouteAutoSave = false;
        }
    }

    @Override
    public void save() {
        if (prop != null) {
            NForagerProp.set(prop);
        }
        saveCurrentRoute();
    }

    private void loadAvailableRoutes() {
        routeNames.clear();
        File dir = NUtils.getDataFilePath(ROUTES_DIR).toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    routeNames.add(f.getName().replace(".json", ""));
                }
            }
        }
        Collections.sort(routeNames);
    }

    private void loadRoute(String name) {
        ForagerPath loaded;
        try {
            loaded = ForagerPath.load(NUtils.getDataFile(ROUTES_DIR, name + ".json"));
        } catch (Exception e) {
            loaded = new ForagerPath(name);
        }
        currentRoute = loaded;
        routeMap.setRoute(currentRoute);
        avoidCliffsCheck.a = currentRoute.avoidCliffs;
        maxChainsEntry.settext(currentRoute.maxChains < 0 ? "" : String.valueOf(currentRoute.maxChains));
        maxDistanceEntry.settext(currentRoute.maxDistance < 0 ? "" : String.valueOf(currentRoute.maxDistance));
        maxChainDistanceEntry.settext(currentRoute.maxChainDistance < 0 ? "" : String.valueOf(currentRoute.maxChainDistance));
    }

    /** Discards any unsaved in-memory edits (waypoints, exclusion paint, caps) to the currently
     *  selected route by reloading it fresh from disk. A scoped "Cancel" for just the route being
     *  edited - the panel-wide Cancel button reloads the whole panel and resets route selection
     *  back to the alphabetically-first route rather than the one actually being worked on. */
    private void resetCurrentRoute() {
        if (routeDropbox.sel == null) return;
        loadRoute(routeDropbox.sel);
    }

    private void clearRouteUI() {
        currentRoute = null;
        routeMap.setRoute(null);
        avoidCliffsCheck.a = false;
        maxChainsEntry.settext("");
        maxDistanceEntry.settext("");
        maxChainDistanceEntry.settext("");
    }

    private void saveCurrentRoute() {
        if (currentRoute == null) return;
        currentRoute.maxChains = parseIntOrNoCap(maxChainsEntry.text());
        currentRoute.maxDistance = parseIntOrNoCap(maxDistanceEntry.text());
        currentRoute.maxChainDistance = parseIntOrNoCap(maxChainDistanceEntry.text());
        try {
            currentRoute.save(NUtils.getDataFile(ROUTES_DIR));
            routeMap.markClean();
        } catch (Exception e) {
            NUtils.getGameUI().error("Failed to save route: " + e.getMessage());
        }
    }

    /** Blank or unparseable text -&gt; -1 (no cap), same sentinel convention as
     *  NForagerProp.PresetData.startAreaId - matches the AutoLogoutSettings/StarvationAlertSettings
     *  nsettings convention of a blank-initial-text TextEntry rather than a stringified "0"/"-1". */
    private int parseIntOrNoCap(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            return Math.max(v, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Pushes the brush-size field's value into routeMap - a pure editing-tool preference (not
     *  route data, unlike max chains/distance below), so not persisted anywhere; falls back to
     *  the default on blank/unparseable input rather than -1/no-cap (a brush needs a real size). */
    private void applyBrushSize() {
        if (routeMap == null) return;
        int v;
        try {
            v = Integer.parseInt(brushSizeEntry.text().trim());
        } catch (Exception e) {
            v = ForagerRouteMap.DEFAULT_BRUSH_SIZE_TILES;
        }
        routeMap.setBrushSizeTiles(v);
    }

    private void addRoute() {
        TextInputWindow win = new TextInputWindow(
                L10n.get("forager.settings.new_route_title"), L10n.get("forager.settings.new_route_prompt"), name -> {
            if (name != null && !name.trim().isEmpty()) {
                String trimmed = name.trim();
                ForagerPath route = new ForagerPath(trimmed);
                try {
                    route.save(NUtils.getDataFile(ROUTES_DIR));
                } catch (Exception e) {
                    NUtils.getGameUI().error("Failed to create route: " + e.getMessage());
                    return;
                }
                if (!routeNames.contains(trimmed)) {
                    routeNames.add(trimmed);
                    Collections.sort(routeNames);
                }
                routeDropbox.change(trimmed);
            }
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    private void deleteRoute() {
        if (routeDropbox.sel == null) return;
        try {
            Files.deleteIfExists(NUtils.getDataFilePath(ROUTES_DIR, routeDropbox.sel + ".json"));
        } catch (Exception e) {
            NUtils.getGameUI().error("Failed to delete route: " + e.getMessage());
        }
        routeNames.remove(routeDropbox.sel);
        // Null out before switching so the dropbox's auto-save-outgoing-route logic doesn't
        // resurrect the file we just deleted.
        currentRoute = null;
        if (routeNames.isEmpty()) {
            clearRouteUI();
        } else {
            routeDropbox.change(routeNames.get(0));
        }
    }

    private void addProfile() {
        if (prop == null) return;
        TextInputWindow win = new TextInputWindow(
                L10n.get("forager.settings.new_profile_title"), L10n.get("forager.settings.new_profile_prompt"), name -> {
            if (name != null && !name.trim().isEmpty()) {
                String trimmed = name.trim();
                prop.actionsProfiles.putIfAbsent(trimmed, new ArrayList<>());
                prop.currentActionsProfile = trimmed;
                actionsProfileDropbox.change(trimmed);
            }
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    private void deleteProfile() {
        if (prop == null || actionsProfileDropbox.sel == null) return;
        if (prop.actionsProfiles.size() <= 1) {
            // Always keep at least one profile to select at bot start.
            return;
        }
        prop.actionsProfiles.remove(actionsProfileDropbox.sel);
        String next = prop.actionsProfiles.keySet().iterator().next();
        prop.currentActionsProfile = next;
        actionsProfileDropbox.change(next);
    }

    /** Saves the currently selected profile to a JSON file, same {"name", data...} shape /
     *  JFileChooser pattern as e.g. BlueprintWidget's save/load - much lighter than Area
     *  Settings' export (which also carries grid/sync data this profile type has none of). */
    private void exportProfile() {
        if (prop == null || actionsProfileDropbox.sel == null) return;
        String name = actionsProfileDropbox.sel;
        ArrayList<ForagerAction> actions = prop.actionsProfiles.get(name);
        if (actions == null) return;

        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Forager actions profile", "json"));
            fc.setSelectedFile(new File(name + ".json"));
            if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;

            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".json")) {
                file = new File(file.getAbsolutePath() + ".json");
            }
            try {
                JSONObject root = new JSONObject();
                root.put("name", name);
                JSONArray arr = new JSONArray();
                for (ForagerAction action : actions) {
                    arr.put(action.toJson());
                }
                root.put("actions", arr);
                Files.write(file.toPath(), root.toString(2).getBytes());
                NUtils.getGameUI().msg(L10n.get("forager.settings.export_success"));
            } catch (Exception e) {
                NUtils.getGameUI().error("Failed to export actions profile: " + e.getMessage());
            }
        });
    }

    /** Loads a profile saved by {@link #exportProfile()} as a new profile (never overwrites an
     *  existing one - appends " (2)", " (3)", ... on a name collision) and selects it. */
    private void importProfile() {
        if (prop == null) return;

        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Forager actions profile", "json"));
            if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;

            File file = fc.getSelectedFile();
            if (file == null) return;
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                JSONObject root = new JSONObject(content);
                JSONArray arr = root.getJSONArray("actions");
                ArrayList<ForagerAction> actions = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    actions.add(new ForagerAction(arr.getJSONObject(i)));
                }

                String baseName = root.has("name") ? root.getString("name") : file.getName().replaceFirst("\\.json$", "");
                String name = baseName;
                int suffix = 2;
                while (prop.actionsProfiles.containsKey(name)) {
                    name = baseName + " (" + suffix + ")";
                    suffix++;
                }

                prop.actionsProfiles.put(name, actions);
                prop.currentActionsProfile = name;
                actionsProfileDropbox.change(name);
            } catch (Exception e) {
                NUtils.getGameUI().error("Failed to import actions profile: " + e.getMessage());
            }
        });
    }
}
