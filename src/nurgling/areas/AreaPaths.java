package nurgling.areas;

import java.util.*;

/**
 * Area folders are not stored anywhere: a folder is the leading part of its areas' {@link NArea#path}.
 * The top level is {@code ""} and a folder two levels down is {@code "/Farm/Fields"}.
 *
 * Every prefix test must stop at a "/" - a plain {@code startsWith("/Farm")} also matches
 * {@code "/Farmhouse"}, which used to make renaming or removing one folder hit its neighbour too.
 */
public final class AreaPaths
{
    public static final String ROOT = "";

    /** Siblings A-Z ignoring case, each folder directly followed by everything inside it. */
    public static final Comparator<String> ORDER = (a, b) -> {
        String[] as = segments(a), bs = segments(b);
        for(int i = 0; (i < as.length) && (i < bs.length); i++) {
            int c = String.CASE_INSENSITIVE_ORDER.compare(as[i], bs[i]);
            if(c != 0)
                return c;
        }
        if(as.length != bs.length)
            return Integer.compare(as.length, bs.length);
        return a.compareTo(b);
    };

    private AreaPaths() {}

    private static String[] segments(String path) {
        return path.isEmpty() ? new String[0] : path.substring(1).split("/", -1);
    }

    /** Whatever the user typed as a folder name, as "a" or "a/b" with no empty parts; "" if nothing is left. */
    public static String clean(String name) {
        StringBuilder sb = new StringBuilder();
        for(String part : name.split("/")) {
            part = part.trim();
            if(part.isEmpty())
                continue;
            if(sb.length() > 0)
                sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }

    public static String join(String parent, String name) {
        return parent + "/" + name;
    }

    public static String parent(String path) {
        int i = path.lastIndexOf('/');
        return (i <= 0) ? ROOT : path.substring(0, i);
    }

    public static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public static int depth(String path) {
        return segments(path).length;
    }

    /** True for the folder itself and anything inside it. */
    public static boolean isUnder(String path, String folder) {
        return folder.isEmpty() || path.equals(folder) || path.startsWith(folder + "/");
    }

    /** Swaps the leading {@code from} for {@code to}; paths outside {@code from} come back unchanged. */
    public static String reparent(String path, String from, String to) {
        if(!isUnder(path, from))
            return path;
        return to + path.substring(from.length());
    }

    /** Whether moving {@code folder} into {@code dest} changes anything and stays out of itself. */
    public static boolean canMoveFolder(String folder, String dest) {
        return !isUnder(dest, folder) && !parent(folder).equals(dest);
    }

    /**
     * Whether dropping these areas (given by their current paths) and folders into {@code dest}
     * is allowed and moves at least one of them. One folder that would land inside itself
     * spoils the whole drop.
     */
    public static boolean canDrop(Collection<String> areaPaths, Collection<String> folders, String dest) {
        boolean moves = false;
        for(String folder : folders) {
            if(isUnder(dest, folder))
                return false;
            if(!parent(folder).equals(dest))
                moves = true;
        }
        for(String path : areaPaths) {
            if(!path.equals(dest))
                moves = true;
        }
        return moves;
    }

    /** Every folder from the top down to {@code path}: "/Base/Farm" gives "", "/Base", "/Base/Farm". */
    public static List<String> crumbs(String path) {
        List<String> res = new ArrayList<>();
        res.add(ROOT);
        String[] segs = segments(path);
        StringBuilder sb = new StringBuilder();
        for(String seg : segs) {
            sb.append('/').append(seg);
            res.add(sb.toString());
        }
        return res;
    }

    /** Every folder some path runs through (the top level excluded), in {@link #ORDER}. */
    public static SortedSet<String> allFolders(Collection<String> paths) {
        SortedSet<String> res = new TreeSet<>(ORDER);
        for(String path : paths) {
            for(String crumb : crumbs(path)) {
                if(!crumb.isEmpty())
                    res.add(crumb);
            }
        }
        return res;
    }

    /** The folders directly inside {@code parent} that some path runs through, A-Z. */
    public static List<String> childFolders(Collection<String> paths, String parent) {
        SortedSet<String> res = new TreeSet<>(ORDER);
        for(String path : paths) {
            if(path.equals(parent) || !isUnder(path, parent))
                continue;
            int end = path.indexOf('/', parent.length() + 1);
            res.add((end < 0) ? path : path.substring(0, end));
        }
        return new ArrayList<>(res);
    }
}
