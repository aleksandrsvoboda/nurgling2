package nurgling.discovery;

import haven.Equipory;
import haven.GItem;
import haven.GameUI;
import haven.Gob;
import haven.ItemInfo;
import haven.Loading;
import haven.Resource;
import haven.Widget;
import haven.resutil.Curiosity;
import nurgling.NGItem;
import nurgling.NUI;
import nurgling.areas.NContext;
import nurgling.widgets.NCharacterInfo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Watches one session's items and LP and writes the verdicts of {@link DiscoveryCorrelator} into
 * the character's {@link DiscoveryLedger}. Owned by that session's NCharacterInfo, and always
 * reached through the item's own UI, never the active session, so background sessions keep apart.
 */
public final class DiscoveryTracker {
    /** An item arriving this soon after a progress bar was showing was made by the player. */
    private static final long PROGRESS_MS = 2000;
    /** ... or this soon after the player right-clicked a gob that yields items (walking there included). */
    private static final long GOB_CLICK_MS = 20_000;
    /** Items arriving this soon after login are the inventory being sent, not new arrivals. */
    private static final long LOGIN_MS = 5000;
    private static final long SAVE_MS = 3000;

    private final NCharacterInfo info;
    private final DiscoveryLedger ledger;
    private final DiscoveryCorrelator correlator = new DiscoveryCorrelator();
    private final List<Consumer<DiscoveryCorrelator.Verdict>> listeners = new CopyOnWriteArrayList<>();
    private final long startedAt = System.currentTimeMillis();
    private long lastProgAt = 0;
    private long lastSave = 0;
    private int lp = -1;

    public DiscoveryTracker(NCharacterInfo info, DiscoveryLedger ledger) {
        this.info = info;
        this.ledger = ledger;
    }

    public DiscoveryLedger ledger() {
        return ledger;
    }

    /** The tracker of the session a widget belongs to, or null. */
    public static DiscoveryTracker of(Widget w) {
        if (w == null || !(w.ui instanceof NUI))
            return null;
        NUI ui = (NUI) w.ui;
        if (ui.sessInfo == null || ui.sessInfo.characterInfo == null)
            return null;
        return ui.sessInfo.characterInfo.discoveries();
    }

    public void addListener(Consumer<DiscoveryCorrelator.Verdict> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<DiscoveryCorrelator.Verdict> l) {
        listeners.remove(l);
    }

    /** The character's LP, from NCharWnd. The first value is the baseline; a drop (LP spent) resets it. */
    public void lp(int value) {
        int prev = lp;
        lp = value;
        if (prev >= 0 && value > prev)
            correlator.lp(value - prev, System.currentTimeMillis());
    }

    public void arrived(NGItem item) {
        if (!owned(item))
            return;
        long now = System.currentTimeMillis();
        boolean login = lp < 0 || now - startedAt < LOGIN_MS;
        Gob clicked = sourceGob();
        boolean produced = !login && (now - lastProgAt <= PROGRESS_MS || clicked != null);
        correlator.arrival(new Ref(item), now, produced, clicked == null ? null : clicked.ngob.name);
    }

    public void departed(NGItem item) {
        String res = resName(item);
        if (res != null)
            correlator.departure(res, System.currentTimeMillis(), owned(item));
    }

    /** An item's name resolved; one never held is remembered as seen. */
    public void named(NGItem item) {
        if (item.name() != null && !owned(item) && !(item.parent instanceof nurgling.NStudyInventory))
            ledger.record(item.name(), DiscoveryLedger.State.SEEN, resName(item), 0, false, System.currentTimeMillis(), null);
    }

    /** A curio left the study, finished or taken out; its LP is not a discovery. */
    public void curioLeft(GItem item) {
        int exp = -1;
        try {
            for (ItemInfo inf : item.info()) {
                if (inf instanceof Curiosity) {
                    exp = ((Curiosity) inf).exp;
                    break;
                }
            }
        } catch (Loading l) {
            // Unknown LP: the correlator then credits nothing in its window.
        }
        correlator.curio(exp, System.currentTimeMillis());
    }

    public void tick() {
        long now = System.currentTimeMillis();
        GameUI gui = info.ui == null ? null : info.ui.gui;
        if (gui != null && gui.prog != null)
            lastProgAt = now;
        if (!correlator.idle()) {
            for (DiscoveryCorrelator.Verdict v : correlator.poll(now, ledger::isDone))
                apply(v);
        }
        if (now - lastSave > SAVE_MS) {
            lastSave = now;
            ledger.saveAsync();
        }
    }

    private void apply(DiscoveryCorrelator.Verdict v) {
        switch (v.kind) {
            case DISCOVERED:
                ledger.record(v.name, DiscoveryLedger.State.DISCOVERED, v.res, v.lp, v.shared, v.at, v.src);
                System.out.println("Discovery: " + v.name + " +" + v.lp + " LP" + (v.shared ? " (shared)" : "") + (v.src != null ? " from " + v.src : ""));
                break;
            case KNOWN:
                if (ledger.record(v.name, DiscoveryLedger.State.KNOWN, v.res, 0, false, v.at, v.src))
                    System.out.println("Discovery: " + v.name + " already known (made it, no LP)");
                break;
            case HELD:
                ledger.record(v.name, DiscoveryLedger.State.HELD, v.res, 0, false, v.at, v.src);
                break;
            case UNATTRIBUTED:
                ledger.unattributed(v.lp, v.at);
                System.out.println("Discovery: +" + v.lp + " LP with no new item");
                break;
        }
        for (Consumer<DiscoveryCorrelator.Verdict> l : listeners)
            l.accept(v);
    }

    /** The gob the player last right-clicked, if it yields items and the click is recent. */
    private Gob sourceGob() {
        NUI ui = info.ui instanceof NUI ? (NUI) info.ui : null;
        if (ui == null || ui.core == null)
            return null;
        Gob gob = ui.core.lastGobRightClick(GOB_CLICK_MS);
        if (gob == null || gob.ngob == null || gob.ngob.name == null)
            return null;
        String name = gob.ngob.name;
        // Ground items, piles, containers and players hand over items that already exist.
        if (name.startsWith("gfx/invobjs") || name.contains("stockpile") || name.startsWith("gfx/borka")
                || NContext.contcaps.containsKey(name))
            return null;
        return gob;
    }

    /** Whether the item is in the player's own inventory, hand, equipment or a stack held in one of those. */
    private boolean owned(GItem item) {
        GameUI gui = item.ui == null ? null : item.ui.gui;
        if (gui == null)
            return false;
        Widget child = item, w = item.parent;
        while (w != null) {
            if (w == gui.maininv || w instanceof Equipory)
                return true;
            // An item placed directly on the GameUI is the one in the hand; windows are also
            // GameUI children, so only an item's own parent counts.
            if (w == gui)
                return child instanceof GItem;
            if (w instanceof GItem.ContentsWindow) {
                child = ((GItem.ContentsWindow) w).cont;
                w = child.parent;
                continue;
            }
            child = w;
            w = w.parent;
        }
        return false;
    }

    private static String resName(GItem item) {
        try {
            Resource r = item.resource();
            return r == null ? null : r.name;
        } catch (Loading l) {
            return null;
        }
    }

    private static final class Ref implements DiscoveryCorrelator.Item {
        final NGItem item;

        Ref(NGItem item) {
            this.item = item;
        }

        public String res() {
            return resName(item);
        }

        public String name() {
            return item.name();
        }
    }
}
