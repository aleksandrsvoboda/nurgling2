package nurgling.actions.bots;

import haven.Area;
import haven.Button;
import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Label;
import haven.MCache;
import haven.Pair;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.res.ui.surv.LandSurvey;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.CloseTargetContainer;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.actions.RestoreResources;
import nurgling.actions.Results;
import nurgling.actions.TakeItemsFromPile;
import nurgling.actions.TransferToPiles;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitItems;
import nurgling.tasks.WaitWindow;
import nurgling.tasks.WindowIsClosed;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.HashSet;

public class Leveler implements Action
{
    private static final Coord SOIL_SIZE = new Coord(1, 1);
    private static final int MIN_FREE_SLOTS = 2;
    private static final String SOIL_ITEM = "Soil";
    private static final NAlias SURVOBJ = new NAlias("survobj");
    private static final NAlias STOCKPILE = new NAlias("stockpile");
    private static final NAlias SOIL_PILE = new NAlias("gfx/terobjs/stockpile-soil");
    private static final NAlias SOIL = new NAlias("Soil", "Earthworm");
    private static final NAlias PAVING = new NAlias("gfx/tiles/paving");

    private final HashSet<Long> done = new HashSet<>();
    private final HashSet<Long> skipped = new HashSet<>();

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        while (true) {
            Results rr = new RestoreResources().run(gui);
            if (!rr.IsSuccess()) {
                return Results.ERROR("Leveler: failed to restore resources");
            }

            Gob target = pickNearestPendingSurvey(gui);
            if (target == null) {
                gui.msg("Leveler: finished. Completed=" + done.size() + " skipped=" + skipped.size());
                return Results.SUCCESS();
            }

            Results sr = handleSurvey(gui, target);
            if (!sr.IsSuccess()) {
                return sr;
            }
        }
    }

    private Gob pickNearestPendingSurvey(NGameUI gui) throws InterruptedException
    {
        Gob player = NUtils.player();
        if (player == null) return null;
        ArrayList<Gob> surveys = Finder.findGobs(SURVOBJ);
        Gob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Gob s : surveys) {
            if (done.contains(s.id) || skipped.contains(s.id)) continue;
            double d = s.rc.dist(player.rc);
            if (d < bestDist) { bestDist = d; best = s; }
        }
        return best;
    }

    private Results handleSurvey(NGameUI gui, Gob surveyGob) throws InterruptedException
    {
        long surveyId = surveyGob.id;

        new PathFinder(surveyGob.rc).run(gui);

        if (NUtils.getGameUI().getWindow("Land survey") == null) {
            NUtils.rclickGob(surveyGob);
            NUtils.addTask(new WaitWindow("Land survey"));
        }
        Window wnd = NUtils.getGameUI().getWindow("Land survey");
        if (!(wnd instanceof LandSurvey)) {
            skipped.add(surveyId);
            return Results.SUCCESS();
        }
        LandSurvey survey = (LandSurvey) wnd;

        if (varea(survey) != null && stockpileInside(gui, varea(survey))) {
            gui.msg("Leveler: survey " + surveyId + " contains a stockpile - skipping");
            closeWindow(survey);
            skipped.add(surveyId);
            return Results.SUCCESS();
        }

        Label wlbl = findWlbl(survey);
        if (wlbl == null) {
            skipped.add(surveyId);
            closeWindow(survey);
            return Results.SUCCESS();
        }
        waitForLabel(wlbl);
        int soilRequired = parseAfter(wlbl.text(), "Units of soil required:");
        if (soilRequired > 0) {
            int invSoil = gui.getInventory().getItems(SOIL).size();
            int need = soilRequired - invSoil;
            if (need > 0) {
                closeWindow(survey);
                Results pr = pullSoilFromTake(gui, need);
                if (!pr.IsSuccess()) {
                    return Results.ERROR("Leveler: insufficient soil for fill survey (need " + need + ")");
                }
                return Results.SUCCESS();
            }
        }

        return digLoop(gui, surveyId, survey);
    }

    private Results digLoop(NGameUI gui, long surveyId, LandSurvey survey) throws InterruptedException
    {
        String prevLabel = null;
        boolean didDigThisCycle = false;
        while (true) {
            Label wlbl = findWlbl(survey);
            Button digBtn = findButton(survey, "Dig");
            Button removeBtn = findButton(survey, "Remove");
            if (wlbl == null || digBtn == null || removeBtn == null) {
                skipped.add(surveyId);
                closeWindow(survey);
                return Results.SUCCESS();
            }
            waitForLabel(wlbl);
            waitForMapUpdate(survey);
            String curLabel = wlbl.text();
            long diff = surveyDiffUnits(survey);

            if (didDigThisCycle && prevLabel != null && prevLabel.equals(curLabel) && diff == 0) {
                removeBtn.click();
                NUtils.addTask(new WindowIsClosed(survey));
                done.add(surveyId);
                disposeIfNeeded(gui, true);
                return Results.SUCCESS();
            }
            prevLabel = curLabel;

            digBtn.click();
            didDigThisCycle = true;

            final Gob player = NUtils.player();
            if (player == null) return Results.FAIL();

            NUtils.addTask(new NTask()
            {
                int idleCount = 0;

                @Override
                public boolean check()
                {
                    if (player.pose().contains("idle")) idleCount++;
                    else idleCount = 0;
                    if (idleCount >= 360) return true;
                    if (NUtils.getStamina() < 0.25 || NUtils.getEnergy() < 0.3) return true;
                    return gui.getInventory().calcFreeSpace() < MIN_FREE_SLOTS;
                }
            });

            if (NUtils.getStamina() < 0.25 || NUtils.getEnergy() < 0.3) {
                closeWindow(survey);
                return Results.SUCCESS();
            }

            int free = gui.getInventory().getNumberFreeCoord(SOIL_SIZE);
            if (free < MIN_FREE_SLOTS) {
                closeWindow(survey);
                Results dr = disposeIfNeeded(gui, false);
                if (!dr.IsSuccess()) {
                    return Results.ERROR("Leveler: no soil disposal route available");
                }
                Gob sg = Finder.findGob(surveyId);
                if (sg == null) {
                    done.add(surveyId);
                    return Results.SUCCESS();
                }
                new PathFinder(sg.rc).run(gui);
                NUtils.rclickGob(sg);
                NUtils.addTask(new WaitWindow("Land survey"));
                Window nw = NUtils.getGameUI().getWindow("Land survey");
                if (!(nw instanceof LandSurvey)) {
                    return Results.SUCCESS();
                }
                survey = (LandSurvey) nw;
                prevLabel = null;
                didDigThisCycle = false;
            }
        }
    }

    private static long surveyDiffUnits(LandSurvey survey)
    {
        try {
            haven.res.ui.surv.Data d = survey.data;
            if (d == null || d.dz == null) return -1;
            haven.MCache map = NUtils.getGameUI().map.glob.map;
            long total = 0;
            for (Coord vc : d.varea) {
                int vz = Math.round((float) map.getfz(vc) * d.gran);
                int tz = d.dz[d.varea.ridx(vc)];
                total += Math.abs(tz - vz);
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void waitForMapUpdate(LandSurvey survey) throws InterruptedException
    {
        final int startSeq = survey.data != null ? survey.data.seq : -1;
        NUtils.addTask(new NTask()
        {
            int ticks = 0;
            @Override
            public boolean check()
            {
                ticks++;
                if (ticks > 40) return true;
                return survey.data != null && survey.data.seq != startSeq;
            }
        });
    }

    private Results pullSoilFromTake(NGameUI gui, int need) throws InterruptedException
    {
        NArea take = NContext.findIn(SOIL_ITEM);
        if (take == null) take = NContext.findInGlobal(SOIL_ITEM);
        if (take == null) return Results.FAIL();
        NUtils.navigateToArea(take);

        for (Gob pile : Finder.findGobs(take, SOIL_PILE)) {
            int invSoil = gui.getInventory().getItems(SOIL).size();
            if (invSoil >= need) return Results.SUCCESS();
            int free = gui.getInventory().getNumberFreeCoord(SOIL_SIZE);
            int toTake = Math.min(need - invSoil, free);
            if (toTake <= 0) break;
            PathFinder pf = new PathFinder(pile);
            pf.isHardMode = true;
            pf.run(gui);
            new OpenTargetContainer("Stockpile", pile).run(gui);
            if (gui.getStockpile() != null) {
                new TakeItemsFromPile(pile, gui.getStockpile(), toTake).run(gui);
                new CloseTargetContainer("Stockpile").run(gui);
            }
        }
        int finalInv = gui.getInventory().getItems(SOIL).size();
        return finalInv >= need ? Results.SUCCESS() : Results.FAIL();
    }

    private Results disposeIfNeeded(NGameUI gui, boolean bestEffort) throws InterruptedException
    {
        if (gui.getInventory().getItems(SOIL).isEmpty()) return Results.SUCCESS();

        NArea put = NContext.findOut(SOIL_ITEM, 1);
        if (put == null) put = NContext.findOutGlobal(SOIL_ITEM, 1, gui);
        if (put != null) {
            NUtils.navigateToArea(put);
            new TransferToPiles(put.getRCArea(), SOIL_ITEM, 0).run(gui);
            if (gui.getInventory().getItems(SOIL).isEmpty()) return Results.SUCCESS();
        }

        NContext ctx = new NContext(gui);
        NArea dump = ctx.getSpecArea(Specialisation.SpecName.soilDump);
        if (dump != null) {
            Pair<Coord2d, Coord2d> rca = dump.getRCArea();
            if (rca != null) {
                Coord2d center = rca.b.sub(rca.a).div(2).add(rca.a);
                new PathFinder(center).run(gui);
                ArrayList<WItem> soils = gui.getInventory().getItems(SOIL);
                for (WItem w : soils) {
                    NUtils.drop(w);
                }
                if (!soils.isEmpty()) {
                    NUtils.addTask(new WaitItems(gui.getInventory(), SOIL, 0));
                }
                if (gui.getInventory().getItems(SOIL).isEmpty()) return Results.SUCCESS();
            }
        }

        return bestEffort ? Results.SUCCESS() : Results.FAIL();
    }

    private static Area varea(LandSurvey survey)
    {
        try {
            return survey.data != null ? survey.data.varea : null;
        } catch (NullPointerException e) {
            return null;
        }
    }

    private static boolean stockpileInside(NGameUI gui, Area varea) throws InterruptedException
    {
        ArrayList<Gob> piles = Finder.findGobs(STOCKPILE);
        for (Gob sp : piles) {
            Coord tc = sp.rc.floor(MCache.tilesz);
            if (varea.contains(tc)) return true;
        }
        return false;
    }

    private static Label findWlbl(LandSurvey survey)
    {
        for (Widget child : survey.children()) {
            if (child instanceof Label) {
                String t = ((Label) child).text();
                if (t.contains("Units of soil left") || t.contains("Units of soil req")) {
                    return (Label) child;
                }
            }
        }
        return null;
    }

    private static Button findButton(LandSurvey survey, String label)
    {
        for (Widget child : survey.children()) {
            if (child instanceof Button) {
                Button b = (Button) child;
                if (b.text != null && b.text.text != null && b.text.text.equals(label)) return b;
            }
        }
        return null;
    }

    private static void waitForLabel(Label label) throws InterruptedException
    {
        final Label fl = label;
        NUtils.addTask(new NTask()
        {
            @Override
            public boolean check()
            {
                return !fl.text().equals("...");
            }
        });
    }

    private static int parseAfter(String label, String prefix)
    {
        int idx = label.indexOf(prefix);
        if (idx < 0) return 0;
        String rem = label.substring(idx + prefix.length()).trim();
        int end = 0;
        while (end < rem.length() && Character.isDigit(rem.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(rem.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void closeWindow(Window wnd) throws InterruptedException
    {
        if (wnd == null || !NUtils.getGameUI().isWindowExist(wnd)) return;
        wnd.wdgmsg("close");
        NUtils.addTask(new WindowIsClosed(wnd));
    }
}
