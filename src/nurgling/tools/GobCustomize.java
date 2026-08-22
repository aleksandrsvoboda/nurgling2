package nurgling.tools;

import haven.Gob;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.gattrr.NGobCustomScale;
import nurgling.gattrr.NGobCustomTint;
import nurgling.overlays.NGobConfigLabel;
import nurgling.overlays.NGobConfigMarker;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-resource display settings that apply to every gob of a type at once - the model behind
 * the Ctrl+RMB "Configure" window.
 *
 * <p>Settings are keyed by {@link nurgling.NGob#name}, the gob's resource path, so configuring
 * one oak configures every oak. Only resources that differ from the defaults are stored, which
 * keeps the config file proportional to what the user actually changed rather than to the number
 * of resources in the game.
 *
 * <p>The authoritative copy lives in memory ({@link #conf}) rather than in the config map.
 * Dragging a slider has to repaint the world on every pixel, and {@link nurgling.NCore} flushes a
 * dirty config to disk on the very next tick - so writing through to {@code NConfig} per drag step
 * would mean a file write per frame. {@link #update} therefore only touches memory, and
 * {@link #commit} publishes the finished value.
 */
public class GobCustomize {
    /* Option names as they appear in the config file. */
    private static final String KEY_SCALE = "scale";
    private static final String KEY_TINT = "tint";
    private static final String KEY_TINT_COLOR = "tintColor";
    private static final String KEY_MARKER = "marker";
    private static final String KEY_LABEL = "label";
    private static final String KEY_LABEL_TEXT = "labelText";

    public static final int SCALE_MIN = 10;
    public static final int SCALE_MAX = 300;
    public static final int SCALE_DEFAULT = 100;

    /** Starting colour for a highlight the user has not picked one for yet. */
    public static final Color DEFAULT_TINT = new Color(255, 64, 64, 200);

    /**
     * One resource's settings. Immutable: {@link #apply} reads these from whichever thread
     * resolved the gob's resource, while the window edits them from the UI thread, so changes are
     * published by replacing the map entry rather than by mutating a shared object.
     */
    public static final class Settings {
        public final int scale;
        public final boolean tint;
        public final Color tintColor;
        public final boolean marker;
        public final boolean label;
        public final String labelText;

        public Settings(int scale, boolean tint, Color tintColor, boolean marker,
                        boolean label, String labelText) {
            this.scale = scale;
            this.tint = tint;
            this.tintColor = (tintColor == null) ? DEFAULT_TINT : tintColor;
            this.marker = marker;
            this.label = label;
            this.labelText = (labelText == null) ? "" : labelText;
        }

        /**
         * A picked colour counts as non-default even with the highlight switched off, so the
         * choice is remembered - and, more importantly, so the window's colour poll does not see
         * its own write vanish and re-fire on every tick.
         */
        public boolean isDefault() {
            return (scale == SCALE_DEFAULT) && !tint && !marker && !label
                    && tintColor.equals(DEFAULT_TINT) && labelText.isEmpty();
        }

        /** True when there is actually a caption to draw - the option on and some text to show. */
        public boolean hasLabel() {
            return label && !labelText.isEmpty();
        }

        public Settings withScale(int v) {return new Settings(clampScale(v), tint, tintColor, marker, label, labelText);}
        public Settings withTint(boolean v) {return new Settings(scale, v, tintColor, marker, label, labelText);}
        public Settings withTintColor(Color v) {return new Settings(scale, tint, v, marker, label, labelText);}
        public Settings withMarker(boolean v) {return new Settings(scale, tint, tintColor, v, label, labelText);}
        public Settings withLabel(boolean v) {return new Settings(scale, tint, tintColor, marker, v, labelText);}
        public Settings withLabelText(String v) {return new Settings(scale, tint, tintColor, marker, label, v);}
    }

    public static final Settings DEFAULTS =
            new Settings(SCALE_DEFAULT, false, DEFAULT_TINT, false, false, "");

    /** res name -> settings. A resource at its defaults is absent rather than present-and-default. */
    private static volatile Map<String, Settings> conf = null;

    private static volatile int seq = 0;

    public static int seq() {
        return seq;
    }

    private static Map<String, Settings> conf() {
        Map<String, Settings> cur = conf;
        if (cur == null) {
            synchronized (GobCustomize.class) {
                cur = conf;
                if (cur == null)
                    conf = cur = load();
            }
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Settings> load() {
        Map<String, Settings> res = new ConcurrentHashMap<>();
        // getGlobal, not get: this is read once and cached, and must not depend on which
        // session's config the calling thread happens to resolve to.
        Object o = NConfig.getGlobal(NConfig.Key.gobConf);
        if (!(o instanceof Map))
            return res;
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) o).entrySet()) {
            if (!(entry.getValue() instanceof Map))
                continue;
            Map<String, Object> opts = (Map<String, Object>) entry.getValue();
            Settings s = new Settings(
                    intOpt(opts.get(KEY_SCALE), SCALE_DEFAULT),
                    boolOpt(opts.get(KEY_TINT)),
                    colorOpt(opts.get(KEY_TINT_COLOR)),
                    boolOpt(opts.get(KEY_MARKER)),
                    boolOpt(opts.get(KEY_LABEL)),
                    strOpt(opts.get(KEY_LABEL_TEXT)));
            if (!s.isDefault())
                res.put(entry.getKey(), s);
        }
        return res;
    }

    private static int intOpt(Object v, int def) {
        return (v instanceof Number) ? clampScale(((Number) v).intValue()) : def;
    }

    private static boolean boolOpt(Object v) {
        return (v instanceof Boolean) && (Boolean) v;
    }

    private static String strOpt(Object v) {
        return (v instanceof String) ? (String) v : "";
    }

    /** Colours are stored as a packed ARGB int, which survives the JSON round-trip unambiguously. */
    private static Color colorOpt(Object v) {
        if (!(v instanceof Number))
            return DEFAULT_TINT;
        return new Color(((Number) v).intValue(), true);
    }

    public static int clampScale(int pct) {
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, pct));
    }

    /** Settings for a resource; {@link #DEFAULTS} when the user has never touched it. */
    public static Settings settings(String res) {
        if (res == null)
            return DEFAULTS;
        Settings s = conf().get(res);
        return (s == null) ? DEFAULTS : s;
    }

    public static int scalePercent(String res) {
        return settings(res).scale;
    }

    public static float scaleOf(Gob gob) {
        if (gob == null || gob.ngob == null)
            return 1.0f;
        return scalePercent(gob.ngob.name) / 100.0f;
    }

    /**
     * Publishes new settings for a resource and shows them immediately, without saving. Used while
     * a slider is being dragged or a colour is being picked; {@link #commit} makes it permanent.
     */
    public static void update(String res, Settings s) {
        if (res == null || s == null)
            return;
        Settings prev = settings(res);
        if (s.isDefault())
            conf().remove(res);
        else
            conf().put(res, s);
        seq++;
        // Only push what actually moved. Dragging the size slider fires this ~60 times a second,
        // and re-adding the marker overlay each time would race its own deferred add.
        applyAll(res, prev.scale != s.scale,
                (prev.tint != s.tint) || !prev.tintColor.equals(s.tintColor),
                prev.marker != s.marker,
                prev.hasLabel() != s.hasLabel());
    }

    /** Writes the current in-memory settings to the config file. */
    public static void commit() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Settings> entry : conf().entrySet()) {
            Settings s = entry.getValue();
            Map<String, Object> opts = new HashMap<>();
            if (s.scale != SCALE_DEFAULT)
                opts.put(KEY_SCALE, s.scale);
            if (s.tint)
                opts.put(KEY_TINT, true);
            if (!s.tintColor.equals(DEFAULT_TINT))
                opts.put(KEY_TINT_COLOR, s.tintColor.getRGB());
            if (s.marker)
                opts.put(KEY_MARKER, true);
            if (s.label)
                opts.put(KEY_LABEL, true);
            if (!s.labelText.isEmpty())
                opts.put(KEY_LABEL_TEXT, s.labelText);
            if (!opts.isEmpty())
                out.put(entry.getKey(), opts);
        }
        NConfig.set(NConfig.Key.gobConf, out);
        NConfig.needUpdate();
    }

    /** Convenience for callers that change a setting outside a drag. */
    public static void set(String res, Settings s) {
        update(res, s);
        commit();
    }

    /**
     * Brings one gob in line with its type's settings. Cheap and idempotent, so it is safe to call
     * from {@link nurgling.NGob} whenever a gob's resource name is resolved.
     */
    public static void apply(Gob gob) {
        apply(gob, true, true, true, true);
    }

    private static void apply(Gob gob, boolean doScale, boolean doTint, boolean doMarker, boolean doLabel) {
        if (gob == null || gob.ngob == null)
            return;
        String res = gob.ngob.name;
        Settings s = settings(res);

        if (doScale) {
            NGobCustomScale scale = gob.getattr(NGobCustomScale.class);
            if (s.scale == SCALE_DEFAULT) {
                if (scale != null)
                    gob.delattr(NGobCustomScale.class);
            } else {
                float f = s.scale / 100.0f;
                if (scale == null || scale.scale != f)
                    gob.setattr(new NGobCustomScale(gob, f));
            }
        }

        if (doTint) {
            NGobCustomTint tint = gob.getattr(NGobCustomTint.class);
            if (!s.tint) {
                if (tint != null)
                    gob.delattr(NGobCustomTint.class);
            } else if (tint == null || !tint.color.equals(s.tintColor)) {
                gob.setattr(new NGobCustomTint(gob, s.tintColor));
            }
        }

        // Only ever added here - a marker whose setting goes away takes itself off, see
        // NGobConfigMarker.
        if (doMarker && s.marker && !hasol(gob, NGobConfigMarker.class))
            gob.addol(new Gob.Overlay(gob, new NGobConfigMarker(gob, res)), true);

        // Same deal: the caption reads its text live and drops itself when there is none left.
        if (doLabel && s.hasLabel() && !hasol(gob, NGobConfigLabel.class))
            gob.addol(new Gob.Overlay(gob, new NGobConfigLabel(gob, res)), true);
    }

    /**
     * Null-safe counterpart to {@link Gob#findol(Class)}: an overlay built from a
     * {@link haven.Sprite.Mill} has no sprite until it initialises, and that is not this one.
     */
    private static boolean hasol(Gob gob, Class<? extends haven.Sprite> cl) {
        for (Gob.Overlay ol : gob.ols) {
            if (cl.isInstance(ol.spr))
                return true;
        }
        return false;
    }

    /**
     * Re-applies the settings for one resource across every open session, so the world updates
     * while the window is still open. Follows {@link GobHide#applyAll}: snapshot the object cache
     * under its monitor, then act outside it.
     */
    public static void applyAll(String res) {
        applyAll(res, true, true, true, true);
    }

    private static void applyAll(String res, boolean doScale, boolean doTint, boolean doMarker,
                                 boolean doLabel) {
        if (res == null || !(doScale || doTint || doMarker || doLabel))
            return;
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            NGameUI gui = ctx.getGameUI();
            if (gui == null || gui.ui == null || gui.ui.sess == null)
                continue;
            List<Gob> gobs = new ArrayList<>();
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob gob : gui.ui.sess.glob.oc) {
                    if (gob != null && gob.ngob != null && res.equals(gob.ngob.name))
                        gobs.add(gob);
                }
            }
            for (Gob gob : gobs)
                apply(gob, doScale, doTint, doMarker, doLabel);
        }
    }
}
