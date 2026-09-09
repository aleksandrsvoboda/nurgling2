package nurgling.widgets;

import haven.*;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.tools.ForageTerrain;
import nurgling.tools.RockResourceMapper;

import java.awt.Color;
import java.util.*;

/**
 * Terrain / ore tile-highlight search. Lives as a tab inside {@link MapToolsWindow}.
 */
public class TerrainSearchPanel extends Widget {
    private static final int PANEL_WIDTH = UI.scale(450);
    private static final int PANEL_HEIGHT = UI.scale(500);
    
    private TextEntry terrainSearchField;
    private CategoryList categoryList;
    private PresetList presetList;
    private TerrainCategory selectedCategory = null;
    
    public TerrainSearchPanel() {
        super(new Coord(PANEL_WIDTH, PANEL_HEIGHT));

        int y = 0;
        int margin = UI.scale(5);
        
        // Terrain search label and field
        add(new Label(L10n.get("terrain.search")), margin, y);
        y += UI.scale(20);
        terrainSearchField = add(new TextEntry(PANEL_WIDTH - margin * 2, "") {
            @Override
            public void changed() {
                super.changed();
                applyTerrainSearch();
            }
            
            @Override
            public boolean keydown(KeyDownEvent ev) {
                if(ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                    applyTerrainSearch();
                    return true;
                }
                return super.keydown(ev);
            }
        }, margin, y);
        y += terrainSearchField.sz.y + UI.scale(10);
        
        // Categories label
        add(new Label(L10n.get("terrain.categories")), margin, y);
        y += UI.scale(20);
        
        // Create split view: categories on left, presets on right
        int listWidth = (PANEL_WIDTH - margin * 3) / 2;
        int itemHeight = UI.scale(20);
        int visibleItems = 15;  // Number of visible items in list
        int listHeight = visibleItems * itemHeight;
        
        categoryList = add(new CategoryList(new Coord(listWidth, listHeight), itemHeight), margin, y);
        presetList = add(new PresetList(new Coord(listWidth, listHeight), itemHeight), margin * 2 + listWidth, y);
        
        y += listHeight + UI.scale(10);
        
        // Select All button for current category
        add(new Button(UI.scale(90), L10n.get("terrain.select_all")) {
            @Override
            public void click() {
                selectAllInCategory();
            }
        }, margin, y);
        
        // Clear search button
        add(new Button(UI.scale(80), L10n.get("terrain.clear")) {
            @Override
            public void click() {
                clearSearch();
            }
        }, PANEL_WIDTH - UI.scale(90), y);
        
        pack();
    }
    
    private void applyTerrainSearch() {
        String pattern = terrainSearchField.text().trim();
        
        // Parse patterns separated by |
        java.util.Set<String> tilesToHighlight = new java.util.HashSet<>();
        if(!pattern.isEmpty()) {
            String[] patterns = pattern.split("\\|");
            for(String p : patterns) {
                String trimmed = p.trim();
                if(!trimmed.isEmpty()) {
                    // Find matching tile resource names
                    tilesToHighlight.addAll(findMatchingTiles(trimmed));
                }
            }
        }
        
        // Update TileHighlight
        TileHighlight.setHighlighted(tilesToHighlight);
        
        // Force map redraw
        if(NUtils.getGameUI() != null) {
            if(NUtils.getGameUI().mmap instanceof NMiniMap) {
                ((NMiniMap)NUtils.getGameUI().mmap).invalidateDisplayCache();
            }
        }
    }

    /** Replace the current map highlight with the terrain used by one forageable. */
    public void selectTerrains(Collection<String> terrains) {
        if(terrains == null || terrains.isEmpty())
            return;
        Set<String> selected = new HashSet<>();
        for(String terrain : terrains)
            selected.add(terrain.toLowerCase(Locale.ROOT));
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        for(TerrainCategory category : TerrainCategory.ALL_CATEGORIES) {
            for(TerrainPreset preset : category.presets) {
                preset.enabled = selected.contains(preset.displayName.toLowerCase(Locale.ROOT));
                if(preset.enabled)
                    patterns.add(preset.searchPattern);
            }
        }
        terrainSearchField.settext(String.join("|", patterns));
        applyTerrainHighlight(terrains);
        invalidateMap();
        if(selectedCategory != null)
            presetList.updatePresets(selectedCategory);
    }

    static void applyTerrainHighlight(Collection<String> terrains) {
        TileHighlight.setHighlighted(ForageTerrain.resourceNames(terrains));
    }

    /** Replace the highlight with already resolved, exact tile resource paths. */
    public void selectResources(Collection<String> resources) {
        if(resources == null || resources.isEmpty())
            return;
        terrainSearchField.settext("");
        applyResourceHighlight(resources);
        invalidateMap();
    }

    static void applyResourceHighlight(Collection<String> resources) {
        TileHighlight.setHighlighted(new LinkedHashSet<>(resources));
    }

    private static void invalidateMap() {
        if(NUtils.getGameUI() != null && NUtils.getGameUI().mmap instanceof NMiniMap)
            ((NMiniMap)NUtils.getGameUI().mmap).invalidateDisplayCache();
    }
    
    /**
     * Find all tile resource names that match the search pattern
     */
    private java.util.Set<String> findMatchingTiles(String pattern) {
        return resourceNamesForPattern(pattern);
    }

    static java.util.Set<String> resourceNamesForPattern(String pattern) {
        java.util.Set<String> result = new java.util.HashSet<>();
        String lowerPattern = pattern.toLowerCase();

        // Prefer an exact preset so "grass" does not also enable the Grassland group.
        for(TerrainCategory cat : TerrainCategory.ALL_CATEGORIES) {
            for(TerrainPreset preset : cat.presets) {
                if(preset.searchPattern.toLowerCase().equals(lowerPattern))
                    result.addAll(presetToResourceNames(preset.searchPattern));
            }
        }
        if(!result.isEmpty())
            return result;
        for(TerrainCategory cat : TerrainCategory.ALL_CATEGORIES) {
            for(TerrainPreset preset : cat.presets) {
                if(preset.searchPattern.toLowerCase().contains(lowerPattern))
                    result.addAll(presetToResourceNames(preset.searchPattern));
            }
        }
        return result;
    }
    
    /**
     * Convert preset search pattern to full tile resource names
     */
    private static java.util.Set<String> presetToResourceNames(String searchPattern) {
        java.util.Set<String> result = new java.util.HashSet<>();
        String lower = searchPattern.toLowerCase();

        if(ForageTerrain.known(lower)) {
            result.addAll(ForageTerrain.resourceNames(lower));
            return result;
        }
        
        Set<String> rocks = RockResourceMapper.getTileResourcesForItem(lower);
        if(!rocks.isEmpty()) {
            result.addAll(rocks);
            return result;
        }

        // Try as a generic ground tile.
        result.add("gfx/tiles/" + lower);
        
        return result;
    }
    
    private void clearSearch() {
        // Clear all enabled presets
        for(TerrainCategory cat : TerrainCategory.ALL_CATEGORIES) {
            for(TerrainPreset preset : cat.presets) {
                preset.enabled = false;
            }
        }
        // Clear search field
        terrainSearchField.settext("");
        // Clear TileHighlight
        TileHighlight.clear();
        // Force map redraw
        if(NUtils.getGameUI() != null) {
            if(NUtils.getGameUI().mmap instanceof NMiniMap) {
                ((NMiniMap)NUtils.getGameUI().mmap).invalidateDisplayCache();
            }
        }
        // Refresh preset list if visible
        if(selectedCategory != null) {
            presetList.updatePresets(selectedCategory);
        }
    }

    private void selectAllInCategory() {
        if(selectedCategory != null) {
            for(TerrainPreset preset : selectedCategory.presets) {
                preset.enabled = true;
            }
            presetList.applyPresetSearch();
        }
    }
    
    // Category list widget with cached rendering
    private class CategoryList extends SListBox<TerrainCategory, Widget> {
        public CategoryList(Coord sz, int itemh) {
            super(sz, itemh);
        }
        
        @Override
        protected List<TerrainCategory> items() {
            return TerrainCategory.ALL_CATEGORIES;
        }
        
        @Override
        protected Widget makeitem(TerrainCategory cat, int idx, Coord sz) {
            return new ItemWidget<TerrainCategory>(this, sz, cat) {
                private final Text.Line text = Text.render(cat.name);
                
                @Override
                public void draw(GOut g) {
                    g.image(text.tex(), new Coord(UI.scale(5), (sz.y - text.sz().y) / 2));
                }
                
                @Override
                public boolean mousedown(MouseDownEvent ev) {
                    if(ev.b == 1) {
                        CategoryList.this.change(cat);
                        return true;
                    }
                    return super.mousedown(ev);
                }
            };
        }
        
        public void change(TerrainCategory item) {
            super.change(item);
            selectedCategory = item;
            presetList.updatePresets(item);
        }
    }
    
    // Preset list widget with cached rendering
    private class PresetList extends SListBox<TerrainPreset, Widget> {
        private List<TerrainPreset> currentPresets = new ArrayList<>();
        
        public PresetList(Coord sz, int itemh) {
            super(sz, itemh);
        }
        
        public void updatePresets(TerrainCategory category) {
            currentPresets = category != null ? category.presets : new ArrayList<>();
            reset();
            sel = null;
        }
        
        @Override
        protected List<TerrainPreset> items() {
            return currentPresets;
        }
        
        @Override
        protected Widget makeitem(TerrainPreset preset, int idx, Coord sz) {
            return new ItemWidget<TerrainPreset>(this, sz, preset) {
                private final Text.Line text = Text.render(preset.displayName);
                private final Tex checkboxBorder;
                private final Coord checkboxSize = new Coord(UI.scale(14), UI.scale(14));
                private final Coord checkboxPos = new Coord(UI.scale(3), UI.scale(3));
                
                // Cache checkbox border texture
                {
                    java.awt.image.BufferedImage borderImg = TexI.mkbuf(checkboxSize);
                    java.awt.Graphics2D graphics = borderImg.createGraphics();
                    graphics.setColor(Color.BLACK);
                    graphics.drawRect(0, 0, checkboxSize.x - 1, checkboxSize.y - 1);
                    graphics.dispose();
                    checkboxBorder = new TexI(borderImg);
                }
                
                @Override
                public void draw(GOut g) {
                    // Draw checkbox fill
                    g.chcolor(preset.enabled ? Color.GREEN : Color.GRAY);
                    g.frect(checkboxPos, checkboxSize);
                    g.chcolor();
                    
                    // Draw checkbox border (cached)
                    g.image(checkboxBorder, checkboxPos);
                    
                    // Draw name (cached texture)
                    g.image(text.tex(), new Coord(UI.scale(22), (sz.y - text.sz().y) / 2));
                }
                
                @Override
                public boolean mousedown(MouseDownEvent ev) {
                    if(ev.b == 1) {
                        preset.enabled = !preset.enabled;
                        applyPresetSearch();
                        return true;
                    }
                    return super.mousedown(ev);
                }
            };
        }
        
        private void applyPresetSearch() {
            // Collect all enabled patterns (search patterns, not resource names)
            java.util.Set<String> enabledPatterns = new java.util.LinkedHashSet<>();
            for(TerrainCategory cat : TerrainCategory.ALL_CATEGORIES) {
                for(TerrainPreset preset : cat.presets) {
                    if(preset.enabled) {
                        enabledPatterns.add(preset.searchPattern);
                    }
                }
            }
            
            // Update text field with unique patterns only
            terrainSearchField.settext(String.join("|", enabledPatterns));
            
            // Apply the search
            applyTerrainSearch();
        }
    }
    
    // Terrain category definition
    public static class TerrainCategory {
        public final String name;
        public final List<TerrainPreset> presets;
        
        public TerrainCategory(String name, TerrainPreset... presets) {
            this.name = name;
            this.presets = Arrays.asList(presets);
        }
        
        // All available categories
        public static final List<TerrainCategory> ALL_CATEGORIES = createCategories();
        
        private static List<TerrainCategory> createCategories() {
            // Define all individual categories first
            TerrainCategory natural = new TerrainCategory("Natural",
                new TerrainPreset("Forest", "forest"),
                new TerrainPreset("Grassland", "grassland"),
                new TerrainPreset("Water Terrain", "waterterrain"),
                new TerrainPreset("Shallow Water", "shallowwater"),
                new TerrainPreset("Grass", "grass"),
                new TerrainPreset("Beach", "beach"),
                new TerrainPreset("Acre Clay Field", "acreclayfield"),
                new TerrainPreset("Badlands", "badlands"),
                new TerrainPreset("Beech Grove", "beechgrove"),
                new TerrainPreset("Black Wood", "blackwood"),
                new TerrainPreset("Blue Sod", "bluesod"),
                new TerrainPreset("Bog", "bog"),
                new TerrainPreset("Bounty Acre", "bountyacre"),
                new TerrainPreset("Cloud Range", "cloudrange"),
                new TerrainPreset("Deep Tangle", "deeptangle"),
                new TerrainPreset("Dry Flat", "dryflat"),
                new TerrainPreset("Dry Weald", "dryweald"),
                new TerrainPreset("Fen", "fen"),
                new TerrainPreset("Flower Meadow", "flowermeadow"),
                new TerrainPreset("Green Brake", "greenbrake"),
                new TerrainPreset("Greens Ward", "greensward"),
                new TerrainPreset("Grove", "grove"),
                new TerrainPreset("Hard Steppe", "hardsteppe"),
                new TerrainPreset("Heath", "heath"),
                new TerrainPreset("Highground", "highground"),
                new TerrainPreset("Leaf", "leaf"),
                new TerrainPreset("Leaf Patch", "leafpatch"),
                new TerrainPreset("Lichen Wold", "lichenwold"),
                new TerrainPreset("Lush Field", "lushfield"),
                new TerrainPreset("Moor", "moor"),
                new TerrainPreset("Moss Brush", "mossbrush"),
                new TerrainPreset("Mountain", "mountain"),
                new TerrainPreset("Oak Wilds", "oakwilds"),
                new TerrainPreset("Ox Pasture", "oxpasture"),
                new TerrainPreset("Peat Moss", "peatmoss"),
                new TerrainPreset("Pine Barren", "pinebarren"),
                new TerrainPreset("Red Plain", "redplain"),
                new TerrainPreset("Root Bosk", "rootbosk"),
                new TerrainPreset("Scrub Veld", "scrubveld"),
                new TerrainPreset("Shady Copse", "shadycopse"),
                new TerrainPreset("Sand Cliff", "sandcliff"),
                new TerrainPreset("Skargard", "skargard"),
                new TerrainPreset("Sombre Bramble", "sombrebramble"),
                new TerrainPreset("Sour Timber", "sourtimber"),
                new TerrainPreset("Swamp", "swamp"),
                new TerrainPreset("Tidepool", "tidepool"),
                new TerrainPreset("Timber Land", "timberland"),
                new TerrainPreset("Wald", "wald"),
                new TerrainPreset("Wild Moor", "wildmoor"),
                new TerrainPreset("Wild Turf", "wildturf")
            );
            
            TerrainCategory ore = new TerrainCategory("Ore",
                new TerrainPreset("Cassiterite", "cassiterite"),
                new TerrainPreset("Chalcopyrite", "chalcopyrite"),
                new TerrainPreset("Malachite", "malachite"),
                new TerrainPreset("Heavy Earth (Ilmenite)", "heavyearth"),
                new TerrainPreset("Iron Ochre (Limonite)", "ironochre"),
                new TerrainPreset("Bloodstone (Hematite)", "bloodstone"),
                new TerrainPreset("Black Ore (Magnetite)", "blackore"),
                new TerrainPreset("Cinnabar", "cinnabar"),
                new TerrainPreset("Galena", "galena"),
                new TerrainPreset("Silvershine (Argentite)", "silvershine"),
                new TerrainPreset("Horn Silver", "hornsilver"),
                new TerrainPreset("Wine Glance (Cuprite)", "wineglance"),
                new TerrainPreset("Lead Glance", "leadglance"),
                new TerrainPreset("Leaf Ore", "leafore"),
                new TerrainPreset("Schrifterz", "schrifterz"),
                new TerrainPreset("Direvein", "direvein"),
                new TerrainPreset("Black Coal", "blackcoal")
            );
            
            TerrainCategory rocks = new TerrainCategory("Rocks",
                new TerrainPreset("Alabaster", "alabaster"),
                new TerrainPreset("Apatite", "apatite"),
                new TerrainPreset("Arkose", "arkose"),
                new TerrainPreset("Basalt", "basalt"),
                new TerrainPreset("Breccia", "breccia"),
                new TerrainPreset("Chert", "chert"),
                new TerrainPreset("Diabase", "diabase"),
                new TerrainPreset("Diorite", "diorite"),
                new TerrainPreset("Dolomite", "dolomite"),
                new TerrainPreset("Eclogite", "eclogite"),
                new TerrainPreset("Feldspar", "feldspar"),
                new TerrainPreset("Flint", "flint"),
                new TerrainPreset("Fluorospar", "fluorospar"),
                new TerrainPreset("Gabbro", "gabbro"),
                new TerrainPreset("Gneiss", "gneiss"),
                new TerrainPreset("Granite", "granite"),
                new TerrainPreset("Graywacke", "graywacke"),
                new TerrainPreset("Greenschist", "greenschist"),
                new TerrainPreset("Hornblende", "hornblende"),
                new TerrainPreset("Jasper", "jasper"),
                new TerrainPreset("Korund", "korund"),
                new TerrainPreset("Kyanite", "kyanite"),
                new TerrainPreset("Limestone", "limestone"),
                new TerrainPreset("Marble", "marble"),
                new TerrainPreset("Mica", "mica"),
                new TerrainPreset("Microlite", "microlite"),
                new TerrainPreset("Olivine", "olivine"),
                new TerrainPreset("Orthoclase", "orthoclase"),
                new TerrainPreset("Pegmatite", "pegmatite"),
                new TerrainPreset("Porphyry", "porphyry"),
                new TerrainPreset("Pumice", "pumice"),
                new TerrainPreset("Quarryartz", "quarryartz"),
                new TerrainPreset("Quartz", "quartz"),
                new TerrainPreset("Rhyolite", "rhyolite"),
                new TerrainPreset("Rock Salt", "rocksalt"),
                new TerrainPreset("Sandstone", "sandstone"),
                new TerrainPreset("Schist", "schist"),
                new TerrainPreset("Serpentine", "serpentine"),
                new TerrainPreset("Slate", "slate"),
                new TerrainPreset("Soapstone", "soapstone"),
                new TerrainPreset("Sodalite", "sodalite"),
                new TerrainPreset("Sunstone", "sunstone"),
                new TerrainPreset("Zincspar", "zincspar")
            );
            
            TerrainCategory paving = new TerrainCategory("Paving",
                new TerrainPreset("Teal Brick", "brick"),
                new TerrainPreset("Red Brick", "brickred"),
                new TerrainPreset("Yellow Brick", "brickyellow"),
                new TerrainPreset("Green Brick", "brickgreen"),
                new TerrainPreset("Gray Brick", "brickblack"),
                new TerrainPreset("Pink Brick", "brickpink"),
                new TerrainPreset("White Brick", "brickwhite"),
                new TerrainPreset("Blue Brick", "brickblue"),
                new TerrainPreset("Coade Stone Brick", "brickcoade"),
                new TerrainPreset("Bronze Metal", "metalbronze"),
                new TerrainPreset("Cast Iron Metal", "metalcastiron"),
                new TerrainPreset("Copper Metal", "metalcopper"),
                new TerrainPreset("Gold Metal", "metalgold"),
                new TerrainPreset("Lead Metal", "metallead"),
                new TerrainPreset("Meteoric Iron Metal", "metalmetiron"),
                new TerrainPreset("Rose Gold Metal", "metalrosegold"),
                new TerrainPreset("Silver Metal", "metalsilver"),
                new TerrainPreset("Steel Metal", "metalsteel"),
                new TerrainPreset("Tin Metal", "metaltin"),
                new TerrainPreset("Wrought Iron Metal", "metalwroughtiron"),
                new TerrainPreset("Cobblestone", "cobble"),
                new TerrainPreset("Flagstone", "flagstone"),
                new TerrainPreset("Wooden", "plank"),
                new TerrainPreset("Dirt", "dirt"),
                new TerrainPreset("Plowed Dirt", "ploweddirt")
            );
            
            TerrainCategory water = new TerrainCategory("Water",
                new TerrainPreset("Shallow Water", "shallowwater"),
                new TerrainPreset("Deep Water", "deepwater"),
                new TerrainPreset("Shallow Ocean", "shallowocean"),
                new TerrainPreset("Deep Ocean", "deepocean"),
                new TerrainPreset("High Seas", "highseas"),
                new TerrainPreset("Rock Beach", "rockbeach"),
                new TerrainPreset("Sand Cliff", "sandcliff")
            );
            
            TerrainCategory specialStones = new TerrainCategory("Special Stones",
                new TerrainPreset("Bat Rock", "batrock"),
                new TerrainPreset("Cat Gold", "catgold"),
                new TerrainPreset("Dross", "dross"),
                new TerrainPreset("Lava Rock", "lavarock"),
                new TerrainPreset("Obsidian", "obsidian"),
                new TerrainPreset("Meteorite", "meteorite"),
                new TerrainPreset("Rock Crystal", "rockcrystal"),
                new TerrainPreset("Conchite", "conchite"),
                new TerrainPreset("Slag", "slag")
            );
            
            TerrainCategory mountain = new TerrainCategory("Mountain & Cave",
                new TerrainPreset("Mountain", "mountain"),
                new TerrainPreset("Mountain Snow", "mountainsnow"),
                new TerrainPreset("Cave", "cave"),
                new TerrainPreset("Wild Cavern", "wildcavern"),
                new TerrainPreset("Lava", "lava"),
                new TerrainPreset("Ashland", "ashland")
            );
            
            // Collect all presets from all categories for "All" category
            List<TerrainPreset> allPresets = new ArrayList<>();
            allPresets.addAll(natural.presets);
            allPresets.addAll(ore.presets);
            allPresets.addAll(rocks.presets);
            allPresets.addAll(paving.presets);
            allPresets.addAll(water.presets);
            allPresets.addAll(specialStones.presets);
            allPresets.addAll(mountain.presets);
            
            TerrainCategory all = new TerrainCategory("All", allPresets.toArray(new TerrainPreset[0]));
            
            return Arrays.asList(
                all,
                natural,
                ore,
                rocks,
                paving,
                water,
                specialStones,
                mountain
            );
        }
    }
    
    // Terrain preset definition
    public static class TerrainPreset {
        public final String displayName;
        public final String searchPattern;
        public boolean enabled = false;
        
        public TerrainPreset(String displayName, String searchPattern) {
            this.displayName = displayName;
            this.searchPattern = searchPattern;
        }
    }
}
