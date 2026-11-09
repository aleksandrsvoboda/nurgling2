package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NRiverExplorerProp;
import nurgling.i18n.L10n;

/** River Explorer's start window: whether the sea may be used, reeds and the axe, limits and safety. */
public class RiverExplorerWnd extends Window implements Checkable {
    CheckBox useSea, clockwise, unclockwise, chopReeds, avoidDanger;
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

        prev = add(new Label(L10n.get("river.coast_direction")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(clockwise = new CheckBox(L10n.get("explorer.clockwise")) {
            { a = start.clockwise; }

            @Override
            public void set(boolean a) {
                super.set(a);
                unclockwise.a = !a;
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(unclockwise = new CheckBox(L10n.get("explorer.counterclockwise")) {
            { a = !start.clockwise; }

            @Override
            public void set(boolean a) {
                super.set(a);
                clockwise.a = !a;
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
                    prop.clockwise = clockwise.a;
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
