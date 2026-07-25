package nurgling.actions;

import nurgling.NGameUI;

/**
 * Ordering contract for the quality-farmer product and planting phases.
 *
 * <p>Planting may begin only after every loose harvest product has been
 * collected and deposited successfully.</p>
 */
public final class QualityFarmerCycleContract implements Action {
    private final Action collectProducts;
    private final Action plant;

    public QualityFarmerCycleContract(Action collectProducts, Action plant) {
        this.collectProducts = collectProducts;
        this.plant = plant;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Results collection = collectProducts.run(gui);
        if (!collection.IsSuccess()) {
            return Results.FAIL();
        }
        return plant.run(gui);
    }
}
