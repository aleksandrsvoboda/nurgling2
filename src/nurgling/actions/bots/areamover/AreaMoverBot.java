package nurgling.actions.bots.areamover;

import haven.Resource;
import haven.UI;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.bots.SelectArea;
import nurgling.areas.NArea;
import nurgling.conf.NAreaMoverProp;
import nurgling.i18n.L10n;
import nurgling.tasks.WaitCheckable;
import nurgling.widgets.bots.AreaMoverWnd;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.TreeMap;

/**
 * Area Mover: empties every stockpile and storage container in a source area and puts the items
 * into piles and containers in a destination area. In a scenario the two areas come from the
 * step's settings; from the bot menu a start window asks for them.
 */
public class AreaMoverBot implements Action {
    public static final String FROM_AREA = "fromAreaId";
    public static final String TO_AREA = "toAreaId";
    public static final String CREATE_PILES = "createPiles";

    private final Integer fromAreaId;
    private final Integer toAreaId;
    private final boolean createPiles;

    public AreaMoverBot() {
        this(null);
    }

    // The bot menu builds one instance and reuses it for every click, so a manual run's choices
    // stay local to run() and never land in these fields.
    public AreaMoverBot(Map<String, Object> settings) {
        fromAreaId = readId(settings, FROM_AREA);
        toAreaId = readId(settings, TO_AREA);
        Object piles = (settings == null) ? null : settings.get(CREATE_PILES);
        createPiles = !(piles instanceof Boolean) || (Boolean) piles;
    }

    private static Integer readId(Map<String, Object> settings, String key) {
        if (settings == null)
            return null;
        Object v = settings.get(key);
        return (v instanceof Number) ? ((Number) v).intValue() : null;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NArea from;
        NArea to;
        boolean piles;
        if (fromAreaId != null && toAreaId != null) {
            from = gui.map.glob.map.areas.get(fromAreaId);
            if (from == null)
                return Results.ERROR("Area Mover: source area not found: " + fromAreaId);
            to = gui.map.glob.map.areas.get(toAreaId);
            if (to == null)
                return Results.ERROR("Area Mover: destination area not found: " + toAreaId);
            piles = createPiles;
        } else {
            NAreaMoverProp prop = NAreaMoverProp.get(NUtils.getUI().sessInfo);
            AreaMoverWnd wnd = null;
            int fromId, toId;
            try {
                wnd = (prop == null)
                        ? new AreaMoverWnd(AreaMoverWnd.SELECT_ON_MAP, AreaMoverWnd.SELECT_ON_MAP, true)
                        : new AreaMoverWnd(prop.fromAreaId, prop.toAreaId, prop.createPiles);
                NUtils.getUI().core.addTask(new WaitCheckable(gui.add(wnd, UI.scale(200, 200))));
                if (!wnd.started())
                    return Results.SUCCESS();
                fromId = wnd.fromAreaId();
                toId = wnd.toAreaId();
                piles = wnd.createPiles();
            } finally {
                if (wnd != null)
                    wnd.destroy();
            }
            if (prop != null) {
                prop.fromAreaId = fromId;
                prop.toAreaId = toId;
                prop.createPiles = piles;
                NAreaMoverProp.set(prop);
            }
            from = resolve(gui, fromId, L10n.get("areamover.select_from"), "baubles/inputArea");
            to = resolve(gui, toId, L10n.get("areamover.select_to"), "baubles/outputArea");
            if (from == null || to == null)
                return Results.ERROR("Area Mover: area not found");
        }

        if (from == to)
            return Results.ERROR("Area Mover: source and destination are the same area");
        if (AreaScan.overlap(from, to))
            return Results.ERROR("Area Mover: source and destination areas overlap");
        if (gui.vhand != null)
            return Results.ERROR("Area Mover: empty your hand first");

        return move(gui, from, to, piles);
    }

    private Results move(NGameUI gui, NArea from, NArea to, boolean piles) throws InterruptedException {
        Cargo cargo = new Cargo(gui);
        LoadFromArea load = new LoadFromArea(from, cargo);
        UnloadToArea unload = new UnloadToArea(to, cargo, load.stockpileable, piles);
        Map<String, Integer> moved = new TreeMap<>();

        while (true) {
            Results loaded = load.run(gui);
            if (!loaded.IsSuccess())
                return loaded;
            Map<String, Integer> carried = cargo.current(gui);
            if (carried.isEmpty()) {
                if (load.exhausted)
                    break;
                return Results.ERROR("Area Mover: no free inventory space to carry anything");
            }

            Results unloaded = unload.run(gui);
            Map<String, Integer> left = cargo.current(gui);
            for (Map.Entry<String, Integer> e : carried.entrySet())
                moved.merge(e.getKey(), e.getValue() - left.getOrDefault(e.getKey(), 0), Integer::sum);
            if (!unloaded.IsSuccess()) {
                report(gui, moved, unload.pilesBuilt);
                gui.error(L10n.get("areamover.dest_full"));
                return unloaded;
            }
            if (load.exhausted)
                break;
        }

        report(gui, moved, unload.pilesBuilt);
        if (load.stuckCount() > 0)
            gui.msg(String.format(L10n.get("areamover.stuck"), load.stuckCount()));
        return Results.SUCCESS();
    }

    private static NArea resolve(NGameUI gui, int areaId, String prompt, String bauble) throws InterruptedException {
        if (areaId != AreaMoverWnd.SELECT_ON_MAP)
            return gui.map.glob.map.areas.get(areaId);
        gui.msg(prompt);
        BufferedImage img = Resource.loadsimg(bauble);
        SelectArea select = new SelectArea(img);
        select.run(gui);
        if (select.result == null)
            return null;
        NArea area = new NArea(prompt);
        area.space = select.result;
        area.lastLocalChange = System.currentTimeMillis();
        area.grids_id.clear();
        area.grids_id.addAll(area.space.space.keySet());
        return area;
    }

    private static void report(NGameUI gui, Map<String, Integer> moved, int pilesBuilt) {
        int total = 0;
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, Integer> e : moved.entrySet()) {
            if (e.getValue() <= 0)
                continue;
            total += e.getValue();
            if (detail.length() > 0)
                detail.append(", ");
            detail.append(e.getValue()).append(" ").append(e.getKey());
        }
        System.out.println("[AreaMover] moved " + total + " items (" + detail + "), piles built: " + pilesBuilt);
        gui.msg(String.format(L10n.get("areamover.report"), total, pilesBuilt));
    }
}
