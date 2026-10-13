package nurgling.widgets;

import haven.*;
import nurgling.areas.AreaPreset;

import java.util.function.Consumer;

import static nurgling.i18n.L10n.get;

/**
 * Edits the description of one of the player's own presets. An empty area preset description starts
 * from the same outline the built-in presets use, with a line for each of the preset's areas.
 */
public class NAreaPresetDescriptionDialog extends Window
{
    private final NTextArea text;
    private final Consumer<String> onSave;

    private NAreaPresetDescriptionDialog(String title, String hint, String initial, Consumer<String> onSave)
    {
        super(UI.scale(new Coord(380, 260)), title);
        this.onSave = onSave;
        Widget prev = add(new Label(hint), Coord.z);
        text = add(new NTextArea(UI.scale(new Coord(380, 230)), initial), prev.pos("bl").adds(0, 4));
        Button ok = add(new Button(UI.scale(80), get("edit.save")) {
            @Override
            public void click() {
                save();
            }
        }, text.pos("bl").adds(0, 8));
        add(new Button(UI.scale(80), get("edit.cancel")) {
            @Override
            public void click() {
                reqdestroy();
            }
        }, ok.pos("ur").adds(5, 0));
        pack();
    }

    public static void open(UI ui, AreaPreset preset, Consumer<String> onSave)
    {
        open(ui, get("area.preset.description_title", preset.name), get("area.preset.description_hint"),
             preset.description.isEmpty() ? template(preset) : preset.description, onSave);
    }

    /** Edits any preset's description: {@code hint} is shown above the text. */
    public static void open(UI ui, String title, String hint, String initial, Consumer<String> onSave)
    {
        NAreaPresetDescriptionDialog dlg = new NAreaPresetDescriptionDialog(title, hint, initial, onSave);
        ui.gui.add(dlg, ui.gui.sz.div(2).sub(dlg.sz.div(2)));
        dlg.setfocus(dlg.text);
        dlg.raise();
    }

    /** The built-in presets' outline, with one line per area to fill in. */
    static String template(AreaPreset preset)
    {
        StringBuilder sb = new StringBuilder(get("area.preset.template_purpose"));
        sb.append("\n\n").append(get("area.preset.template_bots")).append("\n• \n\n")
          .append(get("area.preset.template_areas"));
        for (AreaPreset.Entry e : preset.areas)
            sb.append("\n• ").append(e.name).append(": ");
        return sb.toString();
    }

    private void save()
    {
        reqdestroy();
        onSave.accept(text.text().trim());
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
