package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Grids that don't exist - past the world's edge: the boat was in loading range of them and they never loaded. Kept per map
 *  segment (segment grid coords) in one file, so no run sails for them again. Deleting the file forgets them all. */
public final class Voids {
    private final Path file;
    private final String seg;
    private final Set<Coord> voids = new HashSet<>();

    /** The voids recorded for segment segId. */
    public Voids(Path file, long segId) {
        this.file = file;
        this.seg = Long.toHexString(segId);
        JSONObject all = read();
        JSONArray a = all.optJSONArray(seg);
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONArray c = a.optJSONArray(i);
                if (c != null && c.length() == 2)
                    voids.add(Coord.of(c.optInt(0), c.optInt(1)));
            }
        }
    }

    public boolean contains(Coord c) {
        return voids.contains(c);
    }

    public int size() {
        return voids.size();
    }

    /** Records a void and saves the file. */
    public void add(Coord c) {
        if (!voids.add(c)) return;
        JSONObject all = read();
        JSONArray a = new JSONArray();
        for (Coord v : voids)
            a.put(new JSONArray().put(v.x).put(v.y));
        all.put(seg, a);
        try {
            Files.write(file, all.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[RiverExplorer] voids file: " + e);
        }
    }

    private JSONObject read() {
        try {
            if (!Files.exists(file)) return new JSONObject();
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (IOException | JSONException e) {
            System.out.println("[RiverExplorer] voids file: " + e);
            return new JSONObject();
        }
    }
}
