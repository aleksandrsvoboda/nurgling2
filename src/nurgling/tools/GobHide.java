package nurgling.tools;

import haven.Gob;
import haven.GobIcon;
import haven.Loading;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NGob;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The single authority on which gobs are hidden from the world view.
 *
 * <p>Before this class the same decision was written three times (two inline copies in
 * {@link Gob#setattr} / {@link Gob#added} plus one in {@link NGob}) and applied by two
 * near-identical sweep loops in NUtils, one per hideable thing. Everything now routes
 * through {@link #shouldHide}, so adding a category costs one enum constant.
 *
 * <p>Hiding works by dropping a gob's {@code GAttrib} render nodes. Custom overlays survive
 * that, and {@link nurgling.overlays.NModelBox} deliberately stays clickable while its gob is
 * hidden - that box is the object's only remaining click target. A category whose members
 * carry no {@link nurgling.NHitBox} therefore gets no box and would hide into an unclickable
 * hole, which is what {@link HideCategory#requiresHitBox} guards against.
 */
public class GobHide {
    private static final String TREE_PREFIX = "gfx/terobjs/trees/";
    private static final String ARCH_PREFIX = "gfx/terobjs/arch/";
    private static final String TRELLIS_PREFIX = "gfx/terobjs/plants/trellis";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_RESPECT_ICONS = "respectMapIcons";

    public enum HideCategory {
        TREES("hiding.cat.trees", true, true, n -> n.startsWith(TREE_PREFIX) && !isLogRes(n)),
        TREE_LOGS("hiding.cat.tree_logs", true, true, GobHide::isLogRes),
        BUSHES("hiding.cat.bushes", true, true, n -> n.startsWith("gfx/terobjs/bushes/")),
        BOULDERS("hiding.cat.boulders", true, true,
                n -> n.startsWith("gfx/terobjs/bumlings") || n.equals("gfx/terobjs/stonepillar")),
        WALLS("hiding.cat.walls", true, true, GobHide::isWallRes),
        HOUSES("hiding.cat.houses", true, true, GobHide::isHouseRes),
        STOCKPILES("hiding.cat.stockpiles", true, true, n -> n.startsWith("gfx/terobjs/stockpile-")),
        TRELLISES("hiding.cat.trellises", true, true, n -> n.startsWith(TRELLIS_PREFIX)),
        /**
         * Earthworms predate the categorised system and keep their own config key and their own
         * checkbox in the World panel, so they are neither stored in {@code hideConf} nor gated by
         * the master switch. They also hide without a box, which is the behaviour users already
         * have - hence {@code requiresHitBox = false}.
         */
        EARTHWORMS("hiding.cat.earthworms", false, false, n -> n.contains("earthworm"));

        public final String l10nKey;
        public final boolean gatedByMaster;
        public final boolean requiresHitBox;
        private final Predicate<String> matcher;

        HideCategory(String l10nKey, boolean gatedByMaster, boolean requiresHitBox, Predicate<String> matcher) {
            this.l10nKey = l10nKey;
            this.gatedByMaster = gatedByMaster;
            this.requiresHitBox = requiresHitBox;
            this.matcher = matcher;
        }

        /** Whether the user has switched this category on, ignoring the master switch. */
        public boolean enabled() {
            if (this == EARTHWORMS) {
                Object o = NConfig.get(NConfig.Key.hideEarthworm);
                // Legacy inverted semantics: hideEarthworm == true means "show them".
                return (o instanceof Boolean) && !((Boolean) o);
            }
            Object v = confMap().get(name());
            return (v instanceof Boolean) && (Boolean) v;
        }
    }

    /** Categories offered by the Object Hiding panel - everything except the legacy earthworm toggle. */
    public static final HideCategory[] PANEL_CATEGORIES =
            Arrays.stream(HideCategory.values()).filter(c -> c.gatedByMaster).toArray(HideCategory[]::new);

    /** Categories switched on for a fresh install, so the hotkey does something out of the box. */
    private static final HideCategory[] DEFAULT_ON = {
            HideCategory.TREES, HideCategory.BUSHES, HideCategory.BOULDERS, HideCategory.TREE_LOGS
    };

    private static volatile int version = 1;

    /**
     * Bumped on every settings change. {@link NGob#isHidden()} caches its decision against this,
     * so the hot paths in Gob.setattr/Gob.added never re-run the name matchers.
     */
    public static int version() {
        return version;
    }

    public static void bump() {
        version++;
    }

    private static boolean isLogRes(String n) {
        if (!n.startsWith(TREE_PREFIX))
            return false;
        return n.endsWith("log") || n.endsWith("oldtrunk") || n.contains("driftwood");
    }

    private static boolean isWallRes(String n) {
        if (!n.startsWith(ARCH_PREFIX))
            return false;
        String s = n.substring(ARCH_PREFIX.length());
        // Gates are how you get through a wall - hiding them would make gateways unusable.
        if (s.contains("gate"))
            return false;
        return s.startsWith("palisade") || s.startsWith("pole")
                || s.startsWith("drystonewall") || s.startsWith("brickwall");
    }

    private static boolean isHouseRes(String n) {
        if (!n.startsWith(ARCH_PREFIX))
            return false;
        String s = n.substring(ARCH_PREFIX.length());
        // Doors are separate gobs and must stay visible so the building can still be entered.
        if (s.contains("-door"))
            return false;
        return s.startsWith("logcabin") || s.startsWith("timberhouse") || s.startsWith("stonestead")
                || s.startsWith("stonemansion") || s.startsWith("greathall") || s.startsWith("stonetower")
                || s.startsWith("windmill") || s.startsWith("belltower");
    }

    /**
     * Resolves a resource name to its category once, at the point the gob's name becomes known.
     * Returns null for the overwhelming majority of gobs, which is what makes the per-gob cache cheap.
     */
    public static HideCategory categoryOf(String resName) {
        if (resName == null)
            return null;
        for (HideCategory cat : HideCategory.values()) {
            if (cat.matcher.test(resName))
                return cat;
        }
        return null;
    }

    public static boolean shouldHide(Gob gob, HideCategory cat) {
        if (cat == null || gob == null)
            return false;
        if (cat.gatedByMaster && !isEnabled())
            return false;
        if (!cat.enabled())
            return false;
        if (cat.requiresHitBox && (gob.ngob == null || !gob.ngob.hasClickBox()))
            return false;
        // The map-icon exception belongs to the categorised system. Earthworms are a standalone
        // legacy toggle and must keep behaving exactly as they did, icon or no icon.
        if (cat.gatedByMaster && respectMapIcons() && hasVisibleMapIcon(gob))
            return false;
        return true;
    }

    /**
     * True when the gob has a minimap icon that the user has switched on. Icon settings load
     * asynchronously, so an unresolved icon counts as "no icon" here; {@link NGob} re-applies the
     * decision once the GobIcon attribute actually lands.
     */
    private static boolean hasVisibleMapIcon(Gob gob) {
        try {
            GobIcon icon = gob.getattr(GobIcon.class);
            if (icon == null || !icon.res.isReady())
                return false;
            GobIcon.Icon ic = icon.icon();
            if (ic == null)
                return false;
            NGameUI gui = guiFor(gob);
            if (gui == null || gui.mmap == null || gui.mmap.iconconf == null)
                return false;
            GobIcon.Setting conf = gui.mmap.iconconf.get(ic);
            return conf != null && conf.show;
        } catch (Loading l) {
            return false;
        }
    }

    private static NGameUI guiFor(Gob gob) {
        if (gob.glob == null || gob.glob.sess == null)
            return null;
        SessionContext ctx = SessionManager.getInstance().findBySession(gob.glob.sess);
        return (ctx == null) ? null : ctx.getGameUI();
    }

    /**
     * Brings a single gob's render state in line with the current settings. Cheap and idempotent:
     * for the vast majority of gobs (no category) it returns immediately.
     */
    public static void apply(Gob gob) {
        NGob ngob = (gob == null) ? null : gob.ngob;
        if (ngob == null)
            return;
        boolean want = ngob.isHidden();
        if (want == ngob.hideApplied)
            return;
        if (want) {
            gob.hide();
            ngob.hideApplied = true;
        } else {
            gob.show();
            ngob.hideApplied = false;
            ngob.refreshHarvestOverlay();
        }
    }

    /** Re-applies hiding across every open session. Replaces showHideNature + showHideEarthworm. */
    public static void applyAll() {
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            NGameUI gui = ctx.getGameUI();
            if (gui == null || gui.ui == null || gui.ui.sess == null)
                continue;
            // Snapshot under the monitor, then apply outside it: Gob.show() blocks in
            // Loading.waitfor until the resource resolves, and stalling there while holding the
            // object cache would take the render and network threads down with it.
            List<Gob> gobs = new ArrayList<>();
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob gob : gui.ui.sess.glob.oc)
                    gobs.add(gob);
            }
            for (Gob gob : gobs)
                apply(gob);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> confMap() {
        Object o = NConfig.get(NConfig.Key.hideConf);
        if (o instanceof Map)
            return (Map<String, Object>) o;
        Map<String, Object> fresh = defaults();
        NConfig.set(NConfig.Key.hideConf, fresh);
        return fresh;
    }

    private static Map<String, Object> build(boolean enabled, boolean respectIcons, HideCategory[] on) {
        Map<String, Object> m = new HashMap<>();
        m.put(KEY_ENABLED, enabled);
        m.put(KEY_RESPECT_ICONS, respectIcons);
        for (HideCategory cat : PANEL_CATEGORIES)
            m.put(cat.name(), false);
        for (HideCategory cat : on)
            m.put(cat.name(), true);
        return m;
    }

    /**
     * Settings for a fresh install: hiding on, with the four nature categories ticked. The master
     * switch starts on because the legacy default did too - hideNature defaulted to false, which
     * under the old inverted semantics meant nature was hidden out of the box.
     */
    public static Map<String, Object> defaults() {
        return build(true, true, DEFAULT_ON);
    }

    /**
     * Reproduces the behaviour of the legacy {@code hideNature} flag for an upgrading config.
     *
     * <p>Faithfulness matters in both directions. If nature was hidden we enable exactly what
     * {@code NUtils.isNatureObject} used to cover - trees (stumps included), bushes, boulders and
     * stone pillars, but never logs or oldtrunks - and switch the map-icon exception off, because
     * the legacy flag had no such exception. If nature was not hidden the master switch stays off,
     * but the categories are still primed with the standard set so the hotkey has something to
     * toggle rather than silently doing nothing.
     */
    public static Map<String, Object> legacyMigration(boolean natureWasHidden) {
        HideCategory[] on = natureWasHidden
                ? new HideCategory[]{HideCategory.TREES, HideCategory.BUSHES, HideCategory.BOULDERS}
                : DEFAULT_ON;
        return build(natureWasHidden, false, on);
    }

    public static boolean isEnabled() {
        Object v = confMap().get(KEY_ENABLED);
        return (v instanceof Boolean) && (Boolean) v;
    }

    public static boolean respectMapIcons() {
        Object v = confMap().get(KEY_RESPECT_ICONS);
        return (v instanceof Boolean) && (Boolean) v;
    }

    /**
     * The one place the master switch is flipped - from the panel, the minimap button and the
     * hotkey alike. Everything downstream (sweep, config flush, minimap button state) happens here
     * rather than being re-implemented at each call site.
     */
    public static void setEnabled(boolean val) {
        writeFlag(KEY_ENABLED, val);
        applyAll();
        syncMinimapToggle(val);
    }

    public static void toggle() {
        setEnabled(!isEnabled());
    }

    public static void setRespectMapIcons(boolean val) {
        writeFlag(KEY_RESPECT_ICONS, val);
    }

    public static void setCategory(HideCategory cat, boolean val) {
        if (cat == HideCategory.EARTHWORMS) {
            NConfig.set(NConfig.Key.hideEarthworm, !val);
            NConfig.needUpdate();
            bump();
            return;
        }
        writeFlag(cat.name(), val);
    }

    /**
     * Copy-on-write: the render thread reads this map through {@link HideCategory#enabled()} while
     * the UI thread edits it, so publish a finished replacement rather than mutating in place.
     */
    private static void writeFlag(String key, boolean val) {
        Map<String, Object> next = new HashMap<>(confMap());
        next.put(key, val);
        NConfig.set(NConfig.Key.hideConf, next);
        NConfig.needUpdate();
        bump();
    }

    /**
     * Called when the user edits minimap icon settings. The map-icon exception depends on those
     * settings, but they live outside hideConf, so nothing else would ever re-evaluate the
     * affected gobs. Cheap no-op unless the exception is actually in play.
     */
    public static void onIconSettingsChanged() {
        if (!isEnabled() || !respectMapIcons())
            return;
        bump();
        applyAll();
    }

    private static void syncMinimapToggle(boolean enabled) {
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            NGameUI gui = ctx.getGameUI();
            if (gui != null && gui.mmapw != null && gui.mmapw.natura != null)
                gui.mmapw.natura.a = !enabled;
        }
    }
}
