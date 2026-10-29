package nurgling.overlays;

import haven.*;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;
import nurgling.NMapView;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.widgets.Specialisation;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class NAreaLabel extends Sprite implements RenderTree.Node, PView.Render2D{
    private boolean isSelected = false;
    protected Coord3f pos;
    public TexI label = null;
    public TexI sellabel = null;
    public TexI graylabel = null;
    protected TexI img = null;
    NArea area;
    public Coord sc;
    boolean forced = false;
    int sizeSpec;
    /* A label's images (outlined text and specialisation icons) take
     * about a millisecond to render, and opening the area settings
     * makes a label for every area at once: that froze the game for a
     * good part of a second. They are rendered off the UI thread, once
     * the label comes into view; until then it shows nothing. */
    private static final ExecutorService renderer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Area label renderer");
        t.setDaemon(true);
        return t;
    });
    private boolean stale = true;
    private Future<TexI[]> making = null;

    public NAreaLabel(Owner owner, NArea area) {
        super(owner, null);
        pos = new Coord3f(0,0,2);
        this.area = area;
        sizeSpec = area.spec.size();
    }

    /* Renders the label again, e.g. after a rename. */
    public void update()
    {
        stale = true;
    }

    private void make() {
        if((making != null) && making.isDone()) {
            if(making.state() == Future.State.SUCCESS) {
                TexI[] texs = making.resultNow();
                label = texs[0];
                sellabel = texs[1];
                graylabel = texs[2];
            } else {
                new Warning(making.exceptionNow(), "could not render the label of area " + area.name).issue();
            }
            making = null;
        }
        if(stale && (making == null)) {
            stale = false;
            String name = area.name;
            List<String> specs = new ArrayList<>();
            for(NArea.Specialisation sp : area.spec)
                specs.add(sp.name);
            making = renderer.submit(() -> render(name, specs));
        }
    }

    private static TexI[] render(String name, List<String> specs) {
        BufferedImage img = NStyle.openings.render(name).img;
        BufferedImage selimg = NStyle.selopenings.render(name).img;
        BufferedImage grayimg = NStyle.disabledopenings.render(name).img;
        if(!specs.isEmpty()) {
            int iconSize = UI.scale(32);
            BufferedImage first = Specialisation.findSpecialisation(specs.get(0)) == null ? null : Specialisation.findSpecialisation(specs.get(0)).image;
            BufferedImage ret = TexI.mkbuf(new Coord(iconSize, iconSize));
            Graphics g = ret.getGraphics();
            g.drawImage(first, 0, 0, iconSize, iconSize, null);
            g.dispose();
            first = ret;
            if (specs.size() > 1) {

                for (int i = 1; i < specs.size(); i++) {
                    first = ItemInfo.catimgsh(UI.scale(5), first, new Coord(iconSize, iconSize), Specialisation.findSpecialisation(specs.get(i)).image);
                }
            }
            img = ItemInfo.catimgsh(UI.scale(5), img, first);
            selimg = ItemInfo.catimgsh(UI.scale(5), selimg, first);
            grayimg = ItemInfo.catimgsh(UI.scale(5), grayimg, first);
        }
        return new TexI[] {new TexI(img), new TexI(selimg), new TexI(grayimg)};
    }

    @Override
    public boolean tick(double dt) {
        if(NUtils.getGameUI()!=null) {
            isSelected =NUtils.getGameUI().areas!=null && NUtils.getGameUI().areas.al.sel != null && NUtils.getGameUI().areas.al.sel.area == area;
            if (NUtils.getGameUI() != null) {
                if (area.spec.size() != sizeSpec) {
                    sizeSpec = area.spec.size();
                    update();

                }
                return NUtils.findGob(((Gob) owner).id) == null;
            }
        }
        return true;
    }

    @Override
    public void draw(GOut g, Pipe state) {
        sc = Homo3D.obj2view(pos, state, Area.sized(g.sz())).round2();
        if ((label != null) || sc.isect(g.sz().div(2).inv(), g.sz().mul(2)))
            make();
        if (label != null)
            if(isSelected)
            {
                g.aimage(sellabel, sc, 0.5, 0.5);
            }
            else if(area.hide && graylabel != null)
            {
                g.aimage(graylabel, sc, 0.5, 0.5);
            }
            else {
                g.aimage(label, sc, 0.5, 0.5);
            }
    }

    public boolean isect(Coord pc) {
        if((sc == null) || (label == null))
            return false;
        Coord ul = sc.sub(label.sz().div(2));
        return pc.isect(ul, label.sz());
    }
}
