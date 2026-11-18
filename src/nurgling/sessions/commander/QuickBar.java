package nurgling.sessions.commander;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.widgets.NBotsMenu;
import org.json.JSONArray;

import java.awt.Color;

/**
 * The Commander quick bar: ten slots that act on the selected characters, played with a click or Shift+1..0 (the plain
 * number keys are control groups in Commander mode). Like the regular action bar it takes what is dragged onto it: a
 * bot from the bot menu, an entry of the action menu, a scenario. A bot starts on every selected character, an action
 * is used by every one, a scenario runs in every one's session. Right-click empties a slot. The slots belong to the
 * commander, not to a character, so they are the same whoever is on screen.
 */
class QuickBar extends HudPanel implements DropTarget {
    static final String DRAG_NAME = "Commander quick bar";
    static final int SLOTS = 10;
    private static final int SQ = UI.scale(36), GAP = UI.scale(3);

    QuickBar() {
        super(new Coord(2 * PAD + SLOTS * SQ + (SLOTS - 1) * GAP, 2 * PAD + SQ + UI.scale(12)));
    }

    /* ---- Slots, kept in the config as "bot:<path>", "act:<resource>" or "scenario:<name>" ---- */

    private static String[] slots() {
        String[] out = new String[SLOTS];
        Object v = NConfig.getGlobal(NConfig.Key.commanderQuickBar);
        if (v instanceof String && !((String) v).isEmpty()) {
            try {
                JSONArray a = new JSONArray((String) v);
                for (int i = 0; i < SLOTS && i < a.length(); i++) {
                    String s = a.optString(i, "");
                    out[i] = s.isEmpty() ? null : s;
                }
            } catch (org.json.JSONException e) {
                /* A broken value: start empty. */
            }
        }
        return out;
    }

    private static void set(int slot, String what) {
        String[] s = slots();
        s[slot] = what;
        JSONArray a = new JSONArray();
        for (String x : s)
            a.put((x == null) ? "" : x);
        NConfig.set(NConfig.Key.commanderQuickBar, a.toString());
    }

    /** Play a slot on the selection. */
    static void use(int slot, NGameUI gui) {
        if (slot < 0 || slot >= SLOTS)
            return;
        String s = slots()[slot];
        if (s == null) {
            if (gui != null)
                gui.msg(L10n.get("commander.msg.slot_empty", slotKey(slot)));
            return;
        }
        if (s.startsWith("bot:")) {
            CommanderActions.runBot(CommanderActions.botOfPath(s.substring(4)), gui);
        } else if (s.startsWith("act:")) {
            CommanderActions.useAction(s.substring(4), gui);
        } else if (s.startsWith("scenario:")) {
            CommanderActions.runScenario(s.substring(9), gui);
        }
    }

    private static String slotKey(int slot) {
        return "⇧" + ((slot == 9) ? 0 : slot + 1);
    }

    /* ---- Drawing ---- */

    private Coord slotc(int i) {
        return new Coord(PAD + i * (SQ + GAP), PAD);
    }

    private int slotAt(Coord c) {
        for (int i = 0; i < SLOTS; i++)
            if (c.isect(slotc(i), new Coord(SQ, SQ)))
                return i;
        return -1;
    }

    @Override
    void render(GOut g) {
        fit(new Coord(2 * PAD + SLOTS * SQ + (SLOTS - 1) * GAP, 2 * PAD + SQ + UI.scale(12)));
        background(g);
        String[] s = slots();
        NGameUI gui = gui();
        for (int i = 0; i < SLOTS; i++) {
            Coord ul = slotc(i);
            Coord sq = new Coord(SQ, SQ);
            g.chcolor(0x1C, 0x20, 0x1F, 0xF0);
            g.frect(ul, sq);
            g.chcolor(FRAME);
            g.rect(ul, sq);
            g.chcolor();
            if (s[i] != null)
                icon(g.reclip(ul.add(2, 2), sq.sub(4, 4)), s[i], gui);
            g.aimage(text(slotKey(i), DIM), new Coord(ul.x + SQ / 2, ul.y + SQ + UI.scale(1)), 0.5, 0);
            final int slot = i;
            cells.add(new Cell(Area.sized(ul, sq), "q" + i, (ev, dbl) -> {
                if (ev.b == 3)
                    set(slot, null);
                else if (ev.b == 1)
                    use(slot, gui());
            }, tip(s[i])));
        }
    }

    private void icon(GOut g, String s, NGameUI gui) {
        try {
            if (s.startsWith("bot:") && gui != null && gui.botsMenu != null) {
                NBotsMenu.NButton b = gui.botsMenu.find(s.substring(4));
                if (b != null) {
                    b.btn.draw(g);
                    return;
                }
            } else if (s.startsWith("act:") && gui != null && gui.menu != null) {
                MenuGrid.Pagina pag = gui.menu.paginafor(Resource.remote().load(s.substring(4)));
                pag.button().spr().draw(g);
                return;
            }
        } catch (Loading l) {
            return;
        }
        /* A scenario, or something this character's menus don't know: its name. */
        String label = s.substring(s.indexOf(':') + 1);
        int cut = label.lastIndexOf('/');
        g.aimage(text(clip(label.substring(cut + 1), 6), TEXT), g.sz().div(2), 0.5, 0.5);
    }

    private String tip(String s) {
        if (s == null)
            return L10n.get("commander.tip.quick_empty");
        String what = s.substring(s.indexOf(':') + 1);
        if (s.startsWith("bot:")) {
            nurgling.actions.bots.registry.BotDescriptor d = CommanderActions.botOfPath(what);
            if (d != null)
                what = d.getDisplayName();
        } else if (s.startsWith("act:")) {
            NGameUI gui = gui();
            try {
                if (gui != null && gui.menu != null)
                    what = gui.menu.paginafor(Resource.remote().load(what)).button().name();
            } catch (Loading l) {
                /* Keep the resource name. */
            }
        }
        return L10n.get("commander.tip.quick", what);
    }

    /* ---- Drops: what the regular action bar takes ---- */

    @Override
    public boolean dropthing(Coord c, Object thing) {
        int slot = slotAt(c);
        if (slot < 0 || !shown())
            return false;
        if (thing instanceof nurgling.craft.RecipeTransfer)
            thing = ((nurgling.craft.RecipeTransfer) thing).page();
        if (thing instanceof NBotsMenu.NButton) {
            set(slot, "bot:" + ((NBotsMenu.NButton) thing).path);
            return true;
        }
        if (thing instanceof MenuGrid.Pagina) {
            String name = CommanderActions.resName((MenuGrid.Pagina) thing);
            if (name != null) {
                set(slot, "act:" + name);
                return true;
            }
        }
        if (thing instanceof nurgling.widgets.NScenarioButton) {
            set(slot, "scenario:" + ((nurgling.widgets.NScenarioButton) thing).getScenario().getName());
            return true;
        }
        return false;
    }
}
