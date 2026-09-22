package nurgling.widgets.calculators;

import haven.*;
import nurgling.NConfig;
import nurgling.cheese.CheeseBranch;
import nurgling.cheese.CheeseRackCalculator;
import nurgling.cheese.CheeseStageHours;
import nurgling.i18n.L10n;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;

/**
 * Cheese rack calculator tab: how many racks each area needs for a steady rate of trays.
 * Everything is in trays, racks and hours. Inputs are remembered in {@link NConfig.Key#cheeseRackCalculator}.
 */
public class CheeseRackCalculatorPanel extends Widget {
    private static final int ROW_H = UI.scale(26);
    private static final int STAGE_H = UI.scale(22);
    private static final int X_CHEESE = 0;
    private static final int X_TRAYS = UI.scale(165);
    private static final int X_EVERY = UI.scale(210);
    private static final int X_PER_RUN = UI.scale(258);
    private static final int X_PLACE0 = UI.scale(310);
    private static final int PLACE_W = UI.scale(46);
    private static final int X_FIRST = UI.scale(500);
    private static final int X_STAGES_BTN = UI.scale(548);
    private static final int X_REMOVE_BTN = UI.scale(582);
    private static final int ENTRY_W = UI.scale(40);
    private static final int SMALL_BTN_W = UI.scale(30);
    private static final String[] CURDS = {"Cow's Curd", "Sheep's Curd", "Goat's Curd"};

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
    private String runEvery = "12";
    private String headroom = "0";
    private boolean rebuildPending = false;

    private final Scrollport scroll;
    private final Widget content;
    private final Map<CheeseBranch.Place, Label> totalRacks = new EnumMap<>(CheeseBranch.Place.class);
    private final Label totalAll;
    private final Label circulation;
    private final Label[] curdLabels = new Label[CURDS.length];

    public CheeseRackCalculatorPanel(Coord sz) {
        super(sz);
        load();

        int y = 0;
        Widget prev = add(new Label(L10n.get("calc.cheese.run_every")), new Coord(0, y + UI.scale(4)));
        prev = add(new TextEntry(ENTRY_W, runEvery) {
            @Override
            protected void changed() {
                super.changed();
                runEvery = text();
                save();
                recompute();
            }
        }, prev.pos("ur").adds(5, -4));
        prev = add(new Label(L10n.get("calc.cheese.hours")), prev.pos("ur").adds(4, 4));
        prev = add(new Label(L10n.get("calc.cheese.headroom")), prev.pos("ur").adds(20, 0));
        prev = add(new TextEntry(ENTRY_W, headroom) {
            @Override
            protected void changed() {
                super.changed();
                headroom = text();
                save();
                recompute();
            }
        }, prev.pos("ur").adds(5, -4));
        add(new Label("%"), prev.pos("ur").adds(4, 4));

        y += UI.scale(30);
        add(new Label(L10n.get("calc.cheese.col.cheese")), new Coord(X_CHEESE, y));
        add(new Label(L10n.get("calc.cheese.col.trays")), new Coord(X_TRAYS, y));
        add(new Label(L10n.get("calc.cheese.col.every")), new Coord(X_EVERY, y));
        add(new Label(L10n.get("calc.cheese.col.per_run")), new Coord(X_PER_RUN, y));
        for (int i = 0; i < CheeseRackCalculator.RACK_PLACES.length; i++)
            add(new Label(placeName(CheeseRackCalculator.RACK_PLACES[i])), new Coord(placeX(i), y));
        add(new Label(L10n.get("calc.cheese.col.first")), new Coord(X_FIRST, y));

        y += UI.scale(22);
        int listH = sz.y - y - UI.scale(120);
        scroll = add(new Scrollport(new Coord(sz.x, listH)), new Coord(0, y));
        content = new Widget(new Coord(sz.x - UI.scale(20), UI.scale(20))) {
            @Override
            public void pack() {
                resize(contentsz());
            }
        };
        scroll.cont.add(content, Coord.z);

        y += listH + UI.scale(6);
        add(new Label(L10n.get("calc.cheese.racks_needed")), new Coord(X_CHEESE, y));
        for (int i = 0; i < CheeseRackCalculator.RACK_PLACES.length; i++)
            totalRacks.put(CheeseRackCalculator.RACK_PLACES[i], add(new Label("0"), new Coord(placeX(i), y)));
        totalAll = add(new Label(""), new Coord(X_FIRST, y));

        y += UI.scale(24);
        add(new Button(UI.scale(120), L10n.get("calc.cheese.add"), () -> {
            rows.add(new RowState(cheeseTypes.get(0), "1", "24"));
            save();
            rebuildPending = true;
        }), new Coord(0, y));
        add(new Button(UI.scale(150), L10n.get("calc.cheese.reset_hours"), () -> {
            overrides.clear();
            save();
            rebuildPending = true;
        }), new Coord(UI.scale(130), y));

        y += UI.scale(32);
        circulation = add(new Label(""), new Coord(0, y));
        for (int i = 0; i < CURDS.length; i++)
            curdLabels[i] = add(new Label(""), new Coord(0, y + UI.scale(16) * (i + 1)));
        add(new Label(L10n.get("calc.cheese.hint")), new Coord(UI.scale(300), y));

        rebuild();
    }

    private static int placeX(int i) {
        return X_PLACE0 + i * PLACE_W;
    }

    private static String placeName(CheeseBranch.Place place) {
        return L10n.get("calc.cheese.place." + place.name());
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
        for (RowState row : rows) {
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
        Dropbox<String> cheese = new Dropbox<String>(UI.scale(155), Math.min(16, cheeseTypes.size()), UI.scale(16)) {
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
        content.add(cheese, new Coord(X_CHEESE, y + UI.scale(3)));

        content.add(new TextEntry(ENTRY_W, row.trays) {
            @Override
            protected void changed() {
                super.changed();
                row.trays = text();
                save();
                recompute();
            }
        }, new Coord(X_TRAYS, y + UI.scale(2)));
        content.add(new TextEntry(ENTRY_W, row.every) {
            @Override
            protected void changed() {
                super.changed();
                row.every = text();
                save();
                recompute();
            }
        }, new Coord(X_EVERY, y + UI.scale(2)));

        row.perRun = content.add(new Label(""), new Coord(X_PER_RUN, y + UI.scale(5)));
        row.racks.clear();
        for (int i = 0; i < CheeseRackCalculator.RACK_PLACES.length; i++)
            row.racks.put(CheeseRackCalculator.RACK_PLACES[i], content.add(new Label(""), new Coord(placeX(i), y + UI.scale(5))));
        row.first = content.add(new Label(""), new Coord(X_FIRST, y + UI.scale(5)));

        content.add(new Button(SMALL_BTN_W, row.expanded ? "▼" : "▶", () -> {
            row.expanded = !row.expanded;
            rebuildPending = true;
        }), new Coord(X_STAGES_BTN, y));
        content.add(new Button(SMALL_BTN_W, "x", () -> {
            rows.remove(row);
            save();
            rebuildPending = true;
        }), new Coord(X_REMOVE_BTN, y));
    }

    private int addStages(RowState row, int y) {
        List<CheeseRackCalculator.Stage> stages = CheeseRackCalculator.stages(row.cheese, overrides);
        if (stages == null) {
            content.add(new Label(L10n.get("calc.cheese.no_data")), new Coord(UI.scale(15), y + UI.scale(3)));
            return y + STAGE_H;
        }
        for (CheeseRackCalculator.Stage stage : stages) {
            String key = CheeseStageHours.key(stage.name, stage.place);
            int wiki = CheeseStageHours.defaultHours(stage.name, stage.place);
            content.add(new Label(stage.name + " (" + placeName(stage.place) + ")"), new Coord(UI.scale(15), y + UI.scale(3)));
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
            content.add(new Label(L10n.get("calc.cheese.wiki_hours", String.valueOf(wiki))), new Coord(X_EVERY + UI.scale(5), y + UI.scale(3)));
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
                e.getValue().settext(r == null ? "–" : String.valueOf(r));
            }
        }

        CheeseRackCalculator.Result total = CheeseRackCalculator.calculate(all, run, head, overrides);
        for (Map.Entry<CheeseBranch.Place, Label> e : totalRacks.entrySet())
            e.getValue().settext(String.valueOf(total.racks.getOrDefault(e.getKey(), 0)));
        totalAll.settext(L10n.get("calc.cheese.total", String.valueOf(total.totalRacks)));
        circulation.settext(L10n.get("calc.cheese.circulation", String.valueOf(total.traysInCirculation)));
        for (int i = 0; i < CURDS.length; i++) {
            Double perHour = total.curdsPerHour.get(CURDS[i]);
            curdLabels[i].settext(perHour == null ? "" : L10n.get("calc.cheese.curds", CURDS[i], fmt(perHour),
                    fmt(perHour / CheeseRackCalculator.CURDS_PER_TUB_HOUR)));
        }
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
