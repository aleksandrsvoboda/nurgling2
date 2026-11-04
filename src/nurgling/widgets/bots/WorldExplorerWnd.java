package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NWorldExplorerProp;
import nurgling.i18n.L10n;

/** Ocean Explorer's start window: which edge to follow, which way round the land, and the safety options. */
public class WorldExplorerWnd extends Window implements Checkable {

    CheckBox clockwise;
    CheckBox unclockwise;
    CheckBox deep;
    CheckBox shallow;
    CheckBox avoidDanger;
    CheckBox stopAfterLap;

    public WorldExplorerWnd() {
        super(new Coord(200,200), L10n.get("explorer.wnd_title"));
        NWorldExplorerProp startprop = NWorldExplorerProp.get(NUtils.getUI().sessInfo);
        if (startprop == null) startprop = new NWorldExplorerProp("", "");
        final NWorldExplorerProp finalStartprop = startprop;

        prev = add(new Label(L10n.get("explorer.settings")));
        prev = add(shallow = new CheckBox(L10n.get("explorer.deep_shallow"))
        {
            {
                a = !finalStartprop.deeper;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
                deep.a = !a;
            }
        }, prev.pos("bl").add(UI.scale(0,5)));
        prev = add(deep = new CheckBox(L10n.get("explorer.deep_deeper"))
        {
            {
                a = finalStartprop.deeper;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
                shallow.a = !a;
            }
        }, prev.pos("bl").add(UI.scale(0,5)));

        prev = add(new Label(L10n.get("explorer.direction")), prev.pos("bl").add(UI.scale(0,10)));
        prev = add(clockwise = new CheckBox(L10n.get("explorer.clockwise")){
            {
                a = finalStartprop.clockwise;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
                unclockwise.a = !a;
            }
        }, prev.pos("bl").add(UI.scale(0,5)));
        prev = add(unclockwise = new CheckBox(L10n.get("explorer.counterclockwise")){
            {
                a = !finalStartprop.clockwise;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
                clockwise.a = !a;
            }
        }, prev.pos("bl").add(UI.scale(0,5)));

        prev = add(new Label(L10n.get("explorer.safety")), prev.pos("bl").add(UI.scale(0,10)));
        prev = add(avoidDanger = new CheckBox(L10n.get("explorer.avoid_danger")){
            {
                a = finalStartprop.avoidDanger;
            }
        }, prev.pos("bl").add(UI.scale(0,5)));
        prev = add(stopAfterLap = new CheckBox(L10n.get("explorer.stop_lap")){
            {
                a = finalStartprop.stopAfterLap;
            }
        }, prev.pos("bl").add(UI.scale(0,5)));

        prev = add(new Button(UI.scale(150), L10n.get("botwnd.start")){
            @Override
            public void click() {
                super.click();
                prop = NWorldExplorerProp.get(NUtils.getUI().sessInfo);
                if (prop != null) {
                    prop.deeper = deep.a;
                    prop.clockwise = clockwise.a;
                    prop.avoidDanger = avoidDanger.a;
                    prop.stopAfterLap = stopAfterLap.a;
                    NWorldExplorerProp.set(prop);
                }
                isReady = true;
            }
        }, prev.pos("bl").add(UI.scale(0,10)));
        pack();
    }

    @Override
    public boolean check() {
        return isReady;
    }

    boolean isReady = false;

    @Override
    public void wdgmsg(String msg, Object... args) {
        if(msg.equals("close")) {
            isReady = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }
    public NWorldExplorerProp prop = null;
}
