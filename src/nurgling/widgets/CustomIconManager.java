package nurgling.widgets;

import nurgling.NConfig;
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

/**
 * Manages custom icons - loading, saving, and accessing them.
 * Icons are stored in custom_icons.nurgling.json.
 */
public class CustomIconManager {
    private static CustomIconManager instance;
    private final Map<String, CustomIcon> icons = new LinkedHashMap<>();

    public CustomIconManager() {
        loadIcons();
    }

    public static CustomIconManager getInstance() {
        if (instance == null) {
            instance = new CustomIconManager();
        }
        return instance;
    }

    public void loadIcons() {
        icons.clear();
        String content = NFileUtils.readWithBackupFallback(NConfig.current.getCustomIconsPath());
        if (content != null && !content.isEmpty()) {
            try {
                JSONObject main = new JSONObject(content);
                JSONArray array = main.getJSONArray("icons");
                for (int i = 0; i < array.length(); i++) {
                    CustomIcon icon = new CustomIcon(array.getJSONObject(i));
                    icons.put(icon.getId(), icon);
                }
            } catch (org.json.JSONException e) {
                System.err.println("[CustomIconManager] Failed to parse icons file (corrupt JSON): " + e.getMessage());
            }
        }
    }

    public void writeIcons() {
        JSONObject main = new JSONObject();
        JSONArray jicons = new JSONArray();
        for (CustomIcon icon : icons.values()) {
            jicons.put(icon.toJson());
        }
        main.put("icons", jicons);

        try {
            NFileUtils.writeAtomically(NConfig.current.getCustomIconsPath(), main.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void addOrUpdateIcon(CustomIcon icon) {
        icons.put(icon.getId(), icon);
        writeIcons();
    }

    public void deleteIcon(String iconId) {
        icons.remove(iconId);
        writeIcons();
    }

    public CustomIcon getIcon(String id) {
        return icons.get(id);
    }

    public Map<String, CustomIcon> getIcons() {
        return icons;
    }

    public List<CustomIcon> getIconList() {
        return new ArrayList<>(icons.values());
    }
}
