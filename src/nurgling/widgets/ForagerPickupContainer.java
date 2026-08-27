package nurgling.widgets;

import haven.*;
import haven.res.lib.itemtex.ItemTex;
import nurgling.NGItem;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerAction;
import nurgling.tools.VSpec;
import org.json.JSONObject;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drag-and-drop "what should Forager pick up" editor for one Actions profile. Each icon is one
 * {@link ForagerAction}: dropping a real item resolves its target gob pattern via {@link VSpec}
 * (a reverse item-&gt;gob lookup for things like tree/bush produce, falling back to matching the
 * item's own name directly for herbs, where item and gob names already coincide) and guesses a
 * default pick action from the item's VSpec category; "Add custom" covers anything with no real
 * item to drag, resolving an icon the same way {@code CheeseOrdersPanel} does for its schedule's
 * custom-named steps. Every field (pattern/action type/action name) stays directly editable
 * afterward via the per-item tag menu or the profile's advanced view - this widget only sets
 * reasonable defaults, it never limits what can be expressed.
 */
public class ForagerPickupContainer extends BaseIngredientContainer implements TaggableItemContainer {

    /** category name (VSpec.categories key) -> default flower-menu action label for that pick. */
    // NOTE: these labels must match the actual in-game flower-menu option text exactly (case
    // included) - "Pick Fruit"/"Pick Nuts" is a best guess at standard Haven capitalization,
    // not yet confirmed against a live flower menu. Verify in-game and correct if needed.
    private static final Map<String, String> CATEGORY_DEFAULT_ACTION = new LinkedHashMap<>();
    static {
        CATEGORY_DEFAULT_ACTION.put("Nuts", "Pick Nuts");
        CATEGORY_DEFAULT_ACTION.put("Fruit", "Pick Fruit");
    }

    /** Tags offered on every item, regardless of how its default action was resolved - kept
     *  small and easy to grow, per the user's own "we can build the list of options later". */
    private static final List<String> TAG_OPTIONS = new ArrayList<>();
    static {
        TAG_OPTIONS.add("Pick Fruit");
        TAG_OPTIONS.add("Pick Nuts");
    }

    // Aliases whatever list load() was last given (typically a preset's own live `actions`
    // field) rather than holding a private copy, so every mutation here (drop/delete/tag/add
    // custom) is immediately reflected in the caller's list with no separate sync-back step.
    private ArrayList<ForagerAction> actions = new ArrayList<>();

    /** Notified after any edit (drop, delete, tag change, add custom/manual) so the owner can
     *  persist config immediately, matching how every other per-item edit in this codebase
     *  (IngredientContainer, FoodContainer) saves right away rather than batching to a Save
     *  button. Optional - fine to leave unset if the caller persists on its own schedule. */
    public Runnable onChange = null;

    private void notifyChanged() {
        if (onChange != null) {
            onChange.run();
        }
    }

    public ForagerPickupContainer() {
        super("forager_pickup");
    }

    /** Resolved default for a dropped/typed item: its gob pattern, and a guessed action. */
    private static class Resolution {
        final String pattern;
        final ForagerAction.ActionType actionType;
        final String actionName;

        Resolution(String pattern, ForagerAction.ActionType actionType, String actionName) {
            this.pattern = pattern;
            this.actionType = actionType;
            this.actionName = actionName;
        }
    }

    private Resolution resolve(String itemName) {
        ArrayList<String> gobs = VSpec.getGobsForItem(itemName);

        if (gobs.isEmpty()) {
            // No tree/bush link found - assume herb/mushroom-style direct match: matching is a
            // plain substring check against the gob's resource path (e.g. "chantrelle" against
            // "gfx/terobjs/herbs/chantrelle"), which a plural or multi-word display name breaks
            // outright ("Chantrelles" is not a substring of ".../chantrelle" - the trailing "s"
            // alone defeats it; "Liberty Caps" never will be either, since resource paths have
            // no spaces). Try several normalized candidates alongside the literal name so a
            // simple plural/spacing mismatch like that resolves on its own; anything resolvable
            // only via an unrelated internal name (e.g. "Morels" -> the "lorchel" resource) still
            // needs a manual fix via this item's right-click "Edit Pattern".
            return new Resolution(herbPatternCandidates(itemName), ForagerAction.ActionType.PICK, null);
        }
        String pattern = String.join(",", gobs);

        for (Map.Entry<String, String> cat : CATEGORY_DEFAULT_ACTION.entrySet()) {
            List<String> content = VSpec.getCategoryContent(cat.getKey());
            if (content.contains(itemName)) {
                return new Resolution(pattern, ForagerAction.ActionType.FLOWER_ACTION, cat.getValue());
            }
        }
        // Linked to a gob but no known category default - still a flower-menu pick (it's not a
        // herb), just with no guessed label; the user picks one via the tag menu.
        return new Resolution(pattern, ForagerAction.ActionType.FLOWER_ACTION, null);
    }

    /**
     * Builds a comma-separated set of candidate substrings to match against a gob's resource
     * path for an item with no VSpec gob link, widest/most-specific first: the literal name, the
     * name with spaces removed (multi-word display names never appear as-is in a resource path,
     * which has none), and singular versions of both (strip one trailing "s") since resource
     * paths are consistently singular even when the item name is plural. Duplicates are dropped;
     * {@link ForagerAction#toNAlias()} matches on ANY of these against the gob's name.
     */
    private static String herbPatternCandidates(String itemName) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(itemName);

        String noSpaces = itemName.replace(" ", "");
        candidates.add(noSpaces);

        if (noSpaces.length() > 1 && noSpaces.endsWith("s")) {
            candidates.add(noSpaces.substring(0, noSpaces.length() - 1));
        }

        // Last word alone (e.g. "Caps" out of "Liberty Caps") - and its singular - as a narrower
        // fallback in case the full concatenated name still doesn't match anything.
        String[] words = itemName.trim().split("\\s+");
        String lastWord = words[words.length - 1];
        candidates.add(lastWord);
        if (lastWord.length() > 1 && lastWord.endsWith("s")) {
            candidates.add(lastWord.substring(0, lastWord.length() - 1));
        }

        return String.join(",", candidates);
    }

    private void addResolved(String itemName, JSONObject iconRes, Resolution res) {
        ForagerAction action = new ForagerAction(res.pattern, res.actionType, res.actionName);
        action.sourceItemName = itemName;
        if (iconRes != null && iconRes.has("static")) {
            action.sourceItemResource = iconRes.getString("static");
        }
        actions.add(action);
        addIcon(iconRes);
        notifyChanged();
    }

    @Override
    public boolean drop(Drop ev) {
        NGItem item = (NGItem) ev.src.item;
        String name = item.name();
        JSONObject res = ItemTex.save(item.spr);
        res.put("name", name);
        addResolved(name, res, resolve(name));
        return super.drop(ev);
    }

    /** Opens a small prompt to add an entry with no real item to drag in. */
    public void promptAddCustom() {
        TextInputWindow inputWindow = new TextInputWindow(
                L10n.get("forager.pickup.add_custom_title"), L10n.get("forager.pickup.add_custom_prompt"), typedName -> {
            if (typedName != null && !typedName.trim().isEmpty()) {
                addCustom(typedName.trim());
            }
        });
        NUtils.getGameUI().add(inputWindow, UI.scale(200, 200));
        inputWindow.show();
    }

    private void addCustom(String typedName) {
        JSONObject iconRes = new JSONObject();
        iconRes.put("name", typedName);
        String iconPath = VSpec.getIconPath(typedName);
        if (iconPath != null) {
            iconRes.put("static", iconPath);
        }
        Resolution res = resolve(typedName);
        BufferedImage img = (iconPath != null) ? ItemTex.create(iconRes) : null;
        if (img == null) {
            // Either no icon path was found, or ItemTex failed to load it (e.g. stale/renamed
            // resource) - either way, fall back to a stable per-name placeholder rather than
            // leaving the entry unrenderable.
            iconRes.remove("static");
            addResolvedPlaceholder(typedName, iconRes, res, placeholderIcon(typedName));
            return;
        }
        addResolved(typedName, iconRes, res);
    }

    private void addResolvedPlaceholder(String itemName, JSONObject iconRes, Resolution res, BufferedImage placeholder) {
        ForagerAction action = new ForagerAction(res.pattern, res.actionType, res.actionName);
        action.sourceItemName = itemName;
        actions.add(action);
        // BaseIngredientContainer.addIcon() always goes through ItemTex.create(), which can't
        // produce this placeholder - add the icon item directly instead, mirroring addIcon()'s
        // own bookkeeping (items/icons lists, grid position, scroll bounds).
        items.add(new Ingredient(itemName, placeholder));
        IconItem it = add(new IconItem(itemName, placeholder, this),
                UI.scale(new Coord(35 * ((items.size() - 1) % 5), 51 * ((items.size() - 1) / 5))).add(new Coord(5, 5)));
        it.basec = new Coord(it.c);
        icons.add(it);
        maxy = UI.scale(51) * ((items.size() - 1) / 5 - 5);
        cury = Math.min(cury, Math.max(maxy, 0));
        notifyChanged();
    }

    /** Simple hash-colored square, same approach CheeseOrdersPanel uses for unresolvable names. */
    private static BufferedImage placeholderIcon(String name) {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        int hash = name.hashCode();
        int r = 100 + (Math.abs(hash) % 120);
        int g = 90 + (Math.abs(hash >> 8) % 120);
        int b = 90 + (Math.abs(hash >> 16) % 120);
        g2d.setColor(new Color(r, g, b));
        g2d.fillRect(0, 0, size, size);
        g2d.setColor(Color.BLACK);
        g2d.drawRect(0, 0, size - 1, size - 1);
        g2d.dispose();
        return img;
    }

    @Override
    public void addIcon(JSONObject res) {
        super.addIcon(res);
        // Restore this entry's tag (if any) onto the icon just added.
        if (!icons.isEmpty() && res != null && res.has("name")) {
            String name = res.getString("name");
            for (ForagerAction action : actions) {
                if (name.equals(action.sourceItemName) && action.tag != null) {
                    icons.get(icons.size() - 1).setCustomTag(action.tag);
                    break;
                }
            }
        }
    }

    @Override
    public void delete(String name) {
        actions.removeIf(a -> name.equals(a.sourceItemName));
        super.delete(name);
        notifyChanged();
    }

    @Override
    public void deleteAll() {
        // Only clear entries this widget actually renders - a shared list may also hold
        // advanced/manual entries (e.g. CHAT_NOTIFY) added outside this widget, which must
        // survive a "clear icons" here.
        actions.removeIf(a -> a.sourceItemName != null);
        super.deleteAll();
        notifyChanged();
    }

    /**
     * Redraws from (and starts aliasing) a preset's live action list, so every mutation this
     * widget makes afterward - drop, delete, tag, add custom - is reflected directly in the
     * caller's list with no separate save/sync step needed. Entries with no sourceItemName
     * (e.g. advanced/manual entries added outside this widget) are left in the list untouched
     * but simply aren't rendered as icons here.
     */
    public void load(ArrayList<ForagerAction> liveActions) {
        this.actions = liveActions;
        for (IconItem it : icons) {
            it.destroy();
        }
        icons.clear();
        items.clear();

        for (ForagerAction action : liveActions) {
            if (action.sourceItemName == null) {
                // Not created via this widget (e.g. a legacy free-text entry) - not shown here.
                continue;
            }
            JSONObject iconRes = new JSONObject();
            iconRes.put("name", action.sourceItemName);
            BufferedImage img = null;
            if (action.sourceItemResource != null) {
                iconRes.put("static", action.sourceItemResource);
                img = ItemTex.create(iconRes);
            }
            if (img == null) {
                addResolvedIconOnly(action.sourceItemName, placeholderIcon(action.sourceItemName), action.tag);
            } else {
                addIcon(iconRes);
            }
        }
    }

    private void addResolvedIconOnly(String name, BufferedImage img, String tag) {
        items.add(new Ingredient(name, img));
        IconItem it = add(new IconItem(name, img, this),
                UI.scale(new Coord(35 * ((items.size() - 1) % 5), 51 * ((items.size() - 1) / 5))).add(new Coord(5, 5)));
        it.basec = new Coord(it.c);
        if (tag != null) {
            it.setCustomTag(tag);
        }
        icons.add(it);
        maxy = UI.scale(51) * ((items.size() - 1) / 5 - 5);
        cury = Math.min(cury, Math.max(maxy, 0));
    }

    /**
     * Adds an action configured through the full manual editor (pattern/action type/action name,
     * including CHAT_NOTIFY - see ActionConfigWindow), for anything the drag/drop + "add custom"
     * flow can't cleanly express. Rendered as an icon here too when possible, using the pattern
     * itself as the display name and resolving an icon the same way "add custom" does; falls
     * back to being tracked without an icon (still fully functional for the bot, just not shown
     * in this grid) if the pattern doesn't resolve to anything renderable and isn't a CHAT_NOTIFY.
     */
    public void addManual(ForagerAction action) {
        if (action.actionType == ForagerAction.ActionType.CHAT_NOTIFY) {
            actions.add(action);
            notifyChanged();
            return;
        }
        String displayName = action.targetObjectPattern.split(",")[0].trim();
        action.sourceItemName = displayName;
        String iconPath = VSpec.getIconPath(displayName);
        JSONObject iconRes = new JSONObject();
        iconRes.put("name", displayName);
        BufferedImage img = null;
        if (iconPath != null) {
            iconRes.put("static", iconPath);
            action.sourceItemResource = iconPath;
            img = ItemTex.create(iconRes);
        }
        actions.add(action);
        if (img != null) {
            addIcon(iconRes);
            notifyChanged();
        } else {
            // addResolvedIconOnly() already notifies.
            addResolvedIconOnly(displayName, placeholderIcon(displayName), action.tag);
        }
    }

    /**
     * Opens the full manual editor prefilled with this item's current pattern/action/action
     * name, for correcting a wrong or unresolved automatic guess (e.g. a plural/spacing mismatch
     * {@link #herbPatternCandidates} didn't cover, or a display name that shares no substring at
     * all with its actual gob resource). The icon/name stay as they are - only the underlying
     * match/action fields change.
     */
    @Override
    public void editItem(String itemName) {
        ForagerAction existing = null;
        int idx = -1;
        for (int i = 0; i < actions.size(); i++) {
            if (itemName.equals(actions.get(i).sourceItemName)) {
                existing = actions.get(i);
                idx = i;
                break;
            }
        }
        if (existing == null) {
            return;
        }
        final ForagerAction old = existing;
        final int foundIdx = idx;
        ActionConfigWindow win = new ActionConfigWindow(existing, updated -> {
            if (updated != null) {
                updated.sourceItemName = old.sourceItemName;
                updated.sourceItemResource = old.sourceItemResource;
                updated.tag = old.tag;
                actions.set(foundIdx, updated);
                notifyChanged();
            }
        });
        NUtils.getGameUI().add(win, UI.scale(200, 200));
        win.show();
    }

    @Override
    public List<String> tagOptions(String itemName) {
        return TAG_OPTIONS;
    }

    @Override
    public void setTag(String itemName, String tag) {
        for (ForagerAction action : actions) {
            if (itemName.equals(action.sourceItemName)) {
                action.tag = tag;
                if (tag == null) {
                    // Cleared - fall back to the originally-guessed default rather than leaving
                    // a blank action name behind.
                    Resolution res = resolve(itemName);
                    action.actionType = res.actionType;
                    action.actionName = res.actionName;
                } else {
                    action.actionType = ForagerAction.ActionType.FLOWER_ACTION;
                    action.actionName = tag;
                }
                notifyChanged();
                break;
            }
        }
    }

    @Override
    public String getTag(String itemName) {
        for (ForagerAction action : actions) {
            if (itemName.equals(action.sourceItemName)) {
                return action.tag;
            }
        }
        return null;
    }
}
