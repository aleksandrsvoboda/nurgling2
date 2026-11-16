package nurgling.actions.bots.largefields;

import nurgling.NUtils;
import nurgling.areas.NArea;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where an unfinished large-field run stopped, so the next run of the same crop carries on from
 * there instead of walking every block again. One record per crop, in
 * large_fields_progress.json next to the other data files. A run that finishes clears its record.
 * A record older than {@link #MAX_AGE_MS} is ignored: by then the blocks already done have grown
 * again and need a full pass.
 */
final class FieldProgress {
    static final long MAX_AGE_MS = 6L * 60 * 60 * 1000;
    private static final String FILE = "large_fields_progress.json";

    static final int HARVEST = 0, PLANT = 1;

    /** The field, lap and block to start from, and the fields this run already finished. */
    static final class Mark {
        final Set<String> done = new LinkedHashSet<>();
        final String field;
        /** {@link #HARVEST} or {@link #PLANT}. */
        int lap = HARVEST;
        final int block;
        final int blocks;
        final int blockSize;
        final boolean fromRight, fromBottom;
        final long savedAt;

        Mark(String field, int block, int blocks, int blockSize, boolean fromRight, boolean fromBottom, long savedAt) {
            this.field = field;
            this.block = block;
            this.blocks = blocks;
            this.blockSize = blockSize;
            this.fromRight = fromRight;
            this.fromBottom = fromBottom;
            this.savedAt = savedAt;
        }
    }

    private FieldProgress() {}

    static String key(NArea area) {
        return (area.uuid != null) ? area.uuid : ("id:" + area.id);
    }

    private static Path path() {
        return NUtils.getDataFilePath(FILE);
    }

    private static JSONObject readAll() {
        try {
            Path p = path();
            if (!Files.exists(p))
                return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            System.out.println("[LargeField] can't read " + FILE + ": " + e);
            return new JSONObject();
        }
    }

    private static void writeAll(JSONObject all) {
        try {
            Files.write(path(), all.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[LargeField] can't write " + FILE + ": " + e);
        }
    }

    /** The saved mark for a crop, or null when there is none or it is too old. */
    static Mark load(String crop) {
        JSONObject all = readAll();
        if (!all.has(crop))
            return null;
        try {
            JSONObject o = all.getJSONObject(crop);
            Mark m = new Mark(o.getString("field"), o.getInt("block"), o.getInt("blocks"), o.getInt("blockSize"),
                    o.getBoolean("fromRight"), o.getBoolean("fromBottom"), o.getLong("savedAt"));
            m.lap = o.optInt("lap", HARVEST);
            JSONArray done = o.optJSONArray("done");
            if (done != null)
                for (int i = 0; i < done.length(); i++)
                    m.done.add(done.getString(i));
            return (System.currentTimeMillis() - m.savedAt > MAX_AGE_MS) ? null : m;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Records that the run of this crop should resume at the given block of the given field. */
    static void save(String crop, Mark m) {
        JSONObject all = readAll();
        JSONObject o = new JSONObject();
        o.put("field", m.field);
        o.put("block", m.block);
        o.put("blocks", m.blocks);
        o.put("blockSize", m.blockSize);
        o.put("fromRight", m.fromRight);
        o.put("fromBottom", m.fromBottom);
        o.put("savedAt", m.savedAt);
        o.put("done", new JSONArray(m.done));
        o.put("lap", m.lap);
        all.put(crop, o);
        writeAll(all);
    }

    static void clear(String crop) {
        JSONObject all = readAll();
        if (all.remove(crop) != null)
            writeAll(all);
    }
}
