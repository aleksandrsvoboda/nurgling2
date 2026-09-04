package nurgling.widgets;

import haven.*;
import nurgling.NInventory;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.NConfig;
import haven.res.ui.croster.Entry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;

public class BotsInterruptWidget extends Widget {
    boolean oldStackState = false;

    /** Per-session flag: true when this session has bots running.
     *  Used to suppress auto-petal selection (NFlowerMenu) and gate AutoDrink. */
    public final java.util.concurrent.atomic.AtomicBoolean waitBot = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Stack trace writing for autorunner debugging
    private static String autorunnerStackTraceFile = null;
    private static long lastStackTraceWrite = 0;
    private static final long STACK_TRACE_WRITE_INTERVAL = 2000; // 2 seconds

    // Configurable hotkey: interrupt every running bot. No default key.
    public static final KeyBinding kb_interrupt_bots =
            KeyBinding.get("interrupt-bots", KeyMatch.nil, 0);

    private static final int MAX_BOTS = 9;
    private static final int GEAR_FRAMES = 15;
    // Blurred white text reads on both a dark portrait and the dim overlay.
    private static final Text.Furnace labelFoundry = NStyle.hotkey;

    /** Extract the first "actions." stack frame for a bot thread. */
    public static String currentAction(Thread t) {
        if (t == null) return null;
        for (StackTraceElement e : t.getStackTrace()) {
            String s = e.toString();
            if (s.contains("actions.")) return s;
        }
        return null;
    }

    public class Gear extends Widget {
        final Thread t;
        public IButton cancelb;
        private Coord gearSz = NStyle.gear[0].sz();

        public Gear(Thread t) {
            super();
            assert t != null;
            this.t = t;
            this.sz = gearSz;
            cancelb = add(new IButton(NStyle.canceli[0].back, NStyle.canceli[1].back, NStyle.canceli[2].back) {
                @Override
                public void click() {
                    removeObserve(Gear.this.t);
                }
            });
            // Strip the hover tooltip — the current action is rendered above the gear.
            cancelb.tooltip = null;
            layoutChildren();
        }

        /** Rescale this gear instance to fit a sub-cell; recenters the X button. */
        public void setGearSize(Coord gsz) {
            this.gearSz = gsz;
            this.sz = gsz;
            layoutChildren();
        }

        private void layoutChildren() {
            Coord cb = NStyle.canceli[0].sz();
            cancelb.move(new Coord(sz.x / 2 - cb.x / 2, sz.y / 2 - cb.y / 2));
        }

        @Override
        public void draw(GOut g) {
            int id = (int) (NUtils.getTickId() / 5) % GEAR_FRAMES;
            // Draw the gear at 90% of the cell size, centered.
            int iw = (int)Math.round(gearSz.x * 0.9);
            int ih = (int)Math.round(gearSz.y * 0.9);
            g.image(NStyle.gear[id], new Coord((gearSz.x - iw) / 2, (gearSz.y - ih) / 2), new Coord(iw, ih));
            super.draw(g);
        }
    }

    private void initializeStackTraceFile() {
        if (NConfig.isBotMod() && NConfig.botmod != null && NConfig.botmod.stackTraceFile != null) {
            autorunnerStackTraceFile = NConfig.botmod.stackTraceFile;
            System.out.println("Autorunner mode detected: Stack trace file = " + autorunnerStackTraceFile);
        }
    }

    // Vertical space reserved above the gear area for the current-action label.
    private static final int LABEL_H = UI.scale(18);
    // Horizontal padding on each side of the gear area, reserved for label width.
    private static final int LABEL_W_PAD = UI.scale(140);

    public BotsInterruptWidget() {
        // Widget covers the portrait inner area PLUS label space above it,
        // and extends horizontally on both sides so the action line has room.
        // Actual position is snapped to the portrait in tick().
        Coord p = portraitInnerSz();
        sz = new Coord(p.x + 2 * LABEL_W_PAD, p.y + LABEL_H);
        initializeStackTraceFile();
    }

    private static Coord portraitInnerSz() {
        // Portrait NDraggableWidget is UI.scale(120,108); inner drawable
        // after the lock/vis/flip chrome is UI.scale(85, 88).
        return UI.scale(new Coord(85, 88));
    }

    /** Area of this widget used by gears (centered below the label strip). */
    private Coord gearArea() {
        Coord p = portraitInnerSz();
        return new Coord(p.x, p.y);
    }

    /** Top-left of the gear area within the widget. */
    private Coord gearOrigin() {
        return new Coord(LABEL_W_PAD, LABEL_H);
    }

    public void addObserve(Thread t) {
        if (obs.isEmpty()) {
            waitBot.set(true);
        }
        if (obs.size() >= MAX_BOTS) {
            NUtils.getGameUI().error("Too many running bots!");
        } else {
            obs.add(add(new Gear(t)));
        }
        repack();
    }

    public void addObserve(Thread t, boolean disStack) {
        if (disStack) {
            if (stackObs.isEmpty()) {
                if (((NInventory) NUtils.getGameUI().maininv).bundle.a) {
                    oldStackState = true;
                    NUtils.stackSwitch(false);
                }
            }
            if (oldStackState) stackObs.add(t);
        }
        addObserve(t);
    }

    /**
     * Arrange gears in a grid that fits the widget:
     *   1 bot   -> 1x1 (full portrait)
     *   2-4     -> 2x2
     *   5-9     -> 3x3
     */
    void repack() {
        int n = obs.size();
        if (n == 0) return;
        int cells = (n <= 1) ? 1 : (n <= 4) ? 2 : 3;
        Coord gArea = gearArea();
        Coord gOrigin = gearOrigin();
        int cellW = gArea.x / cells;
        int cellH = gArea.y / cells;
        Coord gsz = new Coord(Math.min(cellW, cellH), Math.min(cellW, cellH));
        for (int i = 0; i < obs.size(); i++) {
            int row = i / cells;
            int col = i % cells;
            int x = gOrigin.x + col * cellW + (cellW - gsz.x) / 2;
            int y = gOrigin.y + row * cellH + (cellH - gsz.y) / 2;
            Gear g = obs.get(i);
            g.setGearSize(gsz);
            g.move(new Coord(x, y));
        }
    }

    public void removeObserve(Thread t) {
        t.interrupt();
        Entry.killList.clear();
        synchronized (obs) {
            for (Gear g : obs) {
                if (g.t == t) {
                    if (stackObs.contains(g.t)) {
                        stackObs.remove(g.t);
                        if (stackObs.isEmpty() && oldStackState) {
                            NUtils.stackSwitch(true);
                        }
                    }
                    g.remove();
                    obs.remove(g);
                    break;
                }
            }
        }
        repack();
        if (obs.isEmpty()) waitBot.set(false);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);

        // Snap our position/size to the current portrait, which is user-draggable.
        snapToPortrait();

        if (autorunnerStackTraceFile != null &&
                System.currentTimeMillis() - lastStackTraceWrite > STACK_TRACE_WRITE_INTERVAL) {
            writeCurrentStackTrace();
            lastStackTraceWrite = System.currentTimeMillis();
        }

        synchronized (obs) {
            boolean any = false;
            for (Gear g : new ArrayList<>(obs)) {
                if (g.t.isInterrupted() || !g.t.isAlive()) {
                    any = true;
                    Entry.killList.clear();
                    if (stackObs.contains(g.t)) {
                        stackObs.remove(g.t);
                        if (stackObs.isEmpty() && oldStackState) {
                            NUtils.stackSwitch(true);
                        }
                    }
                    g.remove();
                    obs.remove(g);
                }
            }
            if (any) {
                if (obs.isEmpty()) waitBot.set(false);
                repack();
            }
        }
    }

    private void snapToPortrait() {
        if (!(parent instanceof GameUI)) return;
        GameUI gui = (GameUI) parent;
        if (gui.portrait == null) return;
        Coord inner = portraitInnerSz();
        Coord want = new Coord(inner.x + 2 * LABEL_W_PAD, inner.y + LABEL_H);
        if (!sz.equals(want)) {
            sz = want;
            repack();
        }
        // Position so that the gear area aligns with the portrait inner
        // drawable, with label space above and padding on either side.
        Coord target = gui.portrait.c.add(NDraggableWidget.off).sub(LABEL_W_PAD, LABEL_H);
        if (!c.equals(target)) move(target);
    }

    @Override
    public void draw(GOut g) {
        if (obs.isEmpty()) return;
        super.draw(g);
    }

    /**
     * Called by NGameUI.draw() after every child widget has drawn, so the
     * label renders on top of windows, inventories, etc. Coordinates passed
     * to g.image are in GameUI (root) space.
     */
    public void drawOverlayLabel(GOut g) {
        if (obs.isEmpty()) return;
        Thread first;
        int n;
        synchronized (obs) {
            if (obs.isEmpty()) return;
            first = obs.get(0).t;
            n = obs.size();
        }
        String action = currentAction(first);
        if (action == null) action = "(running)";
        String line = (n > 1 ? "(" + n + "x) " : "") + first.getName() + ": " + action;

        Text rendered = labelFoundry.render(truncate(line, sz.x));
        Tex tex = rendered.tex();

        // Gear area center in widget-local coords -> translate to GameUI-root coords.
        int gearCenterXLocal = LABEL_W_PAD + portraitInnerSz().x / 2;
        int txLocal = gearCenterXLocal - tex.sz().x / 2;
        if (txLocal < 0) txLocal = 0;
        if (txLocal + tex.sz().x > sz.x) txLocal = Math.max(0, sz.x - tex.sz().x);
        int tyLocal = LABEL_H - tex.sz().y - UI.scale(1);
        if (tyLocal < 0) tyLocal = 0;

        Coord abs = c.add(txLocal, tyLocal);
        int padX = UI.scale(4), padY = UI.scale(2);
        // 50% transparent black background.
        g.chcolor(0, 0, 0, 128);
        g.frect(abs.sub(padX, padY),
                new Coord(tex.sz().x + 2 * padX, tex.sz().y + 2 * padY));
        g.chcolor();
        g.image(tex, abs);
    }

    private static final int MAX_LABEL_CHARS = 80;
    private static String truncate(String s, int pxWidthHint) {
        if (s == null) return "";
        if (s.length() <= MAX_LABEL_CHARS) return s;
        return s.substring(0, MAX_LABEL_CHARS - 1) + "…";
    }

    private void writeCurrentStackTrace() {
        try {
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

            Path tempFile = Paths.get(autorunnerStackTraceFile + ".tmp");
            Path finalFile = Paths.get(autorunnerStackTraceFile);
            Files.write(tempFile, json.toString().getBytes(), StandardOpenOption.CREATE);
            Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to write stack trace: " + e.getMessage());
        } catch (SecurityException e) {
            System.err.println("Permission denied writing stack trace: " + e.getMessage());
        }
    }

    public final ArrayList<Gear> obs = new ArrayList<>();
    final ArrayList<Thread> stackObs = new ArrayList<>();

    public boolean hasRunningBots() {
        return !obs.isEmpty();
    }

    public void interruptAll() {
        synchronized (obs) {
            for (Gear g : new ArrayList<>(obs)) {
                g.t.interrupt();
                Entry.killList.clear();
                if (stackObs.contains(g.t)) {
                    stackObs.remove(g.t);
                }
                g.remove();
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
