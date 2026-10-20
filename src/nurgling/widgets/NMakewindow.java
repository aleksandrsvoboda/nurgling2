package nurgling.widgets;

import haven.Button;
import haven.*;
import haven.Frame;
import haven.Label;
import static haven.Inventory.*;

import haven.render.Render;
import haven.res.lib.itemtex.*;
import static haven.PType.*;
import nurgling.*;
import nurgling.actions.bots.*;
import nurgling.areas.*;
import nurgling.i18n.L10n;
import nurgling.sessions.BotExecutor;
import nurgling.tools.*;
import org.json.*;

import java.awt.*;
import java.awt.image.*;
import java.util.List;
import java.util.*;

public class NMakewindow extends Widget {
//    public static final Text.Foundry fnd = new Text.Foundry(Text.sans, 12);

    public static Text.Furnace fnd = new PUtils.BlurFurn(new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12).aa(true), UI.scale(1), UI.scale(1), Color.BLACK);
    public static Text.Furnace fnd2 = new Text.Foundry(Text.sans, 12).aa(true);
    public static Text qmodl = null;
    private static Text getQmodl() {
        if (qmodl == null) qmodl = fnd.render(L10n.get("craft.quality"));
        return qmodl;
    }
    public static final TexI aready = new TexI(Resource.loadsimg("nurgling/hud/autocraft/ready"));
    public static final TexI anotfound = new TexI(Resource.loadsimg("nurgling/hud/autocraft/notfound"));
    public static final TexI categories = new TexI(Resource.loadsimg("nurgling/hud/autocraft/spec"));
    public static final TexI ignoreOverlay = new TexI(Resource.loadsimg("nurgling/hud/autocraft/ignore"));
    /** Corner badge over the ready frame: this input is fetched from the Storage Items index. */
    public static final TexI storageBadge = new TexI(storageBadgeImg(Inventory.invsq.sz()));
    private static final double STORAGE_REFRESH = 10;
    public static Text tooll = null;
    private static Text getTooll() {
        if (tooll == null) tooll = fnd.render(L10n.get("craft.tools"));
        return tooll;
    }
    public static final Coord boff = UI.scale(new Coord(7, 9));
    public String rcpnm;
    public String recipeResource;
    public boolean atlasInputsReceived, atlasOutputsReceived;
    private boolean atlasDirty = true;
    private double atlasRetry;
    public List<Spec> inputs = Collections.emptyList();
    public List<Spec> outputs = Collections.emptyList();
    public List<Indir<Resource>> qmod = Collections.emptyList();
    public List<Indir<Resource>> tools = new ArrayList<>();;
    private int xoff = UI.scale(45), qmy = UI.scale(38), outy = UI.scale(65);
    public static final Text.Foundry nmf = new Text.Foundry(Text.serif, 20).aa(true);
    private static double softcap = 0;
    private static Tex softTex = null;
    private static Tex softTexLabel = null;
    public CheckBox noTransfer = null;
    public boolean autoMode = false;
    private CheckBox useStorage = null;
    private Button savePresetBtn = null;
    private boolean presetReady = true;
    private Button craftBtn, craftAllBtn;
    private CraftModeSwitch modeSwitch;
    /** Set from any thread by setAutoMode; the widgets move on the next UI tick. */
    private volatile boolean layoutDirty = true;
    /** Exact name -> number of Storage Items rows; filled on a database thread. */
    /**
     * Exact name -> number of Storage Items rows, 0 once asked and none found. Shared by every craft
     * window, so a reopened picker or another recipe shows what is already known at once. Filled on
     * a database thread.
     */
    private static final Map<String, Integer> storageCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean storageQueryRunning = false;
    private double storageQueryAge = STORAGE_REFRESH;

    private static final OwnerContext.ClassResolver<NMakewindow> ctxr = new OwnerContext.ClassResolver<NMakewindow>()
            .add(Glob.class, wdg -> wdg.ui.sess.glob)
            .add(Session.class, wdg -> wdg.ui.sess);
    public class Spec implements GSprite.Owner, ItemInfo.SpriteOwner {
        public Indir<Resource> res;
        public MessageBuf sdt;
        public ResData constraint = null;
        public Tex num;
        public String name;
        public int count;
        public GSprite spr;
        private Object[] rawinfo;
        private List<ItemInfo> info;

        public Ingredient ing = null;

        public int using = 0;
        public List<MenuGrid.Pagina> rpag = null;
        public Coord rcc = null;

        public Spec(Indir<Resource> res, Message sdt, int num, Object[] info) {
            this.res = res;
            this.sdt = new MessageBuf(sdt);
            if(num >= 0)
                this.num = new TexI(Utils.outline2(Text.render(Integer.toString(num), Color.WHITE).img, Utils.contrast(Color.WHITE)));
            else
                this.num = null;
            this.rawinfo = info;
            this.count = num;
        }

        public GSprite sprite() {
            if(spr == null)
                spr = GSprite.create(this, res.get(), sdt.clone());;
            return(spr);
        }

        public void draw(GOut g) {
            try {
                if(ing==null || !autoMode)
                {
                    sprite().draw(g);
                }
                else
                {
                    g.image(new TexI(ing.img), Coord.z, UI.scale(32,32));
                }
            } catch(Loading e) {}
            if(num != null)
                g.aimage(num, Inventory.sqsz, 1.0, 1.0);
        }

        private int opt = 0;
        public boolean opt() {
            if(opt == 0) {
                try {
                    List<ItemInfo> infoList = info();
                    boolean found = false;
                    for (ItemInfo inf : infoList) {
                        String className = inf.getClass().getName();
                        if (className.endsWith("$Optional")) {
                            found = true;
                            break;
                        }
                    }
                    opt = found ? 1 : 2;
                } catch(Loading l) {
                    return(false);
                } catch(Exception e) {
                    e.printStackTrace();
                    return(false);
                }
            }
            return(opt == 1);
        }

        public BufferedImage shorttip() {
            List<ItemInfo> info = info();
            if(info.isEmpty()) {
                Resource.Tooltip tt = res.get().layer(Resource.tooltip);
                if(tt == null)
                    return(null);
                return(Text.render(tt.text()).img);
            }
            return(ItemInfo.shorttip(info()));
        }
        public BufferedImage longtip() {
            List<ItemInfo> info = info();
            BufferedImage img;
            if(info.isEmpty()) {
                Resource.Tooltip tt = res.get().layer(Resource.tooltip);
                if(tt == null)
                    return(null);
                img = Text.render(tt.text()).img;
            } else {
                img = ItemInfo.longtip(info);
            }
            Resource.Pagina pg = res.get().layer(Resource.pagina);
            if(pg != null)
                img = ItemInfo.catimgs(0, img, RichText.render("\n" + pg.text, 200).img);
            return(img);
        }

        private Random rnd = null;
        public Random mkrandoom() {
            if(rnd == null)
                rnd = new Random();
            return(rnd);
        }
        public Resource getres() {return(res.get());}
        public <T> T context(Class<T> cl) {return(ctxr.context(cl, NMakewindow.this));}
        @Deprecated
        public Glob glob() {return(ui.sess.glob);}

        public List<ItemInfo> info() {
            if(info == null)
                info = ItemInfo.buildinfo(this, rawinfo);
            return(info);
        }
        public Resource resource() {return(res.get());}


        void tick(double dt)
        {
            // Load name from sprite, or directly from resource in headless mode
            if (name == null && (spr != null || nurgling.headless.Headless.isHeadless()))
            {
                if (!res.get().name.contains("coin"))
                {
                    if (res.get() != null)
                    {
                        name = ItemInfo.Name.Default.get(this);
                    }
                }
            }
            if(NMakewindow.this.autoMode && name!=null)
            {
                logisticin = (NContext.findIn(name) != null);
                if(!logisticin)
                {
                    categories = (VSpec.categories.get(name)!=null);
                }
                logisticout = (NContext.findOut(name,1) != null);
                if(!logisticout)
                {
                    categories = (VSpec.categories.get(name)!=null);
                }
                for(Spec s : inputs) {
                    if(s.categories && s.ing!=null)
                    {
                        s.ing.logistic = (NContext.findIn(s.ing.name) != null);
                    }
                }
                for(Spec s : outputs) {
                    if(s.categories && s.ing!=null)
                    {
                        s.ing.logistic = (NContext.findOut(s.ing.name, 1) != null);
                    }
                }
            }
        }

        String name()
        {
            return name;
        }

        public boolean logisticin = false;
        public boolean logisticout = false;
        public boolean categories = false;


    }

    public void tick(double dt) {
        for(Spec s : inputs) {
            if(s.spr != null)
                s.spr.tick(dt);
            s.tick(dt);
            if((s.rcc != null) && (s.rpag != null)) {
                if(!s.rpag.isEmpty()) {
                    SListMenu.of(UI.scale(250, 120), s.rpag,
                                 pag -> pag.button().name(), pag -> pag.button().img(),
                                 pag -> pag.button().use(new MenuGrid.Interaction(1, ui.modflags())))
                        .addat(this, s.rcc.add(UI.scale(5, 5))).tick(dt);
                }
                s.rcc = null;
            }
        }
        for(Spec s : outputs) {
            if(s.spr != null)
                s.spr.tick(dt);
            s.tick(dt);
        }
        if(cat!=null) {
            cat.raise();
            cat.tick(dt);
        }

        if(layoutDirty)
            relayout();
        boolean ready = allInputsConfigured();
        if(ready != presetReady) {
            presetReady = ready;
            savePresetBtn.disable(!ready);
            savePresetBtn.settip(L10n.get(ready ? "craft.save_preset_tip" : "craft.save_preset_disabled"));
        }
        boolean showStorage = autoMode && storageEnabled();
        if(useStorage.visible != showStorage) {
            useStorage.visible = showStorage;
            layoutDirty = true;
        }
        refreshStorageCounts(dt);
        atlasRetry -= dt;
        if(atlasDirty && atlasRetry <= 0) {
            atlasRetry = .5;
            NGameUI owner = getparent(NGameUI.class);
            if(owner != null && owner.atlas != null) try {
                if(owner.atlas.observe(this)) atlasDirty = false;
            } catch(Loading ignored) {
                // Resource-backed names and optional flags may arrive after the specs.
            } catch(RuntimeException e) {
                atlasDirty = false;
                System.err.println("[CraftAtlas] Cannot record " + rcpnm + ": " + e);
            }
        }
    }

    /** Turns autocrafting on as the mode switch does. */
    public void enableAutoMode() {
        setAutoMode(true);
    }

    /**
     * Switch between Manual and Autocraft. Safe from a bot thread: the Autocraft panel is shown or
     * hidden, and the window resized, on the next UI tick.
     */
    public void setAutoMode(boolean val) {
        autoMode = val;
        layoutDirty = true;
    }

    /** Whether this client uses the database at all; the storage toggle is only offered then. */
    private static boolean storageEnabled() {
        return (Boolean) NConfig.get(NConfig.Key.ndbenable);
    }

    /** Whether an autocraft started now should fetch area-less inputs from the Storage Items index. */
    public boolean storageActive() {
        // Not isReady(): that reads false whenever every pooled connection is busy, and a query waits for one
        return autoMode && useStorage != null && useStorage.a && storageEnabled() && NCore.databaseManager != null;
    }

    private boolean inStorage(String name) {
        return name != null && storageActive() && storageCounts.getOrDefault(name, 0) > 0;
    }

    /**
     * Keep storageCounts current for every name this window can show: each input and, for a
     * category input, every option its picker offers, so the picker has its borders before it
     * opens. Names never asked about are queried on the next tick; all of them again every few
     * seconds, since rows change whenever a container is closed.
     */
    private void refreshStorageCounts(double dt) {
        storageQueryAge += dt;
        if(!storageActive() || storageQueryRunning)
            return;
        Set<String> names = new HashSet<>();
        for(Spec s : inputs) {
            if(s.ing != null && !s.ing.isIgnored)
                names.add(s.ing.name);
            if(s.name == null)
                continue;
            ArrayList<JSONObject> options = s.categories ? VSpec.categories.get(s.name) : null;
            if(options != null) {
                for(JSONObject obj : options)
                    names.add((String) obj.get("name"));
            } else if(!s.categories) {
                names.add(s.name);
            }
        }
        if(cat != null) {
            for(Ingredient ing : cat.data)
                if(!ing.isIgnored)
                    names.add(ing.name);
        }
        names.remove(null);
        Set<String> ask;
        if(storageQueryAge >= STORAGE_REFRESH) {
            ask = names;
            storageQueryAge = 0;
        } else {
            ask = new HashSet<>();
            for(String n : names)
                if(!storageCounts.containsKey(n))
                    ask.add(n);
        }
        if(ask.isEmpty())
            return;
        storageQueryRunning = true;
        // Read on the UI thread; the village is worked out with the query, off it
        nurgling.widgets.storage.VillageStorage storage = nurgling.widgets.storage.VillageStorage.snapshot(getparent(NGameUI.class));
        NCore.databaseManager.submitTask(() -> {
            try {
                Set<Long> village = storage.village();
                Map<String, Integer> counts = new HashMap<>();
                for(String n : ask)
                    counts.put(n, 0);
                for(nurgling.db.dao.StorageItemDao.LocatedItem row : NCore.databaseManager.getStorageItemService().loadByExactNames(ask))
                    if(nurgling.widgets.storage.VillageStorage.inVillage(row.gridId, village))
                        counts.merge(row.name, 1, Integer::sum);
                storageCounts.putAll(counts);
            } catch(java.sql.SQLException e) {
                System.out.println("[NMakewindow] storage count query failed: " + e.getMessage());
            } finally {
                storageQueryRunning = false;
            }
        });
    }

    /** Lay out the button row and, in Autocraft, the panel above it; then refit the window. */
    private void relayout() {
        layoutDirty = false;
        noTransfer.visible = autoMode;
        useStorage.visible = autoMode && storageEnabled();
        savePresetBtn.visible = autoMode;
        int panelY = outy + Inventory.sqsz.y + UI.scale(8);
        int panelH = savePresetBtn.sz.y + UI.scale(10);
        int rowY = panelY + (panelH - savePresetBtn.sz.y) / 2;
        savePresetBtn.c = Coord.of(UI.scale(325), rowY);
        noTransfer.c = Coord.of(0, rowY + (savePresetBtn.sz.y - noTransfer.sz.y) / 2);
        useStorage.c = Coord.of(noTransfer.c.x + noTransfer.sz.x + UI.scale(16), noTransfer.c.y);
        int by = autoMode ? panelY + panelH + UI.scale(4) : panelY;
        modeSwitch.c = Coord.of(0, by + (craftBtn.sz.y - modeSwitch.sz.y) / 2);
        craft_num.c = Coord.of(UI.scale(165), by + (craftBtn.sz.y - craft_num.sz.y) / 2);
        craftBtn.c = Coord.of(UI.scale(230), by);
        craftAllBtn.c = Coord.of(UI.scale(325), by);
        panelTop = panelY;
        panelBottom = panelY + panelH;
        pack();
        if(parent instanceof NCraftWindow)
            ((NCraftWindow) parent).refit();
    }

    private int panelTop, panelBottom;

    private static BufferedImage storageBadgeImg(Coord sz) {
        BufferedImage img = TexI.mkbuf(sz);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int leg = sz.x * 11 / 20, h = sz.y;
        g.setColor(NStyle.border);
        g.fillPolygon(new int[]{0, leg, 0}, new int[]{h - leg, h, h}, 3);
        // A small chest: body and lid line
        int cw = Math.max(5, sz.x / 5), ch = Math.max(4, sz.y / 7);
        int cx = Math.max(1, sz.x / 16), cy = h - ch - Math.max(1, sz.y / 16);
        g.setColor(new Color(24, 30, 32));
        g.fillRect(cx, cy, cw, ch);
        g.setColor(NStyle.border);
        g.drawLine(cx, cy + ch / 3, cx + cw - 1, cy + ch / 3);
        g.dispose();
        return img;
    }

    /**
     * Check if all category inputs have their ingredient selected.
     */
    private boolean allInputsConfigured() {
        for (Spec s : inputs) {
            // If it's a category input, it needs to have an ingredient selected (or be ignored)
            if (s.categories && s.ing == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(autoMode)
        {
            Coord sc = new Coord(xoff, 0);
            boolean popt = false;
            if (clickForCategories(inputs, popt, sc, ev.c)) return true;
            sc = new Coord(xoff, outy);
            if (clickForCategories(outputs, popt, sc, ev.c)) return true;
        }
        else
        {
            Coord sc = new Coord(xoff, 0);
            boolean popt = false;
            int idx = 0;
            for(Spec s : inputs) {
                boolean opt = s.opt();
                if(opt != popt)
                    sc = sc.add(10, 0);
                if(ev.c.isect(sc, Inventory.sqsz)) {
                    if(ev.b == 1) {
                        wdgmsg("choose", idx, ui.modflags());
                        return true;
                    } else if(ev.b == 3) {
                        if(s.rpag == null)
                            wdgmsg("findrcps", idx);
                        s.rcc = ev.c;
                        return true;
                    }
                }
                sc = sc.add(Inventory.sqsz.x, 0);
                popt = opt;
                idx++;
            }
        }
        return super.mousedown(ev);
    }

    private boolean clickForCategories(List<Spec> outputs, boolean popt, Coord sc, Coord c) {
        for (Spec s : outputs) {
            boolean opt = s.opt();
            if (opt != popt)
                sc = sc.add(10, 0);
            if (s.categories) {
                if (c.isect(sc, Inventory.sqsz)) {
                    boolean isOpt = s.opt();
                    ArrayList<JSONObject> categoryItems = VSpec.categories.get(s.name);
                    if (cat == null) {
                        // If category has only one item and it's not optional, auto-select it
                        if (categoryItems != null && categoryItems.size() == 1 && !isOpt) {
                            s.ing = new Ingredient(categoryItems.get(0));
                            return true;
                        }
                        NUtils.getGameUI().add(cat = new Categories(categoryItems, s, isOpt), sc.add(this.parent.c).add(this.c).add(Inventory.sqsz.x / 2, Inventory.sqsz.y * 2).add(UI.scale(2, 2)));
                        pack();
                        NUtils.getGameUI().craftwnd.lower();
                        cat.raise();
                        return true;
                    }
                }
            }
            sc = sc.add(Inventory.sqsz.x, 0);
            popt = opt;
        }
        return false;
    }


    TextEntry craft_num;
    public static final KeyBinding kb_make = KeyBinding.get("make/one", KeyMatch.forcode(java.awt.event.KeyEvent.VK_ENTER, 0));
    public static final KeyBinding kb_makeall = KeyBinding.get("make/all", KeyMatch.forcode(java.awt.event.KeyEvent.VK_ENTER, KeyMatch.C));
    public NMakewindow(String rcpnm) {
        int inputW = add(new Label(L10n.get("craft.input")), new Coord(0, UI.scale(8))).sz.x;
        int resultW = add(new Label(L10n.get("craft.result")), new Coord(0, outy + UI.scale(8))).sz.x;
        xoff = Math.max(inputW, resultW) + UI.scale(10);

        craft_num = add(new TextEntry(UI.scale(55), ""), Coord.z);
        craftBtn = add(new Button(UI.scale(85), L10n.get("craft.craft")), Coord.z);
        craftBtn.action(() -> craft()).setgkey(kb_make);
        craftAllBtn = add(new Button(UI.scale(85), L10n.get("craft.craft_all")), Coord.z);
        craftAllBtn.action(() -> craftAll()).setgkey(kb_makeall);
        modeSwitch = add(new CraftModeSwitch(() -> autoMode, this::setAutoMode), Coord.z);

        noTransfer = add(new CheckBox(L10n.get("craft.no_transfer")), Coord.z);
        useStorage = add(new CheckBox(L10n.get("craft.use_storage")) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                NConfig.set(NConfig.Key.craftUseStorage, val);
                storageQueryAge = STORAGE_REFRESH;
            }
        }, Coord.z);
        useStorage.a = Boolean.TRUE.equals(NConfig.get(NConfig.Key.craftUseStorage));
        useStorage.settip(L10n.get("craft.use_storage_tip"));
        savePresetBtn = add(new Button(UI.scale(85), L10n.get("craft.save_preset")), Coord.z);
        savePresetBtn.action(this::openSavePresetDialog);
        savePresetBtn.settip(L10n.get("craft.save_preset_tip"));

        relayout();
        this.rcpnm = rcpnm;

        // Capture recipe resource from MenuGrid.lastPagina while it's still valid
        if (MenuGrid.lastPagina != null) {
            try {
                this.recipeResource = MenuGrid.lastPagina.res().name;
            } catch (Loading l) {
                // Resource not loaded yet
            }
        }
    }

    private void openSavePresetDialog() {
        NUtils.getGameUI().add(new SaveCraftPresetDialog(this), UI.scale(new Coord(200, 200)));
    }

    // Parse one make-window spec (protocol v31: modular message format).
    private Spec parsespec(Object[] desc) {
        int a = 0;
        Indir<Resource> res = ui.sess.getresv(desc[a++]);
        Message sdt = BYTES.is(desc, a) ? new MessageBuf(BYTES.of(desc, a++)) : MessageBuf.nil;
        int num = INT.of(desc, a++);
        Object[] info = OBJS.is(desc, a) ? OBJS.of(desc, a++) : new Object[0];
        Spec ret = new Spec(res, sdt, num, info);
        while(a < desc.length) {
            Object[] arg = OBJS.of(desc[a++]);
            switch(STR.of(arg[0])) {
            case "constraint":
                ResData cst = new ResData(ui.sess.getresv(arg[1]), Message.nil);
                if(BYTES.is(arg, 2))
                    cst.sdt = new MessageBuf(BYTES.of(arg, 2));
                ret.constraint = cst;
                break;
            }
        }
        return ret;
    }

    public void uimsg(String msg, Object... args) {
        if(msg == "inpop") {
            List<Spec> inputs;
            if(INT.is(args, 0)) {
                // Indexed update: reuse existing specs, replace by index.
                inputs = new ArrayList<>(this.inputs);
                for(int i = 0; i < args.length; i += 2)
                    inputs.set(INT.of(args, i), parsespec(OBJS.of(args, i + 1)));
            } else {
                inputs = new ArrayList<>();
                for(int i = 0; i < args.length; i++)
                    inputs.add(parsespec(OBJS.of(args[i])));
            }
            this.inputs = inputs;
            atlasInputsReceived = atlasDirty = true;
        } else if(msg == "opop") {
            List<Spec> outputs;
            if(INT.is(args, 0)) {
                outputs = new ArrayList<>(this.outputs);
                for(int i = 0; i < args.length; i += 2)
                    outputs.set(INT.of(args, i), parsespec(OBJS.of(args, i + 1)));
            } else {
                outputs = new ArrayList<>();
                for(int i = 0; i < args.length; i++)
                    outputs.add(parsespec(OBJS.of(args[i])));
            }
            this.outputs = outputs;
            atlasOutputsReceived = atlasDirty = true;
        } else if(msg == "qmod") {
            List<Indir<Resource>> qmod = new ArrayList<Indir<Resource>>();
            for(Object arg : args)
                qmod.add(ui.sess.getres((Integer)arg));
            this.qmod = qmod;
            atlasDirty = true;
        } else if(msg == "tool") {
            tools.add(ui.sess.getres((Integer)args[0]));
            atlasDirty = true;
        } else if(msg == "use") {
            inputs.get(Utils.iv(args[0])).using = Utils.iv(args[1]);
        } else if(msg == "inprcps") {
            int idx = Utils.iv(args[0]);
            List<MenuGrid.Pagina> rcps = new ArrayList<>();
            GameUI gui = getparent(GameUI.class);
            if((gui != null) && (gui.menu != null)) {
                for(int a = 1; a < args.length; a++)
                    rcps.add(gui.menu.paginafor(ui.sess.getres((Integer)args[a])));
            }
            inputs.get(idx).rpag = rcps;
        } else {
            super.uimsg(msg, args);
        }
    }

    public static final Coord qmodsz = UI.scale(20, 20);
    private static final WeakHashMap<Indir<Resource>, Tex> qmicons = new WeakHashMap<>();
    private Tex qmicon(Indir<Resource> qm) {
        synchronized (qmicons) {
            return qmicons.computeIfAbsent(qm, NMakewindow.this::buildQTex);
        }
    }

    public void draw(GOut g) {
        Coord c = new Coord(xoff, 0);
        boolean popt = false;
        for(Spec s : inputs) {
            boolean opt = s.opt();
            if(opt != popt)
                c = c.add(10, 0);
            GOut sg = g.reclip(c, invsq.sz());
            if(opt) {
                sg.chcolor(0, 255, 0, 255);
                sg.image(invsq, Coord.z);
                sg.chcolor();
            } else {
                sg.image(invsq, Coord.z);
            }
            s.draw(sg);
            if(!autoMode && !opt && (s.count > 0) && (s.using < s.count)) {
                sg.chcolor(255, 0, 0, 64);
                sg.frect2(Coord.of(0, (invsq.sz().y * s.using) / s.count), invsq.sz());
                sg.chcolor();
            }
            c = c.add(Inventory.sqsz.x, 0);
            popt = opt;
            if(autoMode)
            {
                if(s.logisticin)
                {
                    sg.image(aready, Coord.z);
                }
                else
                {
                    if(s.categories)
                    {
                        if(s.ing==null)
                            sg.image(categories, Coord.z);
                        else
                        {
                            if(s.ing.isIgnored)
                            {
                                sg.image(ignoreOverlay, Coord.z);
                            }
                            else if(s.ing.logistic)
                            {
                                sg.image(aready, Coord.z);
                            }
                            else if(inStorage(s.ing.name))
                            {
                                sg.image(aready, Coord.z);
                                sg.image(storageBadge, Coord.z);
                            }
                            else
                            {
                                sg.image(anotfound, Coord.z);
                            }
                        }
                    }
                    else if(inStorage(s.name))
                    {
                        sg.image(aready, Coord.z);
                        sg.image(storageBadge, Coord.z);
                    }
                    else
                    {
                        sg.image(anotfound, Coord.z);
                    }
                }
            }
        }
        {
            int x = 0;
            if(!qmod.isEmpty()) {
                x += getQmodl().sz().x + UI.scale(5);
                x = Math.max(x, xoff);
                qmx = x;
                int count = 0;
                double product = 1.0;
                CharWnd chr = (ui != null && ui.gui != null) ? ui.gui.chrwdg : null;
                for(Indir<Resource> qm : qmod) {
                    try {
                        Tex t = buildQTex(qm);
                        g.image(t, new Coord(x, qmy));
                        x += t.sz().x + UI.scale(1);

                        if(chr != null && chr.battr != null) {
                            for(BAttrWnd.Attr attr: chr.battr.attrs)
                            {
                                if(attr.attr.nm.equals(qm.get().basename()))
                                {
                                    count++;
                                    product = product * attr.attr.comp;

                                    BufferedImage texVal = fnd2.render(String.valueOf(attr.attr.comp)).img;
                                    g.image(texVal,new Coord(x, qmy + UI.scale(1)));
                                    x += texVal.getWidth() + UI.scale(1);
                                    break;
                                }
                            }
                        }
                        if(chr != null && chr.sattr != null) {
                            for(SAttrWnd.SAttr attr: chr.sattr.attrs)
                            {
                                if(attr.attr.nm.equals(qm.get().basename()))
                                {
                                    count++;
                                    product = product * attr.attr.comp;
                                    BufferedImage texVal = fnd2.render(String.valueOf(attr.attr.comp)).img;
                                    g.image(texVal,new Coord(x, qmy + UI.scale(1)));
                                    x += texVal.getWidth() + UI.scale(1);
                                    break;
                                }
                            }
                        }
                    } catch(Loading l) {
                    }
                }
                if(count > 0) {
                    x += drawSoftcap(g, new Coord(x, qmy), product, count);
                }
                x += UI.scale(25);

            }
            if(!tools.isEmpty()) {
                g.aimage(getTooll().tex(), new Coord(x, qmy + (qmodsz.y / 2) - UI.scale(2)), 0, 0.5);
                x += getTooll().sz().x + UI.scale(5);
                x = Math.max(x, xoff);
                toolx = x;
                for(Indir<Resource> tool : tools) {
                    try {
                        Tex t = qmicon(tool);
                        g.image(t, new Coord(x, qmy));
                        x += t.sz().x + UI.scale(1);
                    } catch(Loading l) {
                    }
                }
                x += UI.scale(25);
            }
        }
        c = new Coord(xoff, outy);
        for(Spec s : outputs) {
            GOut sg = g.reclip(c, invsq.sz());
            sg.image(invsq, Coord.z);
            s.draw(sg);
            c = c.add(Inventory.sqsz.x, 0);
            if(autoMode)
            {
                if(s.logisticout)
                {
                    sg.image(aready, Coord.z);
                }
                else
                {
                    if(s.categories)
                    {
                        if(s.ing==null)
                            sg.image(categories, Coord.z);
                        else
                        {
                            if(s.ing.isIgnored)
                            {
                                sg.image(ignoreOverlay, Coord.z);
                            }
                            else if(s.ing.logistic)
                            {
                                sg.image(aready, Coord.z);
                            }
                            else
                            {
                                sg.image(anotfound, Coord.z);
                            }
                        }
                    }
                    else
                    {
                        sg.image(anotfound, Coord.z);
                    }
                }
            }
        }
        if(autoMode) {
            // The Autocraft panel sits between square 1 px rules, like the rest of nurgling
            g.chcolor(nurgling.widgets.cookbook.CookbookTheme.line);
            g.frect(Coord.of(0, panelTop), Coord.of(sz.x, UI.scale(1)));
            g.frect(Coord.of(0, panelBottom), Coord.of(sz.x, UI.scale(1)));
            g.chcolor();
        }
        super.draw(g);
    }

    private int drawSoftcap(GOut g, Coord p, double product, int count) {
        if(count > 0) {
            double current = Math.pow(product, 1.0 / count);
            if (current != softcap || softTex == null) {
                softcap = current;
                String format = String.format("%.1f", softcap);
                if (softTex != null) {
                    softTex.dispose();
                }
                softTexLabel = new TexI(fnd.render(L10n.get("craft.softcap")).img);
                softTex = new TexI(fnd2.render(format).img);
            }
            g.image(softTexLabel, p.add(UI.scale(5), UI.scale(-2)));
            g.image(softTex, p.add(UI.scale(5) + softTexLabel.sz().x + UI.scale(3), UI.scale(1)));
            return softTex.sz().x + softTexLabel.sz().x + UI.scale(9);
        }
        return 0;
    }

    private Tex buildQTex(Indir<Resource> res) {
        BufferedImage result = PUtils.convolve(res.get().layer(Resource.imgc).img, qmodsz, CharWnd.iconfilter);
        try {
//            Glob.CAttr attr = NUtils.getGameUI().chrwdg.findattr(res.get().basename());
//            if(attr != null) {
//                result = ItemInfo.catimgsh(1, result, attr.compline().img);
//            }
        } catch (Exception ignored) {
        }
        return new TexI(result);
    }

    public static void invalidate(String name) {
        synchronized (qmicons) {
            LinkedList<Indir<Resource>> tmp = new LinkedList<>(qmicons.keySet());
            tmp.forEach(res -> {
                if(name.equals(res.get().basename())) {
                    qmicons.remove(res);
                }
            });
        }
    }

    private int qmx, toolx;
    private long hoverstart;
    private Spec lasttip;
    private Indir<Object> stip, ltip;
    public Object tooltip(Coord mc, Widget prev) {
        String name = null;
        Spec tspec = null;
        Coord c;
        if(!qmod.isEmpty()) {
            c = new Coord(qmx, qmy);
            try {
                for(Indir<Resource> qm : qmod) {
                    Tex t = qmicon(qm);
                    Coord sz = t.sz();
                    if(mc.isect(c, sz))
                        return(qm.get().layer(Resource.tooltip).text());
                    c = c.add(sz.x + UI.scale(1), 0);
                }
            } catch(Loading l) {
            }
        }
        if(!tools.isEmpty()) {
            c = new Coord(toolx, qmy);
            try {
                for(Indir<Resource> tool : tools) {
                    Coord tsz = qmicon(tool).sz();
                    if(mc.isect(c, tsz))
                        return(tool.get().layer(Resource.tooltip).text());
                    c = c.add(tsz.x + UI.scale(1), 0);
                }
            } catch(Loading l) {
            }
        }
        find: {
            c = new Coord(xoff, 0);
            boolean popt = false;
            for(Spec s : inputs) {
                boolean opt = s.opt();
                if(opt != popt)
                    c = c.add(UI.scale(10), 0);
                if(mc.isect(c, Inventory.invsq.sz())) {
                    name = getDynamicName(s.spr);
                    if(name == null || name.contains("Raw")){
                        tspec = s;
                    }
                    break find;
                }
                c = c.add(Inventory.sqsz.x, 0);
                popt = opt;
            }
            c = new Coord(xoff, outy);
            for(Spec s : outputs) {
                if(mc.isect(c, invsq.sz())) {
                    tspec = s;
                    break find;
                }
                c = c.add(Inventory.sqsz.x, 0);
            }
        }
        if(lasttip != tspec) {
            lasttip = tspec;
            stip = ltip = null;
        }
        if(tspec == null)
            return(super.tooltip(mc, prev));
        long now = System.currentTimeMillis();
        boolean sh = true;
        if(prev != this)
            hoverstart = now;
        else if(now - hoverstart > 1000)
            sh = false;
        if(sh) {
            if(stip == null) {
                BufferedImage tip = withSource(tspec, tspec.shorttip());
                if(tip == null) {
                    stip = () -> null;
                } else {
                    Tex tt = new TexI(tip);
                    stip = () -> tt;
                }
            }
            return(stip);
        } else {
            if(ltip == null) {
                BufferedImage tip = withSource(tspec, tspec.longtip());
                if(tip == null) {
                    ltip = () -> null;
                } else {
                    Tex tt = new TexI(tip);
                    ltip = () -> tt;
                }
            }
            return(ltip);
        }
    }

    /** In Autocraft, append where an input will be fetched from to its tooltip. */
    private BufferedImage withSource(Spec s, BufferedImage tip) {
        if(!autoMode || !inputs.contains(s))
            return tip;
        String name;
        if(s.ing != null)
            name = s.ing.isIgnored ? null : s.ing.name;
        else
            name = s.categories ? null : s.name;
        if(name == null)
            return tip;
        String line;
        NArea area = NContext.findIn(name);
        if(area != null)
            line = L10n.get("craft.src_area", area.name);
        else if(inStorage(name))
            line = L10n.get("craft.src_storage", storageCounts.getOrDefault(name, 0));
        else
            line = L10n.get("craft.src_missing");
        BufferedImage img = Text.render(line).img;
        return (tip == null) ? img : ItemInfo.catimgs(UI.scale(2), tip, img);
    }

    private static String getDynamicName(GSprite spr) {
        if(spr != null) {
            if(spr instanceof ItemInfo.Name.Dynamic)
            {
                return ((ItemInfo.Name.Dynamic)spr).name();
            }
        }
        return null;
    }

    public static Class[] interfaces(Class c) {
        try {
            return c.getInterfaces();
        } catch (Exception ignored) {}
        return new Class[0];
    }

    public static boolean hasInterface(String name, Class c) {
        Class[] interfaces = interfaces(c);
        for (Class in : interfaces) {
            if(in.getCanonicalName().equals(name)) {return true; }
        }
        return false;
    }

    public boolean globtype(GlobKeyEvent ev) {
        if(ev.c == '\n') {
            wdgmsg("make", ui.modctrl?1:0);
            return(true);
        }
        return(super.globtype(ev));
    }

    /**
     * Current text of the target-quantity field, or "" if unavailable. Used to
     * carry the requested craft count across a recipe re-open (which builds a fresh
     * widget with an empty field).
     */
    public String getCraftCount()
    {
        return (craft_num != null) ? craft_num.text() : "";
    }

    /**
     * Restore the target-quantity field text (see {@link #getCraftCount()}).
     */
    public void setCraftCount(String value)
    {
        if (craft_num != null && value != null)
            craft_num.settext(value);
    }

    void craft()
    {
        if(!autoMode)
            wdgmsg("make", 0);
        else
        {
            final NGameUI gui = NUtils.getGameUI();
            if (gui == null) return;

            int num = 1;
            try
            {
                String cand = craft_num.text();
                if(!cand.isEmpty())
                    num = Integer.parseInt(cand);
            }
            catch (NumberFormatException e)
            {
                gui.error("Incorrect target num");
            }
            Craft bot = new Craft(NMakewindow.this, num);
            bot.useStorage = storageActive();
            bot.village = nurgling.widgets.storage.VillageStorage.snapshot(gui);
            BotExecutor.runAsync("Auto craft(BOT)", bot);
        }
    }

    void craftAll()
    {
        if(!autoMode)
        {
            wdgmsg("make", 1);
        }
        else
        {
            Craft bot = new Craft(NMakewindow.this, 9999);
            bot.useStorage = storageActive();
            bot.village = nurgling.widgets.storage.VillageStorage.snapshot(NUtils.getGameUI());
            BotExecutor.runAsync("Auto craft(BOT)", bot);
        }
    }



    public static class Optional extends ItemInfo.Tip {
        public static Text text = null;
        private static Text getText() {
            if (text == null) text = RichText.render(String.format("$i{%s}", L10n.get("craft.optional")), 0);
            return text;
        }
        public Optional(Owner owner) {
            super(owner);
        }

        public BufferedImage tipimg() {
            return(getText().img);
        }

        public Tip shortvar() {return(this);}
    }

    public static class MakePrep extends ItemInfo implements GItem.ColorInfo {
        private final static Color olcol = new Color(0, 255, 0, 64);
        public MakePrep(Owner owner) {
            super(owner);
        }

        public Color olcol() {
            return(olcol);
        }
    }

    Categories cat = null;

    public class Ingredient{
        public BufferedImage img;
        public String name;
        boolean logistic;
        public boolean isIgnored = false;

        public Ingredient(JSONObject obj)
        {
            img = ItemTex.create(obj);
            name = (String) obj.get("name");
        }

        public Ingredient(BufferedImage img, String name, boolean isIgnored)
        {
            this.img = img;
            this.name = name;
            this.isIgnored = isIgnored;
        }

        void tick(double dt)
        {
            if (!isIgnored) {
                logistic = (NContext.findIn(name) != null);
            }
        }
    }

    final static Coord catoff = UI.scale(8,8);
    final static Coord catend = UI.scale(15,15);
    static final int width = 9;
    
    private static Coord calculateSize(int totalSize) {
        return new Coord(
            Math.max((Inventory.sqsz.x+UI.scale(1))*((totalSize/width>=1)?width:0),
                    (Inventory.sqsz.x+UI.scale(1))*(totalSize%width))- UI.scale(2),
            (Inventory.sqsz.x+UI.scale(1))*(totalSize/width+(totalSize%width!=0?1:0))
        ).add(UI.scale(20,18));
    }
    
    public class Categories extends Widget
    {

        Color bg = new Color(30,40,40,160);
        ArrayList<Ingredient> data = new ArrayList<>();
        Frame fr;


        Spec s;
        boolean isOptional;
        
        public Categories(ArrayList<JSONObject> objs, Spec s, boolean isOptional)
        {
            super(calculateSize(objs.size() + (isOptional ? 1 : 0)));
            this.s = s;
            this.isOptional = isOptional;
            add(fr = new Frame(sz.sub(catend),true));
            
            // Add "ignore" option first if this is optional
            if (isOptional) {
                try {
                    BufferedImage ignoreImg = Resource.loadsimg("nurgling/hud/autocraft/ignore");
                    data.add(new Ingredient(ignoreImg, L10n.get("craft.ignore_ingredient"), true));
                } catch (Exception e) {
                    System.out.println("Failed to load ignore resource: " + e.getMessage());
                }
            }
            
            for(JSONObject obj: objs)
            {
                data.add(new Ingredient(obj));
            }
            setfocustab(true);
            autofocus =true;
        }

        @Override
        public void draw(GOut g)
        {
            super.draw(g);
            g.chcolor(bg);
            g.frect(UI.scale(4,4), fr.inner());
            Coord pos = new Coord(catoff);
            Coord shift = new Coord(0,0);
            for(Ingredient ing: data)
            {
                GOut sg = g.reclip(pos, invsq.sz());
                sg.image(new TexI(ing.img), Coord.z,UI.scale(32,32));
                if(ing.isIgnored)
                {
                    sg.image(ignoreOverlay, Coord.z,UI.scale(32,32));
                }
                else if(ing.logistic)
                {
                    sg.image(aready, Coord.z,UI.scale(32,32));
                }
                else if(inStorage(ing.name))
                {
                    sg.image(aready, Coord.z,UI.scale(32,32));
                    sg.image(storageBadge, Coord.z,UI.scale(32,32));
                }
                else
                {
                    sg.image(anotfound, Coord.z,UI.scale(32,32));
                }
                if(shift.x<width-1)
                {
                    pos = pos.add(Inventory.sqsz.x + UI.scale(1), 0);
                    shift.x+=1;
                }
                else
                {
                    pos.x = UI.scale(8);
                    shift.x = 0;
                    pos = pos.add(0, Inventory.sqsz.y + UI.scale(1));
                }
            }

        }
        UI.Grab mg;
        @Override
        protected void added()
        {
            mg = NUtils.getUI().grabmouse(this);
        }

        @Override
        public void remove()
        {
            mg.remove();
            super.remove();
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            Coord pos = new Coord(catoff);
            if(!ev.c.isect(pos, sz.sub(catend)))
            {
                destroy();
                cat = null;
                return true;
            }
            else
            {
                Coord shift = new Coord(0,0);
                for(Ingredient ing: data)
                {
                    if(ev.c.isect(pos, invsq.sz()))
                    {
                        s.ing = ing;
                        destroy();
                        cat = null;
                        return true;
                    }
                    if(shift.x<width-1)
                    {
                        pos = pos.add(Inventory.sqsz.x + UI.scale(1), 0);
                        shift.x+=1;
                    }
                    else
                    {
                        pos.x = UI.scale(8);
                        pos = pos.add(0, Inventory.sqsz.y + UI.scale(1));
                        shift.x = 0;
                    }
                }
                return true;
            }
        }


        @Override
        public void tick(double dt)
        {
            for(Ingredient ing: data)
            {
                ing.tick(dt);
            }
            super.tick(dt);
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            Coord pos = new Coord(catoff);
            if(!c.isect(pos, sz.sub(catend)))
            {
                return null;
            }
            else
            {
                Coord shift = new Coord(0,0);
                for(Ingredient ing: data)
                {
                    if(c.isect(pos, invsq.sz()))
                    {
                        return ing.name;
                    }
                    if(shift.x<width-1)
                    {
                        pos = pos.add(Inventory.sqsz.x + UI.scale(1), 0);
                        shift.x+=1;
                    }
                    else
                    {
                        pos.x = UI.scale(8);
                        pos = pos.add(0, Inventory.sqsz.y + UI.scale(1));
                        shift.x = 0;
                    }
                }
                return true;
            }
        }
    }
}
