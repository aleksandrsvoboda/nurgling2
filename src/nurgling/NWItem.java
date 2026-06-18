package nurgling;

import haven.*;
import haven.res.lib.itemtex.*;
import haven.res.ui.stackinv.ItemStack;
import nurgling.iteminfo.NSearchable;
import nurgling.styles.TooltipStyle;
import nurgling.tools.NSearchItem;
import nurgling.widgets.DropContainer;
import org.json.*;

import java.util.ArrayList;
import java.util.HashMap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

public class NWItem extends WItem
{
    public NWItem(GItem item)
    {
        super(item);
    }

    /**
     * Calculate actual padding needed.
     * GLPanel.drawtooltip adds GLPANEL_MARGIN background margin around the image,
     * so we subtract that to achieve the target total padding.
     * Both values are scaled to maintain proper proportions at any UI scale.
     */
    private static int getTooltipPadding() {
        return Math.max(0, UI.scale(TooltipStyle.OUTER_PADDING) - UI.scale(TooltipStyle.GLPANEL_MARGIN));
    }

    private static int getTooltipPaddingBottom() {
        return Math.max(0, UI.scale(TooltipStyle.OUTER_PADDING_BOTTOM) - UI.scale(TooltipStyle.GLPANEL_MARGIN));
    }

    /**
     * Custom tooltip class that wraps the image with padding
     */
    public class PaddedTip implements Indir<Tex>, ItemInfo.InfoTip {
        private final List<ItemInfo> info;
        private final TexI tex;

        public PaddedTip(List<ItemInfo> info, BufferedImage img) {
            this.info = info;
            if (img == null)
                throw new Loading();
            // Add padding around the tooltip
            BufferedImage padded = addPadding(img);
            tex = new TexI(padded);
        }

        public GItem item() { return item; }
        public List<ItemInfo> info() { return info; }
        public Tex get() { return tex; }

        private BufferedImage addPadding(BufferedImage img) {
            int padding = getTooltipPadding();
            int paddingBottom = getTooltipPaddingBottom();
            int newWidth = img.getWidth() + padding * 2;
            int newHeight = img.getHeight() + padding + paddingBottom;
            BufferedImage result = TexI.mkbuf(new Coord(newWidth, newHeight));
            Graphics g = result.getGraphics();
            g.drawImage(img, padding, padding, null);
            g.dispose();
            return result;
        }
    }

    private PaddedTip nlongtip = null;
    private List<ItemInfo> nttinfo = null;
    private boolean nlastModshift = false;
    private double nlastLongtipBuild = 0;
    private static final double NLONGTIP_REBUILD_INTERVAL = 0.5;

    @Override
    public Object tooltip(Coord c, Widget prev) {
        List<ItemInfo> info = item.info();
        if (info.size() < 1)
            return null;
        // Reset tooltip cache if Shift state changed
        if (ui.modshift != nlastModshift) {
            nlongtip = null;
            nlastModshift = ui.modshift;
        }
        if (info != nttinfo) {
            nlongtip = null;
            nttinfo = info;
        }
        double now = Utils.rtime();
        boolean wantRebuild = (nlongtip == null) || ((NGItem) item).needlongtip();
        boolean throttled = (nlongtip != null) && (now - nlastLongtipBuild < NLONGTIP_REBUILD_INTERVAL);
        if (wantRebuild && !throttled) {
            BufferedImage img = NTooltip.build(info);
            if (img != null) {
                nlongtip = new PaddedTip(info, img);
                ((NGItem) item).consumedLongtip();
                nlastLongtipBuild = now;
            }
        }
        return nlongtip;
    }

    @Override
    public void tick(double dt)
    {
        super.tick(dt);
        // Update overlays only when their tick() method indicates a change is needed
        GItem.InfoOverlay<Tex>[] ols = (GItem.InfoOverlay<Tex>[]) getItemols().get();
        if(ols != null) {
            for(GItem.InfoOverlay<Tex> ol : ols) {
                // tick() returns false when overlay needs to be updated
                // Only recreate texture when actually needed
                if (!ol.inf.tick(dt)) {
                    ol.data = ol.inf.overlay();
                }
            }
        }
        
        search();
        
        if((Boolean)NConfig.get(NConfig.Key.autoDropper)) {
            if(parent instanceof NInventory && NUtils.getGameUI() != null && NUtils.getGameUI().maininv == parent) {
                autoDrop();
            }
        }
    }

    // Server flood protection: dropping many items in the same instant (after
    // picking up a big batch, or trimming a large stack) sends a burst of
    // "drop" messages that trips the server's spam protection and disconnects
    // the client. Auto-drops are throttled to at most one item per interval,
    // shared statically across every item so the COMBINED drop rate -- loose
    // items and stacked items together -- stays under the threshold.
    private static final long AUTODROP_INTERVAL_MS = 150;
    private static long lastAutodropMs = 0;

    // Returns true (and consumes the slot) only when enough time has passed
    // since the last auto-drop. Call this immediately before actually dropping,
    // and only once it is certain the item will be dropped, so slots are never
    // wasted.
    private static boolean autodropAllowed()
    {
        long now = System.currentTimeMillis();
        if (now - lastAutodropMs >= AUTODROP_INTERVAL_MS) {
            lastAutodropMs = now;
            return true;
        }
        return false;
    }

    private void autoDrop()
    {
        NGItem ngitem = (NGItem) item;
        String name = ngitem.name();
        if (name == null) return;
        HashMap<String, Integer> props = DropContainer.getDropProps();
        if (!props.containsKey(name)) return;
        int threshold = props.get(name);

        // Stack container: trim only the sub-threshold items out of the stack
        // instead of dropping the whole thing. The container itself carries no
        // quality (quality lives on each stacked child), so dropping it would
        // discard everything regardless of the threshold. Each child is an
        // independent GItem, so dropping it is the same hand-free "drop"
        // message used for loose items.
        //
        // NOTE: the container's tooltip (which is what makes its quality
        // resolve to null) and its contents widget arrive in separate server
        // messages, in no guaranteed order. Until contents has attached we must
        // NOT fall through to the loose branch below -- that would drop the
        // whole stack. The loose branch only drops items with a concrete
        // quality, and a stack container's quality is always null, so a stack
        // is safe in either branch; it simply gets trimmed on a later tick once
        // contents is present.
        if (ngitem.contents instanceof ItemStack) {
            ItemStack stack = (ItemStack) ngitem.contents;
            // Snapshot the order so the live list isn't touched while drops fire.
            for (GItem gi : new ArrayList<>(stack.order)) {
                if (!(gi instanceof NGItem)) continue;
                NGItem child = (NGItem) gi;
                Float q = child.quality;
                if (q == null) continue;             // quality not loaded yet -> re-check next tick
                if (q >= threshold) continue;        // keep items at/above the threshold
                if (child.autodropRequested) continue; // already asked to drop -> no duplicate messages
                if (!autodropAllowed()) return;      // throttled -> drop it on a later tick
                child.autodropRequested = true;
                // Drop exactly this one item to the ground -- the same hand-free
                // message the client sends when you ctrl-click a single item in
                // a stack (WItem.mousedown: wdgmsg("drop", coord, count)).
                gi.wdgmsg("drop", Coord.z, 1);
                return;                              // one drop per throttle slot
            }
            return;
        }

        // Loose item: drop only when it has a concrete quality below the
        // threshold. A null quality is NEVER dropped here -- it may be an item
        // whose tooltip has not loaded yet, or a stack container whose contents
        // widget has not attached yet (the container carries no quality of its
        // own). Dropping on a null quality is exactly what caused whole stacks
        // to be discarded, so it is deliberately not done.
        if (ngitem.autodropRequested) return;        // already asked to drop -> no repeated messages
        Float q = ngitem.quality;
        if (q == null || q >= threshold) return;     // keep: unknown quality, or at/above threshold
        if (!autodropAllowed()) return;              // throttled -> drop it on a later tick
        ngitem.autodropRequested = true;
        NUtils.drop(this);
    }

    private void search()
    {
        if(NUtils.getGameUI()!=null) {
            if (NUtils.getGameUI().itemsForSearch != null && !NUtils.getGameUI().itemsForSearch.isEmpty()) {
                String name = ((NGItem) item).name();
                if (name != null) {
                    if (NUtils.getGameUI().itemsForSearch.onlyName()) {
                        if (name.toLowerCase().contains(NUtils.getGameUI().itemsForSearch.name)) {
                            if (!((NGItem) item).isSearched) {
                                ((NGItem) item).isSearched = true;
                            }
                            return;
                        }
                    }
                }
                if (item.spr != null) {
                    if (item.info != null) {
                        for (ItemInfo inf : item.info) {
                            if (inf instanceof NSearchable) {
                                if (((NSearchable) inf).search()) {
                                    if (!((NGItem) item).isSearched) {
                                        if (!NUtils.getGameUI().itemsForSearch.q.isEmpty() && !searchQuality()) return;
                                        ((NGItem) item).isSearched = true;
                                    }
                                    return;
                                }
                            }
                        }
                        if (!NUtils.getGameUI().itemsForSearch.q.isEmpty() && searchQuality()) {
                            if (!((NGItem) item).isSearched) {
                                ((NGItem) item).isSearched = true;
                            }
                        }
                    }
                }
            }

            if (((NGItem) item).isSearched) {
                if (NUtils.getGameUI().itemsForSearch != null && !NUtils.getGameUI().itemsForSearch.q.isEmpty() && searchQuality())
                    return;
                ((NGItem) item).isSearched = false;
            }
        }
    }

    private boolean searchQuality() {
        for(NSearchItem.Quality q : NUtils.getGameUI().itemsForSearch.q)
        {
            if(((NGItem) item).quality!=null)
            {
                switch (q.type)
                {
                    case MORE:
                        if (((NGItem) item).quality <= q.val) return false;
                        break;
                    case LOW:
                        if (((NGItem) item).quality >= q.val) return false;
                        break;
                    case EQ:
                        if (((NGItem) item).quality != q.val) return false;
                }
            }
            else
            {
                return false;
            }
        }

        if (!NUtils.getGameUI().itemsForSearch.name.isEmpty()) {
            String name = ((NGItem) item).name();
            if(name!=null) {
                return name.toLowerCase().contains(NUtils.getGameUI().itemsForSearch.name);
            }
            else
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev)
    {
        // Alt+Shift+Click: transfer all same items sorted by quality
        // Right-click (button 3): ascending order (lowest quality first)
        // Left-click (button 1): descending order (highest quality first)
        if(ui.modshift)
        {
            if (ui.modmeta)
            {
                if (parent instanceof NInventory)
                {
                    wdgmsg("transfer-same", item, ev.b == 3);
                    return true;
                }
            }
        }
        // Alt+Ctrl+Click: drop all same items sorted by quality
        // Right-click (button 3): ascending order (lowest quality first)
        // Left-click (button 1): descending order (highest quality first)
        else if(ui.modctrl)
        {
            if (ui.modmeta)
            {
                if (parent instanceof NInventory)
                {
                    wdgmsg("drop-same", item, ev.b == 3);
                    return true;
                }
            }
        }
        return super.mousedown(ev);
    }
}
