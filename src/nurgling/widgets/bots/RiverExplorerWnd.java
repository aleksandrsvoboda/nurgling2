package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NRiverExplorerProp;
import nurgling.i18n.L10n;

/** River Explorer's start window: whether the sea may be used, reeds and the axe, limits and safety. */
public class RiverExplorerWnd extends Window implements Checkable {
    static final String[] HEADS = {"any", "N", "E", "S", "W"};
    CheckBox useSea, chopReeds, avoidDanger;
    Dropbox<String> head;
    UsingTools axes;
    TextEntry timeLimit, leash;
    public NRiverExplorerProp prop = null;
    boolean isReady = false;

    public RiverExplorerWnd() {
        super(new Coord(200, 200), L10n.get("river.wnd_title"));
        NRiverExplorerProp sp = NRiverExplorerProp.get(NUtils.getUI().sessInfo);
        if (sp == null) sp = new NRiverExplorerProp("", "");
        final NRiverExplorerProp start = sp;

        prev = add(new Label(L10n.get("river.water")));
        prev = add(useSea = new CheckBox(L10n.get("river.use_sea")) {
            { a = start.useSea; }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(new Label(L10n.get("river.head")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(head = new Dropbox<String>(UI.scale(120), HEADS.length, UI.scale(16)) {
            {
                sel = HEADS[0];
                for (String h : HEADS)
                    if (h.equals(start.head))
                        sel = h;
            }

            @Override
            protected String listitem(int i) {
                return HEADS[i];
            }

            @Override
            protected int listitems() {
                return HEADS.length;
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(L10n.get("river.head_" + item.toLowerCase()), Coord.z);
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(new Label(L10n.get("river.reeds")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(chopReeds = new CheckBox(L10n.get("river.chop_reeds")) {
            { a = start.chopReeds; }
        }, prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(axes = new UsingTools(UsingTools.Tools.axes), prev.pos("bl").add(UI.scale(0, 5)));
        if (start.axe != null) {
            for (UsingTools.Tool tl : UsingTools.Tools.axes) {
                if (tl.name.equals(start.axe)) {
                    axes.s = tl;
                    break;
                }
            }
        }

        prev = add(new Label(L10n.get("river.time_limit")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(timeLimit = new TextEntry(UI.scale(60), String.valueOf(start.timeLimitMin)), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(new Label(L10n.get("river.leash")), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(leash = new TextEntry(UI.scale(60), String.valueOf(start.leashGrids)), prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(new Label(L10n.get("river.safety")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(avoidDanger = new CheckBox(L10n.get("river.avoid_danger")) {
            { a = start.avoidDanger; }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(new Button(UI.scale(150), L10n.get("botwnd.start")) {
            @Override
            public void click() {
                super.click();
                prop = NRiverExplorerProp.get(NUtils.getUI().sessInfo);
                if (prop != null) {
                    prop.useSea = useSea.a;
                    prop.head = (head.sel != null) ? head.sel : "any";
                    prop.chopReeds = chopReeds.a;
                    if (axes.s != null)
                        prop.axe = axes.s.name;
                    prop.avoidDanger = avoidDanger.a;
                    prop.timeLimitMin = number(timeLimit.text());
                    prop.leashGrids = number(leash.text());
                    NRiverExplorerProp.set(prop);
                }
                isReady = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(new Label(L10n.get("river.hint")), prev.pos("bl").add(UI.scale(0, 5)));
        pack();
    }

    static int number(String s) {
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public boolean check() {
        return isReady;
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            isReady = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }
}
