package nurgling.tools;

import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The fuel zones, one per burner.
 * <p>
 * Fuel used to be a single zone shared by every bot that burns anything, so one
 * {@code Fuel(Branch)} pile fed the kilns, the ovens, the cauldrons and the stack furnaces
 * alike. Each burner now has its own zone, which lets the pile sit next to the station that
 * eats it. The fuel <i>material</i> stays where it was, in the specialisation's subtype.
 * <p>
 * The zones follow the pattern the codebase already uses for shared resources that needed
 * splitting - {@code water} / {@code waterForTrees} / {@code tanning}, {@code soilForTrees} /
 * {@code soilDump}, and {@code smokedlog}, which is exactly "fuel (Log) for the smoke shed"
 * carved out of {@code fuel} - rather than adding a second dimension to
 * {@link NArea.Specialisation}. An unknown specialisation <i>name</i> survives a round trip
 * through an older client untouched (it renders as {@code ???fuelKiln???}), whereas an
 * unknown extra field would be dropped on save and the zone would silently go back to being
 * generic.
 * <p>
 * This table is the only place the zones are listed: {@link Specialisation} and
 * {@link SpecialisationData} both build themselves from it.
 */
public class FuelZones {
    /** Shared by every fuel zone - they are told apart by name, not by icon. */
    public static final String ICON = "nurgling/categories/fuel";

    /**
     * The shared zone every burner falls back to.
     * <p>
     * Code in the packages {@link SpecialisationUsage} walks should name it through here
     * rather than as {@code SpecName.fuel}: that scan treats any class reaching a
     * {@code SpecName} constant as using it, so a mention inside something as widely shared
     * as {@link nurgling.actions.Validator} would put every bot in the village under the
     * Fuel zone's "used by" tooltip.
     */
    public static final Specialisation.SpecName GENERIC = Specialisation.SpecName.fuel;

    public static class Zone {
        /** The specialisation a zone carries to be this burner's fuel. */
        public final Specialisation.SpecName spec;
        /** The station this fuel is for, for error messages and documentation. */
        public final Specialisation.SpecName station;
        public final String prettyName;

        Zone(Specialisation.SpecName spec, Specialisation.SpecName station, String prettyName) {
            this.spec = spec;
            this.station = station;
            this.prettyName = prettyName;
        }
    }

    public static final List<Zone> all;

    static {
        ArrayList<Zone> zones = new ArrayList<>();
        zones.add(new Zone(Specialisation.SpecName.fuelSmelter, Specialisation.SpecName.smelter, "Fuel: Smelter"));
        zones.add(new Zone(Specialisation.SpecName.fuelSteelbox, Specialisation.SpecName.crucibles, "Fuel: Steelbox"));
        zones.add(new Zone(Specialisation.SpecName.fuelFforge, Specialisation.SpecName.fforge, "Fuel: Finery Forge"));
        zones.add(new Zone(Specialisation.SpecName.fuelKiln, Specialisation.SpecName.kiln, "Fuel: Kiln"));
        zones.add(new Zone(Specialisation.SpecName.fuelOven, Specialisation.SpecName.ovens, "Fuel: Oven"));
        zones.add(new Zone(Specialisation.SpecName.fuelCauldron, Specialisation.SpecName.boiler, "Fuel: Cauldron"));
        zones.add(new Zone(Specialisation.SpecName.fuelFireplace, Specialisation.SpecName.pow, "Fuel: Fire Place"));
        zones.add(new Zone(Specialisation.SpecName.fuelCrucible, Specialisation.SpecName.crucible, "Fuel: Crucible"));
        zones.add(new Zone(Specialisation.SpecName.fuelTarkiln, Specialisation.SpecName.tarkiln, "Fuel: Tarkiln"));
        all = Collections.unmodifiableList(zones);
    }

    /** The zone for a specialisation, or null if it is not one of the fuel zones. */
    public static Zone of(Specialisation.SpecName spec) {
        if(spec == null)
            return(null);
        for(Zone zone : all) {
            if(zone.spec == spec)
                return(zone);
        }
        return(null);
    }

    /**
     * Resolves the area a burner takes its fuel from.
     * <p>
     * A zone tagged for a station is reserved: only that station draws from it. A bot whose
     * own zone is not set falls back to the untagged {@code fuel} zone of the same material,
     * which is what every setup that predates the split has.
     * <p>
     * Both steps try the near lookup before the global one. The near lookup needs the zone's
     * grids to be loaded, so on its own it reports a zone one screen too far away as "not
     * set" - the reason this used to fail for anyone whose fuel was not right next to the
     * station. Callers that act on the result must navigate to it first.
     *
     * @param zone     the burner's fuel zone, or null to look only at the generic one
     * @param material the fuel item, or null/empty to match a zone of any material
     */
    public static NArea find(Specialisation.SpecName zone, String material) {
        NArea area = null;
        if(zone != null)
            area = findOne(zone.toString(), material);
        if(area == null)
            area = findOne(GENERIC.toString(), material);
        return(area);
    }

    private static NArea findOne(String name, String material) {
        if((material == null) || material.isEmpty()) {
            NArea area = NContext.findSpec(name);
            return((area != null) ? area : NContext.findSpecGlobal(name));
        }
        NArea area = NContext.findSpec(name, material);
        return((area != null) ? area : NContext.findSpecGlobal(name, material));
    }

    /** "Fuel: Kiln (Branch)", for messages. */
    public static String describe(Specialisation.SpecName zone, String material) {
        Zone z = of(zone);
        String name = (z != null) ? z.prettyName : "Fuel";
        return((material == null || material.isEmpty()) ? name : (name + " (" + material + ")"));
    }
}
