package nurgling.widgets.cookbook;

import haven.Tex;
import nurgling.tools.ItemIcons;

/** Compatibility entry point for the shared item resource cache. */
final class IconCache {
    private IconCache() {}
    static Tex get(String resource) { return ItemIcons.getResource(resource); }
}
