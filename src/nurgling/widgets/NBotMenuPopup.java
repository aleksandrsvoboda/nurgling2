package nurgling.widgets;

import haven.*;
import nurgling.NStyle;

import java.util.ArrayList;
import java.util.List;

/** Right-click menu of the bot menu, drawn like the nurgling object context menu. */
public class NBotMenuPopup extends Widget {
    private static final Tex bl = Resource.loadtex("nurgling/hud/flower/left");
    private static final Tex bm = Resource.loadtex("nurgling/hud/flower/mid");
    private static final Tex br = Resource.loadtex("nurgling/hud/flower/right");
    private static final Tex bhl = Resource.loadtex("nurgling/hud/flower/hleft");
    private static final Tex bhm = Resource.loadtex("nurgling/hud/flower/hmid");
    private static final Tex bhr = Resource.loadtex("nurgling/hud/flower/hright");

    public static class Entry {
        final String label;
        final Runnable action;

        public Entry(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private final List<Petal> petals = new ArrayList<>();
    private final int width;
    private UI.Grab mg, kg;

    public NBotMenuPopup(List<Entry> entries) {
        super(Coord.z);
        int w = 0;
        for (int i = 0; i < entries.size(); i++) {
            Petal p = new Petal(entries.get(i), i);
            petals.add(p);
            w = Math.max(w, p.sz.x);
        }
        width = w;
        int y = 0;
        for (Petal p : petals) {
            p.resize(width, bl.sz().y);
            add(p, new Coord(0, y));
            y += bl.sz().y + UI.scale(2);
        }
        resize(width, Math.max(0, y - UI.scale(2)));
    }

    @Override
    protected void added() {
        mg = ui.grabmouse(this);
        kg = ui.grabkeys(this);
    }

    private void choose(Petal p) {
        ui.destroy(this);
        if (p != null)
            p.entry.action.run();
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (!ev.propagate(this))
            ui.destroy(this);
        return true;
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if (ev.c >= '1' && ev.c <= '9') {
            int idx = ev.c - '1';
            if (idx < petals.size())
                choose(petals.get(idx));
            return true;
        } else if (key_esc.match(ev)) {
            ui.destroy(this);
            return true;
        }
        return false;
    }

    @Override
    public void destroy() {
        if (mg != null) mg.remove();
        if (kg != null) kg.remove();
        super.destroy();
    }

    private class Petal extends Widget {
        final Entry entry;
        private final Text text, textnum;
        private boolean highlighted;

        Petal(Entry entry, int idx) {
            super(Coord.z);
            this.entry = entry;
            text = NStyle.flower.render(entry.label);
            textnum = NStyle.flower.render(String.valueOf(idx + 1));
            resize(text.sz().x + bl.sz().x + br.sz().x + UI.scale(30), FlowerMenu.ph);
        }

        @Override
        public void draw(GOut g) {
            g.image(highlighted ? bhl : bl, Coord.z);
            Coord pos = new Coord(0, 0);
            for (pos.x = bl.sz().x; pos.x + bm.sz().x <= width - br.sz().x; pos.x += bm.sz().x)
                g.image(highlighted ? bhm : bm, pos);
            g.image(highlighted ? bhm : bm, pos, new Coord(sz.x - pos.x - br.sz().x, br.sz().y));
            g.image(textnum.tex(), new Coord(bl.sz().x / 2 - textnum.tex().sz().x / 2 - UI.scale(1), br.sz().y / 2 - textnum.tex().sz().y / 2));
            g.image(text.tex(), new Coord(br.sz().x + bl.sz().x + UI.scale(10), br.sz().y / 2 - text.tex().sz().y / 2));
            g.image(highlighted ? bhr : br, new Coord(width - br.sz().x, 0));
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            choose(this);
            return true;
        }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            highlighted = ev.c.isect(Coord.z, sz);
            super.mousemove(ev);
        }
    }
}
