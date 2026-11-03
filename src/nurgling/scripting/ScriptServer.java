package nurgling.scripting;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The localhost listener. Raw TCP on purpose: browsers cannot open raw sockets, so web pages
 * cannot reach it (no DNS-rebinding or CSRF class of attack). Every connection must present the
 * per-launch token within 5 seconds; the token is only in the owner-readable info file and in the
 * environment of scripts the client launches itself.
 */
public class ScriptServer {
    private final ServerSocket ss;
    private final String token;
    private final File infoFile;
    private final Map<String, ScriptRun> pendingManaged = new ConcurrentHashMap<>();
    private final Set<ScriptConnection> conns = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped = false;

    ScriptServer(int port, File infoFile) throws IOException {
        this.ss = new ServerSocket(port, 16, InetAddress.getLoopbackAddress());
        this.token = randomHex(32);
        this.infoFile = infoFile;
        writeInfo();
        Thread t = new Thread(this::acceptLoop, "Script-Server");
        t.setDaemon(true);
        t.start();
        System.out.println("[Scripting] listening on 127.0.0.1:" + port());
    }

    public int port() { return ss.getLocalPort(); }

    public String token() { return token; }

    private void writeInfo() throws IOException {
        JSONObject j = new JSONObject()
                .put("port", port())
                .put("token", token)
                .put("api", ScriptRuntime.API_VERSION)
                .put("pid", ProcessHandle.current().pid());
        File tmp = new File(infoFile.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(j.toString(2));
        }
        restrictToOwner(tmp);
        if (infoFile.exists() && !infoFile.delete())
            System.out.println("[Scripting] could not replace " + infoFile);
        if (!tmp.renameTo(infoFile))
            throw new IOException("could not write " + infoFile);
    }

    private static void restrictToOwner(File f) {
        // Best effort: on Windows the AppData folder is already private to the user.
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }

    private void acceptLoop() {
        while (!stopped) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (!stopped) System.out.println("[Scripting] accept failed: " + e);
                continue;
            }
            try {
                s.setTcpNoDelay(true);
                ScriptConnection c = new ScriptConnection(this, s);
                conns.add(c);
                c.start();
            } catch (IOException e) {
                System.out.println("[Scripting] could not set up a connection: " + e);
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Nothing left to do with a broken socket.
                }
            }
        }
    }

    boolean checkToken(String presented) {
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Registers a run started by the client itself; the script claims it with this id in its hello. */
    String expectManagedRun(ScriptRun run) {
        String id = randomHex(16);
        pendingManaged.put(id, run);
        return id;
    }

    void forgetManagedRun(String id) {
        pendingManaged.remove(id);
    }

    ScriptRun claimManagedRun(String id) {
        return pendingManaged.remove(id);
    }

    void connectionClosed(ScriptConnection c) {
        conns.remove(c);
    }

    public Set<ScriptConnection> connections() { return conns; }

    void stop() {
        stopped = true;
        try {
            ss.close();
        } catch (IOException ignored) {
            // Shutting down anyway.
        }
        for (ScriptConnection c : conns) c.close();
        if (!infoFile.delete() && infoFile.exists())
            System.out.println("[Scripting] could not delete " + infoFile);
    }

    private static final SecureRandom rnd = new SecureRandom();

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
