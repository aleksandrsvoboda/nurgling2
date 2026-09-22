package nurgling.cheese;

import nurgling.actions.bots.cheese.CheeseConstants;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Racks needed per place for a steady production rate (Little's law):
 * trays on racks = trays started per hour x hours each tray spends on a rack.
 * A tray that finishes right after a bot run keeps its slot until the next run, so every stage
 * also costs the run interval.
 */
public class CheeseRackCalculator {
    public static final int TRAYS_PER_RACK = 3;
    /** One curding tub makes a curd every 36 minutes. */
    public static final double CURDS_PER_TUB_HOUR = 60.0 / 36.0;

    public static final CheeseBranch.Place[] RACK_PLACES = {
            CheeseBranch.Place.mine, CheeseBranch.Place.outside, CheeseBranch.Place.cellar, CheeseBranch.Place.inside
    };

    public static class Row {
        public final String cheese;
        public final double trays;
        public final double everyHours;

        public Row(String cheese, double trays, double everyHours) {
            this.cheese = cheese;
            this.trays = trays;
            this.everyHours = everyHours;
        }
    }

    public static class Stage {
        public final String name;
        public final CheeseBranch.Place place;
        public final int hours;

        public Stage(String name, CheeseBranch.Place place, int hours) {
            this.name = name;
            this.place = place;
            this.hours = hours;
        }
    }

    public static class RowResult {
        public final Row row;
        public final String curd;
        public final List<Stage> stages;
        public final double traysPerHour;
        public final double traysPerRun;
        public final double leadHours;
        public final Map<CheeseBranch.Place, Double> trays = new EnumMap<>(CheeseBranch.Place.class);
        public final Map<CheeseBranch.Place, Integer> racks = new EnumMap<>(CheeseBranch.Place.class);

        RowResult(Row row, String curd, List<Stage> stages, double traysPerHour, double traysPerRun, double leadHours) {
            this.row = row;
            this.curd = curd;
            this.stages = stages;
            this.traysPerHour = traysPerHour;
            this.traysPerRun = traysPerRun;
            this.leadHours = leadHours;
        }
    }

    public static class Result {
        public final List<RowResult> rows = new ArrayList<>();
        public final Map<CheeseBranch.Place, Double> trays = new EnumMap<>(CheeseBranch.Place.class);
        public final Map<CheeseBranch.Place, Integer> racks = new EnumMap<>(CheeseBranch.Place.class);
        public final Map<String, Double> curdsPerHour = new LinkedHashMap<>();
        public int totalRacks;
        public int traysInCirculation;
    }

    /**
     * Rack stages of a cheese (the start/curd step excluded), or null for an unknown cheese
     * or a stage without known hours.
     */
    public static List<Stage> stages(String cheese, Map<String, Integer> overrides) {
        List<CheeseBranch.Cheese> chain = CheeseBranch.getChainToProduct(cheese);
        if (chain == null || chain.size() < 2)
            return null;
        List<Stage> out = new ArrayList<>();
        for (int i = 1; i < chain.size(); i++) {
            CheeseBranch.Cheese step = chain.get(i);
            int h = CheeseStageHours.hours(step.name, step.place, overrides);
            if (h <= 0)
                return null;
            out.add(new Stage(step.name, step.place, h));
        }
        return out;
    }

    /** Hours from starting a tray until it is finished, counting one full wait per stage. */
    public static double leadHours(List<Stage> stages, double runEveryHours) {
        double total = 0;
        for (Stage s : stages)
            total += s.hours + runEveryHours;
        return total;
    }

    public static int racksFor(double trays, double headroomPct) {
        double needed = trays * (1 + headroomPct / 100.0) / TRAYS_PER_RACK;
        return (int) Math.ceil(needed - 1e-9);
    }

    public static Result calculate(List<Row> rows, double runEveryHours, double headroomPct, Map<String, Integer> overrides) {
        Result res = new Result();
        for (CheeseBranch.Place p : RACK_PLACES)
            res.trays.put(p, 0.0);
        double inHand = 0;

        for (Row row : rows) {
            if (row == null || row.trays <= 0 || row.everyHours <= 0)
                continue;
            List<Stage> stages = stages(row.cheese, overrides);
            if (stages == null)
                continue;
            List<CheeseBranch.Cheese> chain = CheeseBranch.getChainToProduct(row.cheese);
            String curd = chain.get(0).name;
            double perHour = row.trays / row.everyHours;
            RowResult rr = new RowResult(row, curd, stages, perHour, perHour * runEveryHours, leadHours(stages, runEveryHours));
            for (Stage s : stages)
                rr.trays.merge(s.place, perHour * (s.hours + runEveryHours), Double::sum);
            for (Map.Entry<CheeseBranch.Place, Double> e : rr.trays.entrySet()) {
                rr.racks.put(e.getKey(), racksFor(e.getValue(), headroomPct));
                res.trays.merge(e.getKey(), e.getValue(), Double::sum);
            }
            res.curdsPerHour.merge(curd, perHour * CheeseConstants.CURDS_PER_TRAY, Double::sum);
            inHand += rr.traysPerRun;
            res.rows.add(rr);
        }

        double onRacks = 0;
        for (CheeseBranch.Place p : RACK_PLACES) {
            double t = res.trays.get(p);
            onRacks += t;
            int r = racksFor(t, headroomPct);
            res.racks.put(p, r);
            res.totalRacks += r;
        }
        res.traysInCirculation = (int) Math.ceil(onRacks + inHand - 1e-9);
        return res;
    }
}
