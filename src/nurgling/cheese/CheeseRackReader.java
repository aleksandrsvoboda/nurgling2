package nurgling.cheese;

import haven.Gob;
import haven.Indir;
import haven.Loading;
import haven.OCache;
import haven.Resource;
import haven.WItem;
import haven.Widget;
import haven.Window;
import nurgling.NGItem;
import nurgling.NInventory;

import java.util.ArrayList;
import java.util.List;

/** Reads a cheese rack's shelves from its overlays, and the cheese names from its open window. */
public final class CheeseRackReader {
    /** Caption of a cheese rack's window. */
    public static final String WINDOW_CAP = "Rack";

    private CheeseRackReader() {}

    /**
     * What is on each shelf (index 0 = bottom), or null while a resource is still loading.
     * A shelf with no tray is null in the array.
     */
    public static CheeseRackMemory.Seen[] readShelves(Gob rack) {
        CheeseRackMemory.Seen[] seen = new CheeseRackMemory.Seen[CheeseRackShelves.SHELVES];
        for (Gob.Overlay ol : rack.ols) {
            if (!(ol.sm instanceof OCache.OlSprite))
                continue;
            OCache.OlSprite os = (OCache.OlSprite) ol.sm;
            String olres = resName(os.res);
            if (olres == null)
                return null;
            if (!CheeseRackShelves.EQ_RES.equals(olres))
                continue;
            CheeseRackShelves.Eq eq = CheeseRackShelves.decodeEq(os.sdt);
            if (eq == null)
                continue;
            int shelf = CheeseRackShelves.shelfOf(eq.bone);
            if (shelf < 0)
                continue;
            String tray = resName(rack.glob.sess.getres(eq.resid));
            // An eq can carry another eq (lantern stands do); walk down to the item.
            while (CheeseRackShelves.EQ_RES.equals(tray) && eq.sub != null && (eq = CheeseRackShelves.decodeEq(eq.sub)) != null)
                tray = resName(rack.glob.sess.getres(eq.resid));
            if (tray == null)
                return null;
            Integer tier = CheeseRackShelves.tierOf(tray);
            if (tier != null)
                seen[shelf] = new CheeseRackMemory.Seen(ol.id, tier);
        }
        return seen;
    }

    /** True when this inventory is the window of a cheese rack. */
    public static boolean isRackInventory(NInventory inv) {
        if (inv.parentGob == null || inv.parentGob.ngob == null || !CheeseRackShelves.RACK_RES.equals(inv.parentGob.ngob.name))
            return false;
        Window w = inv.getparent(Window.class);
        return w != null && WINDOW_CAP.equals(w.cap);
    }

    /**
     * Matches the open rack's trays to its shelves and remembers their cheese names. Runs on the
     * UI thread, so it walks the widgets and reads cached item info (never the blocking queries).
     * A name is only recorded where the match is certain: one tray of that tier, or every tray of
     * that tier holding the same cheese.
     */
    public static void learn(NInventory inv, CheeseRackMemory memory, long now) {
        Gob rack = inv.parentGob;
        if (rack.ngob.hash == null)
            return;
        CheeseRackMemory.Seen[] shelves = readShelves(rack);
        if (shelves == null)
            return;
        List<String> names = new ArrayList<>();
        List<Integer> tiers = new ArrayList<>();
        for (Widget w = inv.child; w != null; w = w.next) {
            if (!(w instanceof WItem) || !(((WItem) w).item instanceof NGItem))
                continue;
            NGItem item = (NGItem) ((WItem) w).item;
            if (item.name() == null)
                return;
            List<NGItem.NContent> content = item.content();
            if (content == null || content.isEmpty()) {
                names.add("");
                tiers.add(CheeseRackShelves.EMPTY);
                continue;
            }
            String name = content.get(0).name();
            Integer tier = CheeseRackShelves.tierOfName(name);
            if (tier == null)
                return;
            names.add(name);
            tiers.add(tier);
        }
        // The window must hold exactly the trays the overlays show, or it isn't this rack (yet).
        List<Integer> shelfTiers = new ArrayList<>();
        for (CheeseRackMemory.Seen s : shelves)
            if (s != null)
                shelfTiers.add(s.tier);
        List<Integer> a = new ArrayList<>(tiers), b = new ArrayList<>(shelfTiers);
        a.sort(null);
        b.sort(null);
        if (!a.equals(b))
            return;

        String[] out = new String[CheeseRackShelves.SHELVES];
        int[] outTiers = new int[CheeseRackShelves.SHELVES];
        for (int i = 0; i < CheeseRackShelves.SHELVES; i++) {
            if (shelves[i] == null || shelves[i].tier == CheeseRackShelves.EMPTY)
                continue;
            String only = null;
            boolean same = true;
            for (int j = 0; j < names.size(); j++) {
                if (tiers.get(j) != shelves[i].tier)
                    continue;
                if (only == null)
                    only = names.get(j);
                else if (!only.equals(names.get(j)))
                    same = false;
            }
            if (only != null && same) {
                out[i] = only;
                outTiers[i] = shelves[i].tier;
            }
        }
        memory.learn(rack.ngob.hash, out, outTiers, now);
    }

    private static String resName(Indir<Resource> res) {
        try {
            Resource r = res.get();
            return r == null ? null : r.name;
        } catch (Loading l) {
            return null;
        }
    }
}
