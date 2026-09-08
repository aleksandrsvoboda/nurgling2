package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NConfig;
import nurgling.NFlowerMenu;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.actions.bots.MasterMiner;
import nurgling.conf.NMasterMinerMarkingConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MiningMasterySettings extends Panel {
    private static class ItemCheckbox extends Widget {
        private final CheckBox checkbox;
        private final Label nameLabel;
        private final Label thresholdLabel;
        private final String itemName;
        private double threshold;
        private final Runnable onThresholdChange;
        /** Clicking right of this column edits the threshold rather than the checkbox. */
        private static final int THRESHOLD_AREA_X = UI.scale(200);

        public ItemCheckbox(String itemName, double defaultThreshold, Runnable onThresholdChange) {
            super(Coord.z);
            this.itemName = itemName;
            this.threshold = defaultThreshold;
            this.onThresholdChange = onThresholdChange;

            checkbox = add(new CheckBox("") {
                @Override
                public void set(boolean val) {
                    boolean oldVal = a;
                    a = val;
                    // Persist as soon as the box is ticked.
                    if (oldVal != val && onThresholdChange != null) {
                        onThresholdChange.run();
                    }
                }
            }, new Coord(UI.scale(5), 0));

            nameLabel = add(new Label(itemName), new Coord(UI.scale(25), 0));
            
            // The threshold column shows the bare number.
            String thresholdText = String.format("%.0f", threshold);
            thresholdLabel = add(new Label(thresholdText), new Coord(THRESHOLD_AREA_X, 0));
            
            resize(UI.scale(400), UI.scale(20));
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1 && checkhit(ev.c)) {
                // A left click on the threshold column opens the editor.
                if (ev.c.x >= THRESHOLD_AREA_X) {
                    editThreshold();
                    return true;
                }
            }
            return super.mousedown(ev);
        }

        private void editThreshold() {
            if (ui == null) return;
            Window thresholdWnd = new Window(UI.scale(300, 120), "Edit Threshold") {
                private TextEntry thresholdEntry;

                {
                    add(new Label("Enter quality threshold:"), new Coord(UI.scale(10), UI.scale(30)));
                    thresholdEntry = add(new TextEntry(UI.scale(100), String.valueOf((int)threshold)), 
                        new Coord(UI.scale(10), UI.scale(50)));
                    
                    add(new Button(UI.scale(80), "OK") {
                        @Override
                        public void click() {
                            try {
                                double newThreshold = Double.parseDouble(thresholdEntry.buf.line());
                                threshold = newThreshold;
                                thresholdLabel.settext(String.format("%.0f", threshold));
                                if (onThresholdChange != null) {
                                    onThresholdChange.run();
                                }
                            } catch (NumberFormatException e) {
                                // Ignore invalid input
                            }
                            parent.destroy();
                        }
                    }, new Coord(UI.scale(10), UI.scale(80)));
                    
                    add(new Button(UI.scale(80), "Cancel") {
                        @Override
                        public void click() {
                            parent.destroy();
                        }
                    }, new Coord(UI.scale(100), UI.scale(80)));
                }
            };
            ui.root.add(thresholdWnd, UI.scale(200, 200));
        }

        public boolean isEnabled() {
            return checkbox.a;
        }

        public void setEnabled(boolean enabled) {
            checkbox.a = enabled;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
            thresholdLabel.settext(String.format("%.0f", threshold));
        }

        public String getItemName() {
            return itemName;
        }
    }

    /** Quality a find must reach before it is worth a map mark. */
    static final double DEFAULT_THRESHOLD = 10.0;

    private final Map<String, ItemCheckbox> checkboxes = new HashMap<>();
    private final List<ItemCheckbox> checkboxOrder = new ArrayList<>();
    private Scrollport scrollport;
    private boolean initialized = false;

    public MiningMasterySettings() {
        super();
        // Building the list is deferred to the first load()/show().
    }
    
    private void initializeIfNeeded() {
        if (initialized) return;
        initialized = true;
        
        int margin = UI.scale(10);
        int y = UI.scale(36);

        add(new Label("Select which items to mark on map when mining:"), new Coord(margin, y));
        
        // Bulk-threshold buttons sit to the right of the heading.
        int labelWidth = UI.scale(330); // Keeps both action buttons inside the standard page width.
        int buttonX = margin + labelWidth + UI.scale(20);
        int buttonY = y;
        
        // Sets the threshold for every plain stone, leaving ores and gemstones alone.
        add(new Button(UI.scale(150), "Threshold for all stones") {
            @Override
            public void click() {
                showThresholdForAllDialog();
            }
        }, new Coord(buttonX, buttonY));
        buttonY += UI.scale(30);
        
        // Sets the threshold for every ore.
        add(new Button(UI.scale(150), "Threshold for all ores") {
            @Override
            public void click() {
                showThresholdForAllOresDialog();
            }
        }, new Coord(buttonX, buttonY));
        
        y += UI.scale(32);
        add(new Label("Click on right side of item to edit quality threshold"), new Coord(margin, y));
        y += UI.scale(25);

        // Pre-sorted so the list never has to be sorted on open.
        List<String> allItems = Arrays.asList(
            "Alabaster", "Apatite", "Arkose", "Basalt", "Bat Rock",
            "Black Coal", "Black Ore", "Bloodstone", "Breccia", "Cassiterite",
            "Cat Gold", "Chalcopyrite", "Chert", "Cinnabar", "Diabase",
            "Diorite", "Direvein", "Dolomite", "Dross", "Eclogite",
            "Feldspar", "Flint", "Fluorospar", "Gabbro", "Galena",
            "Gneiss", "Granite", "Graywacke", "Greenschist", "Heavy Earth",
            "Horn Silver", "Hornblende", "Iron Ochre", "Jasper", "Korund",
            "Kyanite", "Lava Rock", "Lead Glance", "Leaf Ore", "Limestone",
            "Malachite", "Marble", "Meteorite", "Mica", "Microlite",
            "Obsidian", "Olivine", "Orthoclase", "Peacock Ore", "Pegmatite",
            "Petrified Seashell", "Petrified Shell", "Porphyry", "Pumice",
            "Quarryartz", "Quartz", "Rhyolite", "Rock Crystal", "Rock Salt",
            "Sandstone", "Schist", "Schrifterz", "Serpentine", "Shard of Conch",
            "Silvershine", "Slag", "Slate", "Soapstone", "Sodalite",
            "Sunstone", "Wine Glance", "Zincspar",
            // Gemstones
            "Amber", "Amethyst", "Diamond", "Dust Jewel", "Emerald", "Jade",
            "Moonstone", "Onyx", "Opal", "Oyster Pearl", "Red Coral", "River Pearl",
            "Ruby", "Sapphire", "Star Shard", "Sugar Diamond", "Topaz", "Turquoise"
        );

        // Wide enough for two columns.
        scrollport = add(new Scrollport(new Coord(UI.scale(560), UI.scale(400))), new Coord(margin, y));

        // Split the list across two columns.
        int columnWidth = UI.scale(270);
        int itemHeight = UI.scale(22);
        int itemsPerColumn = (allItems.size() + 1) / 2; // Rounded up.
        
        int itemY = 0;
        int columnIndex = 0;
        int maxY = 0;
        for (String itemName : allItems) {
            ItemCheckbox itemCheckbox = new ItemCheckbox(itemName, 10.0, this::save);
            int columnX = columnIndex * columnWidth;
            scrollport.cont.add(itemCheckbox, new Coord(columnX, itemY));
            checkboxes.put(itemName, itemCheckbox);
            checkboxOrder.add(itemCheckbox);
            
            maxY = Math.max(maxY, itemY + itemHeight);
            
            itemY += itemHeight;
            
            // Halfway down: start the second column.
            if (itemY >= itemsPerColumn * itemHeight) {
                itemY = 0;
                columnIndex = 1;
            }
        }
        
        scrollport.cont.resize(columnWidth * 2, maxY);
    }

    @Override
    public void load() {
        initializeIfNeeded();
        NMasterMinerMarkingConfig config = NMasterMinerMarkingConfig.get();
        if (config == null) {
            // Start a fresh config if this character has none.
            if (NUtils.getGameUI() != null && NUtils.getGameUI().getCharInfo() != null) {
                NUI.NSessInfo sessInfo = ((NUI)NUtils.getGameUI().ui).sessInfo;
                if (sessInfo != null) {
                    config = new NMasterMinerMarkingConfig(sessInfo.username, NUtils.getGameUI().getCharInfo().chrid);
                } else {
                    return; // Nothing to load without a session.
                }
            } else {
                return; // Nothing to load without a session.
            }
        }

        for (Map.Entry<String, ItemCheckbox> entry : checkboxes.entrySet()) {
            String itemName = entry.getKey();
            ItemCheckbox checkbox = entry.getValue();
            
            // Matches MasterMiner: everything is marked until the player says otherwise.
            Boolean enabledObj = config.isEnabled(itemName);
            Double threshold = config.getThreshold(itemName);

            checkbox.setEnabled((enabledObj == null) || enabledObj);
            checkbox.setThreshold((threshold == null || threshold.isNaN()) ? DEFAULT_THRESHOLD : threshold);
        }
    }

    @Override
    public void save() {
        if (!initialized) return;
        NMasterMinerMarkingConfig config = NMasterMinerMarkingConfig.get();
        if (config == null) {
            // Start a fresh config if this character has none.
            if (NUtils.getGameUI() != null && NUtils.getGameUI().getCharInfo() != null) {
                NUI.NSessInfo sessInfo = ((NUI)NUtils.getGameUI().ui).sessInfo;
                if (sessInfo != null) {
                    config = new NMasterMinerMarkingConfig(sessInfo.username, NUtils.getGameUI().getCharInfo().chrid);
                } else {
                    return; // Nothing to save without a session.
                }
            } else {
                return; // Nothing to save without a session.
            }
        }

        for (Map.Entry<String, ItemCheckbox> entry : checkboxes.entrySet()) {
            String itemName = entry.getKey();
            ItemCheckbox checkbox = entry.getValue();
            
            config.setEnabled(itemName, checkbox.isEnabled());
            config.setThreshold(itemName, checkbox.getThreshold());
        }
        
        NMasterMinerMarkingConfig.set(config);
        NConfig.needUpdate();
    }
    
    /**
     * Set one threshold across every plain stone, leaving ore and gemstones alone.
     */
    private void showThresholdForAllDialog() {
        if (ui == null) return;
        Window thresholdDialog = new Window(UI.scale(300, 140), "Threshold for all") {
            private TextEntry thresholdEntry;

            {
                add(new Label("Enter quality threshold for all stones"), new Coord(UI.scale(10), UI.scale(30)));
                add(new Label("(except ores and gemstones):"), new Coord(UI.scale(10), UI.scale(50)));
                thresholdEntry = add(new TextEntry(UI.scale(100), "10"), 
                    new Coord(UI.scale(10), UI.scale(70)));
                
                add(new Button(UI.scale(80), "OK") {
                    @Override
                    public void click() {
                        try {
                            double threshold = Double.parseDouble(thresholdEntry.buf.line());
                            for (Map.Entry<String, ItemCheckbox> entry : checkboxes.entrySet()) {
                                String itemName = entry.getKey();
                                ItemCheckbox checkbox = entry.getValue();
                                
                                boolean isOre = MasterMiner.isOre(itemName) || 
                                               itemName.equals("Black Coal") || 
                                               itemName.equals("Quartz") || 
                                               itemName.equals("Flint");
                                boolean isGemstone = MasterMiner.isGemstone(itemName);
                                
                                if (!isOre && !isGemstone) {
                                    checkbox.setThreshold(threshold);
                                }
                            }
                            save();
                            parent.destroy();
                        } catch (NumberFormatException e) {
                            // Ignore invalid input
                        }
                    }
                }, new Coord(UI.scale(10), UI.scale(100)));
                
                add(new Button(UI.scale(80), "Cancel") {
                    @Override
                    public void click() {
                        parent.destroy();
                    }
                }, new Coord(UI.scale(100), UI.scale(100)));
            }
        };
        ui.root.add(thresholdDialog, UI.scale(200, 200));
    }
    
    /**
     * Set one threshold across every ore.
     */
    private void showThresholdForAllOresDialog() {
        if (ui == null) return;
        Window thresholdDialog = new Window(UI.scale(300, 140), "Threshold for all ores") {
            private TextEntry thresholdEntry;

            {
                add(new Label("Enter quality threshold for all ores:"), new Coord(UI.scale(10), UI.scale(30)));
                thresholdEntry = add(new TextEntry(UI.scale(100), "10"), 
                    new Coord(UI.scale(10), UI.scale(70)));
                
                add(new Button(UI.scale(80), "OK") {
                    @Override
                    public void click() {
                        try {
                            double threshold = Double.parseDouble(thresholdEntry.buf.line());
                            for (Map.Entry<String, ItemCheckbox> entry : checkboxes.entrySet()) {
                                String itemName = entry.getKey();
                                ItemCheckbox checkbox = entry.getValue();
                                
                                boolean isOre = MasterMiner.isOre(itemName) || 
                                               itemName.equals("Black Coal") || 
                                               itemName.equals("Quartz") || 
                                               itemName.equals("Flint");
                                
                                if (isOre) {
                                    checkbox.setThreshold(threshold);
                                }
                            }
                            save();
                            parent.destroy();
                        } catch (NumberFormatException e) {
                            // Ignore invalid input
                        }
                    }
                }, new Coord(UI.scale(10), UI.scale(100)));
                
                add(new Button(UI.scale(80), "Cancel") {
                    @Override
                    public void click() {
                        parent.destroy();
                    }
                }, new Coord(UI.scale(100), UI.scale(100)));
            }
        };
        ui.root.add(thresholdDialog, UI.scale(200, 200));
    }
}
