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
    /** Orientations the turn button steps through: 0 = no button, 2 = flip (action bars), 4 = cycle. */
    private int rotations = 0;
    private int rot = 0;
    protected ICheckBox btnFlip;

    public final static Coord off = new Coord(UI.scale(10,10));
    public final static Coord delta = new Coord(UI.scale(35,20));
    public Widget content = null;

    /**
     * Content with four orientations (see {@link #setRotations}). A content that grows up or to the
     * left reports by how much in {@link #anchorShift}: the widget is drawn that far up/left of its
     * saved position, so the part the player placed stays where it was.
     */
    public interface Turnable {
        void rotate(int rot);

        Coord anchorShift();
    }
    TexI label = null;
    public static Text.Furnace fnd = new PUtils.BlurFurn(new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 14, Color.YELLOW).aa(true), UI.scale(1), UI.scale(2), Color.BLACK);
    public NDraggableWidget(Widget content, String name, Coord sz)
    {
        this(name,sz);
        this.content = add(content);
        this.content.visible = btnVis.a;
        // Drag-mode controls above the content: contents that fill their whole area (bot menu,
        // history bar) would otherwise hide the turn button at the top-left corner.
        btnLock.raise();
        btnVis.raise();
        btnFlip.raise();
        content.resize(this.sz.sub(delta));
        content.move(off);
        layoutControls();
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
                    saveProp(target_c);
            }
        }, Coord.z);

        add(btnVis = new ICheckBox(NStyle.visi[0], NStyle.visi[1], NStyle.visi[2], NStyle.visi[3])
        {
            @Override
            public void changed(boolean val)
            {
                super.changed(val);
                if(content != null) {
                    content.visible = val;
                }
                if(NDraggableWidget.this.parent instanceof GameUI)
                    saveProp(target_c);
            }
        }, Coord.z);

        add(btnFlip = new ICheckBox(NStyle.flipi[0], NStyle.flipi[1], NStyle.flipi[2], NStyle.flipi[3])
        {
            @Override
            public void changed(boolean val)
            {
                super.changed(val);
                if(rotations > 2)
                    rot = (rot + 1) % rotations;
                if(content!=null)
                {
                    flipContent();
                }
                if(NDraggableWidget.this.parent instanceof GameUI)
                    saveProp(target_c);
            }
        }, Coord.z);
        layoutControls();

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
            this.rot = prop.rot;
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
        if(content!=null)
        {
            content.resize(sz.sub(delta));
            content.move(off);
        }
        layoutControls();
    }

    /**
     * The box the player sees outside drag mode: the content, in this widget's coordinates. Drag
     * mode outlines it, snaps it and keeps it on screen, so a layout made there is what shows later.
     * The frame around it may hang off screen. Falls back to the whole frame while the content is
     * too small to grab (an empty buff list).
     */
    public Area box()
    {
        Area b = (content != null) ? Area.sized(content.c, content.sz) : Area.sized(off, sz.sub(delta));
        Coord min = NStyle.locki[0].sz();
        if(b.sz().x < min.x || b.sz().y < min.y)
            return Area.sized(sz);
        return b;
    }

    /** Lock and eye at the box's top-right, turn at its top-left: inside the box, so they stay reachable at a screen edge. */
    private void layoutControls()
    {
        Area b = box();
        int pad = UI.scale(2);
        btnLock.move(new Coord(b.br.x - btnLock.sz.x - pad, b.ul.y + pad));
        btnVis.move(new Coord(btnLock.c.x - btnVis.sz.x - pad, b.ul.y + pad));
        btnFlip.move(new Coord(b.ul.x + pad, b.ul.y + pad));
    }

    public static final Tex bg = Resource.loadtex("nurgling/hud/wnd/bg");
    private static final Tex ctl = Resource.loadtex("nurgling/hud/box/tl");

    @Override
    public void draw(GOut g)
    {
        boolean drag = ui.core.mode == NCore.Mode.DRAG;
        Area b = drag ? box() : null;
        if (drag)
            drawFlatBg(g.reclip(b.ul, b.sz()), b.sz(), ui);
        super.draw(g);
        if (drag) {
            drawOutline(g, b);
            g.aimage(label, b.ul.add(b.sz().div(2)), 0.5, 0.5);
        }
    }

    /** The drag-mode frame: an accent line just inside the box, over the content's edge pixels. */
    static void drawOutline(GOut g, Area b)
    {
        int t = Math.max(1, UI.scale(1));
        Coord s = b.sz();
        g.chcolor(nurgling.styles.UITheme.ACCENT);
        g.frect(b.ul, new Coord(s.x, t));
        g.frect(new Coord(b.ul.x, b.br.y - t), new Coord(s.x, t));
        g.frect(b.ul, new Coord(t, s.y));
        g.frect(new Coord(b.br.x - t, b.ul.y), new Coord(t, s.y));
        g.chcolor();
    }

    public static void drawBg(GOut g, Coord sz, UI ui) {
        if(nurgling.styles.UITheme.on()) {
            drawFlatBg(g, sz, ui);
            return;
        }
        Coord bgUl = new Coord(ctl.sz().x / 2, ctl.sz().y / 2);
        Coord bgSz = new Coord(sz.x - ctl.sz().x, sz.y - ctl.sz().y);
        
        if (ui instanceof nurgling.NUI) {
            nurgling.NUI nui = (nurgling.NUI)ui;
            float opacity = nui.getUIOpacity();
            int alpha = (int)(255 * opacity);
            
            if (nui.getUseSolidBackground()) {
                // Use custom background color
                java.awt.Color bgColor = nui.getWindowBackgroundColor();
                g.chcolor(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), alpha);
                g.frect(bgUl, bgSz);
                g.chcolor();
            } else {
                // Use Window.bg texture with opacity
                g.chcolor(255, 255, 255, alpha);
                Coord bgc = new Coord();
                Coord ca_ul = bgUl;
                Coord ca_br = bgUl.add(bgSz);
                for(bgc.y = ca_ul.y; bgc.y < ca_br.y; bgc.y += Window.bg.sz().y) {
                    for(bgc.x = ca_ul.x; bgc.x < ca_br.x; bgc.x += Window.bg.sz().x)
                        g.image(Window.bg, bgc, ca_ul, ca_br);
                }
                g.chcolor();
            }
        } else {
            // Fallback
            int x_pos = ctl.sz().x;
            int y_pos = ctl.sz().y;
            for (int x = ctl.sz().x / 2; x + bg.sz().x < sz.x - ctl.sz().x / 2; x += bg.sz().x)
            {
                for (int y = ctl.sz().y / 2; y + bg.sz().y < sz.y - ctl.sz().y / 2; y += bg.sz().y)
                {
                    g.image(bg, new Coord(x, y));
                    y_pos = Math.max(y_pos, y + bg.sz().y);
                    x_pos = Math.max(x_pos, x + bg.sz().x);
                }
            }
            for (int x = ctl.sz().x / 2; x + bg.sz().x < sz.x - ctl.sz().x / 2; x += bg.sz().x)
            {
                g.image(bg, new Coord(x, y_pos), new Coord(bg.sz().x, sz.y - y_pos - ctl.sz().y / 2));
                x_pos = Math.max(x_pos, x + bg.sz().x);
            }
            for (int y = ctl.sz().y / 2; y + bg.sz().y < sz.y - ctl.sz().y / 2; y += bg.sz().y)
            {
                g.image(bg, new Coord(x_pos, y), new Coord(sz.x - x_pos - ctl.sz().x / 2, bg.sz().y));
                y_pos = Math.max(y_pos, y + bg.sz().y);
            }
            if (x_pos < sz.x - ctl.sz().x / 2 && y_pos < sz.y - ctl.sz().y / 2)
            {
                g.image(bg, new Coord(x_pos, y_pos), new Coord(sz.x - x_pos - ctl.sz().x / 2, sz.y - y_pos - ctl.sz().y / 2));
            }
        }
    }

    /** Fills all of sz: the new UI's flat frame and the drag-mode box both reach their bounds. */
    private static void drawFlatBg(GOut g, Coord sz, UI ui) {
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

            if (box().contains(ev.c)) {
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
            // The invisible padding around the box: leave it to whatever lies underneath.
            return false;
        }
        return super.mousedown(ev);
    }


    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if (dm != null && ui.core.mode == NCore.Mode.DRAG)
        {
            target_c = this.c.add(anchorShift());
            saveProp(target_c);
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
                // The grid catches the box's corner, not the frame's; the screen edge stops the box
                // without pulling it in, so it can sit anywhere up to and against the edge.
                Area b = box();
                Coord newc = nurgling.styles.DragGrid.snap(prepc.add(b.ul)).sub(b.ul);
                if(NUtils.getGameUI() != null && NUtils.getGameUI().sz != Coord.z)
                    newc = DragLayout.clamp(newc, b, NUtils.getGameUI().sz);
                this.c = newc;
            }
            else
            {
                if (ev.c.isect(Coord.z, sz))
                {
                    btnLock.mousemove(ev);
                    btnVis.mousemove(ev);
                    if(rotations > 0)
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
            layoutControls();
            btnLock.show();
            btnVis.show();
            if(rotations > 0)
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

        place();
    }

    /** Puts the widget at its saved position (less the content's anchor shift), its box kept on screen. */
    public void place()
    {
        if(NUtils.getGameUI()!=null && NUtils.getGameUI().sz!=Coord.z && dm == null)
        {
            // Test the intended spot, not the current one: right after a turn or resize c is stale.
            // DragLayout returns a fresh Coord: c can alias a shared instance (Widget defaults c to
            // Coord.z), and writing through it once moved Coord.z itself and broke every blurred text.
            Coord at = DragLayout.clamp(target_c.sub(anchorShift()), box(), NUtils.getGameUI().sz);
            if (!at.equals(c))
                c = at;
        }
    }

    private Coord anchorShift()
    {
        return (content instanceof Turnable) ? ((Turnable) content).anchorShift() : Coord.z;
    }

    private void saveProp(Coord at)
    {
        NDragProp prop = new NDragProp(new Coord(at), btnLock.a, btnVis.a, name);
        prop.flip = btnFlip.a;
        prop.rot = rot;
        NDragProp.set(name, prop);
    }

    public String getName()
    {
        return name;
    }

    public void flipContent()
    {
        if(rotations > 2 && content instanceof Turnable)
            ((Turnable) content).rotate(rot);
        else
            content.flip(btnFlip.a);
        resize(content.sz.add(delta));
        place();
    }

    public void setFlipped(boolean val)
    {
        setRotations(val ? 2 : 0);
    }

    /** Shows the turn button: 2 flips the content, more steps a {@link Turnable} through that many orientations. */
    public void setRotations(int n)
    {
        rotations = n;
        if(rotations > 2)
            rot = Math.floorMod(rot, rotations);
        flipContent();
    }
}
