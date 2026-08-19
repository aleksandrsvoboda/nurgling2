package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.tools.GobCustomize;

import java.awt.Color;

/**
 * Settings for one type of object, reached through the Ctrl+RMB context menu's "Configure" entry.
 *
 * <p>Everything here is keyed by the resource path, so a change applies to every gob of that type
 * at once and survives a restart. The window deliberately holds the resource name rather than the
 * {@link haven.Gob} it was opened from - the object it was opened on may well walk away, be
 * destroyed or scroll out of view while the window is still up.
 */
public class GobConfigWindow extends Window {
    private static final Text.Foundry pathf =
            new Text.Foundry(Text.sans, 11, new Color(170, 170, 170)).aa(true);
    private static final int WIDTH = UI.scale(300);

    private final String res;
    private final HSlider scale;
    private final Label scaleval;

    public GobConfigWindow(String res) {
        super(UI.scale(new Coord(300, 92)), L10n.get("gobconf.title") + ": " + prettyName(res));
        this.res = res;

        Widget prev = add(new Label(shortenPath(res), pathf), UI.scale(new Coord(0, 0)));

        prev = add(new Label(L10n.get("gobconf.size")), prev.pos("bl").adds(0, 8));

        scaleval = new Label(GobCustomize.scalePercent(res) + "%");
        scale = new HSlider(UI.scale(190), GobCustomize.SCALE_MIN, GobCustomize.SCALE_MAX,
                GobCustomize.scalePercent(res)) {
            @Override
            public void changed() {
                // Live while dragging: memory only, so a drag does not write the config file
                // once per frame (NCore flushes a dirty config on the very next tick).
                scaleval.settext(this.val + "%");
                GobCustomize.preview(GobConfigWindow.this.res, this.val);
            }

            @Override
            public void fchanged() {
                GobCustomize.commit();
            }
        };
        addhl(prev.pos("bl").adds(0, 4), WIDTH, scale, scaleval);
        prev = scale;

        add(new Button(UI.scale(90), L10n.get("gobconf.reset")) {
            @Override
            public void click() {
                scale.val = GobCustomize.SCALE_DEFAULT;
                scaleval.settext(GobCustomize.SCALE_DEFAULT + "%");
                GobCustomize.setScalePercent(GobConfigWindow.this.res, GobCustomize.SCALE_DEFAULT);
            }
        }, prev.pos("bl").adds(0, 10));

        pack();
    }

    public String res() {
        return res;
    }

    /** Last path element, capitalised - "gfx/terobjs/trees/oak" reads as "Oak". */
    private static String prettyName(String res) {
        if (res == null || res.isEmpty())
            return "?";
        String s = res.substring(res.lastIndexOf('/') + 1);
        if (s.isEmpty())
            return res;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Keeps the tail of a long resource path, which is the part that identifies the object. */
    private static String shortenPath(String res) {
        if (res == null)
            return "";
        if (res.startsWith("gfx/"))
            return res.substring(4);
        return res;
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            GobCustomize.commit();
            ui.destroy(this);
        } else {
            super.wdgmsg(msg, args);
        }
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if (key_esc.match(ev)) {
            GobCustomize.commit();
            ui.destroy(this);
            return true;
        }
        return super.keydown(ev);
    }

    /**
     * Opens the window for a resource, or raises the one already open for it. Re-opening for a
     * different type replaces the window rather than stacking a second one on top.
     */
    public static void open(String res) {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || res == null)
            return;
        for (Widget w = gui.child; w != null; w = w.next) {
            if (w instanceof GobConfigWindow) {
                if (res.equals(((GobConfigWindow) w).res)) {
                    w.raise();
                    return;
                }
                gui.ui.destroy(w);
                break;
            }
        }
        GobConfigWindow wnd = new GobConfigWindow(res);
        Coord pos = gui.sz.sub(wnd.sz).div(2);
        gui.add(wnd, new Coord(Math.max(0, pos.x), Math.max(0, pos.y)));
        wnd.raise();
    }
}
