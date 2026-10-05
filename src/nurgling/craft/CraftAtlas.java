package nurgling.craft;

import haven.*;
import nurgling.NGameUI;
import nurgling.profiles.ConfigFactory;
import nurgling.tools.NFileUtils;
import nurgling.widgets.NMakewindow;
import org.json.JSONObject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Future;

/** Observes this character's menu and make-window; never probes recipes by sending actions. */
public final class CraftAtlas implements AtlasSource {
    public final AtlasCatalog catalog = new AtlasCatalog();
    private final NGameUI gui;
    private final Path file;
    private final Path flowFile;
    private final CraftCanvases canvases = new CraftCanvases();
    private volatile long savedFlowVersion;
    private volatile String flowError;
    private boolean flowLoadFailed;
    private final Map<String, MenuGrid.Pagina> available = new HashMap<>();
    private final Map<String, MenuGrid.Pagina> actionsByName = new HashMap<>();
    private final Map<String, String> nativeIds = new HashMap<>();
    private WikiRecipes wikiDatabase;
    private String databaseError;
    private int savedVersion, lastMenuSeq = -1;
    private MenuGrid lastMenu;
    private double scanIn, saveIn = 5, databaseCheckIn = 5;
    private Future<WikiRecipes> databaseCheck;
    private boolean retryMenu;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "craft-atlas-save"); thread.setDaemon(true); return thread;
    });
    public volatile String saveError;
    public int availabilityVersion;
    private boolean loadFailed;

    public AtlasCatalog catalog() { return catalog; }
    public int availabilityVersion() { return availabilityVersion; }
    public String saveError() { return databaseError != null ? databaseError : flowError != null ? flowError : saveError; }
    public CraftFlow flow() { return canvases.active().flow; }
    public CraftCanvases canvases() { return canvases; }
    public Double liveQuality(QualityModel.Port port) {
        if(port.kind == QualityModel.Kind.ATTRIBUTE || port.kind == QualityModel.Kind.SKILL) {
            if(gui.ui == null || gui.ui.sess == null) return null;
            String id = QualityModel.STATS.get(port.name);
            if(id == null) return null;
            int value = gui.ui.sess.glob.getcattr(id).comp;
            return value > 0 ? (double)value : null;
        }
        if(port.kind != QualityModel.Kind.TOOL) return null;
        // Read the current session's loaded inventory/equipment; never wait for bot inventory tasks.
        double best = -1;
        Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Widget> inventories = new ArrayDeque<>();
        if(gui.maininv != null) inventories.add(gui.maininv);
        Widget equipment = gui.equwnd == null ? null : gui.equwnd.getchild(Equipory.class);
        if(equipment != null) inventories.add(equipment);
        while(!inventories.isEmpty()) {
            Widget root = inventories.removeFirst(); if(!seen.add(root)) continue;
            for(Widget child : root.children()) if(child instanceof WItem && ((WItem)child).item instanceof nurgling.NGItem) {
                nurgling.NGItem item = (nurgling.NGItem)((WItem)child).item;
                if(item.contents != null) inventories.add(item.contents);
                if(QualityModel.toolMatches(port.name, item.name()) && item.quality != null)
                    best = Math.max(best, item.quality);
            }
        }
        return best >= 0 ? best : null;
    }

    public CraftAtlas(NGameUI gui) {
        this.gui = gui;
        try {
            wikiDatabase = WikiRecipes.bundled();
            wikiDatabase.recipes.forEach(catalog::put);
        } catch(java.io.IOException e) {
            databaseError = "Offline recipe database: " + e.getMessage();
            System.err.println("[CraftAtlas] " + databaseError);
        }
        String key = UUID.nameUUIDFromBytes(gui.chrid.getBytes(StandardCharsets.UTF_8)).toString();
        file = Path.of(ConfigFactory.getConfig(gui.getGenus()).getResourceTimersPath())
            .resolveSibling("craft-atlas-" + key + ".json");
        flowFile = file.resolveSibling("craft-flow-" + key + ".json");
        loadFlow(); savedFlowVersion = canvases.version();
        load();
        savedVersion = catalog.version;
    }
    private void loadFlow() {
        for(Path source : List.of(flowFile, flowFile.resolveSibling(flowFile.getFileName() + ".bak"))) {
            if(!Files.isRegularFile(source)) continue;
            try {
                if(Files.size(source) > 64 * 1024 * 1024) throw new java.io.IOException("Flow file too large");
                canvases.load(new JSONObject(Files.readString(source, StandardCharsets.UTF_8))); flowError = null; return;
            } catch(Exception e) { flowError = "Cannot load calculator: " + e.getMessage(); }
        }
        flowLoadFailed = flowError != null;
    }
    private void load() {
        for(Path source : List.of(file, file.resolveSibling(file.getFileName() + ".bak"))) {
            if(!Files.isRegularFile(source)) continue;
            try {
                catalog.mergeJournal(new JSONObject(Files.readString(source, StandardCharsets.UTF_8)));
                saveError = null; return;
            } catch(Exception e) {
                saveError = "Cannot read craft atlas: " + e.getMessage();
            }
        }
        loadFailed = saveError != null;
    }

    public void tick(double dt) {
        databaseCheckIn -= dt;
        if(databaseCheck != null && databaseCheck.isDone()) {
            try {
                WikiRecipes replacement = databaseCheck.get();
                if(replacement != wikiDatabase) {
                    catalog.replaceWiki(replacement.recipes);
                    wikiDatabase = replacement;
                    retryMenu = true;
                }
                databaseError = null;
            } catch(Exception e) {
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                databaseError = "Offline recipe database update: " + cause.getMessage();
            }
            databaseCheck = null;
        }
        if(databaseCheckIn <= 0 && databaseCheck == null) {
            databaseCheckIn = 5;
            databaseCheck = writer.submit(WikiRecipes::bundled);
        }
        scanIn -= dt; saveIn -= dt;
        if(scanIn <= 0) {
            scanIn = 1;
            if(gui.menu != lastMenu || (gui.menu != null && gui.menu.pagseq != lastMenuSeq) || retryMenu) scan();
        }
        if(saveIn <= 0) { flush(); saveIn = 5; }
    }

    private void scan() {
        lastMenu = gui.menu;
        lastMenuSeq = gui.menu == null ? -1 : gui.menu.pagseq;
        if(gui.menu == null) { available.clear(); actionsByName.clear(); nativeIds.clear(); availabilityVersion++; return; }
        Map<String, MenuGrid.Pagina> next = new HashMap<>();
        Map<String, MenuGrid.Pagina> nextNames = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        retryMenu = false;
        List<MenuGrid.Pagina> craftPages = new ArrayList<>();
        Map<String, Integer> nameCounts = new HashMap<>();
        for(MenuGrid.Pagina page : new ArrayList<>(gui.menu.paginae)) {
            try {
                Resource.AButton action = page.button().act();
                String kind = AtlasSource.actionKind(action);
                if(kind.isEmpty()) continue;
                craftPages.add(page);
                String label = WikiRecipes.normalize(page.button().name());
                if(nextNames.putIfAbsent(label, page) != null) ambiguousNames.add(label);
                nameCounts.merge(kind + ":" + WikiRecipes.normalize(page.button().name()), 1, Integer::sum);
            } catch(Loading e) { retryMenu = true; }
        }
        nativeIds.clear();
        for(MenuGrid.Pagina page : craftPages) {
            try {
                String nativeId = page.res().name;
                String kind = AtlasSource.actionKind(page.button().act());
                AtlasCatalog.Recipe wiki = nameCounts.getOrDefault(kind + ":" + WikiRecipes.normalize(page.button().name()), 0) == 1
                    ? catalog.findWikiAction(page.button().name(), kind) : null;
                if(wiki != null) wiki = catalog.migrate(nativeId, wiki);
                if(wiki != null) nurgling.tools.ItemIcons.registerFallback(wiki.name, nativeId);
                String id = wiki == null ? nativeId : wiki.resource;
                nativeIds.put(nativeId, id);
                next.put(id, page);
                String group = "";
                MenuGrid.Pagina parent = page.parent();
                if(parent != null) group = parent.button().name();
                AtlasCatalog.Recipe old = catalog.get(id);
                if(old == null) catalog.put(new AtlasCatalog.Recipe(id, page.button().name(), group));
                else if(old.wiki == null) catalog.put(new AtlasCatalog.Recipe(id, page.button().name(), group,
                    old.inputs, old.outputs, old.quality, old.tools, old.recorded));
            } catch(Loading e) { retryMenu = true; }
        }
        ambiguousNames.forEach(nextNames::remove);
        if(!available.equals(next) || !actionsByName.equals(nextNames)) {
            available.clear(); available.putAll(next);
            actionsByName.clear(); actionsByName.putAll(nextNames);
            availabilityVersion++;
        }
    }

    public MenuGrid.Pagina page(String resource) {
        MenuGrid.Pagina page = available.get(resource);
        if(page == null) {
            AtlasCatalog.Recipe recipe = catalog.get(resource);
            if(recipe != null) {
                page = actionsByName.get(WikiRecipes.normalize(recipe.name));
                MenuGrid.Pagina named = recipe.wiki == null ? null
                    : actionsByName.get(WikiRecipes.normalize(recipe.wiki.actionName));
                // Finished foods can share the menu name of their unbaked crafting stage.
                // Do not replace either wiki recipe or infer actions from resource icons.
                if(page == null) page = named;
                else if(named != null && named != page) return null;
            }
        }
        return page != null && gui.menu != null && gui.menu.paginae.contains(page) ? page : null;
    }
    public MenuGrid.Pagina shortcut(String resource) {
        // Refresh at the interaction boundary; draw/filter lookups must not mutate the catalog.
        if(gui.menu != lastMenu || (gui.menu != null && gui.menu.pagseq != lastMenuSeq)) scan();
        return AtlasSource.super.shortcut(resource);
    }
    public boolean available(String resource) { return page(resource) != null; }
    public void open(String resource) {
        MenuGrid.Pagina page = shortcut(resource);
        // A cached entry never grants an action which the current session no longer offers.
        if(page != null && gui.menu != null && gui.menu.paginae.contains(page))
            page.button().use(new MenuGrid.Interaction());
    }

    public boolean observe(NMakewindow window) {
        if(window.getparent(NGameUI.class) != gui || !window.atlasInputsReceived || !window.atlasOutputsReceived) return false;
        String id = nativeIds.getOrDefault(window.recipeResource, window.recipeResource);
        MenuGrid.Pagina page = id == null ? null : available.get(id);
        // The legacy lastPagina is global; validate it against this character and recipe name.
        if(page == null || !page.button().name().equals(window.rcpnm)) {
            page = null;
            for(MenuGrid.Pagina candidate : available.values()) {
                if(candidate.button().name().equals(window.rcpnm)) {
                    if(page != null) return false; // ambiguous name, do not record against the wrong recipe
                    page = candidate;
                }
            }
        }
        if(page == null) return false;
        id = nativeIds.getOrDefault(page.res().name, page.res().name);
        List<AtlasCatalog.Material> in = materials(window.inputs), out = materials(window.outputs);
        List<String> quality = names(window.qmod), tools = names(window.tools);
        AtlasCatalog.Recipe old = catalog.get(id);
        catalog.put(new AtlasCatalog.Recipe(id, old != null && old.wiki != null ? old.name : window.rcpnm,
            old == null ? "" : old.group, in, out, quality, tools, true, old == null ? null : old.wiki));
        return true;
    }
    private List<AtlasCatalog.Material> materials(List<NMakewindow.Spec> specs) {
        List<AtlasCatalog.Material> result = new ArrayList<>();
        for(NMakewindow.Spec spec : specs) {
            Resource resource = spec.res.get();
            String name = spec.name;
            if(name == null) name = ItemInfo.Name.Default.get(spec);
            if(name == null) name = displayName(resource);
            // Load all info before committing, so Optional cannot silently turn into required.
            boolean optional = spec.info().stream().anyMatch(i -> i.getClass().getName().endsWith("$Optional"));
            boolean category = spec.constraint != null || nurgling.tools.VSpec.categories.containsKey(name);
            if(!category) {
                JSONObject icon = haven.res.lib.itemtex.ItemTex.save(spec.sprite());
                nurgling.tools.ItemIcons.register(name, icon == null ? new JSONObject().put("static", resource.name) : icon);
            }
            result.add(new AtlasCatalog.Material(resource.name, name, spec.count, "", optional, category,
                wikiDatabase == null ? "" : wikiDatabase.reference(name)));
        }
        return result;
    }
    private static List<String> names(List<Indir<Resource>> resources) {
        List<String> result = new ArrayList<>();
        for(Indir<Resource> resource : resources) result.add(displayName(resource.get()));
        return result;
    }
    private static String displayName(Resource resource) {
        Resource.Tooltip tip = resource.layer(Resource.tooltip);
        return tip == null ? resource.basename() : tip.text();
    }

    public void flush() {
        if(!flowLoadFailed && savedFlowVersion != canvases.version()) {
            String snapshot = canvases.json().toString(); savedFlowVersion = canvases.version();
            writer.execute(() -> {
                try { NFileUtils.writeAtomically(flowFile.toString(), snapshot); flowError = null; }
                catch(Exception e) { savedFlowVersion = -1; flowError = "Cannot save calculator: " + e.getMessage(); }
            });
        }
        // Preserve unrecognized or damaged files if neither primary nor backup could be read.
        if(loadFailed) return;
        if(savedVersion == catalog.version && saveError == null) return;
        String snapshot = catalog.journalJson().toString();
        savedVersion = catalog.version;
        writer.execute(() -> {
            try { NFileUtils.writeAtomically(file.toString(), snapshot); saveError = null; }
            catch(Exception e) { saveError = e.getMessage(); System.err.println("[CraftAtlas] " + saveError); }
        });
    }
}
