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

    /** Shared location for the visible corner and its triangular mouse target. */
    private Coord resizePosition() {
        return sz.sub(sizeru.sz()).sub(NStyle.locki[0].sz().x / 2, UI.scale(8));
    }

    private boolean resizeHit(Coord c) {
        Coord p = c.sub(resizePosition());
        Coord size = sizeru.sz();
        return p.isect(Coord.z, size) && p.x * size.y + p.y * size.x >= size.x * size.y;
    }
    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (ui.core.mode == NCore.Mode.DRAG && !btnLock.a) {
            if ((ev.b == 1) && resizeHit(ev.c)) {
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
            isHighlighted = resizeHit(ev.c);

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
            if (drag != null)
            {
                if (!btnLock.a)
                    g.image(sizerd, resizePosition());
            }
            else if (isHighlighted)
            {
                g.image(sizerh, resizePosition());
            }
            else
                g.image(sizeru, resizePosition());
        }
    }

}
