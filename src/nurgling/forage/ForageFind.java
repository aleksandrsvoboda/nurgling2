package nurgling.forage;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One forageable the player picked: where it stood, what came out of it and at what quality.
 *
 * <p>The position is the server grid id plus the tile offset inside that grid, the same identity fish
 * spots and timers use, so a find means the same place on every client and can be shared through the
 * database. Records are immutable; {@link #withVersion} makes the copy the sync worker needs.
 */
public final class ForageFind {
    public final String id;
    public final long gridId;
    public final int ox, oy;
    /** The picked gob's resource, e.g. {@code gfx/terobjs/herbs/blueberry}. */
    public final String gobRes;
    /** The item that landed in the inventory, e.g. {@code gfx/invobjs/herbs/blueberry}; drawn as the icon. */
    public final String itemRes;
    /** The item's display name, e.g. "Blueberries". Label, search and the near-duplicate rule use it. */
    public final String itemName;
    /** Best quality of the items this pick produced. Exact; the map label rounds. */
    public final double quality;
    /** How many items the pick produced. */
    public final int amount;
    /** When it was picked, epoch ms on the picker's clock. */
    public final long foundAt;
    public final String foundBy;
    /** Database row version; 0 for a find the database has never confirmed. */
    public final int version;

    public ForageFind(String id, long gridId, int ox, int oy, String gobRes, String itemRes, String itemName,
                      double quality, int amount, long foundAt, String foundBy, int version) {
        this.id = id;
        this.gridId = gridId;
        this.ox = ox;
        this.oy = oy;
        this.gobRes = gobRes;
        this.itemRes = itemRes;
        this.itemName = (itemName == null) ? "" : itemName;
        this.quality = quality;
        this.amount = amount;
        this.foundAt = foundAt;
        this.foundBy = (foundBy == null) ? "" : foundBy;
        this.version = version;
    }

    /**
     * Row id from the place, the gob and the gob's id. A gob id names one herb, so every pick is its own
     * row, while sending the same pick twice (a retried upload) stays one row.
     */
    public static String makeId(String profile, long gridId, int ox, int oy, String gobRes, long gobId) {
        String key = "forage|" + profile + "|" + gridId + "|" + ox + "|" + oy + "|" + gobRes + "|" + gobId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public ForageFind withVersion(int version) {
        return new ForageFind(id, gridId, ox, oy, gobRes, itemRes, itemName, quality, amount, foundAt, foundBy, version);
    }

    /** The day it was picked in this computer's time zone, as YYYY-MM-DD. */
    public java.time.LocalDate foundDate() {
        return java.time.Instant.ofEpochMilli(foundAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }

    /** Whether the two finds are in the same grid and within {@code tiles} of each other. */
    public boolean isNear(ForageFind o, int tiles) {
        return gridId == o.gridId && Math.abs(ox - o.ox) <= tiles && Math.abs(oy - o.oy) <= tiles;
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        j.put("id", id);
        j.put("gridId", gridId);
        j.put("ox", ox);
        j.put("oy", oy);
        j.put("gobRes", gobRes);
        if(itemRes != null)
            j.put("itemRes", itemRes);
        j.put("itemName", itemName);
        j.put("quality", quality);
        j.put("amount", amount);
        j.put("foundAt", foundAt);
        j.put("foundBy", foundBy);
        j.put("version", version);
        return j;
    }

    public static ForageFind fromJson(JSONObject j) {
        return new ForageFind(j.getString("id"), j.getLong("gridId"), j.getInt("ox"), j.getInt("oy"),
            j.optString("gobRes", ""), j.optString("itemRes", null), j.optString("itemName", ""),
            j.optDouble("quality", 0), j.optInt("amount", 1), j.optLong("foundAt", 0),
            j.optString("foundBy", ""), j.optInt("version", 0));
    }
}
