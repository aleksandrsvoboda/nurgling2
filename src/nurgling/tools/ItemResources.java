package nurgling.tools;

import haven.*;
import haven.res.lib.layspr.Layered;
import org.json.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** One offline image archive shared by all item UIs, with normal game-resource fallback. */
public final class ItemResources {
    private ItemResources() {}
    private static final class Archive {
        final Map<String, byte[]> files = new HashMap<>();
        JSONObject names = new JSONObject();
        final Resource.Pool pool;
        Archive() {
            try(InputStream source = ItemResources.class.getResourceAsStream("/nurgling/resources/item-icons.bin")) {
                if(source != null) try(ZipInputStream zip = new ZipInputStream(source)) {
                    ZipEntry entry; int total = 0;
                    while((entry = zip.getNextEntry()) != null) {
                        if(entry.isDirectory()) continue;
                        byte[] data = zip.readNBytes(2 * 1024 * 1024 + 1);
                        total += data.length;
                        if(data.length > 2 * 1024 * 1024 || total > 64 * 1024 * 1024 || files.size() >= 10000)
                            throw new IOException("Icon archive exceeds size limits");
                        String name = entry.getName();
                        if(name.equals("index.json")) {
                            JSONObject index = new JSONObject(new String(data, StandardCharsets.UTF_8));
                            if(index.getInt("version") != 1) throw new IOException("Unsupported icon index");
                            names = index.getJSONObject("names");
                        } else if(name.endsWith(".res")) files.put(name.substring(0, name.length() - 4), data);
                        else if(name.startsWith("wiki/") && (name.endsWith(".png") || name.endsWith(".jpg"))) files.put(name, data);
                    }
                }
            } catch(IOException | JSONException e) {
                files.clear(); names = new JSONObject(); System.err.println("[ItemResources] " + e.getMessage());
            }
            pool = new Resource.Pool(name -> {
                byte[] data = files.get(name);
                if(data == null) throw new FileNotFoundException(name);
                return new ByteArrayInputStream(data);
            });
        }
    }
    private static final class Holder { static final Archive archive = new Archive(); }
    private static Resource load(String name) {
        Archive archive = Holder.archive;
        return archive.files.containsKey(name) ? archive.pool.loadwait(name) : Resource.remote().loadwait(name);
    }
    public static boolean contains(String name) { return Holder.archive.files.containsKey(name); }
    public static int count() { return Holder.archive.files.size(); }
    static JSONObject names() { return Holder.archive.names; }
    /** Called only from the shared icon loader's worker thread. Composite layers retain their offsets. */
    public static BufferedImage image(JSONObject descriptor) {
        if(descriptor.has("choice")) {
            JSONArray choices = descriptor.getJSONArray("choice");
            int count = Math.min(choices.length(), 16), columns = (int)Math.ceil(Math.sqrt(count));
            if(count == 0) return null;
            int rows = (count + columns - 1) / columns, cell = 32;
            BufferedImage result = new BufferedImage(columns * cell, rows * cell, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = result.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                for(int i = 0; i < count; i++) {
                    JSONObject option = choices.getJSONObject(i);
                    BufferedImage icon = option.length() == 0 ? null : image(option);
                    int x = (i % columns) * cell, y = (i / columns) * cell;
                    if(icon == null) { g.setColor(java.awt.Color.GRAY); g.drawRect(x + 8, y + 8, 15, 15); continue; }
                    double scale = (cell - 2.0) / Math.max(icon.getWidth(), icon.getHeight());
                    int w = Math.max(1, (int)(icon.getWidth() * scale)), h = Math.max(1, (int)(icon.getHeight() * scale));
                    g.drawImage(icon, x + (cell - w) / 2, y + (cell - h) / 2, w, h, null);
                }
            } finally { g.dispose(); }
            return result;
        }
        if(descriptor.has("image")) {
            byte[] data = Holder.archive.files.get(descriptor.getString("image"));
            if(data == null) return null;
            try { return ImageIO.read(new ByteArrayInputStream(data)); }
            catch(IOException e) { return null; }
        }
        if(descriptor.has("layer")) {
            List<Indir<Resource>> layers = new ArrayList<>();
            JSONArray names = descriptor.getJSONArray("layer");
            for(int i = 0; i < names.length(); i++) layers.add(load(names.getString(i)).indir());
            return new Layered(null, layers).image();
        }
        Resource.Image image = load(descriptor.getString("static")).layer(Resource.imgc);
        return image == null ? null : image.img;
    }
}
