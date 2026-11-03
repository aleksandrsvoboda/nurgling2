package nurgling.scripting;

/** Package-private run controls exposed to the op classes. */
public final class ScriptRuntimeAccess {
    public static void close(ScriptRun r) {
        r.close();
    }

    private ScriptRuntimeAccess() {}
}
