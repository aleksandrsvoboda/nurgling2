package nurgling.widgets.charsel;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.widgets.login.NLoginTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A row of text tabs with counts ("All worlds 7 | World 16 4 | World 16.1 3") on a hairline, the
 * picked one underlined in the accent colour. The first tab has the key null and means "all".
 */
public class NWorldTabs extends Widget {
    private static final int PADX = UI.scale(12);
    /** Padding is squeezed to this before a tab is allowed to run off the edge. */
    private static final int MINPAD = UI.scale(4);
    private static final int NAMEGAP = UI.scale(5);
    private final Consumer<String> onpick;
    private final List<String> keys = new ArrayList<>();
    private final List<Text> names = new ArrayList<>(), counts = new ArrayList<>();
    private final List<Integer> xs = new ArrayList<>(), ws = new ArrayList<>();
    private String sel = null, sig = null;
    private int hover = -1;
    private int padx = PADX;

    public NWorldTabs(int w, Consumer<String> onpick) {
        super(Coord.of(w, UI.scale(26)));
        this.onpick = onpick;
    }

    public void set(List<String> worlds, Map<String, Integer> count, int total, String sel) {
        this.sel = sel;
        String nsig = worlds + "|" + count + "|" + total;
        if (nsig.equals(sig))
            return;
        sig = nsig;
        keys.clear();
        names.clear();
        counts.clear();
        xs.clear();
        ws.clear();
        keys.add(null);
        names.add(NLoginTheme.tab.render(L10n.get("charlist.all_worlds")));
        counts.add(NLoginTheme.tabcount.render(Integer.toString(total)));
        for (String w : worlds) {
            keys.add(w);
            names.add(NLoginTheme.tab.render(w));
            counts.add(NLoginTheme.tabcount.render(Integer.toString(count.getOrDefault(w, 0))));
        }
        /* Fit the padding to the width instead of letting the last tab's count run off the edge. */
        int content = 0;
        for (int i = 0; i < keys.size(); i++)
            content += names.get(i).sz().x + NAMEGAP + counts.get(i).sz().x;
        padx = PADX;
        if (!keys.isEmpty())
            padx = Utils.clip((sz.x - content) / (2 * keys.size()), MINPAD, PADX);
        int x = 0;
        for (int i = 0; i < keys.size(); i++) {
            int w = names.get(i).sz().x + NAMEGAP + counts.get(i).sz().x + (2 * padx);
            xs.add(x);
            ws.add(w);
            x += w;
        }
    }

    public void draw(GOut g) {
        g.chcolor(255, 255, 255, 36);
        g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
        g.chcolor();
        for (int i = 0; i < keys.size(); i++) {
            boolean on = Objects.equals(keys.get(i), sel);
            int x = xs.get(i) + padx;
            Text n = names.get(i), c = counts.get(i);
            if (!on && (i != hover))
                g.chcolor(NLoginTheme.dim);
            g.image(n.tex(), Coord.of(x, (sz.y - n.sz().y) / 2));
            g.chcolor();
            g.image(c.tex(), Coord.of(x + n.sz().x + NAMEGAP, (sz.y - c.sz().y) / 2));
            if (on) {
                g.chcolor(NLoginTheme.accent);
                g.frect(Coord.of(xs.get(i), sz.y - UI.scale(2)), Coord.of(ws.get(i), UI.scale(2)));
                g.chcolor();
            }
        }
    }

    private int tabat(Coord c) {
        if ((c.y < 0) || (c.y >= sz.y))
            return (-1);
        for (int i = 0; i < keys.size(); i++) {
            if ((c.x >= xs.get(i)) && (c.x < xs.get(i) + ws.get(i)))
                return (i);
        }
        return (-1);
    }

    public void mousemove(MouseMoveEvent ev) {
        hover = tabat(ev.c);
        super.mousemove(ev);
    }

    public boolean mousedown(MouseDownEvent ev) {
        int i = tabat(ev.c);
        if ((ev.b == 1) && (i >= 0)) {
            sel = keys.get(i);
            onpick.accept(sel);
            return (true);
        }
        return (super.mousedown(ev));
    }
}
