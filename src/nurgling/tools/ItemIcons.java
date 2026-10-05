package nurgling.tools;

import haven.*;
import org.json.*;
import java.util.*;
import java.awt.image.BufferedImage;

/** Shared resource identity, composite-sprite loader and async texture cache for all item catalogs. */
public final class ItemIcons {
    private ItemIcons() {}
    private static final Map<String, JSONObject> names = new HashMap<>();
    private static final Map<String, Defer.Future<Tex>> textures = new HashMap<>();
    private static final Map<String, JSONObject> fallbacks = new HashMap<>();
    private static Defer.Future<Index> pendingIndex;
    private static Index readyIndex;
    private static final class Index {
        final Map<String,JSONObject> names=new HashMap<>(),categories=new HashMap<>();
        Index() {
            List<String> keys=new ArrayList<>(VSpec.categories.keySet());Collections.sort(keys);
            for(String category:keys) {
                if(!VSpec.categories.get(category).isEmpty())
                    categories.put(normalize(category),new JSONObject(VSpec.categories.get(category).get(0).toString()));
                for(JSONObject item:VSpec.categories.get(category))
                    names.putIfAbsent(normalize(item.optString("name")),new JSONObject(item.toString()));
            }
            JSONObject archive=ItemResources.names();
            for(String name:archive.keySet())names.putIfAbsent(normalize(name),archive.getJSONObject(name));
        }
    }
    private static String normalize(String name) {
        return name == null ? "" : name.replaceAll("[\\u200b-\\u200f\\ufeff]", "").replace('_', ' ')
            .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).replace("ß", "ss");
    }
    private static synchronized Index index() {
        if(readyIndex!=null)return readyIndex;
        if(pendingIndex==null)pendingIndex=Defer.later(Index::new);
        try {return readyIndex=pendingIndex.get();}
        catch(Loading pending){return null;}
    }
    /** Native item descriptors may add names missing from the offline catalog. */
    public static synchronized void register(String name, JSONObject descriptor) {
        index();
        if(name != null && !name.isEmpty() && !key(descriptor).isEmpty())
            names.put(normalize(name), new JSONObject(descriptor.toString()));
    }
    /** Menu artwork must not replace an item sprite or its composed animal/material layers. */
    public static synchronized void registerFallback(String name, String resource) {
        index();
        fallbacks.putIfAbsent(normalize(name), new JSONObject().put("static", resource));
    }
    public static synchronized JSONObject descriptor(String resource, String name, boolean category) {
        Index catalog=index();
        // A menu fallback cannot be resolved until the offline catalog is known.
        // Polling callers display their neutral placeholder while it loads.
        if(catalog==null)return names.containsKey(normalize(name))?new JSONObject(names.get(normalize(name)).toString()):null;
        if(resource != null && resource.startsWith("wiki-choice:")) {
            nurgling.craft.AtlasCatalog.Material group = new nurgling.craft.AtlasCatalog.Material(resource, name, 1, false, true);
            JSONArray icons = new JSONArray();
            for(nurgling.craft.AtlasCatalog.Material option : group.choices) {
                JSONObject icon = descriptor(option.resource, option.name, option.category);
                icons.put(icon == null ? new JSONObject() : icon);
            }
            return new JSONObject().put("choice", icons);
        }
        JSONObject found = names.get(normalize(name));
        if(found==null)found=catalog.names.get(normalize(name));
        if(found == null && resource != null && resource.startsWith("wiki-item:")) {
            String alias=normalize(resource.substring("wiki-item:".length()));
            found=names.get(alias);
            if(found==null)found=catalog.names.get(alias);
        }
        // The wiki does not flag every material family as generic (e.g. Board/Block of Wood).
        if(found == null) {
            String key = normalize(name).replaceFirst("^any ", "");
            found = catalog.categories.get(key);
        }
        if(found==null)found=fallbacks.get(normalize(name));
        if(found == null && resource != null && (resource.startsWith("gfx/") || resource.startsWith("paginae/") || resource.startsWith("nurgling/")))
            found = new JSONObject().put("static", resource);
        return found == null ? null : new JSONObject(found.toString());
    }
    public static String key(JSONObject descriptor) {
        if(descriptor == null) return "";
        if(descriptor.has("choice")) return "choice:" + descriptor.getJSONArray("choice").toString();
        if(descriptor.has("layer")) return "layers:" + descriptor.getJSONArray("layer").toString();
        if(descriptor.has("image")) return "image:" + descriptor.getString("image");
        return descriptor.has("static") ? "static:" + descriptor.getString("static") : "";
    }
    public static Tex get(String resource, String name, boolean category) { return get(descriptor(resource, name, category)); }
    public static Tex getResource(String paths) {
        if(paths == null || paths.isEmpty()) return null;
        String[] layers = paths.split("\\+");
        JSONObject descriptor = new JSONObject();
        if(layers.length == 1) descriptor.put("static", paths);
        else {
            JSONArray array = new JSONArray();
            for(String layer : layers) array.put(layer.trim());
            descriptor.put("layer", array);
        }
        return get(descriptor);
    }
    /** null means missing or still loading. Callers draw a neutral fallback, never block the UI. */
    public static Tex get(JSONObject descriptor) {
        String key = key(descriptor);
        if(key.isEmpty()) return null;
        Defer.Future<Tex> future;
        synchronized(textures) {
            future = textures.get(key);
            if(future == null) {
                JSONObject frozen = new JSONObject(descriptor.toString());
                textures.put(key, future = Defer.later(() -> {
                    try {
                        BufferedImage image = ItemResources.image(frozen);
                        return image == null ? null : new TexI(image);
                    } catch(Resource.LoadException | Resource.BadResourceException | NullPointerException e) {
                        return null;
                    }
                }));
            }
        }
        try { return future.get(); }
        catch(Loading | Defer.DeferredException e) { return null; }
    }
    public static void draw(GOut g, Tex icon, Coord at, int side) {
        if(icon == null) {
            g.chcolor(nurgling.styles.UITheme.MUTED);
            g.rect(at.add(side / 4, side / 4), Coord.of(side / 2, side / 2));
            g.chcolor();
        } else {
            double scale = side / (double)Math.max(icon.sz().x, icon.sz().y);
            Coord size = Coord.of(Math.max(1, (int)(icon.sz().x * scale)), Math.max(1, (int)(icon.sz().y * scale)));
            g.image(icon, at.add(Coord.of(side, side).sub(size).div(2)), size);
        }
    }
}
