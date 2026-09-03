package nurgling.tools;

import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.areas.NContext;
import nurgling.widgets.Specialisation;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Answers "which bots use this specialisation?".
 * <p>
 * Bots do not declare the zones they need; they build their requirement lists inside
 * run(), so the code itself is the only reliable source. Rather than keeping a table
 * that would silently rot every time a bot is added, this reads the compiled class
 * files: for each registered bot it follows class references through the action classes
 * that bot pulls in and collects every {@code Specialisation.SpecName} constant reached
 * along the way.
 * <p>
 * Traversal stays inside the action/bot/conf packages. Infrastructure such as NContext
 * names nearly every specialisation for its own bookkeeping, so walking into it would
 * make every bot look like it uses everything. The one indirection worth following is
 * {@link NContext#workstation_spec_map}: code that reads that map can end up in any of
 * the workstation zones, so referencing the map counts as using all of them.
 */
public class SpecialisationUsage {
    private static final String SPECNAME_CLASS = "nurgling/widgets/Specialisation$SpecName";
    private static final String NCONTEXT_CLASS = "nurgling/areas/NContext";
    private static final String WORKSTATION_MAP = "workstation_spec_map";

    private static volatile Map<String, List<String>> usage = null;
    private static Thread scanner = null;

    /**
     * Kicks off the scan on a background thread unless it has already run or is
     * running. Cheap and safe to call every time the specialisation window opens.
     */
    public static synchronized void request() {
        if((usage != null) || (scanner != null))
            return;
        scanner = new Thread(new Runnable() {
            public void run() {
                Map<String, List<String>> res = scan();
                synchronized(SpecialisationUsage.class) {
                    usage = res;
                    scanner = null;
                }
            }
        }, "Specialisation usage scan");
        scanner.setDaemon(true);
        scanner.start();
    }

    /**
     * Display names of the bots that use the given specialisation, sorted. Empty when
     * no bot uses it, null while the scan is still running.
     */
    public static List<String> botsFor(String spec) {
        Map<String, List<String>> u = usage;
        if(u == null)
            return(null);
        List<String> bots = u.get(spec);
        return((bots == null) ? Collections.<String>emptyList() : bots);
    }

    private static Map<String, List<String>> scan() {
        Map<String, ClassInfo> cache = new HashMap<>();
        Map<String, TreeSet<String>> found = new HashMap<>();
        for(BotDescriptor bot : BotRegistry.all()) {
            String name = bot.getDisplayName();
            if((name == null) || name.trim().isEmpty())
                continue;
            for(String spec : specsOf(bot.clazz.getName().replace('.', '/'), cache)) {
                TreeSet<String> names = found.get(spec);
                if(names == null) {
                    names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    found.put(spec, names);
                }
                names.add(name);
            }
        }
        Map<String, List<String>> res = new HashMap<>();
        for(Map.Entry<String, TreeSet<String>> ent : found.entrySet())
            res.put(ent.getKey(), Collections.unmodifiableList(new ArrayList<>(ent.getValue())));
        return(res);
    }

    private static Set<String> specsOf(String root, Map<String, ClassInfo> cache) {
        Set<String> specs = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        seen.add(root);
        queue.add(root);
        while(!queue.isEmpty()) {
            ClassInfo ci = info(queue.poll(), cache);
            specs.addAll(ci.specs);
            if(ci.workstations)
                specs.addAll(workstationSpecs());
            for(String ref : ci.refs) {
                if(traversable(ref) && seen.add(ref))
                    queue.add(ref);
            }
        }
        return(specs);
    }

    private static boolean traversable(String cls) {
        if(cls.startsWith("nurgling/actions/bots/registry/"))
            /* The registry names every bot class; following it would merge them all. */
            return(false);
        return(cls.startsWith("nurgling/actions/") || cls.startsWith("nurgling/bots/") ||
               cls.startsWith("nurgling/conf/"));
    }

    private static Set<String> wspecs = null;

    private static synchronized Set<String> workstationSpecs() {
        if(wspecs == null) {
            Set<String> specs = new HashSet<>();
            for(Specialisation.SpecName spec : NContext.workstation_spec_map.values())
                specs.add(spec.toString());
            wspecs = specs;
        }
        return(wspecs);
    }

    private static ClassInfo info(String cls, Map<String, ClassInfo> cache) {
        ClassInfo ci = cache.get(cls);
        if(ci == null) {
            ci = parse(cls);
            if(ci == null)
                /* Unreadable or absent: remember as empty so it is not retried. */
                ci = new ClassInfo();
            cache.put(cls, ci);
        }
        return(ci);
    }

    private static class ClassInfo {
        final Set<String> specs = new HashSet<>();
        final Set<String> refs = new HashSet<>();
        boolean workstations = false;
    }

    /* Constant-pool tags, see JVMS 4.4. */
    private static final int CP_UTF8 = 1, CP_INT = 3, CP_FLOAT = 4, CP_LONG = 5, CP_DOUBLE = 6,
        CP_CLASS = 7, CP_STRING = 8, CP_FIELD = 9, CP_METHOD = 10, CP_IFMETHOD = 11,
        CP_NAMETYPE = 12, CP_MHANDLE = 15, CP_MTYPE = 16, CP_DYNAMIC = 17, CP_INDY = 18,
        CP_MODULE = 19, CP_PACKAGE = 20;

    /**
     * Reads the constant pool of one class file, which is all that is needed: the enum
     * constants it uses show up as field references and the classes it touches as class
     * references. Returns null if the class cannot be read or understood.
     */
    private static ClassInfo parse(String cls) {
        try(InputStream res = SpecialisationUsage.class.getClassLoader().getResourceAsStream(cls + ".class")) {
            if(res == null)
                return(null);
            DataInputStream in = new DataInputStream(new BufferedInputStream(res));
            if(in.readInt() != 0xcafebabe)
                return(null);
            in.readUnsignedShort();
            in.readUnsignedShort();
            int n = in.readUnsignedShort();
            int[] tag = new int[n], a = new int[n], b = new int[n];
            String[] utf = new String[n];
            for(int i = 1; i < n; i++) {
                int t = tag[i] = in.readUnsignedByte();
                switch(t) {
                case CP_UTF8:
                    utf[i] = in.readUTF();
                    break;
                case CP_CLASS: case CP_STRING: case CP_MTYPE: case CP_MODULE: case CP_PACKAGE:
                    a[i] = in.readUnsignedShort();
                    break;
                case CP_MHANDLE:
                    a[i] = in.readUnsignedByte();
                    b[i] = in.readUnsignedShort();
                    break;
                case CP_FIELD: case CP_METHOD: case CP_IFMETHOD: case CP_NAMETYPE:
                case CP_DYNAMIC: case CP_INDY:
                    a[i] = in.readUnsignedShort();
                    b[i] = in.readUnsignedShort();
                    break;
                case CP_INT: case CP_FLOAT:
                    in.readInt();
                    break;
                case CP_LONG: case CP_DOUBLE:
                    in.readLong();
                    i++;
                    break;
                default:
                    /* Unknown tag: entry sizes are no longer known, so give up. */
                    return(null);
                }
            }
            ClassInfo ci = new ClassInfo();
            for(int i = 1; i < n; i++) {
                if(tag[i] == CP_CLASS) {
                    String nm = element(str(utf, tag, a[i], CP_UTF8));
                    if(nm != null)
                        ci.refs.add(nm);
                } else if(tag[i] == CP_FIELD) {
                    String owner = className(utf, tag, a, a[i]);
                    String field = fieldName(utf, tag, a, b[i]);
                    if((owner == null) || (field == null))
                        continue;
                    if(owner.equals(SPECNAME_CLASS))
                        ci.specs.add(field);
                    else if(owner.equals(NCONTEXT_CLASS) && field.equals(WORKSTATION_MAP))
                        ci.workstations = true;
                }
            }
            return(ci);
        } catch(IOException e) {
            return(null);
        }
    }

    private static String str(String[] utf, int[] tag, int idx, int want) {
        if((idx < 1) || (idx >= tag.length) || (tag[idx] != want))
            return(null);
        return(utf[idx]);
    }

    private static String className(String[] utf, int[] tag, int[] a, int idx) {
        if((idx < 1) || (idx >= tag.length) || (tag[idx] != CP_CLASS))
            return(null);
        return(str(utf, tag, a[idx], CP_UTF8));
    }

    private static String fieldName(String[] utf, int[] tag, int[] a, int idx) {
        if((idx < 1) || (idx >= tag.length) || (tag[idx] != CP_NAMETYPE))
            return(null);
        return(str(utf, tag, a[idx], CP_UTF8));
    }

    /** Unwraps array class names ("[Lfoo/Bar;" -> "foo/Bar"); null for primitive arrays. */
    private static String element(String nm) {
        if(nm == null)
            return(null);
        int d = 0;
        while((d < nm.length()) && (nm.charAt(d) == '['))
            d++;
        if(d == 0)
            return(nm);
        if((d < nm.length()) && (nm.charAt(d) == 'L') && nm.endsWith(";"))
            return(nm.substring(d + 1, nm.length() - 1));
        return(null);
    }
}
