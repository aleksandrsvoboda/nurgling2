package nurgling.widgets.bots;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.widgets.AreaPicker;

import java.util.ArrayList;
import java.util.List;

/** Start window of the Area Mover for manual runs: source, destination, and the pile option. */
public class AreaMoverWnd extends Window implements Checkable {
    /** Area id meaning "let me drag a rectangle on the map". */
    public static final int SELECT_ON_MAP = -1;

    private final AreaPicker fromBox;
    private final AreaPicker toBox;
    private final CheckBox pilesBox;

    private boolean ready = false;
    private boolean started = false;

    public AreaMoverWnd(int fromAreaId, int toAreaId, boolean createPiles) {
        super(UI.scale(new Coord(220, 200)), L10n.get("areamover.wnd_title"));
        List<AreaPicker.Entry> choices = new ArrayList<>();
        choices.add(new AreaPicker.Entry(SELECT_ON_MAP, L10n.get("areamover.select_on_map"), true));
        choices.addAll(AreaPicker.savedAreas());

        prev = add(new Label(L10n.get("areamover.from")));
        prev = fromBox = add(new AreaPicker(UI.scale(220), choices, fromAreaId, null), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(new Label(L10n.get("areamover.to")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = toBox = add(new AreaPicker(UI.scale(220), choices, toAreaId, null), prev.pos("bl").add(UI.scale(0, 5)));
        pilesBox = new CheckBox(L10n.get("areamover.create_piles"));
        pilesBox.a = createPiles;
        prev = add(pilesBox, prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(new Label(L10n.get("areamover.inventory_hint")), prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(new Button(UI.scale(150), L10n.get("botwnd.start")) {
            @Override
            public void click() {
                super.click();
                started = true;
                ready = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));
        pack();
    }

    public boolean started() {
        return started;
    }

    public int fromAreaId() {
        Integer id = fromBox.selectedId();
        return id == null ? SELECT_ON_MAP : id;
    }

    public int toAreaId() {
        Integer id = toBox.selectedId();
        return id == null ? SELECT_ON_MAP : id;
    }

    public boolean createPiles() {
        return pilesBox.a;
    }

    @Override
    public boolean check() {
        return ready;
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            ready = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }
}
