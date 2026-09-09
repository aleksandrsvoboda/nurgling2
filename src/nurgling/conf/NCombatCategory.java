package nurgling.conf;

import nurgling.i18n.L10n;

import java.util.*;

/**
 * The category tabs above the combat-move list in the Martial Arts window.
 *
 * The move universe is deliberately not re-typed here. Attacks and stances come from
 * {@link NCombatData}, movement moves from {@link NCooldown#vardata}, and defences are whatever
 * is left of the moves {@link NCooldown} already knows about. Only the six memberships that no
 * table can express -- attacks that deal no damage, and the three moves that genuinely belong in
 * two tabs -- are named explicitly below.
 *
 * The consequence worth keeping: a move added to the combat HUD's cooldown tables lands in a tab
 * without anyone remembering this file exists, and a move nobody has classified yet shows up
 * under {@link #OTHER} rather than disappearing from every tab.
 */
public enum NCombatCategory {
    ALL      ("char.fight.category.all",       "nurgling/hud/star",     true),
    ATTACKS  ("char.fight.category.attacks",   "paginae/atk/cleave",    false),
    DEFENCES ("char.fight.category.defences",  "paginae/atk/qdodge",    false),
    MANEUVERS("char.fight.category.maneuvers", "paginae/atk/oakstance", false),
    MOVES    ("char.fight.category.moves",     "paginae/atk/dash",      false),
    OTHER    ("char.fight.category.other",     "gfx/invobjs/missing",   true);

    public static final String PREFIX = "paginae/atk/";

    /** Attacks that deal no damage of their own, so they carry no entry in {@link NCombatData#ATTACKS}. */
    private static final String[] EXTRA_ATTACKS = {"flex", "oppknock", "stealthunder"};
    /** Opportunity Knocks closes the distance as well as hitting. */
    private static final String[] EXTRA_MOVES = {"oppknock"};
    /** Flex restores, Dash breaks away: both defend as much as they do their own job. */
    private static final String[] EXTRA_DEFENCES = {"flex", "dash"};

    private final String labelKey;
    private final String iconRes;
    private final boolean localIcon;
    private final Set<String> names = new HashSet<>();

    NCombatCategory(String labelKey, String iconRes, boolean localIcon) {
        this.labelKey = labelKey;
        this.iconRes = iconRes;
        this.localIcon = localIcon;
    }

    static {
        for(String name : NCombatData.ATTACKS.keySet())
            ATTACKS.names.add(PREFIX + name);
        addAll(ATTACKS, EXTRA_ATTACKS);

        MANEUVERS.names.addAll(NCombatData.MANEUVERS);

        MOVES.names.addAll(NCooldown.vardata.keySet());
        addAll(MOVES, EXTRA_MOVES);

        /* Defences are the remainder: no table names them, but every move the client tracks a
         * cooldown for is either an attack, a stance, a movement move, or a defence. */
        Set<String> known = new HashSet<>();
        known.addAll(NCooldown.data.keySet());
        known.addAll(NCooldown.fixeddata.keySet());
        known.addAll(NCooldown.vardata.keySet());
        for(String name : known) {
            if(!ATTACKS.names.contains(name) && !MANEUVERS.names.contains(name) && !MOVES.names.contains(name))
                DEFENCES.names.add(name);
        }
        addAll(DEFENCES, EXTRA_DEFENCES);
    }

    private static void addAll(NCombatCategory cat, String[] names) {
        for(String name : names)
            cat.names.add(PREFIX + name);
    }

    /** Localized tab tooltip. */
    public String label() {
        return(L10n.get(labelKey));
    }

    /** Resource name of the tab icon. */
    public String iconRes() {
        return(iconRes);
    }

    /** True when the icon ships with the client rather than coming from the game's resource server. */
    public boolean localIcon() {
        return(localIcon);
    }

    /**
     * Whether a move belongs in this tab. A move may belong in two: Flex is an attack and a
     * defence, Dash is a defence and a movement move, Opportunity Knocks is an attack and a
     * movement move. A null name -- a move whose resource has not resolved yet -- belongs
     * nowhere except {@link #ALL}, so it reappears once the resource loads.
     */
    public boolean matches(String resnm) {
        if(this == ALL)
            return(true);
        if(resnm == null)
            return(false);
        if(this == OTHER)
            return(!classified(resnm));
        return(names.contains(resnm));
    }

    private static boolean classified(String resnm) {
        for(NCombatCategory cat : values()) {
            if((cat != ALL) && (cat != OTHER) && cat.names.contains(resnm))
                return(true);
        }
        return(false);
    }

    /** The moves this tab claims. Exposed for tests; empty for {@link #ALL} and {@link #OTHER}. */
    public Set<String> names() {
        return(Collections.unmodifiableSet(names));
    }
}
