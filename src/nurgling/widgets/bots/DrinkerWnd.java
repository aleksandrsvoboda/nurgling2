package nurgling.widgets.bots;

import haven.*;
import nurgling.actions.bots.drinker.Vessels;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One fixed row per drink: name, the cup to drink it from, and how many sips to take. 0 skips the
 * drink. Problems found by the bot on the previous Start (missing areas) are listed under the rows.
 */
public class DrinkerWnd extends Window implements Checkable {

    private static final Color WARN = new Color(255, 179, 107);

    private final LinkedHashMap<String, TextEntry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, Dropbox<String>> cupBoxes = new LinkedHashMap<>();
    boolean isReady = false;
    /** Sips per drink chosen on Start; null when the window was closed instead. */
    public LinkedHashMap<String, Integer> sips = null;
    /** The cup chosen for each drink on Start. */
    public final LinkedHashMap<String, String> cups = new LinkedHashMap<>();

    public DrinkerWnd(Map<String, Integer> startSips, Map<String, String> startCups, List<String> problems) {
        super(new Coord(200, 200), "Drinker");
        List<String> cupNames = new ArrayList<>();
        for (Vessels.Cup c : Vessels.CUPS)
            cupNames.add(c.name);
        int cupX = UI.scale(90);
        int sipX = UI.scale(260);
        int rowH = UI.scale(22);
        prev = add(new Label("Drink"), Coord.z);
        add(new Label("Cup"), new Coord(cupX, 0));
        add(new Label("Sips"), new Coord(sipX, 0));
        int y = prev.pos("bl").y + UI.scale(5);
        for (Vessels.Drink d : Vessels.DRINKS) {
            add(new Label(d.name), new Coord(0, y + UI.scale(3)));
            Dropbox<String> cupBox = add(new Dropbox<String>(UI.scale(160), cupNames.size(), UI.scale(16)) {
                @Override
                protected String listitem(int i) { return cupNames.get(i); }
                @Override
                protected int listitems() { return cupNames.size(); }
                @Override
                protected void drawitem(GOut g, String item, int i) { g.text(item, Coord.z); }
            }, new Coord(cupX, y));
            String cup = startCups.get(d.name);
            cupBox.sel = cup != null && Vessels.isCup(cup) ? cup : d.cup;
            cupBoxes.put(d.name, cupBox);
            Integer n = startSips.get(d.name);
            entries.put(d.name, add(new TextEntry(UI.scale(50), String.valueOf(n == null ? 0 : n)), new Coord(sipX, y)));
            y += rowH;
        }
        prev = add(new Label(""), new Coord(0, y));
        for (String problem : problems) {
            Label warn = new Label(problem);
            warn.setcolor(WARN);
            prev = add(warn, prev.pos("bl").add(UI.scale(0, 5)));
        }

        prev = add(new Button(UI.scale(150), "Start") {
            @Override
            public void click() {
                super.click();
                LinkedHashMap<String, Integer> chosen = new LinkedHashMap<>();
                for (Map.Entry<String, TextEntry> e : entries.entrySet()) {
                    int n = 0;
                    try {
                        n = Math.max(0, Integer.parseInt(e.getValue().text().trim()));
                    } catch (NumberFormatException ignored) {
                        // Anything that isn't a number counts as 0, i.e. skip this drink.
                    }
                    chosen.put(e.getKey(), n);
                }
                for (Map.Entry<String, Dropbox<String>> e : cupBoxes.entrySet())
                    cups.put(e.getKey(), e.getValue().sel);
                sips = chosen;
                isReady = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));
        pack();
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
