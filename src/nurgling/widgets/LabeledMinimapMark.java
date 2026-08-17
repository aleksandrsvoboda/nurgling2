package nurgling.widgets;

import haven.*;
import nurgling.conf.ProspectKind;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a labeled icon mark on the minimap.
 * Used by Checker bots (Water, Soil) to display resource quality on the map.
 * Shows an icon with a label underneath (e.g., "q20" for quality 20).
 *
 * A mark is a small, immutable value object that owns no textures of its own.
 * Icons are shared per resource type and label renders are shared per
 * (colour, text) pair, so a world with thousands of samples still only holds a
 * handful of images.
 */
public class LabeledMinimapMark {
    private final String locationId;     // Unique ID for this mark
    public final String label;           // The text label (e.g., "q20", "q95")
    public final String resourceType;    // Resource type (e.g., "Water", "Clay", "Soil")
    public final double quality;         // Exact sampled quality (the label is rounded)
    public final ProspectKind kind;      // Category used by the map-tools visibility filter
    public final long segmentId;
    public final Coord tileCoords;        // Tile coordinates within the segment
    public final long timestamp;          // When it was created
    public final Color labelColor;        // Color for the label text

    // Text furnace for rendering labels (like quest giver names)
    private static final Text.Furnace labelFurnace = new PUtils.BlurFurn(
        new Text.Foundry(Text.sans, 10, Color.WHITE).aa(true),
        2, 1, new Color(60, 30, 30)
    );

    /* Shared caches. Marks are built on bot and loader threads and read on the
     * render thread, hence the concurrent maps. Nothing is evicted: there are only
     * a few resource types and a bounded set of quality labels. */
    private static final Map<String, Icon> icons = new ConcurrentHashMap<>();
    private static final Map<String, Text> labelTex = new ConcurrentHashMap<>();
    private static final Map<Integer, Text.Furnace> furnaces = new ConcurrentHashMap<>();

    /** One image plus its lazily uploaded texture, shared by every mark of a resource type. */
    private static class Icon {
        final BufferedImage img;
        private TexI tex;
        private String encoded;

        Icon(BufferedImage img) {
            this.img = img;
        }

        synchronized TexI tex() {
            if(tex == null && img != null)
                tex = new TexI(img);
            return tex;
        }

        /** PNG-encoded once and reused, so saving never re-encodes an unchanged icon. */
        synchronized String encoded() {
            if(encoded == null)
                encoded = encodeIcon(img);
            return encoded;
        }
    }

    /**
     * Register the icon used by every mark of a resource type. The first
     * registration wins, so repeated samples of the same resource reuse one image.
     */
    public static void registerIcon(String resourceType, BufferedImage img) {
        if(resourceType == null || img == null)
            return;
        icons.putIfAbsent(resourceType, new Icon(img));
    }

    /** The shared icon image for a resource type, or null if none was registered. */
    public static BufferedImage icon(String resourceType) {
        Icon icon = (resourceType == null) ? null : icons.get(resourceType);
        return (icon == null) ? null : icon.img;
    }

    /** The shared icon for a resource type as a base64 PNG, encoded once and cached. */
    public static String iconBase64(String resourceType) {
        Icon icon = (resourceType == null) ? null : icons.get(resourceType);
        return (icon == null) ? null : icon.encoded();
    }

    /** Resource types that currently have an icon, for persisting the shared icon table. */
    public static Set<String> knownIconTypes() {
        return icons.keySet();
    }

    /**
     * Create a labeled minimap mark.
     *
     * @param label The text to display under the icon (e.g., "q20")
     * @param resourceType The type of resource (e.g., "Water", "Clay")
     * @param quality The exact sampled quality (the label only carries a rounded value)
     * @param segmentId The map segment ID
     * @param tileCoords The tile coordinates within the segment
     * @param iconImage Icon for this resource type; shared with every other mark of the same type
     * @param labelColor Optional color for the label (null = white)
     */
    public LabeledMinimapMark(String label, String resourceType, double quality, long segmentId, Coord tileCoords,
                              BufferedImage iconImage, Color labelColor) {
        this.label = label;
        this.resourceType = resourceType != null ? resourceType : "Unknown";
        this.quality = quality;
        this.kind = ProspectKind.of(this.resourceType);
        this.segmentId = segmentId;
        this.tileCoords = tileCoords;
        this.labelColor = labelColor != null ? labelColor : Color.WHITE;
        this.timestamp = System.currentTimeMillis();
        this.locationId = generateLocationId(segmentId, tileCoords, label);
        registerIcon(this.resourceType, iconImage);
    }

    /**
     * Create a labeled minimap mark with default white label color.
     */
    public LabeledMinimapMark(String label, String resourceType, double quality, long segmentId, Coord tileCoords,
                              BufferedImage iconImage) {
        this(label, resourceType, quality, segmentId, tileCoords, iconImage, null);
    }

    /**
     * Create from JSON (for loading from file).
     */
    public LabeledMinimapMark(JSONObject json) {
        this.locationId = json.getString("locationId");
        this.label = json.getString("label");
        this.resourceType = json.optString("resourceType", "Unknown");
        /* Marks written before quality was stored only carry the rounded value in the label. */
        this.quality = json.has("quality") ? json.getDouble("quality") : parseLabelQuality(this.label);
        this.kind = ProspectKind.of(this.resourceType);
        this.segmentId = json.getLong("segmentId");
        this.tileCoords = new Coord(json.getInt("tileX"), json.getInt("tileY"));
        this.timestamp = json.getLong("timestamp");

        // Load label color
        if (json.has("labelColor")) {
            this.labelColor = new Color(json.getInt("labelColor"));
        } else {
            this.labelColor = Color.WHITE;
        }

        /* Legacy files stored one base64 PNG per mark. Decode it only until the
         * resource type has an icon; every later mark of that type then costs nothing. */
        if(!icons.containsKey(this.resourceType) && json.has("iconBase64"))
            registerIcon(this.resourceType, decodeIcon(json.optString("iconBase64", null)));
    }

    /** Decode a base64 PNG, or null if it is unusable. */
    public static BufferedImage decodeIcon(String base64) {
        if(base64 == null || base64.isEmpty())
            return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        } catch(RuntimeException | java.io.IOException e) {
            System.err.println("Failed to load icon from base64: " + e.getMessage());
            return null;
        }
    }

    /** Encode an icon as a base64 PNG, or null if it cannot be written. */
    public static String encodeIcon(BufferedImage img) {
        if(img == null)
            return null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch(RuntimeException | java.io.IOException e) {
            System.err.println("Failed to save icon to base64: " + e.getMessage());
            return null;
        }
    }

    /**
     * Convert to JSON (for saving to file). The icon is not written here; it is
     * stored once per resource type in the file's shared icon table.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("locationId", locationId);
        json.put("label", label);
        json.put("resourceType", resourceType);
        json.put("quality", quality);
        json.put("segmentId", segmentId);
        json.put("tileX", tileCoords.x);
        json.put("tileY", tileCoords.y);
        json.put("timestamp", timestamp);
        json.put("labelColor", labelColor.getRGB());
        return json;
    }

    /**
     * Recover a quality from a legacy label such as "q40". Returns 0 when the label
     * carries no number, which makes the mark visible at any threshold of 0.
     */
    private static double parseLabelQuality(String label) {
        if(label == null)
            return 0;
        StringBuilder digits = new StringBuilder();
        for(int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if((c >= '0' && c <= '9') || (c == '.' && digits.indexOf(".") < 0))
                digits.append(c);
            else if(digits.length() > 0)
                break;
        }
        if(digits.length() == 0)
            return 0;
        try {
            return Double.parseDouble(digits.toString());
        } catch(NumberFormatException e) {
            return 0;
        }
    }

    private static String generateLocationId(long segmentId, Coord tileCoords, String label) {
        return String.format("labeled_%d_%d_%d_%s", segmentId, tileCoords.x, tileCoords.y,
                           label.replaceAll("[^a-zA-Z0-9]", "_"));
    }

    /**
     * Get the icon texture for rendering. Shared with every mark of the same resource type.
     */
    public TexI getIconTex() {
        Icon icon = icons.get(resourceType);
        return (icon == null) ? null : icon.tex();
    }

    /**
     * Get the label text for rendering. Renders are shared by (colour, text), so the
     * same "q40" in the same colour costs one texture no matter how many marks use it.
     */
    public Text getLabelText() {
        int rgb = labelColor.getRGB();
        return labelTex.computeIfAbsent(rgb + " " + label, k -> furnace(rgb).render(label));
    }

    private static Text.Furnace furnace(int rgb) {
        if(rgb == Color.WHITE.getRGB())
            return labelFurnace;
        return furnaces.computeIfAbsent(rgb, c -> new PUtils.BlurFurn(
            new Text.Foundry(Text.sans, 10, new Color(c)).aa(true),
            2, 1, new Color(60, 30, 30)));
    }

    /**
     * Check if this mark is in the specified segment.
     */
    public boolean isInSegment(long segId) {
        return this.segmentId == segId;
    }

    /**
     * Get a unique identifier for this mark.
     */
    public String getLocationId() {
        return locationId;
    }

    /**
     * Check if this mark is at the same location as another.
     * Used to avoid duplicate marks at the same spot.
     */
    public boolean isSameLocation(LabeledMinimapMark other) {
        return this.segmentId == other.segmentId &&
               this.tileCoords.equals(other.tileCoords);
    }

    /**
     * Check if a coordinate is near this mark (within given tile radius).
     */
    public boolean isNear(long segId, Coord tc, int radiusTiles) {
        if (this.segmentId != segId) return false;
        int dx = Math.abs(this.tileCoords.x - tc.x);
        int dy = Math.abs(this.tileCoords.y - tc.y);
        return dx <= radiusTiles && dy <= radiusTiles;
    }
}
