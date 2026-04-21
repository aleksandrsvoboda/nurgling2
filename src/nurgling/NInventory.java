package nurgling;

import haven.*;
import haven.Button;
import haven.Label;
import haven.Window;
import haven.res.ui.stackinv.ItemStack;
import haven.res.ui.tt.slot.Slotted;
import haven.res.ui.tt.stackn.Stack;
import monitoring.ItemWatcher;
import nurgling.actions.SortInventory;
import nurgling.iteminfo.NCuriosity;
import nurgling.iteminfo.NFoodInfo;
import nurgling.tasks.*;
import nurgling.tools.*;

import java.util.Map;
import java.util.HashMap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

public class NInventory extends Inventory
{
    public boolean mainInvInstalled = false;
    public Scrollport itemListContainer;
    public Widget itemListContent;
    public ICheckBox bundle;
    public MenuGrid.PagButton pagBundle = null;
    // 0=closed, 1=simplified, 2=expanded
    int panelState = 0;
    static final int PANEL_CLOSED = 0, PANEL_SIMPLIFIED = 1, PANEL_EXPANDED = 2;
    static final int PANEL_W_SIMPLIFIED = UI.scale(85);
    static final int PANEL_W_EXPANDED = UI.scale(250);
    Widget rightPanel;
    java.util.List<Widget> expandedOnlyWidgets = new java.util.ArrayList<>();
    java.util.List<Widget> simplifiedOnlyWidgets = new java.util.ArrayList<>();
    // Compact list sort state
    boolean compactNameAscending = true;
    boolean compactQuantityAscending = false;
    String compactLastSortType = "quantity";
    // Title bar button references (Widget type - these are NHeaderButton/NHeaderToggle wrappers)
    private Widget searchBtn;
    public nurgling.widgets.NSearchWidget searchwdg;
    boolean searchVisible = false;
    private Widget eyeBtn;
    private Widget dropperBtn;
    private Widget sortBtnRef;
    private Widget stackSortBtnRef;
    short[][] oldinv = null;
    public Gob parentGob = null;
    long lastUpdate = 0;
    
    // Track pending item removals from cache (for items consumed while container is open)
    public static class PendingCacheRemoval {
        public final ItemWatcher.ItemInfo itemInfo;
        public final long removeAtTick;
        
        public PendingCacheRemoval(ItemWatcher.ItemInfo itemInfo, long removeAtTick) {
            this.itemInfo = itemInfo;
            this.removeAtTick = removeAtTick;
        }
    }
    public final java.util.List<PendingCacheRemoval> pendingCacheRemovals = new java.util.ArrayList<>();
    
    // Flag to indicate inventory is being closed (to distinguish item consumed vs container closed)
    public boolean isClosing = false;
    
    // Flag to indicate if this inventory should be indexed in database
    // Only certain container types should be tracked (e.g. Cupboard, Chest, etc.)
    private Boolean isIndexable = null;
    
    // Container types that should be indexed
    private static final java.util.Set<String> INDEXABLE_CONTAINERS = java.util.Set.of(
        "Cupboard",
        "Chest",
        "Crate",
        "Barrel",
        "Basket",
        "Coffer",
        "Large Chest",
        "Metal Cabinet",
        "Stonecasket"
    );
    
    /**
     * Check if this inventory should be indexed in database
     */
    public boolean isIndexable() {
        if (isIndexable != null) {
            return isIndexable;
        }
        
        // Check if this is an indexable container
        isIndexable = false;
        
        // Skip main inventory, equipment, belt, study
        NGameUI gui = NUtils.getGameUI();
        if (gui != null && this == gui.maininv) {
            return false;
        }
        
        // Check parent window title
        Window wnd = getparent(Window.class);
        if (wnd != null && wnd.cap != null) {
            String title = wnd.cap;
            for (String containerType : INDEXABLE_CONTAINERS) {
                if (title.contains(containerType)) {
                    isIndexable = true;
                    return true;
                }
            }
        }
        
        return false;
    }
    private long pendingSearchRefreshTick = 0;
    
    // Pre-cached slot number textures for performance
    private static final int MAX_SLOT_NUMBERS = 200;
    private static final TexI[] cachedSlotNumbers = new TexI[MAX_SLOT_NUMBERS + 1];
    
    static {
        // Pre-render all slot numbers once at startup
        for (int i = 1; i <= MAX_SLOT_NUMBERS; i++) {
            cachedSlotNumbers[i] = new TexI(NStyle.slotnums.render(String.valueOf(i)).img);
        }
    }

    public NInventory(Coord sz)
    {
        super(sz);
    }

    @Override
    protected void added() {
        super.added();
        // Add Plan button for Study Desk after the widget is added to its parent
        nurgling.widgets.StudyDeskInventoryExtension.addPlanButtonIfStudyDesk(this);
        // Add Sort button for container inventories (not main inventory)
        addSortButtonIfContainer();
    }
    
    /**
     * Add sort button to window title bar (left of close button)
     * Used for both main inventory and container inventories
     */
    private void addSortButtonToTitleBar() {
        // Get parent window
        Window wnd = getparent(Window.class);
        if (wnd == null || wnd.deco == null) {
            return;
        }
        
        // Skip excluded windows
        String caption = wnd.cap;
        if (caption != null) {
            for (String excluded : SortInventory.EXCLUDE_WINDOWS) {
                if (caption.contains(excluded)) {
                    return;
                }
            }
        }
        
        // Check if deco is NWindowDeco with cbtn
        if (!(wnd.deco instanceof NWindowDeco)) {
            return;
        }

        NWindowDeco deco = (NWindowDeco) wnd.deco;
        
        // Add sort button to deco, left of close button
        // The button updates its position in tick() to stay left of cbtn
        NInventory thisInv = this;
        IButton sortBtn = new IButton(NStyle.sorti[0].back, NStyle.sorti[1].back, NStyle.sorti[2].back) {
            @Override
            public void click() {
                SortInventory.sort(thisInv);
            }

            @Override
            public void tick(double dt) {
                super.tick(dt);
                if (deco.cbtn != null) {
                    Coord cbtnPos = deco.cbtn.c;
                    c = new Coord(cbtnPos.x - sz.x - UI.scale(2), cbtnPos.y);
                }
            }
        };
        sortBtn.settip("Sort Inventory");
        deco.add(sortBtn);

        // Initial position left of close button
        Coord cbtnPos = deco.cbtn.c;
        sortBtn.c = new Coord(cbtnPos.x - sortBtn.sz.x - UI.scale(2), cbtnPos.y);

        // Stack sort button — left of the sort button, with box-clickable area
        // Vertically center relative to the sort button
        NHeaderButton stackSortBtn = new NHeaderButton(
                NStyle.stacksorti[0], NStyle.stacksorti[1], NStyle.stacksorti[2],
                () -> SortInventory.sortDeep(thisInv)) {
            @Override
            public void tick(double dt) {
                super.tick(dt);
                if (sortBtn.c != null) {
                    int centerY = sortBtn.c.y + sortBtn.sz.y / 2 - sz.y / 2;
                    c = new Coord(sortBtn.c.x - sz.x - UI.scale(2), centerY);
                }
            }
        }.tip("Sort Within Stacks");
        deco.add(stackSortBtn);
        int centerY = sortBtn.c.y + sortBtn.sz.y / 2 - stackSortBtn.sz.y / 2;
        stackSortBtn.c = new Coord(sortBtn.c.x - stackSortBtn.sz.x - UI.scale(2), centerY);
    }
    
    /**
     * Add sort button to container inventory windows (not main inventory)
     * Button is placed in window title bar, left of the close button
     */
    private void addSortButtonIfContainer() {
        // Skip if this is the main inventory (it has its own sort button via installMainInv)
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || this == gui.maininv) {
            return;
        }
        
        addSortButtonToTitleBar();
    }

    public enum QualityType {
        High, Low
    }
    
    // Grouping modes for inventory panel (like hafen-client)
    public enum Grouping {
        NONE("Type"),
        Q("Quality"),
        Q1("Quality 1"),
        Q5("Quality 5"),
        Q10("Quality 10");
        
        public final String displayName;
        
        Grouping(String displayName) {
            this.displayName = displayName;
        }
    }
    
    // Display types for item list
    public enum DisplayType {
        Name, Quality, Info
    }
    
    // Current display type and grouping
    private static DisplayType currentDisplayType = DisplayType.Name;
    private Grouping currentGrouping = Grouping.NONE;
    public Dropbox<Grouping> groupingDropbox;
    public Dropbox<DisplayType> displayTypeDropbox;
    private Label spaceLabel; // Shows filled/total slots
    private TextEntry qualityFilterEntry; // Min quality filter
    private Double minQualityFilter = null; // Parsed min quality value

    @Override
    public void draw(GOut g) {
        super.draw(g);
        if((Boolean)NConfig.get(NConfig.Key.showInventoryNums) && oldinv != null) {
            drawSlotNumbers(g);
        }
    }
    
    // Optimized direct rendering without creating intermediate BufferedImage
    private void drawSlotNumbers(GOut g) {
        int counter = 1;
        Coord coord = new Coord(0, 0);
        for (coord.y = 0; coord.y < isz.y; coord.y++) {
            for (coord.x = 0; coord.x < isz.x; coord.x++) {
                // Check bounds to prevent ArrayIndexOutOfBoundsException
                if (coord.y >= oldinv.length || coord.x >= oldinv[coord.y].length) {
                    break;
                }
                if (oldinv[coord.y][coord.x] == 0 && counter <= MAX_SLOT_NUMBERS) {
                    TexI numTex = cachedSlotNumbers[counter];
                    Coord pos = coord.mul(sqsz).add(sqsz.div(2));
                    Coord sz = numTex.sz();
                    pos = pos.add((int)((double)sz.x * -0.5), (int)((double)sz.y * -0.5));
                    g.image(numTex, pos);
                }
                if (oldinv[coord.y][coord.x] != 2)
                    counter++;
            }
        }
    }


    // Simplified version - just updates inventory state, rendering happens in draw()
    void updateInventoryState(short[][] inventory) {
        oldinv = inventory.clone();
    }

    @Override
    public void addchild(Widget child, Object... args) {
        super.addchild(child, args);
    }

    public int getNumberFreeCoord(Coord coord) throws InterruptedException
    {
        GetNumberFreeCoord gnfc = new GetNumberFreeCoord(this, coord);
        NUtils.getUI().core.addTask(gnfc);
        return gnfc.result();
    }


    public int getNumberFreeCoord(GItem item) throws InterruptedException
    {
        GetNumberFreeCoord gnfc = new GetNumberFreeCoord(this, item);
        NUtils.getUI().core.addTask(gnfc);
        return gnfc.result();
    }

    public int getNumberFreeCoord(WItem item) throws InterruptedException
    {
        return getNumberFreeCoord(item.item);
    }

    public Coord getFreeCoord(WItem item) throws InterruptedException
    {
        GetFreePlace gfp = new GetFreePlace(this, item.item);
        NUtils.getUI().core.addTask(gfp);
        return gfp.result();
    }

    public int getFreeSpace() throws InterruptedException
    {
        GetFreeSpace gfs = new GetFreeSpace(this);
        NUtils.getUI().core.addTask(gfs);
        return gfs.result();
    }

    public int getTotalSpace() throws InterruptedException
    {
        GetTotalSpace gts = new GetTotalSpace(this);
        NUtils.getUI().core.addTask(gts);
        return gts.result();
    }

    public int getTotalAmountItems(NAlias name) throws InterruptedException
    {
        GetTotalAmountItems gi = new GetTotalAmountItems(this, name);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public WItem getItem(NAlias name) throws InterruptedException
    {
        GetItem gi = new GetItem(this, name);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public WItem getItem(NAlias name, Class<? extends ItemInfo> prop) throws InterruptedException
    {
        GetItem gi = new GetItem(this, name, prop);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public WItem getItem(NAlias name, Float q) throws InterruptedException
    {
        GetItem gi = new GetItem(this, name, q);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public WItem getItem(String name) throws InterruptedException
    {
        return getItem(new NAlias(name));
    }

    public WItem getItem(NAlias name, QualityType type) throws InterruptedException {
        ArrayList<WItem> items = getItems(name, type);
        if (items.isEmpty()) {
            return null;
        }
        return items.get(0);
    }

    public ArrayList<WItem> getItems(NAlias name, QualityType type) throws InterruptedException {
        GetItems gi = new GetItems(this, name, type);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }


    public ArrayList<WItem> getItems() throws InterruptedException
    {
        GetItems gi = new GetItems(this);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public ArrayList<WItem> getItems(NAlias name) throws InterruptedException
    {
        GetItems gi = new GetItems(this, name);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public ArrayList<WItem> getWItems(NAlias name) throws InterruptedException
    {
        GetWItems gi = new GetWItems(this, name);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }



    public ArrayList<WItem> getItems(NAlias name, double th) throws InterruptedException
    {
        GetItems gi = new GetItems(this, name, (float)th);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public ArrayList<WItem> getItems(String name) throws InterruptedException
    {
        return getItems(new NAlias(name));
    }

    public ArrayList<WItem> getItems(String name, double th) throws InterruptedException
    {
        return getItems(new NAlias(name), th);
    }

    public ArrayList<WItem> getItems(GItem target) throws InterruptedException
    {
        GetItems gi = new GetItems(this, target);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public void activateItem(NAlias name) throws InterruptedException {
        WItem it = getItem(name);
        it.item.wdgmsg("iact", Coord.z, 1);
    }

    public void activateItem(WItem item) throws InterruptedException {
        item.item.wdgmsg("iact", Coord.z, 1);
    }

    public void dropOn(Coord dc, String name) throws InterruptedException
    {
        if (NUtils.getGameUI().vhand != null)
        {
            wdgmsg("drop", dc);
            NUtils.getUI().core.addTask(new DropOn(this, dc, name));
        }
    }

    public void dropOn(Coord dc, NAlias name) throws InterruptedException
    {
        if (NUtils.getGameUI().vhand != null)
        {
            wdgmsg("drop", dc);
            NUtils.getUI().core.addTask(new DropOn(this, dc, name));
        }
    }

    public void dropOn(Coord dc) throws InterruptedException
    {
        if (NUtils.getGameUI().vhand != null)
        {
            wdgmsg("drop", dc);
            NUtils.getUI().core.addTask(new DropOn(this, dc, ((NGItem)NUtils.getGameUI().vhand.item).name()));
        }
    }

    private int fullContentWidth() {
        int w = sz.x;
        if (panelState == PANEL_SIMPLIFIED) w = sz.x + UI.scale(8) + PANEL_W_SIMPLIFIED;
        else if (panelState == PANEL_EXPANDED) w = sz.x + UI.scale(8) + PANEL_W_EXPANDED;
        return w;
    }

    @Override
    public void resize(Coord sz) {
        super.resize(new Coord(sz));
        if (rightPanel != null) {
            int gap = UI.scale(8);
            rightPanel.move(new Coord(sz.x + gap, 0));
            rightPanel.resize(new Coord(UI.scale(250), sz.y));
            updateItemListSize();
        }
        if (searchwdg != null) {
            searchwdg.resize(new Coord(fullContentWidth(), 0));
            searchwdg.move(new Coord(0, sz.y + UI.scale(5)));
        }
        resizeSearchToFit();
        parent.pack();
        positionTitleBarButtons();
    }

    public void movePopup(Coord c) {
        // No-op: all panels are now embedded in the window
    }

    @Override
    public void tick(double dt) {
        if(lastUpdate==0)
        {
            lastUpdate = NUtils.getTickId();
        }
        // Note: removed old iis.clear() logic - in new design iis is managed by
        // tryAddToInventoryCache (add) and reqdestroy (sync)
        
        // Process pending cache removals - items consumed while inventory stays open
        // These are only processed if the timer expired (container didn't close)
        if (!pendingCacheRemovals.isEmpty() && (Boolean) NConfig.get(NConfig.Key.ndbenable)) {
            long currentTick = NUtils.getTickId();
            java.util.Iterator<PendingCacheRemoval> it = pendingCacheRemovals.iterator();
            int removedCount = 0;
            while (it.hasNext()) {
                PendingCacheRemoval pr = it.next();
                if (currentTick >= pr.removeAtTick) {
                    // Timer expired, container didn't close - item was consumed
                    // Remove from cache (iis) using direct reference
                    if (iis.remove(pr.itemInfo)) {
                        removedCount++;
                        System.out.println("NInventory.tick: Removed consumed item: " + pr.itemInfo.name);
                    }
                    it.remove();
                }
            }
            // Schedule search refresh after cache changes
            if (removedCount > 0) {
                pendingSearchRefreshTick = NUtils.getTickId() + 5;
            }
        }
        
        // Handle pending search refresh
        if (pendingSearchRefreshTick > 0 && NUtils.getTickId() >= pendingSearchRefreshTick) {
            pendingSearchRefreshTick = 0;
            if (NUtils.getGameUI() != null && NUtils.getGameUI().itemsForSearch != null) {
                NUtils.getGameUI().itemsForSearch.refreshSearch();
            }
        }
        
        if(NUtils.getGameUI() == null)
            return;
        super.tick(dt);
        if((Boolean)NConfig.get(NConfig.Key.showInventoryNums)) {
            short[][] newInv = containerMatrix();
            boolean isDiffrent = false;
            if (newInv != null)
                if (oldinv != null) {
                    if (newInv.length != oldinv.length)
                        isDiffrent = true;
                    else {
                        for (int i = 0; i < newInv.length; i++) {
                            if (newInv[i].length != oldinv[i].length) {
                                isDiffrent = true;
                                break;
                            }
                            for (int j = 0; j < newInv[i].length; j++) {
                                if (newInv[i][j] != oldinv[i][j]) {
                                    isDiffrent = true;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    isDiffrent = true;
                }
            if (isDiffrent)
                updateInventoryState(newInv);
        }
        else
            oldinv = null;
        // Update embedded right panel periodically
        if (panelState != PANEL_CLOSED && rightPanel != null && rightPanel.visible) {
            if (NUtils.getTickId() % 10 == 0) {
                if (panelState == PANEL_SIMPLIFIED) {
                    rebuildCompactList();
                } else {
                    rebuildItemList();
                    updateSpaceLabel();
                }
            }
        }
        // Reposition title bar buttons on tick (window may have resized)
        positionTitleBarButtons();
    }

    private static final TexI[] gildingi = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/gilding/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/gilding/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/gilding/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/gilding/dh"))};

    private static final TexI[] vari = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/var/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/var/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/var/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/var/dh"))};

    private static final TexI[] stacki = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/stack/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/stack/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/stack/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/stack/dh"))};

    private static final TexI[] autoflower = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/autoflower/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/autoflower/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/autoflower/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/autoflower/dh"))};

    private static final TexI[] autosplittor = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/autosplittor/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/autosplittor/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/autosplittor/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/autosplittor/dh"))};

    private static final TexI[] bundlei = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/bundle/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/bundle/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/bundle/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/bundle/dh"))};

    private static final TexI[] numberi = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/numbering/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/numbering/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/numbering/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/numbering/dh"))};

    private static final TexI[] dropperi = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/dropper/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/dropper/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/dropper/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/dropper/dh"))};

    // Shared tooltip foundry for all header buttons: Open Sans Regular 11px
    private static final Text.Foundry tipFnd = new Text.Foundry(nurgling.conf.FontSettings.getOpenSans(), 11, java.awt.Color.WHITE).aa(true);

    // Square clickable header button with icon drawn centered and hover highlight
    static class NHeaderButton extends Widget {
        private final Tex normal, pressed, hover;
        private boolean isHover = false;
        private UI.Grab grab = null;
        private Runnable action;
        private String tip;

        NHeaderButton(Tex normal, Tex pressed, Tex hover, Runnable action) {
            super(new Coord(UI.scale(21), UI.scale(21)));
            this.normal = normal;
            this.pressed = pressed;
            this.hover = hover;
            this.action = action;
        }

        NHeaderButton(String base, Runnable action) {
            this(new TexI(Resource.loadsimg(base + "/u")),
                 new TexI(Resource.loadsimg(base + "/d")),
                 new TexI(Resource.loadsimg(base + "/h")),
                 action);
        }

        @Override
        public void draw(GOut g) {
            Tex img = (grab != null) ? pressed : (isHover ? hover : normal);
            Coord imgSz = img.sz();
            Coord offset = sz.sub(imgSz).div(2);
            if (isHover) {
                g.chcolor(255, 255, 255, 25);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
            g.image(img, offset);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                grab = ui.grabmouse(this);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseup(MouseUpEvent ev) {
            if (grab != null && ev.b == 1) {
                grab.remove();
                grab = null;
                if (ev.c.isect(Coord.z, sz) && action != null) action.run();
                return true;
            }
            return false;
        }

        NHeaderButton tip(String tip) { this.tip = tip; return this; }

        @Override
        public Object tooltip(Coord c, Widget prev) { return tip != null ? tipFnd.render(tip).tex() : null; }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            isHover = ev.c.isect(Coord.z, sz);
        }
    }

    // Square clickable header toggle (checkbox) with icon drawn centered and hover highlight
    static class NHeaderToggle extends Widget {
        private final Tex unchecked, checked, hoverUnchecked, hoverChecked;
        public boolean a = false; // checked state
        private boolean isHover = false;
        private java.util.function.Consumer<Boolean> onChange;
        private String tip;

        NHeaderToggle(Tex unchecked, Tex checked, Tex hoverUnchecked, Tex hoverChecked, java.util.function.Consumer<Boolean> onChange) {
            super(new Coord(UI.scale(21), UI.scale(21)));
            this.unchecked = unchecked;
            this.checked = checked;
            this.hoverUnchecked = hoverUnchecked;
            this.hoverChecked = hoverChecked;
            this.onChange = onChange;
        }

        NHeaderToggle(String base, java.util.function.Consumer<Boolean> onChange) {
            this(new TexI(Resource.loadsimg(base + "/u")),
                 new TexI(Resource.loadsimg(base + "/d")),
                 new TexI(Resource.loadsimg(base + "/h")),
                 new TexI(Resource.loadsimg(base + "/dh")),
                 onChange);
        }

        @Override
        public void draw(GOut g) {
            Tex img = a ? (isHover ? hoverChecked : checked) : (isHover ? hoverUnchecked : unchecked);
            Coord imgSz = img.sz();
            Coord offset = sz.sub(imgSz).div(2);
            if (isHover) {
                g.chcolor(255, 255, 255, 25);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
            g.image(img, offset);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                a = !a;
                if (onChange != null) onChange.accept(a);
                return true;
            }
            return false;
        }

        NHeaderToggle tip(String tip) { this.tip = tip; return this; }

        @Override
        public Object tooltip(Coord c, Widget prev) { return tip != null ? tipFnd.render(tip).tex() : null; }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            isHover = ev.c.isect(Coord.z, sz);
        }
    }

    // Square clickable header button that cycles through N states on click
    static class NHeaderCycler extends Widget {
        private final Tex icon, hoverIcon;
        public int state = 0;
        private final int numStates;
        private boolean isHover = false;
        private java.util.function.IntConsumer onChange;
        // Background alpha per state: 0=no bg, >0 = highlight
        private static final int[] STATE_ALPHA = {0, 20, 40};

        NHeaderCycler(String base, int numStates, java.util.function.IntConsumer onChange) {
            super(new Coord(UI.scale(21), UI.scale(21)));
            this.icon = new TexI(Resource.loadsimg(base + "/u"));
            this.hoverIcon = new TexI(Resource.loadsimg(base + "/h"));
            this.numStates = numStates;
            this.onChange = onChange;
        }

        @Override
        public void draw(GOut g) {
            int bgAlpha = (state < STATE_ALPHA.length) ? STATE_ALPHA[state] : 0;
            if (isHover || bgAlpha > 0) {
                int alpha = isHover ? Math.max(bgAlpha, 25) + 10 : bgAlpha;
                g.chcolor(255, 255, 255, alpha);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
            Tex img = isHover ? hoverIcon : icon;
            Coord offset = sz.sub(img.sz()).div(2);
            g.image(img, offset);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                state = (state + 1) % numStates;
                if (onChange != null) onChange.accept(state);
                return true;
            }
            return false;
        }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            isHover = ev.c.isect(Coord.z, sz);
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            switch (state) {
                case 0: return tipFnd.render(nurgling.i18n.L10n.get("inventory.tip.show_simplified")).tex();
                case 1: return tipFnd.render(nurgling.i18n.L10n.get("inventory.tip.show_full")).tex();
                case 2: return tipFnd.render(nurgling.i18n.L10n.get("inventory.tip.hide_panel")).tex();
                default: return null;
            }
        }
    }

    public void installMainInv() {
        Window wnd = getparent(Window.class);
        NWindowDeco deco = (wnd != null && wnd.deco instanceof NWindowDeco) ? (NWindowDeco) wnd.deco : null;

        // --- Title bar buttons ---
        if (deco != null) {
            // Panel state cycler: closed → simplified → expanded → closed
            eyeBtn = new NHeaderCycler("nurgling/hud/buttons/inv/eye", 3, (state) -> {
                setPanelState(state);
            });
            deco.add(eyeBtn);

            // Search toggle in title bar - expands search panel below inventory
            searchBtn = new NHeaderToggle("nurgling/hud/buttons/inv/search", (val) -> {
                toggleSearch(val);
            }).tip(nurgling.i18n.L10n.get("inventory.tip.search"));
            deco.add(searchBtn);

            // Stack sort button in title bar (deep sort within stacks)
            stackSortBtnRef = new NHeaderButton("nurgling/hud/buttons/inv/stacksort", () -> {
                SortInventory.sortDeep(NInventory.this);
            }).tip("Sort Within Stacks");
            deco.add(stackSortBtnRef);

            // Sort button in title bar
            sortBtnRef = new NHeaderButton("nurgling/hud/buttons/inv/sort", () -> {
                SortInventory.sort(NInventory.this);
            }).tip(nurgling.i18n.L10n.get("inventory.tip.sort"));
            deco.add(sortBtnRef);

            // Dropper/trash toggle in title bar
            dropperBtn = new NHeaderToggle("nurgling/hud/buttons/inv/trash", (val) -> {
                NConfig.set(NConfig.Key.autoDropper, val);
            }).tip(nurgling.i18n.L10n.get("inventory.tip.autodrop"));
            ((NHeaderToggle) dropperBtn).a = (Boolean) NConfig.get(NConfig.Key.autoDropper);
            deco.add(dropperBtn);
        }

        // --- Right panel (embedded in window, to the right of inventory grid) ---
        int panelW = UI.scale(250);
        int gap = UI.scale(8);

        rightPanel = new Widget(new Coord(panelW, sz.y)) {
            @Override
            public void draw(GOut g) {
                // Draw vertical separator on left edge
                int bw = Math.max(1, UI.scale(1));
                g.chcolor(NStyle.separator);
                g.frect(Coord.z, new Coord(bw, sz.y));
                g.chcolor();
                super.draw(g);
            }
        };
        rightPanel.visible = false;
        parent.add(rightPanel, new Coord(sz.x + gap, 0));

        // Setup right panel contents (toggle buttons, dropdowns, item list)
        setupRightPanel();

        // --- Search panel (below inventory, initially hidden) ---
        searchwdg = new nurgling.widgets.NSearchWidget(new Coord(sz));
        searchwdg.resize(sz);
        searchwdg.visible = false;
        parent.add(searchwdg, new Coord(0, sz.y + UI.scale(5)));

        // Load panel state from config (0=closed, 1=simplified, 2=expanded)
        Object stateConfig = NConfig.get(NConfig.Key.inventoryRightPanelShow);
        if (stateConfig instanceof Number) {
            panelState = ((Number) stateConfig).intValue();
        } else if (stateConfig instanceof Boolean) {
            // Migrate old boolean config: true→expanded, false→closed
            panelState = ((Boolean) stateConfig) ? PANEL_EXPANDED : PANEL_CLOSED;
        }
        if (eyeBtn instanceof NHeaderCycler) ((NHeaderCycler) eyeBtn).state = panelState;
        applyPanelState();

        resizeSearchToFit();
        parent.pack();
        positionTitleBarButtons();
        mainInvInstalled = true;
    }

    private void toggleSearch(boolean show) {
        searchVisible = show;
        if (searchwdg != null) {
            searchwdg.visible = show;
            if (!show) {
                // Close history list and clear search
                if (searchwdg.list != null && searchwdg.list.a) {
                    searchwdg.list.set(false);
                }
                if (NUtils.getGameUI() != null && NUtils.getGameUI().itemsForSearch != null) {
                    NUtils.getGameUI().itemsForSearch.install("");
                }
            }
            resizeSearchToFit();
            parent.pack();
            positionTitleBarButtons();
        }
    }

    /**
     * Resize search widget to match the full content width before pack(),
     * so pack() calculates the correct window size.
     */
    private void resizeSearchToFit() {
        if (searchwdg != null && searchwdg.visible) {
            int fullW = fullContentWidth();
            if (searchwdg.sz.x != fullW) {
                searchwdg.resize(new Coord(fullW, 0));
            }
        }
    }

    private void setPanelState(int state) {
        panelState = state;
        NConfig.set(NConfig.Key.inventoryRightPanelShow, state);
        applyPanelState();
        resizeSearchToFit();
        parent.pack();
        positionTitleBarButtons();
    }

    private void applyPanelState() {
        if (rightPanel == null) return;
        int gap = UI.scale(8);

        if (panelState == PANEL_CLOSED) {
            rightPanel.visible = false;
        } else if (panelState == PANEL_SIMPLIFIED) {
            rightPanel.visible = true;
            rightPanel.resize(new Coord(PANEL_W_SIMPLIFIED, sz.y));
            rightPanel.move(new Coord(sz.x + gap, 0));
            for (Widget w : expandedOnlyWidgets) w.visible = false;
            for (Widget w : simplifiedOnlyWidgets) w.visible = true;
            updateItemListSize();
            rebuildCompactList();
        } else { // PANEL_EXPANDED
            rightPanel.visible = true;
            rightPanel.resize(new Coord(PANEL_W_EXPANDED, sz.y));
            rightPanel.move(new Coord(sz.x + gap, 0));
            for (Widget w : expandedOnlyWidgets) w.visible = true;
            for (Widget w : simplifiedOnlyWidgets) w.visible = false;
            updateItemListSize();
            rebuildItemList();
        }
    }

    private void positionTitleBarButtons() {
        Window wnd = getparent(Window.class);
        if (wnd == null || !(wnd.deco instanceof NWindowDeco)) return;
        NWindowDeco deco = (NWindowDeco) wnd.deco;
        int btnGap = UI.scale(2);
        int safetyGap = UI.scale(4);

        // Display order, left-to-right
        Widget[] displayOrder = { eyeBtn, searchBtn, stackSortBtnRef, sortBtnRef, dropperBtn };
        // Drop priority when crowded: lowest priority (most-droppable) first
        Widget[] dropOrder = { dropperBtn, sortBtnRef, stackSortBtnRef, searchBtn, eyeBtn };

        // Reset visibility before recomputing layout
        for (Widget b : displayOrder) if (b != null) b.visible = true;

        // Right edge available for left-anchored buttons (just before the X close button)
        int maxRightX = deco.cbtn.c.x - safetyGap;

        while (true) {
            int leftX = UI.scale(70); // After "Inventory" text
            boolean fits = true;
            for (Widget b : displayOrder) {
                if (b == null || !b.visible) continue;
                if (leftX + b.sz.x > maxRightX) { fits = false; break; }
                b.c = new Coord(leftX, 0);
                leftX += b.sz.x + btnGap;
            }
            if (fits) break;

            // Hide the next-lowest-priority visible button and retry
            boolean hid = false;
            for (Widget b : dropOrder) {
                if (b != null && b.visible) { b.visible = false; hid = true; break; }
            }
            if (!hid) break; // nothing left to drop
        }
    }

    private int itemListExpandedY; // y position of item list in expanded mode

    private static final TexI[] sortarrowi = new TexI[]{
            new TexI(Resource.loadsimg("nurgling/hud/buttons/inv/sortarrow/u")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/inv/sortarrow/d")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/inv/sortarrow/h")),
            new TexI(Resource.loadsimg("nurgling/hud/buttons/inv/sortarrow/dh"))};

    private int itemListSimplifiedY; // y position of item list in simplified mode

    private void setupRightPanel() {
        expandedOnlyWidgets.clear();
        simplifiedOnlyWidgets.clear();
        int margin = UI.scale(8);
        int y = margin;
        int elementGap = UI.scale(5);

        // --- Simplified-only: sort arrow buttons (using NHeaderToggle for square hit area) ---
        int sortBtnX = margin;
        NHeaderToggle nameSortBtn = new NHeaderToggle(
            sortarrowi[0], sortarrowi[1], sortarrowi[2], sortarrowi[3],
            (val) -> {
                compactNameAscending = !val;
                compactLastSortType = "name";
                rebuildCompactList();
            }
        );
        nameSortBtn.a = false;
        nameSortBtn.tip(nurgling.i18n.L10n.get("inventory.tip.sort_by_name"));
        rightPanel.add(nameSortBtn, new Coord(sortBtnX, y));
        simplifiedOnlyWidgets.add(nameSortBtn);

        sortBtnX += nameSortBtn.sz.x + UI.scale(1);
        NHeaderToggle qtySortBtn = new NHeaderToggle(
            sortarrowi[0], sortarrowi[1], sortarrowi[2], sortarrowi[3],
            (val) -> {
                compactQuantityAscending = !val;
                compactLastSortType = "quantity";
                rebuildCompactList();
            }
        );
        qtySortBtn.a = true; // default: quantity descending
        qtySortBtn.tip(nurgling.i18n.L10n.get("inventory.tip.sort_by_quantity"));
        rightPanel.add(qtySortBtn, new Coord(sortBtnX, y));
        simplifiedOnlyWidgets.add(qtySortBtn);

        int sortRowH = nameSortBtn.sz.y + elementGap;
        itemListSimplifiedY = margin + sortRowH;

        // --- Row 1: Toggle buttons (all 7 in one line) ---
        int btnX = margin;
        Widget pw;

        pw = rightPanel.add(new ICheckBox(gildingi[0], gildingi[1], gildingi[2], gildingi[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                Slotted.show = val;
            }
        }, new Coord(btnX, y));
        pw.settip(Resource.remote().loadwait("nurgling/hud/buttons/gilding/u").flayer(Resource.tooltip).text());
        ((ICheckBox) pw).a = Slotted.show;
        expandedOnlyWidgets.add(pw);

        btnX += pw.sz.x + elementGap;
        pw = rightPanel.add(new ICheckBox(vari[0], vari[1], vari[2], vari[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                NFoodInfo.show = val;
                NConfig.set(NConfig.Key.showVarity, val);
            }
        }, new Coord(btnX, y));
        pw.settip(Resource.remote().loadwait("nurgling/hud/buttons/var/u").flayer(Resource.tooltip).text());
        NFoodInfo.show = (Boolean) NConfig.get(NConfig.Key.showVarity);
        ((ICheckBox) pw).a = NFoodInfo.show;
        expandedOnlyWidgets.add(pw);

        btnX += pw.sz.x + elementGap;
        pw = rightPanel.add(new ICheckBox(numberi[0], numberi[1], numberi[2], numberi[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                NConfig.set(NConfig.Key.showInventoryNums, val);
            }
        }, new Coord(btnX, y));
        pw.settip(Resource.remote().loadwait("nurgling/hud/buttons/numbering/u").flayer(Resource.tooltip).text());
        ((ICheckBox) pw).a = (Boolean) NConfig.get(NConfig.Key.showInventoryNums);
        expandedOnlyWidgets.add(pw);

        btnX += pw.sz.x + elementGap;
        pw = rightPanel.add(new ICheckBox(stacki[0], stacki[1], stacki[2], stacki[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                Stack.show = val;
            }
        }, new Coord(btnX, y));
        ((ICheckBox) pw).a = Stack.show;
        pw.settip(Resource.remote().loadwait("nurgling/hud/buttons/stack/u").flayer(Resource.tooltip).text());
        expandedOnlyWidgets.add(pw);

        // Continue on same row: bundle, autoflower, autosplitter
        btnX += pw.sz.x + elementGap;
        bundle = rightPanel.add(new ICheckBox(bundlei[0], bundlei[1], bundlei[2], bundlei[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                pagBundle.use(new MenuGrid.Interaction(1, 0));
            }
        }, new Coord(btnX, y));
        bundle.settip(Resource.remote().loadwait("nurgling/hud/buttons/bundle/u").flayer(Resource.tooltip).text());
        expandedOnlyWidgets.add(bundle);

        btnX += bundle.sz.x + elementGap;
        pw = rightPanel.add(new ICheckBox(autoflower[0], autoflower[1], autoflower[2], autoflower[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                NConfig.set(NConfig.Key.autoFlower, val);
            }
        }, new Coord(btnX, y));
        pw.settip(Resource.remote().loadwait("nurgling/hud/buttons/autoflower/u").flayer(Resource.tooltip).text());
        ((ICheckBox) pw).a = (Boolean) NConfig.get(NConfig.Key.autoFlower);
        expandedOnlyWidgets.add(pw);

        btnX += pw.sz.x + elementGap;
        pw = rightPanel.add(new ICheckBox(autosplittor[0], autosplittor[1], autosplittor[2], autosplittor[3]) {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                NConfig.set(NConfig.Key.autoSplitter, val);
            }
        }, new Coord(btnX, y));
        pw.settip(Resource.remote().loadwait("nurgling/hud/buttons/autosplittor/u").flayer(Resource.tooltip).text());
        ((ICheckBox) pw).a = (Boolean) NConfig.get(NConfig.Key.autoSplitter);
        expandedOnlyWidgets.add(pw);

        // --- Row 3: Dropdowns (grouping, display type, quality filter) ---
        y += pw.sz.y + UI.scale(8);
        int dropX = margin;

        int groupingW = UI.scale(85);
        groupingDropbox = new Dropbox<Grouping>(groupingW, Grouping.values().length, UI.scale(16)) {
            @Override
            protected Grouping listitem(int i) { return Grouping.values()[i]; }
            @Override
            protected int listitems() { return Grouping.values().length; }
            @Override
            protected void drawitem(GOut g, Grouping item, int idx) {
                g.text(item.displayName, new Coord(3, 2));
            }
            @Override
            public void change(Grouping item) {
                super.change(item);
                currentGrouping = item;
                rebuildItemList();
            }
        };
        groupingDropbox.change(Grouping.NONE);
        rightPanel.add(groupingDropbox, new Coord(dropX, y));
        expandedOnlyWidgets.add(groupingDropbox);

        dropX += groupingW + elementGap;
        int displayTypeW = UI.scale(55);
        displayTypeDropbox = new Dropbox<DisplayType>(displayTypeW, DisplayType.values().length, UI.scale(16)) {
            @Override
            protected DisplayType listitem(int i) { return DisplayType.values()[i]; }
            @Override
            protected int listitems() { return DisplayType.values().length; }
            @Override
            protected void drawitem(GOut g, DisplayType item, int idx) {
                g.text(item.name(), new Coord(3, 2));
            }
            @Override
            public void change(DisplayType item) {
                super.change(item);
                currentDisplayType = item;
                rebuildItemList();
            }
        };
        displayTypeDropbox.change(currentDisplayType);
        rightPanel.add(displayTypeDropbox, new Coord(dropX, y));
        expandedOnlyWidgets.add(displayTypeDropbox);

        dropX += displayTypeW + elementGap;
        int qualityW = UI.scale(32);
        qualityFilterEntry = new TextEntry(qualityW, "") {
            @Override
            public void changed() {
                super.changed();
                parseQualityFilter();
                rebuildItemList();
            }
        };
        qualityFilterEntry.settip("Min quality filter\nEnter a number (e.g. 10)\nto show only items with q >= 10");
        rightPanel.add(qualityFilterEntry, new Coord(dropX, y + UI.scale(-2)));
        expandedOnlyWidgets.add(qualityFilterEntry);

        // --- Row 4: Space label ---
        y += UI.scale(22);
        spaceLabel = new Label("");
        spaceLabel.setcolor(java.awt.Color.WHITE);
        updateSpaceLabel();
        rightPanel.add(spaceLabel, new Coord(margin, y));
        expandedOnlyWidgets.add(spaceLabel);

        // --- Row 5: Scrollable item list (fills remaining height) ---
        y += UI.scale(18);
        itemListExpandedY = y;
        int listWidth = UI.scale(230);
        int listHeight = Math.max(UI.scale(100), sz.y - y - margin);

        itemListContainer = rightPanel.add(new Scrollport(new Coord(listWidth, listHeight)), new Coord(margin, y));
        itemListContent = new Widget(new Coord(listWidth, UI.scale(50))) {
            @Override
            public void pack() {
                resize(contentsz());
            }
        };
        itemListContainer.cont.add(itemListContent, Coord.z);

        rebuildItemList();
    }

    private void updateItemListSize() {
        if (itemListContainer == null || rightPanel == null) return;
        int margin = UI.scale(8);
        if (panelState == PANEL_SIMPLIFIED) {
            // In simplified mode, list below sort arrows
            itemListContainer.move(new Coord(margin, itemListSimplifiedY));
            int listWidth = PANEL_W_SIMPLIFIED - margin * 2;
            int listHeight = Math.max(UI.scale(100), rightPanel.sz.y - itemListSimplifiedY - margin);
            itemListContainer.resize(new Coord(listWidth, listHeight));
        } else {
            // In expanded mode, list below controls
            itemListContainer.move(new Coord(margin, itemListExpandedY));
            int listWidth = UI.scale(230);
            int listHeight = Math.max(UI.scale(100), rightPanel.sz.y - itemListExpandedY - margin);
            itemListContainer.resize(new Coord(listWidth, listHeight));
        }
    }

    /**
     * Parse the quality filter text entry to get min quality value
     */
    private void parseQualityFilter() {
        if (qualityFilterEntry == null) {
            minQualityFilter = null;
            return;
        }
        String text = qualityFilterEntry.text().trim();
        if (text.isEmpty()) {
            minQualityFilter = null;
            return;
        }
        try {
            minQualityFilter = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            minQualityFilter = null;
        }
    }

    private void applySorting() {
        rebuildItemList();
    }

    /**
     * Update the space label showing filled/total slots
     */
    private void updateSpaceLabel() {
        if (spaceLabel == null) return;
        int filled = calcFilledSlots();
        int total = calcTotalSpace();
        if (total > 0) {
            spaceLabel.settext(String.format("Slots: %d/%d", filled, total));
        }
    }
    
    /**
     * Calculate how many slots are filled
     */
    private int calcFilledSlots() {
        int count = 0;
        for (Widget wdg = child; wdg != null; wdg = wdg.next) {
            if (wdg instanceof WItem) {
                WItem wItem = (WItem) wdg;
                if (wItem.item.spr != null) {
                    Coord sz = wItem.item.spr.sz().div(UI.scale(32));
                    count += sz.x * sz.y;
                } else {
                    count += 1;
                }
            }
        }
        return count;
    }
    
    // Helper class to group items by name and optionally quality
    private static class ItemGroup {
        String name;
        String groupKey; // Key for grouping (includes quality info if grouped by quality)
        Double groupQuality; // Quality value if grouped by quality (null for Type grouping)
        int totalQuantity = 0;
        double averageQuality = 0;
        java.util.List<WItem> wItems = new ArrayList<>(); // Store WItems for actions
        java.util.List<NGItem> items = new ArrayList<>();
        // Curio info
        Integer curioLph = null;
        Integer curioMw = null;
        Double curioMeter = null; // Study progress (0-1)
        
        ItemGroup(String name) {
            this.name = name;
            this.groupKey = name;
            this.groupQuality = null;
        }
        
        ItemGroup(String name, Double quality, Grouping grouping) {
            this.name = name;
            this.groupQuality = quality;
            if (quality != null && grouping != Grouping.NONE) {
                this.groupKey = name + "@Q" + quantifyQuality(quality, grouping);
            } else {
                this.groupKey = name;
            }
        }
        
        void addItem(NGItem item, WItem wItem) {
            items.add(item);
            if (wItem != null) {
                wItems.add(wItem);
            }
            recalculate();
            
            // Extract curio info from first item if available
            if (curioLph == null) {
                try {
                    NCuriosity curio = item.getInfo(NCuriosity.class);
                    if (curio != null) {
                        curioLph = NCuriosity.lph(curio.lph);
                        curioMw = curio.mw;
                        
                        // Get study progress meter using ItemInfo.find
                        GItem.MeterInfo meterInfo = ItemInfo.find(GItem.MeterInfo.class, item.info());
                        if (meterInfo != null) {
                            curioMeter = meterInfo.meter();
                        }
                    }
                } catch (Exception e) {
                    // Ignore - not a curio
                }
            }
        }
        
        void addItem(NGItem item) {
            addItem(item, null);
        }
        
        static double quantifyQuality(Double q, Grouping g) {
            if (q == null) return 0;
            if (g == Grouping.Q1) {
                return Math.floor(q);
            } else if (g == Grouping.Q5) {
                double floored = Math.floor(q);
                return floored - (floored % 5);
            } else if (g == Grouping.Q10) {
                double floored = Math.floor(q);
                return floored - (floored % 10);
            }
            return q;
        }
        
        void recalculate() {
            // Recalculate total quantity and quality
            totalQuantity = 0;
            double totalQuality = 0;
            int qualityCount = 0;
            
            for (NGItem item : items) {
                // Get proper stack count using Amount info like GetTotalAmountItems does
                int stackSize = 1;
                try {
                    GItem.Amount amount = item.getInfo(GItem.Amount.class);
                    if (amount != null && amount.itemnum() > 0) {
                        stackSize = amount.itemnum();
                    }
                } catch (Exception e) {
                    stackSize = 1;
                }

                totalQuantity += stackSize;

                // Calculate quality - try to get stack quality first, then fallback to item quality
                double itemQuality = 0;
                if(stackSize > 1) {
                    // Try to get stack quality info for stacked items
                    Stack stackInfo = item.getInfo(Stack.class);
                    if (stackInfo != null && stackInfo.quality > 0) {
                        itemQuality = stackInfo.quality;
                    } else if (item.quality != null && item.quality > 0) {
                        // Fallback to individual item quality if no stack quality
                        itemQuality = item.quality;
                    }
                } else {
                    // Fallback to individual item quality on any error
                    try {
                        if (item.quality != null && item.quality > 0) {
                            itemQuality = item.quality;
                        }
                    } catch (Exception e2) {
                        // Ignore and continue with 0 quality
                        itemQuality = 0;
                    }
                }

                
                if (itemQuality > 0) {
                    // Weight quality by stack size for accurate average
                    totalQuality += itemQuality * stackSize;
                    qualityCount += stackSize;
                }
            }
            
            if (qualityCount > 0) {
                averageQuality = totalQuality / qualityCount;
            } else {
                averageQuality = 0;
            }
        }
        
        NGItem getRepresentativeItem() {
            return items.isEmpty() ? null : items.get(0);
        }
    }
    
    /**
     * Get quality of an item, considering stack quality
     */
    private static Double getItemQuality(NGItem item) {
        try {
            Stack stackInfo = item.getInfo(Stack.class);
            if (stackInfo != null && stackInfo.quality > 0) {
                return (double) stackInfo.quality;
            }
            if (item.quality != null && item.quality > 0) {
                return item.quality.doubleValue();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    private void rebuildItemList() {
        if (itemListContent == null) return;
        
        // Clear existing widgets from content
        for (Widget child : new ArrayList<>(itemListContent.children())) {
            child.destroy();
        }
        
        // Get current inventory items and group by name + quality (depending on grouping mode)
        Map<String, ItemGroup> itemGroupMap = new HashMap<>();
        
        // Access parent inventory's children
        for (Widget widget = this.child; widget != null; widget = widget.next) {
            if (widget instanceof WItem) {
                WItem wItem = (WItem) widget;
                if (wItem.item instanceof NGItem) {
                    NGItem nitem = (NGItem) wItem.item;
                    String itemName = nitem.name();
                    
                    if (itemName != null) {
                        Double quality = getItemQuality(nitem);
                        
                        // Apply quality filter
                        if (minQualityFilter != null) {
                            double itemQ = quality != null ? quality : 0;
                            if (itemQ < minQualityFilter) {
                                continue; // Skip items below min quality
                            }
                        }
                        
                        String groupKey;
                        
                        // Create group key based on grouping mode
                        if (currentGrouping == Grouping.NONE) {
                            groupKey = itemName;
                        } else {
                            double quantifiedQ = quality != null ? ItemGroup.quantifyQuality(quality, currentGrouping) : 0;
                            groupKey = itemName + "@Q" + (int) quantifiedQ;
                        }
                        
                        ItemGroup group = itemGroupMap.get(groupKey);
                        if (group == null) {
                            group = new ItemGroup(itemName, quality, currentGrouping);
                            itemGroupMap.put(groupKey, group);
                        }
                        group.addItem(nitem, wItem);
                    }
                }
            }
        }
        
        // Sort the items: by name first, then by quality (descending) within same name
        List<ItemGroup> itemGroups = new ArrayList<>(itemGroupMap.values());
        itemGroups.sort((a, b) -> {
            // First sort by name
            int nameResult = a.name.compareTo(b.name);
            if (nameResult != 0) {
                return nameResult;
            }
            
            // Then by quality (descending - higher quality first)
            return -Double.compare(a.averageQuality, b.averageQuality);
        });
        
        // Create widgets for expanded mode (original list layout)
        int y = 0;
        int contentWidth = itemListContainer.cont.sz.x;
        int itemHeight = UI.scale(20);
        
        for (int idx = 0; idx < itemGroups.size(); idx++) {
            ItemGroup group = itemGroups.get(idx);
            Widget itemWidget = createItemWidget(group, new Coord(contentWidth, itemHeight), idx);
            itemListContent.add(itemWidget, new Coord(0, y));
            y += itemHeight + UI.scale(1);
        }
        
        // Let the content widget auto-resize and update scrollbar
        itemListContent.pack();
        itemListContainer.cont.update();
    }
    
    private void rebuildCompactList() {
        if (itemListContent == null) return;

        for (Widget child : new ArrayList<>(itemListContent.children())) {
            child.destroy();
        }

        Map<String, ItemGroup> itemGroupMap = new HashMap<>();
        for (Widget widget = this.child; widget != null; widget = widget.next) {
            if (widget instanceof WItem) {
                WItem wItem = (WItem) widget;
                if (wItem.item instanceof NGItem) {
                    NGItem nitem = (NGItem) wItem.item;
                    String itemName = nitem.name();
                    if (itemName != null) {
                        ItemGroup group = itemGroupMap.get(itemName);
                        if (group == null) {
                            group = new ItemGroup(itemName);
                            itemGroupMap.put(itemName, group);
                        }
                        group.addItem(nitem, wItem);
                    }
                }
            }
        }

        List<ItemGroup> itemGroups = new ArrayList<>(itemGroupMap.values());
        itemGroups.sort((a, b) -> {
            if ("name".equals(compactLastSortType)) {
                int r = a.name.compareTo(b.name);
                if (!compactNameAscending) r = -r;
                if (r == 0) {
                    r = Integer.compare(a.totalQuantity, b.totalQuantity);
                    return compactQuantityAscending ? r : -r;
                }
                return r;
            } else {
                int r = Integer.compare(a.totalQuantity, b.totalQuantity);
                if (!compactQuantityAscending) r = -r;
                if (r == 0) {
                    r = a.name.compareTo(b.name);
                    return compactNameAscending ? r : -r;
                }
                return r;
            }
        });

        int y = 0;
        int contentWidth = itemListContainer.cont.sz.x;
        int itemHeight = UI.scale(18);

        for (int idx = 0; idx < itemGroups.size(); idx++) {
            ItemGroup group = itemGroups.get(idx);
            final int rowIdx = idx;
            Widget cw = new Widget(new Coord(contentWidth, itemHeight)) {
                @Override
                public void draw(GOut g) {
                    g.chcolor(((rowIdx % 2) == 0) ? ROW_EVEN : ROW_ODD);
                    g.frect(Coord.z, sz);
                    g.chcolor();
                    int iconSize = UI.scale(16);
                    int margin = UI.scale(1);
                    NGItem rep = group.getRepresentativeItem();
                    if (rep != null) {
                        try {
                            Resource.Image img = rep.getres().layer(Resource.imgc);
                            if (img != null) g.image(img.tex(), new Coord(margin, margin), new Coord(iconSize, iconSize));
                        } catch (Exception e) { /* ignore */ }
                    }
                    g.text("x" + group.totalQuantity, new Coord(margin + iconSize + UI.scale(4), UI.scale(2)));
                }

                @Override
                public boolean mousedown(MouseDownEvent ev) {
                    if (ui.modshift && (ev.b == 1 || ev.b == 3)) {
                        processGroupItems(group, ev.b == 3, "transfer");
                        return true;
                    }
                    if (ui.modctrl && (ev.b == 1 || ev.b == 3)) {
                        processGroupItems(group, ev.b == 3, "drop");
                        return true;
                    }
                    if (ev.b == 1 && !group.wItems.isEmpty()) {
                        WItem wItem = group.wItems.get(0);
                        if (wItem != null && wItem.parent != null)
                            wItem.item.wdgmsg("take", new Coord(sqsz.x / 2, sqsz.y / 2));
                        return true;
                    }
                    return super.mousedown(ev);
                }

                @Override
                public Object tooltip(Coord c, Widget prev) {
                    return group.name + (group.averageQuality > 0 ? String.format(" (q%.1f)", group.averageQuality) : "");
                }
            };
            itemListContent.add(cw, new Coord(0, y));
            y += itemHeight + UI.scale(1);
        }

        itemListContent.pack();
        itemListContainer.cont.update();
    }

    // Progress bar color for curio items
    private static final Color CURIO_PROGRESS_COLOR = new Color(31, 209, 185, 128);
    private static final Color ROW_EVEN = new Color(255, 255, 255, 13);  // #FFFFFF0D
    private static final Color ROW_ODD  = new Color(0x1C, 0x25, 0x26);  // #1C2526
    
    private Widget createItemWidget(ItemGroup group, Coord sz, int rowIdx) {
        NInventory thisInv = this;
        return new Widget(sz) {
            @Override
            public void draw(GOut g) {
                // Alternating row background
                g.chcolor(((rowIdx % 2) == 0) ? ROW_EVEN : ROW_ODD);
                g.frect(Coord.z, sz);
                g.chcolor();

                int iconSize = UI.scale(19);
                int margin = UI.scale(1);
                int textY = UI.scale(2);

                // Draw curio study progress bar in background
                if (group.curioMeter != null && group.curioMeter > 0) {
                    g.chcolor(CURIO_PROGRESS_COLOR);
                    int progressWidth = (int)((sz.x - iconSize - margin * 2) * group.curioMeter);
                    g.frect(new Coord(iconSize + margin * 2, 0), new Coord(progressWidth, sz.y));
                    g.chcolor();
                }
                
                // Draw item icon
                NGItem representativeItem = group.getRepresentativeItem();
                if (representativeItem != null) {
                    Coord iconPos = new Coord(margin, margin);
                    
                    try {
                        Resource.Image img = representativeItem.getres().layer(Resource.imgc);
                        if (img != null) {
                            g.image(img.tex(), iconPos, new Coord(iconSize, iconSize));
                        } else {
                            g.chcolor(100, 150, 100, 200);
                            g.frect(iconPos, new Coord(iconSize, iconSize));
                            g.chcolor();
                        }
                    } catch (Exception e) {
                        g.chcolor(100, 150, 100, 200);
                        g.frect(iconPos, new Coord(iconSize, iconSize));
                        g.chcolor();
                    }
                }
                
                // Calculate text positions
                int textStartX = margin + iconSize + UI.scale(4);
                
                // Display based on current DisplayType
                String displayText;
                switch (currentDisplayType) {
                    case Quality:
                        String qSign = (currentGrouping == Grouping.NONE || currentGrouping == Grouping.Q) ? "" : "+";
                        if (group.averageQuality > 0) {
                            displayText = String.format("x%d q%.1f%s", group.totalQuantity, group.averageQuality, qSign);
                        } else {
                            displayText = "x" + group.totalQuantity + " " + group.name;
                        }
                        break;
                    case Info:
                        // Show curio info (LP/H, Mental Weight) if available
                        if (group.curioLph != null && group.curioMw != null) {
                            displayText = String.format("x%d lph:%d mw:%d", group.totalQuantity, group.curioLph, group.curioMw);
                        } else {
                            displayText = String.format("x%d %s", group.totalQuantity, group.name);
                        }
                        break;
                    case Name:
                    default:
                        displayText = String.format("x%d %s", group.totalQuantity, group.name);
                        if (group.averageQuality > 0) {
                            displayText += String.format(" (q%.1f)", group.averageQuality);
                        }
                        break;
                }
                
                g.text(displayText, new Coord(textStartX, textY));
            }
            
            @Override
            public boolean mousedown(MouseDownEvent ev) {
                // Shift+Click = Transfer items in group
                if (ui.modshift && (ev.b == 1 || ev.b == 3)) {
                    boolean reverse = (ev.b == 3);
                    processGroupItems(group, reverse, "transfer");
                    return true;
                }
                
                // Ctrl+Click = Drop items in group
                if (ui.modctrl && (ev.b == 1 || ev.b == 3)) {
                    boolean reverse = (ev.b == 3);
                    processGroupItems(group, reverse, "drop");
                    return true;
                }
                
                // Regular click = interact with first item
                if (ev.b == 1 && !group.wItems.isEmpty()) {
                    WItem wItem = group.wItems.get(0);
                    if (wItem != null && wItem.parent != null) {
                        wItem.item.wdgmsg("take", new Coord(sqsz.x / 2, sqsz.y / 2));
                    }
                    return true;
                }
                
                return super.mousedown(ev);
            }
            
            @Override
            public Object tooltip(Coord c, Widget prev) {
                StringBuilder sb = new StringBuilder();
                sb.append(group.name);
                if (group.averageQuality > 0) {
                    sb.append(String.format(" (q%.1f)", group.averageQuality));
                }
                if (group.curioLph != null && group.curioMw != null) {
                    sb.append(String.format("\nLP/H: %d  MW: %d", group.curioLph, group.curioMw));
                }
                return sb.toString();
            }
        };
    }
    
    /**
     * Process items in a group (transfer or drop), sorted by quality.
     * Shift+Click: transfer one item (highest quality first, or lowest if reverse)
     * Shift+Alt+Click: transfer ALL items in group
     * Ctrl+Click: drop one item
     * Ctrl+Alt+Click: drop ALL items in group
     */
    private void processGroupItems(ItemGroup group, boolean reverse, String action) {
        // Sort items by quality
        List<WItem> items = new ArrayList<>(group.wItems);
        items.sort((a, b) -> {
            Double qa = getItemQuality((NGItem) a.item);
            Double qb = getItemQuality((NGItem) b.item);
            if (qa == null && qb == null) return 0;
            if (qa == null) return 1;
            if (qb == null) return -1;
            // Default: higher quality first
            int result = Double.compare(qb, qa);
            return reverse ? -result : result;
        });
        
        // Process items based on modifier
        boolean all = ui.modmeta; // Alt key = process all items
        
        if (!all && !items.isEmpty()) {
            // Just process first item
            WItem item = items.get(0);
            if (item != null && item.parent != null) {
                item.item.wdgmsg(action, Coord.z);
            }
        } else {
            // Process all items
            for (WItem item : items) {
                if (item != null && item.parent != null) {
                    item.item.wdgmsg(action, Coord.z);
                }
            }
        }
    }
    

    public short[][] containerMatrix()
    {
        short[][] ret = new short[isz.y][isz.x];
        for (int x = 0; x < isz.x; x++)
        {
            for (int y = 0; y < isz.y; y++)
            {
                if (sqmask == null || !sqmask[y * isz.x + x])
                {
                    ret[y][x] = 0; // Пустая ячейка
                }
                else
                {
                    ret[y][x] = 2; // Заблокированная ячейка
                }
            }
        }
        for (Widget widget = child; widget != null; widget = widget.next)
        {
            if (widget instanceof WItem)
            {
                WItem item = (WItem) widget;
                if (item.item.spr != null)
                {
                    Coord size = item.item.spr.sz().div(UI.scale(32));
                    int xSize = size.x;
                    int ySize = size.y;
                    int xLoc = item.c.div(Inventory.sqsz).x;
                    int yLoc = item.c.div(Inventory.sqsz).y;

                    for (int j = 0; j < ySize; j++)
                    {
                        for (int i = 0; i < xSize; i++)
                        {
                            if (yLoc + j < isz.y && xLoc + i < isz.x)
                            {
                                ret[yLoc + j][xLoc + i] = 1;
                            }
                        }
                    }
                }
                else
                    return null;
            }
        }
        return ret;
    }

    public int calcNumberFreeCoord(Coord target_size) {
        if (target_size.x < 1 || target_size.y < 1)
            return 0;

        int count = 0;
        short[][] inventory = containerMatrix();
        if (inventory == null)
            return -1;
        for (int i = 0; i <= inventory.length - target_size.x; i++)
            for (int j = 0; j <= inventory[i].length - target_size.y; j++) {
                boolean isFree = true;
                for (int k = i; k < i + target_size.x; k++)
                    for (int n = j; n < j + target_size.y; n++)
                        if (inventory[k][n] != 0) {
                            isFree = false;
                            break;
                        }

                if (isFree) {
                    count++;
                    for (int k = i; k < i + target_size.x; k++)
                        for (int n = j; n < j + target_size.y; n++)
                            inventory[k][n] = 1;
                }
            }

        return count;
    }

    public Coord findFreeCoord(WItem wItem)
    {
        Coord sz = wItem.item.spr.sz().div(UI.scale(32));
        return findFreeCoord(new Coord(sz.y,sz.x));
    }


    public Coord findFreeCoord(Coord target_size) {
        short[][] inventory = containerMatrix();
        if ((inventory == null) || (target_size.y < 1) || (target_size.x < 1))
            return null;
        for (int i = 0; i <= isz.y - target_size.y; i++)
            for (int j = 0; j <= isz.x - target_size.x; j++)
                if (inventory[i][j] == 0) {
                    boolean isFree = true;
                    for (int k = i; k < i + target_size.x; k++)
                        for (int n = j; n < j + target_size.y; n++)
                            if (n >= isz.x || k >= isz.y || inventory[k][n] != 0) {
                                isFree = false;
                                break;
                            }
                    if (isFree)
                        return new Coord(j, i);
                }
        return null;
    }

    public int calcFreeSpace()
    {
        int freespace = 0;
        short[][] inventory = containerMatrix();
        if(inventory == null)
            return -1;
        for (int i = 0; i < isz.y; i++)
            for (int j = 0; j < isz.x; j++)
                if (inventory[i][j] == 0)
                    freespace++;
        return freespace;
    }

    public int calcTotalSpace()
    {
        int totalSpace = 0;
        short[][] inventory = containerMatrix();
        if(inventory == null)
            return -1;
        for (int i = 0; i < isz.y; i++)
            for (int j = 0; j < isz.x; j++)
                if (inventory[i][j] != 2)
                    totalSpace++;
        return totalSpace;
    }

    public boolean isSlotFree(Coord pos)
    {
        short[][] inventory = containerMatrix();
        return inventory!=null && inventory[pos.y][pos.x] == 0;
    }

    public boolean isItemInSlot(Coord pos , NAlias name)
    {
        for (Widget widget = child; widget != null; widget = widget.next)
        {
            if (widget instanceof WItem)
            {
                WItem item = (WItem) widget;
                if(item.c.div(Inventory.sqsz).equals(pos))
                    return ((NGItem)item.item).name() != null && NParser.checkName(((NGItem)item.item).name(), name);
            }
        }
        return false;
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("transfer-same")) {
            process(getSame((NGItem) args[0], (Boolean) args[1]), "transfer");
        }
        else if (msg.equals("drop-same")) {
            process(getSame((NGItem) args[0], (Boolean) args[1]), "drop");
        }
        else
        {
            super.wdgmsg(sender, msg, args);
        }
    }

    private void process(List<NGItem> items, String action) {
        for (GItem item : items) {
            item.wdgmsg(action, Coord.z);
        }
    }

    private List<NGItem> getSame(NGItem item, Boolean ascending)
    {
        List<NGItem> items = new ArrayList<>();
        if (item != null && item.name() != null)
        {
            // Only collect direct children of inventory, don't expand stacks
            // (expanding stacks would break them apart during transfer)
            for (Widget wdg = lchild; wdg != null; wdg = wdg.prev)
            {
                if (wdg.visible && wdg instanceof NWItem)
                {
                    NWItem wItem = (NWItem) wdg;
                    if (item.isSearched)
                    {
                        if (((NGItem) wItem.item).isSearched)
                            items.add((NGItem) wItem.item);
                    }
                    else
                    {
                        if (NParser.checkName(item.name(), ((NGItem) wItem.item).name()))
                        {
                            items.add((NGItem) wItem.item);
                        }
                    }
                }
            }
            items.sort(ascending ? ITEM_COMPARATOR_ASC : ITEM_COMPARATOR_DESC);
        }
        return items;
    }

    /**
     * Gets the effective quality of an item, considering stack quality for stacked items
     */
    private static double getEffectiveQuality(NGItem item) {
        // First try to get stack quality (for stacked items)
        Stack stackInfo = item.getInfo(Stack.class);
        if (stackInfo != null && stackInfo.quality > 0) {
            return stackInfo.quality;
        }
        // Fall back to individual item quality
        if (item.quality != null && item.quality > 0) {
            return item.quality;
        }
        return -1; // No quality available
    }
    
    public static final Comparator<NGItem> ITEM_COMPARATOR_ASC = new Comparator<NGItem>() {
        @Override
        public int compare(NGItem o1, NGItem o2) {
            double q1 = getEffectiveQuality(o1);
            double q2 = getEffectiveQuality(o2);
            // Items with no quality (-1) go to the end
            if (q1 < 0 && q2 < 0) return 0;
            if (q1 < 0) return 1;  // no quality goes to the end
            if (q2 < 0) return -1; // no quality goes to the end
            return Double.compare(q1, q2);
        }
    };
    public static final Comparator<NGItem> ITEM_COMPARATOR_DESC = new Comparator<NGItem>() {
        @Override
        public int compare(NGItem o1, NGItem o2) {
            double q1 = getEffectiveQuality(o1);
            double q2 = getEffectiveQuality(o2);
            // Items with no quality (-1) go to the end
            if (q1 < 0 && q2 < 0) return 0;
            if (q1 < 0) return 1;  // no quality goes to the end
            if (q2 < 0) return -1; // no quality goes to the end
            return Double.compare(q2, q1);
        }
    };


    public <C extends ItemInfo> ArrayList<WItem> getItems(Class<C> c) throws InterruptedException
    {
        GetItemsWithInfo gi = new GetItemsWithInfo(this, c);
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public ArrayList<ItemWatcher.ItemInfo> iis = new ArrayList<>();
    
    /**
     * Generate a hash for an item for cache identification
     */
    public String generateItemHash(ItemWatcher.ItemInfo item) {
        if (item == null || item.name == null) return null;
        String data = item.name + item.c.toString() + item.q + "_" + item.stackIndex;
        return NUtils.calculateSHA256(data);
    }

    @Override
    public void reqdestroy() {
        // Mark as closing
        isClosing = true;
        
        // Only process if this is an indexable container
        if (isIndexable() && parentGob != null && parentGob.ngob != null && parentGob.ngob.hash != null) {
            String containerHash = parentGob.ngob.hash;
            
            // Clear pending cache removals - container closed, so items weren't consumed
            int pendingCount = pendingCacheRemovals.size();
            pendingCacheRemovals.clear();
            
            if ((Boolean) NConfig.get(NConfig.Key.ndbenable)) {
                System.out.println("NInventory.reqdestroy: Syncing " + iis.size() + " items for container " + containerHash + " (cleared " + pendingCount + " pending)");
                ui.core.writeItemInfoForContainer(iis, containerHash);
            }
        }
        // For non-indexable containers, just clear without logging
        pendingCacheRemovals.clear();

        // Close Study Desk Planner if this is a study desk inventory
        if (nurgling.widgets.StudyDeskInventoryExtension.isStudyDeskInventory(this)) {
            NGameUI gameUI = NUtils.getGameUI();
            if (gameUI != null && gameUI.studyDeskPlanner != null && gameUI.studyDeskPlanner.visible()) {
                gameUI.studyDeskPlanner.hide();
            }
        }

        super.reqdestroy();
    }

    public ItemStack findNotFullStack(String name) throws InterruptedException {
        GetNotFullStack gi = new GetNotFullStack(this, new NAlias(name));
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

    public WItem findNotStack(String name) throws InterruptedException {
        GetNotStack gi = new GetNotStack(this, new NAlias(name));
        NUtils.getUI().core.addTask(gi);
        return gi.getResult();
    }

}
