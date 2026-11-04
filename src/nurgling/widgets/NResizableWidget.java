package nurgling.widgets;

import haven.*;
import nurgling.*;
import nurgling.conf.*;

import java.util.*;

public class NResizableWidget extends NDraggableWidget
{
    public NResizableWidget(Widget content, String name, Coord sz)
    {
        super(content, name, NResizeProp.find(name) != null ? Objects.requireNonNull(NResizeProp.find(name)) : sz);
    }


    public NResizableWidget(String name)
    {
        super(name, NResizeProp.find(name) != null ? Objects.requireNonNull(NResizeProp.find(name)) : new Coord(200, 200));
    }

    public static final Tex sizeru = Resource.loadtex("nurgling/hud/wnd/sizer/u");
    public static final Tex sizerd = Resource.loadtex("nurgling/hud/wnd/sizer/d");
    public static final Tex sizerh = Resource.loadtex("nurgling/hud/wnd/sizer/h");
    public Coord minSize = new Coord(200,200);
        private UI.Grab drag;
    private Coord dragc;

    boolean isHighlighted = false;

    /** The drag-mode resize handle, in the box's bottom-right corner so it stays on screen. */
    private Area sizer() {
        Area b = box();
        int pad = UI.scale(2);
        return Area.sized(b.br.sub(sizeru.sz()).sub(pad, pad), sizeru.sz());
    }

    /** Outside drag mode the frame's own bottom-right corner (beyond the content) still resizes. */
    private boolean onSizer(Coord c) {
        if (ui.core.mode == NCore.Mode.DRAG)
            return sizer().contains(c);
        return (c.x - (sz.x - sizeru.sz().x/2 - UI.scale(15))) * ( - sizeru.sz().x/2) - (c.y - (sz.y - sizeru.sz().y/2)) * ( sizeru.sz().y/2) <= 0;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (!btnLock.a) {
            if ((ev.b == 1) && onSizer(ev.c)) {
                if (drag == null) {
                    drag = ui.grabmouse(this);
                    dragc = sz.sub(ev.c);
                    return (true);
                }
            }
        }
        return super.mousedown(ev);
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        if(drag != null) {
            Coord nsz = ev.c.add(dragc);
            nsz.x = Math.max(nsz.x, UI.scale(minSize.x));
            nsz.y = Math.max(nsz.y, UI.scale(minSize.y));
            resize(nsz);
            NResizeProp.set(name, new NResizeProp( NResizableWidget.this.sz , name));
        }
        else
        {
            isHighlighted = onSizer(ev.c) && ev.c.isect(Coord.z, sz);

        }
        super.mousemove(ev);
    }


    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if((ev.b == 1) && (drag != null)) {
            drag.remove();
            drag = null;
            return(true);
        }
        return super.mouseup(ev);
    }


    @Override
    public void resize(Coord sz)
    {
        if(drag!=null)
            super.resize(sz);
        else
        {
            ArrayList<NResizeProp> resizeProps = ((ArrayList<NResizeProp>) NConfig.get(NConfig.Key.resizeprop));
            if (resizeProps == null)
                resizeProps = new ArrayList<>();
            for (NResizeProp prop : resizeProps)
            {
                if (prop.name.equals(name))
                {
                    super.resize(prop.sz);
                }
            }
        }
    }

    @Override
    public void draw(GOut g) {
        super.draw(g);
        if (ui.core.mode == NCore.Mode.DRAG)
        {
            Coord at = sizer().ul;
            if (drag != null)
            {
                if (!btnLock.a)
                    g.image(sizerd, at);
            }
            else if (isHighlighted)
            {
                g.image(sizerh, at);
            }
            else
                g.image(sizeru, at);
        }
    }

}
