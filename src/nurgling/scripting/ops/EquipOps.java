package nurgling.scripting.ops;

import haven.*;
import nurgling.NGItem;
import nurgling.scripting.api.*;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;
import org.json.JSONObject;

import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/** The equipment window (worn and held items). */
public final class EquipOps {

    private static NEquipory equipory(OpContext c) {
        Window ew = c.gui.equwnd;
        if (ew != null) {
            for (Widget w = ew.lchild; w != null; w = w.prev) {
                if (w instanceof NEquipory) return (NEquipory) w;
            }
        }
        throw ApiError.notSupported("the equipment window is not available yet");
    }

    @ApiOp(name = "equip.slots", kind = QUERY,
            doc = "Every equipment slot with the item in it, or null when empty. Slot names: head, accessory, shirt, armor_body, gloves, belt, "
                    + "hand_left, hand_right, ring_left, ring_right, robe, back, pants, armor_leg, cape, boots, store_hat, eyes, mouth, "
                    + "lfoot, rfoot, shoulder, coat. A two-handed tool appears in both hand slots.",
            returns = "dict", returnsDoc = "{slot name: Item|null}", errors = {Errors.NOT_SUPPORTED},
            example = "eq = nc.equip.slots()\nprint(eq[\"hand_right\"][\"name\"] if eq[\"hand_right\"] else \"empty hand\")")
    public static Object slots(OpContext c, Args a) {
        NEquipory eq = equipory(c);
        JSONObject j = new JSONObject();
        for (NEquipory.Slots s : NEquipory.Slots.values()) {
            WItem w = (s.idx < eq.quickslots.length) ? eq.quickslots[s.idx] : null;
            j.put(s.name().toLowerCase(), w == null ? JSONObject.NULL : Codec.item(w));
        }
        return j;
    }

    @ApiOp(name = "equip.find", kind = QUERY,
            doc = "The first equipped item whose display name matches `name` (substring, case-insensitive), or null. Items still loading are skipped.",
            params = {@ApiParam(name = "name", type = "Alias", doc = "Display-name pattern(s), e.g. \"Axe\" or [\"Axe\", \"Saw\"].")},
            returns = "Item|null", errors = {Errors.NOT_SUPPORTED},
            example = "if nc.equip.find(\"Axe\") is None:\n    nc.chat.notify(\"equip an axe\", error=True)")
    public static Object find(OpContext c, Args a) {
        NAlias name = a.alias("name");
        NEquipory eq = equipory(c);
        for (WItem w : eq.quickslots) {
            if (w != null && w.item instanceof NGItem && NParser.checkName(((NGItem) w.item).name(), name))
                return w;
        }
        return null;
    }

    private EquipOps() {}
}
