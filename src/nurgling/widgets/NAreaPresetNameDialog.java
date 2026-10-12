package nurgling.widgets;

import haven.*;

import java.util.function.Consumer;

import static nurgling.i18n.L10n.get;

/** Asks for an area preset's name, for "Save as preset..." and for renaming one. */
public class NAreaPresetNameDialog extends Window
{
    private final TextEntry name;
    private final Consumer<String> onSave;

    private NAreaPresetNameDialog(String title, String initial, Consumer<String> onSave)
    {
        super(UI.scale(new Coord(260, 40)), title);
        this.onSave = onSave;
        name = add(new TextEntry(UI.scale(180), initial) {
            @Override
            public void activate(String text) {
                save();
            }
        }, UI.scale(5, 5));
        Button ok = add(new Button(UI.scale(70), get("edit.save")) {
            @Override
            public void click() {
                save();
            }
        }, name.pos("ur").adds(5, -6));
        add(new Button(UI.scale(70), get("edit.cancel")) {
            @Override
            public void click() {
                reqdestroy();
            }
        }, ok.pos("bl").adds(0, 4));
        pack();
    }

    public static void open(UI ui, String title, String initial, Consumer<String> onSave)
    {
        NAreaPresetNameDialog dlg = new NAreaPresetNameDialog(title, initial, onSave);
        ui.gui.add(dlg, ui.gui.sz.div(2).sub(dlg.sz.div(2)));
        dlg.setfocus(dlg.name);
        dlg.raise();
    }

    private void save()
    {
        String n = name.text().trim();
        if (n.isEmpty())
            return;
        reqdestroy();
        onSave.accept(n);
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args)
    {
        if (msg.equals("close"))
            reqdestroy();
        else
            super.wdgmsg(sender, msg, args);
    }
}
