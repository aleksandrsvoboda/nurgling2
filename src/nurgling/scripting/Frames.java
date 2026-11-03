package nurgling.scripting;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Wire framing: a 4-byte big-endian length, then that many bytes of UTF-8 JSON. */
final class Frames {
    static final int MAX = 16 << 20;

    /** Reads one frame; null at a clean end of stream. */
    static JSONObject read(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (len < 0 || len > MAX) throw new IOException("bad frame length " + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new JSONObject(new String(buf, StandardCharsets.UTF_8));
    }

    static void write(DataOutputStream out, JSONObject f) throws IOException {
        byte[] buf = f.toString().getBytes(StandardCharsets.UTF_8);
        out.writeInt(buf.length);
        out.write(buf);
        out.flush();
    }

    private Frames() {}
}
