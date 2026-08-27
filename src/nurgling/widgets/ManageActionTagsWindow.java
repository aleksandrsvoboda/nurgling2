package nurgling.widgets;

import haven.*;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;

import java.util.ArrayList;

/**
 * Small popup managing Forager's shared, growable vocabulary of flower-menu action labels (see
 * {@link NForagerProp#getActionTags()}) - add a new one, or remove one. This is the only place
 * new labels are introduced; a pickup item's own right-click menu only ever picks among whatever
 * already exists here. Removing one only stops it being offered going forward - it doesn't touch
 * any pickup entry that already has it applied.
 */
public class ManageActionTagsWindow extends Window {

    private final Listbox<String> tagList;

    public ManageActionTagsWindow() {
        super(UI.scale(new Coord(220, 250)), L10n.get("forager.settings.manage_actions_title"));

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

        prev = add(new Button(UI.scale(85), L10n.get("iconitem.new_action")) {
            @Override
            public void click() {
                super.click();
                promptAdd();
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));

        add(new Button(UI.scale(85), L10n.get("iconitem.delete")) {
            @Override
            public void click() {
                super.click();
                if (tagList.sel != null) {
                    NForagerProp.removeActionTag(tagList.sel);
                    tagList.change(null);
                }
            }
        }, prev.pos("ur").adds(UI.scale(10), 0));

        pack();
    }

    private void promptAdd() {
        TextInputWindow win = new TextInputWindow(
                L10n.get("forager.pickup.new_action_title"), L10n.get("forager.pickup.new_action_prompt"), typed -> {
            if (typed != null && !typed.trim().isEmpty()) {
                NForagerProp.addActionTag(typed.trim());
                tagList.change(null);
            }
        });
        nurgling.NUtils.getGameUI().add(win, UI.scale(200, 200));
        win.show();
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
