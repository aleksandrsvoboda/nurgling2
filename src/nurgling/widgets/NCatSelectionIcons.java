package nurgling.widgets;

import haven.Resource;
import haven.Tex;
import haven.TexI;
import haven.WItem;
import haven.res.lib.itemtex.ItemTex;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.util.function.Function;

class NCatSelectionIcons {
    static Tex itemIcon(JSONObject res) {
        return itemIcon(res, ItemTex::create);
    }

    static Tex itemIcon(JSONObject res, Function<JSONObject, BufferedImage> loader) {
        return itemIcon(res, loader, WItem.missing.layer(Resource.imgc).img);
    }

    static Tex itemIcon(JSONObject res, Function<JSONObject, BufferedImage> loader, BufferedImage fallback) {
        BufferedImage img = null;
        try {
            img = loader.apply(res);
        } catch (Resource.LoadFailedException e) {
            // VSpec can contain stale item resource paths; keep the category picker usable.
        }
        if (img == null)
            img = fallback;
        return new TexI(img);
    }
}
