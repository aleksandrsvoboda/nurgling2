package nurgling.sessions.squad;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionManager;
import nurgling.tools.MarkdownToImageRenderer;

/**
 * How Squad Command works and all its hotkeys, opened by the squad panel's "i" button. Built like NGUIInfo: Markdown
 * assembled from the squadhelp.* language keys and drawn with the shared help renderer, here in a scroll area because
 * the page is longer than a screen.
 */
public class SquadHelpWindow extends Window {
    private static final int CONTENT_W = UI.scale(520);
    private static final int PAD = UI.scale(12);

    private static final String[][] SECTIONS = {
        {"mode_title", "mode_text", "-mode_on", "-mode_frame", "-mode_off"},
        {"select_title", "-select_click", "-select_shift", "-select_box", "-select_double", "-select_ground",
         "-select_all", "-select_tab", "-select_panel", "select_view", "select_limits"},
        {"cmd_title", "-cmd_move", "-cmd_use", "-cmd_map", "-cmd_stop", "-cmd_rally", "-cmd_follow"},
        {"leader_title", "leader_text", "-leader_set", "-leader_change"},
        {"groups_title", "-groups_set", "-groups_recall", "groups_text"},
        {"panel_title", "panel_text", "-panel_formation", "-panel_buttons", "-panel_pathfind", "-panel_bots",
         "-panel_view", "-panel_speed"},
        {"keys_title", "-key_mode", "-key_lmb", "-key_rmb", "-key_s", "-key_r", "-key_f", "-key_all", "-key_l",
         "-key_groups",
         "-key_esc", "-key_tab", "keys_rebind"},
        {"notes_title", "-note_camera", "-note_stop", "-note_far", "-note_bots"},
    };

    private final TexI tex;

    public SquadHelpWindow(Coord screen) {
        super(Coord.z, L10n.get("squadhelp.title"));
        tex = new TexI(MarkdownToImageRenderer.renderHelp(markdown(), CONTENT_W));
        int h = Math.min(tex.sz().y, Math.max(UI.scale(200), screen.y * 7 / 10));
        Scrollport sp = add(new Scrollport(new Coord(CONTENT_W + UI.scale(20), h)), new Coord(PAD, PAD));
        sp.cont.add(new Widget(tex.sz()) {
            @Override
            public void draw(GOut g) {
                g.image(tex, Coord.z);
            }
        }, Coord.z);
        sp.cont.update();
        pack();
        resize(csz().add(PAD, PAD));
    }

    private static String markdown() {
        StringBuilder md = new StringBuilder(L10n.get("squadhelp.intro")).append("\n\n");
        for (String[] sec : SECTIONS) {
            md.append("# ").append(L10n.get("squadhelp." + sec[0])).append("\n");
            for (int i = 1; i < sec.length; i++) {
                String k = sec[i];
                if (k.startsWith("-"))
                    md.append("- ").append(L10n.get("squadhelp." + k.substring(1))).append("\n");
                else
                    md.append("\n").append(L10n.get("squadhelp." + k)).append("\n\n");
            }
            md.append("\n");
        }
        /* Sections added by plugins, for what they add to the squad. */
        for (String extra : SessionManager.getInstance().squad().helpSections())
            md.append(extra).append("\n\n");
        return md.toString();
    }

    /** Show the help in this game UI: raise the open one, or open a new one centred. */
    public static void open(GameUI gui) {
        for (Widget w = gui.child; w != null; w = w.next) {
            if (w instanceof SquadHelpWindow) {
                w.raise();
                return;
            }
        }
        SquadHelpWindow wnd = new SquadHelpWindow(gui.sz);
        gui.add(wnd, new Coord(Math.max(0, (gui.sz.x - wnd.sz.x) / 2), Math.max(0, (gui.sz.y - wnd.sz.y) / 2)));
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if ("close".equals(msg))
            reqdestroy();
        else
            super.wdgmsg(msg, args);
    }

    @Override
    public void dispose() {
        tex.dispose();
        super.dispose();
    }
}
