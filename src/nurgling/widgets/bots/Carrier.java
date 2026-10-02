package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NCarrierProp;
import nurgling.i18n.L10n;

public class Carrier extends Window implements Checkable {

    TextEntry textEntry;
    // Null when the window is opened by a bot that does not place objects
    TextEntry spreadEntry = null;

    public Carrier() {
        this(false);
    }

    public Carrier(boolean withSpread) {
        super(new Coord(200,200), L10n.get("carrier.wnd_title"));
        NCarrierProp startprop = NCarrierProp.get(NUtils.getUI().sessInfo);
        prev = add(new Label(L10n.get("carrier.object_name")));
        prev = add(textEntry = new TextEntry(300, startprop == null || startprop.object == null ? "" : startprop.object), prev.pos("bl").add(UI.scale(0,5)));
        prev = add(new Label(L10n.get("carrier.hint")), prev.pos("bl").add(UI.scale(0, 2)));
        if (withSpread) {
            prev = add(new Label(L10n.get("carrier.spread")), prev.pos("bl").add(UI.scale(0, 10)));
            prev = add(spreadEntry = new TextEntry(UI.scale(60), String.valueOf(startprop == null ? 0 : startprop.spread)), prev.pos("bl").add(UI.scale(0, 5)));
            prev = add(new Label(L10n.get("carrier.spread_hint")), prev.pos("bl").add(UI.scale(0, 2)));
        }
        prev = add(new Button(UI.scale(150), L10n.get("botwnd.start")){
            @Override
            public void click() {
                super.click();
                prop = NCarrierProp.get(NUtils.getUI().sessInfo);
                if (prop != null) {
                    prop.object = textEntry.text();
                    if (spreadEntry != null)
                        prop.spread = parseSpread(spreadEntry.text());
                    NCarrierProp.set(prop);
                }
                isReady = true;
            }
        }, prev.pos("bl").add(UI.scale(0,5)));
        pack();
    }

    private static int parseSpread(String txt) {
        try {
            return Math.max(0, Integer.parseInt(txt.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
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
    public NCarrierProp prop = null;
}
