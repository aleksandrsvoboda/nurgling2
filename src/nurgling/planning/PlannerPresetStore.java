package nurgling.planning;

import nurgling.NUtils;
import nurgling.areas.AreaPresetStore;
import nurgling.tools.NFileUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Where Base planner presets live: one JSON file each in {@code <data dir>/planner_presets/}, shared
 * by every profile and world, so a preset can be shared by sending its file. There are no built-in
 * presets; players make their own.
 */
public final class PlannerPresetStore
{
    private static final String DIR = "planner_presets";

    private PlannerPresetStore() {}

    public static Path dir()
    {
        return NUtils.getDataFilePath(DIR);
    }

    /** The player's presets, A-Z. A file that doesn't parse is skipped, not fatal. */
    public static List<PlannerPreset> all()
    {
        List<PlannerPreset> res = new ArrayList<>();
        Path dir = dir();
        if (!Files.isDirectory(dir))
            return res;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                try {
                    PlannerPreset p = load(f);
                    p.file = f.getFileName().toString();
                    res.add(p);
                } catch (IOException | JSONException | IllegalArgumentException e) {
                    System.out.println("[PlannerPresets] Skipping " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("[PlannerPresets] Can't list " + dir + ": " + e.getMessage());
        }
        res.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        return res;
    }

    public static PlannerPreset load(Path file) throws IOException
    {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return PlannerPreset.fromJson(new JSONObject(text));
    }

    /** Writes a new preset under a file name made from its name. */
    public static void saveNew(PlannerPreset p) throws IOException
    {
        Files.createDirectories(dir());
        String base = AreaPresetStore.slug(p.name);
        String file = base + ".json";
        for (int i = 2; Files.exists(dir().resolve(file)); i++)
            file = base + "-" + i + ".json";
        p.file = file;
        write(p);
    }

    /** Rewrites a preset in its own file, e.g. after a rename. */
    public static void write(PlannerPreset p) throws IOException
    {
        if (p.file == null)
            throw new IOException("Preset has no file: " + p.name);
        NFileUtils.writeAtomically(dir().resolve(p.file).toString(), p.toJson().toString(2));
    }

    public static void delete(PlannerPreset p) throws IOException
    {
        if (p.file == null)
            return;
        Files.deleteIfExists(dir().resolve(p.file));
        Files.deleteIfExists(dir().resolve(p.file + ".bak"));
    }

    public static void export(PlannerPreset p, Path target) throws IOException
    {
        NFileUtils.writeAtomically(target.toString(), p.toJson().toString(2));
    }

    /** Copies a preset file from elsewhere into the presets folder; throws if it isn't one. */
    public static PlannerPreset importFile(Path source) throws IOException
    {
        PlannerPreset p = load(source);
        saveNew(p);
        return p;
    }
}
