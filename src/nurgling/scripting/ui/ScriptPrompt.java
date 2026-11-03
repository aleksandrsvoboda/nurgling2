package nurgling.scripting.ui;

import haven.*;
import nurgling.i18n.L10n;

/**
 * The small "click something on the map" hint shown while a script waits for the player to pick
 * an object or drag an area. Its Cancel button (or close box) abandons the pick.
 */
public class ScriptPrompt extends Window {
    private final Runnable onCancel;
    private volatile boolean cancelled = false;

    public ScriptPrompt(String title, String prompt, Runnable onCancel) {
        super(Coord.z, title);
        this.onCancel = onCancel;
        Widget prev = add(new Label(prompt, UI.scale(240)), Coord.z);
        add(new Button(UI.scale(80), L10n.get("common.cancel")) {
            @Override
            public void click() {
                super.click();
                cancel();
            }
        }, prev.pos("bl").adds(0, 8));
        pack();
    }

    public boolean cancelled() {
        return cancelled;
    }

    private void cancel() {
        if (!cancelled) {
            cancelled = true;
            if (onCancel != null) onCancel.run();
        }
        reqdestroy();
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) cancel();
        else super.wdgmsg(msg, args);
    }

    /** Adds the prompt near the top centre of the game window, out of the way of the map. */
    public static void show(GameUI gui, ScriptPrompt p) {
        gui.add(p, Coord.of(Math.max(0, (gui.sz.x - p.sz.x) / 2), UI.scale(80)));
    }
}
