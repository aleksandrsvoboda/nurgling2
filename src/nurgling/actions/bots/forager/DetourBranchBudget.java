package nurgling.actions.bots.forager;

/** How many more detour hops (in count and cumulative distance) one gob-collection episode may still branch off the route. */
public class DetourBranchBudget {
    private int branchesRemaining;
    private double distanceRemainingWorldUnits;
    private final boolean branchesCapped;
    private final boolean distanceCapped;

    /** maxBranches/maxBranchDistanceTiles: -1 = unlimited. */
    public DetourBranchBudget(int maxBranches, int maxBranchDistanceTiles) {
        this.branchesCapped = maxBranches >= 0;
        this.branchesRemaining = maxBranches;
        this.distanceCapped = maxBranchDistanceTiles >= 0;
        this.distanceRemainingWorldUnits = maxBranchDistanceTiles * haven.MCache.tilesz.x;
    }

    public boolean canBranch() {
        if (branchesCapped && branchesRemaining <= 0) return false;
        if (distanceCapped && distanceRemainingWorldUnits <= 0) return false;
        return true;
    }

    /** Call once per hop actually taken, with that hop's distance in world units. */
    public void spend(double worldUnitsHopDistance) {
        if (branchesCapped) branchesRemaining--;
        if (distanceCapped) distanceRemainingWorldUnits -= worldUnitsHopDistance;
    }
}
