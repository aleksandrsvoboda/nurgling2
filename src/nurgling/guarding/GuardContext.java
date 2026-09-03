package nurgling.guarding;

import haven.Gob;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.conf.NAreaRad;

import java.util.ArrayList;

/** Shared read access every {@link GuardTrigger#check(GuardContext)} call gets. */
public final class GuardContext {
    public final NGameUI gui;
    public final boolean ignoreBats;

    public GuardContext(NGameUI gui, boolean ignoreBats) {
        this.gui = gui;
        this.ignoreBats = ignoreBats;
    }

    public Gob player() {
        return NUtils.player();
    }

    @SuppressWarnings("unchecked")
    public ArrayList<NAreaRad> animalRads() {
        ArrayList<NAreaRad> rads = (ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad);
        return rads != null ? rads : new ArrayList<>();
    }
}
