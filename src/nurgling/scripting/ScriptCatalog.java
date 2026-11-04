package nurgling.scripting;

import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.actions.bots.registry.ParamSpec;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds user scripts in the scripts folder and registers each as a bot (type SCRIPTS), so it
 * shows in the bot menu's Scripts folder and can be a scenario step.
 *
 * A script is a top-level {@code *.py} file, or a folder with a {@code main.py}. Its header
 * (comment lines among the first 40) describes it:
 * <pre>
 * # nurgling: name="Chop trees", description="Chops the nearest trees", icon=chop, java=false
 * # nurgling-param: count int 10 "How many trees"
 * # nurgling-param: zone area - "Where to chop"
 * # nurgling-param: mode choice[fast,careful] fast "Mode"
 * </pre>
 * A default of "-" makes the parameter required.
 *
 * Icon: {@code icon=chop.png} (a PNG next to the script), or {@code icon=choper} (a built-in bot's
 * icon). Without one, {@code <script>.png} beside the script (or {@code icon.png} in a script folder)
 * is used if it exists. The name and description are the button's tooltip.
 */
public final class ScriptCatalog {
    public static final String ID_PREFIX = "script:";

    public static final class Entry {
        public final String rel;
        public final File file;
        public final String name;
        public final String description;
        public final String icon;
        /** The script's own PNG icon, if it has one. */
        public final File iconFile;
        public final boolean java;
        public final List<ParamSpec> params;
        /** Parameter labels for the start form, by name. */
        public final Map<String, String> labels;

        Entry(String rel, File file, String name, String description, String icon, File iconFile, boolean java, List<ParamSpec> params, Map<String, String> labels) {
            this.rel = rel;
            this.file = file;
            this.name = name;
            this.description = description;
            this.icon = icon;
            this.iconFile = iconFile;
            this.java = java;
            this.params = params;
            this.labels = labels;
        }

        public String id() {
            return ID_PREFIX + rel;
        }
    }

    private static final Pattern KV = Pattern.compile("(\\w+)\\s*=\\s*(\"([^\"]*)\"|[^,\\s]+)");
    private static final Pattern PARAM = Pattern.compile("(\\w+)\\s+(\\w+(?:\\[[^\\]]*\\])?)\\s+(\"[^\"]*\"|\\S+)\\s*(?:\"([^\"]*)\")?");

    /** Folder name prefix of the examples shipped with the client, in script ids and rel paths. */
    public static final String EXAMPLES = "examples/";

    /** Scans the user's scripts and the bundled examples and re-registers them all. Call on a UI thread or at startup. */
    public static synchronized List<Entry> refresh() {
        List<Entry> entries = new ArrayList<>(scan(ScriptRuntime.scriptsDir(), ""));
        File ex = ScriptRuntime.examplesDir();
        if (ex != null) entries.addAll(scan(ex, EXAMPLES));
        Set<String> icons = new HashSet<>();
        for (BotDescriptor d : BotRegistry.all()) if (d.type != BotDescriptor.BotType.SCRIPTS) icons.add(d.iconPath);
        List<BotDescriptor> descs = new ArrayList<>();
        for (Entry e : entries) {
            String icon = (e.icon != null && icons.contains(e.icon)) ? e.icon : "script";
            Map<String, Object> defaults = new HashMap<>();
            defaults.put(ScriptLaunchAction.SCRIPT_KEY, e.rel);
            BotDescriptor d = new BotDescriptor(e.id(), BotDescriptor.BotType.SCRIPTS, e.name, e.description,
                    true, true, ScriptLaunchAction.class, icon, false, defaults)
                    .withParams(e.params.toArray(new ParamSpec[0]))
                    .inGroup(e.rel.startsWith(EXAMPLES) ? "examples" : "my_scripts");
            if (e.iconFile != null) {
                java.awt.image.BufferedImage[] imgs = ScriptIcons.load(e.iconFile);
                if (imgs != null) d.withIcon(imgs);
            }
            descs.add(d);
        }
        BotRegistry.replaceDynamic(BotDescriptor.BotType.SCRIPTS, descs, Arrays.asList("my_scripts", "examples"));
        return entries;
    }

    /** A script by its rel path: "x.py", "dir/main.py", or "examples/x.py" for a bundled example. */
    public static Entry find(String rel) {
        if (rel.startsWith(EXAMPLES)) {
            File ex = ScriptRuntime.examplesDir();
            if (ex == null) return null;
            for (Entry e : scan(ex, EXAMPLES)) if (e.rel.equals(rel)) return e;
            return null;
        }
        for (Entry e : scan(ScriptRuntime.scriptsDir(), "")) if (e.rel.equals(rel)) return e;
        return null;
    }

    static List<Entry> scan(File dir, String prefix) {
        List<Entry> res = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return res;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File f : files) {
            if (f.getName().startsWith(".") || f.getName().startsWith("_") || f.getName().equals("logs")) continue;
            File main;
            String rel;
            if (f.isFile() && f.getName().endsWith(".py")) {
                main = f;
                rel = prefix + f.getName();
            } else if (f.isDirectory() && new File(f, "main.py").isFile()) {
                main = new File(f, "main.py");
                rel = prefix + f.getName() + "/main.py";
            } else {
                continue;
            }
            Entry e = parse(rel, main);
            if (e != null) res.add(e);
        }
        return res;
    }

    static Entry parse(String rel, File file) {
        String base = rel.startsWith(EXAMPLES) ? rel.substring(EXAMPLES.length()) : rel;
        String name = base.endsWith("/main.py") ? base.substring(0, base.length() - 8) : base.substring(0, base.length() - 3);
        String description = "Python script " + rel;
        String icon = null;
        boolean java = false;
        List<ParamSpec> params = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            for (int i = 0; i < 40; i++) {
                String line = r.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.startsWith("# nurgling:")) {
                    Matcher m = KV.matcher(line.substring(11));
                    while (m.find()) {
                        String k = m.group(1), v = m.group(3) != null ? m.group(3) : m.group(2);
                        if (k.equals("name")) name = v;
                        else if (k.equals("description")) description = v;
                        else if (k.equals("icon")) icon = v;
                        else if (k.equals("java")) java = v.equalsIgnoreCase("true");
                    }
                } else if (line.startsWith("# nurgling-param:")) {
                    Matcher m = PARAM.matcher(line.substring(17).trim());
                    if (!m.find()) {
                        System.out.println("[Scripting] " + rel + ": cannot parse parameter line: " + line);
                        continue;
                    }
                    ParamSpec p = param(m.group(1), m.group(2), unquote(m.group(3)), m.group(4));
                    if (p == null) {
                        System.out.println("[Scripting] " + rel + ": bad parameter type in: " + line);
                        continue;
                    }
                    params.add(p);
                    labels.put(p.name, m.group(4) != null ? m.group(4) : p.name);
                }
            }
        } catch (IOException e) {
            System.out.println("[Scripting] cannot read " + file + ": " + e);
            return null;
        }
        return new Entry(rel, file, name, description, icon, iconFile(file, icon), java, params, labels);
    }

    /** The script's own PNG: the header's icon=x.png, else script.png beside it, else icon.png in its folder. */
    private static File iconFile(File script, String icon) {
        File dir = script.getAbsoluteFile().getParentFile();
        if (icon != null && icon.toLowerCase().endsWith(".png")) {
            File f = new File(dir, icon);
            if (f.isFile()) return f;
            System.out.println("[Scripting] " + script.getName() + ": icon " + f + " not found");
            return null;
        }
        if (icon != null) return null;   // a built-in icon name
        String base = script.getName().replaceAll("\\.py$", "");
        File own = new File(dir, base + ".png");
        if (own.isFile()) return own;
        if (script.getName().equals("main.py")) {
            File folder = new File(dir, "icon.png");
            if (folder.isFile()) return folder;
        }
        return null;
    }

    private static String unquote(String s) {
        return (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : s;
    }

    private static ParamSpec param(String name, String type, String def, String label) {
        String doc = label != null ? label : name;
        boolean none = def.equals("-");
        try {
            if (type.equals("int")) return ParamSpec.of(name, ParamSpec.Type.INT, doc, none ? null : Integer.valueOf(def));
            if (type.equals("float")) return ParamSpec.of(name, ParamSpec.Type.FLOAT, doc, none ? null : Double.valueOf(def));
            if (type.equals("bool")) return ParamSpec.of(name, ParamSpec.Type.BOOL, doc, none ? null : Boolean.valueOf(def));
            if (type.equals("str")) return ParamSpec.of(name, ParamSpec.Type.STRING, doc, none ? null : def);
            if (type.equals("area")) return ParamSpec.of(name, ParamSpec.Type.AREA, doc, none ? null : Integer.valueOf(def));
        } catch (NumberFormatException e) {
            return null;
        }
        if (type.startsWith("choice[") && type.endsWith("]")) {
            String[] vals = type.substring(7, type.length() - 1).split(",");
            for (int i = 0; i < vals.length; i++) vals[i] = vals[i].trim();
            return ParamSpec.choice(name, doc, none ? null : def, vals);
        }
        return null;
    }

    private ScriptCatalog() {}
}
