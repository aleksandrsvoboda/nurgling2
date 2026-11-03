package nurgling.scripting;

import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.actions.bots.registry.ParamSpec;
import nurgling.scripting.api.Schema;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Build-time tool: writes api-schema.json (every op) and bots.json (the bot catalogue) for the
 * SDK and docs generator. Run by "ant api-schema"; needs no game session.
 */
public final class SchemaExporter {
    public static void main(String[] args) throws IOException {
        File dir = new File(args.length > 0 ? args[0] : "docs/scripting/schema");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        write(new File(dir, "api-schema.json"), Schema.build().toString(2));
        write(new File(dir, "bots.json"), bots().toString(2));
        System.out.println("[apigen] wrote " + dir.getPath());
        System.exit(0);
    }

    private static JSONArray bots() {
        JSONArray res = new JSONArray();
        for (BotDescriptor d : BotRegistry.all()) {
            if (d.type == BotDescriptor.BotType.SCRIPTS) continue;
            JSONObject j = new JSONObject();
            j.put("id", d.id);
            j.put("title", title(d.titleKey, d));
            j.put("description", d.descriptionKey == null ? "" : title(d.descriptionKey, d));
            j.put("type", d.type.name().toLowerCase());
            j.put("menu", d.allowedAsItemInBotMenu);
            j.put("scenario_step", d.allowedAsStepInScenario);
            j.put("interactive", d.isInteractive());
            j.put("class", d.clazz.getName());
            JSONArray ps = new JSONArray();
            for (ParamSpec p : d.params()) {
                JSONObject pj = new JSONObject().put("name", p.name).put("type", p.type.name().toLowerCase()).put("doc", p.doc).put("required", p.required());
                if (p.def != null) pj.put("default", p.def);
                if (!p.values.isEmpty()) pj.put("values", new JSONArray(p.values));
                ps.put(pj);
            }
            j.put("params", ps);
            res.put(j);
        }
        return res;
    }

    /** English text for a title or description key, read from the bundled messages file. */
    private static String title(String key, BotDescriptor d) {
        if (key == null || !key.startsWith("bot.")) return key;
        try (java.io.InputStream in = SchemaExporter.class.getResourceAsStream("/lang/messages.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                String v = p.getProperty(key);
                if (v != null) return v;
            }
        } catch (IOException e) {
            // Fall back to the key.
        }
        return key;
    }

    private static void write(File f, String s) throws IOException {
        Files.write(f.toPath(), (s + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private SchemaExporter() {}
}
