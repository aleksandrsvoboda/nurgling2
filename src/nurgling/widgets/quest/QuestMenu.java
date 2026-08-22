package nurgling.widgets.quest;

import haven.*;
import nurgling.NStyle;

import java.util.*;

/**
 * Small flower-menu-styled popup, used for the tracker's row and gear menus.
 *
 * Modelled on {@link nurgling.widgets.ExploredAreaMenu}: grabs the mouse and keys, closes on
 * a click outside or Escape, and runs the chosen entry's action.
 */
public class QuestMenu extends Widget
{
    public static final Tex bl = Resource.loadtex("nurgling/hud/flower/left");
    public static final Tex bm = Resource.loadtex("nurgling/hud/flower/mid");
    public static final Tex br = Resource.loadtex("nurgling/hud/flower/right");
    public static final Tex bhl = Resource.loadtex("nurgling/hud/flower/hleft");
    public static final Tex bhm = Resource.loadtex("nurgling/hud/flower/hmid");
    public static final Tex bhr = Resource.loadtex("nurgling/hud/flower/hright");

    private final List<Petal> petals = new ArrayList<>();
    private UI.Grab mg, kg;
    private int len = 0;

    /** One entry: a label and what to do when it is picked. */
    public static class Item
    {
        public final String label;
        public final Runnable action;

        public Item(String label, Runnable action)
        {
            this.label = label;
            this.action = action;
        }
    }

    public QuestMenu(List<Item> items)
    {
        super(Coord.z);
        z(100);
        int y = 0;
        for(int i = 0; i < items.size(); i++) {
            Petal p = add(new Petal(items.get(i), i + 1), new Coord(0, y));
            petals.add(p);
            y += bl.sz().y + UI.scale(2);
            len = Math.max(p.sz.x, len);
        }
        for(Petal p : petals)
            p.resize(len, bl.sz().y);
        resize(len, y);
    }

    @Override
    protected void added()
    {
        if(c.equals(-1, -1))
            c = parent.ui.lcc;
        // Keep the whole menu on screen when it opens near an edge.
        if(parent != null) {
            c.x = Math.max(0, Math.min(c.x, parent.sz.x - sz.x));
            c.y = Math.max(0, Math.min(c.y, parent.sz.y - sz.y));
        }
        mg = ui.grabmouse(this);
        kg = ui.grabkeys(this);
    }

    @Override
    public void destroy()
    {
        if(mg != null)
            mg.remove();
        if(kg != null)
            kg.remove();
        super.destroy();
    }

    @Override
    public boolean mousedown(MouseDownEvent ev)
    {
        if(!ev.propagate(this))
            close();
        return true;
    }

    @Override
    public boolean keydown(KeyDownEvent ev)
    {
        char key = ev.c;
        if((key >= '1') && (key <= '9')) {
            int opt = key - '1';
            if(opt < petals.size())
                choose(petals.get(opt));
            return true;
        } else if(key_esc.match(ev)) {
            close();
            return true;
        }
        return false;
    }

    private void choose(Petal p)
    {
        Runnable action = p.item.action;
        close();
        if(action != null)
            action.run();
    }

    public void close()
    {
        ui.destroy(this);
    }

    public class Petal extends Widget
    {
        public final Item item;
        private final Text text, textnum;
        private boolean hl = false;

        public Petal(Item item, int num)
        {
            super(Coord.z);
            this.item = item;
            this.text = NStyle.flower.render(item.label);
            this.textnum = NStyle.flower.render(String.valueOf(num));
            resize(text.sz().x + bl.sz().x + br.sz().x + UI.scale(30), FlowerMenu.ph);
        }

        @Override
        public void draw(GOut g)
        {
            g.image(hl ? bhl : bl, Coord.z);
            Coord pos = new Coord(0, 0);
            for(pos.x = bl.sz().x; pos.x + bm.sz().x <= len - br.sz().x; pos.x += bm.sz().x)
                g.image(hl ? bhm : bm, pos);
            g.image(hl ? bhm : bm, pos, new Coord(sz.x - pos.x - br.sz().x, br.sz().y));
            g.image(textnum.tex(), new Coord(bl.sz().x / 2 - textnum.tex().sz().x / 2 - UI.scale(1),
                                             br.sz().y / 2 - textnum.tex().sz().y / 2));
            g.image(text.tex(), new Coord(br.sz().x + bl.sz().x + UI.scale(10),
                                          br.sz().y / 2 - text.tex().sz().y / 2));
            g.image(hl ? bhr : br, new Coord(len - br.sz().x, 0));
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            choose(this);
            return true;
        }

        @Override
        public void mousemove(MouseMoveEvent ev)
        {
            hl = ev.c.isect(Coord.z, sz);
            super.mousemove(ev);
        }
    }
}
