package nurgling.scripting;

import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.bots.registry.ParamSpec;
import nurgling.scripting.api.ApiError;
import nurgling.scripting.api.Errors;
import nurgling.scripting.api.OpContext;
import nurgling.scripting.ops.FormOps;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The bot behind every entry of the Scripts folder: starts the Python process, waits for it to
 * connect, then serves its requests on this very bot thread until it exits or is stopped.
 *
 * The menu builds one instance per entry and reuses it, so all per-run state is local to run().
 */
public class ScriptLaunchAction implements Action {
    public static final String SCRIPT_KEY = "script";

    private final Map<String, Object> settings;

    public ScriptLaunchAction(Map<String, Object> settings) {
        this.settings = (settings == null) ? new HashMap<>() : new HashMap<>(settings);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        String rel = String.valueOf(settings.get(SCRIPT_KEY));
        if (!ScriptRuntime.enabled()) {
            gui.error("Scripting is off. Turn it on in Options > Scripting.");
            return Results.FAIL();
        }
        ScriptServer server = ScriptRuntime.ensureStarted();
        if (server == null) {
            gui.error("The scripting server could not start; see the client log.");
            return Results.FAIL();
        }
        ScriptCatalog.Entry entry = ScriptCatalog.find(rel);
        if (entry == null) {
            gui.error("Script not found: " + rel);
            return Results.FAIL();
        }
        List<String> python = PythonFinder.find();
        if (python == null) {
            gui.error("No Python 3 found. Install Python 3.9+ or set its path in Options > Scripting.");
            return Results.FAIL();
        }

        NUI ui = NUtils.getUI();
        ScriptRun run = new ScriptRun(ui, entry.name);
        run.params = collectParams(entry, run);
        if (run.params == null) return Results.FAIL();

        String runId = server.expectManagedRun(run);
        File log = logFile(entry);
        Process proc;
        try {
            proc = start(python, entry, server, runId, log);
        } catch (IOException e) {
            server.forgetManagedRun(runId);
            gui.error("Could not start " + String.join(" ", python) + ": " + e.getMessage());
            return Results.FAIL();
        }
        OpContext ctx = new OpContext(run, null);
        try {
            boolean connected = ctx.waitFor(() -> run.connection() != null || !proc.isAlive(), 30);
            if (run.connection() == null) {
                gui.error(entry.name + ": " + (connected ? "exited before connecting: " + lastLine(log) : "did not connect within 30 s"));
                return Results.FAIL();
            }
            run.serve();
            // The script closed its connection; give the process a moment to exit with its code.
            boolean exited = proc.waitFor(3, TimeUnit.SECONDS);
            int code = exited ? proc.exitValue() : 0;
            if (exited && code != 0)
                gui.error(entry.name + " failed: " + lastLine(log));
            if (run.reportedSuccess != null) return run.reportedSuccess ? Results.SUCCESS() : Results.FAIL();
            return code == 0 ? Results.SUCCESS() : Results.FAIL();
        } finally {
            server.forgetManagedRun(runId);
            ScriptConnection c = run.connection();
            if (c != null) c.close();
            reap(proc);
        }
    }

    /** Parameter values from the scenario step or menu settings; asks with a form when required values are missing. */
    private JSONObject collectParams(ScriptCatalog.Entry e, ScriptRun run) throws InterruptedException {
        JSONObject vals = new JSONObject();
        boolean missing = false;
        for (ParamSpec p : e.params) {
            if (settings.containsKey(p.name)) vals.put(p.name, settings.get(p.name));
            else missing = true;
        }
        if (!missing) return vals;
        JSONArray fields = new JSONArray();
        for (ParamSpec p : e.params) {
            JSONObject f = new JSONObject().put("name", p.name).put("label", e.labels.getOrDefault(p.name, p.name));
            switch (p.type) {
                case INT: f.put("type", "int"); break;
                case FLOAT: f.put("type", "float"); break;
                case BOOL: f.put("type", "bool"); break;
                case AREA: f.put("type", "area").put("map", false); break;
                case ENUM: f.put("type", "choice").put("values", new JSONArray(p.values)); break;
                default: f.put("type", "str");
            }
            Object def = settings.containsKey(p.name) ? settings.get(p.name) : p.def;
            if (def != null) f.put("default", def);
            f.put("required", p.required());
            fields.put(f);
        }
        try {
            return FormOps.ask(new OpContext(run, null), e.name, fields);
        } catch (ApiError err) {
            if (Errors.CANCELLED.equals(err.type)) return null;
            throw err;
        }
    }

    private static Process start(List<String> python, ScriptCatalog.Entry e, ScriptServer server, String runId, File log) throws IOException {
        List<String> cmd = new ArrayList<>(python);
        cmd.add("-u");
        cmd.add(e.file.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(e.file.getAbsoluteFile().getParentFile());
        Map<String, String> env = pb.environment();
        env.put("NURGLING_PORT", String.valueOf(server.port()));
        env.put("NURGLING_TOKEN", server.token());
        env.put("NURGLING_RUN", runId);
        env.put("NURGLING_SCRIPT", e.rel);
        env.put("NURGLING_JAVA", e.java ? "1" : "0");
        env.put("PYTHONIOENCODING", "utf-8");
        String sdk = ScriptRuntime.sdkDir().getAbsolutePath();
        String pp = env.get("PYTHONPATH");
        env.put("PYTHONPATH", (pp == null || pp.isEmpty()) ? sdk : sdk + File.pathSeparator + pp);
        // Output goes straight to a file: a pipe nobody drains would block the script once it fills.
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        return pb.start();
    }

    /** Lets the process exit on its own for 2 s, then kills it; never blocks the bot thread. */
    private static void reap(Process proc) {
        if (!proc.isAlive()) return;
        Thread t = new Thread(() -> {
            try {
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroy();
                    if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                // Reaper is a daemon helper; if it is interrupted just kill the script now.
                proc.destroyForcibly();
            }
        }, "Script-Reaper");
        t.setDaemon(true);
        t.start();
    }

    static File logFile(ScriptCatalog.Entry e) {
        File dir = new File(ScriptRuntime.ensureScriptsDir(), "logs");
        if (!dir.isDirectory() && !dir.mkdirs())
            System.out.println("[Scripting] could not create " + dir);
        pruneLogs(dir);
        String base = e.rel.replace("/main.py", "").replace(".py", "").replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(dir, base + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
    }

    /** Keeps a week of logs. */
    private static void pruneLogs(File dir) {
        File[] old = dir.listFiles((d, n) -> n.endsWith(".log"));
        if (old == null) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        for (File f : old) {
            if (f.lastModified() < cutoff && !f.delete())
                System.out.println("[Scripting] could not delete old log " + f);
        }
    }

    /** The last non-empty line of the log, which for a crashed script is the exception. */
    static String lastLine(File log) {
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long len = f.length();
            int n = (int) Math.min(len, 4096);
            byte[] buf = new byte[n];
            f.seek(len - n);
            f.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].trim().isEmpty()) return lines[i].trim();
        } catch (IOException e) {
            return "(no log: " + e.getMessage() + ")";
        }
        return "(no output; see " + log.getName() + ")";
    }
}
