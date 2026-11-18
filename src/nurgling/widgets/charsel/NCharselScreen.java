package nurgling.widgets.charsel;

import haven.*;
import haven.render.Location;
import haven.render.Projection;
import nurgling.NCharlist;
import nurgling.NConfig;
import nurgling.conf.NCharTags;
import nurgling.plugins.NPluginManager;
import nurgling.widgets.NAvaview;
import nurgling.widgets.login.NActionLink;
import nurgling.widgets.login.NBackdrop;
import nurgling.widgets.login.NLoginTheme;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces the server's 800x600 character-selection container with one the size of our art,
 * centred in the window like before. It draws the art through {@link NBackdrop} and places the
 * server's widgets by type instead of by their 800x600 coordinates. As observed on 2026-09-15, the
 * server sends: background Img, verify Img, subscription Img, the charlist, a ProxyFrame holding the
 * big avatar, and the "New character" IButton. The Imgs and the IButton are kept (hidden) because
 * the list's heading badges and footer button stand in for them.
 *
 * Plugins hook in through {@link NPluginManager#onCharsel}: they can add links under the list
 * ({@link #addAction}) and show their own content in the list's place ({@link #showPanel}).
 */
public class NCharselScreen extends Widget {
    private static final Coord SRVSZ = new Coord(800, 600);
    private static final Coord ARTSZ = new Coord(1376, 768);
    /* Children right of this (server coordinates) belong to the right side of the old layout. */
    private static final int SRV_RIGHT = 300;
    /** Rows cropped off the top of the avatar view; see addchild(). */
    private static final int AVACROP = UI.scale(12);
    /** Extra headroom above the character; AVACROP eats 12 of it again. */
    private static final int AVATOP = UI.scale(16);
    /** Extra room below the character, so a spun foot stays inside the view. */
    private static final int AVABOT = UI.scale(40);
    /** Extra width, so a cape does not run off the sides when the character is spun. */
    private static final int AVAWIDE = UI.scale(90);
    /** The big avatar is drawn this many times its server size. The margins above are at 1x and
     * grow with it. */
    private static final float AVAZOOM = 1.28f;
    private static final int ZCROP = Math.round(AVACROP * AVAZOOM), ZBOT = Math.round(AVABOT * AVAZOOM);
    /** Where the character's feet go: the middle of the art's stone platform. */
    private static final Coord FEET = UI.scale(765, 620);
    /** Gap between the feet and the name plate under them. */
    private static final int PLATEGAP = UI.scale(6);
    /** Radians of spin per pixel dragged: a full turn takes about 400 px. */
    private static final double ROTSPEED = 0.015;
    /** Key for our rotation in the view's basic states; must not collide with Camera/Projection. */
    private static final Object ROTID = new Object();

    private final NBackdrop backdrop;
    private final NamePlate plate;
    /** Plague miasma around the character's feet: one layer behind the avatar, one in front. */
    private final Miasma miasma = new Miasma();
    /** The character's shadow on the ground under its feet. */
    private final GroundShadow shadow = new GroundShadow();
    /** "Nurglingify" turns the plague skin on for this screen; "Smoke" turns the miasma off. */
    private final Toggle nurgle, smoke;
    private NCharlist list;
    private Widget avatar;
    private IButton newchar;
    private final List<Img> badges = new ArrayList<>();
    /* Anything unrecognised keeps its server position, re-anchored to the right edge if it sat on
     * the right half, so a server-side addition still shows up somewhere sensible. */
    private final Map<Widget, Coord> loose = new HashMap<>();
    /* Drag-to-spin state for the avatar. */
    private Avaview avaview;
    /* Plugin additions: links under the list, and at most one panel shown in the list's place. */
    private final List<NActionLink> actions = new ArrayList<>();
    private Widget panel;
    private boolean pluginsNotified = false;
    private UI.Grab rotgrab = null;
    private double rot = 0, rotstart = 0;
    private int rotx = 0;

    public static boolean isCharsel(Coord srvsz) {
        return (SRVSZ.equals(srvsz));
    }

    public NCharselScreen() {
        super(UI.scale(ARTSZ));
        backdrop = add(new NBackdrop(this::art, NBackdrop.SCRIMW), Coord.z);
        add(new Overlays(), Coord.z);
        add(shadow, Coord.z);
        add(miasma.back, Coord.z);
        plate = add(new NamePlate(), Coord.z);
        nurgle = add(new Toggle("charsel.nurglingify", nurgling.tools.PlagueSkin::nurglingify, NConfig.Key.nurglingify), Coord.z);
        smoke = add(new Toggle("charsel.smoke", nurgling.tools.PlagueSkin::charselSmoke, NConfig.Key.charselSmoke), Coord.z);
    }

    /* The designer painted one picture per account state; the server's verify and subscription
     * images (kept as badges) say which applies. They arrive just before the character list, so
     * until the list is here the screen keeps showing what it showed last time (as the login
     * bridge did), instead of flashing the plain art; once it is here the answer is final and is
     * remembered for next time. */
    private Tex art() {
        if (list == null)
            return (Img.getCharselBg(CharselBridge.lastArt()));
        boolean v = false, s = false;
        for (Img b : badges) {
            v |= (b.charselType == Img.CharselType.VERIFY);
            s |= (b.charselType == Img.CharselType.SUB);
        }
        int art = (v ? 1 : 0) | (s ? 2 : 0);
        if (art != CharselBridge.lastArt())
            NConfig.set(NConfig.Key.charselArt, art);
        return (Img.getCharselBg(art));
    }

    /** This screen's size; the login bridge takes the same place. */
    static Coord screenSize() {
        return (UI.scale(ARTSZ));
    }

    protected void added() {
        presize();
    }

    public void presize() {
        c = parent.sz.div(2).sub(sz.div(2));
    }

    public void addchild(Widget child, Object... args) {
        super.addchild(child, args);
        if (child instanceof Img) {
            Img img = (Img) child;
            switch (img.charselType) {
                case BACKGROUND:
                    img.hide();
                    break;
                case VERIFY:
                case SUB:
                    img.hide();
                    badges.add(img);
                    break;
                default:
                    loose.put(child, child.c);
            }
        } else if (child instanceof NCharlist) {
            list = (NCharlist) child;
        } else if (child instanceof ProxyFrame) {
            ProxyFrame<?> pf = (ProxyFrame<?>) child;
            /* No frame: the avatar stands in the art like the rest of the screen. */
            pf.color = null;
            /* haven's avatar view renders a short dark line a few pixels below its own top edge, on
             * every character (naked ones included). It is the server's widget and the line comes out
             * of the 3D render, so crop those rows instead: shift the view up and shorten the frame
             * to match, which clips them. Only empty sky above the head is lost. */
            Widget view = pf.ch;
            if (view instanceof Avaview) {
                avaview = (Avaview) view;
                /* The selection screen always shows the plague skin; tick() follows the selection. */
                if (avaview instanceof NAvaview) {
                    ((NAvaview) avaview).plague(selectedName());
                    ((NAvaview) avaview).moodLight();
                }
                /* The server sizes the view so the near foot falls outside it and a spun cape runs
                 * off the sides. Both are fixed by enlarging the view, but the projection has to be
                 * rebuilt by hand: PView.resize leaves it alone, and the view's own makeproj uses a
                 * fixed horizontal field, where scale = width / (2 * field) - so a wider view would
                 * only zoom in. Growing the field with the width keeps the character at its old size
                 * and turns the extra pixels into extra scene instead. */
                Coord osz = view.sz;
                Coord nsz = osz.add(AVAWIDE, AVATOP + AVABOT);
                /* The frustum below is worked out for nsz; the view gets AVAZOOM times as many
                 * pixels for the same scene, which draws the character that much bigger. */
                view.resize(nsz.mul(AVAZOOM));
                float field = 0.5f * ((float) nsz.x / (float) osz.x);
                float half = (((float) nsz.y) / ((float) nsz.x)) * field;
                /* The frustum is symmetric, so the added height would split evenly top and bottom.
                 * Sliding the window down by the difference puts the room where it is wanted -
                 * below the feet - without touching the scale. */
                float wpp = (2 * field) / nsz.x;
                float shift = ((AVABOT - AVATOP) / 2f) * wpp;
                avaview.basic(Projection.class, Projection.frustum(-field, field, -half - shift, half - shift, 1, 5000));
            }
            view.move(Coord.of(0, -ZCROP));
            pf.resize(Coord.of(view.sz.x, view.sz.y - ZCROP));
            avatar = pf;
            addMiasmaFront();
        } else if (child instanceof Avaview) {
            avatar = child;
            avaview = (Avaview) child;
            addMiasmaFront();
        } else if ((child instanceof IButton) && (newchar == null)) {
            newchar = (IButton) child;
            newchar.hide();
        } else {
            loose.put(child, child.c);
        }
        wire();
        layout();
        if ((list != null) && !pluginsNotified) {
            pluginsNotified = true;
            NPluginManager.onCharsel(this);
        }
    }

    public void cdestroy(Widget ch) {
        super.cdestroy(ch);
        if (ch == list) {
            list = null;
            /* The server takes the list away once a character is chosen, but leaves the rest of
             * the screen up until the game is ready - seconds of bare background and smoke. Turn
             * it into a loading screen instead: just the background, with "Loading..." and a
             * spinner in the middle, until the game takes over. */
            loading();
        }
        if (ch == avatar) {
            avatar = null;
            avaview = null;
        }
        if (ch == newchar)
            newchar = null;
        if (ch == panel) {
            panel = null;
            setListShown(true);
        }
        badges.remove(ch);
        loose.remove(ch);
        wire();
    }

    /* The front layer has to come after the avatar to be drawn over it, but the list and anything
     * shown in its place stay on top: the haze drifts behind them, not across them. */
    private void addMiasmaFront() {
        if (miasma.front.parent != null)
            return;
        add(miasma.front, Coord.z);
        if (list != null)
            list.raise();
        if (panel != null)
            panel.raise();
        for (NActionLink a : actions)
            a.raise();
    }

    private void wire() {
        if (list != null) {
            list.badgeSources(badges);
            list.newCharSource(newchar);
        }
    }

    /* ------------------------------------------------------------- loading screen */

    private boolean loading = false;
    private Text loadtext = null;

    private void loading() {
        if (loading)
            return;
        loading = true;
        for (Widget w = child; w != null; w = w.next) {
            if (w != backdrop)
                w.hide();
        }
        loadtext = NLoginTheme.heading.render(nurgling.i18n.L10n.get("charsel.loading"));
    }

    public void draw(GOut g) {
        super.draw(g);
        if (loading && (loadtext != null)) {
            Coord mid = sz.div(2);
            g.image(loadtext.tex(), mid.sub(loadtext.sz().x / 2, loadtext.sz().y));
            NLoginTheme.drawSpinner(g, mid.add(0, UI.scale(28)), UI.scale(16), UI.scale(5));
        }
    }

    /* ------------------------------------------------------------- plugin additions */

    /** Adds a link under the character list's footer. For plugins, from {@code NPlugin.onCharsel}. */
    public void addAction(String label, Runnable action) {
        NActionLink a = add(new NActionLink(label, action), Coord.z);
        actions.add(a);
        if (panel != null)
            a.hide();
        layout();
    }

    /** The size a panel gets: the character list's column. */
    public Coord panelSize() {
        return (Coord.of(NCharlist.W, NCharlist.H));
    }

    /** Shows {@code w} in the character list's place, replacing any earlier panel, until {@link #closePanel}. */
    public <T extends Widget> T showPanel(T w) {
        closePanel();
        panel = add(w, Coord.z);
        setListShown(false);
        layout();
        return (w);
    }

    /** Removes the panel, if any, and brings the character list back. */
    public void closePanel() {
        if (panel != null) {
            Widget p = panel;
            panel = null;
            p.reqdestroy();
        }
        setListShown(true);
        layout();
    }

    /** The panel shown in the list's place, or null. */
    public Widget panel() {
        return (panel);
    }

    /** The character list (its {@code chars} fill in as the server sends them; "play" goes to it), or null once a character is chosen. */
    public NCharlist charlist() {
        return (list);
    }

    /** The server's hidden "New character" button, or null before it arrives. */
    public IButton newCharButton() {
        return (newchar);
    }

    private void setListShown(boolean shown) {
        if (list != null) {
            if (shown)
                list.show();
            else
                list.hide();
        }
        for (NActionLink a : actions) {
            if (shown)
                a.show();
            else
                a.hide();
        }
    }

    private void layout() {
        int lx = UI.scale(56), ly = (sz.y - NCharlist.H) / 2;
        if (list != null)
            list.move(Coord.of(lx, (sz.y - list.sz.y) / 2));
        if (panel != null)
            panel.move(Coord.of(lx, ly));
        int ax = lx;
        for (NActionLink a : actions) {
            a.move(Coord.of(ax, ly + NCharlist.H + UI.scale(8)));
            ax += a.sz.x + UI.scale(16);
        }

        /* The art has a stone platform for the character: stand on it, feet first, with the name
         * plate just under the feet. The platform is low in the picture, so the plate is kept to
         * two lines. */
        if (avatar != null) {
            avatar.move(Coord.of(FEET.x - (avatar.sz.x / 2), FEET.y - (avatar.sz.y - ZBOT)));
            int tall = (int) ((FEET.y - avatar.c.y) * 0.95f);
            miasma.anchor(FEET, tall);
            shadow.anchor(FEET, tall);
        }
        /* The name lines up with the list's footer buttons, so the bottom of the screen reads as
         * one row; without the list it sits just under the feet. */
        int ply = (list != null) ? list.c.y + list.footerMid() - plate.sz.y / 2 : FEET.y + PLATEGAP;
        plate.move(Coord.of(FEET.x - (plate.sz.x / 2), ply));
        /* top right, one under the other, check boxes lined up */
        int tx = sz.x - UI.scale(24) - Math.max(nurgle.sz.x, smoke.sz.x);
        nurgle.move(Coord.of(tx, UI.scale(18)));
        smoke.move(Coord.of(tx, nurgle.c.y + nurgle.sz.y + UI.scale(6)));

        int srvw = UI.scale(SRVSZ.x);
        for (Map.Entry<Widget, Coord> e : loose.entrySet()) {
            Coord o = e.getValue();
            e.getKey().move((o.x > UI.scale(SRV_RIGHT)) ? o.add(sz.x - srvw, 0) : o);
        }
    }

    /* ------------------------------------------------------------- drag to spin the avatar */

    /* The camera is fixed (placed from the base resource's "avacam" bone offset), so the spin is a
     * rotation composed onto the view's basic state, which transforms the model under it and leaves
     * the camera alone. The avatar view itself ignores mouse events, so the drag is handled here. */
    private void setrot(double a) {
        rot = a;
        if (avaview != null)
            avaview.basic(ROTID, Location.rot(new Coord3f(0, 0, 1), (float) rot));
    }

    public boolean mousedown(MouseDownEvent ev) {
        /* The list, its buttons and the name plate get first refusal. */
        if (ev.propagate(this))
            return (true);
        if ((ev.b == 1) && (avaview != null) && (avatar != null) && ev.c.isect(avatar.c, avatar.sz)) {
            rotgrab = ui.grabmouse(this);
            rotx = ev.c.x;
            rotstart = rot;
            return (true);
        }
        return (super.mousedown(ev));
    }

    public void mousemove(MouseMoveEvent ev) {
        if (rotgrab != null)
            setrot(rotstart + ((ev.c.x - rotx) * ROTSPEED));
        super.mousemove(ev);
    }

    public boolean mouseup(MouseUpEvent ev) {
        if ((ev.b == 1) && (rotgrab != null)) {
            rotgrab.remove();
            rotgrab = null;
            return (true);
        }
        return (super.mouseup(ev));
    }

    /* The server re-colours the avatar frame after we blank it in addchild (a "col" message), which
     * brings haven's window-box border back. Nurgling's box graphic is a stub, so it shows up as a
     * short black dash in the frame's margin above the avatar rather than a border. Keep it blank. */
    public void tick(double dt) {
        super.tick(dt);
        /* The server leaves this screen in place under the game while the world loads; once the
         * game UI is here, get out of its way so the loading screen does not show through it. */
        if (visible() && (parent != null)) {
            for (Widget w = parent.child; w != null; w = w.next) {
                if (w instanceof GameUI) {
                    hide();
                    break;
                }
            }
        }
        rememberCharacters(dt);
        if (avatar instanceof ProxyFrame)
            ((ProxyFrame<?>) avatar).color = null;
        /* Each character wears its own plague pattern on the big avatar too. */
        if (avaview instanceof NAvaview)
            ((NAvaview) avaview).plague(selectedName());
    }

    /* The character list fills in over a few messages; once it has stopped growing for a second it
     * is remembered for the account, so the login screen can offer its characters before logging in. */
    private int knownCount = -1;
    private double knownSteady = 0;
    private boolean knownSaved = false;

    private void rememberCharacters(double dt) {
        if (knownSaved || (list == null))
            return;
        List<String> names = characterNames(list);
        if (names.size() != knownCount) {
            knownCount = names.size();
            knownSteady = 0;
            return;
        }
        knownSteady += dt;
        if ((knownCount > 0) && (knownSteady >= 1.0)) {
            knownSaved = true;
            NCharTags.setKnown(NCharTags.account(ui), names);
        }
    }

    /** The names on a character list, in its order. */
    public static List<String> characterNames(Charlist list) {
        List<String> ret = new ArrayList<>();
        synchronized (list.chars) {
            for (Charlist.Char c : list.chars)
                ret.add(c.name);
        }
        return (ret);
    }

    private String selectedName() {
        Charlist.Char c = (list != null) ? list.selected() : null;
        return ((c != null) ? c.name : null);
    }

    /**
     * A switch at the top right ("Nurglingify", "Smoke"): the standard check box with the screen's
     * shadowed label, so it reads over the sky art. Clicking the label works too. Saved like the
     * world tab.
     */
    private static class Toggle extends Widget {
        private final CheckBox box;
        private final Text text;

        /** {@code key} names the label (and {@code key + ".tip"} the tooltip); the state is saved under {@code conf}. */
        Toggle(String key, java.util.function.Supplier<Boolean> state, NConfig.Key conf) {
            super(Coord.z);
            text = NLoginTheme.section.render(nurgling.i18n.L10n.get(key));
            box = new CheckBox("");
            box.state(state);
            box.set(on -> NConfig.set(conf, on));
            int h = Math.max(box.sz.y, text.sz().y);
            add(box, Coord.of(0, (h - box.sz.y) / 2));
            resize(Coord.of(box.sz.x + UI.scale(6) + text.sz().x, h));
            settip(nurgling.i18n.L10n.get(key + ".tip"), true);
        }

        public void draw(GOut g) {
            g.image(text.tex(), Coord.of(box.sz.x + UI.scale(6), (sz.y - text.sz().y) / 2));
            super.draw(g);
        }

        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                box.click();
                return (true);
            }
            return (super.mousedown(ev));
        }
    }

    /**
     * Hover for the verified and subscribed art (dreamcatchers on the tree, a coin by the log). The
     * objects themselves are part of the background ({@link #art}); the server's images, kept hidden
     * as badges, carry our cut-outs of them - what the designer's verified/subscribed picture adds
     * over the plain one - which this uses only to light the object up and show the server's tooltip
     * when hovered. The hover area is the circle the light is drawn in, not the object's pixels:
     * dreamcatchers are mostly thin strings and empty rings.
     */
    private class Overlays extends Widget {
        /** Seconds to fade the hover light fully in or out. */
        private static final float FADE = 0.18f;
        private final Map<Img, Float> lit = new HashMap<>();
        private final Map<Img, Defer.Future<Glow>> glows = new HashMap<>();
        private Img hover = null;

        Overlays() {
            super(NCharselScreen.this.sz);
        }

        private Img at(Coord c) {
            for (int i = badges.size() - 1; i >= 0; i--) {
                Img b = badges.get(i);
                Glow gl = glow(b);
                /* the light's circle once it is built; the picture's own pixels until then */
                if ((gl != null) ? gl.hit(c) : b.checkhit(c))
                    return (b);
            }
            return (null);
        }

        public void mousemove(MouseMoveEvent ev) {
            hover = at(ev.c);
            super.mousemove(ev);
        }

        public boolean checkhit(Coord c) {
            return (at(c) != null);
        }

        public Object tooltip(Coord c, Widget prev) {
            Img b = at(c);
            return ((b == null) ? null : b.tooltip);
        }

        public void tick(double dt) {
            super.tick(dt);
            for (Img b : badges) {
                glow(b); /* start building it now, so the hover area is ready before the mouse is */
                float v = lit.getOrDefault(b, 0f), to = (b == hover) ? 1f : 0f;
                float step = (float) dt / FADE;
                lit.put(b, (v < to) ? Math.min(to, v + step) : Math.max(to, v - step));
            }
        }

        public void draw(GOut g) {
            for (Img b : badges) {
                float v = lit.getOrDefault(b, 0f);
                if (v <= 0)
                    continue;
                Glow gl = glow(b);
                if (gl != null) {
                    g.chcolor(255, 255, 255, Math.round(v * 255));
                    g.image(gl.tex, gl.off);
                    g.chcolor();
                }
            }
        }

        /** The lit version of an overlay, built off the UI thread; null until ready. */
        private Glow glow(Img b) {
            Defer.Future<Glow> f = glows.get(b);
            if (f == null) {
                BufferedImage src = b.image();
                if (src == null)
                    return (null);
                glows.put(b, f = Defer.later(() -> Glow.of(src)));
            }
            try {
                return (f.get());
            } catch (Loading l) {
                return (null);
            }
        }
    }

    /**
     * An overlay object lit up: a soft round light behind it with the object brightened on top,
     * cropped to the object so it stays small. The light is round rather than following the
     * object's outline, because each overlay carries a painted patch of background around its
     * object and an outline glow would trace that patch. Drawn over the plain overlay at the
     * hover strength.
     */
    private static class Glow {
        /** Share of the light's radius that counts as hovering: its bright part. */
        private static final float HITR = 0.85f;
        final Tex tex;
        final Coord off;
        /** The light's circle, in overlay (screen) coordinates. */
        final float cx, cy, rad;

        private Glow(Tex tex, Coord off, float cx, float cy, float rad) {
            this.tex = tex;
            this.off = off;
            this.cx = cx;
            this.cy = cy;
            this.rad = rad;
        }

        boolean hit(Coord c) {
            return (Math.hypot(c.x - cx, c.y - cy) <= rad * HITR);
        }

        static Glow of(BufferedImage src) {
            int w = src.getWidth(), h = src.getHeight();
            int x0 = w, y0 = h, x1 = -1, y1 = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((src.getRGB(x, y) >>> 24) >= 128) {
                        x0 = Math.min(x0, x); y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                    }
                }
            }
            if (x1 < 0)
                return (null);
            float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f;
            float rad = Math.max(x1 - x0, y1 - y0) * 0.75f;
            x0 = Math.max(0, (int) (cx - rad)); y0 = Math.max(0, (int) (cy - rad));
            x1 = Math.min(w - 1, (int) (cx + rad)); y1 = Math.min(h - 1, (int) (cy + rad));
            int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
            BufferedImage out = TexI.mkbuf(Coord.of(cw, ch));
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    int s = src.getRGB(x0 + x, y0 + y);
                    float sa = (s >>> 24) / 255f;
                    /* the light: brightest at the middle, gone by the edge of the circle */
                    float d = (float) Math.hypot(x0 + x - cx, y0 + y - cy) / rad;
                    float t = Math.max(0f, Math.min(1f, (d - 0.2f) / 0.8f));
                    float ga = (1f - t * t * (3 - 2 * t)) * 0.45f;
                    float r = 225, g = 255, bl = 170;
                    float or = Math.min(255, ((s >> 16) & 255) * 1.3f + 15), og = Math.min(255, ((s >> 8) & 255) * 1.3f + 15), ob = Math.min(255, (s & 255) * 1.3f + 15);
                    float oa = sa + ga * (1 - sa);
                    if (oa > 0) {
                        r = (or * sa + r * ga * (1 - sa)) / oa;
                        g = (og * sa + g * ga * (1 - sa)) / oa;
                        bl = (ob * sa + bl * ga * (1 - sa)) / oa;
                    }
                    out.setRGB(x, y, (Math.round(oa * 255) << 24) | (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(bl));
                }
            }
            return (new Glow(new TexI(out).filter(haven.render.Texture.Filter.LINEAR), Coord.of(x0, y0), cx, cy, rad));
        }
    }

    /**
     * A soft contact shadow on the ground under the character, so it stands on the platform instead
     * of floating over the picture: a flat ellipse, darkest under the feet and feathered to nothing,
     * pushed a little to the right, away from the moon. Drawn on the art, under the smoke.
     */
    private static class GroundShadow extends Widget {
        private static final Tex tex = mktex();

        GroundShadow() {
            super(Coord.z);
        }

        /** Sizes the shadow for a character {@code tall} pixels high standing at {@code feet}. */
        void anchor(Coord feet, int tall) {
            Coord sz = Coord.of((int) (tall * 0.5f), (int) (tall * 0.13f));
            resize(sz);
            move(Coord.of(feet.x - sz.x / 2 + (int) (tall * 0.03f), feet.y - (int) (sz.y * 0.55f)));
        }

        public void draw(GOut g) {
            g.image(tex, Coord.z, sz);
        }

        private static Tex mktex() {
            int w = 256, h = 64;
            BufferedImage img = TexI.mkbuf(Coord.of(w, h));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float dx = (x + 0.5f - w / 2f) / (w / 2f), dy = (y + 0.5f - h / 2f) / (h / 2f);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    /* a dark core right under the feet inside a softer falloff */
                    float a = Math.max(0.92f * (1f - smooth(d, 0f, 0.55f)), 0.6f * (1f - smooth(d, 0.25f, 1f)));
                    img.setRGB(x, y, (Math.round(a * 255) << 24) | (8 << 16) | (12 << 8) | 8);
                }
            }
            return (new TexI(img).filter(haven.render.Texture.Filter.LINEAR));
        }

        private static float smooth(float v, float lo, float hi) {
            float t = Math.max(0f, Math.min(1f, (v - lo) / (hi - lo)));
            return (t * t * (3 - 2 * t));
        }
    }

    /** A plugin's entry under the list: accent-coloured text, underlined while hovered. */
    /** Name, then world and last played, under the big avatar, level with the list's footer
     * buttons. Tags and notes are shown in the list rows. */
    private class NamePlate extends Widget {
        private Charlist.Char shown = null;
        private Text nm = null, ln = null;
        private String lns = null;

        NamePlate() {
            super(UI.scale(new Coord(460, 60)));
        }

        public void draw(GOut g) {
            Charlist.Char c = (list != null) ? list.selected() : null;
            if (c == null)
                return;
            String acc = NCharTags.account(ui);
            if (c != shown) {
                shown = c;
                nm = NLoginTheme.plate.render(c.name);
            }
            String l = list.metaline(acc, c, true);
            if (!l.equals(lns)) {
                lns = l;
                ln = NLoginTheme.sub.render(l);
            }
            /* centred in the plate, which the layout centres on the footer buttons */
            int h = nm.sz().y - UI.scale(4) + ln.sz().y, y = (sz.y - h) / 2;
            g.image(nm.tex(), Coord.of((sz.x - nm.sz().x) / 2, y));
            g.image(ln.tex(), Coord.of((sz.x - ln.sz().x) / 2, y + nm.sz().y - UI.scale(4)));
        }
    }
}
