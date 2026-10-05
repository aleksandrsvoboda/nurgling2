package nurgling.craft;

import haven.MenuGrid;

/** Preserve the selected wiki stage on the canvas, and resolve its game action for hotbars. */
public final class RecipeTransfer {
    public final AtlasCatalog.Recipe recipe;
    private final AtlasSource source;
    public RecipeTransfer(AtlasSource source, AtlasCatalog.Recipe recipe) {
        this.source=source; this.recipe=recipe;
    }
    public MenuGrid.Pagina page() { return source.shortcut(recipe.resource); }
}
