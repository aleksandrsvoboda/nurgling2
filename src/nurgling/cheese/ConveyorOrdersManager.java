package nurgling.cheese;

import nurgling.NConfig;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Orders of the Cheese Conveyor bot, stored in {@code cheese_conveyor.nurgling.json}. The Cheese
 * Production Bot's {@code cheese_orders.nurgling.json} is never read or written here.
 */
public class ConveyorOrdersManager {
    public static final String FILE_NAME = "cheese_conveyor.nurgling.json";

    private final Map<Integer, ConveyorOrder> orders = new HashMap<>();
    private final String path;

    public ConveyorOrdersManager() {
        this.path = NConfig.getGlobalInstance().getProfileAwarePath(FILE_NAME);
        load();
    }

    public void load() {
        orders.clear();
        String content = NFileUtils.readWithBackupFallback(path);
        if (content == null || content.isEmpty())
            return;
        try {
            JSONArray array = new JSONObject(content).getJSONArray("orders");
            for (int i = 0; i < array.length(); i++) {
                ConveyorOrder order = new ConveyorOrder(array.getJSONObject(i));
                if (CheeseBranch.getChainToProduct(order.getCheeseType()) != null)
                    orders.put(order.getId(), order);
            }
        } catch (org.json.JSONException e) {
            System.err.println("[ConveyorOrdersManager] Failed to parse orders file (corrupt JSON): " + e.getMessage());
        }
    }

    public void save() {
        JSONArray arr = new JSONArray();
        for (ConveyorOrder order : orders.values())
            arr.put(order.toJson());
        JSONObject main = new JSONObject();
        main.put("orders", arr);
        try {
            NFileUtils.writeAtomically(path, main.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<Integer, ConveyorOrder> getOrders() {
        return orders;
    }

    public String getPath() {
        return path;
    }

    public ConveyorOrder byCheese(String cheeseType) {
        for (ConveyorOrder order : orders.values())
            if (order.getCheeseType().equals(cheeseType))
                return order;
        return null;
    }

    /** The order for {@code cheeseType}, created (one-time, empty) when there is none yet. */
    public ConveyorOrder getOrCreate(String cheeseType) {
        ConveyorOrder existing = byCheese(cheeseType);
        if (existing != null)
            return existing;
        int id = orders.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
        ConveyorOrder order = ConveyorOrder.create(id, cheeseType);
        if (order != null)
            orders.put(id, order);
        return order;
    }

    public void delete(int id) {
        orders.remove(id);
    }

    /**
     * The order a tray of {@code cheese} that has finished aging in {@code place} belongs to: first
     * an order that counts trays in that stage, then any continuous order whose chain moves it on
     * (its counters may have drifted when trays were moved by hand).
     */
    public ConveyorOrder orderFor(String cheese, CheeseBranch.Place place) {
        for (ConveyorOrder order : orders.values()) {
            CheeseOrder.StepStatus step = order.findStep(cheese, place);
            if (step != null && step.left > 0)
                return order;
        }
        for (ConveyorOrder order : orders.values())
            if (order.isContinuous() && order.nextStep(cheese, place) != null)
                return order;
        return null;
    }

    /** The order that wants a finished tray of {@code cheese} in {@code place} sliced, or null. */
    public ConveyorOrder sliceOrder(String cheese, CheeseBranch.Place place) {
        ConveyorOrder order = byCheese(cheese);
        return (order != null && order.wantsSlice(place)) ? order : null;
    }
}
