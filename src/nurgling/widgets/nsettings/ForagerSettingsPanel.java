package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerAction;
import nurgling.routes.ForagerPath;
import nurgling.widgets.ForagerPickupContainer;
import nurgling.widgets.TextInputWindow;
import nurgling.widgets.options.NRingSettings;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * "Forager Settings" panel under Settings &gt; Bots. Owns editing of Forager's Actions Profiles
 * (named, independently-saved pickup-action lists - see {@link ForagerPickupContainer}, which
 * resolves each dropped/typed item's gob pattern and best-guesses its flower-menu action rather
 * than requiring one picked from a fixed list); the Forager bot-launch window only *selects* one
 * of these to run with, it no longer edits them. Routes profiles are edited the same way.
 * <p>
 * <b>Guarding section is a UI mockup only</b> - built ahead of the real data model per direct
 * request, so the layout/field set can be reviewed before wiring it up. It does not read or
 * write {@code NForagerProp}/a {@code GuardingProfile}, has no effect on the bot-launch window's
 * still-live safety dropdowns, and none of its values are consumed by {@code Forager}'s actual
 * watchdog ({@code detectThreat()}) - every field here just holds local in-memory UI state that
 * resets the next time this panel is constructed. Wiring it up (a real {@code GuardingProfile}
 * class, persistence, and hooking the watchdog's hardcoded thresholds/toggles to the selected
 * profile) is a separate, later pass.
 */
public class ForagerSettingsPanel extends Panel {

    private static final String ROUTES_DIR = "forager_paths";

    // Shared row layout for both the Routes and Guarding sections' data rows - description at
    // ROW_LABEL_X, values/units in fixed columns after that, and the row's trailing control
    // (an action dropdown in Guarding, nothing in most Routes rows) at the fixed rightmost
    // ROW_TOGGLE_X - so rows read as one consistent list instead of each hand-picking its own
    // offsets (reported live: "looking jarring", values/toggle not lining up between rows). Not
    // every row uses every column. ROUTE_VALUE_X is wider than ROW_VALUE1_X specifically for
    // Routes' brush-size/cliff-buffer rows, which need room for a long pre-existing label
    // ("Exclusion zone brush size (tiles):") that the Guarding rows' shorter descriptions don't.
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

    // Short, one-line-each instructions for the map editor below, in display order - replaces a
    // single dense paragraph per direct feedback.
    private static final String[] ROUTES_HELP_KEYS = {
            "forager.settings.routes_help_add",
            "forager.settings.routes_help_move",
            "forager.settings.routes_help_delete",
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
    // Set around load()'s own programmatic routeDropbox.change() call so that reload (including
    // the Cancel button, which calls load() directly) discards pending route edits instead of
    // auto-saving them - see routeDropbox's change() override.
    private boolean suppressRouteAutoSave = false;

    // Built lazily on first load() (see ensureRouteMapBuilt()), not in the constructor - every
    // Panel in this settings window is constructed eagerly for the whole NSettingsWindow, itself
    // built inside GameUI's own constructor, well before NUtils.getGameUI()/gui.mmap exist yet.
    // Constructing ForagerRouteMap (which needs gui.mmap.file) here would NPE on every login.
    private ForagerRouteMap routeMap;
    private TextEntry brushSizeEntry;
    private CheckBox avoidCliffsCheck;
    private TextEntry cliffBufferEntry;
    private TextEntry maxChainsEntry;
    private TextEntry maxDistanceEntry;
    private TextEntry maxChainDistanceEntry;

    // ---- Guarding (UI mockup only - see class javadoc) ----
    // "break" simply stops the bot (Results.SUCCESS() and return, no travel/logout) - the
    // reaction to actually take when a check fires; whether that check runs at all is now a
    // separate per-row enabled CheckBox (see e.g. preflightEnergyEnabledCheck below) rather
    // than a "nothing" pseudo-off entry here, per direct correction.
    private static final String[] GUARD_ACTIONS = {"break", "logout", "travel hearth"};
    private static final int CHECK_TOGGLE_X = 0;
    private static final int CHECK_LABEL_X = 24;
    // Mock profile list - names only, no per-profile threshold/toggle storage yet, so switching
    // the selection intentionally leaves every field below untouched.
    private final List<String> guardingProfileNames = new ArrayList<>(Collections.singletonList("Default"));
    private Dropbox<String> guardingProfileDropbox;
    private CheckBox waterModeCheck;
    private CheckBox ignoreBatsCheck;
    // Pre-flight (checked once before departing) vs in-flight (checked continuously while
    // running) - deliberately separate action+threshold pairs per direct request, since the
    // same check (energy/HP) can reasonably want a different threshold depending on phase.
    private CheckBox preflightEnergyEnabledCheck;
    private Dropbox<String> preflightEnergyActionDropbox;
    private TextEntry preflightEnergyThresholdEntry;
    private CheckBox preflightHpEnabledCheck;
    private Dropbox<String> preflightHpActionDropbox;
    private TextEntry preflightHpThresholdEntry;
    private CheckBox inflightEnergyEnabledCheck;
    private Dropbox<String> inflightEnergyActionDropbox;
    private TextEntry inflightEnergyThresholdEntry;
    private CheckBox inflightHpEnabledCheck;
    private Dropbox<String> inflightHpActionDropbox;
    private TextEntry inflightHpThresholdEntry;
    private CheckBox stuckEnabledCheck;
    private Dropbox<String> stuckActionDropbox;
    private TextEntry stuckDistanceEntry;
    private TextEntry stuckTimeoutEntry;
    private CheckBox unknownPlayerEnabledCheck;
    private Dropbox<String> unknownPlayerActionDropbox;
    private CheckBox animalEnabledCheck;
    private Dropbox<String> onAnimalActionDropbox;

    private Scrollport scroll;
    private CollapsibleSection routesSection;
    private Widget routesContent;
    private Widget mapAnchor;

    // Every top-level CollapsibleSection, in display order - collapsing/expanding one only
    // toggles its own content's visibility (CollapsibleSection.content.visible) and shrinks/grows
    // that section's own wrapper; nothing below automatically moves up/down to close the gap or
    // avoid overlapping. relayoutSections() below does that explicitly, and must run after any
    // section's toggle (not just a scroll-range refresh) or after any section's content changes
    // height (e.g. ensureRouteMapBuilt() adding the map widget after construction).
    private final List<CollapsibleSection> sections = new ArrayList<>();

    public ForagerSettingsPanel() {
        super(L10n.get("nsettings.item.forager"));

        // This panel's content (help text + profile row + pickup grid + its button row) already
        // runs past the panel's own 580x580 budget, and later phases (Routes/Guarding) are meant
        // to keep adding to it in the same place - a scrollable viewport rather than a fixed
        // layout, so it degrades to "scroll" instead of "off the bottom of the window" as it grows.
        scroll = add(new Scrollport(UI.scale(new Coord(560, 530))), UI.scale(10, 40));
        Widget cont = scroll.cont;

        // Each of this panel's logically-separate groups (Actions here; Routes/Guarding in later
        // phases) gets its own collapsible section, so the page stays navigable once all three
        // exist rather than always showing everything at once.
        CollapsibleSection actionsSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.actions_section"), UI.scale(540), true), Coord.z);
        actionsSection.setOnToggle(this::relayoutSections);
        sections.add(actionsSection);
        Widget sec = actionsSection.content;

        Widget prev = sec.add(new Label(L10n.get("forager.settings.actions_help"), UI.scale(400)), Coord.z);

        prev = sec.add(new Label(L10n.get("forager.settings.actions_profile")), prev.pos("bl").add(UI.scale(0, 12)));

        Widget profileRow = sec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), prev.pos("bl").add(UI.scale(0, 5)));
        profileRow.add(actionsProfileDropbox = new Dropbox<String>(UI.scale(200), 8, UI.scale(16)) {
            private List<String> names() {
                return prop != null ? new ArrayList<>(new TreeSet<>(prop.actionsProfiles.keySet())) : Collections.emptyList();
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

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null && prop != null) {
                    prop.currentActionsProfile = item;
                    pickupContainer.load(prop.actionsProfiles.get(item));
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
        routesSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.routes_section"), UI.scale(540), true), actionsSection.pos("bl").add(UI.scale(0, 10)));
        routesSection.setOnToggle(this::relayoutSections);
        sections.add(routesSection);
        Widget rsec = routesContent = routesSection.content;

        Widget rprev = null;
        for (String key : ROUTES_HELP_KEYS) {
            rprev = rsec.add(new Label("• " + L10n.get(key)), rprev == null ? Coord.z : rprev.pos("bl").add(UI.scale(0, 3)));
        }

        rprev = rsec.add(new Label(L10n.get("forager.settings.route")), rprev.pos("bl").add(UI.scale(0, 12)));

        Widget routeRow = rsec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), rprev.pos("bl").add(UI.scale(0, 5)));
        routeRow.add(routeDropbox = new Dropbox<String>(UI.scale(200), 8, UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return routeNames.get(i);
            }

            @Override
            protected int listitems() {
                return routeNames.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null) {
                    // Switching which route is being edited must not silently drop in-progress
                    // edits (waypoints/exclusion brush/caps) to the one being switched away from -
                    // only the panel's Save button persists "whatever currentRoute currently is",
                    // and loadRoute() below replaces that reference with a fresh disk-load.
                    // Suppressed during load()'s own programmatic selection (Cancel calls load(),
                    // which must discard rather than save, matching every other panel's Cancel
                    // semantics) and skipped entirely when nothing's actually changing.
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

        // Brush/cliff rows share one value column (ROUTE_VALUE_X) wide enough for the longer
        // of the two labels below, so their TextEntry boxes line up with each other - per
        // direct feedback to align these fields instead of each row picking its own offset.
        Widget brushRow = rsec.add(new Widget(new Coord(UI.scale(ROW_W), UI.scale(ROW_H))), routeRow.pos("bl").add(UI.scale(0, 10)));
        rowItem(brushRow, new Label(L10n.get("forager.settings.brush_size")), UI.scale(ROW_LABEL_X));
        brushSizeEntry = rowItem(brushRow, new TextEntry(UI.scale(ENTRY_W), String.valueOf(ForagerRouteMap.DEFAULT_BRUSH_SIZE_TILES)) {
            @Override
            public void done(ReadLine buf) {
                super.done(buf);
                applyBrushSize();
            }
        }, UI.scale(ROUTE_VALUE_X));

        // "Avoid cliffs"/max chains-distance-chain distance were briefly moved into the
        // Guarding section, then moved back here per direct follow-up correction - they're
        // route geometry (per the *currently selected route*), not bot-behavior/safety
        // settings, even though Guarding also exposes reaction thresholds.
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

        // Kept at their original (proven-fitting) offsets - the longest label ("Max chain
        // distance:") needs more room than an even 3-way split of ROW_W leaves, so this row
        // doesn't share the brush/cliff rows' single ROUTE_VALUE_X column above; rowItem below
        // still fixes vertical centering the same as everywhere else.
        Widget capsRow = rsec.add(new Widget(new Coord(UI.scale(560), UI.scale(ROW_H))), cliffRow.pos("bl").add(UI.scale(0, 8)));
        rowItem(capsRow, new Label(L10n.get("forager.settings.max_chains")), UI.scale(0));
        maxChainsEntry = rowItem(capsRow, new TextEntry(UI.scale(ENTRY_W), ""), UI.scale(80));
        rowItem(capsRow, new Label(L10n.get("forager.settings.max_distance")), UI.scale(160));
        maxDistanceEntry = rowItem(capsRow, new TextEntry(UI.scale(ENTRY_W), ""), UI.scale(280));
        rowItem(capsRow, new Label(L10n.get("forager.settings.max_chain_distance")), UI.scale(360));
        maxChainDistanceEntry = rowItem(capsRow, new TextEntry(UI.scale(ENTRY_W), ""), UI.scale(500));

        mapAnchor = capsRow;

        // ForagerRouteMap itself (needs gui.mmap.file, not available yet here) is built lazily -
        // see ensureRouteMapBuilt(), called from load().
        routesSection.pack();

        // ---- Guarding (UI mockup only - see class javadoc) ----
        CollapsibleSection guardingSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.guarding_section"), UI.scale(540), true), routesSection.pos("bl").add(UI.scale(0, 10)));
        guardingSection.setOnToggle(this::relayoutSections);
        sections.add(guardingSection);
        Widget gsec = guardingSection.content;

        Widget gprev = gsec.add(new Label(L10n.get("forager.settings.guarding_help"), UI.scale(400)), Coord.z);

        gprev = gsec.add(new Label(L10n.get("forager.settings.guarding_profile")), gprev.pos("bl").add(UI.scale(0, 12)));
        Widget guardingProfileRow = gsec.add(new Widget(new Coord(UI.scale(360), UI.scale(20))), gprev.pos("bl").add(UI.scale(0, 5)));
        guardingProfileRow.add(guardingProfileDropbox = new Dropbox<String>(UI.scale(200), 8, UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return guardingProfileNames.get(i);
            }

            @Override
            protected int listitems() {
                return guardingProfileNames.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, new Coord(0, 0));
        guardingProfileDropbox.change(guardingProfileNames.get(0));

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

        // Each check row has its own enabled CheckBox (fully off when unchecked, per direct
        // correction - a "nothing" pseudo-off entry in the action dropdown was removed) plus an
        // action dropdown for what to do when it fires: "break" (just stops the bot),
        // "logout", or "travel hearth". Split into Pre-flight (checked once before departing)
        // and In-flight (checked continuously while running) per direct request: the same
        // underlying check can reasonably want a different threshold depending on phase - a
        // route that starts a little low on energy might be fine, an in-flight drop to that
        // same level might not be. Every row uses the same rowItem()-vertically-centered,
        // fixed-column layout (checkbox/description/value(s)/action dropdown) so the whole
        // section reads as one consistent list instead of each row picking its own offsets.
        Widget preflightLabel = gsec.add(new Label(L10n.get("forager.settings.preflight_checks")), toggleRow.pos("bl").add(UI.scale(0, 14)));

        Coord rowSz = new Coord(UI.scale(ROW_W), UI.scale(ROW_H));

        Widget preflightEnergyRow = gsec.add(new Widget(rowSz), preflightLabel.pos("bl").add(UI.scale(0, 6)));
        preflightEnergyEnabledCheck = rowItem(preflightEnergyRow, new CheckBox(""), UI.scale(CHECK_TOGGLE_X));
        preflightEnergyEnabledCheck.a = true;
        rowItem(preflightEnergyRow, new Label(L10n.get("forager.settings.low_energy_below")), UI.scale(CHECK_LABEL_X));
        preflightEnergyThresholdEntry = rowItem(preflightEnergyRow, new TextEntry(UI.scale(ENTRY_W), "22"), UI.scale(ROW_VALUE1_X));
        rowItem(preflightEnergyRow, new Label("%"), UI.scale(ROW_UNIT1_X));
        preflightEnergyActionDropbox = rowItem(preflightEnergyRow, buildGuardActionDropbox(), UI.scale(ROW_TOGGLE_X));

        Widget preflightHpRow = gsec.add(new Widget(rowSz), preflightEnergyRow.pos("bl").add(UI.scale(0, 4)));
        preflightHpEnabledCheck = rowItem(preflightHpRow, new CheckBox(""), UI.scale(CHECK_TOGGLE_X));
        preflightHpEnabledCheck.a = true;
        rowItem(preflightHpRow, new Label(L10n.get("forager.settings.low_hp_below")), UI.scale(CHECK_LABEL_X));
        preflightHpThresholdEntry = rowItem(preflightHpRow, new TextEntry(UI.scale(ENTRY_W), "50"), UI.scale(ROW_VALUE1_X));
        rowItem(preflightHpRow, new Label("%"), UI.scale(ROW_UNIT1_X));
        preflightHpActionDropbox = rowItem(preflightHpRow, buildGuardActionDropbox(), UI.scale(ROW_TOGGLE_X));

        Widget inflightLabel = gsec.add(new Label(L10n.get("forager.settings.inflight_checks")), preflightHpRow.pos("bl").add(UI.scale(0, 14)));

        Widget inflightEnergyRow = gsec.add(new Widget(rowSz), inflightLabel.pos("bl").add(UI.scale(0, 6)));
        inflightEnergyEnabledCheck = rowItem(inflightEnergyRow, new CheckBox(""), UI.scale(CHECK_TOGGLE_X));
        inflightEnergyEnabledCheck.a = true;
        rowItem(inflightEnergyRow, new Label(L10n.get("forager.settings.low_energy_below")), UI.scale(CHECK_LABEL_X));
        inflightEnergyThresholdEntry = rowItem(inflightEnergyRow, new TextEntry(UI.scale(ENTRY_W), "22"), UI.scale(ROW_VALUE1_X));
        rowItem(inflightEnergyRow, new Label("%"), UI.scale(ROW_UNIT1_X));
        inflightEnergyActionDropbox = rowItem(inflightEnergyRow, buildGuardActionDropbox(), UI.scale(ROW_TOGGLE_X));

        Widget inflightHpRow = gsec.add(new Widget(rowSz), inflightEnergyRow.pos("bl").add(UI.scale(0, 4)));
        inflightHpEnabledCheck = rowItem(inflightHpRow, new CheckBox(""), UI.scale(CHECK_TOGGLE_X));
        inflightHpEnabledCheck.a = true;
        rowItem(inflightHpRow, new Label(L10n.get("forager.settings.low_hp_below")), UI.scale(CHECK_LABEL_X));
        inflightHpThresholdEntry = rowItem(inflightHpRow, new TextEntry(UI.scale(ENTRY_W), "50"), UI.scale(ROW_VALUE1_X));
        rowItem(inflightHpRow, new Label("%"), UI.scale(ROW_UNIT1_X));
        inflightHpActionDropbox = rowItem(inflightHpRow, buildGuardActionDropbox(), UI.scale(ROW_TOGGLE_X));

        Widget stuckRow = gsec.add(new Widget(rowSz), inflightHpRow.pos("bl").add(UI.scale(0, 4)));
        stuckEnabledCheck = rowItem(stuckRow, new CheckBox(""), UI.scale(CHECK_TOGGLE_X));
        stuckEnabledCheck.a = true;
        rowItem(stuckRow, new Label(L10n.get("forager.settings.stuck_moved_less_than")), UI.scale(CHECK_LABEL_X));
        stuckDistanceEntry = rowItem(stuckRow, new TextEntry(UI.scale(ENTRY_W), "3"), UI.scale(ROW_VALUE1_X));
        rowItem(stuckRow, new Label(L10n.get("forager.settings.stuck_tiles_in")), UI.scale(ROW_UNIT1_X));
        stuckTimeoutEntry = rowItem(stuckRow, new TextEntry(UI.scale(ENTRY_W), "10"), UI.scale(ROW_VALUE2_X));
        rowItem(stuckRow, new Label(L10n.get("forager.settings.stuck_seconds")), UI.scale(ROW_UNIT2_X));
        stuckActionDropbox = rowItem(stuckRow, buildGuardActionDropbox(), UI.scale(ROW_TOGGLE_X));

        Widget unknownPlayerRow = gsec.add(new Widget(rowSz), stuckRow.pos("bl").add(UI.scale(0, 4)));
        unknownPlayerEnabledCheck = rowItem(unknownPlayerRow, new CheckBox(""), UI.scale(CHECK_TOGGLE_X));
        unknownPlayerEnabledCheck.a = true;
        rowItem(unknownPlayerRow, new Label(L10n.get("forager.settings.unknown_player_nearby")), UI.scale(CHECK_LABEL_X));
        unknownPlayerActionDropbox = rowItem(unknownPlayerRow, buildGuardActionDropbox(), UI.scale(ROW_TOGGLE_X));

        // Aggression Radii button sits in the same value column other rows use for their
        // numeric entries, so the action dropdown stays aligned at the far right like every
        // other row - same row as the animal check, per direct request. rowItem() (rather than
        // a fixed y offset) is what actually fixes the button being "cut off"/misaligned - a
        // Button renders taller than a Label/TextEntry/Dropbox, so it needs to be vertically
        // centered against the row like everything else instead of pinned to the same y=0/y=4
        // baseline that happened to work for the shorter widgets.
        Widget animalRow = gsec.add(new Widget(rowSz), unknownPlayerRow.pos("bl").add(UI.scale(0, 4)));
        animalEnabledCheck = rowItem(animalRow, new CheckBox(""), UI.scale(CHECK_TOGGLE_X));
        animalEnabledCheck.a = true;
        rowItem(animalRow, new Label(L10n.get("forager.settings.dangerous_animal_nearby")), UI.scale(CHECK_LABEL_X));
        rowItem(animalRow, new Button(UI.scale(150), L10n.get("forager.settings.aggression_radii"), this::openRingSettings), UI.scale(ROW_VALUE1_X));
        onAnimalActionDropbox = rowItem(animalRow, buildGuardActionDropbox(), UI.scale(ROW_TOGGLE_X));

        guardingSection.pack();

        relayoutSections();
    }

    /** Adds child to row, vertically centered against the row's own declared height regardless
     *  of the child's actual rendered height - a plain fixed y offset works fine for
     *  same-height siblings (e.g. two Labels) but clips or misaligns anything taller, like a
     *  Button next to a Label/TextEntry/Dropbox (reported live: a button "kinda cut off ...
     *  doesn't align"). x is the child's left edge. */
    private <T extends Widget> T rowItem(Widget row, T child, int x) {
        return row.adda(child, new Coord(x, row.sz.y / 2), 0.0, 0.5);
    }

    /** Shared builder for the nothing/logout/travel hearth action dropdown used by every
     *  guarding check row above - avoids repeating the same Dropbox&lt;String&gt; boilerplate
     *  five separate times for what's always the same fixed option list. */
    private Dropbox<String> buildGuardActionDropbox() {
        Dropbox<String> db = new Dropbox<String>(UI.scale(110), 3, UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return GUARD_ACTIONS[i];
            }

            @Override
            protected int listitems() {
                return GUARD_ACTIONS.length;
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        };
        db.change(GUARD_ACTIONS[0]);
        return db;
    }

    /** Repositions every top-level section directly below the current bottom edge of the one
     *  before it, so collapsing/expanding a section (or a section's content changing height,
     *  e.g. ensureRouteMapBuilt() adding the map widget after construction) actually moves
     *  everything below it up or down to close/open the gap - CollapsibleSection.pack() alone
     *  only resizes that one section's own wrapper, it has no idea what else is on the page. */
    private void relayoutSections() {
        Coord next = Coord.z;
        for (CollapsibleSection s : sections) {
            s.move(next);
            next = s.pos("bl").add(UI.scale(0, 10));
        }
        scroll.cont.update();
    }

    /** Builds the embedded route-editing map the first time this panel is actually opened, not
     *  in the constructor - see the field javadoc on {@link #routeMap} for why. Idempotent;
     *  safe to call on every load(). (The cliff/caps rows above it are built eagerly in the
     *  constructor instead, since unlike the map itself they don't need gui.mmap.file.) */
    private void ensureRouteMapBuilt() {
        if (routeMap != null) return;

        routeMap = routesContent.add(new ForagerRouteMap(UI.scale(new Coord(520, 360)), NUtils.getGameUI().mmap.file), mapAnchor.pos("bl").add(UI.scale(0, 10)));
        routeMap.onResetRequested = this::resetCurrentRoute;
        applyBrushSize();

        routesSection.pack();
        relayoutSections();
    }

    // MouseWheelEvent dispatch tries each widget on the way DOWN to whatever's under the cursor,
    // not the deepest one first - so the outer Scrollport (an ancestor of routeMap, several
    // levels up) always got first refusal and unconditionally consumed every wheel event for
    // page-scrolling, before routeMap (which wants it for zoom) ever saw it. This panel is
    // itself an ancestor of that Scrollport, so intercepting here - forwarding straight to
    // routeMap when the cursor is actually over it - runs before the Scrollport gets a turn.
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

        if (prop.actionsProfiles.isEmpty()) {
            prop.actionsProfiles.put("Default", new ArrayList<>());
        }
        if (prop.currentActionsProfile == null || !prop.actionsProfiles.containsKey(prop.currentActionsProfile)) {
            prop.currentActionsProfile = prop.actionsProfiles.keySet().iterator().next();
        }

        actionsProfileDropbox.change(prop.currentActionsProfile);
        pickupContainer.load(prop.actionsProfiles.get(prop.currentActionsProfile));

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
    }

    @Override
    public void save() {
        if (prop != null) {
            NForagerProp.set(prop);
        }
        saveCurrentRoute();
    }

    private void loadAvailableRoutes() {
        routeNames.clear();
        File dir = NUtils.getDataFilePath(ROUTES_DIR).toFile();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    routeNames.add(f.getName().replace(".json", ""));
                }
            }
        }
        Collections.sort(routeNames);
    }

    private void loadRoute(String name) {
        ForagerPath loaded;
        try {
            loaded = ForagerPath.load(NUtils.getDataFile(ROUTES_DIR, name + ".json"));
        } catch (Exception e) {
            loaded = new ForagerPath(name);
        }
        currentRoute = loaded;
        routeMap.setRoute(currentRoute);
        avoidCliffsCheck.a = currentRoute.avoidCliffs;
        cliffBufferEntry.settext(String.valueOf(currentRoute.cliffBufferTiles));
        maxChainsEntry.settext(currentRoute.maxChains < 0 ? "" : String.valueOf(currentRoute.maxChains));
        maxDistanceEntry.settext(currentRoute.maxDistance < 0 ? "" : String.valueOf(currentRoute.maxDistance));
        maxChainDistanceEntry.settext(currentRoute.maxChainDistance < 0 ? "" : String.valueOf(currentRoute.maxChainDistance));
    }

    /** Discards any unsaved in-memory edits (waypoints, exclusion paint, caps) to the currently
     *  selected route by reloading it fresh from disk. A scoped "Cancel" for just the route being
     *  edited - the panel-wide Cancel button reloads the whole panel and resets route selection
     *  back to the alphabetically-first route rather than the one actually being worked on. */
    private void resetCurrentRoute() {
        if (routeDropbox.sel == null) return;
        loadRoute(routeDropbox.sel);
    }

    private void clearRouteUI() {
        currentRoute = null;
        routeMap.setRoute(null);
        avoidCliffsCheck.a = false;
        cliffBufferEntry.settext("1");
        maxChainsEntry.settext("");
        maxDistanceEntry.settext("");
        maxChainDistanceEntry.settext("");
    }

    private void saveCurrentRoute() {
        if (currentRoute == null) return;
        currentRoute.cliffBufferTiles = parseIntOrDefault(cliffBufferEntry.text(), 1);
        currentRoute.maxChains = parseIntOrNoCap(maxChainsEntry.text());
        currentRoute.maxDistance = parseIntOrNoCap(maxDistanceEntry.text());
        currentRoute.maxChainDistance = parseIntOrNoCap(maxChainDistanceEntry.text());
        try {
            currentRoute.save(NUtils.getDataFile(ROUTES_DIR));
            routeMap.markClean();
        } catch (Exception e) {
            NUtils.getGameUI().error("Failed to save route: " + e.getMessage());
        }
    }

    /** Blank or unparseable text -&gt; -1 (no cap), same sentinel convention as
     *  NForagerProp.PresetData.startAreaId - matches the AutoLogoutSettings/StarvationAlertSettings
     *  nsettings convention of a blank-initial-text TextEntry rather than a stringified "0"/"-1". */
    private int parseIntOrNoCap(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            return Math.max(v, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Blank/unparseable/negative text -&gt; the given default - for fields like
     *  ForagerPath.cliffBufferTiles where 0 is itself a meaningful value (unlike
     *  parseIntOrNoCap's -1 "no cap" sentinel above), so there's no single sentinel to fall
     *  back to. */
    private int parseIntOrDefault(String text, int def) {
        try {
            int v = Integer.parseInt(text.trim());
            return v >= 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** Pushes the brush-size field's value into routeMap - a pure editing-tool preference (not
     *  route data, unlike max chains/distance below), so not persisted anywhere; falls back to
     *  the default on blank/unparseable input rather than -1/no-cap (a brush needs a real size). */
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
        TextInputWindow win = new TextInputWindow(
                L10n.get("forager.settings.new_route_title"), L10n.get("forager.settings.new_route_prompt"), name -> {
            if (name != null && !name.trim().isEmpty()) {
                String trimmed = name.trim();
                ForagerPath route = new ForagerPath(trimmed);
                try {
                    route.save(NUtils.getDataFile(ROUTES_DIR));
                } catch (Exception e) {
                    NUtils.getGameUI().error("Failed to create route: " + e.getMessage());
                    return;
                }
                if (!routeNames.contains(trimmed)) {
                    routeNames.add(trimmed);
                    Collections.sort(routeNames);
                }
                routeDropbox.change(trimmed);
            }
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    private void deleteRoute() {
        if (routeDropbox.sel == null) return;
        try {
            Files.deleteIfExists(NUtils.getDataFilePath(ROUTES_DIR, routeDropbox.sel + ".json"));
        } catch (Exception e) {
            NUtils.getGameUI().error("Failed to delete route: " + e.getMessage());
        }
        routeNames.remove(routeDropbox.sel);
        // Null out before switching so the dropbox's auto-save-outgoing-route logic doesn't
        // resurrect the file we just deleted.
        currentRoute = null;
        if (routeNames.isEmpty()) {
            clearRouteUI();
        } else {
            routeDropbox.change(routeNames.get(0));
        }
    }

    private void addProfile() {
        if (prop == null) return;
        TextInputWindow win = new TextInputWindow(
                L10n.get("forager.settings.new_profile_title"), L10n.get("forager.settings.new_profile_prompt"), name -> {
            if (name != null && !name.trim().isEmpty()) {
                String trimmed = name.trim();
                prop.actionsProfiles.putIfAbsent(trimmed, new ArrayList<>());
                prop.currentActionsProfile = trimmed;
                actionsProfileDropbox.change(trimmed);
            }
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    private void deleteProfile() {
        if (prop == null || actionsProfileDropbox.sel == null) return;
        if (prop.actionsProfiles.size() <= 1) {
            // Always keep at least one profile to select at bot start.
            return;
        }
        prop.actionsProfiles.remove(actionsProfileDropbox.sel);
        String next = prop.actionsProfiles.keySet().iterator().next();
        prop.currentActionsProfile = next;
        actionsProfileDropbox.change(next);
    }

    /** Mock-only for now (see class javadoc) - just tracks profile names, no per-profile
     *  threshold/toggle storage yet, so this never touches NForagerProp. */
    private void addGuardingProfile() {
        TextInputWindow win = new TextInputWindow(
                L10n.get("forager.settings.new_guarding_profile_title"), L10n.get("forager.settings.new_guarding_profile_prompt"), name -> {
            if (name != null && !name.trim().isEmpty()) {
                String trimmed = name.trim();
                if (!guardingProfileNames.contains(trimmed)) {
                    guardingProfileNames.add(trimmed);
                    Collections.sort(guardingProfileNames);
                }
                guardingProfileDropbox.change(trimmed);
            }
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    private void deleteGuardingProfile() {
        if (guardingProfileDropbox.sel == null || guardingProfileNames.size() <= 1) {
            // Always keep at least one profile to select, same rule as Actions profiles.
            return;
        }
        guardingProfileNames.remove(guardingProfileDropbox.sel);
        guardingProfileDropbox.change(guardingProfileNames.get(0));
    }

    /** Opens the existing per-species aggression-radius editor in its own floating window
     *  rather than duplicating it here - there's no "open one settings panel from inside
     *  another" mechanism in this codebase (NRingSettings is only ever opened as its own
     *  top-level NSettingsWindow entry). NRingSettings builds and packs its own content in its
     *  constructor (base Panel.load() is a no-op it doesn't override), so no separate load()
     *  call is needed here. */
    private void openRingSettings() {
        NRingSettings ring = new NRingSettings();
        // A purely local, non-server-tracked popup like ActionConfigWindow/WaypointStepsWindow -
        // the caption bar's built-in close button (Window.cbtn) sends a "close" wdgmsg up
        // looking for a server-side widget to route it to, which doesn't exist here, so without
        // this override the button did nothing (reported live - "can't close the popout").
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

    /** Saves the currently selected profile to a JSON file, same {"name", data...} shape /
     *  JFileChooser pattern as e.g. BlueprintWidget's save/load - much lighter than Area
     *  Settings' export (which also carries grid/sync data this profile type has none of). */
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
                JSONObject root = new JSONObject();
                root.put("name", name);
                JSONArray arr = new JSONArray();
                for (ForagerAction action : actions) {
                    arr.put(action.toJson());
                }
                root.put("actions", arr);
                Files.write(file.toPath(), root.toString(2).getBytes());
                NUtils.getGameUI().msg(L10n.get("forager.settings.export_success"));
            } catch (Exception e) {
                NUtils.getGameUI().error("Failed to export actions profile: " + e.getMessage());
            }
        });
    }

    /** Loads a profile saved by {@link #exportProfile()} as a new profile (never overwrites an
     *  existing one - appends " (2)", " (3)", ... on a name collision) and selects it. */
    private void importProfile() {
        if (prop == null) return;

        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Forager actions profile", "json"));
            if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;

            File file = fc.getSelectedFile();
            if (file == null) return;
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                JSONObject root = new JSONObject(content);
                JSONArray arr = root.getJSONArray("actions");
                ArrayList<ForagerAction> actions = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    actions.add(new ForagerAction(arr.getJSONObject(i)));
                }

                String baseName = root.has("name") ? root.getString("name") : file.getName().replaceFirst("\\.json$", "");
                String name = baseName;
                int suffix = 2;
                while (prop.actionsProfiles.containsKey(name)) {
                    name = baseName + " (" + suffix + ")";
                    suffix++;
                }

                prop.actionsProfiles.put(name, actions);
                prop.currentActionsProfile = name;
                actionsProfileDropbox.change(name);
            } catch (Exception e) {
                NUtils.getGameUI().error("Failed to import actions profile: " + e.getMessage());
            }
        });
    }
}
