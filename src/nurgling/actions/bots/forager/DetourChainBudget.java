package nurgling.actions.bots.forager;

/** How many more detour hops (in count and cumulative distance) one gob-collection episode may still take. */
public class DetourChainBudget {
    private int chainsRemaining;
    private double distanceRemainingWorldUnits;
    private final boolean chainsCapped;
    private final boolean distanceCapped;

    /** maxChains/maxChainDistanceTiles: -1 = unlimited. */
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
