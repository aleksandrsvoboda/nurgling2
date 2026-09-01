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

    // NConfig.get() resolves to the calling UI's own per-session config copy (see
    // NConfig.resolveConfig()) - a real, independent NConfig instance, separate from the
    // per-genus profile config NCore's save loop actually checks/writes. Editing an NAreaRad
    // in place only mutated that session copy's own list, which the profile instance never
    // saw - needUpdate() alone (even fixed to mark the right *session* config dirty) can't fix
    // that, since it's a completely different object holding a completely different list.
    // Re-pushing this same list through NConfig.set() after every edit is what actually
    // reaches the profile instance (and gets it marked dirty - see NConfig.set()'s own fix).
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
        Label nameLabel;
        TextEntry radEntry;

        public ElementSettings(NAreaRad rad, int width, int height) {
            super(new Coord(width, height));
            this.rad = rad;
            this.itemHeight = height;

            int checkX = 0;
            int labelX = UI.scale(24);
            int entryX = UI.scale(170);

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

            nameLabel = add(new Label(rad.name), new Coord(labelX, (itemHeight - UI.scale(16)) / 2));

            radEntry = add(new TextEntry(UI.scale(80), String.valueOf(rad.radius)) {
                @Override
                public void done(ReadLine buf) {
                    super.done(buf);
                    // A chat message on success makes the accept visible (pressing Enter
                    // previously gave zero feedback either way, reported live as "looks like
                    // nothing happened"); one on failure explains why nothing changed instead
                    // of silently swallowing a bad value.
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
            if (nameLabel != null)
                nameLabel.move(new Coord(UI.scale(24), cy));
            if (radEntry != null)
                radEntry.move(new Coord(UI.scale(170), cy));
        }
    }
}
