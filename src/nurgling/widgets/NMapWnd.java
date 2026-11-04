package nurgling.widgets;

import haven.*;
import haven.MapFile.Marker;
import haven.MapFile.PMarker;
import haven.MapFile.SMarker;
import nurgling.NGameUI;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.tools.MapDbTransfer;
import nurgling.conf.ProspectKind;
import nurgling.i18n.L10n;
import nurgling.widgets.cookbook.HintTextEntry;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static haven.MCache.tilesz;

/**
 * The big map window. The map itself is the stock {@link MapWnd} view; around it this class puts
 * one row of icon buttons across the top, the marker list down the right (which can be hidden),
 * and a status strip under the map. Players and Share open as flyouts from the top row, so
 * nothing sits on the map itself except the zoom buttons.
 *
 * <p>The stock toolbar plate and marker panel still exist, hidden: the stock toolbar keeps owning
 * the map hotkeys, and every control here works on the same state its buttons do. The stock
 * compact mode is switched off - this layout has no way back out of it.
 */
public class NMapWnd extends MapWnd {
    public String searchPattern = "";  // For terrain/tile search
    public String markerSearchPattern = "";  // Marker name filter, shared by the list and the map
    public Resource.Image searchRes = null;

    private static final int PAD = UI.scale(4);
    private static final int SIDE_W = UI.scale(240);
    private static final Coord MINSZ = UI.scale(new Coord(640, 400));
    private static final String PREF_SIDE = "mapwnd-markerlist";
    private static final String PREF_SHOW = "mapwnd-markershow";
    private static final String PREF_SORT = "mapwnd-markersort";
    private static final Color LINE = new Color(77, 93, 95);
    private static final Color MUTED = new Color(143, 163, 164);
    private static final Color ONLINE = new Color(122, 209, 122);

    private final Text.Foundry fnd = new Text.Foundry(Text.dfont, UI.scale(11)).aa(true);
    private final Text.Foundry smallf = new Text.Foundry(Text.dfont, UI.scale(10)).aa(true);

    /* All set after MapWnd's constructor, which already calls resize() and compact(). Every
     * override checks for null rather than initialising here, so nothing is overwritten later. */
    private TopBar bar;
    private MarkerPanel side;
    private StatusStrip status;
    private Flyout playersFly, shareFly, openFly;
    private BarButton playersBtn, shareBtn;
    private PeerRoster roster;
    private Widget shareDb;
    private boolean sideShown;
    private Coord hoverc;

    MapToggleButton treeBtn;
    MapToggleButton fishBtn;
    MapToggleButton oreBtn;
    MapToggleButton gemBtn;
    MapToggleButton stoneBtn;
    MapToggleButton forageBtn;
    MapToggleButton mapToolsBtn;
    MapToggleButton vectorClearBtn;

    public class MapToggleButton extends ICheckBox {
        private final Runnable rightClickAction;

        public MapToggleButton(String base, String tooltip, Runnable rightClickAction) {
            super("nurgling/hud/buttons/" + base + "/", "u", "d", "h", "dh");
            this.rightClickAction = rightClickAction;
            settip(tooltip);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b == 3 && checkhit(ev.c)) {
                if(rightClickAction != null)
                    rightClickAction.run();
                return true;
            }
            return super.mousedown(ev);
        }
    }

    /** An icon button of the top row: square hit area, and an optional count badge. */
    private class BarButton extends ICheckBox {
        Supplier<Integer> badge = null;
        private Text badget = null;
        private int badgen = -1;

        BarButton(Tex[] art) {
            super(art[0], art[1], art[2], art[3]);
        }

        BarButton(String base) {
            super("nurgling/hud/buttons/" + base + "/", "u", "d", "h", "dh");
        }

        public boolean checkhit(Coord c) {
            return(c.isect(Coord.z, sz));
        }

        public void draw(GOut g) {
            super.draw(g);
            Integer n = (badge == null) ? null : badge.get();
            if((n != null) && (n > 0)) {
                if((badget == null) || (badgen != n)) {
                    badgen = n;
                    badget = smallf.render(Integer.toString(n), Color.BLACK);
                }
                Text t = badget;
                Coord bsz = t.sz().add(UI.scale(4), 0);
                Coord bc = Coord.of(sz.x - bsz.x, 0);
                g.chcolor(ONLINE);
                g.frect(bc, bsz);
                g.chcolor();
                g.image(t.tex(), bc.add(UI.scale(2), 0));
            }
        }
    }

    public NMapWnd(MapFile file, MapView mv, Coord sz, String title) {
        super(file, mv, sz, title);
        searchRes = Resource.local().loadwait("alttex/selectedtex").layer(Resource.imgc);

        bar = add(new TopBar());
        buildBar();

        add(side = new MarkerPanel());
        add(status = new StatusStrip());
        viewf.add(new ZoomButtons(), UI.scale(6, 6));

        roster = new PeerRoster(SIDE_W);
        playersFly = add(new Flyout(playersBtn));
        playersFly.add(roster, PAD, PAD);
        shareFly = add(new Flyout(shareBtn));
        buildShare();

        /* Compact mode is gone: drop a saved "on" and free its hotkey (Alt+A by default), which
         * the hidden stock toolbar would otherwise keep swallowing. */
        Utils.setprefb("compact-map", false);
        for(Widget w = toolbar.child; w != null; w = w.next) {
            if(w.kb_gkey == kb_compact)
                w.kb_gkey = null;
        }

        sideShown = Utils.getprefb(PREF_SIDE, true);
        applyMode();
    }

    /* ---------------------------------------------------------------- top row */

    private void buildBar() {
        bar.left(new BarButton(stockArt("gfx/hud/mmap/home", "", "-d", "-h", "-d")))
            .state(() -> timerView().following())
            .click(this::recenter)
            .settip(L10n.get("mapwnd.follow_tip"));
        Tex[] markArt = stockArt("gfx/hud/mmap/mark", "", "-d", "-h", "-dh");
        bar.left(new MarkButton(markArt[0], markArt[1], markArt[2], markArt[3]) {
                public boolean checkhit(Coord c) {return(c.isect(Coord.z, sz));}
            }).settip(L10n.get("mapwnd.mark_tip"));
        bar.left(new BarButton(stockArt("gfx/hud/mmap/hmark", "", "-d", "-h", "-dh")) {
                public Object tooltip(Coord c, Widget prev) {
                    if(Utils.eq(markcfg, MarkerConfig.hideall))
                        return(L10n.get("mapwnd.markers_hidden_tip"));
                    if(cmarkers != null && Utils.eq(markcfg, cmarkers))
                        return(L10n.get("mapwnd.markers_some_tip"));
                    return(L10n.get("mapwnd.markers_all_tip"));
                }
            })
            .state(() -> Utils.eq(markcfg, MarkerConfig.hideall))
            .click(() -> {
                /* The same three-way cycle as the stock button: all, then the kinds picked in
                 * the list (if any), then none. */
                if(Utils.eq(markcfg, MarkerConfig.hideall))
                    markcfg = MarkerConfig.showall;
                else if(Utils.eq(markcfg, MarkerConfig.showall) && (cmarkers != null))
                    markcfg = cmarkers;
                else
                    markcfg = MarkerConfig.hideall;
            });
        bar.left(new BarButton(stockArt("gfx/hud/mmap/prov", "", "-d", "-h", "-dh")) {
                public boolean mousewheel(MouseWheelEvent ev) {
                    if(!ui.modshift || !state())
                        return(super.mousewheel(ev));
                    olalpha = Utils.clip(olalpha + (ev.a * -32), 32, 256);
                    return(true);
                }
            })
            .state(() -> overlays.contains("realm"))
            .set(a -> toggleol("realm", a))
            .settip(L10n.get("mapwnd.provinces_tip"));
        bar.separator();

        /* Finds. These drive the same settings as the Map Tools rows, so the button and the
         * panel are two views of one thing. */
        fishBtn = bar.left(new MapToggleButton("fish", L10n.get("mapwnd.fish_tip"), MapToolsWindow::openFishSearch));
        fishBtn.state(() -> NMiniMap.showFishIcons());
        fishBtn.set(val -> NMiniMap.showFishIcons(val));

        treeBtn = bar.left(new MapToggleButton("tree", L10n.get("mapwnd.tree_tip"), MapToolsWindow::openTreeSearch));
        treeBtn.state(() -> NMiniMap.showTreeIcons());
        treeBtn.set(val -> NMiniMap.showTreeIcons(val));

        forageBtn = bar.left(new MapToggleButton("forage", L10n.get("maptools.forage_icons_tip"), MapToolsWindow::openForageSearch));
        forageBtn.state(() -> NMiniMap.showForageFinds());
        forageBtn.set(val -> NMiniMap.showForageFinds(val));

        oreBtn = bar.left(new MapToggleButton("ores", L10n.get("maptools.ore_icons_tip"), () -> MapToolsWindow.openMineralSearch(ProspectKind.ORE)));
        oreBtn.state(() -> NMiniMap.showProspectKind(ProspectKind.ORE));
        oreBtn.set(val -> NMiniMap.showProspectKind(ProspectKind.ORE, val));

        gemBtn = bar.left(new MapToggleButton("gems", L10n.get("maptools.gem_icons_tip"), () -> MapToolsWindow.openMineralSearch(ProspectKind.GEM)));
        gemBtn.state(() -> NMiniMap.showProspectKind(ProspectKind.GEM));
        gemBtn.set(val -> NMiniMap.showProspectKind(ProspectKind.GEM, val));

        stoneBtn = bar.left(new MapToggleButton("stone", L10n.get("maptools.stone_icons_tip"), () -> MapToolsWindow.openMineralSearch(ProspectKind.STONE)));
        stoneBtn.state(() -> NMiniMap.showProspectKind(ProspectKind.STONE));
        stoneBtn.set(val -> NMiniMap.showProspectKind(ProspectKind.STONE, val));
        bar.separator();

        mapToolsBtn = bar.left(new MapToggleButton("maptools", L10n.get("maptools.button_tip"), MapToolsWindow::toggle));
        mapToolsBtn.state(() -> false); // Opens a window; never shows as pressed
        mapToolsBtn.click(MapToolsWindow::toggle);

        vectorClearBtn = bar.left(new MapToggleButton("vector", L10n.get("mapwnd.vectors_tip"), null));
        vectorClearBtn.state(() -> false); // An action, not a toggle
        vectorClearBtn.click(this::clearVectors);

        /* Right-hand group, built right to left. */
        BarButton listBtn = bar.right(new BarButton("maplist"));
        listBtn.state(() -> sideShown).set(this::showSide).settip(L10n.get("mapwnd.list_tip"));
        bar.rseparator();
        shareBtn = bar.right(new BarButton("mapshare"));
        shareBtn.state(() -> openFly == shareFly).click(() -> toggleFly(shareFly));
        shareBtn.settip(L10n.get("mapwnd.share_tip"));
        playersBtn = bar.right(new BarButton("mapplayers"));
        playersBtn.state(() -> openFly == playersFly).click(() -> toggleFly(playersFly));
        playersBtn.badge = () -> (roster == null) ? 0 : roster.count();
        playersBtn.settip(L10n.get("mapwnd.players_tip"));
        playersBtn.hide(); // Shown once anybody is online
    }

    /** The nurgling button whose orange ring and size the stock icons are fitted into. */
    private static final String RING = "nurgling/hud/buttons/maplist/";
    private static final String[] RING_STATES = {"u", "d", "h", "dh"};
    /** Radius of the stock face, as a fraction of the button's width: just under the ring. */
    private static final double FACE_R = 20.6 / 45.0;
    /** Where the ring's own face is cut away, as a fraction of the button's width. */
    private static final double RING_INNER = 20.0 / 45.0;

    /**
     * Loftar's map buttons in the look of the nurgling ones. Each stock image is the size of the
     * whole toolbar plate with its disc drawn somewhere inside, and the discs are not even the same
     * size (Follow's is a quarter wider than the rest). So find the disc in the "up" image, scale
     * each state's disc to fill the inside of the nurgling ring, and lay the ring around it. The
     * stock resources themselves are untouched.
     *
     * @param sfx the stock suffixes for up, down, hover-up and hover-down
     */
    private static Tex[] stockArt(String base, String... sfx) {
        Tex[] ret = new Tex[sfx.length];
        if(nurgling.headless.Headless.isHeadless()) {
            for(int i = 0; i < sfx.length; i++)
                ret[i] = Resource.loadtex(base + sfx[i]);
            return(ret);
        }
        java.awt.Rectangle disc = alphaBox(Resource.loadimg(base + sfx[0]));
        for(int i = 0; i < sfx.length; i++) {
            BufferedImage ring = Resource.loadsimg(RING + RING_STATES[i]);
            BufferedImage stock = Resource.loadimg(base + sfx[i]);
            ret[i] = new TexI((disc == null) ? ring : inring(ring, stock, disc));
        }
        return(ret);
    }

    /**
     * The box around an image's solid pixels, squared up around its centre; null if empty. Faint
     * pixels are left out: the stock discs have a soft fringe down one side that would otherwise
     * pull the centre off the disc.
     */
    private static java.awt.Rectangle alphaBox(BufferedImage img) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for(int y = 0; y < img.getHeight(); y++) {
            for(int x = 0; x < img.getWidth(); x++) {
                if((img.getRGB(x, y) >>> 24) >= 128) {
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                }
            }
        }
        if(x1 < 0)
            return(null);
        int side = Math.max(x1 - x0, y1 - y0) + 1;
        int cx = (x0 + x1 + 1) / 2, cy = (y0 + y1 + 1) / 2;
        return(new java.awt.Rectangle(cx - side / 2, cy - side / 2, side, side));
    }

    private static BufferedImage inring(BufferedImage ring, BufferedImage stock, java.awt.Rectangle disc) {
        int w = ring.getWidth(), h = ring.getHeight();
        double r = FACE_R * w, cx = w / 2.0, cy = h / 2.0;
        /* Cut the disc out (padding with transparency where it touches the plate's edge), then
         * one high-quality downscale straight to its final size, a little over the ring's inside
         * so its own dark rim tucks under the ring. */
        BufferedImage cut = TexI.mkbuf(Coord.of(disc.width, disc.height));
        java.awt.Graphics2D g = cut.createGraphics();
        g.drawImage(stock, -disc.x, -disc.y, null);
        g.dispose();
        int d = (int)Math.ceil(2 * r + UI.scale(1.0));
        /* Same parity as the button, so the face sits on whole pixels exactly in the middle; at
         * this size half a pixel either way shows as a lopsided ring. */
        if(((w - d) & 1) != 0)
            d++;
        int off = (w - d) / 2;
        BufferedImage face = PUtils.uiscale(cut, Coord.of(d, d));

        /* Round the face off inside the ring with an antialiased mask. */
        BufferedImage layer = TexI.mkbuf(Coord.of(w, h));
        g = layer.createGraphics();
        g.drawImage(face, off, off, null);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(java.awt.AlphaComposite.DstIn);
        g.setColor(Color.WHITE);
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.dispose();

        /* The ring goes on top, with only its own face cut away. At button size the orange is
         * about one pixel wide, so the face must never be drawn over it - that is what made the
         * ring look thin on some sides and thick on others. */
        double ri = RING_INNER * w;
        BufferedImage out = TexI.mkbuf(Coord.of(w, h));
        g = out.createGraphics();
        g.drawImage(ring, 0, 0, null);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(java.awt.AlphaComposite.DstOut);
        g.setColor(Color.WHITE);
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - ri, cy - ri, 2 * ri, 2 * ri));
        g.setComposite(java.awt.AlphaComposite.DstOver);
        g.drawImage(layer, 0, 0, null);
        g.dispose();
        return(out);
    }

    /** One row of buttons: a left group and a right group, each split by thin rules. */
    private class TopBar extends Widget {
        private final List<Widget> lefts = new ArrayList<>(), rights = new ArrayList<>();
        private final List<Integer> rules = new ArrayList<>();
        private final int sp = UI.scale(3), rulew = UI.scale(11);

        TopBar() {
            super(Coord.z);
        }

        <T extends Widget> T left(T w) {
            lefts.add(add(w));
            return(w);
        }

        <T extends Widget> T right(T w) {
            rights.add(add(w));
            return(w);
        }

        void separator() {lefts.add(null);}
        void rseparator() {rights.add(null);}

        void layout(int w) {
            int h = 0;
            for(Widget ch = child; ch != null; ch = ch.next)
                h = Math.max(h, ch.sz.y);
            h += PAD * 2;
            rules.clear();
            int x = PAD;
            for(Widget ch : lefts) {
                if(ch == null) {
                    rules.add(x + (rulew - sp) / 2);
                    x += rulew;
                } else if(ch.visible) {
                    ch.c = Coord.of(x, (h - ch.sz.y) / 2);
                    x += ch.sz.x + sp;
                }
            }
            x = w - PAD;
            for(Widget ch : rights) {
                if(ch == null) {
                    x -= rulew;
                    rules.add(x + (rulew + sp) / 2);
                } else if(ch.visible) {
                    x -= ch.sz.x;
                    ch.c = Coord.of(x, (h - ch.sz.y) / 2);
                    x -= sp;
                }
            }
            resize(Coord.of(w, h));
        }

        public void draw(GOut g) {
            g.chcolor(NStyle.rowEven);
            g.frect(Coord.z, sz);
            g.chcolor(LINE);
            g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
            int rh = sz.y - (PAD * 3);
            for(int x : rules)
                g.frect(Coord.of(x, (sz.y - rh) / 2), Coord.of(1, rh));
            g.chcolor();
            super.draw(g);
        }
    }

    /* ---------------------------------------------------------------- flyouts */

    /** A panel that drops down under its top-row button and keeps clicks off the map below. */
    private class Flyout extends Widget {
        final Widget owner;

        Flyout(Widget owner) {
            super(Coord.z);
            this.owner = owner;
            hide();
        }

        void fit() {
            Coord max = Coord.z;
            for(Widget ch = child; ch != null; ch = ch.next) {
                if(ch.visible)
                    max = max.max(ch.c.add(ch.sz));
            }
            resize(max.add(PAD, PAD));
        }

        public void draw(GOut g) {
            g.chcolor(28, 37, 38, 250);
            g.frect(Coord.z, sz);
            g.chcolor(NStyle.border);
            g.frect(Coord.z, Coord.of(sz.x, 1));
            g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
            g.frect(Coord.z, Coord.of(1, sz.y));
            g.frect(Coord.of(sz.x - 1, 0), Coord.of(1, sz.y));
            g.chcolor();
            super.draw(g);
        }

        public boolean mousedown(MouseDownEvent ev) {
            ev.propagate(this);
            return(true);
        }

        public boolean mousewheel(MouseWheelEvent ev) {
            ev.propagate(this);
            return(true);
        }
    }

    private void toggleFly(Flyout f) {
        boolean open = (openFly != f);
        closeFly();
        if(!open)
            return;
        if(f == shareFly)
            shareDb.show(MapDbTransfer.configured());
        f.fit();
        placeFly(f);
        f.show();
        f.raise();
        openFly = f;
    }

    private void closeFly() {
        if(openFly != null)
            openFly.hide();
        openFly = null;
    }

    private void placeFly(Flyout f) {
        int x = bar.c.x + f.owner.c.x + f.owner.sz.x - f.sz.x;
        f.c = Coord.of(Math.max(0, x), bar.c.y + bar.sz.y + UI.scale(2));
    }

    private void buildShare() {
        int w = UI.scale(110), y = PAD;
        Label l = shareFly.add(new Label(L10n.get("mapwnd.share_file"), smallf), PAD, y);
        y += l.sz.y + UI.scale(3);
        Button ex = shareFly.add(new Button(w, L10n.get("mapwnd.share_export"), false, () -> {closeFly(); exportmap();}), PAD, y);
        shareFly.add(new Button(w, L10n.get("mapwnd.share_import"), false, () -> {closeFly(); importmap();}), PAD + w + PAD, y);
        y += ex.sz.y + UI.scale(8);
        /* Only when a shared database is configured - there is nothing to share with otherwise. */
        shareDb = shareFly.add(new Widget(Coord.z), PAD, y);
        Label dl = shareDb.add(new Label(L10n.get("mapwnd.share_db"), smallf), 0, 0);
        Button dbex = shareDb.add(new Button(w, L10n.get("mapdb.btn_export"), false, () -> {
            closeFly();
            /* The window's own session, not whichever one happens to be in front: a second
             * client in the same process must not upload this map under its name. */
            MapDbTransfer.export(getparent(GameUI.class), file);
        }), 0, dl.sz.y + UI.scale(3));
        dbex.settip(L10n.get("mapdb.btn_export_tip"));
        shareDb.add(new Button(w, L10n.get("mapdb.btn_import"), false, () -> {
            closeFly();
            MapDbTransfer.importFrom(getparent(GameUI.class), file);
        }), w + PAD, dbex.c.y).settip(L10n.get("mapdb.btn_import_tip"));
        shareDb.pack();
    }

    /**
     * Everyone publishing to the shared database. Left-clicking a name pans the map to that
     * character; people in land this map has never seen say so instead.
     */
    public class PeerRoster extends Widget {
        private static final int MAXROWS = 10;
        private final int rowh = UI.scale(16);
        private final List<Row> rows = new ArrayList<>();
        private final RosterBox box;
        private double refresh = 0;

        /** One rendered row. Text is built on the refresh tick, never per frame. */
        private final class Row {
            final nurgling.PeerPosition kp;
            final Text name, status;
            final Color col;
            final boolean placed;

            Row(nurgling.PeerPosition kp, Text name, Text status, Color col, boolean placed) {
                this.kp = kp; this.name = name; this.status = status;
                this.col = col; this.placed = placed;
            }
        }

        private class RosterBox extends Listbox<Row> {
            RosterBox(int w, int h) {
                super(w, h, rowh);
            }

            protected Row listitem(int i) {return(rows.get(i));}

            protected int listitems() {return(rows.size());}

            protected void drawbg(GOut g) {
            }

            protected void drawsel(GOut g) {
                g.chcolor(255, 255, 255, 28);
                g.frect(Coord.z, g.sz());
                g.chcolor();
            }

            protected void drawitem(GOut g, Row row, int idx) {
                int mid = g.sz().y / 2;
                g.chcolor(row.col.getRed(), row.col.getGreen(), row.col.getBlue(),
                          row.placed ? 255 : 120);
                g.fellipse(new Coord(UI.scale(7), mid), new Coord(UI.scale(3), UI.scale(3)));
                g.chcolor();
                g.aimage(row.name.tex(), new Coord(UI.scale(14), mid), 0, 0.5);
                if(row.status != null)
                    g.aimage(row.status.tex(), new Coord(g.sz().x - UI.scale(4), mid), 1, 0.5);
            }

            public void change(Row row) {
                super.change(row);
                if(row == null)
                    return;
                MiniMap.Location loc = row.kp.ref.loc();
                if(loc == null) {
                    /* Online, but in land this client has never walked or imported. Saying so is the
                     * useful answer - and names the fix - where a dead click would just look broken. */
                    GameUI gui = getparent(GameUI.class);
                    if(gui != null)
                        gui.msg(row.kp.charName + " is somewhere your map does not cover yet - "
                                + "import the shared map to place them.");
                    return;
                }
                view.center(new MiniMap.SpecLocator(loc.seg.id, loc.tc));
            }
        }

        PeerRoster(int w) {
            super(Coord.of(w, UI.scale(16)));
            box = add(new RosterBox(w, 1), Coord.z);
        }

        int count() {
            return(rows.size());
        }

        public void tick(double dt) {
            super.tick(dt);
            /* Twice a second. The sync worker only refreshes the underlying data every three, and
             * re-rendering the rows of text per frame to show the same numbers would be waste. */
            refresh -= dt;
            if(refresh <= 0) {
                refresh = 0.5;
                rebuild();
            }
        }

        private void rebuild() {
            NGameUI gui = (NGameUI)getparent(GameUI.class);
            rows.clear();
            if(gui != null && gui.peerPositionService != null
               && (Boolean)nurgling.NConfig.get(nurgling.NConfig.Key.showPeerPositions)) {
                List<nurgling.PeerPosition> live = gui.peerPositionService.snapshot();
                live.sort((a, b) -> a.charName.compareToIgnoreCase(b.charName));
                for(nurgling.PeerPosition kp : live) {
                    if(!kp.online())
                        continue;
                    boolean placed = kp.ref.loc() != null;
                    Color col = NMiniMap.peercol(gui, kp.charName);
                    rows.add(new Row(kp,
                                     fnd.render(kp.charName, placed ? Color.WHITE : new Color(158, 158, 158)),
                                     smallf.render(status(kp, placed), MUTED),
                                     col, placed));
                }
            }
            int n = Math.max(1, Math.min(rows.size(), MAXROWS));
            if(box.h != n) {
                box.resize(new Coord(box.sz.x, n * rowh));
                /* The scrollbar was sized at construction and does not follow a resize on its own. */
                box.sb.resize(new Coord(box.sb.sz.x, n * rowh));
                resize(box.sz);
                if(playersFly != null)
                    playersFly.fit();
            }
        }

        /** Right-hand column: how far, or why there is no distance to give. */
        private String status(nurgling.PeerPosition kp, boolean placed) {
            if(!placed)
                return(L10n.get("mapdb.roster_unmapped"));
            String d = distance(kp.ref.loc());
            return((d == null) ? L10n.get("mapdb.roster_elsewhere") : d);
        }
    }

    /* ---------------------------------------------------------------- distances */

    /** The player's tile in the current segment's coordinates, or null while that is unknown. */
    private Coord playerTile() {
        try {
            MiniMap.Location sessloc = view.sessloc;
            GameUI gui = getparent(GameUI.class);
            if((sessloc != null) && (gui != null) && (gui.map != null))
                return(new Coord2d(gui.map.getcc()).floor(tilesz).add(sessloc.tc));
        } catch(Loading l) {
        }
        return(null);
    }

    private static final String[] COMPASS = {"E", "NE", "N", "NW", "W", "SW", "S", "SE"};

    /**
     * Straight-line distance in tiles and a compass direction, e.g. "1.2k NE"; null when the
     * place is in another map segment, where there is no common coordinate to measure across.
     */
    private String distance(MiniMap.Location loc) {
        MiniMap.Location sessloc = view.sessloc;
        Coord ptc = playerTile();
        if((loc == null) || (ptc == null) || (sessloc == null) || (loc.seg.id != sessloc.seg.id))
            return(null);
        return(distance(ptc, loc.tc));
    }

    private static String distance(Coord from, Coord to) {
        double d = from.dist(to);
        if(d < 3)
            return(L10n.get("mapwnd.here"));
        String n = (d >= 1000) ? String.format("%.1fk", d / 1000.0) : Long.toString(Math.round(d));
        /* Tile y grows southwards, so flip it for an ordinary compass angle. */
        double a = Math.atan2(from.y - to.y, to.x - from.x);
        int sector = (int)Math.floor((a / (Math.PI / 4)) + 0.5);
        return(n + " " + COMPASS[Math.floorMod(sector, 8)]);
    }

    /* ---------------------------------------------------------------- marker list */

    private static final class Row {
        final Marker m;
        MarkerType type;
        double d = Double.MAX_VALUE;
        String dist = "";
        Text name, dtext;
        String rname, rdist;

        Row(Marker m) {
            this.m = m;
            this.type = MarkerType.of(m);
        }
    }

    /** A row of mutually exclusive choices, the chosen one in the accent colour. */
    private class Seg extends Widget {
        final String[] labels;
        final Consumer<Integer> changed;
        int sel;
        private Text[] texts;

        Seg(int w, int sel, Consumer<Integer> changed, String... labels) {
            super(Coord.of(w, UI.scale(18)));
            this.labels = labels;
            this.sel = sel;
            this.changed = changed;
        }

        private int cellx(int i) {return((sz.x * i) / labels.length);}

        public void draw(GOut g) {
            if(texts == null) {
                texts = new Text[labels.length * 2];
                for(int i = 0; i < labels.length; i++) {
                    texts[i * 2] = smallf.render(labels[i], new Color(230, 230, 230));
                    texts[i * 2 + 1] = smallf.render(labels[i], new Color(17, 17, 17));
                }
            }
            for(int i = 0; i < labels.length; i++) {
                int x0 = cellx(i), x1 = cellx(i + 1);
                g.chcolor((i == sel) ? NStyle.border : NStyle.rowEven);
                g.frect(Coord.of(x0, 0), Coord.of(x1 - x0, sz.y));
                g.chcolor(LINE);
                if(i > 0)
                    g.frect(Coord.of(x0, 0), Coord.of(1, sz.y));
                g.chcolor();
                g.aimage(texts[i * 2 + ((i == sel) ? 1 : 0)].tex(), Coord.of((x0 + x1) / 2, sz.y / 2), 0.5, 0.5);
            }
            g.chcolor(LINE);
            g.frect(Coord.z, Coord.of(sz.x, 1));
            g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
            g.frect(Coord.z, Coord.of(1, sz.y));
            g.frect(Coord.of(sz.x - 1, 0), Coord.of(1, sz.y));
            g.chcolor();
        }

        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b != 1)
                return(false);
            for(int i = 0; i < labels.length; i++) {
                if((ev.c.x >= cellx(i)) && (ev.c.x < cellx(i + 1))) {
                    if(i != sel) {
                        sel = i;
                        changed.accept(i);
                    }
                    return(true);
                }
            }
            return(true);
        }
    }

    /** The right-hand panel: search, filters, the list, and the selected marker's details. */
    private class MarkerPanel extends Widget {
        static final int SHOW_ALL = 0, SHOW_PLACED = 1, SHOW_NATURAL = 2;
        static final int SORT_NAME = 0, SORT_TYPE = 1, SORT_DIST = 2;
        final HintTextEntry search;
        final Seg show, sort;
        final MarkerList list;
        Widget card = null;
        List<Row> rows = new ArrayList<>();
        int seenseq = -1;
        boolean dirty = true;
        double distRefresh = 0;
        Marker pending = null;
        private Text countText = null;
        private int countN = -1;

        MarkerPanel() {
            super(Coord.of(SIDE_W, UI.scale(200)));
            int w = SIDE_W;
            int y = 0;
            search = add(new HintTextEntry(w, L10n.get("mapwnd.search_hint"), () -> {
                markerSearchPattern = MarkerPanel.this.search.text().trim();
                dirty = true;
            }), 0, y);
            y += search.sz.y + PAD;
            show = add(new Seg(w, Utils.clip(Utils.getprefi(PREF_SHOW, SHOW_ALL), 0, 2), v -> {
                Utils.setprefi(PREF_SHOW, v);
                dirty = true;
            }, L10n.get("mapwnd.show_all"), L10n.get("mapwnd.show_placed"), L10n.get("mapwnd.show_natural")), 0, y);
            y += show.sz.y + PAD;
            int sortw = UI.scale(150);
            sort = add(new Seg(sortw, Utils.clip(Utils.getprefi(PREF_SORT, SORT_NAME), 0, 2), v -> {
                Utils.setprefi(PREF_SORT, v);
                dirty = true;
            }, L10n.get("mapwnd.sort_name"), L10n.get("mapwnd.sort_type"), L10n.get("mapwnd.sort_dist")), w - sortw, y);
            y += sort.sz.y + PAD;
            list = add(new MarkerList(w, UI.scale(100)), 0, y);
        }

        void layout(int h) {
            resize(Coord.of(sz.x, h));
            int bottom = h;
            if(card != null) {
                card.c = Coord.of(0, h - card.sz.y);
                bottom = card.c.y - PAD;
            }
            list.resize(Coord.of(sz.x, Math.max(MarkerType.iconsz, bottom - list.c.y)));
        }

        public void draw(GOut g) {
            /* "Sort" label and the marker count, left of the sort control. */
            int n = rows.size();
            if((countText == null) || (countN != n)) {
                countN = n;
                countText = smallf.render(L10n.get("mapwnd.sort_label", n), MUTED);
            }
            g.aimage(countText.tex(), Coord.of(0, sort.c.y + sort.sz.y / 2), 0, 0.5);
            super.draw(g);
        }

        public void tick(double dt) {
            super.tick(dt);
            if(dirty || (seenseq != view.file.markerseq))
                rebuild();
            distRefresh -= dt;
            if(distRefresh <= 0) {
                distRefresh = 0.5;
                distances();
            }
            if(pending != null)
                resolvePending();
        }

        private boolean wanted(Marker m) {
            switch(show.sel) {
                case SHOW_PLACED: if(!(m instanceof PMarker)) return(false); break;
                case SHOW_NATURAL: if(!(m instanceof SMarker)) return(false); break;
            }
            String q = markerSearchPattern;
            if((q != null) && !q.isEmpty())
                return((m.nm != null) && m.nm.toLowerCase().contains(q.toLowerCase()));
            return(true);
        }

        private void rebuild() {
            int seq = view.file.markerseq;
            if(!view.file.lock.readLock().tryLock())
                return;
            List<Row> next = new ArrayList<>();
            try {
                Map<Marker, Row> prev = new HashMap<>();
                for(Row r : rows)
                    prev.put(r.m, r);
                for(Marker m : view.file.markers) {
                    if(!wanted(m))
                        continue;
                    Row r = prev.get(m);
                    if(r == null)
                        r = new Row(m);
                    else
                        r.type = MarkerType.of(m);
                    next.add(r);
                }
            } finally {
                view.file.lock.readLock().unlock();
            }
            rows = next;
            seenseq = seq;
            dirty = false;
            distances();
            if((list.sel != null) && !rows.contains(list.sel))
                select(null, false);
        }

        /** Recomputes every row's distance and re-sorts; cheap enough for twice a second. */
        private void distances() {
            MiniMap.Location sessloc = view.sessloc;
            Coord ptc = playerTile();
            for(Row r : rows) {
                if((ptc != null) && (sessloc != null) && (r.m.seg == sessloc.seg.id)) {
                    r.d = ptc.dist(r.m.tc);
                    r.dist = distance(ptc, r.m.tc);
                } else {
                    r.d = Double.MAX_VALUE;
                    r.dist = L10n.get("mapwnd.elsewhere");
                }
            }
            java.util.Comparator<Row> byname = (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.m.nm), String.valueOf(b.m.nm));
            switch(sort.sel) {
                case SORT_TYPE:
                    rows.sort(((java.util.Comparator<Row>)(a, b) -> a.type.compareTo(b.type)).thenComparing(byname));
                    break;
                case SORT_DIST:
                    rows.sort(java.util.Comparator.<Row>comparingDouble(r -> r.d).thenComparing(byname));
                    break;
                default:
                    rows.sort(byname);
            }
        }

        /** A marker clicked on the map, or just placed: make sure it is listed, then select it. */
        private void resolvePending() {
            Marker m = pending;
            if((show.sel == SHOW_PLACED && !(m instanceof PMarker)) || (show.sel == SHOW_NATURAL && !(m instanceof SMarker))) {
                show.sel = SHOW_ALL;
                Utils.setprefi(PREF_SHOW, SHOW_ALL);
                dirty = true;
            }
            if(!wanted(m)) {
                search.settext("");
                markerSearchPattern = "";
                dirty = true;
            }
            if(dirty)
                rebuild();
            for(Row r : rows) {
                if(r.m == m) {
                    select(r, false);
                    list.display(r);
                    pending = null;
                    return;
                }
            }
            if(!dirty && (seenseq == view.file.markerseq))
                pending = null; // Not in the map file at all - nothing to show
        }

        void select(Row r, boolean center) {
            list.sel = r;
            if(card != null) {
                card.destroy();
                card = null;
            }
            if(r != null) {
                card = add(new MarkerCard(r.m));
                if(center)
                    view.center(new MiniMap.SpecLocator(r.m.seg, r.m.tc));
            }
            layout(sz.y);
        }
    }

    private class MarkerList extends Listbox<Row> {
        MarkerList(int w, int h) {
            super(w, Math.max(1, h / MarkerType.iconsz), MarkerType.iconsz);
        }

        protected Row listitem(int i) {return(side.rows.get(i));}

        protected int listitems() {return(side.rows.size());}

        protected void drawbg(GOut g) {
            g.chcolor(15, 22, 23, 255);
            g.frect(Coord.z, sz);
            g.chcolor();
        }

        protected void drawsel(GOut g) {
            g.chcolor(233, 156, 84, 70);
            g.frect(Coord.z, g.sz());
            g.chcolor();
        }

        protected void drawitem(GOut g, Row r, int idx) {
            int h = g.sz().y, mid = h / 2;
            try {
                Tex icon = r.type.icon();
                if(markcfg.filter(r.type))
                    g.chcolor(255, 255, 255, 90);
                g.aimage(icon, Coord.of(h / 2, mid), 0.5, 0.5);
                g.chcolor();
            } catch(Loading l) {
            }
            if((r.name == null) || !Utils.eq(r.rname, r.m.nm)) {
                r.rname = r.m.nm;
                r.name = fnd.render(String.valueOf(r.m.nm), Color.WHITE);
            }
            if((r.dtext == null) || !Utils.eq(r.rdist, r.dist)) {
                r.rdist = r.dist;
                r.dtext = smallf.render(r.dist, MUTED);
            }
            int dx = g.sz().x - UI.scale(4);
            g.aimage(r.dtext.tex(), Coord.of(dx, mid), 1, 0.5);
            int nw = dx - r.dtext.sz().x - UI.scale(6) - (h + UI.scale(2));
            g.reclip(Coord.of(h + UI.scale(2), 0), Coord.of(Math.max(0, nw), h)).aimage(r.name.tex(), Coord.of(0, mid), 0, 0.5);
        }

        protected void itemclick(Row r, Coord c, int button) {
            if(button != 1)
                return;
            if(c.x < itemh) {
                /* The icon hides or shows every marker of its kind, as in the stock list. */
                MarkerConfig nc = markcfg.toggle(r.type);
                markcfg = nc;
                cmarkers = nc.sel.isEmpty() ? null : nc;
                return;
            }
            side.select(r, true);
        }

        public Object tooltip(Coord c, Widget prev) {
            if((c.x < itemh) && (itemat(c) != null))
                return(L10n.get("mapwnd.kind_tip"));
            return(super.tooltip(c, prev));
        }
    }

    /** Name, colour, in-world flag and actions for the selected marker. */
    private class MarkerCard extends Widget {
        MarkerCard(Marker mark) {
            super(Coord.of(SIDE_W, 0));
            int w = SIDE_W - PAD * 2;
            int y = PAD;
            TextEntry name = add(new TextEntry(w, mark.nm) {
                    {dshow = true;}
                    public void activate(String text) {
                        mark.nm = text;
                        view.file.update(mark);
                        uploadpmarker(mark);
                        commit();
                    }
                }, PAD, y);
            name.settip(L10n.get("mapwnd.rename_tip"));
            y += name.sz.y + PAD;
            if(mark instanceof PMarker) {
                PMarker pm = (PMarker)mark;
                Widget cols = add(new BuddyWnd.GroupSelector(Math.max(0, Utils.index(BuddyWnd.gc, pm.color))) {
                        protected void changed(int group) {
                            super.changed(group);
                            pm.color = BuddyWnd.gc[group];
                            view.file.update(mark);
                            uploadpmarker(mark);
                        }
                    }, PAD, y);
                y += cols.sz.y + PAD;
                CheckBox onmap = add(new CheckBox(L10n.get("mapwnd.in_world")), PAD, y);
                onmap.state(() -> pm.onmap);
                onmap.set(v -> {
                    pm.onmap = v;
                    view.file.update(mark);
                    uploadpmarker(mark);
                });
                y += onmap.sz.y + PAD;
            }
            int bw = (w - PAD * 2) / 3;
            Button walk = add(new Button(bw, L10n.get("mapwnd.walk"), false, () -> walkTo(mark)), PAD, y);
            walk.settip(L10n.get("mapwnd.walk_tip"));
            add(new Button(bw, L10n.get("mapwnd.timer"), false, () -> timerFor(mark)), PAD + bw + PAD, y)
                .settip(L10n.get("mapwnd.timer_tip"));
            add(new Button(bw, L10n.get("mapwnd.remove"), false, () -> {
                view.file.remove(mark);
                side.select(null, false);
            }), PAD + (bw + PAD) * 2, y);
            y += walk.sz.y + PAD;
            resize(Coord.of(SIDE_W, y));
        }

        public void draw(GOut g) {
            g.chcolor(NStyle.rowEven);
            g.frect(Coord.z, sz);
            g.chcolor(NStyle.border);
            g.frect(Coord.z, Coord.of(sz.x, 1));
            g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
            g.frect(Coord.z, Coord.of(1, sz.y));
            g.frect(Coord.of(sz.x - 1, 0), Coord.of(1, sz.y));
            g.chcolor();
            super.draw(g);
        }
    }

    /** Walk straight towards a marker in the current segment, as alt-clicking the map does. */
    private void walkTo(Marker mark) {
        NGameUI gui = (NGameUI)getparent(GameUI.class);
        MiniMap.Location sessloc = view.sessloc;
        if((gui == null) || (gui.waypointMovementService == null))
            return;
        if((sessloc == null) || (sessloc.seg.id != mark.seg)) {
            gui.msg(L10n.get("mapwnd.walk_elsewhere"));
            return;
        }
        gui.waypointMovementService.clearQueue();
        gui.waypointMovementService.addWaypoint(new MiniMap.Location(sessloc.seg, mark.tc), sessloc);
    }

    /** The marker's timer: a resource gets its own timer, anything else a pin at its spot. */
    private void timerFor(Marker mark) {
        NGameUI gui = (NGameUI)getparent(GameUI.class);
        if((gui == null) || (gui.timerStore == null))
            return;
        if((mark instanceof SMarker) && NMiniMap.isTimerResource(mark)) {
            SMarker sm = (SMarker)mark;
            gui.showTimerPopover(nurgling.widgets.timers.TimerPopover.forResource(gui, sm, (sm.nm != null) ? sm.nm : sm.res.name));
            return;
        }
        nurgling.timers.TimerPlacement.Spot spot = nurgling.timers.TimerPlacement.fromSegment(view.file, mark.seg, mark.tc);
        gui.showTimerPopover((spot == null) ? null : nurgling.widgets.timers.TimerPopover.newPin(gui, spot, mark.nm));
    }

    /** Marker clicked on the map, or just placed: open the list on it. */
    @Override
    public void focus(Marker m) {
        if(side == null)
            return;
        showSide(true);
        side.pending = m;
    }

    private void showSide(boolean on) {
        if(sideShown != on) {
            sideShown = on;
            Utils.setprefb(PREF_SIDE, on);
        }
        relayout();
    }

    /* ---------------------------------------------------------------- map surface */

    private class ZoomButtons extends Widget {
        private final Text plus = fnd.render("+", Color.WHITE), minus = fnd.render("−", Color.WHITE);
        private final int b = UI.scale(20);

        ZoomButtons() {
            super(Coord.of(UI.scale(20), UI.scale(42)));
        }

        public void draw(GOut g) {
            for(int i = 0; i < 2; i++) {
                Coord ul = Coord.of(0, i * (b + UI.scale(2)));
                g.chcolor(28, 37, 38, 230);
                g.frect(ul, Coord.of(b, b));
                g.chcolor(LINE);
                g.frect(ul, Coord.of(b, 1));
                g.frect(ul.add(0, b - 1), Coord.of(b, 1));
                g.frect(ul, Coord.of(1, b));
                g.frect(ul.add(b - 1, 0), Coord.of(1, b));
                g.chcolor();
                g.aimage(((i == 0) ? plus : minus).tex(), ul.add(b / 2, b / 2), 0.5, 0.5);
            }
        }

        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b == 1)
                timerView().zoomStep(ev.c.y < b);
            return(true);
        }

        public Object tooltip(Coord c, Widget prev) {
            return(L10n.get((c.y < b) ? "mapwnd.zoom_in" : "mapwnd.zoom_out"));
        }
    }

    /** Under the map: what is under the cursor, the zoom, who is online, and any active filter. */
    private class StatusStrip extends Widget {
        private Text where, zoomt, online, filter;
        private String swhere, szoom, sonline, sfilter;
        private int onlinex = -1, filterx = -1;

        StatusStrip() {
            super(Coord.of(UI.scale(100), UI.scale(20)));
        }

        private Text upd(Text t, String s, String prev, Color col) {
            if((t != null) && Utils.eq(s, prev))
                return(t);
            return((s == null) ? null : smallf.render(s, col));
        }

        public void tick(double dt) {
            super.tick(dt);
            /* No mousemove arrives once the pointer has left the window. */
            if((hoverc != null) && !ui.mc.sub(view.rootpos()).isect(Coord.z, view.sz))
                hoverc = null;
            String w = null;
            if(hoverc != null) {
                String terr = timerView().terrainAt(hoverc);
                String d = distance(view.xlate(hoverc));
                if((terr != null) && (d != null))
                    w = terr + "  ·  " + d;
                else
                    w = (terr != null) ? terr : d;
            }
            where = upd(where, w, swhere, new Color(220, 220, 220)); swhere = w;
            String z = L10n.get("mapwnd.zoom", Math.round(timerView().zoom() * 100));
            zoomt = upd(zoomt, z, szoom, MUTED); szoom = z;
            int n = (roster == null) ? 0 : roster.count();
            String o = (n > 0) ? "● " + L10n.get("mapwnd.online", n) : null;
            online = upd(online, o, sonline, ONLINE); sonline = o;
            String f = (!sideShown && !markerSearchPattern.isEmpty()) ? L10n.get("mapwnd.filtered", markerSearchPattern) + "  ✕" : null;
            filter = upd(filter, f, sfilter, NStyle.border); sfilter = f;
        }

        public void draw(GOut g) {
            g.chcolor(NStyle.rowEven);
            g.frect(Coord.z, sz);
            g.chcolor(LINE);
            g.frect(Coord.z, Coord.of(sz.x, 1));
            g.chcolor();
            int mid = sz.y / 2, x = UI.scale(6);
            if(zoomt == null)
                return;
            if(where != null)
                g.aimage(where.tex(), Coord.of(x, mid), 0, 0.5);
            int rx = sz.x - UI.scale(6);
            g.aimage(zoomt.tex(), Coord.of(rx, mid), 1, 0.5);
            rx -= zoomt.sz().x + UI.scale(14);
            onlinex = -1;
            if(online != null) {
                rx -= online.sz().x;
                onlinex = rx;
                g.aimage(online.tex(), Coord.of(rx, mid), 0, 0.5);
                rx -= UI.scale(14);
            }
            filterx = -1;
            if(filter != null) {
                rx -= filter.sz().x;
                filterx = rx;
                g.aimage(filter.tex(), Coord.of(rx, mid), 0, 0.5);
            }
        }

        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b != 1)
                return(false);
            if((online != null) && (onlinex >= 0) && (ev.c.x >= onlinex) && (ev.c.x < onlinex + online.sz().x)) {
                if(justClosed != playersFly)
                    toggleFly(playersFly);
                return(true);
            }
            if((filter != null) && (filterx >= 0) && (ev.c.x >= filterx) && (ev.c.x < filterx + filter.sz().x)) {
                side.search.settext("");
                markerSearchPattern = "";
                side.dirty = true;
                return(true);
            }
            return(false);
        }
    }

    /* ---------------------------------------------------------------- layout */

    /** Hides the stock controls this layout replaces, then lays everything out. */
    private void applyMode() {
        if(bar == null)
            return;
        tool.hide();
        toolbar.hide();
        /* Through resize, so the minimum size applies too. */
        resize(csz());
    }

    /** Always the full window: compact mode is not offered. */
    @Override
    public void compact(boolean a) {
        super.compact(false);
        applyMode();
    }

    @Override
    public void resize(Coord sz) {
        if(bar != null)
            sz = sz.max(MINSZ);
        super.resize(sz);
        relayout();
    }

    private void relayout() {
        if(bar == null)
            return;
        Coord cs = csz();
        bar.c = Coord.z;
        bar.layout(cs.x);
        int top = bar.sz.y + PAD;
        int sidew = sideShown ? (SIDE_W + PAD) : 0;
        int stath = status.sz.y;
        viewf.c = Coord.of(0, top);
        viewf.resize(Coord.of(cs.x - sidew, cs.y - top - stath));
        view.resize(viewf.inner());
        status.c = Coord.of(0, viewf.c.y + viewf.sz.y);
        status.resize(Coord.of(viewf.sz.x, stath));
        side.show(sideShown);
        side.c = Coord.of(cs.x - SIDE_W, top);
        side.layout(cs.y - top);
        if(openFly != null)
            placeFly(openFly);
    }

    private double dbCheck = 0;

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(bar == null)
            return;
        dbCheck -= dt;
        if(dbCheck <= 0) {
            dbCheck = 0.5;
            /* Players only shows while somebody is online, so the row reflows when that changes. */
            boolean anyone = roster.count() > 0;
            if(playersBtn.visible() != anyone) {
                playersBtn.show(anyone);
                if(!anyone && (openFly == playersFly))
                    closeFly();
                relayout();
            }
            /* Database settings can be switched at runtime. */
            if((openFly == shareFly) && (shareDb.visible() != MapDbTransfer.configured())) {
                shareDb.show(MapDbTransfer.configured());
                shareFly.fit();
                placeFly(shareFly);
            }
        }
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if((openFly != null) && key_esc.match(ev)) {
            closeFly();
            return(true);
        }
        return(super.keydown(ev));
    }

    private void clearVectors() {
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if(gui != null && gui.map instanceof nurgling.NMapView) {
            nurgling.NMapView mapView = (nurgling.NMapView) gui.map;
            if(!mapView.directionalVectors.isEmpty()) {
                int count = mapView.directionalVectors.size();
                mapView.clearDirectionalVectors();
                nurgling.tools.DirectionalVector.resetColorCycle();
                gui.msg("Cleared " + count + " directional vector" + (count > 1 ? "s" : ""));
            }
        }
    }

    public long playerSegmentId() {
        MiniMap.Location sessloc = view.sessloc;
        if(sessloc == null) {return 0;}
        return sessloc.seg.id;
    }

    public Coord2d findMarkerPosition(String name) {
        MiniMap.Location sessloc = view.sessloc;
        if(sessloc == null) {return null;}
        for (MapFile.Marker mark : file.markers) {
            if(mark instanceof MapFile.SMarker) {
                MapFile.SMarker m = (MapFile.SMarker) mark;
                if(m.seg == sessloc.seg.id && m.nm != null && name != null && m.nm.contains(name)) {
                    return m.tc.sub(sessloc.tc).mul(tilesz);
                }
            }
        }
        return null;
    }

    /** The map view is always an NMiniMap; MapWnd only declares it as a MiniMap. */
    private NMiniMap timerView() {
        return (NMiniMap) view;
    }

    /** A pin picked up with the left button; it moves on release if the mouse travelled. */
    private nurgling.timers.Timer pressedPin = null;
    private Coord pressedAt = null;
    private UI.Grab pinGrab = null;

    /** The flyout the current click has just closed, so the same click cannot reopen it. */
    private Flyout justClosed = null;

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        /* An open flyout takes its own clicks, and its button toggles it. A click anywhere else
         * closes it; on the map that is all the click does, so closing one does not also walk the
         * character there, while elsewhere (another button, the list) the click still counts. */
        if(openFly != null) {
            if(ev.c.isect(openFly.parentpos(this), openFly.sz))
                return(super.mousedown(ev));
            if(!ev.c.isect(openFly.owner.parentpos(this), openFly.owner.sz)) {
                justClosed = openFly;
                closeFly();
                if(ev.c.isect(view.parentpos(this), view.sz)) {
                    justClosed = null;
                    return(true);
                }
            }
        }
        try {
            return(mapmousedown(ev));
        } finally {
            justClosed = null;
        }
    }

    private boolean mapmousedown(MouseDownEvent ev) {
        // Handle alt+left-click for waypoint queueing (on button release handled below)
        // Handle shift+right-click for timers
        if(view.c != null) {
            // Convert global coordinates to view coordinates
            Coord viewCoord = ev.c.sub(view.parentpos(this));

            // Check if the click is within the view bounds
            if(viewCoord.x >= 0 && viewCoord.x < view.sz.x &&
               viewCoord.y >= 0 && viewCoord.y < view.sz.y) {

                // Shift+right-click for tree locations and timers
                if(ev.b == 3 && ui.modshift) {
                    // First check for tree icons
                    if(handleTreeSaveClick(viewCoord)) {
                        return true; // Consume the event
                    }
                    if(handleTimerClick(viewCoord)) {
                        return true; // Consume the event
                    }
                }

                // Left button on a pin picks it up: a drag moves it, a click opens it.
                if(ev.b == 1 && !ui.modshift && !ui.modctrl && !ui.modmeta) {
                    nurgling.timers.Timer t = timerView().timerAt(viewCoord);
                    if(t != null && t.kind == nurgling.timers.Timer.Kind.PIN) {
                        pressedPin = t;
                        pressedAt = ev.c;
                        pinGrab = ui.grabmouse(this);
                        return true;
                    }
                }
            }
        }

        return super.mousedown(ev);
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        if(pressedPin != null && pressedAt != null && ev.c.dist(pressedAt) > UI.scale(4)) {
            timerView().dragTimer = pressedPin;
            timerView().dragPos = ev.c.sub(view.parentpos(this));
        }
        Coord vc = ev.c.sub(view.parentpos(this));
        hoverc = (view.visible() && vc.isect(Coord.z, view.sz)) ? vc : null;
        super.mousemove(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if(ev.b == 1 && pressedPin != null) {
            dropPin(ev.c);
            return true;
        }
        if(view.c != null) {
            Coord viewCoord = ev.c.sub(view.parentpos(this));

            // Check if the click is within the view bounds
            if(viewCoord.x >= 0 && viewCoord.x < view.sz.x &&
               viewCoord.y >= 0 && viewCoord.y < view.sz.y) {

                // Left-click for forager path recording (without modifier)
                if(ev.b == 1 && !ui.modmeta && !ui.modshift && !ui.modctrl) {
                    if(handleForagerRecordingClick(viewCoord)) {
                        return true; // Consume the event
                    }
                }
                
                // alt+left-click for waypoint queueing; shift is excluded because
                // alt+shift+left-click is the map ping (NMiniMap.sendPointPing)
                if(ev.b == 1 && ui.modmeta && !ui.modshift) {
                    if(handleWaypointClick(viewCoord)) {
                        return true; // Consume the event
                    }
                }

                // Right-click for clearing waypoint queue (fish handling is in parent NMiniMap)
                if(ev.b == 3 && !ui.modshift) {
                    // Clear waypoint queue on regular right-click (if not on fish/marker)
                    NGameUI gui = (NGameUI) NUtils.getGameUI();
                    if(gui != null && gui.waypointMovementService != null) {
                        gui.waypointMovementService.clearQueue();
                    }
                    // Let parent handle fish location clicks and other right-click behavior
                }
            }
        }

        return super.mouseup(ev);
    }

    private boolean handleForagerRecordingClick(Coord c) {
        // Check if a PathRecordable window is open and in recording mode
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if(gui == null) return false;

        // Find a PathRecordable window (Forager or TrufflePigHunter)
        nurgling.widgets.bots.PathRecordable pathWnd = null;
        for(Widget wdg = gui.lchild; wdg != null; wdg = wdg.prev) {
            if(wdg instanceof nurgling.widgets.bots.PathRecordable) {
                pathWnd = (nurgling.widgets.bots.PathRecordable) wdg;
                break;
            }
        }

        if(pathWnd == null || !pathWnd.isRecording()) {
            return false; // Not recording, don't consume the event
        }

        // Get the location at the clicked position
        MiniMap.Location clickLoc = view.xlate(c);
        if(clickLoc == null || view.sessloc == null) return false;

        // Only handle if in same segment
        if(clickLoc.seg.id != view.sessloc.seg.id) return false;

        // Create ForagerWaypoint from MiniMap.Location
        nurgling.routes.ForagerWaypoint waypoint = new nurgling.routes.ForagerWaypoint(clickLoc);

        // Add waypoint to the recording path
        pathWnd.addWaypointToRecording(waypoint);

        return true; // Consume the event
    }
    
    private boolean handleWaypointClick(Coord c) {
        // Try to get the location at clicked coordinates
        MiniMap.Location clickLoc = view.xlate(c);
        if(clickLoc == null || view.sessloc == null) return false;

        // Only handle if in same segment
        if(clickLoc.seg.id != view.sessloc.seg.id) return false;

        // Use the service to add waypoint
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if(gui != null && gui.waypointMovementService != null) {
            gui.waypointMovementService.addWaypoint(clickLoc, view.sessloc);
            return true;
        }

        return false;
    }
    
    /** Move a dragged pin to where it was dropped, or open it if it was only clicked. */
    private void dropPin(Coord c) {
        nurgling.timers.Timer t = pressedPin;
        boolean dragged = timerView().dragTimer != null;
        pressedPin = null;
        pressedAt = null;
        timerView().dragTimer = null;
        timerView().dragPos = null;
        if(pinGrab != null) {
            pinGrab.remove();
            pinGrab = null;
        }
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if(gui == null || gui.timerStore == null)
            return;
        if(!dragged) {
            gui.showTimerPopover(nurgling.widgets.timers.TimerPopover.edit(gui, t));
            return;
        }
        nurgling.timers.TimerPlacement.Spot spot = nurgling.timers.TimerPlacement.fromMap(view.file, view.xlate(c.sub(view.parentpos(this))));
        if(spot == null)
            return;
        nurgling.timers.Timer cur = gui.timerStore.get(t.id);
        if(cur != null)
            gui.timerStore.put(cur.withLocation(spot.gridId, spot.offset.x, spot.offset.y));
    }

    /**
     * Shift+right-click on the big map: an existing timer opens for editing; a resource marker gets its
     * timer; one of your own markers or empty ground gets a pin.
     */
    private boolean handleTimerClick(Coord c) {
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if(gui == null || gui.timerStore == null)
            return false;

        nurgling.timers.Timer existing = timerView().timerAt(c);
        if(existing != null) {
            gui.showTimerPopover(nurgling.widgets.timers.TimerPopover.edit(gui, existing));
            return true;
        }

        MiniMap.Location clickLoc = view.xlate(c);
        if(clickLoc == null)
            return false;
        MiniMap.DisplayMarker marker = view.markerat(clickLoc.tc);
        if(marker != null && NMiniMap.isTimerResource(marker.m)) {
            MapFile.SMarker smarker = (MapFile.SMarker) marker.m;
            String name = (smarker.nm != null) ? smarker.nm : smarker.res.name;
            gui.showTimerPopover(nurgling.widgets.timers.TimerPopover.forResource(gui, smarker, name));
            return true;
        }

        MiniMap.Location at = clickLoc;
        String name = "";
        if(marker != null && marker.m instanceof MapFile.PMarker) {
            at = new MiniMap.Location(clickLoc.seg, marker.m.tc);
            name = marker.m.nm;
        }
        nurgling.timers.TimerPlacement.Spot spot = nurgling.timers.TimerPlacement.fromMap(view.file, at);
        gui.showTimerPopover((spot == null) ? null : nurgling.widgets.timers.TimerPopover.newPin(gui, spot, name));
        return true;
    }

    private boolean handleTreeSaveClick(Coord c) {
        // TODO: Implement tree saving from map click
        // For now, trees can be saved through other means
        // This would require access to gobs at the clicked location
        return false;
    }

    @Override
    public void recenter() {
        super.recenter();
    }
}
