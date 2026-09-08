package nurgling.guarding;

import haven.MCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every known guard type, registered once here; Forager Settings' Guarding section builds its rows from {@link #preflightIds()}/{@link #inflightIds()}. */
public final class GuardRegistry {
    private static final Map<String, GuardSpec> specs = new LinkedHashMap<>();
    private static final List<String> preflightIds = new ArrayList<>();
    private static final List<String> inflightIds = new ArrayList<>();

    private GuardRegistry() {}

    public static void register(GuardSpec spec, boolean preflight, boolean inflight) {
        specs.put(spec.id, spec);
        if (preflight && !preflightIds.contains(spec.id)) {
            preflightIds.add(spec.id);
        }
        if (inflight && !inflightIds.contains(spec.id)) {
            inflightIds.add(spec.id);
        }
    }

    public static GuardSpec get(String id) {
        return specs.get(id);
    }

    public static List<String> preflightIds() {
        return Collections.unmodifiableList(preflightIds);
    }

    public static List<String> inflightIds() {
        return Collections.unmodifiableList(inflightIds);
    }

    static {
        register(new GuardSpec("low_energy", "Low energy below",
                        Collections.singletonList(new GuardInput("threshold", GuardInput.Kind.PERCENT, "%", 22)),
                        settings -> new LowEnergyTrigger(settings.getOrDefault("threshold", 22.0) / 100.0)),
                true, true);

        register(new GuardSpec("low_hp", "Low HHP (hard hitpoints) below",
                        Collections.singletonList(new GuardInput("threshold", GuardInput.Kind.PERCENT, "%", 50)),
                        settings -> new LowHpTrigger(settings.getOrDefault("threshold", 50.0) / 100.0)),
                true, true);

        register(new GuardSpec("full_shp", "Low SHP (soft hitpoints) below",
                        Collections.singletonList(new GuardInput("threshold", GuardInput.Kind.PERCENT, "% of HHP", 85)),
                        settings -> new LowShpTrigger(settings.getOrDefault("threshold", 85.0) / 100.0)),
                true, true);

        register(new GuardSpec("wound_severity", "Swamp Fever Threshold",
                        Collections.singletonList(new GuardInput("threshold", GuardInput.Kind.COUNT, "dmg", 4)),
                        settings -> new WoundSeverityTrigger((int) (double) settings.getOrDefault("threshold", 4.0))),
                true, false);

        register(new GuardSpec("stuck", "Stuck - moved less than",
                        Arrays.asList(
                                new GuardInput("distance", GuardInput.Kind.TILES, "tiles in", 3),
                                new GuardInput("timeout", GuardInput.Kind.SECONDS, "s", 10)),
                        // distance is configured in tiles, but StuckTrigger compares world units.
                        settings -> new StuckTrigger(
                                settings.getOrDefault("distance", 3.0) * MCache.tilesz.x,
                                (long) (settings.getOrDefault("timeout", 10.0) * 1000))),
                false, true);

        register(new GuardSpec("unknown_player", "Unknown/hostile player nearby",
                        Collections.emptyList(),
                        settings -> new UnknownPlayerTrigger()),
                false, true);

        register(new GuardSpec("dangerous_animal", "Dangerous animal nearby",
                        Collections.emptyList(),
                        settings -> new DangerousAnimalTrigger()),
                false, true);
    }
}
