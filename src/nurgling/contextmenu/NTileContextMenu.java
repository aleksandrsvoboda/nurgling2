package nurgling.contextmenu;

import haven.*;
import nurgling.NStyle;
import nurgling.actions.Action;
import nurgling.sessions.BotExecutor;

import java.util.List;

public class NTileContextMenu extends Widget {
    private static final Tex bl = Resource.loadtex("nurgling/hud/flower/left");
    private static final Tex bm = Resource.loadtex("nurgling/hud/flower/mid");
    private static final Tex br = Resource.loadtex("nurgling/hud/flower/right");
    private static final Tex bhl = Resource.loadtex("nurgling/hud/flower/hleft");
    private static final Tex bhm = Resource.loadtex("nurgling/hud/flower/hmid");
    private static final Tex bhr = Resource.loadtex("nurgling/hud/flower/hright");

    private static final int MAX_VISIBLE = 10;

    private final Coord2d mapPos;
    private final Petal[] petals;
    private final int itemHeight;
    private int maxWidth;
    private Scrollbar sb;
    private UI.Grab mg;
    private UI.Grab kg;

    public NTileContextMenu(Coord2d mapPos, List<TileContextAction> actions) {
        super(Coord.z);
        this.mapPos = mapPos;
        this.petals = new Petal[actions.size()];
        this.itemHeight = bl.sz().y + UI.scale(2);

        int y = 0;
        maxWidth = 0;
        for (int i = 0; i < actions.size(); i++) {
            TileContextAction action = actions.get(i);
            petals[i] = new Petal(action, i);
            add(petals[i], new Coord(0, y));
            y += itemHeight;
            maxWidth = Math.max(petals[i].sz.x, maxWidth);
        }
        for (Petal p : petals)
            p.resize(maxWidth, bl.sz().y);

        if (actions.size() > MAX_VISIBLE) {
            int visibleHeight = MAX_VISIBLE * itemHeight;
            sb = add(new Scrollbar(visibleHeight, 0, actions.size() - MAX_VISIBLE), new Coord(maxWidth, 0));
            resize(maxWidth + sb.sz.x, visibleHeight);
        }
    }

    @Override
    protected void added() {
        if (c.equals(-1, -1))
            c = parent.ui.lcc;
        mg = ui.grabmouse(this);
        kg = ui.grabkeys(this);
    }

    private void choose(Petal petal) {
        if (petal != null) {
            Action action = petal.contextAction.create(mapPos);
            BotExecutor.runAsync(petal.contextAction.label(), action);
        }
        ui.destroy(this);
    }

    private void cancel() {
        ui.destroy(this);
    }

    @Override
    public void draw(GOut g) {
        if (sb != null) {
            sb.max = petals.length - MAX_VISIBLE;
            for (int i = 0; i < petals.length; i++)
                petals[i].c = new Coord(0, (i - sb.val) * itemHeight);
            super.draw(g, true);
        } else {
            super.draw(g, false);
        }
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (sb != null && sb.vis()) {
            Coord sc = ev.c.sub(sb.c);
            if (sc.isect(Coord.z, sb.sz)) {
                sb.mousedown(ev.derive(sc));
                return true;
            }
        }
        if (!ev.propagate(this))
            cancel();
        return true;
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        if (sb != null) {
            sb.ch(ev.a);
            return true;
        }
        return super.mousewheel(ev);
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if (ev.c >= '1' && ev.c <= '9') {
            int idx = ev.c - '1';
            if (idx < petals.length)
                choose(petals[idx]);
            return true;
        } else if (key_esc.match(ev)) {
            cancel();
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

    public class Petal extends Widget {
        public final TileContextAction contextAction;
        public final int num;
        private final Text text;
        private final Text textnum;
        private boolean highlighted;

        public Petal(TileContextAction action, int idx) {
            super(Coord.z);
            this.contextAction = action;
            this.num = idx;
            text = NStyle.flower.render(action.label());
            textnum = NStyle.flower.render(String.valueOf(idx + 1));
            resize(text.sz().x + bl.sz().x + br.sz().x + UI.scale(30), FlowerMenu.ph);
        }

        @Override
        public void draw(GOut g) {
            g.image(highlighted ? bhl : bl, new Coord(0, 0));
            Coord pos = new Coord(0, 0);
            for (pos.x = bl.sz().x; pos.x + bm.sz().x <= maxWidth - br.sz().x; pos.x += bm.sz().x)
                g.image(highlighted ? bhm : bm, pos);
            g.image(highlighted ? bhm : bm, pos, new Coord(sz.x - pos.x - br.sz().x, br.sz().y));
            g.image(textnum.tex(), new Coord(bl.sz().x / 2 - textnum.tex().sz().x / 2 - UI.scale(1), br.sz().y / 2 - textnum.tex().sz().y / 2));
            g.image(text.tex(), new Coord(br.sz().x + bl.sz().x + UI.scale(10), br.sz().y / 2 - text.tex().sz().y / 2));
            g.image(highlighted ? bhr : br, new Coord(maxWidth - br.sz().x, 0));
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
