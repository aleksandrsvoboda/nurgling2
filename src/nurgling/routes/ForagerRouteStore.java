package nurgling.routes;

import nurgling.NUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Filesystem persistence for named Forager routes (ForagerPath) - kept separate from any UI so
 *  listing/loading/saving/deleting a route doesn't require going through the Settings panel. */
public class ForagerRouteStore {
    private static final String ROUTES_DIR = "forager_paths";

    private ForagerRouteStore() {}

    /** Every saved route's bare name (no directory, no .json), sorted. */
    public static List<String> listRouteNames() {
        List<String> names = new ArrayList<>();
        File dir = NUtils.getDataFilePath(ROUTES_DIR).toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    names.add(f.getName().replace(".json", ""));
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Loads a route by bare name, or a fresh empty one under that name if it can't be loaded. */
    public static ForagerPath load(String name) {
        try {
            return ForagerPath.load(nameToFile(name));
        } catch (Exception e) {
            return new ForagerPath(name);
        }
    }

    public static void save(ForagerPath route) throws IOException {
        route.save(NUtils.getDataFile(ROUTES_DIR));
    }

    public static void delete(String name) throws IOException {
        Files.deleteIfExists(namePath(name));
    }

    /** PresetData.pathFile is a full file path; callers working in bare names (routeNames, the dropdowns) use this to convert. */
    public static String fileToName(String pathFile) {
        if (pathFile == null || pathFile.isEmpty()) return null;
        String name = new File(pathFile).getName();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        return name;
    }

    public static String nameToFile(String name) {
        return NUtils.getDataFile(ROUTES_DIR, name + ".json");
    }

    private static Path namePath(String name) {
        return NUtils.getDataFilePath(ROUTES_DIR, name + ".json");
    }
}
