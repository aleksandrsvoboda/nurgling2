package nurgling.tools;

import haven.Gob;

/**
 * Bit layout of a towable vehicle's model marker ({@code NGob.getModelAttribute()}, i.e. the
 * {@code ResDrawable} sdt).
 *
 * <p>Observed directly on a cart: parked empty reads 1, right-clicked into tow it becomes 2;
 * parked with one item reads 5, towed with one item reads 6. That places the tow state in the
 * low two bits and leaves everything from bit 2 up for cargo &mdash; which is exactly what
 * {@link nurgling.actions.IsVehicleFull} and {@code TakeFromVehicleSlot.countCargoItems} already
 * assume, both starting their cargo scan at {@code mul = 4} and doubling for six slots.
 * So {@code 4 + 1 = 5} and {@code 4 + 2 = 6} check out against existing code.
 *
 * <p>This matters because it is the <em>only</em> way to ask "is this vehicle currently towed?"
 * at rest. The gob's {@code Moving} attribute is no help: a towed cart carries {@code Homing}
 * only while it is catching up, and nothing at all while the character stands still &mdash; which
 * is precisely when a bot plans its path.
 *
 * <p><b>Confirmed for carts only.</b> Whether a plow or wheelbarrow uses the same two bits has not
 * been measured, so prefer {@link #markerOf} plus a change-detection wait when the vehicle type is
 * not known to be a cart.
 *
 * <p><b>Beware the sign.</b> {@code ResDrawable.calcMarker()} reads {@code sdt.rbuf[0]} as a Java
 * {@code byte}, which is signed, so every marker of 128 or more arrives sign-extended and negative.
 * A full towed cart is {@code 2 | 252 = 254}, which comes back as {@code -2}; parked-and-full comes
 * back as {@code -3}. Two's complement preserves the low bits, so the mask tests below are still
 * correct on those values &mdash; but any {@code marker >= 0} guard silently drops every loaded
 * cart, which is exactly the failure this class was first written with. Other users of
 * {@code getModelAttribute()} may have the same latent problem for markers past 127.
 */
public class VehicleMarker {

    /** Bit 0: the vehicle is standing parked. */
    public static final long MASK_PARKED = 1;
    /** Bit 1: the vehicle is tied to a character and following it. */
    public static final long MASK_TOWED = 2;
    /** Cargo occupies bits 2 and up, one per slot. */
    public static final long CARGO_BIT0 = 4;
    /** A cart has six cargo slots. */
    public static final int CART_CARGO_SLOTS = 6;

    /** {@code calcMarker()} returns this when the gob carries no sdt to read. */
    public static final long UNKNOWN = -1;

    private VehicleMarker() {
    }

    public static long markerOf(Gob vehicle) {
        if (vehicle == null || vehicle.ngob == null)
            return -1;
        return vehicle.ngob.getModelAttribute();
    }

    /**
     * Whether we have a marker to read at all. Only the no-sdt sentinel is rejected: negative
     * values are ordinary markers past 127, not errors. A genuine 0xFF would also present as
     * {@code -1}, but that would mean parked and towed simultaneously, which is not a real state.
     */
    public static boolean known(long marker) {
        return marker != UNKNOWN;
    }

    /** Whether the vehicle is currently tied to and following a character. Verified for carts. */
    public static boolean isTowed(Gob vehicle) {
        long marker = markerOf(vehicle);
        return known(marker) && (marker & MASK_TOWED) != 0;
    }

    /** Whether the vehicle is standing parked. Verified for carts. */
    public static boolean isParked(Gob vehicle) {
        long marker = markerOf(vehicle);
        return known(marker) && (marker & MASK_PARKED) != 0;
    }

    /** The marker as an unsigned byte, for display. Raw values past 127 read back negative. */
    public static int unsigned(long marker) {
        return (int) (marker & 0xFF);
    }

    /** Number of occupied cargo slots. */
    public static int cargoCount(Gob vehicle, int slots) {
        long marker = markerOf(vehicle);
        if (!known(marker))
            return 0;
        int count = 0;
        long mask = CARGO_BIT0;
        for (int i = 0; i < slots; i++) {
            if ((marker & mask) != 0)
                count++;
            mask <<= 1;
        }
        return count;
    }
}
