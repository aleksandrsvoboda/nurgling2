package nurgling.widgets.calculators;

import haven.*;
import nurgling.NConfig;
import nurgling.NStyle;
import nurgling.cheese.CheeseBranch;
import nurgling.cheese.CheeseRackCalculator;
import nurgling.cheese.CheeseStageHours;
import nurgling.i18n.L10n;
import nurgling.widgets.cookbook.CookbookTheme;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.awt.Color;
import java.util.*;

/**
 * Cheese rack calculator tab: how many racks each area needs for a steady rate of trays.
 * Rates are in trays and hours, racks are counts. Inputs are remembered in
 * {@link NConfig.Key#cheeseRackCalculator}. Drawn like the cookbook table.
 */
public class CheeseRackCalculatorPanel extends Widget {
    private static final int TOOLBAR_H = UI.scale(28);
    private static final int HEAD_H = UI.scale(22);
    private static final int ROW_H = UI.scale(28);
    private static final int STAGE_H = UI.scale(20);
    private static final int PAD = UI.scale(8);
    private static final int LINE_H = UI.scale(17);

    private static final int X_CHEESE = PAD;
    private static final int W_CHEESE = UI.scale(160);
    private static final int X_TRAYS = UI.scale(180);
    private static final int X_EVERY = UI.scale(232);
    private static final int R_PER_RUN = UI.scale(332);
    private static final int R_PLACE0 = UI.scale(398);
    private static final int PLACE_W = UI.scale(56);
    private static final int R_FIRST = UI.scale(636);
    private static final int X_STAGES_BTN = UI.scale(646);
    private static final int X_REMOVE_BTN = UI.scale(674);
    private static final int ENTRY_W = UI.scale(44);
    private static final int SMALL_BTN_W = UI.scale(24);
    private static final String[] CURDS = {"Cow's Curd", "Sheep's Curd", "Goat's Curd"};

    /** Label that keeps its right edge fixed, so numbers line up under their heading. */
    private static class RLabel extends Label {
        private final int right;

        RLabel(String text, Text.Foundry f, int right) {
            super(text, f);
            this.right = right;
        }

        @Override
        public void settext(String text) {
            super.settext(text);
            move(new Coord(right - sz.x, c.y));
        }
    }

    /** Flat coloured band behind a row. */
    private static class Band extends Widget {
        private final Color color;

        Band(Coord sz, Color color) {
            super(sz);
            this.color = color;
        }

        @Override
        public void draw(GOut g) {
            CookbookTheme.fill(g, Coord.z, sz, color);
        }
    }

    private static class RowState {
        String cheese;
        String trays;
        String every;
        boolean expanded;
        Label perRun;
        Label first;
        final Map<CheeseBranch.Place, Label> racks = new EnumMap<>(CheeseBranch.Place.class);

        RowState(String cheese, String trays, String every) {
            this.cheese = cheese;
            this.trays = trays;
            this.every = every;
        }
    }

    private final List<String> cheeseTypes = CheeseBranch.allProducts();
    private final List<RowState> rows = new ArrayList<>();
    private final Map<String, Integer> overrides = new HashMap<>();
    private final List<String> periods = Arrays.asList(L10n.get("calc.cheese.per_hour"), L10n.get("calc.cheese.per_day"));
    private String runEvery = "12";
    private String headroom = "0";
    /** Hours one line of the supply summary covers: 1 for per hour, 24 for per day. */
    private int period = 24;
    private boolean rebuildPending = false;

    private final Scrollport scroll;
    private final Widget content;
    private final Map<CheeseBranch.Place, Label> totalRacks = new EnumMap<>(CheeseBranch.Place.class);
    private final Label totalAll;
    private final Label circulation;
    private final Label[] curdLabels = new Label[CURDS.length];
    private final int headY;
    private final int listY;
    private final int listH;
    private final int totalsY;
    private final int summaryY;

    public CheeseRackCalculatorPanel(Coord sz) {
        super(sz);
        load();

        // ---- toolbar
        int y = UI.scale(6);
        Widget prev = add(new Label(L10n.get("calc.cheese.run_every"), CookbookTheme.body), new Coord(PAD, y + UI.scale(3)));
        prev = add(new TextEntry(ENTRY_W, runEvery) {
            @Override
            protected void changed() {
                super.changed();
                runEvery = text();
                save();
                recompute();
            }
        }, prev.pos("ur").adds(6, -3));
        prev = add(new Label(L10n.get("calc.cheese.hours"), CookbookTheme.body), prev.pos("ur").adds(5, 3));
        prev = add(new Label(L10n.get("calc.cheese.headroom"), CookbookTheme.body), prev.pos("ur").adds(18, 0));
        prev = add(new TextEntry(ENTRY_W, headroom) {
            @Override
            protected void changed() {
                super.changed();
                headroom = text();
                save();
                recompute();
            }
        }, prev.pos("ur").adds(6, -3));
        prev = add(new Label("%", CookbookTheme.body), prev.pos("ur").adds(5, 3));
        prev = add(new Label(L10n.get("calc.cheese.show"), CookbookTheme.body), prev.pos("ur").adds(18, 0));
        Dropbox<String> periodBox = new Dropbox<String>(UI.scale(84), periods.size(), UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return periods.get(i);
            }

            @Override
            protected int listitems() {
                return periods.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                period = periods.indexOf(item) == 0 ? 1 : 24;
                save();
                recompute();
            }
        };
        periodBox.sel = periods.get(period == 1 ? 0 : 1);
        add(periodBox, prev.pos("ur").adds(6, -3));

        // ---- table head
        headY = TOOLBAR_H + UI.scale(4);
        int ty = headY + UI.scale(4);
        add(new Label(L10n.get("calc.cheese.col.cheese"), CookbookTheme.bold), new Coord(X_CHEESE, ty));
        add(new Label(L10n.get("calc.cheese.col.trays"), CookbookTheme.bold), new Coord(X_TRAYS, ty));
        add(new Label(L10n.get("calc.cheese.col.every"), CookbookTheme.bold), new Coord(X_EVERY, ty));
        addRight(this, L10n.get("calc.cheese.col.per_run"), CookbookTheme.bold, R_PER_RUN, ty);
        for (int i = 0; i < CheeseRackCalculator.RACK_PLACES.length; i++)
            addRight(this, placeName(CheeseRackCalculator.RACK_PLACES[i]), CookbookTheme.bold, placeRight(i), ty);
        addRight(this, L10n.get("calc.cheese.col.first"), CookbookTheme.bold, R_FIRST, ty);

        // ---- rows
        listY = headY + HEAD_H;
        listH = sz.y - listY - UI.scale(178);
        scroll = add(new Scrollport(new Coord(sz.x, listH)), new Coord(0, listY));
        content = new Widget(new Coord(scroll.cont.sz.x, UI.scale(20))) {
            @Override
            public void pack() {
                resize(new Coord(scroll.cont.sz.x, contentsz().y));
            }
        };
        scroll.cont.add(content, Coord.z);

        // ---- totals
        totalsY = listY + listH + UI.scale(2);
        add(new Label(L10n.get("calc.cheese.racks_needed"), CookbookTheme.bold), new Coord(X_CHEESE, totalsY + UI.scale(4)));
        for (int i = 0; i < CheeseRackCalculator.RACK_PLACES.length; i++)
            totalRacks.put(CheeseRackCalculator.RACK_PLACES[i],
                    addRight(this, "0", CookbookTheme.bold, placeRight(i), totalsY + UI.scale(4)));
        totalAll = addRight(this, "", CookbookTheme.bold, R_FIRST, totalsY + UI.scale(4));

        int btnY = totalsY + HEAD_H + UI.scale(8);
        add(new Button(UI.scale(110), L10n.get("calc.cheese.add"), () -> {
            rows.add(new RowState(cheeseTypes.get(0), "1", "24"));
            save();
            rebuildPending = true;
        }), new Coord(PAD, btnY));
        add(new Button(UI.scale(140), L10n.get("calc.cheese.reset_hours"), () -> {
            overrides.clear();
            save();
            rebuildPending = true;
        }), new Coord(PAD + UI.scale(120), btnY));

        // ---- supply box
        summaryY = btnY + UI.scale(32);
        int sy = summaryY + UI.scale(6);
        circulation = add(new Label("", CookbookTheme.bold), new Coord(PAD + UI.scale(6), sy));
        for (int i = 0; i < CURDS.length; i++)
            curdLabels[i] = add(new Label("", CookbookTheme.body), new Coord(PAD + UI.scale(6), sy + LINE_H * (i + 1)));
        int noteY = sy + LINE_H * (CURDS.length + 1) + UI.scale(3);
        add(new Label(L10n.get("calc.cheese.tubs_note"), CookbookTheme.small), new Coord(PAD + UI.scale(6), noteY));
        add(new Label(L10n.get("calc.cheese.hint"), CookbookTheme.small), new Coord(PAD + UI.scale(6), noteY + UI.scale(13)));

        rebuild();
    }

    private static Label addRight(Widget parent, String text, Text.Foundry f, int right, int y) {
        RLabel label = new RLabel(text, f, right);
        parent.add(label, new Coord(right - label.sz.x, y));
        return label;
    }

    private static int placeRight(int i) {
        return R_PLACE0 + i * PLACE_W;
    }

    private static String placeName(CheeseBranch.Place place) {
        return L10n.get("calc.cheese.place." + place.name());
    }

    @Override
    public void draw(GOut g) {
        CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.bg);
        CookbookTheme.fill(g, Coord.z, new Coord(sz.x, TOOLBAR_H), CookbookTheme.head);
        CookbookTheme.fill(g, new Coord(0, headY), new Coord(sz.x, HEAD_H), CookbookTheme.head);
        CookbookTheme.fill(g, new Coord(0, totalsY), new Coord(sz.x, HEAD_H), CookbookTheme.head);
        CookbookTheme.fill(g, new Coord(0, listY + listH), new Coord(sz.x, UI.scale(1)), CookbookTheme.line);
        CookbookTheme.frame(g, new Coord(PAD, summaryY), new Coord(sz.x - PAD * 2, sz.y - summaryY - UI.scale(4)), CookbookTheme.outline);
        super.draw(g);
    }

    @Override
    public void tick(double dt) {
        if (rebuildPending) {
            rebuildPending = false;
            rebuild();
        }
        super.tick(dt);
    }

    private void rebuild() {
        for (Widget child : new ArrayList<>(content.children()))
            child.destroy();

        int y = 0;
        int i = 0;
        for (RowState row : rows) {
            content.add(new Band(new Coord(content.sz.x, ROW_H), (i++ % 2 == 0) ? NStyle.rowEven : NStyle.rowOdd), new Coord(0, y));
            addRow(row, y);
            y += ROW_H;
            if (row.expanded)
                y = addStages(row, y);
        }
        content.pack();
        scroll.cont.update();
        recompute();
    }

    private void addRow(RowState row, int y) {
        Dropbox<String> cheese = new Dropbox<String>(W_CHEESE, Math.min(16, cheeseTypes.size()), UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return cheeseTypes.get(i);
            }

            @Override
            protected int listitems() {
                return cheeseTypes.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                row.cheese = item;
                save();
                rebuildPending = true;
            }
        };
        cheese.sel = row.cheese;
        content.add(cheese, new Coord(X_CHEESE, y + UI.scale(5)));

        content.add(new TextEntry(ENTRY_W, row.trays) {
            @Override
            protected void changed() {
                super.changed();
                row.trays = text();
                save();
                recompute();
            }
        }, new Coord(X_TRAYS, y + UI.scale(5)));
        content.add(new TextEntry(ENTRY_W, row.every) {
            @Override
            protected void changed() {
                super.changed();
                row.every = text();
                save();
                recompute();
            }
        }, new Coord(X_EVERY, y + UI.scale(5)));

        int ty = y + UI.scale(8);
        row.perRun = addRight(content, "", CookbookTheme.body, R_PER_RUN, ty);
        row.racks.clear();
        for (int i = 0; i < CheeseRackCalculator.RACK_PLACES.length; i++)
            row.racks.put(CheeseRackCalculator.RACK_PLACES[i], addRight(content, "", CookbookTheme.body, placeRight(i), ty));
        row.first = addRight(content, "", CookbookTheme.body, R_FIRST, ty);

        content.add(new Button(SMALL_BTN_W, row.expanded ? "▼" : "▶", () -> {
            row.expanded = !row.expanded;
            rebuildPending = true;
        }), new Coord(X_STAGES_BTN, y + UI.scale(3)));
        content.add(new Button(SMALL_BTN_W, "x", () -> {
            rows.remove(row);
            save();
            rebuildPending = true;
        }), new Coord(X_REMOVE_BTN, y + UI.scale(3)));
    }

    private int addStages(RowState row, int y) {
        List<CheeseRackCalculator.Stage> stages = CheeseRackCalculator.stages(row.cheese, overrides);
        if (stages == null) {
            content.add(new Label(L10n.get("calc.cheese.no_data"), CookbookTheme.small), new Coord(X_CHEESE + UI.scale(18), y + UI.scale(3)));
            return y + STAGE_H;
        }
        for (CheeseRackCalculator.Stage stage : stages) {
            String key = CheeseStageHours.key(stage.name, stage.place);
            int wiki = CheeseStageHours.defaultHours(stage.name, stage.place);
            content.add(new Label(stage.name + " (" + placeName(stage.place) + ")", CookbookTheme.small),
                    new Coord(X_CHEESE + UI.scale(18), y + UI.scale(4)));
            content.add(new TextEntry(ENTRY_W, String.valueOf(stage.hours)) {
                @Override
                protected void changed() {
                    super.changed();
                    int v = (int) parse(text());
                    if (v > 0 && v != wiki)
                        overrides.put(key, v);
                    else
                        overrides.remove(key);
                    save();
                    recompute();
                }
            }, new Coord(X_TRAYS, y));
            content.add(new Label(L10n.get("calc.cheese.wiki_hours", String.valueOf(wiki)), CookbookTheme.small),
                    new Coord(X_EVERY, y + UI.scale(4)));
            y += STAGE_H;
        }
        return y;
    }

    private void recompute() {
        double run = parse(runEvery);
        double head = parse(headroom);
        List<CheeseRackCalculator.Row> all = new ArrayList<>();
        for (RowState rs : rows) {
            CheeseRackCalculator.Row row = new CheeseRackCalculator.Row(rs.cheese, parse(rs.trays), parse(rs.every));
            all.add(row);
            if (rs.perRun == null)
                continue;
            CheeseRackCalculator.Result single = CheeseRackCalculator.calculate(List.of(row), run, head, overrides);
            if (single.rows.isEmpty()) {
                rs.perRun.settext("–");
                rs.first.settext("–");
                for (Label l : rs.racks.values())
                    l.settext("–");
                continue;
            }
            CheeseRackCalculator.RowResult rr = single.rows.get(0);
            rs.perRun.settext(fmt(rr.traysPerRun));
            rs.first.settext(String.valueOf(Math.round(rr.leadHours)));
            for (Map.Entry<CheeseBranch.Place, Label> e : rs.racks.entrySet()) {
                Integer r = rr.racks.get(e.getKey());
                e.getValue().settext((r == null || r == 0) ? "–" : String.valueOf(r));
            }
        }

        CheeseRackCalculator.Result total = CheeseRackCalculator.calculate(all, run, head, overrides);
        for (Map.Entry<CheeseBranch.Place, Label> e : totalRacks.entrySet())
            e.getValue().settext(String.valueOf(total.racks.getOrDefault(e.getKey(), 0)));
        totalAll.settext(L10n.get("calc.cheese.total", String.valueOf(total.totalRacks)));
        circulation.settext(L10n.get("calc.cheese.circulation", String.valueOf(total.traysInCirculation)));
        String unit = L10n.get(period == 1 ? "calc.cheese.per_hour" : "calc.cheese.per_day");
        for (int i = 0; i < CURDS.length; i++) {
            CheeseRackCalculator.Supply supply = total.supplies.get(CURDS[i]);
            curdLabels[i].settext(supply == null ? "" : L10n.get("calc.cheese.curds", CURDS[i],
                    fmt(supply.curdsPerHour * period), String.valueOf(supply.tubs),
                    fmt(supply.milkPerHour() * period), fmt2(supply.rennetPerHour() * period), unit));
        }
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static double parse(String s) {
        try {
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void save() {
        JSONObject o = new JSONObject();
        o.put("runEvery", runEvery);
        o.put("headroom", headroom);
        o.put("period", period);
        JSONArray arr = new JSONArray();
        for (RowState rs : rows) {
            JSONObject r = new JSONObject();
            r.put("cheese", rs.cheese);
            r.put("trays", rs.trays);
            r.put("every", rs.every);
            arr.put(r);
        }
        o.put("rows", arr);
        o.put("overrides", new JSONObject(overrides));
        NConfig.set(NConfig.Key.cheeseRackCalculator, o.toString());
    }

    @SuppressWarnings("unchecked")
    private void load() {
        Object raw = NConfig.get(NConfig.Key.cheeseRackCalculator);
        JSONObject o = null;
        try {
            if (raw instanceof String && !((String) raw).isEmpty())
                o = new JSONObject((String) raw);
            else if (raw instanceof Map)
                o = new JSONObject((Map<String, Object>) raw);
        } catch (JSONException e) {
            o = null;
        }
        if (o == null) {
            rows.add(new RowState(cheeseTypes.get(0), "1", "24"));
            return;
        }
        runEvery = o.optString("runEvery", runEvery);
        headroom = o.optString("headroom", headroom);
        period = o.optInt("period", period) == 1 ? 1 : 24;
        JSONArray arr = o.optJSONArray("rows");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null || !cheeseTypes.contains(r.optString("cheese")))
                    continue;
                rows.add(new RowState(r.getString("cheese"), r.optString("trays", "1"), r.optString("every", "24")));
            }
        }
        JSONObject ov = o.optJSONObject("overrides");
        if (ov != null) {
            for (String k : ov.keySet()) {
                int v = ov.optInt(k, 0);
                if (v > 0)
                    overrides.put(k, v);
            }
        }
    }
}
