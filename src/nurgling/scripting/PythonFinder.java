package nurgling.scripting;

import nurgling.NConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Finds a Python 3 interpreter: the configured path, else the first of python, py -3, python3
 * that actually runs. "Actually runs" matters: a broken py launcher (pointing at a removed
 * install) is common on Windows.
 */
public final class PythonFinder {
    private static List<String> cached;
    private static String cachedFor;

    public static synchronized List<String> find() throws InterruptedException {
        Object v = NConfig.get(NConfig.Key.scriptingPython);
        String conf = (v instanceof String) ? ((String) v).trim() : "";
        if (cached != null && conf.equals(cachedFor)) return cached;
        List<List<String>> cands = new ArrayList<>();
        if (!conf.isEmpty()) cands.add(Arrays.asList(conf.split("\\s+(?=-)")));
        boolean win = File.separatorChar == '\\';
        if (win) {
            cands.add(Arrays.asList("python"));
            cands.add(Arrays.asList("py", "-3"));
            cands.add(Arrays.asList("python3"));
        } else {
            cands.add(Arrays.asList("python3"));
            cands.add(Arrays.asList("python"));
        }
        for (List<String> c : cands) {
            if (works(c)) {
                cached = c;
                cachedFor = conf;
                return c;
            }
        }
        return null;
    }

    private static boolean works(List<String> cmd) throws InterruptedException {
        List<String> full = new ArrayList<>(cmd);
        full.add("-c");
        full.add("import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)");
        try {
            ProcessBuilder pb = new ProcessBuilder(full);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private PythonFinder() {}
}
