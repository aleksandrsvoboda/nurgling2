package nurgling.widgets;

import haven.*;
import monitoring.ItemWatcher;
import nurgling.NCore;
import nurgling.NConfig;
import nurgling.NISBox;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.tools.LiquidContent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records stockpiles, barrels and cisterns in the storage database, which the inventory path
 * cannot see: their windows hold an ISBox or a RelCont, never an NInventory.
 *
 * <p>A zero-size tracker widget is added to the window. It binds to the gob the window was opened
 * on, keeps the latest reading of the window while it is open, and when the window goes away
 * replaces the gob's storage rows with that reading. The rows go into storageitems like any
 * container's, tagged in the coordinates column so readers can tell them apart:
 * <ul>
 *   <li>a stockpile gets one row per item, quality unknown (null), coordinates "#pile:&lt;i&gt;";</li>
 *   <li>a barrel or cistern gets one row, coordinates "#bulk:&lt;amount&gt; &lt;unit&gt;".</li>
 * </ul>
 */
public final class BulkStorageWindowExtension {
    public static final String PILE_TAG = "#pile:";
    public static final String BULK_TAG = "#bulk:";

    private static final String PILE_CAPTION = "Stockpile";
    private static final String PILE_GOB_PREFIX = "gfx/terobjs/stockpile";
    /** Window caption -> resource name of the gob such a window belongs to. */
    private static final Map<String, String> LIQUID_GOBS = Map.of(
        "Barrel", "gfx/terobjs/barrel",
        "Cistern", "gfx/terobjs/cistern"
    );

    /** How long a barrel must have been in view, overlays settled, before "no content" is believed. */
    private static final long BARREL_SETTLE_MS = 5_000;
    /** Barrels already cleared as empty this session, so each costs one DELETE at most. */
    private static final Set<String> sweptBarrels = ConcurrentHashMap.newKeySet();

    private BulkStorageWindowExtension() {
    }

    /** Called for every window added to the game UI. */
    public static void attach(Window wnd) {
        if (wnd == null || wnd.cap == null || !(Boolean) NConfig.get(NConfig.Key.ndbenable))
            return;
        if (PILE_CAPTION.equals(wnd.cap)) {
            wnd.add(new Tracker(true, PILE_GOB_PREFIX), Coord.z);
        } else {
            String gobName = LIQUID_GOBS.get(wnd.cap);
            if (gobName != null)
                wnd.add(new Tracker(false, gobName), Coord.z);
        }
    }

    /**
     * Called from the barrel's gob tick. A barrel shows its content as an overlay, so one that has
     * been in view a while with none is empty, and its stored content can go without opening it.
     */
    public static void observeBarrel(Gob gob) {
        String hash = gob.ngob.storageHash();
        if (hash == null || sweptBarrels.contains(hash))
            return;
        long now = System.currentTimeMillis();
        if (gob.ngob.bulkSeenAt == 0) {
            gob.ngob.bulkSeenAt = now;
            return;
        }
        if (now - gob.ngob.bulkSeenAt < BARREL_SETTLE_MS)
            return;
        for (Gob.Overlay ol : gob.ols) {
            // A sprite still loading may be the content; a loaded one with a resource is.
            if (ol.spr == null || ol.spr.res != null)
                return;
        }
        if (NCore.writeBulkStorage(hash, 0, null, Collections.emptyList()))
            sweptBarrels.add(hash);
    }

    /**
     * A stockpile window shows one icon for the whole pile, which for a mixed kind of pile (fish,
     * onions, boards) is only the general kind. Piles are rarely mixed in practice, so when the pile
     * stands in an area whose PUT list has exactly one entry of that kind, assume the pile holds that.
     * An entry is of that kind when it is the same resource, the same resource family
     * ("fish-mackerel" / "fish"), or one name contains the other ("Red Onion" / "Onion").
     * With no entry of that kind, a pile in an area that PUTs exactly one item is assumed to hold it.
     * @return the assumed item name, or null to keep what the window says
     */
    static String assumedPileItem(Gob pile, String windowName, String windowRes) {
        MCache map = pile.glob.map;
        Coord tile = pile.rc.floor(MCache.tilesz);
        MCache.Grid grid;
        try {
            grid = map.getgridt(tile);
        } catch (Loading l) {
            return null;
        }
        Coord local = tile.sub(grid.ul);
        List<NArea> areas;
        try {
            areas = new ArrayList<>(map.areas.values());
        } catch (java.util.ConcurrentModificationException e) {
            return null; // area sync is replacing areas right now; keep what the window says
        }
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        java.util.Set<String> sameKind = new java.util.LinkedHashSet<>();
        for (NArea area : areas) {
            if (area.isDisabled() || area.jout == null)
                continue;
            NArea.VArea va = area.space.space.get(grid.id);
            if (va == null || !va.area.contains(local))
                continue;
            for (int i = 0; i < area.jout.length(); i++) {
                JSONObject entry = area.jout.optJSONObject(i);
                if (entry == null)
                    continue;
                String name = entry.optString("name", null);
                if (name == null)
                    continue;
                all.add(name);
                if (sameKind(windowName, windowRes, name, entry.optString("static", null)))
                    sameKind.add(name);
            }
        }
        if (sameKind.size() == 1)
            return sameKind.iterator().next();
        // The window often names only a broad pile kind ("Stone" for slag, "Trash" for intestines)
        // that shares nothing with the item; a single-purpose area is then the best evidence.
        if (sameKind.isEmpty() && all.size() == 1)
            return all.iterator().next();
        return null;
    }

    private static boolean sameKind(String windowName, String windowRes, String name, String res) {
        if (windowRes != null && res != null && (windowRes.equals(res) || resFamily(windowRes).equals(resFamily(res))))
            return true;
        String a = windowName.toLowerCase(), b = name.toLowerCase();
        return a.contains(b) || b.contains(a);
    }

    /** "gfx/invobjs/fish-mackerel" -> "fish". */
    private static String resFamily(String res) {
        String base = res.substring(res.lastIndexOf('/') + 1);
        int dash = base.indexOf('-');
        return dash > 0 ? base.substring(0, dash) : base;
    }

    private static class Tracker extends Widget {
        private final boolean pile;
        private final String gobPrefix;
        /** How long a window may wait for its newly placed gob to show up. */
        private static final long BIND_WAIT_MS = 5_000;
        private Gob gob = null;
        private long bindStarted;
        private boolean dead = false;

        /* Latest reading. pileName == null / liquid == null: nothing read yet. */
        private String pileName = null;
        private String pileRes = null;
        private int pileCount = -1;
        private LiquidContent liquid = null;

        Tracker(boolean pile, String gobPrefix) {
            super(Coord.z);
            this.pile = pile;
            this.gobPrefix = gobPrefix;
        }

        private boolean accepts(Gob g) {
            return g != null && g.ngob != null && g.ngob.name != null && g.ngob.name.startsWith(gobPrefix);
        }

        @Override
        protected void added() {
            super.added();
            bindStarted = System.currentTimeMillis();
            bind();
        }

        /**
         * Find the gob this window belongs to: the right-clicked one, or for a pile just placed (its
         * window opens by itself, possibly before the gob arrives) the one at the placement.
         */
        private void bind() {
            gob = ui.core.takeClickedGob(this::accepts);
            if (gob == null)
                gob = ui.core.takePlacedGob(this::accepts);
            if (gob == null && !ui.core.placePending()
                    && ui.core.getLastActions() != null && accepts(ui.core.getLastActions().gob))
                gob = ui.core.getLastActions().gob;
            if (gob == null && System.currentTimeMillis() - bindStarted > BIND_WAIT_MS) {
                // Better unrecorded than filed under the wrong gob.
                dead = true;
            }
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            if (dead)
                return;
            if (gob == null) {
                bind();
                return;
            }
            if (pile)
                readPile();
            else
                readLiquid();
        }

        private void readPile() {
            for (Widget w = parent.child; w != null; w = w.next) {
                if (w instanceof NISBox) {
                    NISBox box = (NISBox) w;
                    if (pileName == null) {
                        try {
                            Resource res = box.itemres.get();
                            Resource.Tooltip tt = res.layer(Resource.tooltip);
                            if (tt != null) {
                                pileName = tt.text();
                                pileRes = res.name;
                            }
                        } catch (Loading l) {
                            return;
                        }
                    }
                    int count = box.calcCount();
                    if (count >= 0)
                        pileCount = count;
                    return;
                }
            }
        }

        private void readLiquid() {
            LiquidContent read = LiquidContent.read((Window) parent);
            if (read != null)
                liquid = read;
        }

        @Override
        public void dispose() {
            super.dispose();
            if (dead || gob == null || gob.ngob.storageHash() == null || gob.ngob.storageCoord() == null)
                return;
            String hash = gob.ngob.storageHash();
            List<ItemWatcher.Row> rows = new ArrayList<>();
            if (pile) {
                if (pileName == null || pileCount < 0)
                    return;
                // Taking the last item destroys the pile, and its gob with it.
                boolean gone = (ui != null && ui.sess != null && ui.sess.glob.oc.getgob(gob.id) == null);
                if (!gone) {
                    String item = assumedPileItem(gob, pileName, pileRes);
                    if (item == null)
                        item = pileName;
                    else
                        System.out.println("[BulkStorage] pile " + pileName + " assumed to hold " + item + " (area PUT)");
                    for (int i = 0; i < pileCount; i++) {
                        rows.add(new ItemWatcher.Row(
                            NUtils.calculateSHA256(hash + "|" + item + "|" + PILE_TAG + i),
                            item, null, PILE_TAG + i));
                    }
                }
            } else {
                if (liquid == null)
                    return;
                if (!liquid.isEmpty()) {
                    rows.add(new ItemWatcher.Row(
                        NUtils.calculateSHA256(hash + "|" + BULK_TAG),
                        liquid.name,
                        liquid.quality == null ? null : Double.parseDouble(Utils.odformat2(liquid.quality, 2)),
                        BULK_TAG + Utils.odformat2(liquid.amount, 2) + " " + liquid.unit));
                    // Its content is known again; let the passive check clear it once emptied.
                    sweptBarrels.remove(hash);
                }
            }
            System.out.println("[BulkStorage] " + (pile ? "pile " + pileName : "liquid " + (liquid.isEmpty() ? "empty" : liquid.name))
                + " -> " + rows.size() + " row(s) for " + hash.substring(0, 8));
            NCore.writeBulkStorage(hash, gob.ngob.storageGridId(), gob.ngob.storageCoord().toString(), rows);
        }
    }
}
