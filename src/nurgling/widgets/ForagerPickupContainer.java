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

/** Drag-and-drop "what should Forager pick up" editor for one Actions profile; each icon is a {@link ForagerAction} resolved via {@link VSpec}, editable afterward via right-click "Edit Pattern". */
public class ForagerPickupContainer extends BaseIngredientContainer implements TaggableItemContainer {

    // Aliases whatever list load() was last given, so edits here are reflected with no sync step.
    private ArrayList<ForagerAction> actions = new ArrayList<>();

    /** Notified after any edit so the owner can persist immediately; optional. */
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

    // itemResourcePath: the item's own invobj resource path if known, else null.
    private Resolution resolve(String itemName, String itemResourcePath) {
        ArrayList<String> gobs = VSpec.getGobsForItem(itemName);

        if (gobs.isEmpty()) {
            // No tree/bush link - herb/mushroom items share their invobj/terobj resource's short name.
            String pattern = (itemResourcePath != null)
                    ? resourceShortName(itemResourcePath)
                    : herbPatternCandidates(itemName);
            return new Resolution(pattern, ForagerAction.ActionType.PICK, null);
        }
        String pattern = String.join(",", gobs);
        return new Resolution(pattern, ForagerAction.ActionType.FLOWER_ACTION, actionNameCandidates(itemName));
    }

    /** Last path segment of a gfx resource path. */
    private static String resourceShortName(String resourcePath) {
        int slash = resourcePath.lastIndexOf('/');
        return slash >= 0 ? resourcePath.substring(slash + 1) : resourcePath;
    }

    /** Candidate flower-menu strings for a gob-linked item: a verified category action if known, else a best-guess ordered set tried in order at runtime. */
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
            // Flower-menu text only capitalizes the first word of the phrase.
            String lower = name.toLowerCase();
            candidates.add("Pick " + lower);
            candidates.add("Take " + lower);
        }
        return String.join(",", candidates);
    }

    /** Last-resort fallback with no resource path to slice: candidate substrings (name, no-spaces, singular forms) matched against a gob's resource path. */
    private static String herbPatternCandidates(String itemName) {
        String lower = itemName.toLowerCase();

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(lower);

        String noSpaces = lower.replace(" ", "");
        candidates.add(noSpaces);

        if (noSpaces.length() > 1 && noSpaces.endsWith("s")) {
            candidates.add(noSpaces.substring(0, noSpaces.length() - 1));
        }

        // Last word alone, and its singular, as a narrower fallback.
        String[] words = lower.trim().split("\\s+");
        String lastWord = words[words.length - 1];
        candidates.add(lastWord);
        if (lastWord.length() > 1 && lastWord.endsWith("s")) {
            candidates.add(lastWord.substring(0, lastWord.length() - 1));
        }

        return String.join(",", candidates);
    }

    // placeholder: null for the normal icon path; non-null draws a hash-colored placeholder instead.
    private void addResolved(String itemName, JSONObject iconRes, Resolution res, BufferedImage placeholder) {
        // Every lookup here (setMaintainQuantity/getPriority/editItem/delete) matches by name and
        // stops at the first hit, so a second entry sharing a name would be permanently
        // unreachable through this UI - and delete() would orphan its icon, since the base
        // container only removes one icon per name. Adding an already-present item is a no-op.
        for (ForagerAction existing : actions) {
            if (itemName.equals(existing.sourceItemName)) {
                return;
            }
        }
        ForagerAction action = new ForagerAction(res.pattern, res.actionType, res.actionName);
        action.sourceItemName = itemName;
        if (iconRes != null && iconRes.has("static")) {
            action.sourceItemResource = iconRes.getString("static");
        }
        actions.add(action);
        if (placeholder != null) {
            addPlaceholderIcon(itemName, placeholder, action.actionType == ForagerAction.ActionType.FLOWER_ACTION);
        } else {
            addIcon(iconRes);
        }
        notifyChanged();
    }

    @Override
    public boolean drop(Drop ev) {
        NGItem item = (NGItem) ev.src.item;
        String name = item.name();
        JSONObject res = ItemTex.save(item.spr);
        res.put("name", name);
        // Use the item's real resource (not ItemTex.save's derived one) so Maintain's count-by-resource check is stable across display-name variants.
        String itemResourcePath = (item.res != null && item.res.get() != null) ? item.res.get().name : null;
        if (itemResourcePath != null) {
            res.put("static", itemResourcePath);
        }
        addResolved(name, res, resolve(name, itemResourcePath), null);
        return super.drop(ev);
    }

    /** Opens the same searchable item catalogue Area Settings uses, for adding without a real item in hand. */
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
        addResolved(name, iconRes, resolve(name, itemResourcePath), null);
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
            // No icon path, or ItemTex failed to load it - fall back to a stable placeholder.
            iconRes.remove("static");
            addResolved(typedName, iconRes, res, placeholderIcon(typedName));
            return;
        }
        addResolved(typedName, iconRes, res, null);
    }

    // 400px wide container - default gridColumns()=5 wastes most of that.
    @Override
    protected int gridColumns() {
        return 10;
    }

    // addIcon() always goes through ItemTex.create(), which can't produce a placeholder - this mirrors its bookkeeping directly instead.
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
                    restorePriorityBadge(it, action);
                    break;
                }
            }
        }
    }

    /** Restores the shared Threshold/Maintain badge onto a freshly (re)drawn icon from its entry's saved cap. */
    private static void restoreMaintainBadge(IconItem it, ForagerAction action) {
        if (action.maintainQuantity >= 0) {
            it.hasBadge = true;
            it.val = action.maintainQuantity;
            it.q = new TexI(nurgling.NStyle.iiqual.render(String.valueOf(action.maintainQuantity)).img);
        }
    }

    /** Restores the priority badge onto a freshly (re)drawn icon from its entry's saved priority - independent of the shared Threshold/Maintain badge, since both can apply to the same item. */
    private static void restorePriorityBadge(IconItem it, ForagerAction action) {
        if (action.priority >= 0) {
            it.priority = action.priority;
            it.priorityTex = new TexI(nurgling.NStyle.iiqual.render(String.valueOf(action.priority)).img);
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
    public void setPriority(String itemName, int priority) {
        for (ForagerAction action : actions) {
            if (itemName.equals(action.sourceItemName)) {
                action.priority = priority;
                break;
            }
        }
        notifyChanged();
    }

    @Override
    public int getPriority(String itemName) {
        for (ForagerAction action : actions) {
            if (itemName.equals(action.sourceItemName)) {
                return action.priority;
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
        // Only clear entries this widget renders - manual entries (e.g. CHAT_NOTIFY) must survive.
        actions.removeIf(a -> a.sourceItemName != null);
        super.deleteAll();
        notifyChanged();
    }

    /** Redraws from (and starts aliasing) a preset's live action list; CHAT_NOTIFY entries have no sourceItemName and aren't rendered as icons here, but stay in the list. */
    public void load(ArrayList<ForagerAction> liveActions) {
        this.actions = liveActions != null ? liveActions : new ArrayList<>();
        for (IconItem it : icons) {
            it.destroy();
        }
        icons.clear();
        items.clear();

        for (ForagerAction action : this.actions) {
            if (action.sourceItemName == null) {
                if (action.actionType == ForagerAction.ActionType.CHAT_NOTIFY) {
                    continue; // Not created via this widget - not shown here.
                }
                // Orphaned entry - e.g. one created via the old manual "Configure Action" flow,
                // which only ever set pattern/type/name and never sourceItemName. Left alone, this
                // stays invisible and undeletable here forever while still matching gobs at full
                // priority with no Maintain cap possible. Heal it into a normal entry keyed by its
                // own pattern, so it shows up as a placeholder icon the user can inspect and delete.
                action.sourceItemName = (action.targetObjectPattern != null && !action.targetObjectPattern.isEmpty())
                        ? action.targetObjectPattern : "Unknown action";
                notifyChanged();
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
                restorePriorityBadge(it, action);
            } else {
                addIcon(iconRes);
            }
        }
    }

    /** Opens the full manual editor prefilled with this item's current pattern/action, for correcting a wrong or unresolved automatic guess. */
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
                // ActionConfigWindow has no UI for priority at all, so its built ForagerAction
                // always defaults it unset - without this, editing just the pattern silently
                // dropped whatever priority the item had.
                updated.priority = old.priority;
                actions.set(foundIdx, updated);
                for (IconItem it : icons) {
                    if (itemName.equals(it.name)) {
                        it.setFlowerAction(updated.actionType == ForagerAction.ActionType.FLOWER_ACTION);
                        restoreMaintainBadge(it, updated);
                        restorePriorityBadge(it, updated);
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
