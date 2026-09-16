package nurgling.widgets.cookbook;

import haven.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe icons, loaded in the background. Waiting for a resource on the UI thread freezes the
 * game, so the table asks every frame and draws a placeholder until the icon is ready.
 */
final class IconCache {
    private static final Map<String, Defer.Future<Tex>> cache = new HashMap<>();

    private IconCache() {
    }

    /** The icon of a recipe's resource name, or null while it loads or when it cannot be loaded. */
    static Tex get(String resName) {
        if((resName == null) || resName.isEmpty())
            return null;
        Defer.Future<Tex> f;
        synchronized(cache) {
            f = cache.get(resName);
            if(f == null)
                cache.put(resName, f = Defer.later(() -> load(resName)));
        }
        try {
            return f.get();
        } catch(Loading l) {
            return null;
        } catch(Defer.DeferredException e) {
            return null;
        }
    }

    /* Runs on a Defer thread, where waiting for a resource is fine. A name joined with '+' holds the
     * layers of a composite sprite (meat, fish ...), drawn over each other at their offsets on a
     * canvas that covers them all, as Layered.image() does. */
    private static Tex load(String resName) {
        String[] names = resName.split("\\+");
        List<Resource.Image> layers = new ArrayList<>();
        for(String name : names) {
            try {
                Resource.Image img = Resource.remote().loadwait(name.trim()).layer(Resource.imgc);
                if(img != null)
                    layers.add(img);
            } catch(Resource.LoadException | Resource.BadResourceException e) {
                System.out.println("[Cookbook] cannot load icon layer " + name + ": " + e.getMessage());
            }
        }
        if(layers.isEmpty())
            return null;
        if(names.length == 1)
            return new TexI(layers.get(0).img);
        int w = 0, h = 0;
        for(Resource.Image img : layers) {
            BufferedImage li = img.scaled();
            w = Math.max(w, img.o.x + li.getWidth());
            h = Math.max(h, img.o.y + li.getHeight());
        }
        if((w < 1) || (h < 1))
            return null;
        BufferedImage combined = TexI.mkbuf(Coord.of(w, h));
        Graphics2D g = combined.createGraphics();
        for(Resource.Image img : layers)
            g.drawImage(img.scaled(), img.o.x, img.o.y, null);
        g.dispose();
        return new TexI(combined);
    }
}
