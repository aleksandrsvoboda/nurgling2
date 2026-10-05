package nurgling.widgets;

import haven.*;
import haven.Window;
import nurgling.*;
import nurgling.conf.*;

import java.awt.*;

import static nurgling.widgets.NCatSelection.fnd;

public class NDraggableWidget extends Widget
{
    protected final String name;
    private UI.Grab dm;
    private Coord doff;
    public Coord target_c;
    protected ICheckBox btnLock;
    protected ICheckBox btnVis;
    private boolean isFlipped = false;
    protected ICheckBox btnFlip;
    public static final IBox box = Window.wbox;

    public final static Coord off = new Coord(UI.scale(10,10));
    public final static Coord delta = new Coord(UI.scale(35,20));
    public Widget content = null;
    TexI label = null;
    public static Text.Furnace fnd = new PUtils.BlurFurn(new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 14, Color.YELLOW).aa(true), UI.scale(1), UI.scale(2), Color.BLACK);
    public NDraggableWidget(Widget content, String name, Coord sz)
    {
        this(name,sz);
        this.content = add(content);
        this.content.visible = btnVis.a;
        content.resize(this.sz.sub(delta));
        content.move(off);
    }

    public NDraggableWidget(String name, Coord sz)
    {
        label = new TexI(fnd.render(name).img);
        this.sz = sz;
        this.name = name;
        add(btnLock = new ICheckBox(NStyle.locki[0], NStyle.locki[1], NStyle.locki[2], NStyle.locki[3])
        {
            @Override
            public void changed(boolean val)
            {
                super.changed(val);
                if(NDraggableWidget.this.parent instanceof GameUI)
                {
                    NDragProp prop = new NDragProp(NDraggableWidget.this.c, val, btnVis.a, name);
                    prop.flip = btnFlip.a;
                    NDragProp.set(name, prop);
                }
            }
        }, new Coord(sz.x - NStyle.locki[0].sz().x - NStyle.locki[0].sz().x / 2, NStyle.locki[0].sz().y / 2));

        add(btnVis = new ICheckBox(NStyle.visi[0], NStyle.visi[1], NStyle.visi[2], NStyle.visi[3])
        {
            @Override
            public boolean checkhit(Coord c) {
                return c.isect(Coord.z, sz);
            }

            @Override
            public void changed(boolean val)
            {
                super.changed(val);
                if(content != null) {
                    content.visible = val;
                }
                if(NDraggableWidget.this.parent instanceof GameUI)
                {
                    NDragProp prop = new NDragProp(NDraggableWidget.this.c, btnLock.a, val, name);
                    prop.flip = btnFlip.a;
                    NDragProp.set(name, prop);
                }
            }
        }, new Coord(sz.x - NStyle.locki[0].sz().x - NStyle.locki[0].sz().x / 2, NStyle.locki[0].sz().y + off.y));

        add(btnFlip = new ICheckBox(NStyle.flipi[0], NStyle.flipi[1], NStyle.flipi[2], NStyle.flipi[3])
        {
            @Override
            public void changed(boolean val)
            {
                super.changed(val);
                if(content!=null)
                {
                    flipContent();
                }
                if(NDraggableWidget.this.parent instanceof GameUI)
                {
                    NDragProp prop = new NDragProp(NDraggableWidget.this.c, btnLock.a, btnVis.a, name);
                    prop.flip = val;
                    NDragProp.set(name, prop);
                }
            }
        }, new Coord(NStyle.locki[0].sz().x / 2, NStyle.locki[0].sz().y/2));

        btnVis.hide();
        btnLock.hide();
        btnFlip.hide();
//        this.sz = sz.add(new Coord(NStyle.locki[0].sz().x, 0));
        NDragProp prop = NDragProp.get(name);
        if (prop.c != Coord.z)
        {
            this.c = new Coord(prop.c);
            this.target_c = prop.c;
            this.btnLock.a = prop.locked;
            this.btnVis.a = prop.vis;
            this.btnFlip.a = prop.flip;
        }
        else
        {
            this.target_c = new Coord(Coord.z);
            this.btnVis.a = true;
        }
        // Apply loaded visibility state to content if it exists
        if(content != null) {
            content.visible = btnVis.a;
        }
    }



    @Override
    public void resize(Coord sz)
    {
        super.resize(sz);
        btnLock.move(new Coord(sz.x - NStyle.locki[0].sz().x - NStyle.locki[0].sz().x / 2, NStyle.locki[0].sz().y / 2));
        btnVis.move(new Coord(sz.x - NStyle.locki[0].sz().x - NStyle.locki[0].sz().x / 2, NStyle.locki[0].sz().y + off.y));
        if(isFlipped)
            btnFlip.move(new Coord(NStyle.locki[0].sz().x / 2, NStyle.locki[0].sz().y/2));
        if(content!=null)
        {
            content.resize(sz.sub(delta));
            content.move(off);
        }
    }

    public static final Tex bg = Resource.loadtex("nurgling/hud/wnd/bg");
    private static final Tex ctl = Resource.loadtex("nurgling/hud/box/tl");

    @Override
    public void draw(GOut g)
    {
        if (ui.core.mode == NCore.Mode.DRAG)
        {
            drawBg(g, sz, ui);
            box.draw(g, Coord.z, sz);

        }
        super.draw(g);
        if (ui.core.mode == NCore.Mode.DRAG) {
            g.aimage(label, sz.div(2), 0.5, 0.5);
        }
    }

    public static void drawBg(GOut g, Coord sz, UI ui) {
        // The flat frame lies on the outer bounds: fill up to those same bounds.
        // Insets from the old ornamental corners left a transparent strip inside it.
        nurgling.NUI nui = ui instanceof nurgling.NUI ? (nurgling.NUI)ui : null;
        int alpha = nui == null ? 255 : (int)(255 * nui.getUIOpacity());
        if(nui != null && nui.getUseSolidBackground()) {
            Color color = nui.getWindowBackgroundColor();
            g.chcolor(color.getRed(), color.getGreen(), color.getBlue(), alpha);
            g.frect(Coord.z, sz);
        } else {
            g.chcolor(255, 255, 255, alpha);
            Tex texture = nui == null ? bg : Window.bg;
            for(int y = 0; y < sz.y; y += texture.sz().y)
                for(int x = 0; x < sz.x; x += texture.sz().x)
                    g.image(texture, new Coord(x, y), Coord.z, sz);
        }
        g.chcolor();
    }
    /**
     * Forward a click to one of the control buttons, translating the event into
     * the button's own coordinate space. The buttons sit visually on top of the
     * content while in DRAG mode, but the content is higher in the event z-order
     * (it is added last), so it would otherwise swallow clicks aimed at the
     * buttons. Handling them explicitly here makes lock/visibility/flip work on
     * every window regardless of what the content does with the event.
     */
    private boolean btnClick(ICheckBox btn, MouseDownEvent ev) {
        return btn.visible() && btn.mousedown(ev.derive(ev.c.sub(btn.c)));
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (ui.core.mode == NCore.Mode.DRAG) {
            if (btnClick(btnLock, ev) || btnClick(btnVis, ev) || btnClick(btnFlip, ev))
                return true;

            if (ev.c.isect(Coord.z, sz)) {
                // Start dragging only when this widget is unlocked, nothing else
                // is currently grabbed and it is the left mouse button.
                if (ev.b == 1 && !btnLock.a && ui.grabs.isEmpty()) {
                    dm = ui.grabmouse(this);
                    doff = ev.c;
                    parent.setfocus(this);
                }
                // Consume the event so it does not fall through to widgets
                // stacked underneath this one. Without this, overlapping
                // draggable widgets would all grab the mouse at once and get
                // stuck to the cursor on release. Only the topmost widget under
                // the pointer should react.
                return true;
            }
        }
        return super.mousedown(ev);
    }


    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if (dm != null && ui.core.mode == NCore.Mode.DRAG)
        {
            NDragProp res = new NDragProp(NDraggableWidget.this.c, btnLock.a, btnVis.a, name);
            res.flip = btnFlip.a;
            NDragProp.set(name, res);
            target_c = new Coord(this.c);
            dm.remove();
            dm = null;
            return true;
        }
        else
        {
            return super.mouseup(ev);
        }
    }


    @Override
    public void mousemove(MouseMoveEvent ev) {
        if (ui.core.mode == NCore.Mode.DRAG)
        {

            if (dm != null)
            {
                Coord prepc = this.c.add(ev.c.add(doff.inv()));
                Coord newc = nurgling.styles.DragGrid.snap(prepc);
                
                // Snap to screen edges
                if(NUtils.getGameUI() != null && NUtils.getGameUI().sz != Coord.z) {
                    int snapThreshold = UI.scale(20); // Distance at which snapping activates
                    Coord screenSz = NUtils.getGameUI().sz;
                    
                    // Snap to left edge
                    if(newc.x < snapThreshold) {
                        newc.x = 0;
                    }
                    // Snap to top edge
                    if(newc.y < snapThreshold) {
                        newc.y = 0;
                    }
                    // Snap to right edge
                    if(newc.x + sz.x > screenSz.x - snapThreshold) {
                        newc.x = screenSz.x - sz.x;
                    }
                    // Snap to bottom edge
                    if(newc.y + sz.y > screenSz.y - snapThreshold) {
                        newc.y = screenSz.y - sz.y;
                    }
                }
                
                this.c = newc;
            }
            else
            {
                if (ev.c.isect(Coord.z, sz))
                {
                    btnLock.mousemove(ev);
                    btnVis.mousemove(ev);
                    if(isFlipped)
                        btnFlip.mousemove(ev);
                }
            }
        }
        else
        {
            super.mousemove(ev);
        }

    }

    @Override
    public void tick(double dt)
    {
        super.tick(dt);
        if (ui.core.mode == NCore.Mode.DRAG)
        {
            btnLock.show();
            btnVis.show();
            if( isFlipped )
                btnFlip.show();
        }
        else
        {
            if (btnLock.visible())
            {
                btnLock.hide();
                btnVis.hide();
                btnFlip.hide();
            }
        }

        if(NUtils.getGameUI()!=null && NUtils.getGameUI().sz!=Coord.z && dm == null)
        {
            // Assign a fresh Coord: c can alias a shared instance (Widget defaults c to Coord.z),
            // and writing through it once moved Coord.z itself and broke every blurred text render.
            int x = (c.x + sz.x > NUtils.getGameUI().sz.x - GameUI.margin.x) ? NUtils.getGameUI().sz.x - sz.x : target_c.x;
            int y = (c.y + sz.y > NUtils.getGameUI().sz.y - GameUI.margin.y) ? NUtils.getGameUI().sz.y - sz.y : target_c.y;
            if (c.x != x || c.y != y)
                c = new Coord(x, y);
        }
    }

    public String getName()
    {
        return name;
    }

    public void flipContent()
    {
        content.flip(btnFlip.a);
        resize(content.sz.add(delta));
    }

    public void setFlipped(boolean val)
    {
        isFlipped = val;
        flipContent();
    }
}
