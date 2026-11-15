package nurgling.fish;

import haven.Coord;
import nurgling.FishLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FishSurveyIdTest {
    private static final Coord T = Coord.of(12, 34);

    @Test
    void sameInputsSameId() {
        assertEquals(FishLocation.surveyId("p", 7L, T, "Asp", "Fine Fishline", "Bone Hook", "Tin Fly"),
                     FishLocation.surveyId("p", 7L, T, "Asp", "Fine Fishline", "Bone Hook", "Tin Fly"));
    }

    @Test
    void eachGearPartMakesItsOwnRecord() {
        String base = FishLocation.surveyId("p", 7L, T, "Asp", "Fine Fishline", "Bone Hook", "Tin Fly");
        assertNotEquals(base, FishLocation.surveyId("p", 7L, T, "Asp", "Farmer's Fishline", "Bone Hook", "Tin Fly"));
        assertNotEquals(base, FishLocation.surveyId("p", 7L, T, "Asp", "Fine Fishline", "Metal Hook", "Tin Fly"));
        assertNotEquals(base, FishLocation.surveyId("p", 7L, T, "Asp", "Fine Fishline", "Bone Hook", "Woodfish"));
        assertNotEquals(base, FishLocation.surveyId("p", 7L, T, "Bream", "Fine Fishline", "Bone Hook", "Tin Fly"));
        assertNotEquals(base, FishLocation.surveyId("p", 7L, Coord.of(12, 35), "Asp", "Fine Fishline", "Bone Hook", "Tin Fly"));
    }

    @Test
    void neverCollidesWithAManualSave() {
        assertNotEquals(FishLocation.deterministicId("p", 7L, T, "Asp"),
                        FishLocation.surveyId("p", 7L, T, "Asp", null, null, null));
    }
}
