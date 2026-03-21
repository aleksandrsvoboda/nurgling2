package nurgling.conf;

import org.json.JSONObject;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;

public class NCritterCircleConf implements JConf {
    public String path;
    public boolean visible;
    public int red, green, blue, alpha;
    public float radius;

    public NCritterCircleConf(String path, boolean visible, Color color, float radius) {
        this.path = path;
        this.visible = visible;
        this.red = color.getRed();
        this.green = color.getGreen();
        this.blue = color.getBlue();
        this.alpha = color.getAlpha();
        this.radius = radius;
    }

    public NCritterCircleConf(HashMap<String, Object> values) {
        path = (String) values.get("path");
        visible = values.get("visible") != null ? (Boolean) values.get("visible") : true;
        red = values.get("red") != null ? ((Number) values.get("red")).intValue() : 193;
        green = values.get("green") != null ? ((Number) values.get("green")).intValue() : 0;
        blue = values.get("blue") != null ? ((Number) values.get("blue")).intValue() : 255;
        alpha = values.get("alpha") != null ? ((Number) values.get("alpha")).intValue() : 140;
        radius = values.get("radius") != null ? ((Number) values.get("radius")).floatValue() : 10f;
    }

    public Color getColor() {
        return new Color(red, green, blue, alpha);
    }

    public void setColor(Color c) {
        this.red = c.getRed();
        this.green = c.getGreen();
        this.blue = c.getBlue();
        this.alpha = c.getAlpha();
    }

    @Override
    public JSONObject toJson() {
        JSONObject jobj = new JSONObject();
        jobj.put("type", "NCritterCircleConf");
        jobj.put("path", path);
        jobj.put("visible", visible);
        jobj.put("red", red);
        jobj.put("green", green);
        jobj.put("blue", blue);
        jobj.put("alpha", alpha);
        jobj.put("radius", radius);
        return jobj;
    }

    /**
     * Find config for a specific critter path.
     */
    @SuppressWarnings("unchecked")
    public static NCritterCircleConf get(String critterPath) {
        Object obj = nurgling.NConfig.get(nurgling.NConfig.Key.critterCircleSettings);
        if (obj instanceof ArrayList) {
            for (Object item : (ArrayList<?>) obj) {
                if (item instanceof NCritterCircleConf) {
                    NCritterCircleConf conf = (NCritterCircleConf) item;
                    if (conf.path.equals(critterPath))
                        return conf;
                }
            }
        }
        return null;
    }

    /**
     * Derive a display name from a resource path.
     * "gfx/kritter/rabbit/rabbit" -> "Rabbit"
     * "gfx/kritter/mallard/mallard-f" -> "Mallard F"
     * "gfx/terobjs/items/grub" -> "Grub"
     */
    public static String displayName(String path) {
        if (path == null) return "Unknown";
        int lastSlash = path.lastIndexOf('/');
        String raw = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        // Replace hyphens with spaces, capitalize each word
        String[] parts = raw.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            if (!part.isEmpty())
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
