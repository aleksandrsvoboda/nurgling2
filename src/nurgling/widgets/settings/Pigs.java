package nurgling.widgets.settings;

import haven.*;
import haven.res.ui.croster.CattleRoster;
import nurgling.animals.HerdRulesFactory;
import nurgling.conf.PigsHerd;


public class Pigs extends Window {
    NEntryListSet els;
    HerdPreviewPanel preview;
    NSettinsSetI totalAdult;
    NSettinsSetI gap;
    NSettinsSetD meatQuality;
    NSettinsSetD hideQuality;
    NSettinsSetD milkQuality;
    NSettinsSetD milk1;
    NSettinsSetD milk2;
    NSettinsSetD milkth;
    NSettinsSetD truf1;
    NSettinsSetD truf2;
    NSettinsSetD trufth;
    NSettinsSetD meatq1;
    NSettinsSetD meatq2;
    NSettinsSetD meatqth;
    NSettinsSetD coverbreed;

    CheckBox ic;
    CheckBox dk;
    CheckBox ignorebd;
    CheckBox ignoreqp;

    public Pigs(CattleRoster<?> roster) {
        super(new Coord(100,100), "Pigs Herds");
        prev = els = add(new NEntryListSet(PigsHerd::getKeySet) {
            @Override
            public void nsave() {
                String name = this.get();
                if(!name.isEmpty()) {
                    PigsHerd gh = new PigsHerd(name);
                    PigsHerd.set(gh);
                    totalAdult.setVal(gh.adultPigs);
                    gap.setVal(gh.breedingGap);
                    coverbreed.setVal(gh.coverbreed);
                    meatQuality.setVal(gh.meatq);
                    hideQuality.setVal(gh.hideq);
                    milkQuality.setVal(gh.milkq);
                    milk1.setVal(gh.milkquan1);
                    milkth.setVal(gh.milkquanth);
                    milk2.setVal(gh.milkquan2);
                    truf1.setVal(gh.trufquan1);
                    truf2.setVal(gh.trufquan2);
                    trufth.setVal(gh.trufquanth);
                    meatq1.setVal(gh.meatquan1);
                    meatqth.setVal(gh.meatquanth);
                    meatq2.setVal(gh.meatquan2);
                    ic.set(gh.ignoreChildren);
                    dk.set(gh.disable_killing);
                    ignorebd.set(gh.ignoreBD);
                    ignoreqp.set(gh.disable_q_percentage);
                }
            }
            @Override
            public void oldsave()
            {
                PigsHerd gh = PigsHerd.get(name.text());
                {
                    gh.adultPigs = totalAdult.get();
                    gh.breedingGap = gap.get();
                    gh.coverbreed = coverbreed.get();
                    gh.meatq = meatQuality.get();
                    gh.hideq = hideQuality.get();
                    gh.milkq = milkQuality.get();
                    gh.milkquan1 = milk1.get();
                    gh.milkquanth = milkth.get();
                    gh.milkquan2 = milk2.get();
                    gh.meatquan1 = meatq1.get();
                    gh.meatquanth = meatqth.get();
                    gh.meatquan2 = meatq2.get();
                    gh.trufquan1 = truf1.get();
                    gh.trufquanth = trufth.get();
                    gh.trufquan2 = truf2.get();
                    gh.disable_killing = dk.a;
                    gh.ignoreBD = ignorebd.a;
                    gh.ignoreChildren = ic.a;
                    gh.disable_q_percentage = ignoreqp.a;
                }
                PigsHerd.set(gh);
            }

            @Override
            public void nchange() {
                PigsHerd gh = PigsHerd.get(name.text());;
                totalAdult.setVal(gh.adultPigs);
                gap.setVal(gh.breedingGap);
                coverbreed.setVal(gh.coverbreed);
                meatQuality.setVal(gh.meatq);
                hideQuality.setVal(gh.hideq);
                milkQuality.setVal(gh.milkq);
                milk1.setVal(gh.milkquan1);
                milkth.setVal(gh.milkquanth);
                milk2.setVal(gh.milkquan2);
                meatq1.setVal(gh.meatquan1);
                meatqth.setVal(gh.meatquanth);
                meatq2.setVal(gh.meatquan2);
                truf1.setVal(gh.trufquan1);
                trufth.setVal(gh.trufquanth);
                truf2.setVal(gh.trufquan2);
                ic.set(gh.ignoreChildren);
                dk.set(gh.disable_killing);
                ignorebd.set(gh.ignoreBD);
                ignoreqp.set(gh.disable_q_percentage);
                PigsHerd.setCurrent(name.text());
            }

            @Override
            public void ndelete() {
                PigsHerd.remove(name.text());
                if(PigsHerd.getCurrent()!=null)
                    update(PigsHerd.getCurrent().name);
                else
                    update("");
            }
        });
        prev = add(new Label("Main settings:"), prev.pos("bl").add(0, 5));
        ic = (CheckBox)(prev = add (new CheckBox("Save cubs"){
            @Override
            public void changed(boolean val) {
                super.changed(val);
            }
        }, prev.pos("bl").add(0, UI.scale(5))));

        dk = (CheckBox)(prev = add (new CheckBox("Disable slaughting"){
            @Override
            public void changed(boolean val) {
                super.changed(val);
            }
        }, prev.pos("bl").add(0, UI.scale(5))));

        ignorebd = (CheckBox)(prev = add (new CheckBox("Ignore breading for female"){
            @Override
            public void changed(boolean val) {
                super.changed(val);
            }
        }, prev.pos("bl").add(0, UI.scale(5))));

        ignoreqp = (CheckBox)(prev = add (new CheckBox("Ignore quality for % score") {
            @Override
            public void changed(boolean val) {
                super.changed(val);
            }
        }, prev.pos("bl").add(0, UI.scale(5))));


        if(PigsHerd.getCurrent()!=null) {
            ignoreqp.set(PigsHerd.getCurrent().disable_q_percentage);
            ignorebd.set(PigsHerd.getCurrent().ignoreBD);
            dk.set(PigsHerd.getCurrent().disable_killing);
            ic.set(PigsHerd.getCurrent().ignoreChildren);
        }

        prev = totalAdult = add(new NSettinsSetI("Total adult:"), prev.pos("bl").add(0, 5));
        prev = gap = add(new NSettinsSetI("Gap:"), prev.pos("bl").add(0, 5));
        prev = coverbreed = add(new NSettinsSetD("Overbreed ( 0.0 ... 0.3 ):"), prev.pos("bl").add(0, 5));
        prev = add(new Label("Rank settings:"), prev.pos("bl").add(0, 15));
        prev = add(new Label("All coefficients are arbitrary, only relations between them matters."), prev.pos("bl").add(0, 5));
        prev = meatQuality = add(new NSettinsSetD("Meat:"), prev.pos("bl").add(0, 5));
        prev = milkQuality = add(new NSettinsSetD("Milk:"), prev.pos("bl").add(0, 5));
        prev = hideQuality = add(new NSettinsSetD("Hide:"), prev.pos("bl").add(0, 5));
        prev = add(new Label("Follow stats may be tracked with different coefficients below and above threshold. If you want to ignore threshold, set both coefficients equal."), prev.pos("bl").add(0, 5));
        prev = add(new Label("If you want to track stat up to threshold, but ignore stat gain over threshold simply set second coefficient to zero."), prev.pos("bl").add(0, 5));
        prev = meatq1 = add(new NSettinsSetD("Meat quantity 1:"), prev.pos("bl").add(0, 5));
        prev = meatqth = add(new NSettinsSetD("Meat quantity threshold:"), prev.pos("bl").add(0, 5));
        prev = meatq2 = add(new NSettinsSetD("Meat quantity 2:"), prev.pos("bl").add(0, 5));
        prev = milk1 = add(new NSettinsSetD("Milk quantity 1:"), prev.pos("bl").add(0, 5));
        prev = milkth = add(new NSettinsSetD("Milk quantity threshold:"), prev.pos("bl").add(0, 5));
        prev = milk2 = add(new NSettinsSetD("Milk quantity 2:"), prev.pos("bl").add(0, 5));
        prev = truf1 = add(new NSettinsSetD("Truffle snort quantity 1:"), prev.pos("bl").add(0, 5));
        prev = trufth = add(new NSettinsSetD("Truffle snort quantity threshold:"), prev.pos("bl").add(0, 5));
        prev = truf2 = add(new NSettinsSetD("Truffle snort quantity 2:"), prev.pos("bl").add(0, 5));

        preview = add(new HerdPreviewPanel(roster, "pigs", () -> els.get(), () -> HerdRulesFactory.of(draft()), () -> HerdRulesFactory.of(saved())),
                new Coord(HerdPreviewPanel.rightEdge(this) + UI.scale(15), 0));
        pack();
    }

    /** The preset as currently typed into the window, not saved. */
    private PigsHerd draft() {
        PigsHerd gh = new PigsHerd(els.get());
        gh.adultPigs = totalAdult.get();
        gh.breedingGap = gap.get();
        gh.coverbreed = coverbreed.get();
        gh.meatq = meatQuality.get();
        gh.hideq = hideQuality.get();
        gh.milkq = milkQuality.get();
        gh.milkquan1 = milk1.get();
        gh.milkquanth = milkth.get();
        gh.milkquan2 = milk2.get();
        gh.meatquan1 = meatq1.get();
        gh.meatquanth = meatqth.get();
        gh.meatquan2 = meatq2.get();
        gh.trufquan1 = truf1.get();
        gh.trufquanth = trufth.get();
        gh.trufquan2 = truf2.get();
        gh.disable_killing = dk.a;
        gh.ignoreBD = ignorebd.a;
        gh.ignoreChildren = ic.a;
        gh.disable_q_percentage = ignoreqp.a;
        return gh;
    }

    /** The preset as saved, or null when this name has never been saved. */
    private PigsHerd saved() {
        String name = els.get();
        return PigsHerd.getKeySet().contains(name) ? PigsHerd.get(name) : null;
    }

    @Override
    public void show() {
        if(PigsHerd.getCurrent()!=null)
            els.update(PigsHerd.getCurrent().name);
        super.show();
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if(msg.equals("close"))
        {
            hide();
        }
        else {
            super.wdgmsg(msg, args);
        }
    }

    @Override
    public void resize(Coord sz) {
        super.resize(sz);
        els.resize((preview == null ? sz.x : preview.c.x - UI.scale(10)) - UI.scale(5), els.sz.y);
    }
}
