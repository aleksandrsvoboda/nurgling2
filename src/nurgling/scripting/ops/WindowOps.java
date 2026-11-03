package nurgling.scripting.ops;

import haven.*;
import nurgling.scripting.ScriptRuntime;
import nurgling.scripting.api.*;

import java.util.ArrayList;
import java.util.List;

import static nurgling.scripting.api.ApiOp.Kind.ACTION;
import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/** Top-level windows of the run's session. */
public final class WindowOps {

    @ApiOp(name = "windows.list", kind = QUERY,
            doc = "The open top-level windows (containers, crafting, character sheet, ...), newest last. "
                    + "Windows the client keeps hidden (the character sheet, equipment when closed) are left out unless `hidden` is true.",
            params = {@ApiParam(name = "hidden", type = "bool", doc = "Include hidden windows.", optional = true, def = "false")},
            returns = "list[Window]", returnsDoc = "Widget snapshots with `caption`.",
            example = "print([w[\"caption\"] for w in nc.windows.list()])")
    public static Object list(OpContext c, Args a) {
        boolean hidden = a.b("hidden", false);
        List<Widget> res = new ArrayList<>();
        for (Widget w = c.gui.child; w != null; w = w.next) {
            if (w instanceof Window && (hidden || w.visible())) res.add(w);
        }
        return res;
    }

    @ApiOp(name = "windows.get", kind = QUERY,
            doc = "The newest window whose caption equals `caption` exactly, or null.",
            params = {@ApiParam(name = "caption", type = "str", doc = "Window caption, e.g. \"Cupboard\" or \"Stockpile\".")},
            returns = "Window|null",
            example = "if nc.windows.get(\"Cupboard\") is None:\n    nc.actions.open(cupboard)")
    public static Object get(OpContext c, Args a) {
        Window w = c.gui.getWindow(a.str("caption"));
        return w == null ? null : Codec.widget(w);
    }

    @ApiOp(name = "windows.close", kind = ACTION,
            doc = "Closes a window the way its close button does, and waits up to 5 seconds for it to go away. "
                    + "Client-side windows such as the character sheet are hidden rather than destroyed; that counts as closed.",
            params = {@ApiParam(name = "window", type = "Window|str", doc = "Window handle or caption.")},
            returns = "bool", returnsDoc = "True once the window is gone or hidden; false if it is still open after 5 s.",
            errors = {Errors.NOT_FOUND, Errors.WIDGET_GONE},
            example = "nc.windows.close(\"Stockpile\")")
    public static Object close(OpContext c, Args a) throws InterruptedException {
        final Window w = a.window("window");
        ScriptRuntime.onUiThread(c.ui, w::reqclose);
        return c.waitFor(() -> w.parent == null || !w.visible(), 5);
    }

    private static void collectButtons(Widget w, List<String> res) {
        for (Widget ch = w.child; ch != null; ch = ch.next) {
            if (ch instanceof Button && ((Button) ch).text != null) res.add(((Button) ch).text.text);
            collectButtons(ch, res);
        }
    }

    @ApiOp(name = "windows.buttons", kind = QUERY,
            doc = "The texts of the buttons in a window, in widget order. Click one with `ui.click_button(text, window=...)`. Icon-only buttons have no text and are left out.",
            params = {@ApiParam(name = "window", type = "Window|str", doc = "Window handle or caption.")},
            returns = "list[str]", errors = {Errors.NOT_FOUND, Errors.WIDGET_GONE},
            example = "if \"Build\" in nc.windows.buttons(\"Palisade\"):\n    nc.ui.click_button(\"Build\", window=\"Palisade\")")
    public static Object buttons(OpContext c, Args a) {
        List<String> res = new ArrayList<>();
        collectButtons(a.window("window"), res);
        return res;
    }

    private static void collectTexts(Widget w, List<String> res) {
        for (Widget ch = w.child; ch != null; ch = ch.next) {
            String t = null;
            if (ch instanceof Label) t = ((Label) ch).texts;
            else if (ch instanceof TextEntry) t = ((TextEntry) ch).text();
            if (t != null && !t.isEmpty()) res.add(t);
            collectTexts(ch, res);
        }
    }

    @ApiOp(name = "windows.text", kind = QUERY,
            doc = "The texts shown in a window: every label and text field, in widget order. Rich-text boxes and drawn text are not included.",
            params = {@ApiParam(name = "window", type = "Window|str", doc = "Window handle or caption.")},
            returns = "list[str]", errors = {Errors.NOT_FOUND, Errors.WIDGET_GONE},
            example = "for line in nc.windows.text(\"Milestone\"):\n    print(line)")
    public static Object text(OpContext c, Args a) {
        List<String> res = new ArrayList<>();
        collectTexts(a.window("window"), res);
        return res;
    }

    private WindowOps() {}
}
