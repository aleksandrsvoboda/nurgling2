package nurgling.widgets;

import haven.*;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;

import java.util.ArrayList;

/**
 * Small popup listing every action label in Forager's shared, growable vocabulary (see
 * {@link NForagerProp#getActionTags()}, grown via {@link ForagerPickupContainer}'s right-click
 * "+ New Action..." option), with a way to remove one. Deleting one here only stops it being
 * offered as a menu option going forward - it doesn't touch any pickup entry that already has it
 * applied.
 */
public class ManageActionTagsWindow extends Window {

    private final Listbox<String> tagList;

    public ManageActionTagsWindow() {
        super(UI.scale(new Coord(220, 220)), L10n.get("forager.settings.manage_actions_title"));

        Widget prev = add(tagList = new Listbox<String>(UI.scale(180), 8, UI.scale(18)) {
            @Override
            protected String listitem(int i) {
                return NForagerProp.getActionTags().get(i);
            }

            @Override
            protected int listitems() {
                return NForagerProp.getActionTags().size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, UI.scale(10, 10));

        add(new Button(UI.scale(180), L10n.get("iconitem.delete")) {
            @Override
            public void click() {
                super.click();
                if (tagList.sel != null) {
                    NForagerProp.removeActionTag(tagList.sel);
                    tagList.change(null);
                }
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));

        pack();
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(msg, args);
        }
    }
}
