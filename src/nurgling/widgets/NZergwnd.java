package nurgling.widgets;

import haven.*;
import nurgling.i18n.L10n;

import java.util.*;

public class NZergwnd extends GameUI.Hidewnd
{
    public final Tabs tabs = new Tabs(Coord.z, Coord.z, this);
    public final NZergwnd.TButton kin;
    public final Collection<PTab<Category>> types = new ArrayList<>();

    public static class Category extends Widget {
        public final String id;
        public final List<Polity> pols = new ArrayList<>();
        public final Widget cap;
        public Widget sel = null;
        private Coord polc = Coord.z;

        public Category(String id, String name) {
            this.id = id;
            cap = add(new Img(CharWnd.catf.render(name).tex()));
        }

        public class Selector extends SDropBox<Polity, Widget> {
            public Selector() {
                super(BuddyWnd.width, UI.scale(75), Polity.nmf.height());
                for(Widget ch : Category.this.children()) {
                    if((ch instanceof Polity) && ch.visible()) {
                        super.change((Polity)ch);
                        break;
                    }
                }
            }

            protected List<Polity> items() {return(pols);}
            protected Widget makeitem(Polity pol, int idx, Coord sz) {
                return(SListWidget.TextItem.of(sz, Polity.nmf, () -> pol.name));
            }

            public void change(Polity pol) {
                super.change(pol);
                select(pol);
            }
        }

        private void updsel() {
            if(sel != null)
                sel.destroy();
            if(pols.isEmpty()) {
                sel = null;
            } else if(pols.size() == 1) {
                sel = new Label(pols.get(0).name, Polity.nmf);
            } else {
                sel = new Selector();
            }
            Coord c = cap.pos("bl").adds(0, 2);
            if(sel != null)
                c = add(sel, c).pos("bl").adds(0, 5);
            if(!Utils.eq(c, polc)) {
                polc = c;
                for(Polity pol : pols)
                    pol.move(polc);
                pack();
            }
        }

        public void select(Polity sel) {
            for(Polity pol : pols)
                pol.show(pol == sel);
            pack();
        }

        public void cresize(Widget ch) {
            pack();
        }

        public void addpol(Polity p) {
            pols.add(add(p));
            if(sel != null)
                p.move(polc);
            select(p);
            updsel();
        }

        public void cdestroy(Widget w) {
            if(pols.contains(w)) {
                pols.remove(w);
                updsel();
                if(pols.isEmpty()) {
                    destroy();
                } else {
                    if(w.visible) {
                        if(pols.size() > 1)
                            ((Selector)sel).change(pols.get(0));
                        else
                            pols.get(0).show(true);
                    }
                }
            }
        }
    }

    public class PTab<W extends Widget> extends Tabs.Tab {
        public final W main;
        public final NZergwnd.TButton tb;

        public PTab(W main, NZergwnd.TButton tb) {
            tabs.super();
            this.main = main;
            this.tb = tb;
        }

        public void cdestroy(Widget w) {
            if(w == main) {
                destroy();
                tb.destroy();
                NZergwnd.this.types.remove(this);
                repack();
                if(tabs.curtab == this) {
                    tabs.showtab(kin.tab);
                    repack();
                }
            }
        }

        public void cresize(Widget ch) {
            repack();
        }
    }

    public class TButton extends IButton {
        public final Resource.Image upimg;
        public PTab tab = null;

        TButton(String nm) {
            super("gfx/hud/buttons/" + nm, "u", "d", null);
            upimg = Resource.loadrimg("gfx/hud/buttons/" + nm + "u");
            Resource.Tooltip tt = upimg.getres().layer(Resource.tooltip);
            if(tt != null)
                settip(tt.t);
        }

        public void click() {
            if(tab != null) {
                tabs.showtab(tab);
                repack();
            }
        }

        protected void depress() {
            ui.sfx(Button.clbtdown.stream());
        }

        protected void unpress() {
            ui.sfx(Button.clbtup.stream());
        }
    }

    public NZergwnd() {
        super(Coord.z, L10n.get("opt.keybind.kith_kin"), true);
        kin = add(new NZergwnd.TButton("kin"));
        kin.tooltip = Text.render(L10n.get("kin.window_title"));
    }

    private void repack() {
        tabs.indpack();
        kin.move(Coord.of(0, tabs.curtab.contentsz().y + UI.scale(20)));
        List<NZergwnd.TButton> pbtns = new ArrayList<>();
        for(Widget ch : children()) {
            if((ch instanceof NZergwnd.TButton) && (ch != kin))
                pbtns.add((NZergwnd.TButton)ch);
        }
        pbtns.sort((a, b) -> a.upimg.z - b.upimg.z);
        Widget lf = kin, prev = lf;
        int x = 1;
        for(NZergwnd.TButton pbtn : pbtns) {
            if(x < 3) {
                pbtn.move(prev.pos("ur").adds(10, 0));
                prev = pbtn;
                x++;
            } else {
                pbtn.move(lf.pos("bl").adds(0, 10));
                lf = prev = pbtn;
                x = 0;
            }
        }
        this.pack();
    }

    public <W extends Widget> PTab<W> ntab(W ch, NZergwnd.TButton tb) {
        PTab<W> tab = add(new PTab<>(ch, tb), tabs.c);
        tab.add(ch, Coord.z);
        tb.tab = tab;
        repack();
        return(tab);
    }

    private PTab<Category> getptab(String name) {
        PTab<Category> tab = null;
        for(PTab<Category> cur : types) {
            if(Utils.eq(cur.main.id, name)) {
                tab = cur;
                break;
            }
        }
        if(tab == null) {
            NZergwnd.TButton tb = add(new NZergwnd.TButton(name));
            tab = ntab(new Category(name, tb.upimg.getres().flayer(Resource.tooltip).t), tb);
            types.add(tab);
        }
        return(tab);
    }

    public void addpol(Polity p) {
        getptab(p.type()).main.addpol(p);
    }
}
