package nurgling.craft;

import haven.MenuGrid;

/** View boundary; cached data and a character's currently permitted actions are distinct. */
public interface AtlasSource {
    AtlasCatalog catalog();
    boolean available(String resource);
    MenuGrid.Pagina page(String resource);
    void open(String resource);
    void flush();
    int availabilityVersion();
    String saveError();
    default CraftFlow flow() { return null; }
    default CraftCanvases canvases() { return null; }
    default Double liveQuality(QualityModel.Port port) { return null; }

    static String actionKind(haven.Resource.AButton action) {
        if(action.ad.length < 2) return "";
        return "craft".equals(action.ad[0]) ? "craft" : "bp".equals(action.ad[0]) ? "build" : "";
    }

    /** Only actual actions offered by this character may become recipe hotkeys. */
    default MenuGrid.Pagina shortcut(String resource) {
        MenuGrid.Pagina page = page(resource);
        if(page == null || page.scm == null || !page.scm.paginae.contains(page)) return null;
        try { return actionKind(page.button().act()).isEmpty() ? null : page; }
        catch(haven.Loading ignored) { return null; }
    }

    /** Resolve a native menu drag without executing the action or modifying the catalog. */
    default AtlasCatalog.Recipe recipe(Object thing) {
        if(thing instanceof RecipeTransfer) thing = ((RecipeTransfer)thing).recipe;
        if(thing instanceof AtlasCatalog.Recipe)
            return catalog().get(((AtlasCatalog.Recipe)thing).resource);
        if(!(thing instanceof MenuGrid.Pagina)) return null;
        MenuGrid.Pagina page = (MenuGrid.Pagina)thing;
        haven.Resource.AButton action = page.button().act();
        String kind = actionKind(action);
        if(kind.isEmpty()) return null;
        for(AtlasCatalog.Recipe recipe : catalog().recipes())
            if((recipe.wiki == null || kind.equals(recipe.wiki.kind)) && page(recipe.resource) == page) return recipe;
        AtlasCatalog.Recipe recipe = catalog().get(page.res().name);
        if(recipe != null) return recipe;
        recipe = catalog().findWikiAction(action.name, kind);
        if(recipe != null && page.scm != null) {
            // Identically named game actions must not acquire the same wiki formula.
            for(MenuGrid.Pagina other : page.scm.paginae) {
                if(other == page) continue;
                haven.Resource.AButton a = other.button().act();
                if(kind.equals(actionKind(a)) &&
                   WikiRecipes.normalize(a.name).equals(WikiRecipes.normalize(action.name))) {
                    recipe = null; break;
                }
            }
        }
        return recipe != null ? recipe : new AtlasCatalog.Recipe(page.res().name, action.name, "");
    }
}
