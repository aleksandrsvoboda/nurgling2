package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;
import nurgling.widgets.ForagerPickupContainer;
import nurgling.widgets.TextInputWindow;

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

        Widget prev = add(new Label(L10n.get("forager.settings.actions_help"), UI.scale(400)), UI.scale(10, 40));

        prev = add(new Label(L10n.get("forager.settings.actions_profile")), prev.pos("bl").add(UI.scale(0, 12)));

        Widget profileRow = add(new Widget(new Coord(UI.scale(300), UI.scale(20))), prev.pos("bl").add(UI.scale(0, 5)));
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

        prev = add(pickupContainer = new ForagerPickupContainer(), profileRow.pos("bl").add(UI.scale(0, 10)));
        pickupContainer.resize(UI.scale(new Coord(400, 320)));

        Widget pickupButtonsRow = add(new Widget(new Coord(UI.scale(300), UI.scale(24))), prev.pos("bl").add(UI.scale(0, 5)));
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
}
