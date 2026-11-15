package nurgling.widgets;

import haven.*;
import haven.Locked;
import nurgling.FishLocation;
import nurgling.FishLocationService;
import nurgling.i18n.L10n;
import nurgling.NGameUI;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Window for searching saved fish locations by fish name, moon phase, and catch percentage
 */
public class FishSearchWindow extends Window {
    private final NGameUI gui;
    private final FishLocationService fishService;

    private NDropbox<String> fishNameDropdown;
    private NDropbox<String> moonPhaseDropdown;
    private TextEntry percentageEntry;
    private FishResultsList resultsList;
    private BestCombosWindow bestWindow;

    private static final int WINDOW_WIDTH = UI.scale(560);
    private static final int WINDOW_HEIGHT = UI.scale(600);
    private static final int MARGIN = UI.scale(10);
    private static final int ROW_H = UI.scale(34);
    /** Left gutter of each result row that holds the fish icon; labels line up with the row text after it. */
    private static final int GUTTER = UI.scale(36);
    private static final int GEAR = UI.scale(30);
    private static final int GAP = UI.scale(3);

    public FishSearchWindow(NGameUI gui) {
        super(new Coord(WINDOW_WIDTH, WINDOW_HEIGHT), L10n.get("fish.search_title"), true);
        this.gui = gui;
        this.fishService = gui.fishLocationService;

        int y = UI.scale(10);
        int labelX = MARGIN + GUTTER;
        int controlX = labelX + UI.scale(120);
        int controlW = WINDOW_WIDTH - controlX - MARGIN - UI.scale(30);
        int lineHeight = UI.scale(30);

        // Fish name filter
        add(new Label(L10n.get("fish.name")), labelX, y + UI.scale(5));
        List<String> fishNames = getDistinctFishNames();
        fishNames.add(0, "Any"); // Add "Any" option at the beginning
        fishNameDropdown = add(new NDropbox<String>(controlW, Math.min(fishNames.size(), 10), UI.scale(20)) {
            @Override
            protected String listitem(int i) {
                return fishNames.get(i);
            }

            @Override
            protected int listitems() {
                return fishNames.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, controlX, y);
        fishNameDropdown.change(fishNames.get(0)); // Select "Any" by default
        y += lineHeight;

        // Moon phase filter
        add(new Label(L10n.get("fish.moon_phase")), labelX, y + UI.scale(5));
        List<String> moonPhases = getDistinctMoonPhases();
        moonPhases.add(0, "Any"); // Add "Any" option at the beginning
        moonPhaseDropdown = add(new NDropbox<String>(controlW, Math.min(moonPhases.size(), 10), UI.scale(20)) {
            @Override
            protected String listitem(int i) {
                return moonPhases.get(i);
            }

            @Override
            protected int listitems() {
                return moonPhases.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, controlX, y);
        moonPhaseDropdown.change(moonPhases.get(0)); // Select "Any" by default
        y += lineHeight;

        // Percentage filter (minimum)
        add(new Label(L10n.get("fish.min_percent")), labelX, y + UI.scale(5));
        percentageEntry = add(new TextEntry(UI.scale(100), "0"), controlX, y);
        y += lineHeight + UI.scale(5);

        // Search button, and the best combination per fish
        Button search = add(new Button(UI.scale(150), L10n.get("common.search")) {
            @Override
            public void click() {
                performSearch();
            }
        }, controlX, y);
        add(new Button(UI.scale(190), L10n.get("fish.best_button")) {
            @Override
            public void click() {
                toggleBest();
            }
        }, search.pos("ur").adds(UI.scale(10), 0));
        y += lineHeight + UI.scale(5);

        // Where saved fish are drawn: the map and the world each have their own switch
        CheckBox onMap = add(new CheckBox(L10n.get("fish.show_map_icons")) {
            @Override
            public void changed(boolean val) {
                NMiniMap.showFishIcons(val);
            }
        }, controlX, y);
        onMap.a = NMiniMap.showFishIcons();
        onMap.settip(L10n.get("fish.show_map_icons_tip"));
        CheckBox inWorld = add(new CheckBox(L10n.get("fish.show_world_icons")) {
            @Override
            public void changed(boolean val) {
                nurgling.NConfig.set(nurgling.NConfig.Key.showFishWorldIcons, val);
            }
        }, onMap.pos("ur").adds(UI.scale(20), 0));
        inWorld.a = nurgling.overlays.NFishWorldIcons.enabled();
        inWorld.settip(L10n.get("fish.show_world_icons_tip"));
        y += lineHeight + UI.scale(5);

        // Results list
        add(new Label(L10n.get("common.results")), labelX, y);
        y += UI.scale(25);

        Coord resultsSize = new Coord(WINDOW_WIDTH - 2 * MARGIN, WINDOW_HEIGHT - y - MARGIN);
        resultsList = add(new FishResultsList(resultsSize), MARGIN, y);

        pack();
    }

    /**
     * Get list of distinct fish names from saved locations
     */
    private List<String> getDistinctFishNames() {
        if (fishService == null) return new ArrayList<>();

        return fishService.getAllFishLocations().stream()
            .map(FishLocation::getFishName)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Get list of distinct moon phases from saved locations
     */
    private List<String> getDistinctMoonPhases() {
        if (fishService == null) return new ArrayList<>();

        return fishService.getAllFishLocations().stream()
            .map(FishLocation::getMoonPhase)
            .filter(phase -> !phase.equals("Unknown"))
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Perform search based on selected filters
     */
    private void performSearch() {
        if (fishService == null) return;

        String selectedFish = fishNameDropdown.sel;
        String selectedMoon = moonPhaseDropdown.sel;

        // Parse percentage once and make it final for lambda
        int parsedPercentage;
        try {
            parsedPercentage = Integer.parseInt(percentageEntry.text());
        } catch (NumberFormatException e) {
            parsedPercentage = 0;
        }
        final int minPercentage = parsedPercentage;

        // Get all locations and filter
        List<FishLocation> results = fishService.getAllFishLocations().stream()
            .filter(loc -> {
                // Filter by fish name
                if (!selectedFish.equals("Any") && !loc.getFishName().equals(selectedFish)) {
                    return false;
                }

                // Filter by moon phase
                if (!selectedMoon.equals("Any") && !loc.getMoonPhase().equals(selectedMoon)) {
                    return false;
                }

                // Filter by percentage ("23%" from a catch, "23.0%" from a survey); unreadable ones are left out
                double percent = nurgling.overlays.NFishWorldIcons.percent(loc.getPercentage());
                return percent >= 0 && percent >= minPercentage;
            })
            .collect(Collectors.toList());

        resultsList.setResults(results);
    }

    /**
     * List widget for displaying search results: fish icon in a left gutter, the description (click to show
     * it on the map), the hook / line / bait the spot was saved with, and a delete button.
     */
    private class FishResultsList extends SListBox<FishLocation, Widget> {
        private List<FishLocation> results = new ArrayList<>();

        public FishResultsList(Coord sz) {
            super(sz, ROW_H, UI.scale(2));
        }

        public void setResults(List<FishLocation> results) {
            this.results = results;
        }

        @Override
        protected List<FishLocation> items() {
            return results;
        }

        /** Stripes and selection start after the icon gutter, so the fish icons sit in the margin. */
        private Area rowArea(Area area) {
            return Area.corn(area.ul.add(GUTTER, 0), area.br);
        }

        @Override
        protected void drawbg(GOut g, FishLocation item, int idx, Area area) {
            super.drawbg(g, item, idx, rowArea(area));
        }

        @Override
        protected void drawsel(GOut g, FishLocation item, int idx, Area area) {
            super.drawsel(g, item, idx, rowArea(area));
        }

        @Override
        protected Widget makeitem(FishLocation location, int idx, Coord sz) {
            return new ResultRow(this, sz, location);
        }
    }

    private class ResultRow extends SListWidget.ItemWidget<FishLocation> {
        private final Tex text;
        private final int textW;
        private boolean hovering;

        ResultRow(FishResultsList list, Coord sz, FishLocation location) {
            super(list, sz, location);

            IButton delete = new IButton(nurgling.NStyle.crossSquare[0].back,
                                         nurgling.NStyle.crossSquare[1].back,
                                         nurgling.NStyle.crossSquare[2].back) {
                @Override
                public void click() {
                    if (gui != null && gui.fishLocationService != null) {
                        gui.fishLocationService.removeFishLocation(location.getLocationId());
                        gui.msg("Removed " + location.getFishName() + " location", java.awt.Color.YELLOW);
                        // Refresh the search results to remove the deleted item
                        performSearch();
                    }
                }
            };
            delete.settip(L10n.get("fish.delete"));
            int x = sz.x - delete.sz.x - UI.scale(4);
            add(delete, new Coord(x, (sz.y - delete.sz.y) / 2));
            x -= UI.scale(14);

            String[][] gear = {
                {L10n.get("fish.bait"), location.getBait()},
                {L10n.get("fish.line"), location.getLine()},
                {L10n.get("fish.hook"), location.getHook()},
            };
            for (String[] g : gear) {
                x -= GEAR;
                add(new GearSlot(g[0], g[1]), new Coord(x, (sz.y - GEAR) / 2));
                x -= GAP;
            }

            textW = x - GUTTER - UI.scale(8);
            text = Text.render(fit(String.format("%s - %s - %s @ %s",
                location.getFishName(),
                location.getPercentage(),
                location.getMoonPhase(),
                location.getGameTime()), textW)).tex();
            settip(L10n.get("fish.show_on_map"));
        }

        /** Trims the description with an ellipsis so it never runs under the gear slots. */
        private String fit(String s, int w) {
            if (Text.std.strsize(s).x <= w) return s;
            while (s.length() > 1 && Text.std.strsize(s + "\u2026").x > w)
                s = s.substring(0, s.length() - 1);
            return s + "\u2026";
        }

        @Override
        public void draw(GOut g) {
            int side = sz.y - UI.scale(4);
            nurgling.tools.ItemIcons.draw(g, nurgling.tools.ItemIcons.getResource(item.getFishResource()),
                Coord.of((GUTTER - side) / 2, (sz.y - side) / 2), side);
            if (hovering) {
                g.chcolor(255, 255, 255, 20);
                g.frect(Coord.of(GUTTER, 0), Coord.of(sz.x - GUTTER, sz.y));
                g.chcolor();
            }
            g.image(text, Coord.of(GUTTER + UI.scale(4), (sz.y - text.sz().y) / 2));
            super.draw(g);
        }

        @Override
        public boolean mousehover(MouseHoverEvent ev, boolean on) {
            hovering = on;
            return false;
        }

        @Override
        protected boolean clicked(MouseDownEvent ev) {
            super.clicked(ev);
            panMapToLocation(item);
            return true;
        }

        @Override
        public void dispose() {
            text.dispose();
            super.dispose();
        }
    }

    /** One piece of the rod's tackle, shown as an inventory cell with the item's icon and its name as tooltip. */
    private static class GearSlot extends Widget {
        private final String name;

        GearSlot(String kind, String name) {
            super(new Coord(GEAR, GEAR));
            this.name = (name == null || name.isEmpty() || name.equals("Unknown")) ? null : name;
            settip(kind + " " + (this.name == null ? L10n.get("fish.none") : this.name));
        }

        @Override
        public void draw(GOut g) {
            g.image(Inventory.invsq, Coord.z, sz);
            if (name != null) {
                int pad = UI.scale(3);
                nurgling.tools.ItemIcons.draw(g, nurgling.tools.ItemIcons.get(null, name, false),
                    Coord.of(pad, pad), sz.x - 2 * pad);
            }
        }
    }

    private void toggleBest() {
        if (bestWindow != null && bestWindow.parent != null) {
            bestWindow.reqdestroy();
            bestWindow = null;
            return;
        }
        bestWindow = gui.add(new BestCombosWindow(), this.c.add(sz.x + UI.scale(10), 0));
    }

    /** One fish's best saved record, and how many records of that fish there are. */
    static final class Best {
        final FishLocation loc;
        final double pct;
        final int saved;

        Best(FishLocation loc, double pct, int saved) {
            this.loc = loc;
            this.pct = pct;
            this.saved = saved;
        }
    }

    /** Per fish (by name, A to Z): the record with the highest chance, the newest on a tie. */
    static List<Best> bestPerFish(Collection<FishLocation> all) {
        Map<String, FishLocation> best = new TreeMap<>();
        Map<String, Integer> count = new HashMap<>();
        for (FishLocation loc : all) {
            if (loc.getFishName() == null)
                continue;
            double p = nurgling.overlays.NFishWorldIcons.percent(loc.getPercentage());
            count.merge(loc.getFishName(), 1, Integer::sum);
            FishLocation cur = best.get(loc.getFishName());
            double cp = cur == null ? -2 : nurgling.overlays.NFishWorldIcons.percent(cur.getPercentage());
            if (cur == null || p > cp || (p == cp && loc.getTimestamp() > cur.getTimestamp()))
                best.put(loc.getFishName(), loc);
        }
        List<Best> res = new ArrayList<>();
        for (Map.Entry<String, FishLocation> e : best.entrySet())
            res.add(new Best(e.getValue(), nurgling.overlays.NFishWorldIcons.percent(e.getValue().getPercentage()),
                    count.get(e.getKey())));
        return res;
    }

    /** Every saved fish with the line, hook and bait of its best saved chance; click a row to see the spot on the map.
     *  Refreshed every 2 s, so it follows a running fish survey. */
    private class BestCombosWindow extends Window {
        private List<Best> rows = new ArrayList<>();
        private String sig = null;
        private double refresh = 0;
        private final Label empty;

        BestCombosWindow() {
            super(new Coord(UI.scale(460), UI.scale(420)), L10n.get("fish.best_title"), true);
            add(new SListBox<Best, Widget>(new Coord(UI.scale(460), UI.scale(420)), ROW_H, UI.scale(2)) {
                @Override
                protected List<Best> items() {
                    return rows;
                }

                @Override
                protected Widget makeitem(Best b, int idx, Coord sz) {
                    return new BestRow(this, sz, b);
                }
            }, Coord.z);
            empty = add(new Label(L10n.get("fish.best_empty")), UI.scale(10, 10));
            update();
            pack();
        }

        private void update() {
            List<Best> b = fishService == null ? new ArrayList<>() : bestPerFish(fishService.getAllFishLocations());
            StringBuilder k = new StringBuilder();
            for (Best x : b)
                k.append(x.loc.getLocationId()).append(x.saved).append(';');
            if (!k.toString().equals(sig)) {
                sig = k.toString();
                rows = b;
            }
            empty.show(rows.isEmpty());
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            if ((refresh += dt) >= 2) {
                refresh = 0;
                update();
            }
        }

        @Override
        public void wdgmsg(Widget sender, String msg, Object... args) {
            if (msg.equals("close")) {
                reqdestroy();
                bestWindow = null;
            } else {
                super.wdgmsg(sender, msg, args);
            }
        }
    }

    private class BestRow extends SListWidget.ItemWidget<Best> {
        private final Tex name, detail;

        BestRow(SListBox<Best, Widget> list, Coord sz, Best b) {
            super(list, sz, b);
            int x = sz.x - UI.scale(8);
            String[][] gear = {
                {L10n.get("fish.bait"), b.loc.getBait()},
                {L10n.get("fish.line"), b.loc.getLine()},
                {L10n.get("fish.hook"), b.loc.getHook()},
            };
            for (String[] g : gear) {
                x -= GEAR;
                add(new GearSlot(g[0], g[1]), new Coord(x, (sz.y - GEAR) / 2));
                x -= GAP;
            }
            name = Text.render(b.loc.getFishName() + "  " + (b.pct >= 0 ? b.loc.getPercentage() : "?")).tex();
            detail = Text.render(L10n.get("fish.best_saved", b.saved), new java.awt.Color(159, 176, 177)).tex();
            settip(L10n.get("fish.show_on_map"));
        }

        @Override
        public void draw(GOut g) {
            int side = sz.y - UI.scale(4);
            nurgling.tools.ItemIcons.draw(g, nurgling.tools.ItemIcons.getResource(item.loc.getFishResource()),
                Coord.of((GUTTER - side) / 2, (sz.y - side) / 2), side);
            int tx = GUTTER + UI.scale(4);
            g.image(name, Coord.of(tx, sz.y / 2 - name.sz().y));
            g.image(detail, Coord.of(tx, sz.y / 2));
            super.draw(g);
        }

        @Override
        protected boolean clicked(MouseDownEvent ev) {
            super.clicked(ev);
            panMapToLocation(item.loc);
            return true;
        }

        @Override
        public void dispose() {
            name.dispose();
            detail.dispose();
            super.dispose();
        }
    }

    /**
     * Pan the main map to the selected fish location
     */
    private void panMapToLocation(FishLocation location) {
        if (gui == null || gui.mapfile == null) return;

        NMapWnd mapWnd = gui.mapfile;
        if (mapWnd == null || mapWnd.view == null) return;

        // Open map window if not visible
        if (!mapWnd.visible()) {
            gui.togglewnd(mapWnd);
        }

        // A record only knows which segment it draws in once its grid id has been resolved against the
        // map file; an unexplored grid never resolves, so there is nowhere to pan to.
        if (!location.isResolved()) {
            gui.msg("That location is not on your map yet", java.awt.Color.YELLOW);
            return;
        }

        // Get the segment for this fish location
        if (gui.mmap != null && gui.mmap.file != null) {
            try (Locked lk = new Locked(gui.mmap.file.lock.readLock())) {
                MapFile.Segment segment = gui.mmap.file.segments.get(location.getSegmentId());
                if (segment != null) {
                    // Create a location for the fish coordinates
                    MiniMap.Location targetLoc = new MiniMap.Location(segment, location.getTileCoords());
                    // Center the map view on this location
                    mapWnd.view.center(targetLoc);
                    mapWnd.view.follow(null);
                    gui.msg("Map centered on " + location.getFishName() + " location", java.awt.Color.GREEN);
                } else {
                    gui.msg("Fish location is in a different area", java.awt.Color.YELLOW);
                }
            }
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }
}
