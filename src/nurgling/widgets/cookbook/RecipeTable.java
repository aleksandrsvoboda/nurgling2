package nurgling.widgets.cookbook;

import haven.*;
import nurgling.cookbook.CookbookModel;
import nurgling.cookbook.CookbookRow;
import nurgling.cookbook.FepAttr;
import nurgling.cookbook.FepValue;
import nurgling.cookbook.Recipe;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The recipe table: a header of sortable columns -- with the stat icons and the +1/+2 switch over
 * the FEPs -- above a virtualized list of rows. The columns share out the width again on every
 * resize, and the least important drop out when the window gets narrow.
 */
public class RecipeTable extends Widget {
    /** What rows ask the window to do. */
    public interface Actions {
        void craft(CookbookRow row);
        void toggleFavorite(CookbookRow row);
        void rowMenu(CookbookRow row, Widget at);
        void clearFilters();
    }

    /* Widths are unscaled pixels: fix is the minimum, flex a share of what is left. hideBelow is
     * the table width under which the column drops out, 0 for never. */
    enum Col {
        ICON(52, 0, 0, null, null, null, false),
        NAME(120, 1.2, 0, CookbookModel.Column.NAME, "cookbook.col.food", null, false),
        INGREDIENTS(80, 1.4, 900, CookbookModel.Column.INGREDIENTS, "cookbook.col.ingredients", null, false),
        FEPS(330, 1.0, 0, null, "cookbook.col.feps", "cookbook.tip.feps", false),
        F(58, 0, 700, CookbookModel.Column.F, "cookbook.col.f", "cookbook.tip.f", true),
        H(54, 0, 700, CookbookModel.Column.H, "cookbook.col.h", "cookbook.tip.h", true),
        FH(64, 0, 0, CookbookModel.Column.FH, "cookbook.col.fh", "cookbook.tip.fh", true),
        TOP(44, 0, 900, CookbookModel.Column.TOP, "cookbook.col.top", "cookbook.tip.top", true),
        ENERGY(62, 0, 900, CookbookModel.Column.ENERGY, "cookbook.col.energy", "cookbook.tip.energy", true);

        final int fix;
        final double flex;
        final int hideBelow;
        final CookbookModel.Column sort;
        final String label, tip;
        final boolean right;

        Col(int fix, double flex, int hideBelow, CookbookModel.Column sort, String label, String tip, boolean right) {
            this.fix = fix;
            this.flex = flex;
            this.hideBelow = hideBelow;
            this.sort = sort;
            this.label = label;
            this.tip = tip;
            this.right = right;
        }
    }

    public static final int HEAD_H = UI.scale(28);
    public static final int ROW_H = UI.scale(40);
    private static final int PAD = UI.scale(8);
    private static final int CHIP_GAP = UI.scale(11);
    private static final Coord ICON_SZ = UI.scale(new Coord(24, 24));
    private static final Coord STAR_SZ = UI.scale(new Coord(16, 16));
    private static final Col[] COLS = Col.values();

    private final CookbookModel model;
    private final Actions actions;
    private final RowList rows;
    private final List<StatIcon> statIcons = new ArrayList<>();
    private final TierSwitch tier;
    private final PillButton clearBtn;
    private final Tex[] headOff = new Tex[COLS.length], headOn = new Tex[COLS.length];
    private final int[] colX = new int[COLS.length], colW = new int[COLS.length];
    private int hoverCol = -1;
    private int fepSortX = 0;
    private String fepSortKey = null;
    private Tex fepSortTex = null;
    private String emptyText = "";
    private Tex emptyTex = null;
    private int emptyW = -1;

    public RecipeTable(Coord sz, CookbookModel model, Actions actions) {
        super(sz);
        this.model = model;
        this.actions = actions;
        for(Col c : COLS) {
            if(c.label != null) {
                String l = L10n.get(c.label);
                headOff[c.ordinal()] = CookbookTheme.render(CookbookTheme.bold, l, CookbookTheme.fg);
                headOn[c.ordinal()] = CookbookTheme.render(CookbookTheme.bold, l, CookbookTheme.accent);
            }
        }
        rows = add(new RowList(Coord.of(sz.x, Math.max(sz.y - HEAD_H, ROW_H))), Coord.of(0, HEAD_H));
        for(FepAttr a : FepAttr.KNOWN)
            statIcons.add(add(new StatIcon(a, model), Coord.z));
        tier = add(new TierSwitch(model::statTier, model::setStatTier), Coord.z);
        clearBtn = add(new PillButton(L10n.get("cookbook.opt.clear"), false, actions::clearFilters), Coord.z);
        clearBtn.hide();
        layoutColumns();
    }

    /** What to say when no row is shown, and whether to offer clearing the filters. */
    public void empty(String text, boolean offerClear) {
        String t = (text == null) ? "" : text;
        if(!t.equals(emptyText)) {
            emptyText = t;
            emptyTex = null;
        }
        clearBtn.show(offerClear && !t.isEmpty());
    }

    public void scrollTop() {
        rows.scrollval(0);
    }

    @Override
    public void resize(Coord sz) {
        super.resize(sz);
        /* Null while Widget's constructor runs. */
        if(rows != null) {
            rows.resize(Coord.of(sz.x, Math.max(sz.y - HEAD_H, ROW_H)));
            layoutColumns();
        }
    }

    private void layoutColumns() {
        int w = sz.x - rows.sb.sz.x;
        boolean[] on = new boolean[COLS.length];
        int fixed = 0;
        double flex = 0;
        for(int i = 0; i < COLS.length; i++) {
            on[i] = (COLS[i].hideBelow == 0) || (sz.x >= UI.scale(COLS[i].hideBelow));
            if(on[i]) {
                fixed += UI.scale(COLS[i].fix);
                flex += COLS[i].flex;
            }
        }
        int rest = Math.max(w - fixed, 0);
        int x = 0;
        double acc = 0;
        for(int i = 0; i < COLS.length; i++) {
            colX[i] = x;
            if(!on[i]) {
                colW[i] = 0;
                continue;
            }
            int extra = 0;
            if(flex > 0) {
                extra = (int)(Math.round((acc + COLS[i].flex) * rest / flex) - Math.round(acc * rest / flex));
                acc += COLS[i].flex;
            }
            colW[i] = UI.scale(COLS[i].fix) + extra;
            x += colW[i];
        }

        int fi = Col.FEPS.ordinal();
        int ix = colX[fi] + PAD + headOff[fi].sz().x + UI.scale(10);
        for(StatIcon s : statIcons) {
            s.move(Coord.of(ix, (HEAD_H - s.sz.y) / 2));
            ix += s.sz.x + UI.scale(3);
        }
        tier.move(Coord.of(ix + UI.scale(5), (HEAD_H - tier.sz.y) / 2));
        fepSortX = tier.c.x + tier.sz.x + UI.scale(8);
    }

    private int colAt(int x) {
        for(int i = 0; i < COLS.length; i++) {
            if((colW[i] > 0) && (x >= colX[i]) && (x < colX[i] + colW[i]))
                return i;
        }
        return -1;
    }

    @Override
    public void draw(GOut g) {
        CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.bg);
        CookbookTheme.fill(g, Coord.z, Coord.of(sz.x, HEAD_H), CookbookTheme.head);
        CookbookTheme.fill(g, Coord.of(0, HEAD_H - 1), Coord.of(sz.x, 1), CookbookTheme.outline);
        CookbookModel.Column sorted = model.sortColumn();
        for(int i = 0; i < COLS.length; i++) {
            Col col = COLS[i];
            if((colW[i] == 0) || (headOff[i] == null))
                continue;
            boolean active = (col.sort != null) && (col.sort == sorted);
            if((i == hoverCol) && (col.sort != null))
                CookbookTheme.fill(g, Coord.of(colX[i], 0), Coord.of(colW[i], HEAD_H - 1), CookbookTheme.hover);
            Tex t = active ? headOn[i] : headOff[i];
            int arrow = active ? UI.scale(10) : 0;
            int tx = col.right ? (colX[i] + colW[i] - PAD - arrow - t.sz().x) : (colX[i] + PAD);
            g.image(t, Coord.of(tx, (HEAD_H - t.sz().y) / 2));
            if(active)
                CookbookTheme.triangle(g, Coord.of(tx + t.sz().x + UI.scale(6), HEAD_H / 2), model.descending(), CookbookTheme.accent);
        }
        FepAttr sa = model.sortAttr();
        if(sa != null) {
            String key = sa.label(model.sortTier());
            if(!key.equals(fepSortKey)) {
                fepSortKey = key;
                fepSortTex = CookbookTheme.render(CookbookTheme.bold, key, CookbookTheme.accent);
            }
            g.image(fepSortTex, Coord.of(fepSortX, (HEAD_H - fepSortTex.sz().y) / 2));
            CookbookTheme.triangle(g, Coord.of(fepSortX + fepSortTex.sz().x + UI.scale(6), HEAD_H / 2), model.descending(), CookbookTheme.accent);
        }
        if(model.view().isEmpty()) {
            Tex t = emptyTex();
            if(t != null) {
                int y = HEAD_H + UI.scale(30);
                g.image(t, Coord.of((sz.x - t.sz().x) / 2, y));
                clearBtn.move(Coord.of((sz.x - clearBtn.sz.x) / 2, y + t.sz().y + UI.scale(12)));
            }
        }
        super.draw(g);
    }

    private Tex emptyTex() {
        if(emptyText.isEmpty())
            return null;
        int w = Math.max(sz.x - UI.scale(60), UI.scale(200));
        if((emptyTex == null) || (emptyW != w)) {
            emptyTex = CookbookTheme.body.renderwrap(emptyText, CookbookTheme.muted, w).tex();
            emptyW = w;
        }
        return emptyTex;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.propagate(this) || super.mousedown(ev))
            return true;
        if((ev.b == 1) && (ev.c.y < HEAD_H)) {
            int i = colAt(ev.c.x);
            if((i >= 0) && (COLS[i].sort != null)) {
                model.sortBy(COLS[i].sort);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        hoverCol = (hovering && (ev.c.y < HEAD_H)) ? colAt(ev.c.x) : -1;
        return false;
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        if(c.y < HEAD_H) {
            int i = colAt(c.x);
            if((i >= 0) && (COLS[i].tip != null))
                return L10n.get(COLS[i].tip);
        }
        return null;
    }

    static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private class RowList extends SListBox<CookbookRow, RowWidget> {
        RowList(Coord sz) {
            super(sz, ROW_H);
        }

        @Override
        protected List<CookbookRow> items() {
            return model.view();
        }

        @Override
        protected RowWidget makeitem(CookbookRow row, int idx, Coord sz) {
            return new RowWidget(row, sz);
        }

        @Override
        protected void drawbg(GOut g, CookbookRow item, int idx, Area area) {
        }

        @Override
        protected void drawsel(GOut g, CookbookRow item, int idx, Area area) {
        }
    }

    /**
     * One recipe. Only rows on screen exist, so everything is rendered once when the row is made;
     * the star, the hover and the sort highlight are read live.
     */
    class RowWidget extends Widget {
        final CookbookRow row;
        private final Tex title, total, hunger, perHunger, top, energy;
        private final Tex[] chipVal, chipCode;
        private final int[] chipW;
        private final String[] chipTip;
        private Tex ing = null, wood = null, ingTip = null;
        private int ingW = -1;
        private boolean hover = false;
        private Coord mouse = null;

        RowWidget(CookbookRow row, Coord sz) {
            super(sz);
            this.row = row;
            title = CookbookTheme.render(CookbookTheme.bold, row.name(), CookbookTheme.fg);
            total = CookbookTheme.render(CookbookTheme.body, fmt(row.total, 1), CookbookTheme.fg);
            hunger = CookbookTheme.render(CookbookTheme.body, fmt(row.hunger, 2), CookbookTheme.fg);
            perHunger = CookbookTheme.render(CookbookTheme.bold, fmt(row.perHunger, 2), CookbookTheme.fg);
            top = CookbookTheme.render(CookbookTheme.body, fmt(row.topShare, 0), CookbookTheme.fg);
            energy = CookbookTheme.render(CookbookTheme.body, row.energy() + "%", CookbookTheme.fg);
            int n = row.feps.size();
            chipVal = new Tex[n];
            chipCode = new Tex[n];
            chipW = new int[n];
            chipTip = new String[n];
            for(int i = 0; i < n; i++) {
                FepValue f = row.feps.get(i);
                Color c = f.attr.color(f.tier);
                chipVal[i] = CookbookTheme.render(CookbookTheme.bold, fmt(f.value, 1), c);
                chipCode[i] = CookbookTheme.render(CookbookTheme.small, chipLabel(f), c);
                chipW[i] = Math.max(chipVal[i].sz().x, chipCode[i].sz().x);
                String share = (row.total > 0) ? fmt(f.value / row.total * 100, 0) : "0";
                chipTip[i] = L10n.get("cookbook.tip.chip", f.name, fmt(f.value, 2), share);
            }
        }

        private String chipLabel(FepValue f) {
            if(f.attr != FepAttr.UNKNOWN)
                return f.attr.label(f.tier);
            String base = (f.name == null) ? "?" : f.name.replaceFirst("\\s*\\+\\d+\\s*$", "").trim();
            base = base.substring(0, Math.min(3, base.length())).toLowerCase(Locale.ROOT);
            return (f.tier > 1) ? base + "+2" : base;
        }

        private GOut cell(GOut g, Col col) {
            int i = col.ordinal();
            return (colW[i] > 0) ? g.reclip(Coord.of(colX[i], 0), Coord.of(colW[i], sz.y)) : null;
        }

        private void number(GOut g, Col col, Tex t) {
            int i = col.ordinal();
            if(colW[i] > 0)
                g.image(t, Coord.of(colX[i] + colW[i] - PAD - t.sz().x, (sz.y - t.sz().y) / 2));
        }

        private Coord starUl() {
            return Coord.of(colX[Col.ICON.ordinal()] + UI.scale(4) + ICON_SZ.x + UI.scale(4), (sz.y - STAR_SZ.y) / 2);
        }

        private boolean inStar(Coord c) {
            return c.isect(starUl(), STAR_SZ);
        }

        private int chipAt(Coord c) {
            int fi = Col.FEPS.ordinal();
            if(colW[fi] == 0)
                return -1;
            int x = colX[fi] + PAD, end = colX[fi] + colW[fi];
            for(int i = 0; i < chipW.length; i++) {
                if(x + chipW[i] > end)
                    break;
                if((c.x >= x) && (c.x < x + chipW[i]))
                    return i;
                x += chipW[i] + CHIP_GAP;
            }
            return -1;
        }

        /* Ingredients, then the smoking woods in the warning colour, cut to the column's width. */
        private void ensureIngredients(int w) {
            if(w == ingW)
                return;
            ingW = w;
            ing = null;
            wood = null;
            String a = row.ingredientText;
            String b = row.woodText.isEmpty() ? "" : L10n.get("cookbook.smoked_with") + " " + row.woodText;
            if(!a.isEmpty())
                ing = CookbookTheme.render(CookbookTheme.body, CookbookTheme.ellipsize(CookbookTheme.body, b.isEmpty() ? a : a + " · ", w), CookbookTheme.muted);
            if(!b.isEmpty()) {
                int used = (ing != null) ? ing.sz().x : 0;
                if(w - used > UI.scale(20))
                    wood = CookbookTheme.render(CookbookTheme.body, CookbookTheme.ellipsize(CookbookTheme.body, b, w - used), CookbookTheme.warn);
            }
        }

        private Tex ingredientTip() {
            if(ingTip == null) {
                StringBuilder sb = new StringBuilder();
                List<Map.Entry<String, Recipe.IngredientInfo>> ings = new ArrayList<>(row.recipe.getIngredients().entrySet());
                ings.sort((x, y) -> Double.compare(y.getValue().percentage, x.getValue().percentage));
                for(Map.Entry<String, Recipe.IngredientInfo> e : ings) {
                    if(sb.length() > 0)
                        sb.append('\n');
                    sb.append(RichText.Parser.quote(e.getKey())).append(": ").append(Utils.odformat2(e.getValue().percentage, 2)).append('%');
                }
                if(!row.recipe.getSmokingWoods().isEmpty()) {
                    List<String> woods = new ArrayList<>();
                    for(Map.Entry<String, Double> e : row.recipe.getSmokingWoods().entrySet())
                        woods.add(RichText.Parser.quote(e.getKey()) + " (" + Utils.odformat2(e.getValue(), 2) + "%)");
                    if(sb.length() > 0)
                        sb.append('\n');
                    sb.append("$col[232,163,61]{").append(RichText.Parser.quote(L10n.get("cookbook.smoked_with")))
                      .append(' ').append(String.join(", ", woods)).append('}');
                }
                if(sb.length() == 0)
                    return null;
                ingTip = CookbookTheme.tip.render(sb.toString(), UI.scale(320)).tex();
            }
            return ingTip;
        }

        @Override
        public void draw(GOut g) {
            if(hover)
                CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.hover);
            CookbookTheme.fill(g, Coord.of(0, sz.y - 1), Coord.of(sz.x, 1), CookbookTheme.line);

            Coord ic = Coord.of(colX[Col.ICON.ordinal()] + UI.scale(4), (sz.y - ICON_SZ.y) / 2);
            Tex icon = IconCache.get(row.recipe.getResourceName());
            if(icon != null)
                g.image(icon, ic, ICON_SZ);
            else
                CookbookTheme.fill(g, ic, ICON_SZ, CookbookTheme.head);
            if(!row.recipe.isFavorite())
                g.chcolor(255, 255, 255, ((mouse != null) && inStar(mouse)) ? 190 : 55);
            g.image(CookbookTheme.star(), starUl(), STAR_SZ);
            g.chcolor();

            int cy = sz.y / 2;
            GOut cg = cell(g, Col.NAME);
            if(cg != null)
                cg.image(title, Coord.of(PAD, cy - title.sz().y / 2));
            cg = cell(g, Col.INGREDIENTS);
            if(cg != null) {
                ensureIngredients(colW[Col.INGREDIENTS.ordinal()] - 2 * PAD);
                int x = PAD;
                if(ing != null) {
                    cg.image(ing, Coord.of(x, cy - ing.sz().y / 2));
                    x += ing.sz().x;
                }
                if(wood != null)
                    cg.image(wood, Coord.of(x, cy - wood.sz().y / 2));
            }
            cg = cell(g, Col.FEPS);
            if(cg != null) {
                FepAttr sa = model.sortAttr();
                int hot = (mouse != null) ? chipAt(mouse) : -1;
                int x = PAD;
                for(int i = 0; i < chipVal.length; i++) {
                    FepValue f = row.feps.get(i);
                    int vh = chipVal[i].sz().y, ch = chipCode[i].sz().y, y0 = (sz.y - vh - ch) / 2;
                    boolean sorted = (sa != null) && f.is(sa, model.sortTier());
                    if(sorted || (i == hot)) {
                        CookbookTheme.frame(cg, Coord.of(x - UI.scale(3), y0 - UI.scale(1)), Coord.of(chipW[i] + UI.scale(6), vh + ch + UI.scale(2)),
                                            sorted ? CookbookTheme.accent : CookbookTheme.outline);
                    }
                    cg.image(chipVal[i], Coord.of(x + (chipW[i] - chipVal[i].sz().x) / 2, y0));
                    cg.image(chipCode[i], Coord.of(x + (chipW[i] - chipCode[i].sz().x) / 2, y0 + vh));
                    x += chipW[i] + CHIP_GAP;
                }
            }
            number(g, Col.F, total);
            number(g, Col.H, hunger);
            number(g, Col.FH, perHunger);
            number(g, Col.TOP, top);
            number(g, Col.ENERGY, energy);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b == 3) {
                actions.rowMenu(row, this);
                return true;
            }
            if(ev.b != 1)
                return super.mousedown(ev);
            if(inStar(ev.c)) {
                actions.toggleFavorite(row);
                return true;
            }
            int chip = chipAt(ev.c);
            if(chip >= 0) {
                FepValue f = row.feps.get(chip);
                if(f.attr != FepAttr.UNKNOWN)
                    model.sortByFep(f.attr, f.tier);
                return true;
            }
            actions.craft(row);
            return true;
        }

        @Override
        public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
            hover = hovering;
            mouse = hovering ? ev.c : null;
            return false;
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            if(inStar(c))
                return L10n.get(row.recipe.isFavorite() ? "cookbook.remove_from_favorites" : "cookbook.add_to_favorites");
            int chip = chipAt(c);
            if(chip >= 0)
                return chipTip[chip];
            int i = Col.INGREDIENTS.ordinal();
            if((colW[i] > 0) && (c.x >= colX[i]) && (c.x < colX[i] + colW[i])) {
                Tex t = ingredientTip();
                if(t != null)
                    return t;
            }
            return L10n.get("cookbook.tip.row");
        }
    }
}
