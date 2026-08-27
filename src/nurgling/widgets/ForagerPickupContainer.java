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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Drag-and-drop "what should Forager pick up" editor for one Actions profile. Each icon is one
 * {@link ForagerAction}: dropping a real item resolves its target gob pattern via {@link VSpec}
 * (a reverse item-&gt;gob lookup for things like tree/bush produce, falling back to matching the
 * item's own name directly for herbs, where item and gob names already coincide) and, for a
 * gob-linked item, a best-guess set of candidate flower-menu action strings tried in order at
 * runtime (see {@link #actionNameCandidates}) rather than one fixed guess. "Add custom" covers
 * anything with no real item to drag, resolving an icon the same way {@code CheeseOrdersPanel}
 * does for its schedule's custom-named steps. Every field (pattern/action type/action name) stays
 * directly editable afterward via the per-item right-click "Edit Pattern" - this widget only sets
 * reasonable defaults, it never limits what can be expressed.
 */
public class ForagerPickupContainer extends BaseIngredientContainer implements TaggableItemContainer {

    /** category name (VSpec.categories key) -> a flower-menu action string confirmed against the
     *  real menu (not a guess) - included ahead of any generated candidate for that category. */
    private static final Map<String, String> VERIFIED_CATEGORY_ACTION = new LinkedHashMap<>();
    static {
        // CollectBark (an existing, working bot) uses this exact string with the same tree/bush
        // gobs - confirmed correct, unlike everything actionNameCandidates() below only guesses.
        VERIFIED_CATEGORY_ACTION.put("Bark", "Take bark");
        VERIFIED_CATEGORY_ACTION.put("Berry", "Pick berries");
        VERIFIED_CATEGORY_ACTION.put("Tree Bough", "Take bough");
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
        return new Resolution(pattern, ForagerAction.ActionType.FLOWER_ACTION, actionNameCandidates(itemName));
    }

    /**
     * Candidate flower-menu option string(s) for a gob-linked item. If the item falls into a
     * category with a string already confirmed correct (see {@link #VERIFIED_CATEGORY_ACTION}),
     * that's used on its own - a known answer, not a guess, so there's no reason to also carry a
     * pile of untested ones alongside it. Otherwise falls back to a best-guess, ordered set:
     * "Pick "/"Take " + the item's own name (and its other singular/plural form, since the source
     * item name isn't necessarily how the flower menu phrases it - e.g. "Chestnut" the item vs.
     * potentially "Chestnuts" on the tree), then the same two prefixes against each VSpec category
     * the item falls into (e.g. "Nuts" for a nut). Tried in order at runtime (see
     * {@link ForagerAction#toActionNameCandidates}) against the gob's real flower menu - same
     * principle as matching several candidate gob-name patterns for an item with no VSpec link at
     * all. Not a substitute for a confirmed answer: right-click "Edit Pattern" always lets this be
     * replaced with one exact known-good string once confirmed in-game.
     */
    private static String actionNameCandidates(String itemName) {
        List<String> categories = VSpec.getCategory(itemName);

        LinkedHashSet<String> verified = new LinkedHashSet<>();
        for (String cat : categories) {
            String action = VERIFIED_CATEGORY_ACTION.get(cat);
            if (action != null) {
                verified.add(action);
            }
        }
        if (!verified.isEmpty()) {
            return String.join(",", verified);
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(itemName);
        if (itemName.length() > 1 && itemName.endsWith("s")) {
            names.add(itemName.substring(0, itemName.length() - 1));
        } else {
            names.add(itemName + "s");
        }
        names.addAll(categories);

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String name : names) {
            // Flower-menu text only capitalizes the first word of the whole phrase (confirmed by
            // the verified entries above) - the item/category name itself is never a proper noun
            // here, so lowercase it rather than guessing "Take Walnut"/"Take Nuts".
            String lower = name.toLowerCase();
            candidates.add("Pick " + lower);
            candidates.add("Take " + lower);
        }
        return String.join(",", candidates);
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
        // Matching is already case-insensitive (NAlias lowercases everything internally), but
        // gob resource paths are always lowercase - keep the saved pattern looking like one
        // instead of a mix of cases, so it reads sensibly if the user reviews/edits it later.
        String lower = itemName.toLowerCase();

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(lower);

        String noSpaces = lower.replace(" ", "");
        candidates.add(noSpaces);

        if (noSpaces.length() > 1 && noSpaces.endsWith("s")) {
            candidates.add(noSpaces.substring(0, noSpaces.length() - 1));
        }

        // Last word alone (e.g. "caps" out of "liberty caps") - and its singular - as a narrower
        // fallback in case the full concatenated name still doesn't match anything.
        String[] words = lower.trim().split("\\s+");
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

    /**
     * Opens the same searchable item catalogue Area Settings uses (browse by VSpec category, or
     * search by name across all of them) for adding something without needing the real item in
     * hand - reuses {@link NCatSelection}, generalized (see its {@code onSelect} constructor)
     * rather than duplicated, since it's already exactly this feature for a different container.
     */
    public void openCatalogue() {
        NCatSelection cat = new NCatSelection(this::addFromCatalogue);
        NUtils.getGameUI().add(cat, UI.scale(200, 150));
        cat.show();
    }

    private void addFromCatalogue(NCatSelection.Element element) {
        String name = element.getName();
        JSONObject iconRes = new JSONObject(element.getRes().toString());
        iconRes.put("name", name);
        addResolved(name, iconRes, resolve(name));
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

    // 400px wide (see ForagerSettingsPanel) - default gridColumns()=5 (tuned for the 205px-wide
    // area/food containers) would waste most of that. 10 columns * 35px + margin comfortably
    // fits within 400px alongside the scrollbar.
    @Override
    protected int gridColumns() {
        return 10;
    }

    private void addResolvedPlaceholder(String itemName, JSONObject iconRes, Resolution res, BufferedImage placeholder) {
        ForagerAction action = new ForagerAction(res.pattern, res.actionType, res.actionName);
        action.sourceItemName = itemName;
        actions.add(action);
        // BaseIngredientContainer.addIcon() always goes through ItemTex.create(), which can't
        // produce this placeholder - add the icon item directly instead, mirroring addIcon()'s
        // own bookkeeping (items/icons lists, grid position, scroll bounds).
        items.add(new Ingredient(itemName, placeholder));
        IconItem it = add(new IconItem(itemName, placeholder, this), gridPos(items.size() - 1));
        it.basec = new Coord(it.c);
        it.setFlowerAction(action.actionType == ForagerAction.ActionType.FLOWER_ACTION);
        icons.add(it);
        updateScrollRange();
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
        // Flag the flower badge on the icon just added, per this entry's action type.
        if (!icons.isEmpty() && res != null && res.has("name")) {
            String name = res.getString("name");
            for (ForagerAction action : actions) {
                if (name.equals(action.sourceItemName)) {
                    icons.get(icons.size() - 1).setFlowerAction(action.actionType == ForagerAction.ActionType.FLOWER_ACTION);
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
     * widget makes afterward - drop, delete, add custom - is reflected directly in the caller's
     * list with no separate save/sync step needed. Entries with no sourceItemName (e.g. advanced/
     * manual entries added outside this widget) are left in the list untouched but simply aren't
     * rendered as icons here.
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
            boolean isFlowerAction = action.actionType == ForagerAction.ActionType.FLOWER_ACTION;
            if (img == null) {
                addResolvedIconOnly(action.sourceItemName, placeholderIcon(action.sourceItemName), isFlowerAction);
            } else {
                addIcon(iconRes);
            }
        }
    }

    private void addResolvedIconOnly(String name, BufferedImage img, boolean isFlowerAction) {
        items.add(new Ingredient(name, img));
        IconItem it = add(new IconItem(name, img, this), gridPos(items.size() - 1));
        it.basec = new Coord(it.c);
        it.setFlowerAction(isFlowerAction);
        icons.add(it);
        updateScrollRange();
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
                actions.set(foundIdx, updated);
                for (IconItem it : icons) {
                    if (itemName.equals(it.name)) {
                        it.setFlowerAction(updated.actionType == ForagerAction.ActionType.FLOWER_ACTION);
                        break;
                    }
                }
                notifyChanged();
            }
        });
        NUtils.getGameUI().add(win, UI.scale(200, 200));
        win.show();
    }
}
