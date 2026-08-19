package nurgling.tools;

import haven.Gob;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.gattrr.NGobCustomScale;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

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
 * one oak configures every oak. Only values that differ from the default are stored, which keeps
 * the config file proportional to what the user actually changed rather than to the number of
 * resources in the game.
 *
 * <p>The authoritative copy lives in memory ({@link #scales}) rather than in the config map.
 * Dragging a slider has to repaint the world on every pixel, and {@link nurgling.NCore} flushes a
 * dirty config to disk on the very next tick - so writing through to {@code NConfig} per drag step
 * would mean a file write per frame. {@link #preview} therefore only touches memory, and
 * {@link #commit} publishes the finished value.
 */
public class GobCustomize {
    /** Option name inside a resource's settings object, as stored in the config file. */
    private static final String KEY_SCALE = "scale";

    public static final int SCALE_MIN = 10;
    public static final int SCALE_MAX = 300;
    public static final int SCALE_DEFAULT = 100;

    /** res name -> size percentage. Absent means "default size"; never holds SCALE_DEFAULT. */
    private static volatile Map<String, Integer> scales = null;

    private static Map<String, Integer> scales() {
        Map<String, Integer> cur = scales;
        if (cur == null) {
            synchronized (GobCustomize.class) {
                cur = scales;
                if (cur == null)
                    scales = cur = load();
            }
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> load() {
        Map<String, Integer> res = new ConcurrentHashMap<>();
        // getGlobal, not get: this is read once and cached, and must not depend on which
        // session's config the calling thread happens to resolve to.
        Object o = NConfig.getGlobal(NConfig.Key.gobConf);
        if (!(o instanceof Map))
            return res;
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) o).entrySet()) {
            if (!(entry.getValue() instanceof Map))
                continue;
            Object v = ((Map<String, Object>) entry.getValue()).get(KEY_SCALE);
            if (!(v instanceof Number))
                continue;
            int pct = clampScale(((Number) v).intValue());
            if (pct != SCALE_DEFAULT)
                res.put(entry.getKey(), pct);
        }
        return res;
    }

    public static int clampScale(int pct) {
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, pct));
    }

    /** Configured size for a resource, as a percentage. {@link #SCALE_DEFAULT} when untouched. */
    public static int scalePercent(String res) {
        if (res == null)
            return SCALE_DEFAULT;
        Integer v = scales().get(res);
        return (v == null) ? SCALE_DEFAULT : v;
    }

    /** True when this resource has any non-default setting worth persisting. */
    public static boolean isConfigured(String res) {
        return (res != null) && scales().containsKey(res);
    }

    /**
     * Applies a size to every gob of the type immediately, without saving. Used while a slider is
     * being dragged so the change is visible as it happens; {@link #commit} makes it permanent.
     */
    public static void preview(String res, int pct) {
        if (res == null)
            return;
        pct = clampScale(pct);
        if (pct == SCALE_DEFAULT)
            scales().remove(res);
        else
            scales().put(res, pct);
        applyAll(res);
    }

    /** Writes the current in-memory settings to the config file. */
    public static void commit() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Integer> entry : scales().entrySet()) {
            Map<String, Object> opts = new HashMap<>();
            opts.put(KEY_SCALE, entry.getValue());
            out.put(entry.getKey(), opts);
        }
        NConfig.set(NConfig.Key.gobConf, out);
        NConfig.needUpdate();
    }

    /** Convenience for callers that change a setting outside a drag. */
    public static void setScalePercent(String res, int pct) {
        preview(res, pct);
        commit();
    }

    /**
     * Brings one gob's scale attribute in line with its type's setting. Cheap and idempotent, so
     * it is safe to call from {@link nurgling.NGob} whenever a gob's resource name is resolved.
     */
    public static void apply(Gob gob) {
        if (gob == null || gob.ngob == null)
            return;
        int pct = scalePercent(gob.ngob.name);
        NGobCustomScale cur = gob.getattr(NGobCustomScale.class);
        if (pct == SCALE_DEFAULT) {
            if (cur != null)
                gob.delattr(NGobCustomScale.class);
        } else {
            float s = pct / 100.0f;
            if (cur == null || cur.scale != s)
                gob.setattr(new NGobCustomScale(gob, s));
        }
    }

    /**
     * Re-applies the setting for one resource across every open session, so the world updates
     * while the window is still open. Follows {@link GobHide#applyAll}: snapshot the object cache
     * under its monitor, then act outside it.
     */
    public static void applyAll(String res) {
        if (res == null)
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
                apply(gob);
        }
    }
}
