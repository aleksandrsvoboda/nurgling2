package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NStyle;
import nurgling.i18n.L10n;
import nurgling.leveling.LevelAnalysis;
import nurgling.leveling.LevelPlan;
import nurgling.leveling.LevelPlans;
import nurgling.leveling.LevelSampler;
import nurgling.widgets.cookbook.PillButton;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static haven.MCache.cmaps;

/**
 * The leveling planner: pick an area on the map and one flat height to level it to.
 *
 * <p>While this window is open the big map window draws with it instead of panning: the
 * rectangle tool adds (left drag) or removes (right drag) a box of tiles, the brush paints or
 * erases squares. Closing the window gives the map its own clicks back. The map shows each plan's outline and, against its target height, where to dig
 * and where to fill. Nothing here talks to the server.
 *
 * <p>The target follows the chosen rule (balanced, least work, a soil budget or a picked height)
 * until the plan is locked; a locked plan keeps its height while the ground is dug, and shows how
 * far the job has come.
 */
public class LevelPlanWindow extends Window {
    private static final int W = UI.scale(300);
    private static final int GAP = UI.scale(4);
    private static final int ROWH = UI.scale(16);
    /** Seconds between re-reads of the ground, so progress shows while digging. */
    private static final double RESAMPLE = 5;
    private static final int[] BRUSHES = {1, 2, 3, 5};
    private static final Color DIM = NStyle.questDim;
    private static final Color CUTC = new Color(0xeb, 0x46, 0x3c), FILLC = new Color(0x3c, 0x8c, 0xf0);
    private static final Color GOOD = new Color(122, 209, 122);

    public enum Tool {RECT, BRUSH}

    private final NGameUI gui;
    private final LevelPlans store;
    private String selId = null;
    private Tool tool = Tool.RECT;
    private int brush = 2;

    private final PlanList list;
    private final TextEntry name;
    private final Map<Object, PillButton> pills = new HashMap<>();
    private final Widget budgetRow;
    private final TextEntry budget;
    private final HSlider slider;
    private final Curve curve;
    private final Stats stats;
    private final PillButton lockBtn, unlockBtn;

    private Defer.Future<LevelSampler.Sample> pending;
    private LevelSampler.Sample sample;
    private Map<Long, long[]> sampledMasks;
    private double resampleIn = 0;
    private double sliderT = Double.NaN;
    /* What the mode tooltips were last set for, so they are not rebuilt every frame. */
    private Boolean tipsLocked = null, tipsGran = null;

    /* A drag on the map in progress. */
    private MiniMap.Location dragFrom, dragTo, lastBrush;
    private boolean dragAdd;
    private MiniMap.Location hover;

    public LevelPlanWindow(NGameUI gui) {
        super(Coord.z, L10n.get("level.title"), true);
        this.gui = gui;
        this.store = gui.levelPlans;
        int y = 0;

        list = add(new PlanList(W - UI.scale(72), 4), 0, y);
        Button nb = add(new Button(UI.scale(66), L10n.get("level.new"), false, this::newPlan), W - UI.scale(66), y);
        add(new Button(UI.scale(66), L10n.get("level.delete"), false, this::deletePlan), W - UI.scale(66), y + nb.sz.y + GAP);
        y += list.sz.y + GAP;
        name = add(new TextEntry(W, "") {
                protected void changed() {
                    LevelPlan p = selected();
                    String t = text().trim();
                    if((p != null) && !t.isEmpty() && !t.equals(p.name))
                        store.update(p.withName(t));
                }
            }, 0, y);
        y += name.sz.y + GAP * 2;

        Label tl = add(new Label(L10n.get("level.tool"), NStyle.flower), 0, y + UI.scale(4));
        Label bl0 = add(new Label(L10n.get("level.brush"), NStyle.flower), 0, 0);
        int lx = Math.max(tl.sz.x, bl0.sz.x) + GAP * 2;
        y = flow(lx, y, pill(Tool.RECT, L10n.get("level.tool_rect"), () -> tool = Tool.RECT),
                 pill(Tool.BRUSH, L10n.get("level.tool_brush"), () -> tool = Tool.BRUSH));
        bl0.c = Coord.of(0, y + UI.scale(4));
        List<PillButton> sizes = new ArrayList<>();
        for(int b : BRUSHES) {
            final int sz = b;
            sizes.add(pill(b, b + "\u00d7" + b, () -> {brush = sz; tool = Tool.BRUSH;}));
        }
        y = flow(lx, y, sizes.toArray(new PillButton[0]));
        Img hint = add(new Img(NStyle.flower.renderwrap(L10n.get("level.draw_hint"), DIM, W).tex()), 0, y);
        y += hint.sz.y + GAP * 2;

        add(new Label(L10n.get("level.height"), NStyle.flower), 0, y);
        y += ROWH + GAP;
        y = flow(0, y, pill(LevelPlan.Mode.BALANCED, L10n.get("level.mode_balanced"), () -> setMode(LevelPlan.Mode.BALANCED)),
                 pill(LevelPlan.Mode.LEAST_WORK, L10n.get("level.mode_least"), () -> setMode(LevelPlan.Mode.LEAST_WORK)),
                 pill(LevelPlan.Mode.BUDGET, L10n.get("level.mode_budget"), () -> setMode(LevelPlan.Mode.BUDGET)),
                 pill(LevelPlan.Mode.CUSTOM, L10n.get("level.mode_custom"), () -> setMode(LevelPlan.Mode.CUSTOM)));
        pills.get(LevelPlan.Mode.BALANCED).settip(L10n.get("level.mode_balanced_tip"));
        pills.get(LevelPlan.Mode.LEAST_WORK).settip(L10n.get("level.mode_least_tip"));
        pills.get(LevelPlan.Mode.BUDGET).settip(L10n.get("level.mode_budget_tip"));
        pills.get(LevelPlan.Mode.CUSTOM).settip(L10n.get("level.mode_custom_tip"));

        budgetRow = add(new Widget(Coord.of(W, UI.scale(20))), 0, y);
        Label bl = budgetRow.add(new Label(L10n.get("level.budget"), NStyle.flower), 0, UI.scale(2));
        budget = budgetRow.add(new TextEntry(UI.scale(70), "0") {
                protected void changed() {
                    LevelPlan p = selected();
                    Integer v = parseInt(text());
                    if((p != null) && (v != null) && (v != p.budget))
                        store.update(p.withBudget(v));
                }
            }, bl.sz.x + GAP, 0);
        slider = add(new HSlider(W, 0, 1, 0) {
                public void changed() {
                    LevelPlan p = selected();
                    if((p != null) && !p.locked) {
                        sliderT = val;
                        store.update(p.withMode(LevelPlan.Mode.CUSTOM).withCustom(val).withTarget(val));
                    }
                }
            }, 0, y + UI.scale(2));
        y += UI.scale(24) + GAP;

        curve = add(new Curve(Coord.of(W, UI.scale(90))), 0, y);
        y += curve.sz.y + GAP;
        stats = add(new Stats(W), 0, y);
        y += stats.sz.y + GAP;
        /* One setting for every plan: their borders on the ground in the world. */
        Widget wb = add(new CheckBox(L10n.get("level.world_border"))
                .state(() -> !Boolean.FALSE.equals(NConfig.get(NConfig.Key.levelPlanWorldBorder)))
                .set(a -> NConfig.set(NConfig.Key.levelPlanWorldBorder, a)), 0, y);
        wb.settip(L10n.get("level.world_border_tip"));
        y += wb.sz.y + GAP;
        lockBtn = add(new PillButton(L10n.get("level.lock"), false, this::toggleLock).minWidth(W), 0, y);
        lockBtn.settip(L10n.get("level.lock_tip"));
        unlockBtn = add(new PillButton(L10n.get("level.unlock"), false, this::toggleLock).minWidth(W), 0, y);
        unlockBtn.settip(L10n.get("level.unlock_tip"));
        unlockBtn.expanded = true;
        unlockBtn.hide();
        pack();
        if(!store.plans().isEmpty())
            select(store.plans().get(0).id);
    }

    /** A row button; one that picks how the height is chosen dims while the plan is locked. */
    private class Pill extends PillButton {
        final boolean lockable;

        Pill(String label, boolean lockable, Runnable action) {
            super(label, false, action);
            this.lockable = lockable;
        }

        public void draw(GOut g) {
            super.draw(g);
            LevelPlan p = lockable ? store.get(selId) : null;
            if((p != null) && p.locked) {
                g.chcolor(28, 37, 38, 170);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
        }
    }

    private PillButton pill(Object key, String label, Runnable action) {
        PillButton b = add(new Pill(label, key instanceof LevelPlan.Mode, action));
        pills.put(key, b);
        return(b);
    }

    /** Lays buttons out left to right from {@code x0}, wrapping at the window width; returns the y below them. */
    private int flow(int x0, int y, PillButton... bs) {
        int x = x0, h = 0;
        for(PillButton b : bs) {
            if((x > x0) && (x + b.sz.x > W)) {
                x = x0;
                y += h + GAP;
            }
            b.c = Coord.of(x, y);
            x += b.sz.x + GAP;
            h = Math.max(h, b.sz.y);
        }
        return(y + h + GAP);
    }

    private static Integer parseInt(String s) {
        try {
            return(Integer.parseInt(s.trim()));
        } catch(NumberFormatException e) {
            return(null);
        }
    }

    // -------------------------------------------------------------- plans

    private LevelPlan selected() {
        LevelPlan p = store.get(selId);
        if((p == null) && !store.plans().isEmpty()) {
            p = store.plans().get(0);
            select(p.id);
        }
        return(p);
    }

    private void select(String id) {
        selId = id;
        sample = null;
        sampledMasks = null;
        resampleIn = 0;
        LevelPlan p = store.get(id);
        name.settext((p == null) ? "" : p.name);
        budget.settext((p == null) ? "0" : Integer.toString(p.budget));
    }

    private void newPlan() {
        LevelPlan p = store.create(L10n.get("level.default_name", store.plans().size() + 1));
        select(p.id);
    }

    private void deletePlan() {
        LevelPlan p = selected();
        if(p == null)
            return;
        store.delete(p.id);
        selId = null;
        LevelPlan next = selected();
        select((next == null) ? null : next.id);
    }

    private void setMode(LevelPlan.Mode m) {
        LevelPlan p = selected();
        if((p == null) || p.locked)
            return;
        if((m == LevelPlan.Mode.BUDGET) && Double.isNaN(store.gran()))
            return;
        LevelPlan np = p.withMode(m);
        if((m == LevelPlan.Mode.CUSTOM) && Double.isNaN(np.custom) && np.hasTarget())
            np = np.withCustom(Math.round(np.target));
        store.update(np);
    }

    private void toggleLock() {
        LevelPlan p = selected();
        if(p == null)
            return;
        if(p.locked) {
            store.update(p.unlock());
        } else if(p.hasTarget() && (sample != null) && !sample.analysis.empty()) {
            LevelAnalysis a = sample.analysis;
            store.update(p.lock(a.dig(p.target) + a.fill(p.target)));
        }
    }

    /** The height the plan's rule picks from the ground as it is now; NaN if it cannot. */
    private double ruleTarget(LevelPlan p, LevelAnalysis a) {
        if(a.empty())
            return(Double.NaN);
        switch(p.mode) {
            case LEAST_WORK:
                return(a.median());
            case BUDGET: {
                double g = store.gran();
                return(Double.isNaN(g) ? a.mean() : a.forNet(p.budget / g));
            }
            case CUSTOM:
                return(Double.isNaN(p.custom) ? a.mean() : p.custom);
            default:
                return(a.mean());
        }
    }

    // -------------------------------------------------------------- ticking

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(!visible())
            return;
        LevelPlan p = selected();
        list.sel = p;
        resampleIn -= dt;
        if(pending != null) {
            try {
                sample = pending.get();
                pending = null;
            } catch(Loading l) {
                /* Still reading the map file. */
            }
        }
        if((p != null) && (pending == null) && (dragFrom == null)
           && ((sampledMasks != p.masks) || (resampleIn <= 0))) {
            MapFile file = (gui.mmap == null) ? null : gui.mmap.file;
            if(file != null) {
                LevelPlan sp = p;
                sampledMasks = p.masks;
                resampleIn = RESAMPLE;
                pending = Defer.later(() -> LevelSampler.sample(file, sp));
            }
        }
        /* An unlocked plan's target follows its rule, from a sample of its current tiles. */
        if((p != null) && !p.locked && (sample != null) && (sample.plan.masks == p.masks)) {
            double t = ruleTarget(p, sample.analysis);
            if(Double.isNaN(t) != Double.isNaN(p.target) || (!Double.isNaN(t) && (Math.abs(t - p.target) > 0.01)))
                store.update(p.withTarget(t));
        }
        refresh(p);
    }

    private void refresh(LevelPlan p) {
        for(Map.Entry<Object, PillButton> e : pills.entrySet()) {
            Object k = e.getKey();
            boolean on;
            if(k instanceof Tool)
                on = (tool == k);
            else if(k instanceof Integer)
                on = (tool == Tool.BRUSH) && (brush == (Integer)k);
            else
                on = (p != null) && (p.mode == k);
            e.getValue().expanded = on;
        }
        boolean gran = !Double.isNaN(store.gran());
        boolean locked = (p != null) && p.locked;
        if(!Boolean.valueOf(locked).equals(tipsLocked) || !Boolean.valueOf(gran).equals(tipsGran)) {
            tipsLocked = locked;
            tipsGran = gran;
            pills.get(LevelPlan.Mode.BALANCED).settip(L10n.get(locked ? "level.mode_locked_tip" : "level.mode_balanced_tip"));
            pills.get(LevelPlan.Mode.LEAST_WORK).settip(L10n.get(locked ? "level.mode_locked_tip" : "level.mode_least_tip"));
            pills.get(LevelPlan.Mode.BUDGET).settip(L10n.get(locked ? "level.mode_locked_tip" : gran ? "level.mode_budget_tip" : "level.mode_budget_nogran"));
            pills.get(LevelPlan.Mode.CUSTOM).settip(L10n.get(locked ? "level.mode_locked_tip" : "level.mode_custom_tip"));
        }
        LevelAnalysis a = (sample == null) ? null : sample.analysis;
        boolean custom = (p != null) && (p.mode == LevelPlan.Mode.CUSTOM);
        boolean showSlider = custom && !locked && (a != null) && !a.empty();
        slider.show(showSlider);
        budgetRow.show((p != null) && (p.mode == LevelPlan.Mode.BUDGET));
        if(showSlider) {
            slider.min = (int)Math.floor(a.min());
            slider.max = Math.max(slider.min + 1, (int)Math.ceil(a.max()));
            /* Follow the plan only when its target moved some other way, so a drag is never fought. */
            if(p.hasTarget() && (p.target != sliderT)) {
                sliderT = p.target;
                slider.val = (int)Math.round(p.target);
            }
        }
        lockBtn.show(!locked);
        unlockBtn.show(locked);
        if(locked && p.hasTarget())
            unlockBtn.suffix(String.format("%.1f", p.target));
    }

    // -------------------------------------------------------------- map tools

    /** Whether a press on the map window should draw instead of panning or walking: while this window is open. */
    public boolean armed() {
        return(visible());
    }

    public boolean dragging() {
        return(dragFrom != null);
    }

    public boolean press(NMiniMap view, Coord c, int button) {
        if(!armed() || ((button != 1) && (button != 3)))
            return(false);
        MiniMap.Location loc = view.xlate(c);
        if(loc == null)
            return(false);
        /* Drawing with no plan yet starts one. */
        if((selected() == null) && (button == 1))
            newPlan();
        dragFrom = dragTo = loc;
        dragAdd = (button == 1);
        lastBrush = null;
        if(tool == Tool.BRUSH)
            brushTo(view, loc);
        return(true);
    }

    public void drag(NMiniMap view, Coord c) {
        MiniMap.Location loc = view.xlate(c);
        hover = loc;
        if((dragFrom == null) || (loc == null) || (loc.seg != dragFrom.seg))
            return;
        dragTo = loc;
        if(tool == Tool.BRUSH)
            brushTo(view, loc);
    }

    public void release(NMiniMap view, Coord c) {
        if(dragFrom == null)
            return;
        MiniMap.Location loc = view.xlate(c);
        if((loc != null) && (loc.seg == dragFrom.seg))
            dragTo = loc;
        if(tool == Tool.RECT) {
            Coord a = dragFrom.tc.min(dragTo.tc), b = dragFrom.tc.max(dragTo.tc);
            commit(view.file, dragFrom.seg, Area.corn(a, b.add(1, 1)));
        }
        dragFrom = dragTo = lastBrush = null;
    }

    public void hover(MiniMap.Location loc) {
        hover = loc;
    }

    /** Paint brush squares from the last one to here, so a fast stroke leaves no gaps. */
    private void brushTo(NMiniMap view, MiniMap.Location loc) {
        Coord from = (lastBrush == null) ? loc.tc : lastBrush.tc, to = loc.tc;
        int steps = Math.max(Math.abs(to.x - from.x), Math.abs(to.y - from.y));
        List<Area> squares = new ArrayList<>();
        for(int i = (lastBrush == null) ? 0 : 1; i <= steps; i++) {
            double f = (steps == 0) ? 0 : (double)i / steps;
            Coord tc = Coord.of((int)Math.round(from.x + ((to.x - from.x) * f)), (int)Math.round(from.y + ((to.y - from.y) * f)));
            squares.add(brushArea(tc));
        }
        lastBrush = loc;
        if(!squares.isEmpty())
            commit(view.file, loc.seg, squares.toArray(new Area[0]));
    }

    private Area brushArea(Coord tc) {
        Coord ul = tc.sub((brush - 1) / 2, (brush - 1) / 2);
        return(Area.sized(ul, Coord.of(brush, brush)));
    }

    /** Hand tiles of the map to the store, sorted by grid. Tiles in grids with no id are skipped. */
    private void commit(MapFile file, MapFile.Segment seg, Area... areas) {
        LevelPlan p = selected();
        if(p == null)
            return;
        Map<Long, List<Coord>> tiles = new HashMap<>();
        Map<Coord, Long> ids = new HashMap<>();
        try(Locked lk = new Locked(file.lock.readLock())) {
            for(Area area : areas) {
                Coord g0 = area.ul.div(cmaps), g1 = area.br.sub(1, 1).div(cmaps);
                for(int gy = g0.y; gy <= g1.y; gy++) {
                    for(int gx = g0.x; gx <= g1.x; gx++) {
                        Coord gc = Coord.of(gx, gy);
                        Long id = ids.computeIfAbsent(gc, k -> {
                                Long v = seg.map.get(k);
                                return((v == null) ? 0L : v);
                            });
                        if(id == 0)
                            continue;
                        Coord gul = gc.mul(cmaps);
                        Area part = Area.corn(area.ul.max(gul), area.br.min(gul.add(cmaps)));
                        List<Coord> l = tiles.computeIfAbsent(id, k -> new ArrayList<>());
                        for(Coord tc : part)
                            l.add(tc.sub(gul));
                    }
                }
            }
        }
        if(!tiles.isEmpty())
            store.paint(p.id, tiles, dragAdd);
    }

    /** The rectangle being dragged, or the brush under the pointer, on the map window. */
    public void drawTool(NMiniMap view, GOut g) {
        if(!armed())
            return;
        Color col = ((dragFrom != null) && !dragAdd) ? CUTC : NStyle.border;
        if((tool == Tool.RECT) && (dragFrom != null) && (dragTo != null)) {
            Coord a = dragFrom.tc.min(dragTo.tc), b = dragFrom.tc.max(dragTo.tc).add(1, 1);
            outline(view, g, dragFrom.seg, a, b, col);
        } else if((tool == Tool.BRUSH) && (hover != null)) {
            Area ba = brushArea(hover.tc);
            outline(view, g, hover.seg, ba.ul, ba.br, col);
        }
    }

    private static void outline(NMiniMap view, GOut g, MapFile.Segment seg, Coord ul, Coord br, Color col) {
        Coord a = view.xlate(new MiniMap.Location(seg, ul)), b = view.xlate(new MiniMap.Location(seg, br));
        if((a == null) || (b == null))
            return;
        Coord sz = b.sub(a).max(1, 1);
        int w = Math.max(1, UI.scale(1));
        g.chcolor(col);
        g.frect(a, Coord.of(sz.x, w));
        g.frect(Coord.of(a.x, b.y - w), Coord.of(sz.x, w));
        g.frect(a, Coord.of(w, sz.y));
        g.frect(Coord.of(b.x - w, a.y), Coord.of(w, sz.y));
        g.chcolor();
    }

    // -------------------------------------------------------------- window

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if(msg.equals("close")) {
            hide();
            dragFrom = dragTo = lastBrush = null;
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    /** Open or close the planner, creating it on first use. */
    public static void toggle(NGameUI gui) {
        if(gui == null || gui.levelPlans == null)
            return;
        if(gui.levelPlanWindow == null) {
            gui.levelPlanWindow = new LevelPlanWindow(gui);
            gui.add(gui.levelPlanWindow, UI.scale(Coord.of(120, 120)));
        } else if(gui.levelPlanWindow.visible()) {
            gui.levelPlanWindow.hide();
            return;
        }
        gui.levelPlanWindow.show();
        gui.levelPlanWindow.raise();
    }

    public static boolean isOpen(NGameUI gui) {
        return((gui != null) && (gui.levelPlanWindow != null) && gui.levelPlanWindow.visible());
    }

    // -------------------------------------------------------------- parts

    private class PlanList extends Listbox<LevelPlan> {
        PlanList(int w, int h) {
            super(w, h, ROWH);
        }

        protected LevelPlan listitem(int i) {return(store.plans().get(i));}

        protected int listitems() {return(store.plans().size());}

        protected void drawitem(GOut g, LevelPlan item, int i) {
            g.atext(item.name, Coord.of(UI.scale(4), ROWH / 2), 0, 0.5);
            String t = item.locked ? "● " + L10n.get("level.locked") : item.tiles() + " " + L10n.get("level.tiles");
            g.chcolor(DIM);
            g.atext(t, Coord.of(sz.x - UI.scale(16), ROWH / 2), 1, 0.5);
            g.chcolor();
        }

        public void change(LevelPlan item) {
            super.change(item);
            if(item != null)
                select(item.id);
        }

        protected void drawsel(GOut g) {
            /* Plans are replaced on every edit, so match the selection by id. */
        }

        protected void drawbg(GOut g) {
            super.drawbg(g);
        }

        public void draw(GOut g) {
            super.draw(g);
            int n = listitems();
            for(int i = 0; i < h; i++) {
                int idx = i + sb.val;
                if((idx >= 0) && (idx < n) && listitem(idx).id.equals(selId)) {
                    g.chcolor(NStyle.border);
                    g.frect(Coord.of(0, i * ROWH), Coord.of(UI.scale(2), ROWH));
                    g.chcolor();
                }
            }
        }
    }

    /** Ground to dig, to fill, and both together, against every target height; the target marked. */
    private class Curve extends Widget {
        Curve(Coord sz) {
            super(sz);
        }

        public void draw(GOut g) {
            g.chcolor(NStyle.rowEven);
            g.frect(Coord.z, sz);
            g.chcolor(NStyle.separator);
            g.frect(Coord.z, Coord.of(sz.x, 1));
            g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
            g.chcolor();
            LevelPlan p = selected();
            LevelAnalysis a = (sample == null) ? null : sample.analysis;
            if((p == null) || (a == null) || a.empty() || (a.max() <= a.min())) {
                g.chcolor(DIM);
                g.atext(L10n.get((p == null) ? "level.no_plan" : "level.no_ground"), sz.div(2), 0.5, 0.5);
                g.chcolor();
                return;
            }
            double lo = a.min(), hi = a.max();
            int n = Math.max(2, sz.x / UI.scale(3));
            double[] dig = new double[n + 1], fill = new double[n + 1];
            double top = 0;
            for(int i = 0; i <= n; i++) {
                double t = lo + ((hi - lo) * i / n);
                dig[i] = a.dig(t);
                fill[i] = a.fill(t);
                top = Math.max(top, dig[i] + fill[i]);
            }
            int pad = UI.scale(4);
            plot(g, dig, null, top, pad, CUTC);
            plot(g, fill, null, top, pad, FILLC);
            plot(g, dig, fill, top, pad, Color.WHITE);
            mark(g, a.mean(), lo, hi, DIM, "B");
            mark(g, a.median(), lo, hi, DIM, "L");
            if(p.hasTarget())
                mark(g, p.target, lo, hi, NStyle.border, null);
        }

        private int xof(double t, double lo, double hi) {
            return((int)Math.round(UI.scale(2) + ((sz.x - UI.scale(4)) * ((t - lo) / (hi - lo)))));
        }

        private void plot(GOut g, double[] a, double[] b, double top, int pad, Color col) {
            g.chcolor(col);
            int n = a.length - 1;
            Coord prev = null;
            for(int i = 0; i <= n; i++) {
                double v = a[i] + ((b == null) ? 0 : b[i]);
                Coord c = Coord.of(UI.scale(2) + ((sz.x - UI.scale(4)) * i / n),
                                   (int)Math.round((sz.y - pad) - ((sz.y - (2 * pad)) * (v / top))));
                if(prev != null)
                    g.line(prev, c, UI.scale(1.0));
                prev = c;
            }
            g.chcolor();
        }

        private void mark(GOut g, double t, double lo, double hi, Color col, String label) {
            int x = xof(t, lo, hi);
            g.chcolor(col);
            if(label == null) {
                g.frect(Coord.of(x - UI.scale(1), 0), Coord.of(UI.scale(2), sz.y));
            } else {
                for(int y = 0; y < sz.y; y += UI.scale(6))
                    g.frect(Coord.of(x, y), Coord.of(1, UI.scale(3)));
                g.atext(label, Coord.of(x + UI.scale(2), UI.scale(2)), 0, 0);
            }
            g.chcolor();
        }
    }

    /** The numbers: how much ground, which height, how far along. */
    private class Stats extends Widget {
        private static final int LINES = 7;

        Stats(int w) {
            super(Coord.of(w, ROWH * LINES));
        }

        public void draw(GOut g) {
            LevelPlan p = selected();
            if(p == null)
                return;
            LevelAnalysis a = (sample == null) ? null : sample.analysis;
            double gran = store.gran();
            boolean soil = !Double.isNaN(gran);
            int y = 0;
            int col2 = sz.x / 2;
            if((a == null) || a.empty()) {
                row(g, y, L10n.get("level.stat_tiles"), (sample == null) ? "…" : Integer.toString(sample.tiles), null);
                if((sample != null) && (sample.unresolved > 0))
                    row(g, y + ROWH, L10n.get("level.stat_unresolved"), Integer.toString(sample.unresolved), CUTC);
                return;
            }
            String tiles = Integer.toString(sample.tiles) + ((sample.skipped > 0) ? "  " + L10n.get("level.stat_skipped", sample.skipped) : "");
            row(g, y, L10n.get("level.stat_tiles"), tiles, null);
            y += ROWH;
            if(!p.hasTarget())
                return;
            double t = p.target;
            String ts = String.format("%.1f", t) + (soil ? "  (" + L10n.get("level.stat_survey_units", Math.round(t * gran)) + ")" : "");
            row(g, y, L10n.get("level.stat_target"), ts, NStyle.border);
            y += ROWH;
            double dig = a.dig(t), fill = a.fill(t), net = fill - dig;
            double sc = soil ? gran : 1;
            String unit = soil ? L10n.get("level.unit_soil") : L10n.get("level.unit_height");
            row(g, y, L10n.get("level.stat_dig"), fmt(dig * sc) + " " + unit, CUTC);
            rowAt(g, col2, y, L10n.get("level.stat_fill"), fmt(fill * sc), FILLC);
            y += ROWH;
            String nk = (net >= 0) ? "level.stat_short" : "level.stat_over";
            row(g, y, L10n.get(nk), fmt(Math.abs(net) * sc) + " " + unit, (Math.abs(net * sc) < 1) ? GOOD : null);
            y += ROWH;
            row(g, y, L10n.get("level.stat_cut"), String.format("%.1f", Math.max(0, a.max() - t)), null);
            rowAt(g, col2, y, L10n.get("level.stat_raise"), String.format("%.1f", Math.max(0, t - a.min())), null);
            y += ROWH;
            if(p.locked && (p.baseline > 0)) {
                double done = 1 - ((dig + fill) / p.baseline);
                row(g, y, L10n.get("level.stat_progress"), Math.round(Math.max(0, done) * 100) + "%", GOOD);
            } else {
                int lvl = a.within(t, 0.5);
                row(g, y, L10n.get("level.stat_level"), Math.round(100.0 * lvl / a.count()) + "%", null);
            }
            y += ROWH;
            if(sample.unresolved > 0) {
                row(g, y, L10n.get("level.stat_unresolved"), Integer.toString(sample.unresolved), CUTC);
            } else if(sample.oldest > 0) {
                long days = (System.currentTimeMillis() - sample.oldest) / 86_400_000L;
                row(g, y, L10n.get("level.stat_age"), (days < 1) ? L10n.get("level.today") : L10n.get("level.days", days), (days > 3) ? CUTC : null);
            }
        }

        private String fmt(double v) {
            return(String.format("%,d", Math.round(v)));
        }

        private void row(GOut g, int y, String k, String v, Color vc) {
            rowAt(g, 0, y, k, v, vc);
        }

        private void rowAt(GOut g, int x, int y, String k, String v, Color vc) {
            g.chcolor(DIM);
            g.atext(k, Coord.of(x, y), 0, 0);
            int kx = x + UI.scale(78);
            g.chcolor((vc == null) ? Color.WHITE : vc);
            g.atext(v, Coord.of(kx, y), 0, 0);
            g.chcolor();
        }
    }
}
