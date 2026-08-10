package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NConfig;
import nurgling.NMapView;
import nurgling.i18n.L10n;
import nurgling.overlays.NModelBox;
import nurgling.tools.GobHide;
import nurgling.widgets.NColorWidget;
import nurgling.widgets.NKeyBindButton;

import java.awt.Color;
import java.util.EnumMap;
import java.util.Map;

/**
 * Settings for hiding world objects and styling the boxes they leave behind.
 *
 * <p>The box colours here are deliberately <b>not</b> the ones in the World panel. Those style the
 * general "show object boundaries" overlay, which draws over objects that are still visible; these
 * style the boxes that stand in for objects that have been removed from the world. They are two
 * different things on screen and get two different sets of config keys.
 */
public class ObjectHiding extends Panel {
    private static class HidingSettings {
        boolean enabled;
        boolean fillBoxes;
        boolean respectMapIcons;
        final Map<GobHide.HideCategory, Boolean> categories = new EnumMap<>(GobHide.HideCategory.class);
        Color fillColor = NModelBox.DEF_FILL;
        Color edgeColor = NModelBox.DEF_EDGE;
        int lineWidth = NModelBox.DEF_LINE_WIDTH;
    }

    private final HidingSettings tempSettings = new HidingSettings();

    private final CheckBox enabled;
    private final CheckBox fillBoxes;
    private final CheckBox respectMapIcons;
    private final Map<GobHide.HideCategory, CheckBox> categoryBoxes =
            new EnumMap<>(GobHide.HideCategory.class);
    private final NColorWidget fillColorWidget;
    private final NColorWidget edgeColorWidget;
    private final HSlider lineWidthSlider;
    private final Label lineWidthLabel;

    private final Scrollport scrollport;
    private final Widget content;

    public ObjectHiding() {
        super("");
        int margin = UI.scale(10);

        int scrollWidth = UI.scale(560);
        int scrollHeight = UI.scale(550);
        scrollport = add(new Scrollport(new Coord(scrollWidth, scrollHeight)), new Coord(margin, margin));

        content = new Widget(new Coord(scrollWidth - UI.scale(20), UI.scale(50))) {
            @Override
            public void pack() {
                resize(contentsz());
            }
        };
        scrollport.cont.add(content, Coord.z);

        int contentMargin = UI.scale(5);

        Widget prev = content.add(new Label("● " + L10n.get("hiding.section.general")),
                new Coord(contentMargin, contentMargin));

        prev = enabled = content.add(new CheckBox(L10n.get("hiding.enabled")) {
            public void set(boolean val) {
                tempSettings.enabled = val;
                a = val;
            }
        }, prev.pos("bl").adds(0, 5));

        prev = fillBoxes = content.add(new CheckBox(L10n.get("hiding.fill_boxes")) {
            public void set(boolean val) {
                tempSettings.fillBoxes = val;
                a = val;
            }
        }, prev.pos("bl").adds(0, 5));

        prev = respectMapIcons = content.add(new CheckBox(L10n.get("hiding.respect_map_icons")) {
            public void set(boolean val) {
                tempSettings.respectMapIcons = val;
                a = val;
            }
        }, prev.pos("bl").adds(0, 5));

        // Hotkey row. Unlike everything else on this panel the binding is applied immediately -
        // KeyBinding owns its own persistence, so there is nothing sensible to stage until Save.
        Label hotkeyLabel = content.add(new Label(L10n.get("hiding.hotkey")), prev.pos("bl").adds(0, 12));
        content.add(new NKeyBindButton(UI.scale(175), NMapView.kb_togglenature),
                hotkeyLabel.pos("ur").adds(10, -4));
        prev = hotkeyLabel;

        // Box appearance
        prev = content.add(new Label("● " + L10n.get("hiding.section.box")), prev.pos("bl").adds(0, 20));

        prev = fillColorWidget = content.add(new NColorWidget(L10n.get("hiding.box_fill")), prev.pos("bl").adds(0, 5));
        fillColorWidget.color = tempSettings.fillColor;

        prev = edgeColorWidget = content.add(new NColorWidget(L10n.get("hiding.box_edge")), prev.pos("bl").adds(0, 5));
        edgeColorWidget.color = tempSettings.edgeColor;

        prev = lineWidthLabel = content.add(
                new Label(L10n.get("hiding.box_line_width") + " " + tempSettings.lineWidth), prev.pos("bl").adds(0, 5));
        prev = lineWidthSlider = content.add(new HSlider(UI.scale(100), 1, 10, tempSettings.lineWidth) {
            public void changed() {
                tempSettings.lineWidth = val;
                lineWidthLabel.settext(L10n.get("hiding.box_line_width") + " " + val);
            }
        }, prev.pos("bl").adds(0, 5));

        prev = content.add(new Button(UI.scale(120), L10n.get("hiding.box_reset")) {
            public void click() {
                resetBoxStyle();
            }
        }, prev.pos("bl").adds(0, 8));

        // Categories, laid out in two columns like the reference client
        prev = content.add(new Label("● " + L10n.get("hiding.section.categories")), prev.pos("bl").adds(0, 20));

        GobHide.HideCategory[] cats = GobHide.PANEL_CATEGORIES;
        int columnX = UI.scale(280);
        int rowStep = UI.scale(22);
        Coord origin = prev.pos("bl").adds(0, 8);
        int rows = (cats.length + 1) / 2;
        for (int i = 0; i < cats.length; i++) {
            final GobHide.HideCategory cat = cats[i];
            int col = i / rows;
            int row = i % rows;
            CheckBox cb = content.add(new CheckBox(L10n.get(cat.l10nKey)) {
                public void set(boolean val) {
                    tempSettings.categories.put(cat, val);
                    a = val;
                }
            }, origin.add(col * columnX, row * rowStep));
            categoryBoxes.put(cat, cb);
        }
        // Anchor the scroll height to the taller of the two columns.
        content.add(new Label(""), origin.add(0, rows * rowStep));

        content.pack();
        scrollport.cont.update();

        pack();
    }

    private void resetBoxStyle() {
        tempSettings.fillColor = NModelBox.DEF_FILL;
        tempSettings.edgeColor = NModelBox.DEF_EDGE;
        tempSettings.lineWidth = NModelBox.DEF_LINE_WIDTH;
        fillColorWidget.color = tempSettings.fillColor;
        edgeColorWidget.color = tempSettings.edgeColor;
        lineWidthSlider.val = tempSettings.lineWidth;
        lineWidthLabel.settext(L10n.get("hiding.box_line_width") + " " + tempSettings.lineWidth);
    }

    @Override
    public void load() {
        tempSettings.enabled = GobHide.isEnabled();
        tempSettings.respectMapIcons = GobHide.respectMapIcons();
        for (GobHide.HideCategory cat : GobHide.PANEL_CATEGORIES)
            tempSettings.categories.put(cat, cat.enabled());

        Object mode = NConfig.get(NConfig.Key.hideBoxDisplayMode);
        String modeStr = (mode instanceof String) ? (String) mode : NModelBox.DEF_MODE;
        tempSettings.fillBoxes = modeStr.startsWith("FILLED");

        tempSettings.fillColor = NConfig.getColor(NConfig.Key.hideBoxFillColor, NModelBox.DEF_FILL);
        tempSettings.edgeColor = NConfig.getColor(NConfig.Key.hideBoxEdgeColor, NModelBox.DEF_EDGE);
        Object lw = NConfig.get(NConfig.Key.hideBoxLineWidth);
        tempSettings.lineWidth = (lw instanceof Number) ? ((Number) lw).intValue() : NModelBox.DEF_LINE_WIDTH;

        enabled.a = tempSettings.enabled;
        fillBoxes.a = tempSettings.fillBoxes;
        respectMapIcons.a = tempSettings.respectMapIcons;
        for (Map.Entry<GobHide.HideCategory, CheckBox> e : categoryBoxes.entrySet())
            e.getValue().a = Boolean.TRUE.equals(tempSettings.categories.get(e.getKey()));

        fillColorWidget.color = tempSettings.fillColor;
        edgeColorWidget.color = tempSettings.edgeColor;
        lineWidthSlider.val = tempSettings.lineWidth;
        lineWidthLabel.settext(L10n.get("hiding.box_line_width") + " " + tempSettings.lineWidth);
    }

    @Override
    public void save() {
        // Colours come straight off the widgets: NColorWidget writes its result back from a Swing
        // dialog on another thread, so there is no set() hook to stage it through.
        tempSettings.fillColor = fillColorWidget.color;
        tempSettings.edgeColor = edgeColorWidget.color;
        NConfig.setColor(NConfig.Key.hideBoxFillColor, tempSettings.fillColor);
        NConfig.setColor(NConfig.Key.hideBoxEdgeColor, tempSettings.edgeColor);
        NConfig.set(NConfig.Key.hideBoxLineWidth, tempSettings.lineWidth);

        // Preserve the "always visible" half of the mode - the checkbox only chooses fill vs outline.
        Object mode = NConfig.get(NConfig.Key.hideBoxDisplayMode);
        String modeStr = (mode instanceof String) ? (String) mode : NModelBox.DEF_MODE;
        boolean always = modeStr.endsWith("_ALWAYS");
        NConfig.set(NConfig.Key.hideBoxDisplayMode,
                (tempSettings.fillBoxes ? "FILLED" : "OUTLINE") + (always ? "_ALWAYS" : ""));
        NModelBox.invalidateStyles();

        GobHide.setRespectMapIcons(tempSettings.respectMapIcons);
        for (GobHide.HideCategory cat : GobHide.PANEL_CATEGORIES)
            GobHide.setCategory(cat, Boolean.TRUE.equals(tempSettings.categories.get(cat)));

        // Last, because setEnabled runs the sweep that applies every change made above and
        // refreshes the minimap toggle. It does so unconditionally, so no separate applyAll here.
        GobHide.setEnabled(tempSettings.enabled);

        NConfig.needUpdate();
    }
}
