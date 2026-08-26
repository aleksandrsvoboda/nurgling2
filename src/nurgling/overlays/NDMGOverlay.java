package nurgling.overlays;

import haven.*;
import haven.render.Homo3D;
import haven.render.Pipe;
import nurgling.NUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.WeakHashMap;

public class NDMGOverlay extends Sprite implements PView.Render2D {

    /**
     * Clears all NDMGOverlay overlays from all Gobs
     */
    public static void clearAll() {
        if (NUtils.getGameUI() == null || NUtils.getGameUI().ui == null || 
            NUtils.getGameUI().ui.sess == null || NUtils.getGameUI().ui.sess.glob == null) {
            return;
        }
        synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
            for (Gob gob : NUtils.getGameUI().ui.sess.glob.oc) {
                Gob.Overlay ol = gob.findol(NDMGOverlay.class);
                if (ol != null) {
                    ol.remove();
                }
            }
        }
        synchronized (live) {
            live.clear();
        }
    }

    /* Gob.addcustomol() defers the actual add to a loader thread, so findol() cannot
     * see a freshly created overlay yet. A single hit sends its hard- and soft-health
     * scores together and both land inside that window, so without a registry of
     * in-flight overlays each score would build its own sprite and the two would draw
     * on top of each other at the same point above the gob. Weak keys so a gob leaving
     * view takes its entry with it. */
    private static final Map<Gob, NDMGOverlay> live = new WeakHashMap<>();
    public static final Text.Foundry fnd = new Text.Foundry(Text.sans, 12);
    Color[] colt = new Color[]{Color.RED, Color.YELLOW, Color.GREEN};
    TexI[] dmgt = new TexI[3];
    int[] dmg = new int[3];

    public NDMGOverlay(Owner owner) {
        super(owner, null);
    }

    public static void IsDMG(Message sdt, Gob g) {
        if (sdt.rt == 7) {
            MessageBuf buf = new MessageBuf(sdt);
            int dmg = buf.int32();
            buf.uint8();
            int type = buf.uint16();

        }
    }

    public static void IsDMG(int col, int num, Gob owner) {
        int type;
        if (col == 64527) {
            type = 1;
        } else if (col == 36751) {
            type = 2;
        } else if (col == 61455) {
            type = 0;
        } else {
            return;
        }
        NDMGOverlay ol;
        synchronized (live) {
            Gob.Overlay gol = owner.findol(NDMGOverlay.class);
            ol = (gol != null) ? (NDMGOverlay) gol.spr : live.get(owner);
            if (ol == null) {
                ol = new NDMGOverlay(owner);
                live.put(owner, ol);
                owner.addcustomol(ol);
            }
        }
        ol.updDmg(num, type);
    }

    public synchronized void updDmg(int dmg, int type) {
        this.dmg[type] += dmg;
        dmgt[type] = new TexI(Utils.outline2(fnd.render(Integer.toString(this.dmg[type]), colt[type]).img, Utils.contrast(colt[type])));
        int w = 0;
        int h = 0;
        for(int i = 0; i < 3; i++) {
            if (dmgt[i] != null) {
                w += dmgt[i].sz().x + UI.scale(2);
                h = Math.max(h, dmgt[i].sz().y + UI.scale(2));
            }
        }
        BufferedImage ret = TexI.mkbuf(new Coord(w, h));
        Graphics g = ret.getGraphics();
        Coord pos = new Coord(0, 0);
        for(int i = 0; i < 3; i++) {
            if(dmgt[i] != null) {
                g.drawImage(dmgt[i].back, pos.x, pos.y, null);
                pos.x += dmgt[i].sz().x + UI.scale(2);
            }
        }
        g.dispose();
        pending = ret;
    }

    /* Set on the thread that parses the score message, picked up and uploaded once by
     * the render thread. Converting on every frame instead meant a fresh texture upload
     * per damaged gob per frame. */
    private volatile BufferedImage pending = null;
    private TexI curOl = null;

    /* Dark enough to read a thin stroked digit against grass, snow or firelight. */
    private static final Color backing = new Color(0, 0, 0, 187);
    private static final Coord bpad = UI.scale(new Coord(3, 1));

    public void draw(GOut g, Pipe state) {
        BufferedImage upd = pending;
        if(upd != null) {
            pending = null;
            if(curOl != null)
                curOl.dispose();
            curOl = new TexI(upd);
        }
        if(curOl == null)
            return;
        Coord sc = Homo3D.obj2view(Coord3f.zu.add(0, 0, 16), state, Area.sized(Coord.z, g.sz())).round2();
        if(sc == null)
            return;
        Coord sz = curOl.sz();
        Coord ul = sc.sub(sz.div(2));
        g.chcolor(backing);
        g.frect2(ul.sub(bpad), ul.add(sz).add(bpad));
        g.chcolor();
        g.image(curOl, ul);
    }

    @Override
    public boolean tick(double dt) {
        return super.tick(dt);
    }
}
