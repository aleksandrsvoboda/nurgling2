package nurgling.conf;

/**
 * Categories of prospected ground samples placed on the map by the Checker bots
 * (CheckWater, CheckClay). The bots store the raw item name as the mark's resource
 * type, so the mapping is done by name matching.
 */
public enum ProspectKind {
    WATER("maptools.kind.water"),
    SALTWATER("maptools.kind.saltwater"),
    CLAY("maptools.kind.clay"),
    SOIL("maptools.kind.soil"),
    SAND("maptools.kind.sand"),
    OTHER("maptools.kind.other");

    public final String l10nKey;

    ProspectKind(String l10nKey) {
        this.l10nKey = l10nKey;
    }

    /**
     * Classify a mark's resource type (the raw item name, e.g. "Saltwater", "Clay", "Moss").
     * Anything unrecognised lands in OTHER so it always stays controllable from the UI.
     */
    public static ProspectKind of(String resourceType) {
        if(resourceType == null)
            return OTHER;
        String s = resourceType.toLowerCase();
        if(s.contains("saltwater") || s.contains("salt water"))
            return SALTWATER;
        if(s.contains("water"))
            return WATER;
        if(s.contains("clay"))
            return CLAY;
        if(s.contains("soil") || s.contains("dirt"))
            return SOIL;
        if(s.contains("sand"))
            return SAND;
        return OTHER;
    }
}
