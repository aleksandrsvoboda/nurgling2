package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NFlowerMenu;
import nurgling.NWindowDeco;
import nurgling.actions.ReadJsonAction;
import nurgling.cookbook.CookbookModel;
import nurgling.cookbook.CookbookRow;
import nurgling.cookbook.Recipe;
import nurgling.cookbook.SpiceCalc;
import nurgling.cookbook.connection.RecipeCatalogLoader;
import nurgling.db.DatabaseManager;
import nurgling.db.service.FavoriteRecipeService;
import nurgling.i18n.L10n;
import nurgling.sessions.BotExecutor;
import nurgling.widgets.cookbook.CText;
import nurgling.widgets.cookbook.CookbookTheme;
import nurgling.widgets.cookbook.FepFilterPanel;
import nurgling.widgets.cookbook.HintTextEntry;
import nurgling.widgets.cookbook.IngredientFilterPanel;
import nurgling.widgets.cookbook.OptionsPanel;
import nurgling.widgets.cookbook.PillButton;
import nurgling.widgets.cookbook.Popover;
import nurgling.widgets.cookbook.RecipeTable;
import nurgling.widgets.cookbook.SpicePanel;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The cookbook: every recipe the village database has recorded, in a table after the Kitten Rider
 * cookbook (cookbook.kittenrider.com). Live search, FEP / ingredient / spice / option filters,
 * sortable columns, the stat icons with their +1/+2 switch, and a spice what-if.
 *
 * <p>All filtering and sorting happens in memory on {@link CookbookModel}; the database is only
 * read, off the UI thread, when the window opens or Reload is pressed.
 */
public class NCookBook extends Window implements RecipeTable.Actions {
    private static final Coord DEFAULT_SZ = UI.scale(new Coord(1100, 560));
    private static final Coord MIN_SZ = UI.scale(new Coord(640, 320));
    private static final String SIZE_PREF = "wndsz-cookbook";
    private static final int GAP = UI.scale(6);

    private final CookbookModel model = new CookbookModel();
    private HintTextEntry search;
    private PillButton fepPill, ingPill, spicePill, optPill, reloadBtn;
    private IButton importBtn;
    private CText count, banner, footer;
    private RecipeTable table;
    private Popover fepPanel, ingPanel, spicePanel, optPanel;
    private Popover openPop = null;
    private NFlowerMenu rowMenu = null;

    private RecipeCatalogLoader loader = null;
    private String loadError = null;
    private boolean loadedOnce = false;
    private boolean posRestored = false;
    private int seenVersion = -1, seenQuery = -1;
    private int toolbarH = 0;
    private Area bannerArea = null;

    public NCookBook() {
        super(clampSize(Utils.getprefc(SIZE_PREF, DEFAULT_SZ)), L10n.get("cookbook.window_title"));
        posmem("cookbook");

        search = add(new HintTextEntry(UI.scale(220), L10n.get("cookbook.search_hint"), this::searchChanged), Coord.z);
        search.settip(L10n.get("cookbook.search_tip"));
        fepPill = add(new PillButton(L10n.get("cookbook.pill.feps"), true, () -> toggle(fepPanel)), Coord.z);
        ingPill = add(new PillButton(L10n.get("cookbook.pill.ingredients"), true, () -> toggle(ingPanel)), Coord.z);
        spicePill = add(new PillButton(L10n.get("cookbook.pill.spices"), true, () -> toggle(spicePanel)), Coord.z);
        optPill = add(new PillButton(L10n.get("cookbook.pill.options"), true, () -> toggle(optPanel)), Coord.z);
        count = add(new CText(CookbookTheme.body, CookbookTheme.muted), Coord.z);
        reloadBtn = add(new PillButton(L10n.get("cookbook.btn_reload"), false, this::reload), Coord.z);
        reloadBtn.settip(L10n.get("cookbook.btn_reload_tip"));
        importBtn = add(new IButton(Resource.loadsimg("nurgling/hud/buttons/cookbook/download/u"),
                                    Resource.loadsimg("nurgling/hud/buttons/cookbook/download/d"),
                                    Resource.loadsimg("nurgling/hud/buttons/cookbook/download/h")) {
            @Override
            public void click() {
                importFoodInfo();
            }
        }, Coord.z);
        importBtn.settip(L10n.get("cookbook.btn_import"));
        banner = add(new CText(CookbookTheme.body, CookbookTheme.fg), Coord.z);
        table = add(new RecipeTable(Coord.of(UI.scale(200), UI.scale(200)), model, this), Coord.z);
        footer = add(new CText(CookbookTheme.small, CookbookTheme.muted).set(L10n.get("cookbook.footer")), Coord.z);

        /* Last, so they draw over the table and get clicks first. */
        fepPanel = add(new FepFilterPanel(fepPill, model), Coord.z);
        ingPanel = add(new IngredientFilterPanel(ingPill, model), Coord.z);
        spicePanel = add(new SpicePanel(spicePill, model), Coord.z);
        optPanel = add(new OptionsPanel(optPill, model, this::clearFilters), Coord.z);
        arrange();
    }

    @Override
    protected Deco makedeco() {
        return new NWindowDeco(false).dragsize(true);
    }

    private static Coord clampSize(Coord sz) {
        return Coord.of(Math.max(sz.x, MIN_SZ.x), Math.max(sz.y, MIN_SZ.y));
    }

    @Override
    public void resize(Coord sz) {
        super.resize(clampSize(sz));
        /* Null while Window's constructor runs. */
        if(table != null)
            arrange();
    }

    private void arrange() {
        Coord isz = csz();
        int th = Math.max(search.sz.y, fepPill.sz.y);
        int x = 0;
        search.move(Coord.of(x, (th - search.sz.y) / 2));
        x += search.sz.x + GAP;
        for(PillButton p : new PillButton[] {fepPill, ingPill, spicePill, optPill}) {
            p.move(Coord.of(x, (th - p.sz.y) / 2));
            x += p.sz.x + GAP;
        }
        int rx = isz.x - importBtn.sz.x;
        importBtn.move(Coord.of(rx, (th - importBtn.sz.y) / 2));
        rx -= GAP + reloadBtn.sz.x;
        reloadBtn.move(Coord.of(rx, (th - reloadBtn.sz.y) / 2));
        rx -= UI.scale(12) + count.sz.x;
        count.move(Coord.of(Math.max(x, rx), (th - count.sz.y) / 2));
        toolbarH = th;

        int y = th + GAP;
        boolean spiced = !banner.text().isEmpty();
        banner.show(spiced);
        if(spiced) {
            int bh = banner.sz.y + UI.scale(6);
            bannerArea = Area.sized(Coord.of(0, y), Coord.of(isz.x, bh));
            banner.move(Coord.of(UI.scale(8), y + UI.scale(3)));
            y += bh + GAP;
        } else {
            bannerArea = null;
        }
        footer.move(Coord.of(0, isz.y - footer.sz.y));
        table.move(Coord.of(0, y));
        table.resize(Coord.of(isz.x, Math.max(isz.y - y - footer.sz.y - GAP, RecipeTable.ROW_H * 2)));
        for(Popover p : new Popover[] {fepPanel, ingPanel, spicePanel, optPanel})
            place(p);
    }

    private void place(Popover p) {
        Coord isz = csz();
        int px = Math.max(0, Math.min(p.owner.c.x, isz.x - p.sz.x));
        p.move(Coord.of(px, p.owner.c.y + p.owner.sz.y + UI.scale(3)));
    }

    @Override
    public void cdraw(GOut g) {
        CookbookTheme.fill(g, Coord.z, Coord.of(csz().x, toolbarH), CookbookTheme.head);
        if(bannerArea != null)
            CookbookTheme.fill(g, bannerArea.ul, bannerArea.sz(), CookbookTheme.warnBg);
    }

    /* ---- showing and loading ---- */

    @Override
    public void show() {
        super.show();
        reload();
    }

    @Override
    public void hide() {
        closePopover();
        Utils.setprefc(SIZE_PREF, csz());
        super.hide();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if(msg.equals("close"))
            hide();
        else
            super.wdgmsg(sender, msg, args);
    }

    private DatabaseManager db() {
        if((ui == null) || (ui.core == null))
            return null;
        return ui.core.databaseManager;
    }

    private boolean dbReady() {
        DatabaseManager db = db();
        return (Boolean)NConfig.get(NConfig.Key.ndbenable) && (db != null) && db.isReady();
    }

    private void reload() {
        if((loader == null) && dbReady()) {
            RecipeCatalogLoader l = new RecipeCatalogLoader(db());
            if(db().submitTask(l) != null)
                loader = l;
        }
        refreshChrome();
    }

    @Override
    public void tick(double dt) {
        if(!posRestored) {
            /* GameUI places the window before it is shown; a remembered spot wins over that default. */
            posRestored = true;
            c = restorepos(c);
        }
        super.tick(dt);
        if((loader != null) && loader.ready.get()) {
            loadError = loader.error();
            if(loadError == null)
                model.setRecipes(loader.recipes());
            loader = null;
            loadedOnce = true;
            refreshChrome();
        }
        if(model.version() != seenVersion) {
            seenVersion = model.version();
            refreshChrome();
        }
        if(model.queryVersion() != seenQuery) {
            seenQuery = model.queryVersion();
            table.scrollTop();
        }
    }

    /* Everything outside the table that shows the model: pill summaries, the count, the spice
     * banner, the empty-table message and an open panel. */
    private void refreshChrome() {
        fepPill.suffix(model.fepCodes().isEmpty() ? "" : String.valueOf(model.fepCodes().size()));
        ingPill.suffix(model.ingredients().isEmpty() ? "" : String.valueOf(model.ingredients().size()));
        spicePill.suffix(spiceSummary());
        List<CookbookRow> view = model.view();
        if(loader != null)
            count.set(L10n.get("cookbook.loading"));
        else
            count.set(L10n.get("cookbook.count", String.valueOf(view.size()), String.valueOf(model.total())));
        banner.set(bannerText());
        table.empty(emptyMessage(view), view.isEmpty() && (model.total() > 0) && model.hasActiveFilters());
        if(openPop != null)
            openPop.syncFromModel();
        arrange();
    }

    private String emptyMessage(List<CookbookRow> view) {
        if(!view.isEmpty())
            return "";
        if(model.total() > 0)
            return L10n.get("cookbook.empty.filtered", String.valueOf(model.total()));
        if(!dbReady())
            return L10n.get("cookbook.empty.db");
        if((loader != null) || !loadedOnce)
            return L10n.get("cookbook.empty.loading");
        if(loadError != null)
            return L10n.get("cookbook.empty.error", loadError);
        return L10n.get("cookbook.empty.none");
    }

    private String spiceSummary() {
        List<String> parts = new ArrayList<>();
        for(Map.Entry<SpiceCalc.Spice, Double> e : model.activeSpices().entrySet())
            parts.add(L10n.get("cookbook.spice.summary", SpicePanel.name(e.getKey()).toLowerCase(Locale.ROOT), SpicePanel.quality(e.getValue())));
        return String.join(", ", parts);
    }

    private String bannerText() {
        if(model.activeSpices().isEmpty())
            return "";
        List<String> parts = new ArrayList<>();
        for(Map.Entry<SpiceCalc.Spice, Double> e : model.activeSpices().entrySet()) {
            SpiceCalc.Spice s = e.getKey();
            String name = SpicePanel.name(s).toLowerCase(Locale.ROOT);
            String q = SpicePanel.quality(e.getValue());
            String m = String.format(Locale.ROOT, "%.4f", s.multiplier(e.getValue()));
            if(s.adds == null)
                parts.add(L10n.get("cookbook.banner.part", name, q, m));
            else
                parts.add(L10n.get("cookbook.banner.adds", name, q, m, s.adds.title));
        }
        return L10n.get("cookbook.banner", String.join(", ", parts));
    }

    /* ---- toolbar ---- */

    private void searchChanged() {
        if(search != null)
            model.setSearch(search.text());
    }

    private void toggle(Popover p) {
        if(openPop == p) {
            closePopover();
            return;
        }
        closePopover();
        p.syncFromModel();
        place(p);
        p.show();
        p.raise();
        p.owner.expanded = true;
        openPop = p;
    }

    private void closePopover() {
        if(openPop == null)
            return;
        openPop.hide();
        openPop.owner.expanded = false;
        openPop = null;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        /* A click anywhere but the open panel or its own button closes the panel. */
        if(openPop != null) {
            Coord pc = xlate(openPop.c, true), oc = xlate(openPop.owner.c, true);
            if(!ev.c.isect(pc, openPop.sz) && !ev.c.isect(oc, openPop.owner.sz))
                closePopover();
        }
        return super.mousedown(ev);
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if((openPop != null) && key_esc.match(ev)) {
            closePopover();
            return true;
        }
        return super.keydown(ev);
    }

    @Override
    public void lostfocus() {
        closePopover();
        super.lostfocus();
    }

    private void importFoodInfo() {
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("food-info2 file", "json"));
            if((fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) || (fc.getSelectedFile() == null))
                return;
            BotExecutor.runAsync("food-info2_download", new ReadJsonAction(fc.getSelectedFile().getAbsolutePath()));
        });
    }

    /* ---- row actions ---- */

    @Override
    public void clearFilters() {
        model.clearFilters();
        search.settext("");
        closePopover();
    }

    @Override
    public void craft(CookbookRow row) {
        GameUI gui = getparent(GameUI.class);
        if((gui == null) || (gui.menu == null))
            return;
        MenuGrid.Pagina target = null;
        synchronized(gui.menu.paginae) {
            for(MenuGrid.Pagina pg : gui.menu.paginae) {
                try {
                    if(Objects.equals(pg.button().name(), row.name())) {
                        target = pg;
                        break;
                    }
                } catch(Loading l) {
                    /* Not loaded yet, so not the recipe the player is looking at. */
                }
            }
        }
        if(target != null)
            target.button().use(new MenuGrid.Interaction(1, 0));
    }

    @Override
    public void toggleFavorite(CookbookRow row) {
        DatabaseManager db = db();
        if(!dbReady())
            return;
        FavoriteRecipeService favorites = db.getFavoriteRecipeService();
        if(favorites == null)
            return;
        Recipe recipe = row.recipe;
        boolean favorite = !recipe.isFavorite();
        recipe.setFavorite(favorite);
        model.favoritesChanged();
        String hash = recipe.getHash();
        db.submitTask(() -> {
            try {
                if(favorite)
                    favorites.addFavorite(hash);
                else
                    favorites.removeFavorite(hash);
            } catch(SQLException e) {
                System.err.println("[Cookbook] Failed to save favourite " + hash + ": " + e.getMessage());
            }
        });
    }

    @Override
    public void rowMenu(CookbookRow row, Widget at) {
        if(rowMenu != null)
            return;
        String[] opts = {L10n.get(row.recipe.isFavorite() ? "cookbook.remove_from_favorites" : "cookbook.add_to_favorites")};
        rowMenu = new NFlowerMenu(opts) {
            @Override
            public boolean mousedown(MouseDownEvent ev) {
                if(super.mousedown(ev))
                    nchoose(null);
                return true;
            }

            @Override
            public void destroy() {
                rowMenu = null;
                super.destroy();
            }

            @Override
            public void nchoose(NPetal option) {
                if(option != null)
                    toggleFavorite(row);
                uimsg("cancel");
            }
        };
        rowMenu.shiftMode = true;
        ui.root.add(rowMenu, at.rootpos().add(UI.scale(60, 20)));
    }
}
