package nurgling.animals;

import nurgling.conf.CowsHerd;
import nurgling.conf.GoatsHerd;
import nurgling.conf.HorseHerd;
import nurgling.conf.PigsHerd;
import nurgling.conf.SheepsHerd;
import nurgling.conf.TeimDeerHerd;

import java.awt.Color;

/**
 * Reads each species' herd preset into {@link HerdRules}. Term order follows the order the original
 * rang() of that species summed its terms, which keeps floating-point results identical.
 */
public final class HerdRulesFactory {
    /** How many adult males the animal bots keep. Not a preset setting. */
    public static final int KEEP_MALES = 1;
    public static final int HORSE_KEEP_MALES = 3;

    public static final Color MEAT_Q = new Color(232, 145, 58);
    public static final Color MEAT_N = new Color(208, 100, 60);
    public static final Color MILK_Q = new Color(127, 211, 200);
    public static final Color MILK_N = new Color(74, 163, 223);
    public static final Color WOOL_Q = new Color(224, 210, 122);
    public static final Color WOOL_N = new Color(184, 166, 74);
    public static final Color HIDE_Q = new Color(201, 162, 107);
    public static final Color TRUFFLE = new Color(163, 122, 204);
    public static final Color STAMINA = new Color(111, 195, 111);
    public static final Color ENDURANCE = new Color(63, 168, 111);
    public static final Color METABOLISM = new Color(195, 111, 159);

    private HerdRulesFactory() {
    }

    public static HerdRules of(CowsHerd h) {
        if (h == null)
            return null;
        return new HerdRules.Builder("cows", h.name)
                .flags(h.ignoreChildren, h.disable_killing, h.ignoreBD, h.disable_q_percentage)
                .keep(h.adultCows, KEEP_MALES)
                .breeding(h.breedingGap, h.coverbreed)
                .term(RankTerm.percent("meatq", "Meat quality", h.meatq, MEAT_Q))
                .term(RankTerm.threshold("meat", "Meat quantity", h.meatquan1, h.meatquanth, h.meatquan2, MEAT_N))
                .term(RankTerm.percent("milkq", "Milk quality", h.milkq, MILK_Q))
                .term(RankTerm.threshold("milk", "Milk quantity", h.milkquan1, h.milkquanth, h.milkquan2, MILK_N))
                .term(RankTerm.percent("hideq", "Hide quality", h.hideq, HIDE_Q))
                .build();
    }

    public static HerdRules of(GoatsHerd h) {
        if (h == null)
            return null;
        return new HerdRules.Builder("goats", h.name)
                .flags(h.ignoreChildren, h.disable_killing, h.ignoreBD, h.disable_q_percentage)
                .keep(h.adultGoats, KEEP_MALES)
                .breeding(h.breedingGap, h.coverbreed)
                .term(RankTerm.percent("meatq", "Meat quality", h.meatq, MEAT_Q))
                .term(RankTerm.threshold("meat", "Meat quantity", h.meatquan1, h.meatquanth, h.meatquan2, MEAT_N))
                .term(RankTerm.percent("milkq", "Milk quality", h.milkq, MILK_Q))
                .term(RankTerm.threshold("milk", "Milk quantity", h.milkquan1, h.milkquanth, h.milkquan2, MILK_N))
                .term(RankTerm.percent("woolq", "Wool quality", h.woolq, WOOL_Q))
                .term(RankTerm.threshold("wool", "Wool quantity", h.woolquan1, h.woolquanth, h.woolquan2, WOOL_N))
                .term(RankTerm.percent("hideq", "Hide quality", h.hideq, HIDE_Q))
                .build();
    }

    public static HerdRules of(SheepsHerd h) {
        if (h == null)
            return null;
        return new HerdRules.Builder("sheeps", h.name)
                .flags(h.ignoreChildren, h.disable_killing, h.ignoreBD, h.disable_q_percentage)
                .keep(h.adultSheeps, KEEP_MALES)
                .breeding(h.breedingGap, h.coverbreed)
                .term(RankTerm.percent("meatq", "Meat quality", h.meatq, MEAT_Q))
                .term(RankTerm.threshold("meat", "Meat quantity", h.meatquan1, h.meatquanth, h.meatquan2, MEAT_N))
                .term(RankTerm.percent("milkq", "Milk quality", h.milkq, MILK_Q))
                .term(RankTerm.threshold("milk", "Milk quantity", h.milkquan1, h.milkquanth, h.milkquan2, MILK_N))
                .term(RankTerm.percent("woolq", "Wool quality", h.woolq, WOOL_Q))
                .term(RankTerm.threshold("wool", "Wool quantity", h.woolquan1, h.woolquanth, h.woolquan2, WOOL_N))
                .term(RankTerm.percent("hideq", "Hide quality", h.hideq, HIDE_Q))
                .build();
    }

    public static HerdRules of(PigsHerd h) {
        if (h == null)
            return null;
        return new HerdRules.Builder("pigs", h.name)
                .flags(h.ignoreChildren, h.disable_killing, h.ignoreBD, h.disable_q_percentage)
                .keep(h.adultPigs, KEEP_MALES)
                .breeding(h.breedingGap, h.coverbreed)
                .term(RankTerm.threshold("truffle", "Truffle snout", h.trufquan1, h.trufquanth, h.trufquan2, TRUFFLE))
                .term(RankTerm.percent("meatq", "Meat quality", h.meatq, MEAT_Q))
                .term(RankTerm.threshold("meat", "Meat quantity", h.meatquan1, h.meatquanth, h.meatquan2, MEAT_N))
                .term(RankTerm.percent("milkq", "Milk quality", h.milkq, MILK_Q))
                .term(RankTerm.threshold("milk", "Milk quantity", h.milkquan1, h.milkquanth, h.milkquan2, MILK_N))
                .term(RankTerm.percent("hideq", "Hide quality", h.hideq, HIDE_Q))
                .build();
    }

    public static HerdRules of(HorseHerd h) {
        if (h == null)
            return null;
        return new HerdRules.Builder("horses", h.name)
                .flags(h.ignoreChildren, h.disable_killing, h.ignoreBD, h.disable_q_percentage)
                .keep(h.adultHorse, HORSE_KEEP_MALES)
                .breeding(h.breedingGap, h.coverbreed)
                .term(RankTerm.percent("meatq", "Meat quality", h.meatq, MEAT_Q))
                .term(RankTerm.threshold("meat", "Meat quantity", h.meatquan1, h.meatquanth, h.meatquan2, MEAT_N))
                .term(RankTerm.percent("milkq", "Milk quality", h.milkq, MILK_Q))
                .term(RankTerm.threshold("milk", "Milk quantity", h.milkquan1, h.milkquanth, h.milkquan2, MILK_N))
                .term(RankTerm.threshold("stam", "Stamina", h.stam1, h.stamth, h.stam2, STAMINA))
                .term(RankTerm.linear("end", "Endurance", h.enduran, ENDURANCE))
                .term(RankTerm.linear("mb", "Metabolism", h.meta, METABOLISM))
                .term(RankTerm.percent("hideq", "Hide quality", h.hideq, HIDE_Q))
                .build();
    }

    public static HerdRules of(TeimDeerHerd h) {
        if (h == null)
            return null;
        return new HerdRules.Builder("deer", h.name)
                .flags(h.ignoreChildren, h.disable_killing, h.ignoreBD, h.disable_q_percentage)
                .keep(h.adultDeers, KEEP_MALES)
                .breeding(h.breedingGap, h.coverbreed)
                .term(RankTerm.percent("meatq", "Meat quality", h.meatq, MEAT_Q))
                .term(RankTerm.threshold("meat", "Meat quantity", h.meatquan1, h.meatquanth, h.meatquan2, MEAT_N))
                .term(RankTerm.percent("milkq", "Milk quality", h.milkq, MILK_Q))
                .term(RankTerm.threshold("milk", "Milk quantity", h.milkquan1, h.milkquanth, h.milkquan2, MILK_N))
                .term(RankTerm.percent("hideq", "Hide quality", h.hideq, HIDE_Q))
                .build();
    }
}
