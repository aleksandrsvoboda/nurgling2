package nurgling.actions.cart;

import haven.Gob;
import haven.Resource;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.actions.bots.SelectGob;
import nurgling.i18n.L10n;
import nurgling.tools.VehicleMarker;

/**
 * Asks which cart to haul with, when carts are allowed at all.
 *
 * <p>Click an empty cart and the bot hauls with it; click anything else, the ground, or press Esc and it
 * carries by hand. A cart that still holds cargo is refused with a message. A cart already tied to the
 * character is used without asking. With {@link NConfig.Key#allowCarts} off nothing is asked.
 */
public final class SelectCart {
    private SelectCart() {
    }

    public static boolean allowed() {
        return Boolean.TRUE.equals(NConfig.get(NConfig.Key.allowCarts));
    }

    /** The cart to haul with, or null to carry by hand. */
    public static Gob ask(NGameUI gui) throws InterruptedException {
        if (!allowed())
            return null;

        Gob picked = CartTow.ownTowedCart(gui);
        if (picked == null) {
            gui.msg(L10n.get("cart.select_prompt"));
            SelectGob sel = new SelectGob(Resource.loadsimg("baubles/inputVeh"));
            sel.run(gui);
            picked = sel.getResult();
        }
        if (picked == null || !VehicleMarker.isCart(picked)) {
            gui.msg(L10n.get("cart.by_hand"));
            return null;
        }
        if (VehicleMarker.cargoCount(picked) > 0) {
            gui.msg(L10n.get("cart.not_empty"));
            return null;
        }
        return picked;
    }

}
