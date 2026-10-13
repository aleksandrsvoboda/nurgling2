package nurgling.areas;

import nurgling.NUtils;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Where area presets live. The player's own presets are one JSON file each in
 * {@code <data dir>/area_presets/}, shared by every profile and world, so a preset can be shared by
 * sending its file. Presets shipped with the client sit next to this class in the jar and are listed
 * in {@code presets/index.json}, because a directory inside a jar can't be listed reliably.
 */
public final class AreaPresetStore
{
    private static final String DIR = "area_presets";
    private static final String BUILTIN_INDEX = "presets/index.json";

    private AreaPresetStore() {}

    public static Path dir()
    {
        return NUtils.getDataFilePath(DIR);
    }

    /** Presets shipped with the client, in the order the index lists them. */
    public static List<AreaPreset> builtins()
    {
        List<AreaPreset> res = new ArrayList<>();
        String index = readResource(BUILTIN_INDEX);
        if (index == null)
            return res;
        JSONArray files;
        try {
            files = new JSONObject(index).getJSONArray("presets");
        } catch (JSONException e) {
            System.out.println("[AreaPresets] Bad built-in index: " + e.getMessage());
            return res;
        }
        for (int i = 0; i < files.length(); i++) {
            String file = files.getString(i);
            String text = readResource("presets/" + file);
            if (text == null)
                continue;
            try {
                AreaPreset p = AreaPreset.fromJson(new JSONObject(text));
                p.builtin = true;
                p.file = file;
                res.add(p);
            } catch (JSONException | IllegalArgumentException e) {
                System.out.println("[AreaPresets] Skipping built-in preset " + file + ": " + e.getMessage());
            }
        }
        return res;
    }

    private static String readResource(String name)
    {
        try (InputStream in = AreaPresetStore.class.getResourceAsStream(name)) {
            if (in == null)
                return null;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0)
                buf.write(chunk, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            System.out.println("[AreaPresets] Can't read " + name + ": " + e.getMessage());
            return null;
        }
    }

    /** The player's presets, A-Z. A file that doesn't parse is skipped, not fatal. */
    public static List<AreaPreset> user()
    {
        List<AreaPreset> res = new ArrayList<>();
        Path dir = dir();
        if (!Files.isDirectory(dir))
            return res;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                try {
                    AreaPreset p = load(f);
                    p.file = f.getFileName().toString();
                    res.add(p);
                } catch (IOException | JSONException | IllegalArgumentException e) {
                    System.out.println("[AreaPresets] Skipping " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("[AreaPresets] Can't list " + dir + ": " + e.getMessage());
        }
        res.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        return res;
    }

    public static AreaPreset load(Path file) throws IOException
    {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return AreaPreset.fromJson(new JSONObject(text));
    }

    /** Writes a new user preset under a file name made from its name. */
    public static void saveNew(AreaPreset p) throws IOException
    {
        Files.createDirectories(dir());
        String base = slug(p.name);
        String file = base + ".json";
        for (int i = 2; Files.exists(dir().resolve(file)); i++)
            file = base + "-" + i + ".json";
        p.builtin = false;
        p.file = file;
        write(p);
    }

    /** Rewrites a user preset in its own file, e.g. after a rename. */
    public static void write(AreaPreset p) throws IOException
    {
        if (p.builtin || p.file == null)
            throw new IOException("Not a user preset: " + p.name);
        NFileUtils.writeAtomically(dir().resolve(p.file).toString(), p.toJson().toString(2));
    }

    public static void delete(AreaPreset p) throws IOException
    {
        if (p.builtin || p.file == null)
            return;
        Files.deleteIfExists(dir().resolve(p.file));
        Files.deleteIfExists(dir().resolve(p.file + ".bak"));
    }

    public static void export(AreaPreset p, Path target) throws IOException
    {
        NFileUtils.writeAtomically(target.toString(), p.toJson().toString(2));
    }

    /** Copies a preset file from elsewhere into the presets folder; throws if it isn't one. */
    public static AreaPreset importFile(Path source) throws IOException
    {
        AreaPreset p = load(source);
        saveNew(p);
        return p;
    }

    /** A file name for a preset name: letters, digits, '-' and '_' only. */
    public static String slug(String name)
    {
        StringBuilder sb = new StringBuilder();
        for (char c : name.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_')
                sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_')
                sb.append('_');
        }
        String s = sb.toString();
        while (s.endsWith("_"))
            s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "preset" : s;
    }
}
