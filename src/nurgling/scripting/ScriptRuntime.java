package nurgling.scripting;

import haven.UI;
import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.sessions.BotExecutor;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry points of the scripting feature: starting and stopping the server, session lookup,
 * starting bot threads for runs, and where scripts and the SDK live.
 */
public final class ScriptRuntime {
    /** The protocol/API version. Bump minor for additions, major for breaking changes. */
    public static final String API_VERSION = "1.0";

    private static volatile ScriptServer server;
    private static boolean hookInstalled = false;

    public static boolean enabled() {
        return Boolean.TRUE.equals(NConfig.get(NConfig.Key.scriptingEnabled));
    }

    /** Starts the server if scripting is enabled; called at client startup and when the setting is turned on. */
    public static synchronized void startIfEnabled() {
        if (enabled()) ensureStarted();
    }

    /** The running server, starting it if needed; null if it could not be started. */
    public static synchronized ScriptServer ensureStarted() {
        if (server != null) return server;
        int port = 0;
        Object p = NConfig.get(NConfig.Key.scriptingPort);
        if (p instanceof Number) port = ((Number) p).intValue();
        try {
            server = new ScriptServer(port, infoFile());
            if (!hookInstalled) {
                hookInstalled = true;
                // Remove the info file on exit so attaching tools don't find a stale port and token.
                Runtime.getRuntime().addShutdownHook(new Thread(ScriptRuntime::stop, "Script-Shutdown"));
            }
        } catch (IOException e) {
            System.out.println("[Scripting] could not start the server on port " + port + ": " + e);
            server = null;
        }
        return server;
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public static ScriptServer server() { return server; }

    /** Where attaching scripts find the port and token. */
    public static File infoFile() {
        return new File(NUtils.getDataFile("scripting.json"));
    }

    public static File scriptsDir() {
        Object v = NConfig.get(NConfig.Key.scriptingDir);
        return (v instanceof String && !((String) v).isEmpty()) ? new File((String) v) : new File("scripts");
    }

    /** The scripts folder, created if missing (when the player turns scripting on or opens the folder). */
    public static File ensureScriptsDir() {
        File d = scriptsDir();
        if (!d.isDirectory() && !d.mkdirs())
            System.out.println("[Scripting] could not create " + d.getAbsolutePath());
        return d;
    }

    /**
     * The bundled Python SDK: "scripting/sdk" next to the client jar, then in the working
     * directory, then the source tree (for developers running from the repo).
     */
    public static File sdkDir() {
        try {
            File jar = new File(ScriptRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File d = new File(jar.getParentFile(), "scripting/sdk");
            if (new File(d, "nurgling").isDirectory()) return d;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            // Fall through to the other locations.
        }
        File d = new File("scripting/sdk");
        if (new File(d, "nurgling").isDirectory()) return d;
        d = new File("sdk/python");
        if (new File(d, "nurgling").isDirectory()) return d;
        return new File("../sdk/python");
    }

    /**
     * Example scripts shipped with the client ("scripting/examples" next to the jar), or the docs
     * folder they come from when running from the source tree. Null if neither exists.
     */
    public static File examplesDir() {
        try {
            File jar = new File(ScriptRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File d = new File(jar.getParentFile(), "scripting/examples");
            if (d.isDirectory()) return d;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            // Fall through to the other locations.
        }
        for (String p : new String[]{"scripting/examples", "docs/scripting/examples", "../docs/scripting/examples"}) {
            File d = new File(p);
            if (d.isDirectory()) return d;
        }
        return null;
    }

    private static String version;

    public static synchronized String clientVersion() {
        if (version == null) {
            version = "unknown";
            try (InputStream in = ScriptRuntime.class.getResourceAsStream("/buildinfo")) {
                if (in != null) {
                    Properties p = new Properties();
                    p.load(in);
                    version = p.getProperty("git-rev", "unknown");
                }
            } catch (IOException e) {
                // Leave it "unknown".
            }
        }
        return version;
    }

    /** Runs {@code r} on the session's tick thread (UI thread for the visible tab, headless thread otherwise). */
    public static void onUiThread(NUI ui, Runnable r) {
        if (ui != null && ui.core != null)
            ui.core.post(r);
    }

    /** Session by character name, username or session id (case-insensitive); null name means the active tab. */
    public static NUI resolveSession(String name) {
        SessionManager sm = SessionManager.getInstance();
        if (name == null || name.isEmpty()) {
            NUI ui = sm.getActiveUI();
            if (ui != null) return ui;
            UI u = UI.getInstance();
            return (u instanceof NUI) ? (NUI) u : null;
        }
        for (SessionContext sc : sm.getAllSessions()) {
            if (name.equalsIgnoreCase(sc.getDisplayName()) || name.equalsIgnoreCase(sc.username) || name.equalsIgnoreCase(sc.sessionId))
                return sc.ui;
        }
        return null;
    }

    public static JSONObject sessionInfo(NUI ui) {
        JSONObject j = new JSONObject();
        SessionContext sc = SessionManager.getInstance().findByUI(ui);
        if (sc != null) {
            j.put("id", sc.sessionId);
            j.put("character", sc.getDisplayName());
            j.put("username", sc.username == null ? JSONObject.NULL : sc.username);
            j.put("headless", sc.isHeadless());
        } else {
            j.put("id", JSONObject.NULL);
            j.put("character", ui.gui != null && ui.gui.chrid != null ? ui.gui.chrid : JSONObject.NULL);
            j.put("username", JSONObject.NULL);
            j.put("headless", false);
        }
        j.put("active", SessionManager.getInstance().getActiveUI() == ui);
        return j;
    }

    static JSONObject paramsFor(ScriptRun run) {
        return run.params;
    }

    /**
     * Starts a bot thread that serves {@code run}'s requests, on the session's UI thread, and
     * waits for it. Used for attached scripts and for extra sessions of a multi-session script.
     */
    public static ScriptRun startAttachedRun(NUI ui, String title) {
        ScriptRun run = new ScriptRun(ui, title);
        Action serve = gui -> {
            run.serve();
            return Results.SUCCESS();
        };
        AtomicReference<Thread> started = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        onUiThread(ui, () -> {
            try {
                started.set(BotExecutor.runWithSupportsOn(ui, title, serve, false, null));
            } finally {
                latch.countDown();
            }
        });
        boolean ok;
        try {
            ok = latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // The caller (a connection's reader thread) is shutting down; give up on this run.
            Thread.currentThread().interrupt();
            return null;
        }
        return (ok && started.get() != null) ? run : null;
    }

    private ScriptRuntime() {}
}
