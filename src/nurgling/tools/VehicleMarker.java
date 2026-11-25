package nurgling.tools;

import haven.Gob;

/**
 * Bit layout of a cart's model marker ({@code NGob.getModelAttribute()}, the {@code ResDrawable} sdt).
 *
 * <p>Measured on a cart: parked empty reads 1, towed empty 2, parked with one item 5, towed with one
 * item 6. So bit 0 is parked, bit 1 is towed, and bits 2-7 are the six cargo slots, which is what
 * {@code IsVehicleFull} and {@code TakeFromVehicleSlot} already assume when they start their cargo
 * scan at {@code 4}. This is the only way to tell a towed cart at rest: a towed cart carries
 * {@code Homing} only while it is catching up, and no {@code Moving} attr at all while standing.
 *
 * <p>Confirmed for carts only.
 *
 * <p><b>Beware the sign.</b> {@code calcMarker()} reads a signed byte, so every marker of 128 or more
 * arrives negative (a full towed cart is 254, read back as -2). Mask tests still work; a
 * {@code marker >= 0} guard would silently drop every loaded cart. The only "no marker" value is -1.
 */
public final class VehicleMarker {

    public static final long MASK_PARKED = 1;
    public static final long MASK_TOWED = 2;
    public static final long CARGO_BIT0 = 4;
    public static final int CART_SLOTS = 6;
    public static final long UNKNOWN = -1;

    public static final NAlias CART = new NAlias("gfx/terobjs/vehicle/cart");

    /** "Parked" and "can't read the marker yet" must never be confused: right-click toggles the tie. */
    public enum Tow { TOWED, PARKED, UNKNOWN }

    private VehicleMarker() {
    }

    public static boolean isCart(Gob gob) {
        return gob != null && gob.ngob != null && gob.ngob.name != null && NParser.checkName(gob.ngob.name, CART);
    }

    public static long markerOf(Gob vehicle) {
        if (vehicle == null || vehicle.ngob == null)
            return UNKNOWN;
        return vehicle.ngob.getModelAttribute();
    }

    public static Tow towState(Gob vehicle) {
        long marker = markerOf(vehicle);
        if (marker == UNKNOWN)
            return Tow.UNKNOWN;
        return ((marker & MASK_TOWED) != 0) ? Tow.TOWED : Tow.PARKED;
    }

    /** True only when definitely towed; false covers both parked and unknown. */
    public static boolean isTowed(Gob vehicle) {
        return towState(vehicle) == Tow.TOWED;
    }

    /** Occupied cargo slots; -1 when the marker can't be read yet. */
    public static int cargoCount(Gob cart) {
        long marker = markerOf(cart);
        if (marker == UNKNOWN)
            return -1;
        int count = 0;
        long mask = CARGO_BIT0;
        for (int i = 0; i < CART_SLOTS; i++) {
            if ((marker & mask) != 0)
                count++;
            mask <<= 1;
        }
        return count;
    }
}
