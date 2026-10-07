package nurgling.widgets;

import haven.*;
import nurgling.*;
import nurgling.i18n.L10n;

import nurgling.tools.MarkdownToImageRenderer;

/** Instructions for the HUD editor, rendered with the shared Markdown renderer. */
public class NGUIInfo extends Window {
    public static final int xs = UI.scale(500); // Legacy centering width used by other HUD windows.
    private static final int CONTENT_W = UI.scale(520);
    private static final int PAD = UI.scale(20);

    public NGUIInfo() {
        super(new Coord(CONTENT_W + PAD * 2, UI.scale(200)), L10n.get("draghelp.title"));
        String path = L10n.get("opt.title") + " > " + L10n.get("opt.main.interface") + " > " + L10n.get("opt.interface.drag_mode");
        String markdown = "# " + L10n.get("draghelp.heading") + "\n"
            + L10n.get("draghelp.intro") + "\n\n"
            + "- " + L10n.get("draghelp.move") + "\n"
            + "- " + L10n.get("draghelp.resize") + "\n"
            + "- " + L10n.get("draghelp.visibility") + "\n"
            + "- " + L10n.get("draghelp.lock") + "\n"
            + "# " + L10n.get("draghelp.reopen") + "\n"
            + "**" + path + "**\n\n"
            + L10n.get("draghelp.finish");
        final TexI tex = new TexI(MarkdownToImageRenderer.renderHelp(markdown, CONTENT_W));
        Widget instructions = add(new Widget(tex.sz()) {
            @Override public void draw(GOut g) { g.image(tex, Coord.z); }
            @Override public void dispose() { tex.dispose(); super.dispose(); }
        }, new Coord(PAD, UI.scale(8)));
        int width = CONTENT_W + PAD * 2;
        add(new Button(UI.scale(240), L10n.get("draghelp.done")) {
            @Override public void click() { closeEvent(); }
        }, new Coord((width - UI.scale(240)) / 2, instructions.c.y + instructions.sz.y + UI.scale(4)));
        pack();
        resize(csz().add(PAD, UI.scale(16)));
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if(msg.equals("close")) closeEvent();
        else super.wdgmsg(msg, args);
    }

    @Override
    protected void added() {
        super.added();
        centerOnParent();
    }

    @Override
    public void show() {
        centerOnParent();
        super.show();
    }

    private void centerOnParent() {
        if(parent != null)
            move(new Coord(Math.max(0, (parent.sz.x - sz.x) / 2), Math.max(0, (parent.sz.y - sz.y) / 2)));
    }
    private void closeEvent() {
        ui.core.mode = NCore.Mode.IDLE;
        NConfig.set(NConfig.Key.show_drag_menu, false);
        if(ui.core.config.isUpdated()) ui.core.config.write();
        hide();
    }
}