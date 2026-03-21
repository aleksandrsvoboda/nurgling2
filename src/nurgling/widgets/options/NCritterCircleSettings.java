package nurgling.widgets.options;

import haven.*;
import nurgling.NConfig;
import nurgling.conf.NCritterCircleConf;
import nurgling.i18n.L10n;
import nurgling.overlays.NCritterCircle;
import nurgling.widgets.NColorWidget;
import nurgling.widgets.nsettings.Panel;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NCritterCircleSettings extends Panel {

    private final List<CritterRow> rows = new ArrayList<>();

    /**
     * Mark the critter circle config as dirty so it gets saved.
     * Critter circle settings live in the global config (NConfig.current) because
     * the settings panel is constructed during mouse click (no ThreadLocalUI),
     * so all conf objects reference the global config's instances.
     * NConfig.needUpdate() marks the global config dirty, and NCore.tick() writes it.
     */
    private static void markDirty() {
        NConfig.needUpdate();
    }

    public NCritterCircleSettings() {
        super();
        int margin = UI.scale(10);

        // Scrollport for the list
        int scrollWidth = UI.scale(560);
        int scrollHeight = UI.scale(540);
        Scrollport scrollport = add(new Scrollport(new Coord(scrollWidth, scrollHeight)), new Coord(margin, margin));
        Widget content = scrollport.cont;

        // Header
        Widget prev = content.add(new Label(L10n.get("critter_circles.title")), new Coord(0, 0));

        // Select All / Deselect All buttons
        Widget selectAll = content.add(new Button(UI.scale(80), L10n.get("critter_circles.select_all")) {
            @Override
            public void click() {
                for (CritterRow row : rows) {
                    row.visBox.a = true;
                    row.conf.visible = true;
                }
                markDirty();
            }
        }, prev.pos("bl").adds(0, 5));

        content.add(new Button(UI.scale(80), L10n.get("critter_circles.deselect_all")) {
            @Override
            public void click() {
                for (CritterRow row : rows) {
                    row.visBox.a = false;
                    row.conf.visible = false;
                }
                markDirty();
            }
        }, selectAll.pos("ur").adds(10, 0));

        prev = selectAll;

        // Column headers
        int colCheck = 0;
        int colName = UI.scale(24);
        int colColor = UI.scale(180);
        int colRadius = UI.scale(230);

        prev = content.add(new Label(L10n.get("critter_circles.col_name")), prev.pos("bl").adds(colName, 10));
        content.add(new Label(L10n.get("critter_circles.col_color")), new Coord(colColor, prev.c.y));
        content.add(new Label(L10n.get("critter_circles.col_radius")), new Coord(colRadius, prev.c.y));

        // Build config map for lookup — always from global config for consistency
        Map<String, NCritterCircleConf> confMap = new LinkedHashMap<>();
        Object obj = NConfig.getGlobal(NConfig.Key.critterCircleSettings);
        if (obj instanceof ArrayList) {
            for (Object item : (ArrayList<?>) obj) {
                if (item instanceof NCritterCircleConf) {
                    NCritterCircleConf c = (NCritterCircleConf) item;
                    confMap.put(c.path, c);
                }
            }
        }

        // Create a row for each critter
        for (String path : NCritterCircle.CRITTER_PATHS) {
            NCritterCircleConf conf = confMap.get(path);
            if (conf == null) {
                // Not in saved config yet — create default
                Color defColor = NCritterCircle.isRabbit(path)
                        ? new Color(88, 255, 0, 140)
                        : new Color(193, 0, 255, 140);
                conf = new NCritterCircleConf(path, true, defColor, 10f);
            }
            CritterRow row = new CritterRow(conf, UI.scale(540), UI.scale(28));
            prev = content.add(row, prev.pos("bl").adds(0, 2));
            rows.add(row);
        }

        content.pack();
    }

    @Override
    public void save() {
        for (CritterRow row : rows) {
            // Commit pending radius from TextEntry (user may not have pressed Enter)
            try {
                float val = Float.parseFloat(row.radiusEntry.text());
                if (val < 1) val = 1;
                if (val > 50) val = 50;
                row.conf.radius = val;
            } catch (NumberFormatException ignored) {}

            // Commit pending color from picker
            if (row.colorWidget.color != null) {
                Color c = row.colorWidget.color;
                Color withAlpha = new Color(c.getRed(), c.getGreen(), c.getBlue(), row.conf.alpha);
                row.conf.setColor(withAlpha);
            }
        }
        markDirty();
    }

    /**
     * One row: [checkbox] [name] [color button] [radius entry]
     */
    private class CritterRow extends Widget {
        final NCritterCircleConf conf;
        final CheckBox visBox;
        final NColorWidget colorWidget;
        final TextEntry radiusEntry;

        CritterRow(NCritterCircleConf conf, int width, int height) {
            super(new Coord(width, height));
            this.conf = conf;

            int cy = (height - UI.scale(16)) / 2;
            int colCheck = 0;
            int colName = UI.scale(24);
            int colColor = UI.scale(180);
            int colRadius = UI.scale(260);

            visBox = add(new CheckBox("") {
                { a = conf.visible; }
                @Override
                public void changed(boolean val) {
                    super.changed(val);
                    conf.visible = val;
                    markDirty();
                }
            }, new Coord(colCheck, cy));

            add(new Label(NCritterCircleConf.displayName(conf.path)),
                    new Coord(colName, cy));

            // Color picker — reuse NColorWidget but just the button part
            colorWidget = add(new NColorWidget("") {
                {
                    color = conf.getColor();
                    // Remove the label — we only want the button
                    label.hide();
                    cb.move(Coord.z);
                }
            }, new Coord(colColor, cy - UI.scale(4)));
            colorWidget.color = conf.getColor();

            radiusEntry = add(new TextEntry(UI.scale(50), String.valueOf((int) conf.radius)) {
                @Override
                public void done(ReadLine buf) {
                    super.done(buf);
                    try {
                        float val = Float.parseFloat(buf.line());
                        if (val < 1) val = 1;
                        if (val > 50) val = 50;
                        conf.radius = val;
                        markDirty();
                    } catch (NumberFormatException ignored) {}
                }
            }, new Coord(colRadius, cy));

            resize(new Coord(width, height));
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            // Sync color from picker back to config (picker updates on dialog close)
            if (colorWidget.color != null) {
                Color current = colorWidget.color;
                Color withAlpha = new Color(current.getRed(), current.getGreen(), current.getBlue(), conf.alpha);
                if (withAlpha.getRGB() != conf.getColor().getRGB()) {
                    conf.setColor(withAlpha);
                    markDirty();
                }
            }
        }
    }
}
