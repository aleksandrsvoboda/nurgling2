package nurgling.widgets.todo;

import haven.Tex;
import haven.Text;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rendered text for hand-drawn rows. Rows repaint every frame; rendering a fresh texture each time
 * would churn GPU memory, so each (font, colour, text) is rendered once and kept while it is in use.
 */
final class TexCache {
    private static final int CAPACITY = 600;

    private final Map<String, Tex> cache = new LinkedHashMap<String, Tex>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Tex> e) {
            if (size() > CAPACITY) {
                e.getValue().dispose();
                return true;
            }
            return false;
        }
    };

    Tex get(Text.Foundry f, String text, Color c) {
        String key = System.identityHashCode(f) + "|" + c.getRGB() + "|" + text;
        Tex t = cache.get(key);
        if (t == null) {
            t = f.render(text, c).tex();
            cache.put(key, t);
        }
        return t;
    }

    void clear() {
        for (Tex t : cache.values())
            t.dispose();
        cache.clear();
    }
}
