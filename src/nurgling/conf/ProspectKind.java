package nurgling.conf;

import nurgling.actions.bots.MasterMiner;

/**
 * Categories of resource marks placed on the map: ground samples from the Checker bots
 * (CheckWater, CheckClay) and mined finds from Master Miner. The bots store the raw item
 * name as the mark's resource type, so the mapping is done by name matching.
 */
public enum ProspectKind {
    WATER("maptools.kind.water"),
    SALTWATER("maptools.kind.saltwater"),
    CLAY("maptools.kind.clay"),
    SOIL("maptools.kind.soil"),
    SAND("maptools.kind.sand"),
    ORE("maptools.kind.ore"),
    GEM("maptools.kind.gem"),
    STONE("maptools.kind.stone"),
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
        /* Mined finds are tested first, and against name lists rather than the loose word
         * matching below: Sandstone would otherwise read as a Sand ground sample. Nothing a
         * Checker bot samples appears in those lists, so the samples are unaffected. */
        if(MasterMiner.isGemstone(resourceType))
            return GEM;
        if(MasterMiner.isOre(resourceType))
            return ORE;
        if(MasterMiner.isMinedStone(resourceType))
            return STONE;
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
