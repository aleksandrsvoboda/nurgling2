package nurgling.widgets.calculators;

import haven.*;
import nurgling.i18n.L10n;

import java.util.ArrayList;
import java.util.List;

/**
 * Window hosting planning calculators, one tab each. Calculators only compute; they never
 * change bot state.
 */
public class NCalculatorsWindow extends Window {
    private final List<Widget> tabs = new ArrayList<>();

    public NCalculatorsWindow() {
        super(UI.scale(640, 470), L10n.get("calc.title"));
        int tabW = UI.scale(120);
        int tabY = 0;
        int contentY = UI.scale(30);

        addTab(L10n.get("calc.tab.cheese_racks"), new CheeseRackCalculatorPanel(UI.scale(640, 440)),
                tabW, tabY, contentY);
        select(0);
    }

    private void addTab(String title, Widget content, int tabW, int tabY, int contentY) {
        int idx = tabs.size();
        add(new Button(tabW, title, () -> select(idx)), new Coord(idx * (tabW + UI.scale(5)), tabY));
        tabs.add(add(content, new Coord(0, contentY)));
    }

    private void select(int idx) {
        for (int i = 0; i < tabs.size(); i++) {
            if (i == idx)
                tabs.get(i).show();
            else
                tabs.get(i).hide();
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && msg.equals("close")) {
            reqdestroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }
}
