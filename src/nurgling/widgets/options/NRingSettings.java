package nurgling.widgets.options;

import haven.Label;
import haven.*;
import nurgling.NConfig;
import nurgling.NUtils;
import nurgling.conf.NAreaRad;
import nurgling.i18n.L10n;
import nurgling.widgets.nsettings.Panel;

import java.util.ArrayList;

public class NRingSettings extends Panel {

    // Re-pushed through NConfig.set() after every edit (see persistRadProps) rather than relying on needUpdate(), since NConfig.get() returns a session-local copy separate from the saved profile config.
    private final ArrayList<NAreaRad> radProps;

    public NRingSettings() {
        final int margin = UI.scale(10);

        prev = add(new Label(L10n.get("rings.settings_title")), new Coord(margin, margin));
        radProps = ((ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad));
        for (NAreaRad prop : radProps)
        {
            prev = add(new ElementSettings(prop, UI.scale(320), UI.scale(22)), prev.pos("bl").adds(0, 5));
        }
        pack();
    }

    private void persistRadProps() {
        NConfig.set(NConfig.Key.animalrad, radProps);
    }

    public class ElementSettings extends Widget {
        final NAreaRad rad;
        final int itemHeight;

        CheckBox visBox;
        CheckBox dangerBox;
        Label nameLabel;
        TextEntry radEntry;

        public ElementSettings(NAreaRad rad, int width, int height) {
            super(new Coord(width, height));
            this.rad = rad;
            this.itemHeight = height;

            int checkX = 0;
            int dangerX = UI.scale(20);
            int labelX = UI.scale(44);
            int entryX = UI.scale(190);

            visBox = add(new CheckBox("") {
                {
                    a = rad.vis;
                }
                @Override
                public void changed(boolean val) {
                    super.changed(val);
                    rad.vis = val;
                    persistRadProps();
                }
            }, new Coord(checkX, (itemHeight - UI.scale(16)) / 2));

            // Independent of visBox - whether DangerousAnimalTrigger treats this ring as a threat, not just whether it's drawn.
            dangerBox = add(new CheckBox("") {
                {
                    a = rad.dangerous;
                }
                @Override
                public void changed(boolean val) {
                    super.changed(val);
                    rad.dangerous = val;
                    persistRadProps();
                }
            }, new Coord(dangerX, (itemHeight - UI.scale(16)) / 2));
            dangerBox.settip(L10n.get("rings.settings_dangerous_tip"));

            nameLabel = add(new Label(rad.name), new Coord(labelX, (itemHeight - UI.scale(16)) / 2));

            radEntry = add(new TextEntry(UI.scale(80), String.valueOf(rad.radius)) {
                @Override
                public void done(ReadLine buf) {
                    super.done(buf);
                    // Chat message on success/failure so accepting (or rejecting) the value is visible.
                    try {
                        int newRadius = Integer.parseInt(buf.line().trim());
                        rad.radius = newRadius;
                        persistRadProps();
                        NUtils.getGameUI().msg("Ring settings: " + rad.name + " radius set to " + newRadius);
                    } catch (Exception e) {
                        NUtils.getGameUI().error("Ring settings: invalid radius \"" + buf.line() + "\"");
                    }
                }
            }, new Coord(entryX, (itemHeight - UI.scale(16)) / 2));

            resize(new Coord(width, itemHeight));
        }

        @Override
        public void resize(Coord sz) {
            super.resize(sz);
            int cy = (itemHeight - UI.scale(16)) / 2;
            if (visBox != null)
                visBox.move(new Coord(0, cy));
            if (dangerBox != null)
                dangerBox.move(new Coord(UI.scale(20), cy));
            if (nameLabel != null)
                nameLabel.move(new Coord(UI.scale(44), cy));
            if (radEntry != null)
                radEntry.move(new Coord(UI.scale(190), cy));
        }
    }
}
