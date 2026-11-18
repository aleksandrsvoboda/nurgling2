package nurgling.tools;

import nurgling.cookbook.GenericTypes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Herbs a recipe's Spices input accepts that are flowers too. */
class SpicesCategoryTest {
    @Test
    void bloodSternIsAFlowerAndASpice() {
        List<String> cats = VSpec.getCategory("Blood Stern");
        assertTrue(cats.contains("Flower"), cats.toString());
        assertTrue(cats.contains("Spices"), cats.toString());
    }

    @Test
    void tansyIsAFlowerAndASpice() {
        List<String> cats = VSpec.getCategory("Tansy");
        assertTrue(cats.contains("Flower"), cats.toString());
        assertTrue(cats.contains("Spices"), cats.toString());
    }

    @Test
    void cookbookSpicesListBloodSternAndTansy() throws IOException {
        GenericTypes types = GenericTypes.bundled();
        assertTrue(types.contains("Spices", "Blood Stern"));
        assertTrue(types.contains("Spices", "Tansy"));
    }
}
