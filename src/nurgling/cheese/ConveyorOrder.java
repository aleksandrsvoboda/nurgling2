package nurgling.cheese;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An order of the Cheese Conveyor bot. There is at most one per cheese: trays on a rack carry no
 * owner, so two orders for the same cheese would fight over the same trays.
 * <p>
 * The step counters work like {@link CheeseOrder}'s: the start step counts trays still to be
 * filled with curd, every other step counts trays currently in that stage.
 * <ul>
 * <li>{@link Mode#once}: a fixed number of trays, as in the Cheese Production Bot.</li>
 * <li>{@link Mode#continuous}: {@code trays} every {@code everyHours}. Each run releases the trays
 * earned since the last run into the start step, so batches stay staggered.</li>
 * </ul>
 */
public class ConveyorOrder {
    public enum Mode { once, continuous }

    /** A skipped day or two releases one day of trays, not a lump that would put batches in lockstep. */
    public static final double MAX_CREDIT_HOURS = 24;
    /** Runs are started by hand once or twice a day, so a finished tray can wait this long. */
    public static final double WIP_WAIT_HOURS = 24;
    /** Trays in progress may exceed the plan by this factor before releases stop. */
    public static final double WIP_SLACK = 1.25;

    private final int id;
    private final String cheeseType;
    private Mode mode;
    private int count;
    private double trays;
    private double everyHours;
    private double credit;
    private long lastRelease;
    private int extra;
    private boolean paused;
    private final List<CheeseOrder.StepStatus> status;
    private final Map<String, List<Batch>> batches = new LinkedHashMap<>();

    private ConveyorOrder(int id, String cheeseType, Mode mode, List<CheeseOrder.StepStatus> status) {
        this.id = id;
        this.cheeseType = cheeseType;
        this.mode = mode;
        this.status = status;
    }

    /** New order with its start step, or null for a cheese without a recipe chain. */
    public static ConveyorOrder create(int id, String cheeseType) {
        List<CheeseBranch.Cheese> chain = CheeseBranch.getChainToProduct(cheeseType);
        if (chain == null || chain.isEmpty())
            return null;
        CheeseBranch.Cheese first = chain.get(0);
        List<CheeseOrder.StepStatus> status = new ArrayList<>();
        status.add(new CheeseOrder.StepStatus(first.name, first.place.name(), 0));
        return new ConveyorOrder(id, cheeseType, Mode.once, status);
    }

    public ConveyorOrder(JSONObject json) {
        this.id = json.getInt("id");
        this.cheeseType = json.getString("cheeseType");
        this.mode = "continuous".equals(json.optString("mode")) ? Mode.continuous : Mode.once;
        this.count = json.optInt("count", 0);
        this.trays = json.optDouble("trays", 0);
        this.everyHours = json.optDouble("everyHours", 0);
        this.credit = json.optDouble("credit", 0);
        this.lastRelease = json.optLong("lastRelease", 0);
        this.extra = json.optInt("extra", 0);
        this.paused = json.optBoolean("paused", false);
        this.status = new ArrayList<>();
        JSONArray arr = json.optJSONArray("status");
        if (arr != null)
            for (int i = 0; i < arr.length(); i++)
                status.add(new CheeseOrder.StepStatus(arr.getJSONObject(i)));
        JSONObject stamps = json.optJSONObject("batches");
        if (stamps != null) {
            for (String key : stamps.keySet()) {
                JSONArray list = stamps.optJSONArray(key);
                if (list == null)
                    continue;
                List<Batch> stage = batches.computeIfAbsent(key, k -> new ArrayList<>());
                for (int i = 0; i < list.length(); i++) {
                    JSONObject b = list.optJSONObject(i);
                    if (b != null && b.optInt("count") > 0)
                        stage.add(new Batch(b.getInt("count"), b.optLong("time", 0)));
                }
            }
        }
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("cheeseType", cheeseType);
        obj.put("mode", mode.name());
        obj.put("count", count);
        obj.put("trays", trays);
        obj.put("everyHours", everyHours);
        obj.put("credit", credit);
        obj.put("lastRelease", lastRelease);
        obj.put("extra", extra);
        obj.put("paused", paused);
        JSONArray arr = new JSONArray();
        for (CheeseOrder.StepStatus s : status)
            arr.put(s.toJson());
        obj.put("status", arr);
        JSONObject stamps = new JSONObject();
        for (Map.Entry<String, List<Batch>> stage : batches.entrySet()) {
            if (stage.getValue().isEmpty())
                continue;
            JSONArray list = new JSONArray();
            for (Batch b : stage.getValue()) {
                JSONObject jb = new JSONObject();
                jb.put("count", b.count);
                jb.put("time", b.time);
                list.put(jb);
            }
            stamps.put(stage.getKey(), list);
        }
        obj.put("batches", stamps);
        return obj;
    }

    public int getId() { return id; }
    public String getCheeseType() { return cheeseType; }
    public Mode getMode() { return mode; }
    public int getCount() { return count; }
    public double getTrays() { return trays; }
    public double getEveryHours() { return everyHours; }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public List<CheeseOrder.StepStatus> getStatus() { return status; }
    public boolean isContinuous() { return mode == Mode.continuous; }

    public CheeseOrder.StepStatus startStep() {
        for (CheeseOrder.StepStatus s : status)
            if (s.place.equals(CheeseBranch.Place.start.name()))
                return s;
        CheeseBranch.Cheese first = CheeseBranch.getChainToProduct(cheeseType).get(0);
        CheeseOrder.StepStatus s = new CheeseOrder.StepStatus(first.name, first.place.name(), 0);
        status.add(0, s);
        return s;
    }

    /** One-time trays still waiting to be started on top of the rate. */
    public int extraLeft() {
        return Math.min(extra, startStep().left);
    }

    /** One-time order: add trays to fill. Continuous order: start these trays on top of the rate. */
    public void addTrays(int n) {
        startStep().left += n;
        if (mode == Mode.continuous)
            extra = extraLeft() + n;
        else
            count += n;
    }

    /** Switch to (or update) continuous mode. Trays not started yet stay as extra trays. */
    public void makeContinuous(double trays, double everyHours) {
        if (mode == Mode.once)
            extra = startStep().left;
        this.mode = Mode.continuous;
        this.trays = trays;
        this.everyHours = everyHours;
    }

    public double traysPerHour() {
        return (mode == Mode.continuous && trays > 0 && everyHours > 0) ? trays / everyHours : 0;
    }

    /** Trays on racks or in buffers, i.e. every step except the start step. */
    public int inProgress() {
        int n = 0;
        for (CheeseOrder.StepStatus s : status)
            if (!s.place.equals(CheeseBranch.Place.start.name()))
                n += s.left;
        return n;
    }

    /** Trays the rate needs in progress when every tray waits a day for the next run. */
    public int plannedWip() {
        List<CheeseRackCalculator.Stage> stages = CheeseRackCalculator.stages(cheeseType, null);
        if (stages == null)
            return Integer.MAX_VALUE;
        return (int) Math.ceil(traysPerHour() * CheeseRackCalculator.leadHours(stages, WIP_WAIT_HOURS) * WIP_SLACK);
    }

    /**
     * Earn trays for the hours since the last release and move whole ones into the start step.
     * Returns how many were released.
     */
    public int release(long nowMillis) {
        double perHour = traysPerHour();
        if (perHour <= 0 || paused) {
            lastRelease = nowMillis;
            return 0;
        }
        double hours = lastRelease <= 0 ? MAX_CREDIT_HOURS : Math.max(0, (nowMillis - lastRelease) / 3_600_000.0);
        double cap = perHour * MAX_CREDIT_HOURS;
        credit = Math.min(cap, credit + perHour * hours);
        lastRelease = nowMillis;

        CheeseOrder.StepStatus start = startStep();
        extra = extraLeft();
        int pendingRate = start.left - extra;
        int backlogRoom = (int) Math.floor(cap + 1e-9) - pendingRate;
        int wipRoom = plannedWip() - inProgress() - start.left;
        int n = Math.max(0, Math.min((int) Math.floor(credit + 1e-9), Math.min(backlogRoom, wipRoom)));
        start.left += n;
        credit -= n;
        return n;
    }

    /** Stage counters for trays of {@code cheese} leaving {@code from}: move them to the next step of this chain. */
    public void advance(String cheese, CheeseBranch.Place from, int moved) {
        advance(cheese, from, moved, System.currentTimeMillis());
    }

    public void advance(String cheese, CheeseBranch.Place from, int moved, long nowMillis) {
        List<CheeseBranch.Cheese> chain = CheeseBranch.getChainToProduct(cheeseType);
        if (chain == null)
            return;
        for (int i = 0; i < chain.size() - 1; i++) {
            CheeseBranch.Cheese step = chain.get(i);
            if (step.name.equals(cheese) && step.place == from) {
                CheeseOrder.StepStatus cur = findStep(step.name, step.place);
                if (cur != null)
                    cur.left = Math.max(0, cur.left - moved);
                // The oldest trays in a stage are the ones that ripen first, so they are the ones
                // that just left it.
                takeOldest(step.name, step.place, moved);
                CheeseBranch.Cheese next = chain.get(i + 1);
                CheeseOrder.StepStatus nxt = findStep(next.name, next.place);
                if (nxt == null) {
                    nxt = new CheeseOrder.StepStatus(next.name, next.place.name(), 0);
                    status.add(nxt);
                }
                nxt.left += moved;
                batches(next.name, next.place).add(new Batch(moved, nowMillis));
                return;
            }
        }
    }

    /**
     * When trays entered a stage. Each entry is a batch the bot placed there, oldest first; trays
     * that were already on the racks before this was recorded have {@link Batch#time} 0.
     */
    public static class Batch {
        public int count;
        public final long time;

        Batch(int count, long time) {
            this.count = count;
            this.time = time;
        }
    }

    public List<Batch> batches(String name, CheeseBranch.Place place) {
        return batches.computeIfAbsent(CheeseStageHours.key(name, place), k -> new ArrayList<>());
    }

    /**
     * Batches of a stage, corrected against its counter first: trays counted but never stamped
     * (placed by an older build, imported, or moved by hand) show up as one batch of unknown age.
     */
    public List<Batch> stageBatches(String name, CheeseBranch.Place place) {
        CheeseOrder.StepStatus step = findStep(name, place);
        int left = step == null ? 0 : step.left;
        List<Batch> list = batches(name, place);
        int total = 0;
        for (Batch b : list)
            total += b.count;
        if (total > left)
            takeOldest(name, place, total - left);
        else if (total < left)
            list.add(0, new Batch(left - total, 0));
        return list;
    }

    /** When the oldest trays of a stage arrived, or 0 when that is unknown. */
    public long oldestArrival(String name, CheeseBranch.Place place) {
        List<Batch> list = stageBatches(name, place);
        return list.isEmpty() ? 0 : list.get(0).time;
    }

    private void takeOldest(String name, CheeseBranch.Place place, int count) {
        List<Batch> list = batches(name, place);
        int togo = count;
        while (togo > 0 && !list.isEmpty()) {
            Batch first = list.get(0);
            if (first.count > togo) {
                first.count -= togo;
                return;
            }
            togo -= first.count;
            list.remove(0);
        }
    }

    /** Next step of this chain after {@code cheese} aged in {@code place}, or null at the end of the chain. */
    public CheeseBranch.Cheese nextStep(String cheese, CheeseBranch.Place place) {
        List<CheeseBranch.Cheese> chain = CheeseBranch.getChainToProduct(cheeseType);
        if (chain == null)
            return null;
        for (int i = 0; i < chain.size() - 1; i++)
            if (chain.get(i).name.equals(cheese) && chain.get(i).place == place)
                return chain.get(i + 1);
        return null;
    }

    public CheeseOrder.StepStatus findStep(String name, CheeseBranch.Place place) {
        for (CheeseOrder.StepStatus s : status)
            if (s.name.equals(name) && s.place.equals(place.name()))
                return s;
        return null;
    }

    /** True when {@code place} is where this order's last stage ages. */
    public boolean endsIn(CheeseBranch.Place place) {
        List<CheeseBranch.Cheese> chain = CheeseBranch.getChainToProduct(cheeseType);
        return chain != null && chain.get(chain.size() - 1).place == place;
    }

    /**
     * A finished tray of this order's cheese in the place its last stage ages in, while this order
     * still counts trays waiting there. Counting matters when another order's recipe carries the
     * same cheese further: an order for Jorbonzola must not slice the trays booked to a Midnight
     * Blue order, since Jorbonzola is one of its stages.
     */
    public boolean wantsSlice(CheeseBranch.Place place) {
        if (!endsIn(place))
            return false;
        for (CheeseOrder.StepStatus s : status)
            if (s.name.equals(cheeseType) && s.left > 0)
                return true;
        return false;
    }

    public void sliced() {
        for (CheeseOrder.StepStatus s : status) {
            if (s.name.equals(cheeseType) && s.left > 0) {
                s.left--;
                takeOldest(s.name, CheeseBranch.Place.valueOf(s.place), 1);
                return;
            }
        }
    }
}
