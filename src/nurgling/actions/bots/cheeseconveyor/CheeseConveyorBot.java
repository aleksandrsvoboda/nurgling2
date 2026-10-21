package nurgling.actions.bots.cheeseconveyor;

import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.FreeInventory2;
import nurgling.actions.Results;
import nurgling.areas.NContext;
import nurgling.cheese.CheeseBranch;
import nurgling.cheese.ConveyorOrder;
import nurgling.cheese.ConveyorOrdersManager;

import java.util.*;

/**
 * Cheese Conveyor bot. Same pipeline as {@link nurgling.actions.bots.CheeseProductionBot}, run from
 * its own orders ({@link ConveyorOrdersManager}): continuous orders release the trays earned since
 * the last run, so a little cheese is started, and finished, every run.
 * <ol>
 * <li>Release trays for continuous orders.</li>
 * <li>Clear ready cheese from all racks into buffers and record free rack slots.</li>
 * <li>Slice finished cheese and move aging cheese to its next place.</li>
 * <li>Start new curd trays in the first-stage slots that are left, shared between the orders
 * that start in the same place.</li>
 * </ol>
 */
public class CheeseConveyorBot implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        ConveyorOrdersManager orders = new ConveyorOrdersManager();
        if (orders.getOrders().isEmpty()) {
            gui.msg("No Cheese Conveyor orders. Add them in Nurgling Settings > Bots > Cheese Conveyor.");
            return Results.SUCCESS();
        }

        long now = System.currentTimeMillis();
        for (ConveyorOrder order : orders.getOrders().values())
            order.release(now);
        orders.save();

        ConveyorClearRacks clear = new ConveyorClearRacks(orders);
        if (!clear.run(gui).IsSuccess())
            gui.error("Failed to clear racks and record capacity");
        Map<CheeseBranch.Place, Integer> rackCapacity = clear.getLastRecordedCapacity();

        ConveyorProcessBuffers buffers = new ConveyorProcessBuffers(orders, rackCapacity, clear.getBufferEmptinessMap());
        if (!buffers.run(gui).IsSuccess())
            gui.error("Failed to process cheese from buffer containers");
        for (Map.Entry<CheeseBranch.Place, Integer> moved : buffers.getTraysMovedToAreas().entrySet())
            rackCapacity.put(moved.getKey(), Math.max(0, rackCapacity.getOrDefault(moved.getKey(), 0) - moved.getValue()));

        startNewTrays(gui, orders, rackCapacity);

        orders.save();
        new FreeInventory2(new NContext(gui)).run(gui);
        return Results.SUCCESS();
    }

    /**
     * Fill curd trays for the orders waiting to start, highest priority first: a whole priority is
     * served, curds and first-stage rack slots, before the next one gets a look in. Orders that
     * share a priority split the free slots of their first-stage place in proportion to what each
     * is waiting to start.
     */
    private void startNewTrays(NGameUI gui, ConveyorOrdersManager orders, Map<CheeseBranch.Place, Integer> rackCapacity) throws InterruptedException {
        List<ConveyorOrder> waiting = new ArrayList<>();
        Map<Integer, CheeseBranch.Place> firstPlace = new HashMap<>();
        for (ConveyorOrder order : orders.sorted()) {
            if (order.startStep().left <= 0)
                continue;
            List<CheeseBranch.Cheese> chain = CheeseBranch.getChainToProduct(order.getCheeseType());
            if (chain == null || chain.size() < 2)
                continue;
            waiting.add(order);
            firstPlace.put(order.getId(), chain.get(1).place);
        }

        for (int from = 0; from < waiting.size(); ) {
            int to = from;
            while (to < waiting.size() && waiting.get(to).getPriority() == waiting.get(from).getPriority())
                to++;
            List<ConveyorOrder> tier = waiting.subList(from, to);
            from = to;

            // Split this priority's share of each place before anyone starts filling trays.
            Map<Integer, Integer> allotted = new HashMap<>();
            Map<CheeseBranch.Place, List<ConveyorOrder>> byPlace = new EnumMap<>(CheeseBranch.Place.class);
            for (ConveyorOrder order : tier)
                byPlace.computeIfAbsent(firstPlace.get(order.getId()), k -> new ArrayList<>()).add(order);
            for (Map.Entry<CheeseBranch.Place, List<ConveyorOrder>> entry : byPlace.entrySet()) {
                List<ConveyorOrder> here = entry.getValue();
                int[] demand = new int[here.size()];
                for (int i = 0; i < demand.length; i++)
                    demand[i] = here.get(i).startStep().left;
                int[] share = shareSlots(rackCapacity.getOrDefault(entry.getKey(), 0), demand);
                for (int i = 0; i < here.size(); i++)
                    allotted.put(here.get(i).getId(), share[i]);
            }

            for (ConveyorOrder order : tier) {
                int allot = allotted.getOrDefault(order.getId(), 0);
                if (allot <= 0)
                    continue;
                CheeseBranch.Place place = firstPlace.get(order.getId());
                ConveyorStartCurds start = new ConveyorStartCurds(order, allot, orders);
                if (!start.run(gui).IsSuccess())
                    gui.error("Failed to start " + order.getCheeseType() + " curd trays");
                rackCapacity.put(place, Math.max(0, rackCapacity.getOrDefault(place, 0) - start.getPlaced()));
            }
        }
    }

    /**
     * Split {@code slots} free rack slots between orders in proportion to their demand; leftover
     * slots go to the largest remainders. Nobody gets more than asked for.
     */
    static int[] shareSlots(int slots, int[] demand) {
        int[] share = new int[demand.length];
        long total = 0;
        for (int d : demand)
            total += Math.max(0, d);
        if (slots <= 0 || total == 0)
            return share;
        if (total <= slots) {
            for (int i = 0; i < demand.length; i++)
                share[i] = Math.max(0, demand[i]);
            return share;
        }
        double[] remainder = new double[demand.length];
        int given = 0;
        for (int i = 0; i < demand.length; i++) {
            double exact = (double) slots * Math.max(0, demand[i]) / total;
            share[i] = (int) Math.floor(exact);
            remainder[i] = exact - share[i];
            given += share[i];
        }
        while (given < slots) {
            int best = -1;
            for (int i = 0; i < demand.length; i++)
                if (share[i] < demand[i] && (best < 0 || remainder[i] > remainder[best]))
                    best = i;
            if (best < 0)
                break;
            share[best]++;
            remainder[best] = -1;
            given++;
        }
        return share;
    }
}
