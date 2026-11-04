package nurgling.scripting;

import haven.*;
import nurgling.plugins.CombatEvents;
import nurgling.plugins.UiEvents;
import nurgling.scripting.api.Codec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bridges the client's event seams to subscribed scripts.
 *
 * Listeners run on the UI thread, so they only build a small JSON object for the subscribers of
 * that session and queue it; writing to the socket happens on each connection's writer thread.
 *
 * Event kinds: ui.new, ui.add, ui.msg, ui.destroy, ui.out (raw widget traffic), combat.used.
 * Other op classes publish their own kinds through {@link #publish}.
 */
public final class ScriptEvents implements UiEvents.Listener, CombatEvents.UsedListener {
    private static ScriptEvents installed;

    static synchronized void ensureInstalled() {
        if (installed != null) return;
        installed = new ScriptEvents();
        UiEvents.addListener(installed);
        CombatEvents.addListener(installed);
    }

    /** Sends an event to every subscription of {@code ui}'s session that wants {@code kind}. */
    public static void publish(UI ui, String kind, JSONObject data) {
        ScriptServer s = ScriptRuntime.server();
        if (s == null) return;
        for (ScriptConnection c : s.connections()) {
            for (ScriptConnection.Subscription sub : c.subscriptions()) {
                if (sub.run.ui == ui && sub.wants(kind) && matches(sub.filter, data))
                    c.sendEvent(sub.id, kind, data);
            }
        }
    }

    /** True if anyone listens for {@code kind} on {@code ui}; lets publishers skip building the payload. */
    public static boolean wanted(UI ui, String kind) {
        ScriptServer s = ScriptRuntime.server();
        if (s == null) return false;
        for (ScriptConnection c : s.connections()) {
            for (ScriptConnection.Subscription sub : c.subscriptions()) {
                if (sub.run.ui == ui && sub.wants(kind)) return true;
            }
        }
        return false;
    }

    /** A subscription filter is a set of exact field matches; a string value ending in '*' matches a prefix. */
    private static boolean matches(JSONObject filter, JSONObject data) {
        if (filter == null || filter.isEmpty()) return true;
        for (String k : filter.keySet()) {
            Object want = filter.get(k);
            Object have = data.opt(k);
            if (have == null) return false;
            if (want instanceof String && ((String) want).endsWith("*")) {
                String pre = ((String) want).substring(0, ((String) want).length() - 1);
                if (!String.valueOf(have).startsWith(pre)) return false;
            } else if (want instanceof Number && have instanceof Number) {
                if (((Number) want).doubleValue() != ((Number) have).doubleValue()) return false;
            } else if (!String.valueOf(want).equals(String.valueOf(have))) {
                return false;
            }
        }
        return true;
    }

    private static JSONArray args(Object[] args) {
        JSONArray a = new JSONArray();
        if (args == null) return a;
        for (Object o : args) {
            if (o instanceof Widget) a.put(((Widget) o).wdgid());
            else a.put(Codec.toJson(o));
        }
        return a;
    }

    private static String cls(Widget w) {
        return w == null ? null : w.getClass().getName();
    }

    @Override
    public void onNewWidget(UI ui, int id, String type, Widget wdg, Object[] cargs) {
        if (!wanted(ui, "ui.new")) return;
        JSONObject d = new JSONObject().put("wdg", id).put("type", type).put("class", cls(wdg)).put("args", args(cargs));
        if (wdg instanceof Window) d.put("caption", ((Window) wdg).cap);
        publish(ui, "ui.new", d);
    }

    @Override
    public void onAddWidget(UI ui, int id, Widget wdg, int parent, Widget pwdg, Object[] pargs) {
        if (!wanted(ui, "ui.add")) return;
        JSONObject d = new JSONObject().put("wdg", id).put("parent", parent).put("class", cls(wdg)).put("args", args(pargs));
        if (wdg instanceof Window) d.put("caption", ((Window) wdg).cap);
        publish(ui, "ui.add", d);
    }

    @Override
    public void onUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {
        if (!wanted(ui, "ui.msg")) return;
        publish(ui, "ui.msg", new JSONObject().put("wdg", id).put("class", cls(wdg)).put("msg", msg).put("args", args(args)));
    }

    @Override
    public void onDestroyWidget(UI ui, int id, Widget wdg) {
        if (!wanted(ui, "ui.destroy")) return;
        publish(ui, "ui.destroy", new JSONObject().put("wdg", id).put("class", cls(wdg)));
    }

    @Override
    public void onOutgoing(UI ui, int id, Widget sender, String msg, Object[] args) {
        if (!wanted(ui, "ui.out")) return;
        publish(ui, "ui.out", new JSONObject().put("wdg", id).put("class", cls(sender)).put("msg", msg).put("args", args(args)));
    }

    @Override
    public void onUsed(Fightview fv, Indir<Resource> act) {
        UI ui = fv.ui;
        if (ui == null || !wanted(ui, "combat.used")) return;
        Object r = Codec.toJson(act);
        publish(ui, "combat.used", new JSONObject().put("action", r));
    }
}
