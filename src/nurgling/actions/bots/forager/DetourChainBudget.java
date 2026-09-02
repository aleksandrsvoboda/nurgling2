package nurgling.actions.bots.forager;

/**
 * How many more detour hops (a "chain" - the user's own term for a sequence of off-route
 * waypoints chased while collecting) one gob-collection episode is still allowed to take, in
 * both hop count and cumulative distance. Created fresh per episode (a local variable inside
 * Forager.collectNearbyActionableGobs, never an instance field - episodes are sequential, never
 * nested) and only ever touched by the bot thread itself, so it needs none of
 * GuardingProfile.reconcileWithRegistry()'s "return a new list, don't mutate in place" discipline
 * that state shared with a background thread would.
 */
public class DetourChainBudget {
    private int chainsRemaining;
    private double distanceRemainingWorldUnits;
    private final boolean chainsCapped;
    private final boolean distanceCapped;

    /**
     * @param maxChains -1 = unlimited hops
     * @param maxChainDistanceTiles -1 = unlimited cumulative distance, otherwise in tiles
     */
    public DetourChainBudget(int maxChains, int maxChainDistanceTiles) {
        this.chainsCapped = maxChains >= 0;
        this.chainsRemaining = maxChains;
        this.distanceCapped = maxChainDistanceTiles >= 0;
        this.distanceRemainingWorldUnits = maxChainDistanceTiles * haven.MCache.tilesz.x;
    }

    public boolean canChain() {
        if (chainsCapped && chainsRemaining <= 0) return false;
        if (distanceCapped && distanceRemainingWorldUnits <= 0) return false;
        return true;
    }

    /** Call once per hop actually taken, with that hop's distance in world units. */
    public void spend(double worldUnitsHopDistance) {
        if (chainsCapped) chainsRemaining--;
        if (distanceCapped) distanceRemainingWorldUnits -= worldUnitsHopDistance;
    }
}
