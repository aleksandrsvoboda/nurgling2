package nurgling.overlays;

import haven.*;
import haven.render.Pipe;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.cheese.CheeseBranch;
import nurgling.cheese.CheeseRackMemory;
import nurgling.cheese.CheeseRackReader;
import nurgling.cheese.CheeseRackShelves;
import nurgling.cheese.CheeseRackShelves.State;
import nurgling.cheese.CheeseRackShelves.Status;
import nurgling.cheese.CheeseStageHours;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.widgets.Specialisation;
import org.json.JSONException;
import org.json.JSONObject;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;

/**
 * The panel above a cheese rack: one row per tray, top shelf first, saying its tier and whether it
 * is aging or finished here. Zoomed out it becomes three squares. Hovering it shows the details.
 * Display only; the cheese bots don't read it.
 */
public class NCheeseRackOverlay extends NObjectTexLabel {
    private static final double TICK_EVERY = 0.5;
    /** New trays in the first seconds after the rack loads are the ones it already had. */
    private static final long WARMUP_MS = 3000;
    private static final long PLACE_EVERY_MS = 5000;
    private static final String[] SHELF_NAMES = {"Bottom", "Middle", "Top"};

    private static final Color BG = new Color(28, 37, 38, 235);
    private static final Color FRAME = new Color(233, 156, 84);
    private static final Color TEAL = new Color(126, 198, 194);
    private static final Color CURD = new Color(242, 230, 184);
    private static final Color CURD_EDGE = new Color(201, 183, 119);
    private static final Color GREEN = new Color(122, 209, 122);
    private static final Color ORANGE = new Color(233, 156, 84);
    private static final Color WRONG_BG = new Color(58, 36, 20);
    private static final Color DIM = new Color(143, 163, 164);
    private static final Color TEXT = new Color(222, 222, 222);
    private static final Color GREY = new Color(111, 127, 128);
    private static final Color UNKNOWN_EDGE = new Color(85, 85, 102);
    private static final Color CHIP_BG = new Color(28, 37, 38);
    private static final Color INK = new Color(16, 32, 15);

    private static boolean enabled = true;
    private static long lastConfigCheck = 0;
    private static Map<String, Integer> overrides = Collections.emptyMap();
    private static long lastOverridesCheck = 0;
    private static Font font, smallFont;

    private final Gob gob;
    private final long born = System.currentTimeMillis();
    private double acc = TICK_EVERY;
    private CheeseRackShelves.Place place = null;
    private long placeAt = 0;
    private volatile CheeseRackShelves.Memory[] memory = null;
    private volatile Status[] status = null;
    private String signature = null;
    private TexI tip = null;
    private String tipSig = null;
    /** Middle style: one tier chip per shelf, no text. (label = full panel, img = squares) */
    private volatile TexI chips = null;

    public NCheeseRackOverlay(Gob gob) {
        super(gob);
        this.gob = gob;
        pos = new Coord3f(0, 0, 16);
    }

    // ------------------------------------------------------------------ state

    @Override
    public boolean tick(double dt) {
        acc += dt;
        if (acc < TICK_EVERY)
            return false;
        acc = 0;
        long now = System.currentTimeMillis();
        refreshConfig(now);
        if (!enabled) {
            label = null;
            chips = null;
            img = null;
            status = null;
            signature = null;
            return false;
        }
        update(now);
        return false;
    }

    private static void refreshConfig(long now) {
        if (now - lastConfigCheck > 1000) {
            Object v = NConfig.get(NConfig.Key.showCheeseRackTrays);
            enabled = !(v instanceof Boolean) || (Boolean) v;
            lastConfigCheck = now;
        }
        if (now - lastOverridesCheck > 10000) {
            lastOverridesCheck = now;
            Object raw = NConfig.get(NConfig.Key.cheeseRackCalculator);
            try {
                JSONObject o = (raw instanceof String && !((String) raw).isEmpty()) ? new JSONObject((String) raw)
                        : (raw instanceof Map) ? new JSONObject((Map<?, ?>) raw) : null;
                overrides = CheeseStageHours.overridesFrom(o);
            } catch (JSONException e) {
                overrides = Collections.emptyMap();
            }
        }
    }

    private void update(long now) {
        String hash = gob.ngob == null ? null : gob.ngob.hash;
        if (hash == null)
            return;
        CheeseRackMemory.Seen[] seen = CheeseRackReader.readShelves(gob);
        if (seen == null)
            return;
        NGameUI gui = ownerGui();
        if (gui == null || gui.ui == null || gui.ui.core == null)
            return;
        CheeseRackMemory mem = gui.ui.core.cheeseRacks();
        CheeseRackShelves.Memory[] m = mem.observe(hash, seen, now - born > WARMUP_MS, now);
        mem.saveIfDue(now, false);
        if (now - placeAt > PLACE_EVERY_MS) {
            place = findPlace(gui);
            placeAt = now;
        }
        CheeseBranch.Place bp = place == null ? null : place.branch;
        Status[] st = new Status[CheeseRackShelves.SHELVES];
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < st.length; i++) {
            if (seen[i] == null) {
                sig.append("-|");
                continue;
            }
            st[i] = CheeseRackShelves.evaluate(m[i], seen[i].tier, bp, now, overrides);
            double left = st[i].hoursLeft();
            sig.append(st[i].state.ordinal()).append(',').append(st[i].tier).append(',').append(st[i].cheese)
               .append(',').append(Double.isNaN(left) ? "?" : String.valueOf((int) Math.ceil(left))).append('|');
        }
        memory = m;
        status = st;
        String s = sig.toString();
        if (!s.equals(signature)) {
            signature = s;
            rebuild(st);
        }
    }

    private NGameUI ownerGui() {
        if (gob.glob == null || gob.glob.sess == null)
            return null;
        SessionContext ctx = SessionManager.getInstance().findBySession(gob.glob.sess);
        return ctx == null ? null : ctx.getGameUI();
    }

    /** The place of the cheese-rack area this rack stands in, or null. */
    private CheeseRackShelves.Place findPlace(NGameUI gui) {
        if (gui.map == null || gui.map.glob == null || gui.map.glob.map == null)
            return place;
        String spec = Specialisation.SpecName.cheeseRacks.toString();
        try {
            for (NArea area : gui.map.glob.map.areas.values()) {
                for (NArea.Specialisation s : area.spec) {
                    if (!spec.equals(s.name))
                        continue;
                    CheeseRackShelves.Place p = CheeseRackShelves.Place.ofSubtype(s.subtype);
                    if (p != null && area.checkHit(gob.rc))
                        return p;
                }
            }
        } catch (ConcurrentModificationException e) {
            return place;
        }
        return null;
    }

    // ------------------------------------------------------------------ drawing

    /** Hands this rack to the map's panel layout, which picks the style and draws it. */
    @Override
    public void draw(GOut g, Pipe state) {
        TexI full = label, col = chips, sq = img;
        if (full == null || col == null || sq == null)
            return;
        NGameUI gui = NUtils.getGameUI();
        if (gui != null && gui.map instanceof NMapView)
            ((NMapView) gui.map).cheeseRackPanels.add(this, project(state, g.sz()), full, col, sq);
    }

    private void rebuild(Status[] st) {
        List<Line> rows = new ArrayList<>();
        boolean any = false;
        for (int i = st.length - 1; i >= 0; i--) {
            if (st[i] == null)
                continue;
            any = true;
            rows.add(new Line(null, st[i], panelText(st[i]), false));
        }
        if (!any) {
            label = null;
            chips = null;
            img = null;
            return;
        }
        chips = new TexI(chipColumn(st));
        img = new TexI(pips(st));
        label = new TexI(render(rows));
    }

    private static List<Seg> panelText(Status s) {
        List<Seg> out = new ArrayList<>();
        if (s.state == State.EMPTY) {
            out.add(new Seg("empty tray", DIM));
            return out;
        }
        out.add(new Seg(name(s), TEXT));
        switch (s.state) {
            case READY: out.add(new Seg("  move", GREEN)); break;
            case FINAL: out.add(new Seg("  done", ORANGE)); break;
            case WRONG_PLACE: out.add(new Seg("  won't age here", ORANGE)); break;
            case UNKNOWN: out.add(new Seg("  ?", DIM)); break;
            default: {
                double left = s.hoursLeft();
                if (!Double.isNaN(left))
                    out.add(left > 0 ? new Seg("  ~" + (int) Math.ceil(left) + "h", DIM) : new Seg("  due", TEAL));
            }
        }
        return out;
    }

    private static String name(Status s) {
        if (s.cheese != null)
            return s.cheese;
        return s.tier == 0 ? "Curd" : "Tier " + s.tier;
    }

    TexI tooltip(long now) {
        Status[] st = status;
        CheeseRackShelves.Memory[] m = memory;
        if (st == null || m == null)
            return null;
        String sig = signature + (now / 60000) + place;
        if (tip != null && sig.equals(tipSig))
            return tip;
        List<Line> lines = new ArrayList<>();
        List<Seg> head = new ArrayList<>();
        head.add(new Seg("Cheese rack", ORANGE));
        head.add(place != null ? new Seg("  " + place.label, TEXT) : new Seg("  not in a cheese-rack area", DIM));
        lines.add(new Line(null, null, head, false));
        for (int i = st.length - 1; i >= 0; i--) {
            if (st[i] == null) {
                lines.add(new Line(SHELF_NAMES[i], null, List.of(new Seg("no tray", DIM)), false));
                continue;
            }
            lines.add(new Line(SHELF_NAMES[i], st[i], tooltipText(st[i]), false));
            List<Seg> when = whenText(st[i], m[i], now);
            if (!when.isEmpty())
                lines.add(new Line("", null, when, true));
        }
        tip = new TexI(render(lines));
        tipSig = sig;
        return tip;
    }

    private List<Seg> tooltipText(Status s) {
        List<Seg> out = new ArrayList<>();
        if (s.state == State.EMPTY) {
            out.add(new Seg("empty tray", DIM));
            return out;
        }
        out.add(new Seg(name(s), TEXT));
        switch (s.state) {
            case READY:
                out.add(new Seg("  finished here, move it", GREEN));
                out.addAll(nextText(s.cheese));
                break;
            case FINAL:
                out.add(new Seg("  final cheese, done", ORANGE));
                break;
            case WRONG_PLACE:
                out.add(new Seg("  won't age in " + (place == null ? "this place" : place.label.toLowerCase()), ORANGE));
                out.addAll(nextText(s.cheese));
                break;
            case UNKNOWN:
                out.add(new Seg("  was already this tier when first seen", DIM));
                break;
            default:
                if (s.next != null) {
                    out.add(new Seg("  aging into " + s.next.name, TEXT));
                    if (s.agedHours >= 0 && s.stepHours > 0)
                        out.add(new Seg("  " + (int) s.agedHours + "h of ~" + s.stepHours + "h", DIM));
                    else if (s.stepHours > 0)
                        out.add(new Seg("  ~" + s.stepHours + "h step", DIM));
                } else {
                    out.add(new Seg("  aging", DIM));
                }
        }
        return out;
    }

    private static List<Seg> nextText(String cheese) {
        if (cheese == null)
            return Collections.emptyList();
        List<CheeseBranch.Cheese> next = CheeseRackShelves.nextSteps(cheese);
        if (next.isEmpty())
            return Collections.emptyList();
        StringBuilder sb = new StringBuilder("  next: ");
        for (int i = 0; i < next.size(); i++) {
            if (i > 0)
                sb.append(i == next.size() - 1 ? " or " : ", ");
            CheeseRackShelves.Place p = CheeseRackShelves.Place.of(next.get(i).place);
            sb.append(next.get(i).name).append(" (").append(p == null ? next.get(i).place : p.label).append(')');
        }
        return List.of(new Seg(sb.toString(), DIM));
    }

    private static List<Seg> whenText(Status s, CheeseRackShelves.Memory m, long now) {
        List<Seg> out = new ArrayList<>();
        if (m == null || s.state == State.EMPTY)
            return out;
        out.add(new Seg((m.placedLive ? "placed " : "first seen ") + ago(now - m.since), DIM));
        if (s.cheese == null)
            out.add(new Seg(" · open the rack to see which cheese", DIM));
        return out;
    }

    private static String ago(long ms) {
        long h = ms / 3600000;
        if (h < 1)
            return "under an hour ago";
        if (h < 48)
            return h + "h ago";
        return (h / 24) + "d ago";
    }

    // ------------------------------------------------------------------ images

    private static final class Seg {
        final String text;
        final Color col;

        Seg(String text, Color col) {
            this.text = text;
            this.col = col;
        }
    }

    /** One row: an optional lead column (shelf name), an optional chip, then text. */
    private static final class Line {
        final String lead;
        final Status chip;
        final List<Seg> segs;
        final boolean small;

        Line(String lead, Status chip, List<Seg> segs, boolean small) {
            this.lead = lead;
            this.chip = chip;
            this.segs = segs;
            this.small = small;
        }
    }

    private static void fonts() {
        if (font == null) {
            font = new Font("SansSerif", Font.BOLD, UI.scale(11));
            smallFont = new Font("SansSerif", Font.PLAIN, UI.scale(10));
        }
    }

    private static BufferedImage render(List<Line> lines) {
        fonts();
        int pad = UI.scale(4), gap = UI.scale(5), chipW = UI.scale(18), chipH = UI.scale(15), rowGap = UI.scale(2);
        Graphics2D mg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        FontMetrics fm = mg.getFontMetrics(font), sm = mg.getFontMetrics(smallFont);
        boolean anyChip = false;
        int leadW = 0;
        for (Line l : lines) {
            if (l.lead != null)
                leadW = Math.max(leadW, fm.stringWidth(l.lead));
            anyChip |= l.chip != null;
        }
        int w = 0, h = 0;
        int[] rowH = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            FontMetrics f = l.small ? sm : fm;
            int x = (l.lead != null ? leadW + gap : 0) + ((l.chip != null || (anyChip && l.lead != null)) ? chipW + gap : 0);
            for (Seg s : l.segs)
                x += f.stringWidth(s.text);
            w = Math.max(w, x);
            rowH[i] = Math.max(f.getHeight(), (l.chip != null) ? chipH : 0);
            h += rowH[i] + (i > 0 ? rowGap : 0);
        }
        mg.dispose();
        BufferedImage out = new BufferedImage(w + pad * 2, h + pad * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(FRAME);
        g.drawRect(0, 0, out.getWidth() - 1, out.getHeight() - 1);
        int y = pad;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            Font f = l.small ? smallFont : font;
            FontMetrics m = l.small ? sm : fm;
            int base = y + (rowH[i] - m.getHeight()) / 2 + m.getAscent();
            int x = pad;
            g.setFont(font);
            if (l.lead != null) {
                g.setColor(DIM);
                g.drawString(l.lead, x, base);
                x += leadW + gap;
            }
            if (l.chip != null) {
                chip(g, l.chip, x, y + (rowH[i] - chipH) / 2, chipW, chipH);
                x += chipW + gap;
            } else if (anyChip && l.lead != null) {
                x += chipW + gap;
            }
            g.setFont(f);
            for (Seg s : l.segs) {
                g.setColor(s.col);
                g.drawString(s.text, x, base);
                x += m.stringWidth(s.text);
            }
            y += rowH[i] + rowGap;
        }
        g.dispose();
        return out;
    }

    private static void chip(Graphics2D g, Status s, int x, int y, int w, int h) {
        Color fill = CHIP_BG, edge = TEAL, ink = TEAL;
        String text = s.tier <= 0 ? (s.tier == 0 ? "C" : "") : String.valueOf(s.tier);
        switch (s.state) {
            case EMPTY: fill = null; edge = GREY; ink = GREY; break;
            case READY: fill = GREEN; edge = GREEN; ink = INK; break;
            case FINAL: fill = ORANGE; edge = ORANGE; ink = CHIP_BG; break;
            case WRONG_PLACE: fill = WRONG_BG; edge = ORANGE; ink = ORANGE; break;
            case UNKNOWN: edge = UNKNOWN_EDGE; ink = DIM; break;
            default:
                if (s.tier == 0) {
                    edge = CURD_EDGE;
                    ink = CURD;
                }
        }
        if (fill != null) {
            g.setColor(fill);
            g.fillRect(x, y, w, h);
        }
        g.setColor(edge);
        if (s.state == State.EMPTY) {
            java.awt.Stroke old = g.getStroke();
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{2, 2}, 0));
            g.drawRect(x, y, w - 1, h - 1);
            g.setStroke(old);
        } else {
            g.drawRect(x, y, w - 1, h - 1);
        }
        if (!text.isEmpty()) {
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(ink);
            g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (h - fm.getHeight()) / 2 + fm.getAscent());
        }
    }

    /** One tier chip per tray, top shelf first, no text. */
    private static BufferedImage chipColumn(Status[] st) {
        fonts();
        int w = UI.scale(18), h = UI.scale(15), gap = UI.scale(2), pad = UI.scale(2);
        int n = 0;
        for (Status t : st)
            if (t != null)
                n++;
        BufferedImage out = new BufferedImage(w + pad * 2, n * h + (n - 1) * gap + pad * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(FRAME);
        g.drawRect(0, 0, out.getWidth() - 1, out.getHeight() - 1);
        int y = pad;
        for (int i = st.length - 1; i >= 0; i--) {
            if (st[i] == null)
                continue;
            chip(g, st[i], pad, y, w, h);
            y += h + gap;
        }
        g.dispose();
        return out;
    }

    /** Smallest style: one square per shelf, top first. */
    private static BufferedImage pips(Status[] st) {
        int s = UI.scale(7), gap = UI.scale(2), pad = UI.scale(2);
        BufferedImage out = new BufferedImage(s + pad * 2, st.length * s + (st.length - 1) * gap + pad * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(Color.BLACK);
        g.drawRect(0, 0, out.getWidth() - 1, out.getHeight() - 1);
        int y = pad;
        for (int i = st.length - 1; i >= 0; i--, y += s + gap) {
            Status t = st[i];
            if (t == null)
                continue;
            Color c;
            switch (t.state) {
                case EMPTY: c = null; break;
                case READY: c = GREEN; break;
                case FINAL: case WRONG_PLACE: c = ORANGE; break;
                case UNKNOWN: c = GREY; break;
                default: c = t.tier == 0 ? CURD : TEAL;
            }
            if (c == null) {
                g.setColor(GREY);
                g.drawRect(pad, y, s - 1, s - 1);
            } else {
                g.setColor(c);
                g.fillRect(pad, y, s, s);
            }
        }
        g.dispose();
        return out;
    }
}
