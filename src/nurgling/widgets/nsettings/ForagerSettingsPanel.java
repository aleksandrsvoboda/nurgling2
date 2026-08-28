package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerAction;
import nurgling.widgets.ForagerPickupContainer;
import nurgling.widgets.TextInputWindow;
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
 * of these to run with, it no longer edits them. Routes and Guarding profiles are planned to join
 * this same panel in later phases of the same refactor.
 */
public class ForagerSettingsPanel extends Panel {

    private NForagerProp prop;
    private final Dropbox<String> actionsProfileDropbox;
    private final ForagerPickupContainer pickupContainer;

    public ForagerSettingsPanel() {
        super(L10n.get("nsettings.item.forager"));

        // This panel's content (help text + profile row + pickup grid + its button row) already
        // runs past the panel's own 580x580 budget, and later phases (Routes/Guarding) are meant
        // to keep adding to it in the same place - a scrollable viewport rather than a fixed
        // layout, so it degrades to "scroll" instead of "off the bottom of the window" as it grows.
        Scrollport scroll = add(new Scrollport(UI.scale(new Coord(560, 530))), UI.scale(10, 40));
        Widget cont = scroll.cont;

        // Each of this panel's logically-separate groups (Actions here; Routes/Guarding in later
        // phases) gets its own collapsible section, so the page stays navigable once all three
        // exist rather than always showing everything at once.
        CollapsibleSection actionsSection = cont.add(new CollapsibleSection(L10n.get("forager.settings.actions_section"), UI.scale(540), true), Coord.z);
        actionsSection.setOnToggle(scroll.cont::update);
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
    }

    @Override
    public void load() {
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
    }

    @Override
    public void save() {
        if (prop != null) {
            NForagerProp.set(prop);
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
