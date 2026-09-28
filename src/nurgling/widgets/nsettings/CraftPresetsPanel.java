package nurgling.widgets.nsettings;

import haven.*;
import haven.res.lib.itemtex.ItemTex;
import nurgling.areas.NContext;
import nurgling.scenarios.CraftPreset;
import nurgling.scenarios.CraftPresetManager;
import nurgling.tools.VSpec;
import nurgling.widgets.NDropbox;
import nurgling.widgets.Specialisation;
import org.json.JSONObject;

import nurgling.i18n.L10n;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Panel for managing craft presets in the settings window.
 * Allows viewing and deleting saved craft presets.
 */
public class CraftPresetsPanel extends Panel {
    private final int margin = UI.scale(10);
    private Scrollport presetListContainer;
    private Widget presetListContent;
    private final Set<String> expandedPresets = new HashSet<>();

    // Icon cache
    private final Map<String, BufferedImage> iconCache = new HashMap<>();
    private final Set<String> failedIcons = new HashSet<>();

    public CraftPresetsPanel() {
        super("");

        int contentWidth = sz.x - margin * 2;
        int listHeight = UI.scale(420);

        add(new Label(L10n.get("craftpresets.title")), new Coord(margin, margin));
        add(new Label(L10n.get("craftpresets.hint")),
            new Coord(margin, margin + UI.scale(18)));

        int listWidth = contentWidth - margin * 2;

        // Create scrollable container
        presetListContainer = add(new Scrollport(new Coord(listWidth, listHeight)),
            new Coord(margin, margin + UI.scale(45)));
        presetListContent = new Widget(new Coord(listWidth, UI.scale(50))) {
            @Override
            public void pack() {
                resize(contentsz());
            }
        };
        presetListContainer.cont.add(presetListContent, Coord.z);

        rebuildPresetList();
    }

    private void rebuildPresetList() {
        // Clear existing widgets
        for (Widget child : new ArrayList<>(presetListContent.children())) {
            child.destroy();
        }

        List<CraftPreset> presets = CraftPresetManager.getInstance().getPresetList();

        int y = 0;
        int contentWidth = presetListContainer.cont.sz.x;

        for (CraftPreset preset : presets) {
            Widget presetWidget = createPresetWidget(preset, new Coord(contentWidth, UI.scale(32)));
            presetListContent.add(presetWidget, new Coord(0, y));
            y += presetWidget.sz.y + UI.scale(2);
        }

        if (presets.isEmpty()) {
            presetListContent.add(new Label(L10n.get("craftpresets.no_presets")), new Coord(margin, UI.scale(10)));
        }

        presetListContent.pack();
        presetListContainer.cont.update();
    }

    private static final int lineH = UI.scale(18);
    private static final int fallbackRowH = UI.scale(22);
    private static final int fuelRowH = UI.scale(24);

    /** Whether this preset's workstation (captured at save time) is a crucible. */
    private static boolean isCrucible(CraftPreset preset) {
        return NContext.workstation_spec_map.get(preset.getWorkstationType()) == Specialisation.SpecName.crucible;
    }

    private Widget createPresetWidget(CraftPreset preset, Coord sz) {
        boolean isExpanded = expandedPresets.contains(preset.getId());

        // Calculate height
        int baseHeight = UI.scale(32);
        int expandedHeight = 0;
        if (isExpanded) {
            expandedHeight += UI.scale(5);
            for (CraftPreset.InputSpec input : preset.getInputs()) {
                expandedHeight += lineH;
                if (input.getEffectiveName() != null) {
                    expandedHeight += input.getFallbackPresetIds().size() * fallbackRowH;
                    expandedHeight += fallbackRowH;
                }
            }
            if (isCrucible(preset)) {
                expandedHeight += fuelRowH;
            }
            expandedHeight += lineH; // outputs summary
            expandedHeight += UI.scale(5);
        }
        int totalHeight = baseHeight + expandedHeight;

        Widget w = new Widget(new Coord(sz.x, totalHeight));

        // Column positions
        int expandBtnX = margin;
        int expandBtnW = UI.scale(30);
        int iconX = expandBtnX + expandBtnW + UI.scale(5);
        int iconW = UI.scale(24);
        int nameX = iconX + iconW + UI.scale(8);
        int btnW = UI.scale(60);
        int delBtnX = sz.x - margin - btnW;

        // Main row background
        w.add(new Widget(new Coord(sz.x - margin * 2, baseHeight)) {
            public void draw(GOut g) {
                g.chcolor(255, 255, 255, 12);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
        }, new Coord(margin, 0));

        // Expand/collapse button
        String expandBtnText = isExpanded ? "▼" : "▶";
        Button expandBtn = new Button(expandBtnW, expandBtnText, () -> {
            if (expandedPresets.contains(preset.getId())) {
                expandedPresets.remove(preset.getId());
            } else {
                expandedPresets.add(preset.getId());
            }
            rebuildPresetList();
        });
        w.add(expandBtn, new Coord(expandBtnX, (baseHeight - expandBtn.sz.y) / 2));

        // Item icon (use first output item)
        String iconName = null;
        String iconResPath = null;
        if (!preset.getOutputs().isEmpty()) {
            iconName = preset.getOutputs().get(0).getEffectiveName();
            iconResPath = preset.getOutputs().get(0).getResourcePath();
        } else if (!preset.getInputs().isEmpty()) {
            iconName = preset.getInputs().get(0).getPreferredIngredient() != null
                ? preset.getInputs().get(0).getPreferredIngredient()
                : preset.getInputs().get(0).getName();
            iconResPath = preset.getInputs().get(0).getResourcePath();
        }
        if (iconName != null || iconResPath != null) {
            BufferedImage icon = getItemIcon(iconName, iconResPath);
            if (icon != null) {
                final TexI iconTex = new TexI(icon);
                w.add(new Widget(new Coord(UI.scale(20), UI.scale(20))) {
                    public void draw(GOut g) {
                        g.image(iconTex, Coord.z, new Coord(UI.scale(20), UI.scale(20)));
                    }
                }, new Coord(iconX, (baseHeight - UI.scale(20)) / 2));
            }
        }

        // Preset name
        Label nameLabel = new Label(preset.getName());
        w.add(nameLabel, new Coord(nameX, (baseHeight - nameLabel.sz.y) / 2));

        // Recipe name (shorter, to the right of preset name)
        String recipeName = preset.getRecipeName() != null ? "(" + preset.getRecipeName() + ")" : "";
        if (!recipeName.isEmpty()) {
            Label recipeLabel = new Label(recipeName);
            w.add(recipeLabel, new Coord(nameX + nameLabel.sz.x + UI.scale(10), (baseHeight - recipeLabel.sz.y) / 2));
        }

        // Delete button
        w.add(new Button(btnW, L10n.get("craftpresets.btn_delete"), () -> {
            CraftPresetManager.getInstance().deletePreset(preset.getId());
            expandedPresets.remove(preset.getId());
            rebuildPresetList();
        }), new Coord(delBtnX, (baseHeight - UI.scale(24)) / 2));

        // Expanded details: one row per input (name + count), and - for inputs with a
        // resolvable concrete item name - an editable, ordered fallback-recipe list used by
        // ComplexCraftResolver to craft a shortfall when normal sourcing comes up short.
        if (isExpanded) {
            int detailY = baseHeight + UI.scale(5);
            List<CraftPreset> allPresets = CraftPresetManager.getInstance().getPresetList();

            for (CraftPreset.InputSpec input : preset.getInputs()) {
                String label = input.getPreferredIngredient() != null ? input.getPreferredIngredient() : input.getName();
                String line = label + (input.getCount() > 1 ? " x" + input.getCount() : "");
                w.add(new Label(line), new Coord(nameX, detailY));
                detailY += lineH;

                String effectiveName = input.getEffectiveName();
                if (effectiveName != null) {
                    for (String fallbackId : new ArrayList<>(input.getFallbackPresetIds())) {
                        CraftPreset fallback = CraftPresetManager.getInstance().getPreset(fallbackId);
                        String fallbackName = fallback != null ? fallback.getName() : "(missing preset)";
                        w.add(new Label("   " + L10n.get("craftpresets.fallback") + " " + fallbackName),
                            new Coord(nameX + UI.scale(10), detailY));
                        w.add(new Button(UI.scale(24), "x", () -> {
                            input.getFallbackPresetIds().remove(fallbackId);
                            CraftPresetManager.getInstance().addOrUpdatePreset(preset);
                            rebuildPresetList();
                        }), new Coord(sz.x - margin - UI.scale(24), detailY));
                        detailY += fallbackRowH;
                    }

                    List<CraftPreset> candidates = new ArrayList<>();
                    for (CraftPreset candidate : allPresets) {
                        if (!candidate.getId().equals(preset.getId())
                            && !input.getFallbackPresetIds().contains(candidate.getId())) {
                            candidates.add(candidate);
                        }
                    }
                    if (!candidates.isEmpty()) {
                        w.add(new Label(L10n.get("craftpresets.add_fallback")),
                            new Coord(nameX + UI.scale(10), detailY));
                        final List<CraftPreset> finalCandidates = candidates;
                        NDropbox<CraftPreset> addFallback = new NDropbox<CraftPreset>(
                            UI.scale(150), Math.min(finalCandidates.size(), 8), UI.scale(20)
                        ) {
                            @Override
                            protected CraftPreset listitem(int i) { return finalCandidates.get(i); }
                            @Override
                            protected int listitems() { return finalCandidates.size(); }
                            @Override
                            protected void drawitem(GOut g, CraftPreset item, int i) {
                                g.text(item.getName(), Coord.z);
                            }
                            @Override
                            public void change(CraftPreset item) {
                                super.change(item);
                                if (item != null) {
                                    input.getFallbackPresetIds().add(item.getId());
                                    CraftPresetManager.getInstance().addOrUpdatePreset(preset);
                                    rebuildPresetList();
                                }
                            }
                        };
                        w.add(addFallback, new Coord(nameX + UI.scale(90), detailY - UI.scale(2)));
                    } else {
                        w.add(new Label(L10n.get("craftpresets.no_other_presets")),
                            new Coord(nameX + UI.scale(90), detailY));
                    }
                    detailY += fallbackRowH;
                }
            }

            // Crucible fuel preference - only shown for presets whose recipe uses a crucible
            // (captured as workstationType at save time). Coal/Branch toggle: no dedicated
            // radio widget exists in this codebase, so two plain Buttons stand in for one,
            // matching the fallback-recipe editor's own toggle style above.
            if (isCrucible(preset)) {
                boolean isBranch = "Branch".equals(preset.getCrucibleFuel());
                w.add(new Label(L10n.get("craftpresets.crucible_fuel")), new Coord(nameX, detailY));
                int fuelBtnW = UI.scale(70);
                int coalBtnX = nameX + UI.scale(90);
                w.add(new Button(fuelBtnW, isBranch ? "Coal" : "[Coal]", () -> {
                    preset.setCrucibleFuel(null);
                    CraftPresetManager.getInstance().addOrUpdatePreset(preset);
                    rebuildPresetList();
                }), new Coord(coalBtnX, detailY - UI.scale(2)));
                w.add(new Button(fuelBtnW, isBranch ? "[Branch]" : "Branch", () -> {
                    preset.setCrucibleFuel("Branch");
                    CraftPresetManager.getInstance().addOrUpdatePreset(preset);
                    rebuildPresetList();
                }), new Coord(coalBtnX + fuelBtnW + UI.scale(5), detailY - UI.scale(2)));
                detailY += fuelRowH;
            }

            // Outputs
            StringBuilder outputs = new StringBuilder(L10n.get("craftpresets.outputs") + " ");
            for (CraftPreset.OutputSpec output : preset.getOutputs()) {
                if (outputs.length() > 9) outputs.append(", ");
                outputs.append(output.getEffectiveName());
                if (output.getCount() > 1) outputs.append(" x").append(output.getCount());
            }
            String outputsStr = outputs.length() > 70 ? outputs.substring(0, 67) + "..." : outputs.toString();
            w.add(new Label(outputsStr), new Coord(nameX, detailY));
        }

        return w;
    }

    @Override
    public void show() {
        CraftPresetManager.getInstance().loadPresets();
        rebuildPresetList();
        super.show();
    }

    @Override
    public void load() {
        CraftPresetManager.getInstance().loadPresets();
        rebuildPresetList();
    }

    /**
     * Get item icon - first try VSpec categories, then try loading from resource path.
     */
    private BufferedImage getItemIcon(String itemName, String resourcePath) {
        String cacheKey = itemName != null ? itemName : resourcePath;
        if (cacheKey == null) return null;

        if (iconCache.containsKey(cacheKey)) {
            return iconCache.get(cacheKey);
        }

        if (failedIcons.contains(cacheKey)) {
            return null;
        }

        BufferedImage icon;

        // First try: Search VSpec categories for the item
        if (itemName != null) {
            try {
                JSONObject itemRes = null;
                for (Map.Entry<String, ArrayList<JSONObject>> entry : VSpec.categories.entrySet()) {
                    for (JSONObject obj : entry.getValue()) {
                        if (obj.getString("name").equals(itemName)) {
                            itemRes = obj;
                            break;
                        }
                    }
                    if (itemRes != null) break;
                }

                if (itemRes != null) {
                    icon = ItemTex.create(itemRes);
                    if (icon != null) {
                        iconCache.put(cacheKey, icon);
                        return icon;
                    }
                }
            } catch (Exception e) {
                // Continue to next method
            }
        }

        // Second try: Load directly from resource path
        if (resourcePath != null && !resourcePath.isEmpty()) {
            try {
                Resource res = Resource.remote().loadwait(resourcePath);
                if (res != null) {
                    Resource.Image img = res.layer(Resource.imgc);
                    if (img != null) {
                        icon = img.img;
                        if (icon != null) {
                            iconCache.put(cacheKey, icon);
                            return icon;
                        }
                    }
                }
            } catch (Exception e) {
                // Silent failure
            }
        }

        failedIcons.add(cacheKey);
        return null;
    }
}
