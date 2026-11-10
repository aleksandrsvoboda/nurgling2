package nurgling.actions.bots.riverexplorer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** River Explorers running at once on this PC (sessions of one client, or several clients) tell each other their targets
 *  through one small file, so they go different ways. Each run claims its target's grid id and refreshes the claim; a claim
 *  not refreshed within TTL_MS is dropped (a bot that stopped or crashed). Grid ids are the server's, the same in every
 *  session and map file. Anything wrong with the file only means no claims are seen. */
public final class Claims {
    public static final long REFRESH_MS = 30_000;
    static final long TTL_MS = 2 * REFRESH_MS;

    /** Sessions of one client share a JVM, where a second FileChannel lock on the file throws instead of waiting. */
    private static final Object IN_PROCESS = new Object();

    private final Path file;
    private final String me = Long.toHexString(ThreadLocalRandom.current().nextLong());
    private Long claimed = null;
    private long writtenT = 0;

    public Claims(Path file) {
        this.file = file;
    }

    /** Claims gridId (null = nothing) when it changed or the claim is due for a refresh; returns the grids that the runs ranked
     *  before this one claim. Ranked by a random id: two runs that picked the same target don't both give it up - the later one
     *  in the ranking goes elsewhere. */
    public Set<Long> exchange(Long gridId, long now) {
        boolean write = !java.util.Objects.equals(gridId, claimed) || now - writtenT >= REFRESH_MS;
        Set<Long> others = new HashSet<>();
        synchronized (IN_PROCESS) {
            exchange(gridId, now, write, others);
        }
        return others;
    }

    private void exchange(Long gridId, long now, boolean write, Set<Long> others) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = ch.lock()) {
            JSONArray kept = new JSONArray();
            for (Object o : read(ch)) {
                if (!(o instanceof JSONObject)) continue;
                JSONObject c = (JSONObject) o;
                if (me.equals(c.optString("who")) || now - c.optLong("t") > TTL_MS) continue;
                kept.put(c);
                if (c.optString("who").compareTo(me) < 0)
                    others.add(c.optLong("grid"));
            }
            if (write) {
                if (gridId != null)
                    kept.put(new JSONObject().put("who", me).put("grid", gridId.longValue()).put("t", now));
                byte[] out = kept.toString().getBytes(StandardCharsets.UTF_8);
                ch.truncate(0);
                ch.write(ByteBuffer.wrap(out), 0);
                claimed = gridId;
                writtenT = now;
            }
        } catch (IOException | JSONException e) {
            System.out.println("[RiverExplorer] claims file: " + e);
        }
    }

    /** Drops this run's claim. */
    public void release() {
        exchange(null, System.currentTimeMillis());
    }

    private static JSONArray read(FileChannel ch) throws IOException {
        long size = ch.size();
        if (size == 0 || size > 1 << 20) return new JSONArray();
        ByteBuffer buf = ByteBuffer.allocate((int) size);
        while (buf.hasRemaining() && ch.read(buf, buf.position()) > 0) ;
        String text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? new JSONArray() : new JSONArray(text);
    }
}
