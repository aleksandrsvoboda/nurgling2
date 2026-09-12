package nurgling.widgets.cookbook;

import haven.*;
import nurgling.cookbook.CookbookModel;
import nurgling.cookbook.SpiceCalc;
import nurgling.i18n.L10n;

import java.util.ArrayList;
import java.util.List;

/**
 * The Spices what-if: the quality of each measured spice you would sprinkle. Typing a quality
 * recomputes and re-ranks every row; emptying it turns the spice off. Salt is listed for what it
 * does -- nothing measurable -- and has no box.
 */
public class SpicePanel extends Popover {
    private final List<QualityField> fields = new ArrayList<>();

    public SpicePanel(PillButton owner, CookbookModel model) {
        super(owner);
        int labelW = UI.scale(110), fieldW = UI.scale(54), whyW = UI.scale(270);
        int whyX = PAD + labelW + fieldW + UI.scale(12);
        int y = PAD;
        for(SpiceCalc.Spice s : SpiceCalc.Spice.values()) {
            add(new CText(CookbookTheme.bold, CookbookTheme.fg).set(name(s)), Coord.of(PAD, y + UI.scale(3)));
            QualityField field = add(new QualityField(fieldW, model, s), Coord.of(PAD + labelW, y));
            field.settip(L10n.get("cookbook.spice.quality_tip"));
            fields.add(field);
            Img why = add(new Img(CookbookTheme.small.renderwrap(L10n.get("cookbook.spice." + key(s) + "_why"), CookbookTheme.muted, whyW).tex()),
                          Coord.of(whyX, y + UI.scale(3)));
            y += Math.max(field.sz.y, why.sz.y + UI.scale(3)) + UI.scale(8);
        }
        add(new CText(CookbookTheme.bold, CookbookTheme.muted).set(L10n.get("cookbook.spice.salt")), Coord.of(PAD, y));
        Img salt = add(new Img(CookbookTheme.small.renderwrap(L10n.get("cookbook.spice.salt_why"), CookbookTheme.muted, whyW).tex()), Coord.of(whyX, y));
        y += salt.sz.y + UI.scale(12);
        add(new Img(CookbookTheme.small.renderwrap(L10n.get("cookbook.spice.footnote"), CookbookTheme.muted, whyX + whyW - PAD).tex()), Coord.of(PAD, y));
        fit();
    }

    /** The spice's localised name. */
    public static String name(SpiceCalc.Spice s) {
        return L10n.get("cookbook.spice." + key(s));
    }

    private static String key(SpiceCalc.Spice s) {
        switch(s) {
            case BLACK_TRUFFLE:
                return "blacktruffle";
            case WHITE_TRUFFLE:
                return "whitetruffle";
            default:
                return "pepper";
        }
    }

    /** A quality as typed: whole numbers without a decimal point. */
    public static String quality(double q) {
        return (q == Math.rint(q)) ? String.valueOf((long)q) : String.valueOf(q);
    }

    /* Clearing every filter also clears the spices; the boxes follow when the panel next opens. */
    @Override
    public void syncFromModel() {
        for(QualityField f : fields)
            f.sync();
    }

    private static class QualityField extends TextEntry.NumberValue {
        private final CookbookModel model;
        private final SpiceCalc.Spice spice;

        QualityField(int w, CookbookModel model, SpiceCalc.Spice spice) {
            super(w, (model.spice(spice) > 0) ? quality(model.spice(spice)) : "");
            this.model = model;
            this.spice = spice;
        }

        @Override
        protected void changed() {
            super.changed();
            /* Null while TextEntry's constructor sets the initial text. */
            if(model != null)
                model.setSpice(spice, parse(text()));
        }

        void sync() {
            double q = model.spice(spice);
            if(parse(text()) != q)
                settext((q > 0) ? quality(q) : "");
        }

        private static double parse(String t) {
            String s = t.trim();
            if(s.isEmpty())
                return 0;
            try {
                return Double.parseDouble(s);
            } catch(NumberFormatException e) {
                return 0;
            }
        }
    }
}
