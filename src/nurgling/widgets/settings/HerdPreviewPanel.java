package nurgling.widgets.settings;

import haven.*;
import haven.res.ui.croster.CattleRoster;
import haven.res.ui.croster.Entry;
import nurgling.NConfig;
import nurgling.NStyle;
import nurgling.animals.AnimalFacts;
import nurgling.animals.CullPlanner;
import nurgling.animals.HerdRules;
import nurgling.animals.RankExplainer;
import nurgling.animals.RankTerm;

import java.awt.Color;
import java.util.*;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

/**
 * Beside a herd settings window: runs the cull planner on the animals in the roster with the
 * settings as typed and as saved, and shows who would switch between living and dying, which
 * settings actually move the ranking, and what the breeding and threshold settings do to a stat.
 */
public class HerdPreviewPanel extends Widget {
    public static final int W = UI.scale(380);
    public static final int H = UI.scale(700);
    private static final double PERIOD = 0.3;
    private static final int CHART_H = UI.scale(105);
    private static final int ROW_H = UI.scale(16);
    private static final int TEXT_MAX = UI.scale(250);
    private static final Color DIES = new Color(255, 143, 143);
    private static final Color LIVES = new Color(150, 210, 150);
    private static final Color DIM = new Color(143, 163, 164);
    private static final Color TEAL = new Color(127, 211, 200);
    private static final Color TRACK = new Color(31, 42, 48);

    private final CattleRoster<?> roster;
    private final String species;
    private final Supplier<String> presetName;
    private final Supplier<HerdRules> draft;
    private final Supplier<HerdRules> saved;
    private double age = PERIOD;
    private int pick = 0;

    private String markup;
    private Tex text;
    private HerdRules rules;
    private List<AnimalFacts> herd = Collections.emptyList();
    private Map<Object, CullPlanner.Outcome> after = Collections.emptyMap();
    private double[] influence;
    private final Map<String, Tex> labels = new HashMap<>();
    private Area eqChart;

    /**
     * @param species    the key area rank presets are stored under ("cows", "goats", "sheeps", "pigs", "horses", "deer")
     * @param presetName the preset being edited
     * @param draft      the preset as currently typed into the window
     * @param saved      the preset as saved, or null when it has never been saved
     */
    public HerdPreviewPanel(CattleRoster<?> roster, String species, Supplier<String> presetName,
                            Supplier<HerdRules> draft, Supplier<HerdRules> saved) {
        super(new Coord(W, H));
        this.roster = roster;
        this.species = species;
        this.presetName = presetName;
        this.draft = draft;
        this.saved = saved;
    }

    /** Right edge of a window's existing content, so the panel can sit beside it. */
    public static int rightEdge(Widget wnd) {
        int x = 0;
        for (Widget ch = wnd.child; ch != null; ch = ch.next) {
            if ((wnd instanceof Window) && (ch == ((Window) wnd).deco))
                continue;
            x = Math.max(x, ch.c.x + ch.sz.x);
        }
        return x;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (visible() && (age += dt) >= PERIOD) {
            age = 0;
            recompute();
        }
    }

    /** Pens to preview, by area: the ones bound to this preset, or every pen when none is. */
    private Map<Integer, List<AnimalFacts>> pens(String name, boolean[] bound) {
        Map<Integer, List<AnimalFacts>> mine = new TreeMap<>(), all = new TreeMap<>();
        if (roster != null) {
            for (Entry e : roster.entries.values()) {
                if (e.areaId < 0)
                    continue;
                AnimalFacts f = e.facts();
                if (f == null)
                    continue;
                all.computeIfAbsent(e.areaId, k -> new ArrayList<>()).add(f);
                if (name.equals(NConfig.getAreaRankPreset(e.areaId, species)))
                    mine.computeIfAbsent(e.areaId, k -> new ArrayList<>()).add(f);
            }
        }
        bound[0] = !mine.isEmpty();
        return bound[0] ? mine : all;
    }

    private void recompute() {
        String name = presetName.get();
        HerdRules d = draft.get(), s = saved.get();
        boolean[] bound = new boolean[1];
        Map<Integer, List<AnimalFacts>> pens = pens(name, bound);
        List<AnimalFacts> all = new ArrayList<>();
        Map<Object, CullPlanner.Outcome> nafter = new HashMap<>(), before = new HashMap<>();
        Set<String> warns = new LinkedHashSet<>();
        for (List<AnimalFacts> pen : pens.values()) {
            all.addAll(pen);
            CullPlanner.Plan pa = CullPlanner.plan(pen, d, d);
            nafter.putAll(pa.outcomes);
            warns.addAll(pa.warnings);
            if (s != null)
                before.putAll(CullPlanner.plan(pen, s, s).outcomes);
        }
        if (pens.isEmpty())
            warns.addAll(CullPlanner.ruleWarnings(d, all));
        herd = all;
        after = nafter;
        rules = d;
        influence = CullPlanner.influence(d, all);

        StringBuilder b = new StringBuilder("$b{What these settings do}\n");
        if (all.isEmpty()) {
            b.append(col("No animals seen inside an animal area yet. Open the roster near the pen\nso the animals get placed, then come back.", DIM)).append('\n');
        } else {
            b.append(col(bound[0] ? all.size() + " animals in pens bound to \"" + name + "\""
                    : "No pen uses \"" + name + "\" yet: previewing every pen as if it did (" + all.size() + " animals)", DIM)).append('\n');
            int ka = count(nafter, CullPlanner.Verdict.KILL);
            if (s == null) {
                b.append("Would slaughter $b{").append(ka).append("} of ").append(all.size()).append(" (preset not saved yet)\n");
            } else {
                int kb = count(before, CullPlanner.Verdict.KILL);
                b.append("Would slaughter: saved $b{").append(kb).append("}, as typed $b{").append(ka).append("}\n");
                List<String> flips = new ArrayList<>();
                for (AnimalFacts f : all) {
                    CullPlanner.Outcome ob = before.get(f.id), oa = nafter.get(f.id);
                    if (ob == null || oa == null || ob.verdict.dies() == oa.verdict.dies())
                        continue;
                    String who = who(f);
                    flips.add(oa.verdict.dies() ? col(who + ": lives -> dies", DIES) : col(who + ": dies -> lives", LIVES));
                }
                if (flips.isEmpty()) {
                    b.append(col("Your edits change nobody's fate.", DIM)).append('\n');
                } else {
                    for (int i = 0; i < Math.min(6, flips.size()); i++)
                        b.append("  ").append(flips.get(i)).append('\n');
                    if (flips.size() > 6)
                        b.append(col("  and " + (flips.size() - 6) + " more", DIM)).append('\n');
                    b.append(col("Press Save to keep these edits.", DIM)).append('\n');
                }
            }
        }
        int shown = 0;
        for (String w : warns) {
            if (shown++ == 3) {
                b.append(col("(" + (warns.size() - 3) + " more warnings in the roster preview)", DIM)).append('\n');
                break;
            }
            b.append(col("! " + w, NStyle.border)).append('\n');
        }
        String nmarkup = b.toString();
        if (!nmarkup.equals(markup)) {
            if (text != null)
                text.dispose();
            text = RichText.render(nmarkup, W).tex();
            markup = nmarkup;
        }
    }

    /** Name plus what tells same-named animals apart. */
    private static String who(AnimalFacts f) {
        return f.name + " (" + (f.male ? "male" : "female") + (f.young ? ", young" : "") + ", Q " + Math.round(f.q) + ")";
    }

    private static int count(Map<Object, CullPlanner.Outcome> outcomes, CullPlanner.Verdict v) {
        int n = 0;
        for (CullPlanner.Outcome o : outcomes.values())
            if (o.verdict == v)
                n++;
        return n;
    }

    private static String col(String s, Color c) {
        return "$col[" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "]{" + RankExplainer.quote(s) + "}";
    }

    private Tex label(String s, Color c) {
        String key = c.getRGB() + "|" + s;
        Tex t = labels.get(key);
        if (t == null && labels.size() >= 96) {
            // Titles carry weights and names that change while editing; drop stale ones
            for (Tex old : labels.values())
                old.dispose();
            labels.clear();
        }
        if (t == null)
            labels.put(key, t = Text.render(s, c).tex());
        return t;
    }

    @Override
    public void draw(GOut g) {
        g.chcolor(NStyle.infoBg);
        g.frect(Coord.z, sz);
        g.chcolor();
        if (rules == null) {
            if (text != null)
                g.image(text, UI.scale(4, 4));
            return;
        }
        List<RankTerm> knees = new ArrayList<>();
        for (RankTerm t : rules.terms)
            if (t.kind == RankTerm.Kind.THRESHOLD)
                knees.add(t);
        int charts = 1 + knees.size();
        int y = UI.scale(4);
        if (text != null) {
            int th = Math.min(text.sz().y, TEXT_MAX);
            g.reclip(new Coord(UI.scale(4), y), new Coord(sz.x - UI.scale(8), th)).image(text, Coord.z);
            y += th;
        }
        y = drawInfluence(g, y + UI.scale(10));
        y += UI.scale(10);
        g.image(label("Shapes: dots are your animals, red = slaughtered.", DIM), new Coord(UI.scale(4), y));
        g.image(label("Click the first chart to step through the animals.", DIM), new Coord(UI.scale(4), y + ROW_H));
        int chartsTop = y + ROW_H * 2 + UI.scale(2);
        int cw = (sz.x - UI.scale(14)) / 2;
        for (int i = 0; i < charts; i++) {
            Coord ul = new Coord(UI.scale(4) + (i % 2) * (cw + UI.scale(6)), chartsTop + (i / 2) * (CHART_H + UI.scale(6)));
            Coord csz = new Coord(cw, CHART_H);
            if (i == 0) {
                eqChart = Area.sized(ul, csz);
                drawQualityChart(g, ul, csz);
            } else {
                drawKneeChart(g, ul, csz, knees.get(i - 1));
            }
        }
    }

    private int drawInfluence(GOut g, int y) {
        g.image(label("Real influence: share of the rank differences between your animals", DIM), new Coord(UI.scale(4), y));
        y += UI.scale(18);
        int lx = UI.scale(4), bx = UI.scale(130), bw = UI.scale(120);
        for (int i = 0; i < rules.terms.size(); i++) {
            RankTerm t = rules.terms.get(i);
            double share = influence == null ? 0 : influence[i];
            boolean wasted = t.weighted() && share < 0.03;
            g.reclip(new Coord(lx, y), new Coord(bx - lx - UI.scale(4), ROW_H)).image(label(t.label, wasted ? NStyle.border : Color.WHITE), Coord.z);
            g.chcolor(TRACK);
            g.frect(new Coord(bx, y + UI.scale(3)), new Coord(bw, UI.scale(9)));
            g.chcolor(t.color);
            g.frect(new Coord(bx, y + UI.scale(3)), new Coord((int) Math.round(bw * share), UI.scale(9)));
            g.chcolor();
            g.image(label(Math.round(share * 100) + "%", DIM), new Coord(bx + bw + UI.scale(6), y));
            if (wasted)
                g.image(label("set, barely matters", NStyle.border), new Coord(bx + bw + UI.scale(40), y));
            y += ROW_H;
        }
        if (influence == null && !herd.isEmpty()) {
            g.image(label("No stat makes your animals rank differently", DIM), new Coord(lx, y));
            y += ROW_H;
        }
        return y;
    }

    private void drawQualityChart(GOut g, Coord ul, Coord sz) {
        if (herd.isEmpty()) {
            frame(g, ul, sz, "Effective quality");
            return;
        }
        AnimalFacts a = herd.get(Math.floorMod(pick, herd.size()));
        double xmax = Math.max(100, Math.max(a.q, a.seedq) * 1.2);
        DoubleUnaryOperator f = q -> rules.effectiveQuality(a.male, q, a.seedq);
        Plot p = new Plot(ul, sz, 0, xmax, 0, xmax);
        frame(g, ul, sz, "Eff. quality: " + a.name + " Q" + Math.round(a.q));
        g.chcolor(DIM.getRed(), DIM.getGreen(), DIM.getBlue(), 90);
        g.line(p.at(0, 0), p.at(xmax, xmax), 1);
        if (rules.usesBreeding(a.male)) {
            int t = rules.target(a.seedq);
            if (t > 0 && t < xmax) {
                dashed(g, p.at(t, 0), p.at(t, xmax), TEAL);
                g.image(label("target " + t, TEAL), p.at(t, xmax).add(UI.scale(2), UI.scale(12)));
            }
        }
        curve(g, p, f, 0, xmax);
        dot(g, p.at(a.q, f.applyAsDouble(a.q)), dies(a) ? DIES : TEAL);
        g.chcolor();
    }

    private void drawKneeChart(GOut g, Coord ul, Coord sz, RankTerm t) {
        int vmax = 1;
        for (AnimalFacts a : herd)
            vmax = Math.max(vmax, a.stat(t.id));
        double xmax = vmax * 1.3;
        DoubleUnaryOperator f = v -> t.value((int) Math.round(v), 0, false);
        double ymin = 0, ymax = 0;
        for (int i = 0; i <= 40; i++) {
            double y = f.applyAsDouble(xmax * i / 40);
            ymin = Math.min(ymin, y);
            ymax = Math.max(ymax, y);
        }
        if (ymax - ymin < 1)
            ymax = ymin + 1;
        Plot p = new Plot(ul, sz, 0, xmax, ymin, ymax);
        frame(g, ul, sz, t.label.replace("quantity", "qty") + ": " + t.weightsText().replace(" up to ", " to "));
        if (t.threshold > 0 && t.threshold < xmax)
            dashed(g, p.at(t.threshold, ymin), p.at(t.threshold, ymax), NStyle.border);
        curve(g, p, f, 0, xmax);
        for (AnimalFacts a : herd) {
            if (a.dead)
                continue;
            int v = a.stat(t.id);
            dot(g, p.at(v, t.value(v, 0, false)), dies(a) ? DIES : TEAL);
        }
        g.chcolor();
    }

    private boolean dies(AnimalFacts a) {
        CullPlanner.Outcome o = after.get(a.id);
        return o != null && o.verdict.dies();
    }

    private void frame(GOut g, Coord ul, Coord sz, String title) {
        g.chcolor(TRACK);
        g.frect(ul, sz);
        g.chcolor(NStyle.separator.brighter());
        g.line(ul, ul.add(sz.x, 0), 1);
        g.line(ul.add(0, sz.y), ul.add(sz.x, sz.y), 1);
        g.line(ul, ul.add(0, sz.y), 1);
        g.line(ul.add(sz.x, 0), ul.add(sz.x, sz.y), 1);
        g.chcolor();
        g.reclip(ul.add(UI.scale(3), UI.scale(1)), sz.sub(UI.scale(6), UI.scale(2))).image(label(title, TEAL), Coord.z);
    }

    private static void curve(GOut g, Plot p, DoubleUnaryOperator f, double x0, double x1) {
        g.chcolor(NStyle.border);
        Coord prev = null;
        for (int i = 0; i <= 80; i++) {
            double x = x0 + (x1 - x0) * i / 80;
            Coord c = p.at(x, f.applyAsDouble(x));
            if (prev != null)
                g.line(prev, c, 2);
            prev = c;
        }
    }

    private static void dashed(GOut g, Coord a, Coord b, Color c) {
        g.chcolor(c);
        double len = Math.max(1, a.dist(b)), step = UI.scale(5);
        for (double d = 0; d < len; d += step * 2) {
            double e = Math.min(len, d + step);
            g.line(lerp(a, b, d / len), lerp(a, b, e / len), 1);
        }
    }

    private static Coord lerp(Coord a, Coord b, double t) {
        return new Coord((int) Math.round(a.x + (b.x - a.x) * t), (int) Math.round(a.y + (b.y - a.y) * t));
    }

    private static void dot(GOut g, Coord c, Color col) {
        int r = UI.scale(2);
        g.chcolor(col);
        g.frect(c.sub(r, r), new Coord(r * 2 + 1, r * 2 + 1));
    }

    /** Maps data coordinates into a chart box, below its title line. */
    private static final class Plot {
        final Coord ul, sz;
        final double x0, x1, y0, y1;

        Plot(Coord box, Coord bsz, double x0, double x1, double y0, double y1) {
            this.ul = box.add(UI.scale(6), UI.scale(18));
            this.sz = bsz.sub(UI.scale(12), UI.scale(24));
            this.x0 = x0;
            this.x1 = x1;
            this.y0 = y0;
            this.y1 = y1;
        }

        Coord at(double x, double y) {
            double fx = Math.max(0, Math.min(1, (x - x0) / (x1 - x0)));
            double fy = Math.max(0, Math.min(1, (y - y0) / (y1 - y0)));
            return new Coord(ul.x + (int) Math.round(fx * sz.x), ul.y + sz.y - (int) Math.round(fy * sz.y));
        }
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (ev.b == 1 && eqChart != null && eqChart.contains(ev.c)) {
            pick++;
            return true;
        }
        return super.mousedown(ev);
    }

    @Override
    public void dispose() {
        for (Tex t : labels.values())
            t.dispose();
        labels.clear();
        if (text != null)
            text.dispose();
        super.dispose();
    }
}
