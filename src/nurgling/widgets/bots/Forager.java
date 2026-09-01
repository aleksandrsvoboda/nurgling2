package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerPath;
import nurgling.widgets.settings.NAreaDropbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeSet;

public class Forager extends PathBotWindow {

    // Actions Profile selection only - editing lives in Forager Settings (Settings > Bots).
    private Dropbox<String> actionsProfileDropbox = null;

    // Guarding Profile selection only - editing (which composable Guards run pre-flight/
    // in-flight, their thresholds/reactions) lives in Forager Settings' Guarding section, same
    // split as Actions Profile above.
    private Dropbox<String> guardingProfileDropbox = null;

    NAreaDropbox startArea = null;
    Dropbox<String> afterFinishAction = null;
    Dropbox<String> onFullInventoryAction = null;

    private static final String[] AFTER_FINISH_ACTIONS = {"nothing", "logout", "travel hearth"};
    private static final String[] FULL_INVENTORY_ACTIONS = {"nothing", "logout", "travel hearth"};

    public NForagerProp prop = null;
    private String lastPresetName = null;

    public Forager() {
        super(new Coord(380, 300), L10n.get("forager.wnd_title"));

        // Build common UI (preset, path, record, sections)
        prev = buildCommonUI(L10n.get("forager.settings"), L10n.get("forager.preset"), L10n.get("forager.path"));

        // Actions Profile: which one to run with. Editing what's in it (drag items, custom
        // entries, manual patterns) happens in Forager Settings (Settings > Bots > Forager),
        // not here - this window only selects.
        prev = add(new Label(L10n.get("forager.settings.actions_profile")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(actionsProfileDropbox = new Dropbox<String>(UI.scale(200), 8, UI.scale(16)) {
            private java.util.List<String> names() {
                return (prop != null && prop.actionsProfiles != null)
                        ? new ArrayList<>(new TreeSet<>(prop.actionsProfiles.keySet())) : Collections.emptyList();
            }

            @Override
            protected String listitem(int i) {
                return names().get(i);
            }

            @Override
            protected int listitems() {
                return names().size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        // Guarding Profile: which composable safety-watchdog configuration to run with (see
        // nurgling.guarding). Editing happens in Forager Settings, not here - this window only
        // selects, same as Actions Profile above.
        prev = add(new Label(L10n.get("forager.settings.guarding_profile")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(guardingProfileDropbox = new Dropbox<String>(UI.scale(200), 8, UI.scale(16)) {
            private java.util.List<String> names() {
                return (prop != null && prop.guardingProfiles != null)
                        ? new ArrayList<>(new TreeSet<>(prop.guardingProfiles.keySet())) : Collections.emptyList();
            }

            @Override
            protected String listitem(int i) {
                return names().get(i);
            }

            @Override
            protected int listitems() {
                return names().size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        // Start area - ChunkNav-travelled to before the recorded path, so the bot can be
        // started from anywhere (e.g. indoors) rather than requiring a separate go-to first.
        prev = add(new Label(L10n.get("forager.start_area")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(startArea = new NAreaDropbox(UI.scale(150)) {
            @Override
            public boolean mousedown(MouseDownEvent ev) {
                // Pick up any areas created/renamed since this window opened.
                reloadAreas();
                return super.mousedown(ev);
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        // After finish action
        prev = add(new Label(L10n.get("forager.after_finish")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(afterFinishAction = createSimpleDropbox(AFTER_FINISH_ACTIONS), prev.pos("bl").add(UI.scale(0, 5)));

        // On full inventory action
        prev = add(new Label(L10n.get("forager.on_full_inv")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(onFullInventoryAction = createSimpleDropbox(FULL_INVENTORY_ACTIONS), prev.pos("bl").add(UI.scale(0, 5)));

        // Start button
        addStartButton();

        pack();

        // Initialize from config
        initializeFromConfig();
    }

    private Dropbox<String> createSimpleDropbox(String[] items) {
        return new Dropbox<String>(UI.scale(150), items.length, UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return items[i];
            }

            @Override
            protected int listitems() {
                return items.length;
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        };
    }

    // ========== Abstract method implementations ==========

    @Override
    protected String getPathDataDir() {
        return "forager_paths";
    }

    @Override
    protected String getNoPathsMessage() {
        return L10n.get("forager.no_paths");
    }

    @Override
    protected String loadPropAndGetCurrentPreset() {
        prop = NForagerProp.get(NUtils.getUI().sessInfo);
        if (prop == null) {
            prop = new NForagerProp("", "");
        }
        return prop.currentPreset;
    }

    @Override
    protected void saveProp() {
        if (prop != null) {
            NForagerProp.set(prop);
        }
    }

    @Override
    protected String getCurrentPresetName() {
        return prop != null ? prop.currentPreset : null;
    }

    @Override
    protected void setCurrentPresetName(String name) {
        if (prop != null) {
            prop.currentPreset = name;
            lastPresetName = name;
        }
    }

    @Override
    protected String getPresetPathFile(String presetName) {
        if (prop != null && prop.presets != null) {
            NForagerProp.PresetData preset = prop.presets.get(presetName);
            if (preset != null) {
                return preset.pathFile;
            }
        }
        return null;
    }

    @Override
    protected void setPresetPathFile(String presetName, String pathFile) {
        if (prop != null && prop.presets != null) {
            NForagerProp.PresetData preset = prop.presets.get(presetName);
            if (preset != null) {
                preset.pathFile = pathFile;
            }
        }
    }

    @Override
    protected ForagerPath getPresetForagerPath(String presetName) {
        if (prop != null && prop.presets != null) {
            NForagerProp.PresetData preset = prop.presets.get(presetName);
            if (preset != null) {
                return preset.foragerPath;
            }
        }
        return null;
    }

    @Override
    protected void setPresetForagerPath(String presetName, ForagerPath path) {
        if (prop != null && prop.presets != null) {
            NForagerProp.PresetData preset = prop.presets.get(presetName);
            if (preset != null) {
                preset.foragerPath = path;
            }
        }
    }

    @Override
    protected void createPreset(String name) {
        if (prop != null) {
            prop.presets.put(name, new NForagerProp.PresetData());
        }
    }

    @Override
    protected void removePreset(String name) {
        if (prop != null && prop.presets != null) {
            prop.presets.remove(name);
        }
    }

    @Override
    protected Iterable<String> getPresetNames() {
        if (prop != null && prop.presets != null) {
            return prop.presets.keySet();
        }
        return Collections.emptyList();
    }

    @Override
    protected void onPresetLoaded(String presetName) {
        NForagerProp.PresetData preset = prop.presets.get(presetName);
        if (preset != null) {
            updateSafetyDropboxes(preset);
        }

        // Actions Profile selection is prop-level (shared across presets), not per-preset.
        if (prop.actionsProfiles == null || prop.actionsProfiles.isEmpty()) {
            prop.actionsProfiles = new java.util.HashMap<>();
            prop.actionsProfiles.put("Default", new ArrayList<>());
        }
        if (prop.currentActionsProfile == null || !prop.actionsProfiles.containsKey(prop.currentActionsProfile)) {
            prop.currentActionsProfile = prop.actionsProfiles.keySet().iterator().next();
        }
        actionsProfileDropbox.change(prop.currentActionsProfile);

        // Guarding Profile selection is prop-level (shared across presets), same as Actions
        // Profile above.
        if (prop.guardingProfiles == null || prop.guardingProfiles.isEmpty()) {
            prop.guardingProfiles = new java.util.HashMap<>();
            prop.guardingProfiles.put("Default", nurgling.guarding.GuardingProfile.withDefaults());
        }
        if (prop.currentGuardingProfile == null || !prop.guardingProfiles.containsKey(prop.currentGuardingProfile)) {
            prop.currentGuardingProfile = prop.guardingProfiles.keySet().iterator().next();
        }
        guardingProfileDropbox.change(prop.currentGuardingProfile);
    }

    @Override
    protected void onPresetSaving(String presetName) {
        // Save safety settings to the old preset before switching
        NForagerProp.PresetData preset = prop.presets.get(presetName);
        if (preset != null) {
            preset.startAreaId = startArea.getSelectedAreaId();
            if (afterFinishAction.sel != null)
                preset.afterFinishAction = afterFinishAction.sel;
            if (onFullInventoryAction.sel != null)
                preset.onFullInventoryAction = onFullInventoryAction.sel;
        }
        if (actionsProfileDropbox.sel != null) {
            prop.currentActionsProfile = actionsProfileDropbox.sel;
        }
        if (guardingProfileDropbox.sel != null) {
            prop.currentGuardingProfile = guardingProfileDropbox.sel;
        }
    }

    @Override
    protected void onStartBot() {
        if (actionsProfileDropbox.sel != null) {
            prop.currentActionsProfile = actionsProfileDropbox.sel;
        }
        if (guardingProfileDropbox.sel != null) {
            prop.currentGuardingProfile = guardingProfileDropbox.sel;
        }
        NForagerProp.PresetData preset = prop.presets.get(prop.currentPreset);
        if (preset != null) {
            preset.startAreaId = startArea.getSelectedAreaId();

            if (afterFinishAction.sel != null)
                preset.afterFinishAction = afterFinishAction.sel;
            else
                preset.afterFinishAction = "nothing";

            if (onFullInventoryAction.sel != null)
                preset.onFullInventoryAction = onFullInventoryAction.sel;
            else
                preset.onFullInventoryAction = "nothing";
        }
    }

    // ========== Forager-specific methods ==========

    private void updateSafetyDropboxes(NForagerProp.PresetData preset) {
        startArea.reloadAreas();
        startArea.setSelectedAreaId(preset.startAreaId);

        for (int i = 0; i < AFTER_FINISH_ACTIONS.length; i++) {
            if (AFTER_FINISH_ACTIONS[i].equals(preset.afterFinishAction)) {
                afterFinishAction.change(AFTER_FINISH_ACTIONS[i]);
                break;
            }
        }

        for (int i = 0; i < FULL_INVENTORY_ACTIONS.length; i++) {
            if (FULL_INVENTORY_ACTIONS[i].equals(preset.onFullInventoryAction)) {
                onFullInventoryAction.change(FULL_INVENTORY_ACTIONS[i]);
                break;
            }
        }
    }
}
