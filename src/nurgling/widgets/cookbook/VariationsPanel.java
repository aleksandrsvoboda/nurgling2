package nurgling.widgets.cookbook;

import haven.*;
import nurgling.NCloseButton;
import nurgling.NStyle;
import nurgling.cookbook.Variations;
import nurgling.i18n.L10n;
import nurgling.tools.ItemIcons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The untried variations of one food, docked at the right of the cookbook: for every ingredient
 * choice that changes its FEPs, a bar of how many options were tried, the untried options as icon
 * chips, and the tried ones in a dimmed line. The body scrolls with the wheel. Each choice with
 * untried options in storage has a "Vary this" button, which sets up crafting the food once per option.
 */
public class VariationsPanel extends Widget {
    public static final int WIDTH = UI.scale(360);
    private static final int PAD = UI.scale(10);
    private static final int ICON = UI.scale(16);
    private static final int CHIP_H = UI.scale(22);
    private static final int CHIP_GAP = UI.scale(4);
    private static final int BAR_H = UI.scale(4);
    private static final int WHEEL = UI.scale(30);

    private final CText title;
    private final IButton close;
    private final Consumer<String> onVary;
    private Variations.Dish dish = null;
    private String why = "";
    private Set<String> stored = Collections.emptySet();
    private final List<Text> texts = new ArrayList<>();
    private final List<Chip> chips = new ArrayList<>();
    private final List<Bar> bars = new ArrayList<>();
    private final List<VaryButton> varyButtons = new ArrayList<>();
    private VaryButton hovered = null;
    private int laidOutW = -1, contentH = 0, scroll = 0;

    private static final class Text {
        final Tex tex;
        final Coord c;

        Text(Tex tex, Coord c) {
            this.tex = tex;
            this.c = c;
        }
    }

    private static final class Bar {
        final Coord c;
        final int w;
        final double share;

        Bar(Coord c, int w, double share) {
            this.c = c;
            this.w = w;
            this.share = share;
        }
    }

    /* A choice's "Vary this" button, drawn in the scrolled body. */
    private static final class VaryButton {
        final Variations.Slot slot;
        final Tex label;
        final Coord c, sz;

        VaryButton(Variations.Slot slot, Tex label, Coord c, Coord sz) {
            this.slot = slot;
            this.label = label;
            this.c = c;
            this.sz = sz;
        }
    }

    private static final class Chip {
        final Variations.Option option;
        final Tex label;
        final Coord c, sz;

        Chip(Variations.Option option, Tex label, Coord c, Coord sz) {
            this.option = option;
            this.label = label;
            this.c = c;
            this.sz = sz;
        }
    }

    /** @param onVary sets up crafting the untried options of the choice with this title */
    public VariationsPanel(Runnable onClose, Consumer<String> onVary) {
        super(Coord.of(WIDTH, UI.scale(200)));
        this.onVary = onVary;
        title = add(new CText(CookbookTheme.bold, CookbookTheme.fg), Coord.of(PAD, PAD));
        /* The windows' own close button: Open Sans has no "✕", so a text one drew nothing to click. */
        close = add(new NCloseButton(NStyle.cbtni[0], NStyle.cbtni[1], NStyle.cbtni[2]), Coord.z).action(onClose);
        hide();
    }

    /** Shows a food's variations, or with a null dish, why there are none to show. */
    public void set(Variations.Dish dish, Set<String> stored, String why) {
        this.stored = stored;
        this.dish = dish;
        this.why = why;
        title.set((dish == null) ? L10n.get("cookbook.var.menu") : dish.name + " · " + L10n.get("cookbook.var.title"));
        scroll = 0;
        laidOutW = -1;
    }

    /* How many crafts varying a choice would take: its untried options in storage, and going without it. */
    private int crafts(Variations.Slot s) {
        int n = (s.optional && !s.noneTried) ? 1 : 0;
        for(Variations.Option o : s.options) {
            if(!o.tried() && stocked(o))
                n++;
        }
        return n;
    }

    @Override
    public void resize(Coord sz) {
        super.resize(sz);
        laidOutW = -1;
    }

    private int bodyY() {
        return PAD + Math.max(title.sz.y, close.sz.y) + UI.scale(6);
    }

    private void layout() {
        int w = sz.x - 2 * PAD;
        if(w == laidOutW)
            return;
        laidOutW = w;
        close.move(Coord.of(sz.x - PAD - close.sz.x, PAD));
        texts.clear();
        chips.clear();
        bars.clear();
        varyButtons.clear();
        hovered = null;
        int y = 0;
        if(dish == null) {
            y = para(why, CookbookTheme.muted, y, w);
        } else {
            y = line(CookbookTheme.small, L10n.get("cookbook.var.variants", String.valueOf(dish.variants)), CookbookTheme.muted, 0, y);
            if(anyStocked())
                y = line(CookbookTheme.small, L10n.get("cookbook.var.legend"), CookbookTheme.stocked, 0, y);
            y += UI.scale(6);
            if(!dish.known)
                y = para(L10n.get("cookbook.var.unknown"), CookbookTheme.muted, y, w) + UI.scale(6);
            if(dish.slots.isEmpty())
                y = para(L10n.get("cookbook.var.nothing"), CookbookTheme.fg, y, w) + UI.scale(6);
            for(Variations.Slot s : dish.slots)
                y = slot(s, y, w);
            if(!dish.notListed.isEmpty())
                y = para(L10n.get("cookbook.var.not_listed", String.join(", ", dish.notListed)), CookbookTheme.muted, y + UI.scale(4), w);
        }
        contentH = y + PAD;
        clampScroll();
    }

    private int slot(Variations.Slot s, int y, int w) {
        StringBuilder head = new StringBuilder(s.wood() ? L10n.get("cookbook.var.wood") : s.title);
        if((s.count >= 2) && (s.count == Math.rint(s.count)))
            head.append(" ×").append((int)s.count);
        Tex count = CookbookTheme.render(CookbookTheme.small, L10n.get("cookbook.var.tried", String.valueOf(s.tried()), String.valueOf(s.options.size())), CookbookTheme.muted);
        texts.add(new Text(count, Coord.of(w - count.sz().x, y + UI.scale(2))));
        Tex name = CookbookTheme.render(CookbookTheme.bold, CookbookTheme.ellipsize(CookbookTheme.bold, head.toString(), w - count.sz().x - UI.scale(8)), CookbookTheme.fg);
        texts.add(new Text(name, Coord.of(0, y)));
        y += name.sz().y;
        String note = s.optional ? L10n.get("cookbook.var.optional") : s.inferred ? L10n.get("cookbook.var.inferred") : null;
        if(note != null)
            y = line(CookbookTheme.small, note, CookbookTheme.muted, 0, y);
        y += UI.scale(3);
        bars.add(new Bar(Coord.of(0, y), w, s.options.isEmpty() ? 0 : (double)s.tried() / s.options.size()));
        y += BAR_H + UI.scale(5);

        List<Variations.Option> untried = new ArrayList<>();
        List<String> tried = new ArrayList<>();
        for(Variations.Option o : s.options) {
            if(o.tried())
                tried.add(o.name + " ×" + o.seen);
            else
                untried.add(o);
        }
        if(s.optional && !s.noneTried)
            untried.add(null);
        else if(s.optional)
            tried.add(L10n.get("cookbook.var.none"));
        if(untried.isEmpty()) {
            y = line(CookbookTheme.body, L10n.get("cookbook.var.all_tried"), CookbookTheme.fg, 0, y);
        } else {
            int x = 0;
            for(Variations.Option o : untried) {
                Tex label = CookbookTheme.render(CookbookTheme.body, (o == null) ? L10n.get("cookbook.var.none") : o.name, CookbookTheme.fg);
                int cw = UI.scale(3) + ((o == null) ? 0 : ICON + UI.scale(4)) + label.sz().x + UI.scale(6);
                if((x > 0) && (x + cw > w)) {
                    x = 0;
                    y += CHIP_H + CHIP_GAP;
                }
                chips.add(new Chip(o, label, Coord.of(x, y), Coord.of(Math.min(cw, w), CHIP_H)));
                x += cw + CHIP_GAP;
            }
            y += CHIP_H;
        }
        if(!tried.isEmpty())
            y = para(L10n.get("cookbook.var.tried_list", String.join(", ", tried)), CookbookTheme.muted, y + UI.scale(4), w);
        if(!s.wood()) {
            int n = crafts(s);
            y += UI.scale(6);
            if(n > 0) {
                Tex label = CookbookTheme.render(CookbookTheme.bold, L10n.get("cookbook.var.vary", String.valueOf(n)), CookbookTheme.fg);
                Coord bsz = Coord.of(label.sz().x + UI.scale(16), CHIP_H + UI.scale(2));
                varyButtons.add(new VaryButton(s, label, Coord.of(0, y), bsz));
                y += bsz.y;
            } else if(untried.size() > (s.optional && !s.noneTried ? 1 : 0)) {
                y = line(CookbookTheme.small, L10n.get("cookbook.var.vary_none"), CookbookTheme.muted, 0, y);
            }
        }
        return y + UI.scale(12);
    }

    private int line(haven.Text.Foundry f, String s, Color c, int x, int y) {
        Tex t = CookbookTheme.render(f, s, c);
        texts.add(new Text(t, Coord.of(x, y)));
        return y + t.sz().y;
    }

    private int para(String s, Color c, int y, int w) {
        Tex t = CookbookTheme.small.renderwrap(s, c, w).tex();
        texts.add(new Text(t, Coord.of(0, y)));
        return y + t.sz().y;
    }

    private int viewH() {
        return Math.max(0, sz.y - bodyY() - UI.scale(2));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, contentH - viewH()));
    }

    @Override
    public void draw(GOut g) {
        layout();
        CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.popBg);
        CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.accent);
        int by = bodyY();
        CookbookTheme.fill(g, Coord.of(PAD, by - UI.scale(3)), Coord.of(sz.x - 2 * PAD, 1), CookbookTheme.line);
        GOut body = g.reclip(Coord.of(PAD, by), Coord.of(sz.x - 2 * PAD, viewH()));
        Coord off = Coord.of(0, -scroll);
        for(VaryButton b : varyButtons) {
            Coord ul = b.c.add(off);
            CookbookTheme.fill(body, ul, b.sz, (b == hovered) ? CookbookTheme.hover : CookbookTheme.bg);
            CookbookTheme.frame(body, ul, b.sz, CookbookTheme.accent);
            body.image(b.label, ul.add((b.sz.x - b.label.sz().x) / 2, (b.sz.y - b.label.sz().y) / 2));
        }
        for(Text t : texts)
            body.image(t.tex, t.c.add(off));
        for(Bar b : bars) {
            CookbookTheme.fill(body, b.c.add(off), Coord.of(b.w, BAR_H), CookbookTheme.hover);
            CookbookTheme.fill(body, b.c.add(off), Coord.of((int)Math.round(b.w * b.share), BAR_H), CookbookTheme.accent);
        }
        for(Chip c : chips) {
            Coord ul = c.c.add(off);
            CookbookTheme.fill(body, ul, c.sz, CookbookTheme.bg);
            if(stocked(c.option)) {
                CookbookTheme.frame(body, ul, c.sz, CookbookTheme.stocked);
                CookbookTheme.frame(body, ul.add(1, 1), c.sz.sub(2, 2), CookbookTheme.stocked);
            } else {
                CookbookTheme.frame(body, ul, c.sz, CookbookTheme.outline);
            }
            int x = ul.x + UI.scale(3);
            if(c.option != null) {
                ItemIcons.draw(body, icon(c.option), Coord.of(x, ul.y + (c.sz.y - ICON) / 2), ICON);
                x += ICON + UI.scale(4);
            }
            body.image(c.label, Coord.of(x, ul.y + (c.sz.y - c.label.sz().y) / 2));
        }
        super.draw(g);
    }

    static Tex icon(Variations.Option o) {
        if(o.resource != null) {
            Tex t = IconCache.get(o.resource);
            if(t != null)
                return t;
        }
        /* By the chip's own name first: "Beef bollock" has its own icon, while its wiki item "Bollock" is drawn as a horse's. */
        Tex t = ItemIcons.get(null, o.name, false);
        return (t != null) ? t : ItemIcons.get(null, o.item, false);
    }

    /* Untried and in the village's storage, by the Storage Items window's rule. */
    private boolean stocked(Variations.Option o) {
        return (o != null) && o.stocked(stored);
    }

    private boolean anyStocked() {
        for(Variations.Slot s : dish.slots) {
            for(Variations.Option o : s.options) {
                if(!o.tried() && stocked(o))
                    return true;
            }
        }
        return false;
    }

    /* A point in the body's own coordinates, or null outside the body. */
    private Coord bodyPoint(Coord c) {
        int by = bodyY();
        if((c.y < by) || (c.y >= by + viewH()))
            return null;
        return c.sub(PAD, by).add(0, scroll);
    }

    private Chip chipAt(Coord c) {
        Coord p = bodyPoint(c);
        if(p == null)
            return null;
        for(Chip ch : chips) {
            if(p.isect(ch.c, ch.sz))
                return ch;
        }
        return null;
    }

    private VaryButton varyAt(Coord c) {
        Coord p = bodyPoint(c);
        if(p == null)
            return null;
        for(VaryButton b : varyButtons) {
            if(p.isect(b.c, b.sz))
                return b;
        }
        return null;
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        Chip ch = chipAt(c);
        if((ch != null) && (ch.option != null))
            return L10n.get(stocked(ch.option) ? "cookbook.var.tip.stocked" : "cookbook.var.tip.untried", ch.option.item);
        if(varyAt(c) != null)
            return L10n.get("cookbook.var.tip.vary");
        return super.tooltip(c, prev);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.propagate(this))
            return true;
        VaryButton b = varyAt(ev.c);
        if((ev.b == 1) && (b != null))
            onVary.accept(b.slot.title);
        return true;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hovered = varyAt(ev.c);
        super.mousemove(ev);
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        scroll += ev.a * WHEEL;
        clampScroll();
        return true;
    }
}
