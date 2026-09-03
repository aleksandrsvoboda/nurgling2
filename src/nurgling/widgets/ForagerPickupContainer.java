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
import java.util.LinkedHashSet;
import java.util.List;

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

    /**
     * @param itemResourcePath the item's own invobj resource path (e.g.
     *                         "gfx/invobjs/herbs/blueberry"), if known - null for the rare case
     *                         nothing resolved one (e.g. a typed custom name with no catalogue
     *                         icon match)
     */
    private Resolution resolve(String itemName, String itemResourcePath) {
        ArrayList<String> gobs = VSpec.getGobsForItem(itemName);

        if (gobs.isEmpty()) {
            // No tree/bush link found - assume herb/mushroom-style direct match: matching is a
            // plain substring check against the gob's resource path (e.g. "blueberry" against
            // "gfx/terobjs/herbs/blueberry"). A herb's invobj and terobj resource paths
            // consistently share the same short name (only "invobjs"/"terobjs" differs), so the
            // item's own resource path's last segment is a precise, correct pattern on its own -
            // no need to guess anything from the display name, which is often a poor match for
            // the internal name (plurals like "Blueberries"/"Chantrelles", multi-word names like
            // "Liberty Caps", or a display name with no textual relation at all like "Morels" ->
            // the "lorchel" resource). Only falls back to guessing from the display name when no
            // resource path was available to slice at all.
            String pattern = (itemResourcePath != null)
                    ? resourceShortName(itemResourcePath)
                    : herbPatternCandidates(itemName);
            return new Resolution(pattern, ForagerAction.ActionType.PICK, null);
        }
        String pattern = String.join(",", gobs);
        return new Resolution(pattern, ForagerAction.ActionType.FLOWER_ACTION, actionNameCandidates(itemName));
    }

    /** Last path segment of a gfx resource path (e.g. "gfx/invobjs/herbs/blueberry" -&gt;
     *  "blueberry"). */
    private static String resourceShortName(String resourcePath) {
        int slash = resourcePath.lastIndexOf('/');
        return slash >= 0 ? resourcePath.substring(slash + 1) : resourcePath;
    }

    /**
     * Candidate flower-menu option string(s) for a gob-linked item. If the item falls into a
     * category with a string already confirmed correct (see {@link VSpec#VERIFIED_CATEGORY_ACTION}),
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
            String action = VSpec.VERIFIED_CATEGORY_ACTION.get(cat);
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
     * Last-resort fallback for the rare case {@link #resolve} has no resource path to slice at
     * all (e.g. a typed custom name with no catalogue icon match) - builds a comma-separated set
     * of candidate substrings to match against a gob's resource path, widest/most-specific first:
     * the literal name, the name with spaces removed (multi-word display names never appear as-is
     * in a resource path, which has none), and singular versions of both (strip one trailing "s")
     * since resource paths are consistently singular even when the item name is plural.
     * Duplicates are dropped; {@link ForagerAction#toNAlias()} matches on ANY of these against the
     * gob's name.
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
        // Overrides whatever icon-layer resource ItemTex.save derived (which, for a Layered/
        // composite sprite, may not even be set) with the item's own actual resource - this is
        // what sourceItemResource needs to be for Maintain's inventory-count check to work, since
        // an item's display name (e.g. "Unripe Chestnut" vs "Chestnut") varies by growth/quality
        // stage while its underlying invobj resource doesn't. Icon rendering still prefers
        // "layer" over "static" (see ItemTex.create), so this doesn't change how the icon looks.
        String itemResourcePath = (item.res != null && item.res.get() != null) ? item.res.get().name : null;
        if (itemResourcePath != null) {
            res.put("static", itemResourcePath);
        }
        addResolved(name, res, resolve(name, itemResourcePath));
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
        String itemResourcePath = iconRes.has("static") ? iconRes.getString("static") : null;
        addResolved(name, iconRes, resolve(name, itemResourcePath));
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
        Resolution res = resolve(typedName, iconPath);
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
        addPlaceholderIcon(itemName, placeholder, action.actionType == ForagerAction.ActionType.FLOWER_ACTION);
        notifyChanged();
    }

    // BaseIngredientContainer.addIcon() always goes through ItemTex.create(), which can't produce
    // a placeholder image - this adds the icon item directly instead, mirroring addIcon()'s own
    // bookkeeping (items/icons lists, grid position, scroll bounds). Shared by addResolvedPlaceholder
    // (a fresh entry, which also owns creating+registering the ForagerAction itself) and load()
    // (redrawing an entry whose ForagerAction already exists).
    private IconItem addPlaceholderIcon(String name, BufferedImage img, boolean isFlowerAction) {
        items.add(new Ingredient(name, img));
        IconItem it = add(new IconItem(name, img, this), gridPos(items.size() - 1));
        it.basec = new Coord(it.c);
        it.setFlowerAction(isFlowerAction);
        icons.add(it);
        updateScrollRange();
        return it;
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
        // Flag the flower badge and any saved Maintain cap on the icon just added, per this entry.
        if (!icons.isEmpty() && res != null && res.has("name")) {
            String name = res.getString("name");
            for (ForagerAction action : actions) {
                if (name.equals(action.sourceItemName)) {
                    IconItem it = icons.get(icons.size() - 1);
                    it.setFlowerAction(action.actionType == ForagerAction.ActionType.FLOWER_ACTION);
                    restoreMaintainBadge(it, action);
                    break;
                }
            }
        }
    }

    /** Restores the shared Threshold/Maintain badge (see IconItem.SetThreshold) onto a freshly
     *  (re)drawn icon from its entry's saved cap, mirroring IngredientContainer's own restore of
     *  a saved threshold onto a freshly-added icon. */
    private static void restoreMaintainBadge(IconItem it, ForagerAction action) {
        if (action.maintainQuantity >= 0) {
            it.isThreshold = true;
            it.val = action.maintainQuantity;
            it.q = new TexI(nurgling.NStyle.iiqual.render(String.valueOf(action.maintainQuantity)).img);
        }
    }

    @Override
    public void setMaintainQuantity(String itemName, int quantity) {
        for (ForagerAction action : actions) {
            if (itemName.equals(action.sourceItemName)) {
                action.maintainQuantity = quantity;
                break;
            }
        }
        notifyChanged();
    }

    @Override
    public int getMaintainQuantity(String itemName) {
        for (ForagerAction action : actions) {
            if (itemName.equals(action.sourceItemName)) {
                return action.maintainQuantity;
            }
        }
        return -1;
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
                IconItem it = addPlaceholderIcon(action.sourceItemName, placeholderIcon(action.sourceItemName), isFlowerAction);
                restoreMaintainBadge(it, action);
            } else {
                addIcon(iconRes);
            }
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
                updated.maintainQuantity = old.maintainQuantity;
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
