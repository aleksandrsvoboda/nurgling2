package nurgling.actions.bots;

import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Equip;
import nurgling.actions.Results;
import nurgling.tools.NAlias;

public class EquipShieldSword implements Action {
    // Substring keys so every shield and every "...man's Sword" variant matches,
    // instead of a hardcoded list that goes stale with each new weapon.
    private static final String[] SHIELDS = {"Shield"};
    private static final String[] SWORDS = {"Bronze Sword", "man's Sword"};

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        new Equip(new NAlias(SHIELDS), new NAlias(SWORDS)).run(gui);
        new Equip(new NAlias(SWORDS), new NAlias(SHIELDS)).run(gui);
        return Results.SUCCESS();
    }
}
