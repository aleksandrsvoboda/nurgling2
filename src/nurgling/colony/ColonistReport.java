package nurgling.colony;

import nurgling.sessions.squad.WorldRef;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * What a character can do for the colony, published by its own session (see {@link ColonyReporter}) and only read by
 * the colony: skills and LP, the tools it carries, and how many of each colony material it holds. Immutable.
 */
public final class ColonistReport {
    public final String sessionId;
    /** Character key ({@code genus|name}), the same as the squad's: survives relogs. */
    public final String key;
    public final String name;
    public final String account;
    /** Where the character stands, or null before its map has loaded. */
    public final WorldRef pos;
    /** Known skills by id ("forage", "lumber", "crp", ...). */
    public final Set<String> skills;
    /** Skills it can buy now, id to LP cost. */
    public final Map<String, Integer> buyable;
    /** Learning points, or -1 before the character sheet has loaded. */
    public final int lp;
    /** Tools it carries (hands, belt or inventory), each with the item's name. */
    public final Map<ColonyItems.Tool, String> tools;
    /** Colony materials in its hands, belt and inventory. */
    public final Map<ColonyItems.Material, Integer> materials;
    /** The character's world ("World 16.2"), or null until learned from character select. */
    public final String world;
    /** The character's world id (genus). */
    public final String genus;
    /** Free cells of the main inventory, or -1. */
    public final int invFree;
    public final long time;

    public ColonistReport(String sessionId, String key, String name, String account, WorldRef pos, Set<String> skills,
                          Map<String, Integer> buyable, int lp, Map<ColonyItems.Tool, String> tools,
                          Map<ColonyItems.Material, Integer> materials, String genus, String world, int invFree, long time) {
        this.genus = genus;
        this.world = world;
        this.sessionId = sessionId;
        this.key = key;
        this.name = name;
        this.account = account;
        this.pos = pos;
        this.skills = Collections.unmodifiableSet(skills);
        this.buyable = Collections.unmodifiableMap(buyable);
        this.lp = lp;
        this.tools = Collections.unmodifiableMap(tools.isEmpty() ? new EnumMap<>(ColonyItems.Tool.class) : new EnumMap<>(tools));
        this.materials = Collections.unmodifiableMap(materials.isEmpty() ? new EnumMap<>(ColonyItems.Material.class) : new EnumMap<>(materials));
        this.invFree = invFree;
        this.time = time;
    }

    public boolean knows(String skill) {
        return skills.contains(skill);
    }

    public int count(ColonyItems.Material m) {
        Integer n = materials.get(m);
        return (n == null) ? 0 : n;
    }

    public boolean fresh() {
        return System.currentTimeMillis() - time < 10000;
    }
}
