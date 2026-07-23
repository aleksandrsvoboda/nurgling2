package nurgling.actions;

import nurgling.conf.CropRegistry;
import nurgling.tools.NAlias;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityFarmerContractE2ETest {
    @ParameterizedTest(name = "{0}: one-tile product is stored and only {2} is planted")
    @MethodSource("p0SingleTileCrops")
    @Tag("P0")
    void single_tile_quality_cycle_deposits_product_before_planting(
            String cropResource,
            String byproduct,
            String plantingMaterial,
            boolean stackedPlantingMaterial
    ) throws InterruptedException {
        FarmerPort port = new FarmerPort(byproduct);
        NAlias plantingMaterials = CropRegistry.getQualityPlantingMaterials(new NAlias(cropResource));
        List<String> plantingOrder = new ArrayList<>();

        Results completed = new QualityFarmerCycleContract(
                ignored -> GroundItemCollectionContract.collectAndDeposit(port)
                        ? Results.SUCCESS()
                        : Results.FAIL(),
                ignored -> {
                    QualityPlantingContract.Material<String> validMaterial =
                            stackedPlantingMaterial
                                    ? QualityPlantingContract.Material.stack(
                                            plantingMaterial,
                                            plantingMaterial,
                                            5
                                    )
                                    : QualityPlantingContract.Material.individual(
                                            plantingMaterial,
                                            plantingMaterial
                                    );
                    List<QualityPlantingContract.Material<String>> backpack = Arrays.asList(
                            QualityPlantingContract.Material.individual(byproduct, byproduct),
                            validMaterial
                    );
                    plantingOrder.addAll(
                            QualityPlantingContract.plan(backpack, plantingMaterials, 1, 5)
                    );
                    port.events.add("plant:" + plantingMaterial);
                    return plantingOrder.size() == 1 ? Results.SUCCESS() : Results.FAIL();
                }
        ).run(null);

        assertTrue(completed.IsSuccess());
        assertEquals(Arrays.asList(byproduct), port.productStorage);
        assertEquals(Arrays.asList(plantingMaterial), plantingOrder);
        assertEquals(
                Arrays.asList("deposit:" + byproduct, "plant:" + plantingMaterial),
                port.events
        );
        assertTrue(plantingMaterials.matches(plantingMaterial));
        assertFalse(plantingMaterials.matches(byproduct));
    }

    @Test
    @Tag("P1")
    void failed_product_deposit_prevents_quality_planting() throws InterruptedException {
        FarmerPort port = new FarmerPort("Hemp Fibres");
        port.depositSucceeds = false;
        AtomicBoolean plantingStarted = new AtomicBoolean(false);

        Results completed = new QualityFarmerCycleContract(
                ignored -> GroundItemCollectionContract.collectAndDeposit(port)
                        ? Results.SUCCESS()
                        : Results.FAIL(),
                ignored -> {
                    plantingStarted.set(true);
                    return Results.SUCCESS();
                }
        ).run(null);

        assertFalse(completed.IsSuccess());
        assertFalse(plantingStarted.get());
    }

    @ParameterizedTest(name = "{0} rejects its similarly named byproduct")
    @MethodSource("p1ByproductBoundaries")
    @Tag("P1")
    void quality_planting_contract_rejects_secondary_products(
            String cropResource,
            String validMaterial,
            String byproduct
    ) {
        NAlias plantingMaterials = CropRegistry.getQualityPlantingMaterials(new NAlias(cropResource));

        assertTrue(plantingMaterials.matches(validMaterial));
        assertFalse(plantingMaterials.matches(byproduct));
    }

    @ParameterizedTest(name = "{0} has an exact quality-farmer planting contract")
    @MethodSource("qualityFarmerCrops")
    @Tag("P1")
    void every_quality_farmer_crop_has_registered_exact_planting_material(
            String cropResource,
            String plantingMaterial
    ) {
        NAlias plantingMaterials = CropRegistry.getQualityPlantingMaterials(new NAlias(cropResource));

        assertFalse(plantingMaterials.keys.isEmpty());
        assertTrue(plantingMaterials.matches(plantingMaterial));
        assertFalse(plantingMaterials.matches(plantingMaterial + " Byproduct"));
    }

    private static Stream<Arguments> p0SingleTileCrops() {
        return Stream.of(
                Arguments.of("plants/beet", "Beetroot Leaves", "Beetroot", false),
                Arguments.of("plants/hemp", "Hemp Fibres", "Hemp Seeds", true),
                Arguments.of("plants/flax", "Flax Fibres", "Flax Seeds", true),
                Arguments.of("plants/lettuce", "Head of Lettuce", "Lettuce Seeds", true),
                Arguments.of("plants/pumpkin", "Pumpkin", "Pumpkin Seeds", true)
        );
    }

    private static Stream<Arguments> p1ByproductBoundaries() {
        return Stream.of(
                Arguments.of("plants/beet", "Beetroot", "Beetroot Leaves"),
                Arguments.of("plants/greenkale", "Green Kale Seeds", "Green Kale"),
                Arguments.of("plants/lettuce", "Lettuce Seeds", "Head of Lettuce"),
                Arguments.of("plants/pipeweed", "Pipeweed Seeds", "Fresh Leaf of Pipeweed"),
                Arguments.of("plants/poppy", "Poppy Seeds", "Poppy Flower"),
                Arguments.of("plants/pumpkin", "Pumpkin Seeds", "Pumpkin Flesh")
        );
    }

    private static Stream<Arguments> qualityFarmerCrops() {
        return Stream.of(
                Arguments.of("plants/barley", "Barley Seeds"),
                Arguments.of("plants/beet", "Beetroot"),
                Arguments.of("plants/carrot", "Carrot Seeds"),
                Arguments.of("plants/flax", "Flax Seeds"),
                Arguments.of("plants/garlic", "Garlic"),
                Arguments.of("plants/greenkale", "Green Kale Seeds"),
                Arguments.of("plants/hemp", "Hemp Seeds"),
                Arguments.of("plants/leek", "Leek Seeds"),
                Arguments.of("plants/lettuce", "Lettuce Seeds"),
                Arguments.of("plants/millet", "Millet Seeds"),
                Arguments.of("plants/pipeweed", "Pipeweed Seeds"),
                Arguments.of("plants/poppy", "Poppy Seeds"),
                Arguments.of("plants/pumpkin", "Pumpkin Seeds"),
                Arguments.of("plants/redonion", "Red Onion"),
                Arguments.of("plants/turnip", "Turnip Seeds"),
                Arguments.of("plants/wheat", "Wheat Seeds"),
                Arguments.of("plants/yellowonion", "Yellow Onion")
        );
    }

    private static final class FarmerPort implements GroundItemCollectionContract.Port<String> {
        private final Deque<String> fieldProducts = new ArrayDeque<>();
        private final List<String> backpack = new ArrayList<>();
        private final List<String> productStorage = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private String pendingPickup;
        private boolean depositSucceeds = true;

        private FarmerPort(String product) {
            fieldProducts.add(product);
        }

        @Override
        public String nextGroundItem() {
            return fieldProducts.peekFirst();
        }

        @Override
        public boolean inventoryMustBeDeposited() {
            return false;
        }

        @Override
        public boolean depositInventory() {
            if (!depositSucceeds) {
                events.add("deposit-failed");
                return false;
            }
            productStorage.addAll(backpack);
            for (String item : backpack) {
                events.add("deposit:" + item);
            }
            backpack.clear();
            return true;
        }

        @Override
        public void approach(String item) {
        }

        @Override
        public int visibleInventoryCount() {
            return backpack.size();
        }

        @Override
        public void pickUp(String item) {
            fieldProducts.removeFirst();
            pendingPickup = item;
        }

        @Override
        public void awaitInventoryCountAtLeast(int minimumCount) {
            backpack.add(pendingPickup);
            pendingPickup = null;
            if (backpack.size() < minimumCount) {
                throw new AssertionError("pickup was not visible");
            }
        }
    }
}
