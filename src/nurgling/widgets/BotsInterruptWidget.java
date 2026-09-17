package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NInventory;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.i18n.L10n;
import nurgling.tasks.NTask;
import haven.res.ui.croster.Entry;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bot Status strip, hosted in the "botstatus" NDraggableWidget. One meter-style bar per running bot:
 * the meters' gold frame with a spinning gear in the badge. A collapsed bar shows icon, name, run time
 * and stop; clicking it expands that bar to add the action breadcrumb and the NTask the bot waits on.
 * Right-clicking opens a detail window with the raw action frames.
 */
public class BotsInterruptWidget extends Widget {
    public static final String DRAG_NAME = "botstatus";
    public static final KeyBinding kb_interrupt_bots = KeyBinding.get("interrupt-bots", KeyMatch.nil);

    boolean oldStackState = false;

    /** Per-session flag: true when this session has bots running.
     *  Used to suppress auto-petal selection (NFlowerMenu) and gate AutoDrink. */
    public final java.util.concurrent.atomic.AtomicBoolean waitBot = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Stack trace writing for autorunner debugging
    private static String autorunnerStackTraceFile = null;
    private static long lastStackTraceWrite = 0;
    private static final long STACK_TRACE_WRITE_INTERVAL = 2000; // 2 seconds

    private static final int MAX_BOTS = 9;
    // Thread.getStackTrace() stops the thread at a safepoint, so sample a few times a second, not per frame.
    private static final long SAMPLE_MS = 250;
    private static final String CRUMB_SEP = " › ";

    // Bar geometry, matching the IMeter bars exactly (155x30 frame, 30px badge at scale 1).
    private static final int COLLAPSED_W = UI.scale(155);
    private static final int EXPANDED_W = UI.scale(300);
    private static final int PAD = UI.scale(7);
    private static final int LINE = UI.scale(14);
    private static final int COLLAPSED_H = UI.scale(30);
    private static final int EXPANDED_H = 2 * PAD + 3 * LINE;
    private static final int BADGE = UI.scale(30);
    private static final int FRAME_X = UI.scale(15);
    private static final int ICON = UI.scale(16);
    private static final int GAP = UI.scale(4);
    private static final int ROW_GAP = UI.scale(2);
    private static final int ICON_X = FRAME_X + UI.scale(18);
    private static final int TEXT_X = ICON_X + ICON + GAP;

    static final Text.Foundry titlef = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 12, Color.WHITE).aa(true);
    static final Text.Foundry linef = new Text.Foundry(Text.sans, 11, NStyle.questDim).aa(true);
    static final Text.Foundry waitf = new Text.Foundry(Text.sans, 11, NStyle.border).aa(true);
    private static final Color FRAME_DARK = new Color(94, 75, 26);
    private static final Color FRAME_GOLD = new Color(255, 198, 94);

    private static final Tex[] gears = scaledGears(UI.scale(22));
    private static final Tex badge = badge();
    private static final Frame frame = Frame.load("nurgling/hud/meter/stam");
    private static final BufferedImage[] stopi = scaled(NStyle.crossSquare, ICON);

    final ArrayList<Gear> obs = new ArrayList<>();
    final ArrayList<Thread> stackObs = new ArrayList<>();

    private static Tex[] scaledGears(int size) {
        Tex[] res = new Tex[NStyle.gear.length];
        for (int i = 0; i < res.length; i++)
            res[i] = new TexI(PUtils.convolvedown(Resource.loadsimg("nurgling/hud/gear/" + i), new Coord(size, size), CharWnd.iconfilter));
        return res;
    }

    private static BufferedImage[] scaled(TexI[] src, int size) {
        BufferedImage[] res = new BufferedImage[src.length];
        for (int i = 0; i < src.length; i++)
            res[i] = PUtils.convolvedown(src[i].back, new Coord(size, size), CharWnd.iconfilter);
        return res;
    }

    /** The round badge the meters carry their icon in, empty: dark / gold / dark rings around a teal disc. */
    private static Tex badge() {
        BufferedImage img = new BufferedImage(BADGE, BADGE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int ring = Math.max(1, UI.scale(1));
        g.setColor(FRAME_DARK);
        g.fillOval(0, 0, BADGE, BADGE);
        g.setColor(FRAME_GOLD);
        g.fillOval(ring, ring, BADGE - 2 * ring, BADGE - 2 * ring);
        g.setColor(FRAME_DARK);
        g.fillOval(4 * ring, 4 * ring, BADGE - 8 * ring, BADGE - 8 * ring);
        g.setColor(NStyle.rowEven);
        g.fillOval(5 * ring, 5 * ring, BADGE - 10 * ring, BADGE - 10 * ring);
        g.dispose();
        return new TexI(img);
    }

    /** The meter frame cut into nine slices so it stretches to any bar size; the left cap is the right cap mirrored. */
    private static class Frame {
        final Tex tl, t, tr, l, r, bl, b, br;
        final int capw, top, bottom;

        private Frame(BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            double f = w / 155.0;
            capw = (int) Math.round(10 * f);
            top = (int) Math.round(8 * f);
            bottom = (int) Math.round(8 * f);
            int midh = h - top - bottom, cx = w - capw, mx = w / 2;
            tr = slice(img, cx, 0, capw, top, false);
            r = slice(img, cx, top, capw, midh, false);
            br = slice(img, cx, h - bottom, capw, bottom, false);
            tl = slice(img, cx, 0, capw, top, true);
            l = slice(img, cx, top, capw, midh, true);
            bl = slice(img, cx, h - bottom, capw, bottom, true);
            t = slice(img, mx, 0, 1, top, false);
            b = slice(img, mx, h - bottom, 1, bottom, false);
        }

        static Frame load(String res) {
            BufferedImage img = Resource.loadsimg(res);
            // Headless sessions get a dummy image too small to slice; they never draw anyway.
            return img.getWidth() < 100 ? null : new Frame(img);
        }

        /** A standalone copy of the region: TexI of a getSubimage view would upload the whole parent raster. */
        private static Tex slice(BufferedImage src, int x, int y, int w, int h, boolean mirror) {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            if (mirror)
                g.drawImage(src, w, 0, 0, h, x, y, x + w, y + h, null);
            else
                g.drawImage(src, 0, 0, w, h, x, y, x + w, y + h, null);
            g.dispose();
            return new TexI(out);
        }

        void draw(GOut g, Coord ul, Coord sz) {
            int midw = sz.x - 2 * capw, midh = sz.y - top - bottom;
            int rx = ul.x + sz.x - capw, by = ul.y + sz.y - bottom;
            g.image(tl, ul);
            g.image(t, new Coord(ul.x + capw, ul.y), new Coord(midw, top));
            g.image(tr, new Coord(rx, ul.y));
            g.image(l, new Coord(ul.x, ul.y + top), new Coord(capw, midh));
            g.image(r, new Coord(rx, ul.y + top), new Coord(capw, midh));
            g.image(bl, new Coord(ul.x, by));
            g.image(b, new Coord(ul.x + capw, by), new Coord(midw, bottom));
            g.image(br, new Coord(rx, by));
        }
    }

    /** The first nurgling.actions frame, i.e. the innermost action the thread is in. */
    public static String currentAction(Thread t) {
        List<StackTraceElement> frames = actionFrames(t.getStackTrace());
        return frames.isEmpty() ? null : frames.get(0).toString();
    }

    /** nurgling.actions frames, innermost first. */
    static List<StackTraceElement> actionFrames(StackTraceElement[] stack) {
        List<StackTraceElement> res = new ArrayList<>();
        for (StackTraceElement e : stack) {
            if (e.getClassName().startsWith("nurgling.actions."))
                res.add(e);
        }
        return res;
    }

    /** "nurgling.actions.bots.Forager$2" -> "Forager". */
    static String simpleName(String className) {
        String s = className.substring(className.lastIndexOf('.') + 1);
        int d = s.indexOf('$');
        return d > 0 ? s.substring(0, d) : s;
    }

    /** Action class names, outermost first, with consecutive repeats collapsed. */
    static List<String> breadcrumb(List<StackTraceElement> frames) {
        List<String> res = new ArrayList<>();
        for (int i = frames.size() - 1; i >= 0; i--) {
            String n = simpleName(frames.get(i).getClassName());
            if (res.isEmpty() || !res.get(res.size() - 1).equals(n))
                res.add(n);
        }
        return res;
    }

    /** Joins the breadcrumb, dropping middle items and then characters until it fits w pixels. */
    static String fit(List<String> parts, Text.Foundry f, int w) {
        List<String> p = new ArrayList<>(parts);
        String s = String.join(CRUMB_SEP, p);
        while (f.strsize(s).x > w && p.size() > 3) {
            p.remove(p.get(1).equals("…") ? 2 : 1);
            if (!p.get(1).equals("…"))
                p.add(1, "…");
            s = String.join(CRUMB_SEP, p);
        }
        while (f.strsize(s).x > w && s.length() > 1)
            s = s.substring(0, s.length() - 2) + "…";
        return s;
    }

    static String taskName(NTask task) {
        Class<?> c = task.getClass();
        if (c.isAnonymousClass())
            return simpleName(c.getName()) + " task";
        return c.getSimpleName();
    }

    static String elapsed(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    /** Re-renders only when the string changed, disposing the old texture. */
    static Text rerender(Text old, String s, Text.Foundry f) {
        if (old != null && old.text.equals(s))
            return old;
        if (old != null)
            old.dispose();
        return s == null ? null : f.render(s);
    }

    private static BotDescriptor descriptorFor(String threadName) {
        String n = threadName;
        for (String suffix : new String[]{"-ToggleThread", "-RecentAction"}) {
            if (n.endsWith(suffix))
                n = n.substring(0, n.length() - suffix.length());
        }
        for (BotDescriptor d : BotRegistry.all()) {
            if (n.equals(d.iconPath) || n.equals(d.id) || n.equals(d.getDisplayName()))
                return d;
        }
        return null;
    }

    public class Gear extends Widget {
        final Thread t;
        final String title;
        final long started = System.currentTimeMillis();
        private final Tex icon;
        private final IButton stopb;
        private boolean expanded = false;
        private long lastSample = 0;

        List<StackTraceElement> frames = new ArrayList<>();
        List<String> crumb = new ArrayList<>();
        String waiting = null;
        private Text titlet, timet, crumbt, waitt;
        BotDetailWnd detail = null;

        public Gear(Thread t) {
            super();
            assert t != null;
            this.t = t;
            BotDescriptor d = descriptorFor(t.getName());
            title = d != null ? d.getDisplayName() : t.getName();
            icon = d != null ? botIcon(d) : null;
            stopb = add(new IButton(stopi[0], stopi[1], stopi[2]) {
                @Override
                public void click() {
                    removeObserve(Gear.this.t);
                }
            });
            stopb.settip(L10n.get("botstatus.stop", title));
            layout();
        }

        private Tex botIcon(BotDescriptor d) {
            try {
                return new TexI(PUtils.convolvedown(Resource.loadsimg("nurgling/bots/icons/" + d.iconPath + "/u"), new Coord(ICON, ICON), CharWnd.iconfilter));
            } catch (Resource.NoSuchResourceException e) {
                return null;
            }
        }

        void layout() {
            resize(expanded ? new Coord(EXPANDED_W, EXPANDED_H) : new Coord(COLLAPSED_W, COLLAPSED_H));
            stopb.move(new Coord(sz.x - UI.scale(6) - ICON, PAD));
        }

        /** Room for text between the icon and the stop button. */
        private int textW() {
            return stopb.c.x - GAP - TEXT_X;
        }

        void sample() {
            frames = actionFrames(t.getStackTrace());
            crumb = breadcrumb(frames);
            NTask task = (ui != null && ui.core != null) ? ui.core.waitingTask(t) : null;
            long waited = task == null ? 0 : (System.currentTimeMillis() - task.waitSince) / 1000;
            // Most waits last a few ticks; only show the ones long enough to explain a stall.
            waiting = waited < 1 ? null : L10n.get("botstatus.waiting", taskName(task), waited);
        }

        String crumbText(Text.Foundry f, int w) {
            return crumb.isEmpty() ? L10n.get("botstatus.no_action") : fit(crumb, f, w);
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            long now = System.currentTimeMillis();
            if ((expanded || detail != null) && now - lastSample >= SAMPLE_MS) {
                lastSample = now;
                sample();
            }
            timet = rerender(timet, elapsed(now - started), linef);
            titlet = rerender(titlet, fit(List.of(title), titlef, textW() - timet.sz().x - GAP), titlef);
            if (expanded) {
                crumbt = rerender(crumbt, crumbText(linef, textW()), linef);
                waitt = rerender(waitt, waiting, waitf);
            }
        }

        @Override
        public void draw(GOut g) {
            Coord fsz = new Coord(sz.x - FRAME_X, sz.y);
            int inset = UI.scale(4);
            g.chcolor(ui.mc.isect(rootpos(), sz) ? NStyle.rowOdd : NStyle.rowEven);
            g.frect(new Coord(FRAME_X + inset, inset), fsz.sub(2 * inset, 2 * inset));
            g.chcolor();
            if (frame != null)
                frame.draw(g, new Coord(FRAME_X, 0), fsz);

            int by = (sz.y - BADGE) / 2;
            g.image(badge, new Coord(0, by));
            Tex gear = gears[(int) (NUtils.getTickId() / 5) % gears.length];
            g.image(gear, new Coord((BADGE - gear.sz().x) / 2, by + (BADGE - gear.sz().y) / 2));

            if (icon != null)
                g.image(icon, new Coord(ICON_X, PAD));
            int ty = expanded ? PAD : (sz.y - LINE) / 2;
            if (titlet != null)
                g.image(titlet.tex(), new Coord(TEXT_X, ty));
            if (timet != null)
                g.image(timet.tex(), new Coord(stopb.c.x - GAP - timet.sz().x, ty + UI.scale(1)));
            if (expanded) {
                if (crumbt != null)
                    g.image(crumbt.tex(), new Coord(TEXT_X, PAD + LINE));
                if (waitt != null)
                    g.image(waitt.tex(), new Coord(TEXT_X, PAD + 2 * LINE));
            }
            super.draw(g);
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            Object tt = super.tooltip(c, prev);
            return tt != null ? tt : L10n.get("botstatus.hint");
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.propagate(this))
                return true;
            if (ev.b == 1) {
                expanded = !expanded;
                lastSample = 0;
                layout();
                repack();
            } else if (ev.b == 3) {
                openDetail();
            }
            // Bars sit over the map; never let a click fall through and walk the character.
            return true;
        }

        void openDetail() {
            if (detail == null) {
                lastSample = 0;
                GameUI gui = getparent(GameUI.class);
                if (gui != null)
                    detail = gui.add(new BotDetailWnd(this), rootpos().add(sz.x + UI.scale(10), 0));
            } else {
                detail.raise();
            }
        }

        void closeDetail() {
            if (detail != null) {
                detail.reqdestroy();
                detail = null;
            }
        }

        void stop() {
            removeObserve(t);
        }

        @Override
        public void dispose() {
            for (Text txt : new Text[]{titlet, timet, crumbt, waitt}) {
                if (txt != null)
                    txt.dispose();
            }
            if (icon != null)
                icon.dispose();
            super.dispose();
        }
    }

    private void initializeStackTraceFile() {
        // Check if running under autorunner and stackTraceFile is configured
        if (NConfig.isBotMod() && NConfig.botmod != null && NConfig.botmod.stackTraceFile != null) {
            autorunnerStackTraceFile = NConfig.botmod.stackTraceFile;
            System.out.println("Autorunner mode detected: Stack trace file = " + autorunnerStackTraceFile);
        }
    }

    public BotsInterruptWidget() {
        super(new Coord(COLLAPSED_W, COLLAPSED_H));
        initializeStackTraceFile();
    }

    public void addObserve(Thread t)
    {
        if(obs.isEmpty())
        {
            waitBot.set(true);
        }

        if(obs.size()>=MAX_BOTS)
            NUtils.getGameUI().error("Too many running bots!");
        else {
            synchronized (obs) {
                obs.add(add(new Gear(t)));
            }
        }
        repack();
    }

    public void addObserve(Thread t, boolean disStack)
    {
        if(disStack)
        {
            if(stackObs.isEmpty())
            {
                 if(((NInventory) NUtils.getGameUI().maininv).bundle.a) {
                     oldStackState = true;
                     NUtils.stackSwitch(false);
                 }
            }
            if(oldStackState)
                stackObs.add(t);
        }
        addObserve(t);
    }

    /** Stacks the bars and sizes the strip to the widest one. */
    void repack()
    {
        int y = 0, w = COLLAPSED_W;
        synchronized (obs) {
            for (Gear g : obs) {
                g.move(new Coord(0, y));
                y += g.sz.y + ROW_GAP;
                w = Math.max(w, g.sz.x);
            }
        }
        Coord nsz = new Coord(w, obs.isEmpty() ? COLLAPSED_H : y - ROW_GAP);
        if (!nsz.equals(sz)) {
            if (parent instanceof NDraggableWidget)
                parent.resize(nsz.add(NDraggableWidget.delta));
            else
                resize(nsz);
        }
    }

    public void removeObserve(Thread t)
    {
        t.interrupt();
        // Clear kill list highlight when bot stops
        Entry.killList.clear();
        synchronized (obs)
        {
            for(Gear g: obs)
            {
                if(g.t == t) {
                    if(stackObs.contains(g.t))
                    {
                        stackObs.remove(g.t);
                        if(stackObs.isEmpty() && oldStackState)
                        {
                            NUtils.stackSwitch(true);
                        }

                    }
                    g.closeDetail();
                    g.destroy();
                    obs.remove(g);
                    break;
                }
            }
        }
        repack();
        if(obs.isEmpty())
            waitBot.set(false);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);

        // Only write stack traces if running under autorunner
        if (autorunnerStackTraceFile != null &&
            System.currentTimeMillis() - lastStackTraceWrite > STACK_TRACE_WRITE_INTERVAL) {
            writeCurrentStackTrace();
            lastStackTraceWrite = System.currentTimeMillis();
        }
        boolean removed = false;
        synchronized (obs)
        {
            for(Gear g: obs)
            {
                // isInterrupted() only means the thread was ASKED to stop, not that it has - only isAlive() means "done".
                if(!g.t.isAlive())
                {
                    // Clear kill list highlight when bot stops
                    Entry.killList.clear();
                    if(stackObs.contains(g.t))
                    {
                        stackObs.remove(g.t);
                        if(stackObs.isEmpty() && oldStackState)
                        {
                            NUtils.stackSwitch(true);
                        }

                    }
                    g.closeDetail();
                    g.destroy();
                    obs.remove(g);
                    if(obs.isEmpty())
                        waitBot.set(false);
                    removed = true;
                    break;
                }
            }
        }
        if (removed)
            repack();
    }

    @Override
    public void draw(GOut g) {
        if (!obs.isEmpty())
            super.draw(g);
    }

    private void writeCurrentStackTrace() {
        try {
            // Create JSON with current stack trace information
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"timestamp\": \"").append(Instant.now().toString()).append("\",\n");

            String currentAction = null;
            String botName = null;

            synchronized (obs) {
                if (!obs.isEmpty()) {
                    Gear firstGear = obs.iterator().next();
                    botName = firstGear.t.getName();
                    currentAction = currentAction(firstGear.t);
                }
            }

            json.append("  \"botName\": \"").append(botName != null ? botName : "Unknown").append("\",\n");
            json.append("  \"currentAction\": \"").append(currentAction != null ? currentAction.replace("\"", "\\\"") : "No action found").append("\",\n");
            json.append("  \"activeBotsCount\": ").append(obs.size()).append("\n");
            json.append("}");

            // Write atomically to temp file then rename
            Path tempFile = Paths.get(autorunnerStackTraceFile + ".tmp");
            Path finalFile = Paths.get(autorunnerStackTraceFile);

            Files.write(tempFile, json.toString().getBytes(), StandardOpenOption.CREATE);
            Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            // File I/O operations failed - log but don't crash
            System.err.println("Failed to write stack trace: " + e.getMessage());
        } catch (SecurityException e) {
            // File permission issue - log but don't crash
            System.err.println("Permission denied writing stack trace: " + e.getMessage());
        }
    }

    /**
     * Check if there are any bots currently running.
     */
    public boolean hasRunningBots() {
        return !obs.isEmpty();
    }

    /**
     * Interrupt and remove all running bots.
     */
    public void interruptAll() {
        synchronized (obs) {
            for (Gear g : new ArrayList<>(obs)) {
                g.t.interrupt();
                Entry.killList.clear();
                if (stackObs.contains(g.t)) {
                    stackObs.remove(g.t);
                }
                g.closeDetail();
                g.destroy();
            }
            obs.clear();
            if (oldStackState && stackObs.isEmpty()) {
                NUtils.stackSwitch(true);
            }
        }
        waitBot.set(false);
        repack();
    }
}
