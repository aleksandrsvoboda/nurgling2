package nurgling.cheese;

import nurgling.NConfig;
import nurgling.profiles.ConfigFactory;
import nurgling.profiles.ProfileAwareService;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class CheeseOrdersManager implements ProfileAwareService {
    private final Map<Integer, CheeseOrder> orders = new HashMap<>();
    private String genus;
    private String configPath;

    public CheeseOrdersManager() {
        this.configPath = NConfig.getGlobalInstance().getCheeseOrdersPath();
        loadOrders();
    }

    /**
     * Constructor for profile-aware initialization
     */
    public CheeseOrdersManager(String genus) {
        this.genus = genus;
        initializeForProfile(genus);
    }

    // ProfileAwareService implementation

    @Override
    public void initializeForProfile(String genus) {
        this.genus = genus;
        NConfig config = ConfigFactory.getConfig(genus);
        this.configPath = config.getCheeseOrdersPath();
        load();
    }

    @Override
    public String getGenus() {
        return genus;
    }

    @Override
    public void load() {
        loadOrders();
    }

    @Override
    public void save() {
        writeOrders();
    }

    public void loadOrders() {
        orders.clear();
        String content = NFileUtils.readWithBackupFallback(configPath);
        if (content != null && !content.isEmpty()) {
            try {
                JSONObject main = new JSONObject(content);
                JSONArray array = main.getJSONArray("orders");
                for (int i = 0; i < array.length(); i++) {
                    CheeseOrder order = new CheeseOrder(array.getJSONObject(i));
                    orders.put(order.getId(), order);
                }
            } catch (org.json.JSONException e) {
                System.err.println("[CheeseOrdersManager] Failed to parse orders file (corrupt JSON): " + e.getMessage());
            }
        }
    }

    public void writeOrders() {
        JSONObject main = new JSONObject();
        JSONArray jorders = new JSONArray();
        for (CheeseOrder order : orders.values()) {
            jorders.put(order.toJson());
        }
        main.put("orders", jorders);

        try {
            NFileUtils.writeAtomically(configPath, main.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void addOrUpdateOrder(CheeseOrder order) {
        orders.put(order.getId(), order);
    }

    public void deleteOrder(int orderId) {
        orders.remove(orderId);
    }

    public Map<Integer, CheeseOrder> getOrders() {
        return orders;
    }
    
    public String getOrdersFilePath() {
        return configPath;
    }
}
