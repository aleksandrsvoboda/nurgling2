package nurgling.conf;

import nurgling.routes.ForagerAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NForagerPropMergeTest {

    private static NForagerProp character(String name, String profile, String... picks) {
        NForagerProp prop = new NForagerProp("acc", name);
        ArrayList<ForagerAction> actions = new ArrayList<>();
        for (String pick : picks) {
            actions.add(new ForagerAction(pick, ForagerAction.ActionType.PICK));
        }
        prop.actionsProfiles.put(profile, actions);
        prop.currentActionsProfile = profile;
        prop.presets.get("Default").actionsProfileName = profile;
        return prop;
    }

    @Test
    void noCharactersGivesDefaults() {
        NForagerProp shared = NForagerProp.merge(List.of());
        assertTrue(shared.presets.containsKey("Default"));
        assertTrue(shared.actionsProfiles.containsKey("Default"));
        assertTrue(shared.guardingProfiles.containsKey("Default"));
    }

    @Test
    void sameNameSameContentsIsKeptOnce() {
        NForagerProp shared = NForagerProp.merge(List.of(
                character("Bob", "Berries", "blueberry"),
                character("Ann", "Berries", "blueberry")));
        assertEquals(List.of("Berries", "Default"), sorted(shared.actionsProfiles.keySet()));
        assertEquals(1, shared.presets.size());
    }

    @Test
    void clashGetsTheCharacterNameAndLatestKeepsThePlainName() {
        // Ann is last in the list, so she saved most recently and keeps "Berries".
        NForagerProp shared = NForagerProp.merge(List.of(
                character("Bob", "Berries", "blueberry"),
                character("Ann", "Berries", "raspberry")));
        assertEquals("raspberry", shared.actionsProfiles.get("Berries").get(0).targetObjectPattern);
        assertEquals("blueberry", shared.actionsProfiles.get("Berries (Bob)").get(0).targetObjectPattern);
        assertEquals("Berries", shared.currentActionsProfile);

        // Bob's Default preset pointed at his Berries, so it clashes too and follows the rename.
        assertEquals("Berries", shared.presets.get("Default").actionsProfileName);
        assertEquals("Berries (Bob)", shared.presets.get("Default (Bob)").actionsProfileName);
    }

    @Test
    void sharedEntryDoesNotHoldTheCharactersObjects() {
        NForagerProp bob = character("Bob", "Herbs", "nettle");
        NForagerProp shared = NForagerProp.merge(List.of(bob));
        assertNotSame(bob.actionsProfiles.get("Herbs"), shared.actionsProfiles.get("Herbs"));
        shared.actionsProfiles.get("Herbs").clear();
        assertEquals(1, bob.actionsProfiles.get("Herbs").size());
    }

    private static List<String> sorted(java.util.Collection<String> names) {
        ArrayList<String> list = new ArrayList<>(names);
        java.util.Collections.sort(list);
        return list;
    }
}
