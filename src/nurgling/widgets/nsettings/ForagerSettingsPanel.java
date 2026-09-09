package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NGameUI;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.conf.NForagerProp;
import nurgling.guarding.Guard;
import nurgling.guarding.GuardEntry;
import nurgling.guarding.GuardInput;
import nurgling.guarding.GuardOutcome;
import nurgling.guarding.GuardRegistry;
import nurgling.guarding.GuardSpec;
import nurgling.guarding.GuardingProfile;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerAction;
import nurgling.routes.ForagerPath;
import nurgling.routes.ForagerRouteStore;
import nurgling.widgets.ForagerPickupContainer;
import nurgling.widgets.TextInputWindow;
import nurgling.widgets.options.NRingSettings;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** "Forager Settings" panel under Settings &gt; Bots - owns editing of Forager's Actions, Routes, and Guarding Profiles. */
public class ForagerSettingsPanel extends Panel {

    // Shared row layout for the Routes/Guarding sections' data rows: label at ROW_LABEL_X, values/units after, trailing control at ROW_TOGGLE_X.
    private static final int ROW_LABEL_X = 0;
    private static final int ROW_VALUE1_X = 250;
    private static final int ROW_UNIT1_X = 295;
    private static final int ROW_VALUE2_X = 350;
    private static final int ROW_UNIT2_X = 395;
    private static final int ROW_TOGGLE_X = 420;
    private static final int ROW_W = 530;
    private static final int ROW_H = 24;
    private static final int ENTRY_W = 50;
    private static final int ROUTE_VALUE_X = 350;

    // Short, one-line-each instructions for the map editor below, in display order.
    private static final String[] ROUTES_HELP_KEYS = {
            "forager.settings.routes_help_add",
            "forager.settings.routes_help_move",
            "forager.settings.routes_help_delete",
            "forager.settings.routes_help_steps",
            "forager.settings.routes_help_milestone",
            "forager.settings.routes_help_paint",
            "forager.settings.routes_help_erase",
            "forager.settings.routes_help_pan_zoom",
    };

    private NForagerProp prop;
    private final Dropbox<String> actionsProfileDropbox;
    private final ForagerPickupContainer pickupContainer;

    private ForagerPath currentRoute;
    private final List<String> routeNames = new ArrayList<>();
    private final Dropbox<String> routeDropbox;
    // Set around load()'s own programmatic routeDropbox.change() so a reload discards pending edits instead of auto-saving them.
    private boolean suppressRouteAutoSave = false;
    // Same purpose as suppressRouteAutoSave, for guardingProfileDropbox's write-back-before-switch logic.
    private boolean suppressGuardingAutoSave = false;

    // Built lazily on first load() - constructing ForagerRouteMap here would NPE before gui.mmap exists.
    private ForagerRouteMap routeMap;
    private TextEntry brushSizeEntry;
    private CheckBox avoidCliffsCheck;
    private TextEntry cliffBufferEntry;
    private TextEntry maxBranchesEntry;
    private TextEntry maxDistanceEntry;
    private TextEntry maxBranchDistanceEntry;

    // ---- Guarding ----
    // "break" just stops the bot; whether a check runs at all is a separate per-row enabled CheckBox (see GuardRow/buildGuardRow).
    private static final String[] GUARD_ACTIONS = GuardOutcome.ALL_IDS;
    private static final int CHECK_TOGGLE_X = 0;
    private static final int CHECK_LABEL_X = 24;

    private GuardingProfile currentGuardingProfile;
    private Dropbox<String> guardingProfileDropbox;
    private CheckBox waterModeCheck;
    private CheckBox ignoreBatsCheck;

    /** One built row's live widgets, keyed by GuardSpec.id in preflightRows/inflightRows - see buildGuardRow(). */
    private static final class GuardRow {
        final CheckBox enabled;
        final List<TextEntry> inputs;
        final Dropbox<String> outcome;

        GuardRow(CheckBox enabled, List<TextEntry> inputs, Dropbox<String> outcome) {
            this.enabled = enabled;
            this.inputs = inputs;
            this.outcome = outcome;
        }
    }

    // Built once in the constructor, one row per GuardRegistry.preflightIds()/inflightIds().
    private final Map<String, GuardRow> preflightRows = new LinkedHashMap<>();
    private final Map<String, GuardRow> inflightRows = new LinkedHashMap<>();

    // ---- Presets ----
    // A preset bundles which Actions Profile/Route/Guarding Profile to run together, plus the start area and finish/full-inventory reactions.
    private static final String[] PRESET_ACTIONS = {"nothing", "logout", "travel hearth"};
    private NForagerProp.PresetData currentPresetData;
    private Dropbox<String> presetDropbox;
    private Dropbox<String> presetActionsDropbox;
    private Dropbox<String> presetRouteDropbox;
    private Dropbox<String> presetGuardingDropbox;
    private Dropbox<String> presetAfterFinishDropbox;
    private Dropbox<String> presetOnFullInventoryDropbox;
    private CheckBox presetIgnoreMaintainLimitsCheck;
    // Same purpose as suppressRouteAutoSave/suppressGuardingAutoSave above.
    private boolean suppressPresetAutoSave = false;

    // Actions/Guarding each have two selectors (top section's editor, Presets section's picker); switching the active preset's profile mirrors into the top editor, one-directionally only.
    private Scrollport scroll;
    private CollapsibleSection routesSection;
    private Widget routesContent;
    private Widget mapAnchor;

    // Every top-level CollapsibleSection, in display order; relayoutSections() repositions them after any toggle/content-height change.
    private final List<CollapsibleSection> sections = new ArrayList<>();

    /** Base for this panel's "list of names" dropdowns; only names()/change() differ per site. */
    private abstract class NamesDropbox extends Dropbox<String> {
        NamesDropbox(int w, int h, int itemh) {
            super(w, h, itemh);
        }

        protected abstract List<String> names();

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
    }

    public ForagerSettingsPanel() {
        super(L10n.get("nsettings.item.forager"));

        // Scrollable viewport since this panel's content runs past the panel's own 580x580 budget.
        scroll = add(new Scrollport(UI.scale(new Coord(560, 530))), UI.scale(10, 40));
        Widget cont = scroll.cont;

        // Each logically-separate group gets its own collapsible section.
        CollapsibleSection actionsSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.actions_section"), UI.scale(540), false), Coord.z);
        actionsSection.setOnToggle(this::relayoutSections);
        sections.add(actionsSection);
        Widget sec = actionsSection.content;

        Widget prev = sec.add(new Label(L10n.get("forager.settings.actions_help"), UI.scale(400)), Coord.z);

        prev = sec.add(new Label(L10n.get("forager.settings.actions_profile")), prev.pos("bl").add(UI.scale(0, 12)));

        Widget profileRow = sec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), prev.pos("bl").add(UI.scale(0, 5)));
        profileRow.add(actionsProfileDropbox = new NamesDropbox(UI.scale(200), 8, UI.scale(16)) {
            @Override
            protected List<String> names() {
                return prop != null ? new ArrayList<>(new TreeSet<>(prop.actionsProfiles.keySet())) : Collections.emptyList();
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null && prop != null) {
                    prop.currentActionsProfile = item;
                    // Self-heals a stale reference by creating the missing entry rather than handing pickupContainer.load() a null list.
                    pickupContainer.load(prop.actionsProfiles.computeIfAbsent(item, k -> new ArrayList<>()));
                    // Deliberately one-directional - does not push into the Presets section's selector/active preset binding.
                }
            }
        }, new Coord(0, 0));

        profileRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                addProfile();
            }
        }, new Coord(UI.scale(210), 0)).settip(L10n.get("forager.settings.new_profile_tip"));

        profileRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/remove/u"),
                Resource.loadsimg("nurgling/hud/buttons/remove/d"),
                Resource.loadsimg("nurgling/hud/buttons/remove/h")) {
            @Override
            public void click() {
                super.click();
                deleteProfile();
            }
        }, new Coord(UI.scale(240), 0)).settip(L10n.get("forager.settings.delete_profile_tip"));

        profileRow.add(new IButton(
                NStyle.importb[0].back, NStyle.importb[1].back, NStyle.importb[2].back) {
            @Override
            public void click() {
                super.click();
                importProfile();
            }
        }, new Coord(UI.scale(275), 0)).settip(L10n.get("forager.settings.import_profile_tip"));

        profileRow.add(new IButton(
                NStyle.exportb[0].back, NStyle.exportb[1].back, NStyle.exportb[2].back) {
            @Override
            public void click() {
                super.click();
                exportProfile();
            }
        }, new Coord(UI.scale(305), 0)).settip(L10n.get("forager.settings.export_profile_tip"));

        pickupContainer = new ForagerPickupContainer();
        pickupContainer.resize(UI.scale(new Coord(400, 320)));

        Widget pickupButtonsRow = sec.add(new Widget(new Coord(UI.scale(300), UI.scale(24))), profileRow.pos("bl").add(UI.scale(0, 10)));
        pickupButtonsRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                pickupContainer.promptAddCustom();
            }
        }, new Coord(0, 0)).settip(L10n.get("forager.pickup.add_custom"));

        pickupButtonsRow.add(new IButton(
                NStyle.catmenu[0].back, NStyle.catmenu[1].back, NStyle.catmenu[2].back) {
            @Override
            public void click() {
                super.click();
                pickupContainer.openCatalogue();
            }
        }, new Coord(UI.scale(30), 0)).settip(L10n.get("forager.pickup.catalogue"));

        sec.add(pickupContainer, pickupButtonsRow.pos("bl").add(UI.scale(0, 5)));

        actionsSection.pack();

        // ---- Routes ----
        routesSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.routes_section"), UI.scale(540), false), actionsSection.pos("bl").add(UI.scale(0, 10)));
        routesSection.setOnToggle(() -> {
            relayoutSections();
            updateActiveRouteEditor();
        });
        sections.add(routesSection);
        Widget rsec = routesContent = routesSection.content;

        Widget rprev = null;
        for (String key : ROUTES_HELP_KEYS) {
            rprev = rsec.add(new Label("• " + L10n.get(key), UI.scale(520)), rprev == null ? Coord.z : rprev.pos("bl").add(UI.scale(0, 3)));
        }

        rprev = rsec.add(new Label(L10n.get("forager.settings.route")), rprev.pos("bl").add(UI.scale(0, 12)));

        Widget routeRow = rsec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), rprev.pos("bl").add(UI.scale(0, 5)));
        routeRow.add(routeDropbox = new NamesDropbox(UI.scale(200), 8, UI.scale(16)) {
            @Override
            protected List<String> names() {
                return routeNames;
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null) {
                    // Switching routes must not silently drop in-progress edits to the one being switched away from.
                    if (!suppressRouteAutoSave && currentRoute != null && !currentRoute.name.equals(item)) {
                        saveCurrentRoute();
                    }
                    loadRoute(item);
                }
            }
        }, new Coord(0, 0));

        routeRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                addRoute();
            }
        }, new Coord(UI.scale(210), 0)).settip(L10n.get("forager.settings.new_route_tip"));

        routeRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/remove/u"),
                Resource.loadsimg("nurgling/hud/buttons/remove/d"),
                Resource.loadsimg("nurgling/hud/buttons/remove/h")) {
            @Override
            public void click() {
                super.click();
                deleteRoute();
            }
        }, new Coord(UI.scale(240), 0)).settip(L10n.get("forager.settings.delete_route_tip"));

        // Brush/cliff rows share one value column (ROUTE_VALUE_X) so their TextEntry boxes line up.
        Widget brushRow = rsec.add(new Widget(new Coord(UI.scale(ROW_W), UI.scale(ROW_H))), routeRow.pos("bl").add(UI.scale(0, 10)));
        rowItem(brushRow, new Label(L10n.get("forager.settings.brush_size")), UI.scale(ROW_LABEL_X));
        brushSizeEntry = rowItem(brushRow, new TextEntry(UI.scale(ENTRY_W), String.valueOf(ForagerRouteMap.DEFAULT_BRUSH_SIZE_TILES)) {
            @Override
            public void done(ReadLine buf) {
                super.done(buf);
                applyBrushSize();
            }
        }, UI.scale(ROUTE_VALUE_X));

        // Route geometry (per the currently selected route), not a bot-behavior/safety setting.
        Widget cliffRow = rsec.add(new Widget(new Coord(UI.scale(ROW_W), UI.scale(ROW_H))), brushRow.pos("bl").add(UI.scale(0, 6)));
        avoidCliffsCheck = rowItem(cliffRow, new CheckBox(L10n.get("forager.settings.avoid_cliffs")) {
            @Override
            public void set(boolean val) {
                a = val;
                if (currentRoute != null) {
                    currentRoute.avoidCliffs = val;
                    routeMap.markDirty();
                }
            }
        }, UI.scale(ROW_LABEL_X));
        avoidCliffsCheck.settip(L10n.get("forager.settings.avoid_cliffs_tip"));
        rowItem(cliffRow, new Label(L10n.get("forager.settings.cliff_buffer")), UI.scale(160));
        cliffBufferEntry = rowItem(cliffRow, new TextEntry(UI.scale(ENTRY_W), "1"), UI.scale(ROUTE_VALUE_X));

        // Own offsets, not the brush/cliff rows' shared column - the longest label needs more room than an even 3-way split.
        Widget capsRow = rsec.add(new Widget(new Coord(UI.scale(560), UI.scale(ROW_H))), cliffRow.pos("bl").add(UI.scale(0, 8)));
        rowItem(capsRow, new Label(L10n.get("forager.settings.max_branches")), UI.scale(0));
        maxBranchesEntry = rowItem(capsRow, new TextEntry(UI.scale(ENTRY_W), ""), UI.scale(80));
        maxBranchesEntry.settip(L10n.get("forager.settings.max_branches_tip"));
        rowItem(capsRow, new Label(L10n.get("forager.settings.max_distance")), UI.scale(160));
        maxDistanceEntry = rowItem(capsRow, new TextEntry(UI.scale(ENTRY_W), ""), UI.scale(280));
        maxDistanceEntry.settip(L10n.get("forager.settings.max_distance_tip"));
        rowItem(capsRow, new Label(L10n.get("forager.settings.max_branch_distance")), UI.scale(360));
        maxBranchDistanceEntry = rowItem(capsRow, new TextEntry(UI.scale(ENTRY_W), ""), UI.scale(500));
        maxBranchDistanceEntry.settip(L10n.get("forager.settings.max_branch_distance_tip"));

        mapAnchor = capsRow;

        // ForagerRouteMap itself is built lazily - see ensureRouteMapBuilt(), called from load().
        routesSection.pack();

        // ---- Guarding ----
        CollapsibleSection guardingSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.guarding_section"), UI.scale(540), false), routesSection.pos("bl").add(UI.scale(0, 10)));
        guardingSection.setOnToggle(this::relayoutSections);
        sections.add(guardingSection);
        Widget gsec = guardingSection.content;

        Widget gprev = gsec.add(new Label(L10n.get("forager.settings.guarding_help"), UI.scale(400)), Coord.z);

        gprev = gsec.add(new Label(L10n.get("forager.settings.guarding_profile")), gprev.pos("bl").add(UI.scale(0, 12)));
        Widget guardingProfileRow = gsec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), gprev.pos("bl").add(UI.scale(0, 5)));
        guardingProfileRow.add(guardingProfileDropbox = new NamesDropbox(UI.scale(200), 8, UI.scale(16)) {
            @Override
            protected List<String> names() {
                return prop != null ? new ArrayList<>(new TreeSet<>(prop.guardingProfiles.keySet())) : Collections.emptyList();
            }

            @Override
            public void change(String item) {
                String previous = sel;
                super.change(item);
                if (item != null && prop != null) {
                    // Switching profiles must not silently drop in-progress edits to the one being switched away from.
                    if (!suppressGuardingAutoSave && previous != null && !previous.equals(item) && currentGuardingProfile != null) {
                        writeBackCurrentGuardingProfile();
                        prop.guardingProfiles.put(previous, currentGuardingProfile);
                    }
                    prop.currentGuardingProfile = item;
                    loadGuardingProfile(item);
                    // Deliberately one-directional - see actionsProfileDropbox's equivalent comment.
                }
            }
        }, new Coord(0, 0));

        guardingProfileRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                addGuardingProfile();
            }
        }, new Coord(UI.scale(210), 0)).settip(L10n.get("forager.settings.new_guarding_profile_tip"));

        guardingProfileRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/remove/u"),
                Resource.loadsimg("nurgling/hud/buttons/remove/d"),
                Resource.loadsimg("nurgling/hud/buttons/remove/h")) {
            @Override
            public void click() {
                super.click();
                deleteGuardingProfile();
            }
        }, new Coord(UI.scale(240), 0)).settip(L10n.get("forager.settings.delete_guarding_profile_tip"));

        Widget toggleRow = gsec.add(new Widget(new Coord(UI.scale(ROW_W), UI.scale(ROW_H))), guardingProfileRow.pos("bl").add(UI.scale(0, 12)));
        waterModeCheck = rowItem(toggleRow, new CheckBox(L10n.get("forager.settings.water_mode")), UI.scale(0));
        waterModeCheck.settip(L10n.get("forager.settings.water_mode_tip"));
        ignoreBatsCheck = rowItem(toggleRow, new CheckBox(L10n.get("forager.settings.ignore_bats")), UI.scale(160));
        ignoreBatsCheck.a = true;
        ignoreBatsCheck.settip(L10n.get("forager.settings.ignore_bats_tip"));

        // Every check row is built generically from GuardRegistry (see buildGuardRow); Pre-flight runs once before departing, In-flight continuously.
        Widget preflightLabel = gsec.add(new Label(L10n.get("forager.settings.preflight_checks")), toggleRow.pos("bl").add(UI.scale(0, 14)));
        Widget prevGuardRow = preflightLabel;
        for (String id : GuardRegistry.preflightIds()) {
            prevGuardRow = buildGuardRow(gsec, prevGuardRow, GuardRegistry.get(id), preflightRows);
        }

        Widget inflightLabel = gsec.add(new Label(L10n.get("forager.settings.inflight_checks")), prevGuardRow.pos("bl").add(UI.scale(0, 14)));
        prevGuardRow = inflightLabel;
        for (String id : GuardRegistry.inflightIds()) {
            prevGuardRow = buildGuardRow(gsec, prevGuardRow, GuardRegistry.get(id), inflightRows);
        }

        guardingSection.pack();

        // ---- Presets ----
        CollapsibleSection presetsSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.presets_section"), UI.scale(540), false), guardingSection.pos("bl").add(UI.scale(0, 10)));
        presetsSection.setOnToggle(this::relayoutSections);
        sections.add(presetsSection);
        Widget psec = presetsSection.content;

        Widget pprev = psec.add(new Label(L10n.get("forager.settings.presets_help"), UI.scale(400)), Coord.z);

        pprev = psec.add(new Label(L10n.get("forager.settings.preset")), pprev.pos("bl").add(UI.scale(0, 12)));
        Widget presetRow = psec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), pprev.pos("bl").add(UI.scale(0, 5)));
        presetRow.add(presetDropbox = new NamesDropbox(UI.scale(200), 8, UI.scale(16)) {
            @Override
            protected List<String> names() {
                return prop != null ? new ArrayList<>(new TreeSet<>(prop.presets.keySet())) : Collections.emptyList();
            }

            @Override
            public void change(String item) {
                String previous = sel;
                super.change(item);
                if (item != null && prop != null) {
                    if (!suppressPresetAutoSave && previous != null && !previous.equals(item) && currentPresetData != null) {
                        writeBackCurrentPreset();
                    }
                    prop.currentPreset = item;
                    loadPreset(item);
                }
            }
        }, new Coord(0, 0));

        presetRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                addPreset();
            }
        }, new Coord(UI.scale(210), 0)).settip(L10n.get("forager.settings.new_preset_tip"));

        presetRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/remove/u"),
                Resource.loadsimg("nurgling/hud/buttons/remove/d"),
                Resource.loadsimg("nurgling/hud/buttons/remove/h")) {
            @Override
            public void click() {
                super.click();
                deletePreset();
            }
        }, new Coord(UI.scale(240), 0)).settip(L10n.get("forager.settings.delete_preset_tip"));

        Widget prevField = psec.add(new Label(L10n.get("forager.settings.preset_actions_profile")), presetRow.pos("bl").add(UI.scale(0, 12)));
        prevField = psec.add(presetActionsDropbox = new NamesDropbox(UI.scale(300), 8, UI.scale(16)) {
            @Override
            protected List<String> names() {
                return prop != null ? new ArrayList<>(new TreeSet<>(prop.actionsProfiles.keySet())) : Collections.emptyList();
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null && prop != null) {
                    if (currentPresetData != null) {
                        currentPresetData.actionsProfileName = item;
                    }
                    // Mirror into the Actions section's own selector; one-directional only, see actionsProfileDropbox's change().
                    actionsProfileDropbox.change(item);
                }
            }
        }, prevField.pos("bl").add(UI.scale(0, 5)));

        prevField = psec.add(new Label(L10n.get("forager.settings.preset_route")), prevField.pos("bl").add(UI.scale(0, 10)));
        prevField = psec.add(presetRouteDropbox = new NamesDropbox(UI.scale(300), 8, UI.scale(16)) {
            @Override
            protected List<String> names() {
                return routeNames;
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null) {
                    // Mirror into the Routes section's own selector, same one-directional pattern
                    // as actionsProfileDropbox/guardingProfileDropbox above - so picking a
                    // different route for this preset also loads it into the map editor below,
                    // instead of leaving whatever route was last open there.
                    routeDropbox.change(item);
                }
            }
        }, prevField.pos("bl").add(UI.scale(0, 5)));

        prevField = psec.add(new Label(L10n.get("forager.settings.preset_guarding_profile")), prevField.pos("bl").add(UI.scale(0, 10)));
        prevField = psec.add(presetGuardingDropbox = new NamesDropbox(UI.scale(300), 8, UI.scale(16)) {
            @Override
            protected List<String> names() {
                return prop != null ? new ArrayList<>(new TreeSet<>(prop.guardingProfiles.keySet())) : Collections.emptyList();
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null && prop != null) {
                    if (currentPresetData != null) {
                        currentPresetData.guardingProfileName = item;
                    }
                    // Mirror into the Guarding section's own selector - one-directional, see presetActionsDropbox's equivalent.
                    guardingProfileDropbox.change(item);
                }
            }
        }, prevField.pos("bl").add(UI.scale(0, 5)));

        prevField = psec.add(new Label(L10n.get("forager.after_finish")), prevField.pos("bl").add(UI.scale(0, 10)));
        prevField = psec.add(presetAfterFinishDropbox = buildSimpleDropbox(PRESET_ACTIONS, UI.scale(150)), prevField.pos("bl").add(UI.scale(0, 5)));

        prevField = psec.add(new Label(L10n.get("forager.on_full_inv")), prevField.pos("bl").add(UI.scale(0, 10)));
        prevField = psec.add(presetOnFullInventoryDropbox = buildSimpleDropbox(PRESET_ACTIONS, UI.scale(150)), prevField.pos("bl").add(UI.scale(0, 5)));

        // Lets a "manual" preset share the same Actions profile without inheriting its Maintain caps - see NForagerProp.PresetData.ignoreMaintainLimits.
        presetIgnoreMaintainLimitsCheck = psec.add(new CheckBox(L10n.get("forager.settings.ignore_maintain_limits")), prevField.pos("bl").add(UI.scale(0, 10)));
        presetIgnoreMaintainLimitsCheck.settip(L10n.get("forager.settings.ignore_maintain_limits_tip"));

        presetsSection.pack();

        relayoutSections();
    }

    /** Shared builder for a plain fixed-option-list dropdown. */
    private Dropbox<String> buildSimpleDropbox(String[] items, int width) {
        Dropbox<String> db = new Dropbox<String>(width, items.length, UI.scale(16)) {
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
        db.change(items[0]);
        return db;
    }

    /** Builds one generic guard-check row from a GuardSpec, recording its widgets in rowMap keyed by spec.id. */
    private Widget buildGuardRow(Widget gsec, Widget prev, GuardSpec spec, Map<String, GuardRow> rowMap) {
        Widget row = gsec.add(new Widget(new Coord(UI.scale(ROW_W), UI.scale(ROW_H))), prev.pos("bl").add(UI.scale(0, 4)));
        CheckBox enabled = rowItem(row, new CheckBox(""), UI.scale(CHECK_TOGGLE_X));
        rowItem(row, new Label(spec.label), UI.scale(CHECK_LABEL_X));

        int[] valueXs = {ROW_VALUE1_X, ROW_VALUE2_X};
        int[] unitXs = {ROW_UNIT1_X, ROW_UNIT2_X};
        List<TextEntry> inputEntries = new ArrayList<>();
        for (int i = 0; i < spec.inputs.size() && i < valueXs.length; i++) {
            GuardInput input = spec.inputs.get(i);
            TextEntry entry = rowItem(row, new TextEntry(UI.scale(ENTRY_W), String.valueOf((long) input.defaultValue)), UI.scale(valueXs[i]));
            rowItem(row, new Label(input.suffixLabel), UI.scale(unitXs[i]));
            inputEntries.add(entry);
        }

        if ("dangerous_animal".equals(spec.id)) {
            rowItem(row, new Button(UI.scale(150), L10n.get("forager.settings.aggression_radii"), this::openRingSettings), UI.scale(ROW_VALUE1_X));
        }

        Dropbox<String> outcome = rowItem(row, buildSimpleDropbox(GUARD_ACTIONS, UI.scale(110)), UI.scale(ROW_TOGGLE_X));

        rowMap.put(spec.id, new GuardRow(enabled, inputEntries, outcome));
        return row;
    }

    /** Adds child to row, vertically centered against the row's declared height; x is the child's left edge. */
    private <T extends Widget> T rowItem(Widget row, T child, int x) {
        return row.adda(child, new Coord(x, row.sz.y / 2), 0.0, 0.5);
    }

    /** Repositions every top-level section below the current bottom edge of the one before it. */
    private void relayoutSections() {
        Coord next = Coord.z;
        for (CollapsibleSection s : sections) {
            s.move(next);
            next = s.pos("bl").add(UI.scale(0, 10));
        }
        scroll.cont.update();
    }

    /** Builds the embedded route-editing map on first open, not in the constructor; idempotent. */
    private void ensureRouteMapBuilt() {
        if (routeMap != null) return;

        routeMap = routesContent.add(new ForagerRouteMap(UI.scale(new Coord(520, 360)), NUtils.getGameUI().mmap.file), mapAnchor.pos("bl").add(UI.scale(0, 10)));
        routeMap.onResetRequested = this::resetCurrentRoute;
        applyBrushSize();

        routesSection.pack();
        relayoutSections();
    }

    // Intercepted here so routeMap gets wheel events for zoom before the outer Scrollport consumes them for page-scrolling.
    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        if (routeMap != null) {
            Coord rel = ev.c.sub(routeMap.parentpos(this));
            if (rel.isect(Coord.z, routeMap.sz)) {
                return routeMap.mousewheel(ev.derive(rel));
            }
        }
        return super.mousewheel(ev);
    }

    @Override
    public void load() {
        ensureRouteMapBuilt();

        prop = NForagerProp.get(NUtils.getUI().sessInfo);
        if (prop == null) return;

        // Reset before touching actionsProfileDropbox/guardingProfileDropbox, so a reload doesn't write into the previous load's preset object.
        currentPresetData = null;

        if (prop.actionsProfiles.isEmpty()) {
            prop.actionsProfiles.put("Default", new ArrayList<>());
        }
        if (prop.currentActionsProfile == null || !prop.actionsProfiles.containsKey(prop.currentActionsProfile)) {
            prop.currentActionsProfile = prop.actionsProfiles.keySet().iterator().next();
        }

        actionsProfileDropbox.change(prop.currentActionsProfile);
        pickupContainer.load(prop.actionsProfiles.get(prop.currentActionsProfile));

        if (prop.guardingProfiles.isEmpty()) {
            prop.guardingProfiles.put("Default", GuardingProfile.withDefaults());
        }
        if (prop.currentGuardingProfile == null || !prop.guardingProfiles.containsKey(prop.currentGuardingProfile)) {
            prop.currentGuardingProfile = prop.guardingProfiles.keySet().iterator().next();
        }
        suppressGuardingAutoSave = true;
        try {
            guardingProfileDropbox.change(prop.currentGuardingProfile);
        } finally {
            suppressGuardingAutoSave = false;
        }

        loadAvailableRoutes();
        suppressRouteAutoSave = true;
        try {
            if (!routeNames.isEmpty()) {
                routeDropbox.change(routeNames.get(0));
            } else {
                clearRouteUI();
            }
        } finally {
            suppressRouteAutoSave = false;
        }

        // Presets loads last - loadPreset() needs routeNames already populated.
        if (prop.presets.isEmpty()) {
            NForagerProp.PresetData defaultPreset = new NForagerProp.PresetData();
            defaultPreset.actionsProfileName = prop.currentActionsProfile;
            defaultPreset.guardingProfileName = prop.currentGuardingProfile;
            prop.presets.put("Default", defaultPreset);
        }
        if (prop.currentPreset == null || !prop.presets.containsKey(prop.currentPreset)) {
            prop.currentPreset = prop.presets.keySet().iterator().next();
        }
        suppressPresetAutoSave = true;
        try {
            presetDropbox.change(prop.currentPreset);
        } finally {
            suppressPresetAutoSave = false;
        }

        // Kept for the reload case where Routes may already be expanded from before.
        updateActiveRouteEditor();
    }

    /** Lets NMapView show/edit whichever route this panel has loaded, only while Routes is expanded. */
    private void updateActiveRouteEditor() {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null) return;
        gui.activeRouteEditor = (routesSection != null && routesSection.isExpanded() && routeMap != null)
                ? routeMap : null;
    }

    // Walks the parent chain since this.visible alone doesn't cascade from an ancestor's hide().
    private boolean genuinelyVisible() {
        for (Widget w = this; w != null; w = w.parent) {
            if (!w.visible) return false;
        }
        return true;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (genuinelyVisible()) {
            updateActiveRouteEditor();
        } else {
            NGameUI gui = NUtils.getGameUI();
            if (gui != null && gui.activeRouteEditor == routeMap) {
                gui.activeRouteEditor = null;
            }
        }
    }

    @Override
    public void hide() {
        super.hide();
        NGameUI gui = NUtils.getGameUI();
        if (gui != null) {
            gui.activeRouteEditor = null;
        }
    }

    @Override
    public void save() {
        if (prop != null) {
            writeBackCurrentPreset();
            if (currentPresetData != null && presetDropbox.sel != null) {
                prop.presets.put(presetDropbox.sel, currentPresetData);
            }
            writeBackCurrentGuardingProfile();
            if (currentGuardingProfile != null && guardingProfileDropbox.sel != null) {
                prop.guardingProfiles.put(guardingProfileDropbox.sel, currentGuardingProfile);
            }
            NForagerProp.set(prop);
        }
        saveCurrentRoute();
    }

    /** Populates every Guarding widget from the named profile, creating one with defaults if it doesn't exist yet. */
    private void loadGuardingProfile(String name) {
        if (prop == null) return;
        GuardingProfile profile = prop.guardingProfiles.get(name);
        if (profile == null) {
            profile = GuardingProfile.withDefaults();
            prop.guardingProfiles.put(name, profile);
        }
        profile.reconcileWithRegistry();
        currentGuardingProfile = profile;

        waterModeCheck.a = profile.waterMode;
        ignoreBatsCheck.a = profile.ignoreBats;

        applyGuardEntriesToRows(profile.preflightGuards, preflightRows);
        applyGuardEntriesToRows(profile.inflightGuards, inflightRows);
    }

    private void applyGuardEntriesToRows(List<GuardEntry> entries, Map<String, GuardRow> rowMap) {
        for (GuardEntry entry : entries) {
            GuardRow row = rowMap.get(entry.guardId);
            GuardSpec spec = GuardRegistry.get(entry.guardId);
            if (row == null || spec == null) continue;
            row.enabled.a = entry.enabled;
            for (int i = 0; i < spec.inputs.size() && i < row.inputs.size(); i++) {
                GuardInput input = spec.inputs.get(i);
                double v = entry.settings.getOrDefault(input.key, input.defaultValue);
                row.inputs.get(i).settext(String.valueOf((long) v));
            }
            row.outcome.change(entry.outcomeId);
        }
    }

    /** Reads every Guarding widget back into currentGuardingProfile - called before switching profiles and from save(). */
    private void writeBackCurrentGuardingProfile() {
        if (currentGuardingProfile == null) return;
        currentGuardingProfile.waterMode = waterModeCheck.a;
        currentGuardingProfile.ignoreBats = ignoreBatsCheck.a;
        writeRowsToGuardEntries(currentGuardingProfile.preflightGuards, preflightRows);
        writeRowsToGuardEntries(currentGuardingProfile.inflightGuards, inflightRows);
    }

    private void writeRowsToGuardEntries(List<GuardEntry> entries, Map<String, GuardRow> rowMap) {
        for (GuardEntry entry : entries) {
            GuardRow row = rowMap.get(entry.guardId);
            GuardSpec spec = GuardRegistry.get(entry.guardId);
            if (row == null || spec == null) continue;
            entry.enabled = row.enabled.a;
            entry.outcomeId = row.outcome.sel != null ? row.outcome.sel : "break";
            for (int i = 0; i < spec.inputs.size() && i < row.inputs.size(); i++) {
                GuardInput input = spec.inputs.get(i);
                entry.settings.put(input.key, parseDoubleOrDefault(row.inputs.get(i).text(), input.defaultValue));
            }
        }
    }

    private double parseDoubleOrDefault(String text, double def) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** Populates every Presets widget from the named preset, creating it with defaults if it doesn't exist yet. */
    private void loadPreset(String name) {
        if (prop == null) return;
        NForagerProp.PresetData pd = prop.presets.get(name);
        if (pd == null) {
            pd = new NForagerProp.PresetData();
            pd.actionsProfileName = prop.currentActionsProfile;
            pd.guardingProfileName = prop.currentGuardingProfile;
            prop.presets.put(name, pd);
        }
        currentPresetData = pd;

        presetActionsDropbox.change(pd.actionsProfileName != null ? pd.actionsProfileName : prop.currentActionsProfile);

        String routeName = ForagerRouteStore.fileToName(pd.pathFile);
        if (routeName != null && routeNames.contains(routeName)) {
            presetRouteDropbox.change(routeName);
        } else if (!routeNames.isEmpty()) {
            presetRouteDropbox.change(routeNames.get(0));
        }

        presetGuardingDropbox.change(pd.guardingProfileName != null ? pd.guardingProfileName : prop.currentGuardingProfile);

        presetAfterFinishDropbox.change(pd.afterFinishAction != null ? pd.afterFinishAction : "nothing");
        presetOnFullInventoryDropbox.change(pd.onFullInventoryAction != null ? pd.onFullInventoryAction : "nothing");
        presetIgnoreMaintainLimitsCheck.a = pd.ignoreMaintainLimits;
    }

    /** Reads every Presets widget back into currentPresetData - called before switching presets and from save(). */
    private void writeBackCurrentPreset() {
        if (currentPresetData == null) return;
        if (presetActionsDropbox.sel != null) {
            currentPresetData.actionsProfileName = presetActionsDropbox.sel;
        }
        if (presetRouteDropbox.sel != null) {
            currentPresetData.pathFile = ForagerRouteStore.nameToFile(presetRouteDropbox.sel);
            // Stale now - Forager.run() re-resolves foragerPath from pathFile itself.
            currentPresetData.foragerPath = null;
        }
        if (presetGuardingDropbox.sel != null) {
            currentPresetData.guardingProfileName = presetGuardingDropbox.sel;
        }
        if (presetAfterFinishDropbox.sel != null) {
            currentPresetData.afterFinishAction = presetAfterFinishDropbox.sel;
        }
        if (presetOnFullInventoryDropbox.sel != null) {
            currentPresetData.onFullInventoryAction = presetOnFullInventoryDropbox.sel;
        }
        currentPresetData.ignoreMaintainLimits = presetIgnoreMaintainLimitsCheck.a;
    }


    private void addPreset() {
        if (prop == null) return;
        addNamedEntry("forager.settings.new_preset_title", "forager.settings.new_preset_prompt",
                prop.presets, () -> {
                    NForagerProp.PresetData pd = new NForagerProp.PresetData();
                    pd.actionsProfileName = prop.currentActionsProfile;
                    pd.guardingProfileName = prop.currentGuardingProfile;
                    return pd;
                }, name -> prop.currentPreset = name, presetDropbox);
    }

    private void deletePreset() {
        if (prop == null) return;
        deleteNamedEntry(prop.presets, presetDropbox, name -> prop.currentPreset = name);
    }

    private void loadAvailableRoutes() {
        routeNames.clear();
        routeNames.addAll(ForagerRouteStore.listRouteNames());
    }

    private void loadRoute(String name) {
        currentRoute = ForagerRouteStore.load(name);
        routeMap.setRoute(currentRoute);
        avoidCliffsCheck.a = currentRoute.avoidCliffs;
        cliffBufferEntry.settext(String.valueOf(currentRoute.cliffBufferTiles));
        maxBranchesEntry.settext(currentRoute.maxBranches < 0 ? "" : String.valueOf(currentRoute.maxBranches));
        maxDistanceEntry.settext(currentRoute.maxDistance < 0 ? "" : String.valueOf(currentRoute.maxDistance));
        maxBranchDistanceEntry.settext(currentRoute.maxBranchDistance < 0 ? "" : String.valueOf(currentRoute.maxBranchDistance));
    }

    /** Discards unsaved in-memory edits to the currently selected route by reloading it from disk. */
    private void resetCurrentRoute() {
        if (routeDropbox.sel == null) return;
        loadRoute(routeDropbox.sel);
    }

    private void clearRouteUI() {
        currentRoute = null;
        routeMap.setRoute(null);
        avoidCliffsCheck.a = false;
        cliffBufferEntry.settext("1");
        maxBranchesEntry.settext("");
        maxDistanceEntry.settext("");
        maxBranchDistanceEntry.settext("");
    }

    private void saveCurrentRoute() {
        if (currentRoute == null) return;
        currentRoute.cliffBufferTiles = parseIntOrDefault(cliffBufferEntry.text(), 1);
        currentRoute.maxBranches = parseIntOrNoCap(maxBranchesEntry.text());
        currentRoute.maxDistance = parseIntOrNoCap(maxDistanceEntry.text());
        currentRoute.maxBranchDistance = parseIntOrNoCap(maxBranchDistanceEntry.text());
        try {
            ForagerRouteStore.save(currentRoute);
            routeMap.markClean();
        } catch (Exception e) {
            NUtils.getGameUI().error("Failed to save route: " + e.getMessage());
        }
    }

    /** Blank or unparseable text -&gt; -1 (no cap). */
    private int parseIntOrNoCap(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            return Math.max(v, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Blank/unparseable/negative text -&gt; the given default - for fields where 0 is itself meaningful. */
    private int parseIntOrDefault(String text, int def) {
        try {
            int v = Integer.parseInt(text.trim());
            return v >= 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** Pushes the brush-size field's value into routeMap - a pure editing-tool preference, not persisted route data. */
    private void applyBrushSize() {
        if (routeMap == null) return;
        int v;
        try {
            v = Integer.parseInt(brushSizeEntry.text().trim());
        } catch (Exception e) {
            v = ForagerRouteMap.DEFAULT_BRUSH_SIZE_TILES;
        }
        routeMap.setBrushSizeTiles(v);
    }

    private void addRoute() {
        promptForName("forager.settings.new_route_title", "forager.settings.new_route_prompt", trimmed -> {
            ForagerPath route = new ForagerPath(trimmed);
            try {
                ForagerRouteStore.save(route);
            } catch (Exception e) {
                NUtils.getGameUI().error("Failed to create route: " + e.getMessage());
                return;
            }
            if (!routeNames.contains(trimmed)) {
                routeNames.add(trimmed);
                Collections.sort(routeNames);
            }
            routeDropbox.change(trimmed);
        });
    }

    private void deleteRoute() {
        if (routeDropbox.sel == null) return;
        try {
            ForagerRouteStore.delete(routeDropbox.sel);
        } catch (Exception e) {
            NUtils.getGameUI().error("Failed to delete route: " + e.getMessage());
        }
        routeNames.remove(routeDropbox.sel);
        // Null out before switching so auto-save-outgoing-route logic doesn't resurrect the deleted file.
        currentRoute = null;
        if (routeNames.isEmpty()) {
            clearRouteUI();
        } else {
            routeDropbox.change(routeNames.get(0));
        }
    }

    private void addProfile() {
        if (prop == null) return;
        addNamedEntry("forager.settings.new_profile_title", "forager.settings.new_profile_prompt",
                prop.actionsProfiles, ArrayList::new, name -> prop.currentActionsProfile = name, actionsProfileDropbox);
    }

    private void deleteProfile() {
        if (prop == null) return;
        deleteNamedEntry(prop.actionsProfiles, actionsProfileDropbox, name -> prop.currentActionsProfile = name);
    }

    private void addGuardingProfile() {
        if (prop == null) return;
        addNamedEntry("forager.settings.new_guarding_profile_title", "forager.settings.new_guarding_profile_prompt",
                prop.guardingProfiles, GuardingProfile::withDefaults, name -> prop.currentGuardingProfile = name, guardingProfileDropbox);
    }

    private void deleteGuardingProfile() {
        if (prop == null) return;
        deleteNamedEntry(prop.guardingProfiles, guardingProfileDropbox, name -> prop.currentGuardingProfile = name);
    }

    /** Shared "new named entry" flow for the Actions/Guarding/Presets sections' add buttons. */
    private <V> void addNamedEntry(String titleKey, String promptKey, Map<String, V> map,
                                    java.util.function.Supplier<V> defaultValue,
                                    java.util.function.Consumer<String> setCurrent, Dropbox<String> dropbox) {
        promptForName(titleKey, promptKey, trimmed -> {
            map.putIfAbsent(trimmed, defaultValue.get());
            setCurrent.accept(trimmed);
            dropbox.change(trimmed);
        });
    }

    /** Shared "prompt for a non-blank name" flow underlying every Add button in this panel - the
     *  actual add semantics (Map-backed profile vs. file-backed route) differ enough per caller
     *  that only this boilerplate (not the whole add flow) is worth sharing. */
    private void promptForName(String titleKey, String promptKey, java.util.function.Consumer<String> onNamed) {
        TextInputWindow win = new TextInputWindow(
                L10n.get(titleKey), L10n.get(promptKey), name -> {
            if (name != null && !name.trim().isEmpty()) {
                onNamed.accept(name.trim());
            }
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    /** Shared "delete selected entry" flow; refuses to drop the last remaining entry. */
    private <V> void deleteNamedEntry(Map<String, V> map, Dropbox<String> dropbox, java.util.function.Consumer<String> setCurrent) {
        if (dropbox.sel == null || map.size() <= 1) return;
        map.remove(dropbox.sel);
        String next = map.keySet().iterator().next();
        setCurrent.accept(next);
        dropbox.change(next);
    }

    /** Opens the per-species aggression-radius editor in its own floating window. */
    private void openRingSettings() {
        NRingSettings ring = new NRingSettings();
        // Purely local popup - override close since there's no server-side widget for the caption bar's close button to route to.
        Window win = new Window(ring.sz, L10n.get("rings.settings_title")) {
            @Override
            public void wdgmsg(String msg, Object... args) {
                if (msg.equals("close")) {
                    hide();
                    destroy();
                } else {
                    super.wdgmsg(msg, args);
                }
            }
        };
        win.add(ring, Coord.z);
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    /** Saves the currently selected profile to a JSON file. */
    private void exportProfile() {
        if (prop == null || actionsProfileDropbox.sel == null) return;
        String name = actionsProfileDropbox.sel;
        ArrayList<ForagerAction> actions = prop.actionsProfiles.get(name);
        if (actions == null) return;

        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Forager actions profile", "json"));
            fc.setSelectedFile(new File(name + ".json"));
            if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;

            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".json")) {
                file = new File(file.getAbsolutePath() + ".json");
            }
            try {
                NForagerProp.exportActionsProfile(name, actions, file);
                NUtils.getGameUI().msg(L10n.get("forager.settings.export_success"));
            } catch (Exception e) {
                NUtils.getGameUI().error("Failed to export actions profile: " + e.getMessage());
            }
        });
    }

    /** Loads a profile saved by {@link #exportProfile()} as a new profile, never overwriting an existing one. */
    private void importProfile() {
        if (prop == null) return;

        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Forager actions profile", "json"));
            if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;

            File file = fc.getSelectedFile();
            if (file == null) return;
            try {
                NForagerProp.ImportedActionsProfile imported =
                        NForagerProp.importActionsProfile(file, prop.actionsProfiles.keySet());
                prop.actionsProfiles.put(imported.name, imported.actions);
                prop.currentActionsProfile = imported.name;
                actionsProfileDropbox.change(imported.name);
            } catch (Exception e) {
                NUtils.getGameUI().error("Failed to import actions profile: " + e.getMessage());
            }
        });
    }
}
