package nurgling.forage;

import haven.Coord;
import haven.GItem;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.WItem;
import haven.Widget;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NConfig;
import nurgling.NGItem;
import nurgling.NGameUI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notices the player picking a forageable and records it as a {@link ForageFind}.
 *
 * <p>The sequence it watches for:
 * <ol>
 *   <li>a right-click on a gob - {@link #noteMapClick}, fed from {@code NMapView.wdgmsg}, which every gob
 *       click goes through: the player's own, the Q quick action and every bot helper;</li>
 *   <li>"Pick" chosen in the flower menu that click opened - {@link #onPetal} remembers where the gob
 *       stood and what the inventory held;</li>
 *   <li>the gob disappearing - that is what tells a forageable from, say, a garden pot, which stays put;</li>
 *   <li>new items arriving - the difference against the remembered inventory gives the item and its
 *       quality.</li>
 * </ol>
 *
 * <p>One per session, ticked from {@code NGameUI.tick} on that session's UI thread. The inventory is read
 * by walking the widgets and the cached {@link NGItem} fields: the {@code NInventory} queries block on the
 * core, which is this same thread.
 */
public class ForageRecorder {
    /** How long a chosen "Pick" may wait for its gob to go: covers walking to a herb clicked from afar. */
    private static final long ARM_MS = 60_000;
    /** How long after the gob is gone the items may take to arrive and show their quality. */
    private static final long COLLECT_MS = 4_000;

    private final NGameUI gui;
    /** The gob the latest right-click on the map was on, or -1. */
    private volatile long clickedGobId = -1;
    private volatile Pending pending = null;

    private static final class Pending {
        final long gobId;
        final String gobRes;
        final long gridId;
        final int ox, oy;
        /** The items there were, and the (resource, quality) counts of them, when "Pick" was chosen. */
        final java.util.Set<GItem> beforeItems;
        final Map<String, Integer> before;
        final long deadline;
        volatile long collectUntil = -1;

        Pending(long gobId, String gobRes, long gridId, int ox, int oy, List<Entry> inv, long deadline) {
            this.gobId = gobId;
            this.gobRes = gobRes;
            this.gridId = gridId;
            this.ox = ox;
            this.oy = oy;
            this.beforeItems = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            this.before = new HashMap<>();
            for(Entry e : inv) {
                beforeItems.add(e.item);
                before.merge(e.key(), 1, Integer::sum);
            }
            this.deadline = deadline;
        }
    }

    public ForageRecorder(NGameUI gui) {
        this.gui = gui;
    }

    /** Off unless the player turned it on in Map Tools. */
    public static boolean enabled() {
        Object val = NConfig.get(NConfig.Key.recordForageFinds);
        return (val instanceof Boolean) && (Boolean) val;
    }

    public static void enabled(boolean val) {
        NConfig.set(NConfig.Key.recordForageFinds, val);
    }

    /**
     * A {@code "click"} message the map is sending to the server. Its arguments are
     * {@code (pc, mc, button, mods[, olflag, gobid, gobrc, olid, meshid])}; only a right-click on a gob
     * matters here.
     */
    public void noteMapClick(Object[] args) {
        if(args.length < 3 || !(args[2] instanceof Integer))
            return;
        int button = (Integer) args[2];
        if(button == 3 && args.length >= 6 && args[5] instanceof Integer)
            clickedGobId = Integer.toUnsignedLong((Integer) args[5]);
        else
            clickedGobId = -1;
    }

    /** A flower menu option was chosen. "Pick" on the gob that was just right-clicked arms the recorder. */
    public void onPetal(String name) {
        if(!"Pick".equals(name) || clickedGobId < 0 || !enabled())
            return;
        if(gui.ui == null || gui.ui.sess == null || gui.map == null)
            return;
        Gob gob = gui.ui.sess.glob.oc.getgob(clickedGobId);
        if(gob == null || gob.ngob == null || gob.ngob.name == null)
            return;
        /* A bot picks the next herb as soon as the last one is gone. If that pick's items are already in,
         * record them now rather than lose them to the new arming. */
        Pending prev = pending;
        if(prev != null && prev.collectUntil >= 0 && prev.gobId != gob.id)
            tryRecord(prev, true);
        try {
            Coord tc = gob.rc.floor(MCache.tilesz);
            MCache.Grid grid = gui.map.glob.map.getgrid(tc.div(MCache.cmaps));
            Coord off = tc.sub(grid.ul);
            pending = new Pending(gob.id, gob.ngob.name, grid.id, off.x, off.y, inventoryEntries(),
                System.currentTimeMillis() + ARM_MS);
        } catch(Loading e) {
            // The gob's grid is not loaded here, so there is no position to file it under.
            pending = null;
        }
    }

    public void tick() {
        Pending p = pending;
        if(p == null)
            return;
        long now = System.currentTimeMillis();
        if(p.collectUntil < 0) {
            if(gui.ui.sess.glob.oc.getgob(p.gobId) != null) {
                if(now > p.deadline)
                    pending = null;
                return;
            }
            p.collectUntil = now + COLLECT_MS;
        }
        boolean last = now > p.collectUntil;
        if((tryRecord(p, last) || last) && pending == p)
            pending = null;
    }

    /**
     * Compare the inventory with the one remembered when "Pick" was chosen and record what the pick
     * produced. Returns false while the items are still arriving or still have no quality.
     */
    private boolean tryRecord(Pending p, boolean last) {
        /* An item is old if it is the same widget as before, or - since the server may rebuild widgets
         * when a stack forms - if an old item with the same resource and quality is still unaccounted for.
         * Same-widget matches go first so they cannot use up another item's budget. */
        Map<String, List<Entry>> added = new HashMap<>();
        Map<String, Integer> left = new HashMap<>(p.before);
        List<Entry> unknown = new ArrayList<>();
        for(Entry e : inventoryEntries()) {
            if(p.beforeItems.contains(e.item))
                left.merge(e.key(), -1, Integer::sum);
            else
                unknown.add(e);
        }
        for(Entry e : unknown) {
            Integer n = left.get(e.key());
            if(n != null && n > 0) {
                left.put(e.key(), n - 1);
                continue;
            }
            added.computeIfAbsent(e.res, k -> new ArrayList<>()).add(e);
        }
        if(added.isEmpty()) {
            if(last)
                System.out.println("[Forage] " + p.gobRes + " was picked but nothing new reached the inventory");
            return false;
        }

        List<Entry> items = added.get(matchingRes(p.gobRes, added));
        if(items == null) {
            if(last)
                System.out.println("[Forage] could not tell which of " + added.keySet() + " came from " + p.gobRes);
            return false;
        }

        double best = -1;
        String name = null;
        boolean complete = true;
        for(Entry e : items) {
            if(e.quality == null)
                complete = false;
            else
                best = Math.max(best, e.quality);
            if(e.name == null)
                complete = false;
            else
                name = e.name;
        }
        if(!complete && !last)
            return false;
        if(best < 0) {
            System.out.println("[Forage] " + p.gobRes + " produced " + items.get(0).res + " but its quality never arrived");
            return false;
        }
        if(name == null)
            name = nameFromRes(items.get(0).res);

        ForageStore store = gui.forageStore;
        if(store == null)
            return true;
        ForageFind f = new ForageFind(ForageFind.makeId(store.profile(), p.gridId, p.ox, p.oy, p.gobRes, p.gobId),
            p.gridId, p.ox, p.oy, p.gobRes, items.get(0).res, name, best, items.size(),
            System.currentTimeMillis(), gui.chrid, 0);
        store.add(f);
        System.out.println(String.format("[Forage] recorded %s q%.1f x%d at grid %d (%d,%d)",
            name, best, items.size(), p.gridId, p.ox, p.oy));
        return true;
    }

    /**
     * Which of the newly arrived item types the gob produced: the one sharing the gob's resource basename
     * (herbs/blueberry gives invobjs/herbs/blueberry), else the only one there is.
     */
    private static String matchingRes(String gobRes, Map<String, List<Entry>> added) {
        String base = basename(gobRes);
        for(String res : added.keySet()) {
            if(basename(res).equals(base))
                return res;
        }
        return (added.size() == 1) ? added.keySet().iterator().next() : null;
    }

    private static String basename(String res) {
        int i = res.lastIndexOf('/');
        return (i < 0) ? res : res.substring(i + 1);
    }

    private static String nameFromRes(String res) {
        String b = basename(res);
        return b.isEmpty() ? res : Character.toUpperCase(b.charAt(0)) + b.substring(1);
    }

    // -------------------- Reading the inventory --------------------

    private static final class Entry {
        final GItem item;
        final String res;
        final Float quality;
        final String name;

        Entry(GItem item, String res, Float quality, String name) {
            this.item = item;
            this.res = res;
            this.quality = quality;
            this.name = name;
        }

        String key() {
            return res + "|" + quality;
        }
    }

    /** Every item in the main inventory, inside its stacks, and in the hand. */
    private List<Entry> inventoryEntries() {
        List<Entry> out = new ArrayList<>();
        if(gui.maininv != null) {
            for(Widget w = gui.maininv.child; w != null; w = w.next) {
                if(w instanceof WItem)
                    addItem(out, ((WItem) w).item);
            }
        }
        if(gui.vhand != null)
            addItem(out, gui.vhand.item);
        return out;
    }

    private static void addItem(List<Entry> out, GItem item) {
        if(item == null)
            return;
        /* A stack's members are not children of the inventory; they are only reachable through the
         * stack item's contents. The stack item itself is not an item with a quality. */
        if(item.contents instanceof ItemStack) {
            for(GItem gi : new ArrayList<>(((ItemStack) item.contents).order))
                addOne(out, gi);
            return;
        }
        addOne(out, item);
    }

    private static void addOne(List<Entry> out, GItem item) {
        try {
            Resource res = item.res.get();
            if(item instanceof NGItem) {
                NGItem ng = (NGItem) item;
                out.add(new Entry(item, res.name, ng.quality, ng.name()));
            } else {
                out.add(new Entry(item, res.name, null, null));
            }
        } catch(Loading e) {
            // Not loaded yet; it will be counted on a later tick.
        }
    }
}
