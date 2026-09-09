package nurgling.widgets;

import haven.*;
import haven.Locked;
import nurgling.NGameUI;
import nurgling.conf.ProspectKind;
import nurgling.i18n.L10n;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Search the ore, gemstone and stone marks Master Miner has recorded.
 *
 * <p>Same shape as the fish and tree searches, over {@link LabeledMinimapMark} instead of a
 * location service: the marks already carry the exact quality they were recorded at, so
 * quality is a first-class filter rather than something parsed back out of the label.
 */
public class MineralSearchWindow extends Window {
    private static final int WINDOW_WIDTH = UI.scale(400);
    private static final int WINDOW_HEIGHT = UI.scale(500);
    private static final String ANY = "Any";

    /** The categories this window searches, in the order the map buttons sit in. */
    private static final ProspectKind[] KINDS = {ProspectKind.ORE, ProspectKind.GEM, ProspectKind.STONE};

    private final NGameUI gui;

    private NDropbox<String> categoryDropdown;
    private NDropbox<String> typeDropdown;
    private TextEntry minQualityEntry;
    private MineralResultsList resultsList;

    private List<String> categories;
    private List<String> types;
    private final int controlX;
    private final int typeDropdownY;

    public MineralSearchWindow(NGameUI gui) {
        super(new Coord(WINDOW_WIDTH, WINDOW_HEIGHT), L10n.get("mineral.search_title"), true);
        this.gui = gui;

        int y = UI.scale(10);
        int labelX = UI.scale(10);
        controlX = UI.scale(120);
        int lineHeight = UI.scale(30);

        add(new Label(L10n.get("mineral.category")), labelX, y + UI.scale(5));
        categories = new ArrayList<>();
        categories.add(ANY);
        for (ProspectKind kind : KINDS) {
            categories.add(L10n.get(kind.l10nKey));
        }
        categoryDropdown = add(new NDropbox<String>(UI.scale(250), Math.min(categories.size(), 10), UI.scale(20)) {
            @Override
            protected String listitem(int i) {
                return categories.get(i);
            }

            @Override
            protected int listitems() {
                return categories.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                // Narrowing the category narrows what is worth offering as a type.
                refreshTypeDropdown();
            }
        }, controlX, y);
        /* Not change(): that rebuilds the type dropdown, and the rebuild destroys the old
         * one through `ui`, which is still null until this window is added to the tree. */
        categoryDropdown.sel = ANY;
        y += lineHeight;

        add(new Label(L10n.get("mineral.type")), labelX, y + UI.scale(5));
        typeDropdownY = y;
        refreshTypeDropdown();
        y += lineHeight;

        add(new Label(L10n.get("mineral.min_quality")), labelX, y + UI.scale(5));
        minQualityEntry = add(new TextEntry(UI.scale(100), "0") {
            @Override
            public boolean keydown(KeyDownEvent ev) {
                if (ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                    performSearch();
                    return true;
                }
                return super.keydown(ev);
            }
        }, controlX, y);
        y += lineHeight;

        add(new Button(UI.scale(150), L10n.get("common.search")) {
            @Override
            public void click() {
                performSearch();
            }
        }, UI.scale(125), y);
        y += lineHeight + UI.scale(10);

        add(new Label(L10n.get("common.results")), labelX, y);
        y += UI.scale(25);

        Coord resultsSize = new Coord(WINDOW_WIDTH - UI.scale(20), WINDOW_HEIGHT - y - UI.scale(10));
        resultsList = add(new MineralResultsList(resultsSize), labelX, y);

        pack();
    }

    /** Preselect a category, so right-clicking the ore button opens an ore search. */
    public void preset(ProspectKind kind) {
        categoryDropdown.change((kind == null) ? ANY : L10n.get(kind.l10nKey));
        performSearch();
    }

    private ProspectKind selectedKind() {
        String sel = categoryDropdown.sel;
        for (ProspectKind kind : KINDS) {
            if (L10n.get(kind.l10nKey).equals(sel)) {
                return kind;
            }
        }
        return null;
    }

    /** Marks this window covers: everything Master Miner records, in any of the three kinds. */
    private List<LabeledMinimapMark> minedMarks() {
        if (gui == null || gui.labeledMarkService == null) {
            return new ArrayList<>();
        }
        return gui.labeledMarkService.getAllMarks().stream()
                .filter(mark -> {
                    for (ProspectKind kind : KINDS) {
                        if (mark.kind == kind) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    /** Offer only the resource types actually recorded, narrowed to the chosen category. */
    private void refreshTypeDropdown() {
        String previous = (typeDropdown == null) ? ANY : typeDropdown.sel;
        if (typeDropdown != null) {
            // ui is null until this window joins the tree; destroy() itself is null-safe.
            if (ui != null) {
                ui.destroy(typeDropdown);
            } else {
                typeDropdown.destroy();
            }
            typeDropdown = null;
        }
        ProspectKind kind = selectedKind();
        types = minedMarks().stream()
                .filter(mark -> (kind == null) || (mark.kind == kind))
                .map(mark -> mark.resourceType)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        types.add(0, ANY);

        typeDropdown = add(new NDropbox<String>(UI.scale(250), Math.min(types.size(), 10), UI.scale(20)) {
            @Override
            protected String listitem(int i) {
                return types.get(i);
            }

            @Override
            protected int listitems() {
                return types.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, controlX, typeDropdownY);
        // Keep the chosen type across a category change when it is still on offer.
        typeDropdown.change(types.contains(previous) ? previous : ANY);
    }

    private void performSearch() {
        double minQuality;
        try {
            String txt = minQualityEntry.text().trim();
            minQuality = txt.isEmpty() ? 0 : Double.parseDouble(txt.replace(',', '.'));
        } catch (NumberFormatException e) {
            minQuality = 0;
        }
        final double min = minQuality;
        final ProspectKind kind = selectedKind();
        final String type = typeDropdown.sel;

        List<LabeledMinimapMark> results = minedMarks().stream()
                .filter(mark -> (kind == null) || (mark.kind == kind))
                .filter(mark -> ANY.equals(type) || type.equals(mark.resourceType))
                .filter(mark -> mark.quality >= min)
                // Richest first: the whole point of recording quality is finding the best spot.
                .sorted(Comparator.comparingDouble((LabeledMinimapMark m) -> m.quality).reversed())
                .collect(Collectors.toList());

        resultsList.setResults(results);
    }

    private class MineralResultsList extends SListBox<LabeledMinimapMark, Widget> {
        private List<LabeledMinimapMark> results = new ArrayList<>();

        MineralResultsList(Coord sz) {
            super(sz, UI.scale(25));
        }

        void setResults(List<LabeledMinimapMark> results) {
            this.results = results;
        }

        @Override
        protected List<LabeledMinimapMark> items() {
            return results;
        }

        @Override
        protected Widget makeitem(LabeledMinimapMark mark, int idx, Coord sz) {
            return new ItemWidget<LabeledMinimapMark>(this, sz, mark) {
                {
                    int deleteButtonWidth = UI.scale(22);
                    int panButtonWidth = sz.x - deleteButtonWidth - UI.scale(4);

                    add(new Button(panButtonWidth, "") {
                        @Override
                        public void draw(GOut g) {
                            g.text(String.format("%s - q%.0f", mark.resourceType, mark.quality), Coord.z);
                        }

                        @Override
                        public void click() {
                            panMapToMark(mark);
                        }
                    }, Coord.z);

                    add(new IButton(nurgling.NStyle.crossSquare[0].back,
                                    nurgling.NStyle.crossSquare[1].back,
                                    nurgling.NStyle.crossSquare[2].back) {
                        @Override
                        public void click() {
                            if (gui != null && gui.labeledMarkService != null) {
                                gui.labeledMarkService.removeMark(mark.getLocationId());
                                gui.msg("Removed " + mark.resourceType + " mark", java.awt.Color.YELLOW);
                                performSearch();
                            }
                        }
                    }, new Coord(panButtonWidth + UI.scale(2), (sz.y - UI.scale(22)) / 2));
                }
            };
        }
    }

    private void panMapToMark(LabeledMinimapMark mark) {
        if (gui == null || gui.mapfile == null) {
            return;
        }
        NMapWnd mapWnd = gui.mapfile;
        if (mapWnd.view == null) {
            return;
        }
        if (!mapWnd.visible()) {
            gui.togglewnd(mapWnd);
        }
        if (gui.mmap == null || gui.mmap.file == null) {
            return;
        }
        try (Locked lk = new Locked(gui.mmap.file.lock.readLock())) {
            MapFile.Segment segment = gui.mmap.file.segments.get(mark.segmentId);
            if (segment == null) {
                gui.msg("That mark is in a different area", java.awt.Color.YELLOW);
                return;
            }
            mapWnd.view.center(new MiniMap.Location(segment, mark.tileCoords));
            mapWnd.view.follow(null);
            gui.msg("Map centered on " + mark.resourceType, java.awt.Color.GREEN);
        }
    }

    @Override
    public void show() {
        // Marks accumulate while the window is closed, so rebuild what is on offer.
        refreshTypeDropdown();
        performSearch();
        super.show();
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
