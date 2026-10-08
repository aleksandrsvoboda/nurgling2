package nurgling.tools;

import haven.*;
import haven.res.ui.relcnt.RelCont;
import haven.res.ui.tt.q.qbuff.QBuff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a barrel or cistern window says it holds. The window carries a RelCont whose TipLabel
 * (a class loaded from the res server, hence the reflection) lists the content as item info:
 * a Name such as "45.00 l of Water" or "1234 seeds of Flax", or an AdHoc "Empty".
 */
public final class LiquidContent {
    /** "45.00 l of Water", "1234 seeds of Flax": amount, unit, substance. */
    private static final Pattern AMOUNT_OF = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s+(.+?)\\s+of\\s+(.+)$");

    public static final LiquidContent EMPTY = new LiquidContent(null, 0, null, null);

    /** Display name, matching what the same thing is called in an inventory ("Water", "Flax Seeds"). */
    public final String name;
    public final double amount;
    public final String unit;
    /** Null when the window shows no quality. */
    public final Double quality;

    private LiquidContent(String name, double amount, String unit, Double quality) {
        this.name = name;
        this.amount = amount;
        this.unit = unit;
        this.quality = quality;
    }

    public boolean isEmpty() {
        return name == null;
    }

    /**
     * Every info entry of every TipLabel in the window, in window order. Empty until the window's
     * RelCont and labels have arrived.
     */
    @SuppressWarnings("unchecked")
    public static List<ItemInfo> tipInfos(Window wnd) {
        List<ItemInfo> result = new ArrayList<>();
        for (Widget sp = wnd.lchild; sp != null; sp = sp.prev) {
            if (sp instanceof RelCont) {
                for (Pair<Widget, Supplier<Coord>> pair : ((RelCont) sp).childpos) {
                    if (pair.a.getClass().getName().contains("TipLabel")) {
                        try {
                            result.addAll((Collection<ItemInfo>) pair.a.getClass().getField("info").get(pair.a));
                        } catch (NoSuchFieldException | IllegalAccessException e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Parse the window. EMPTY for an empty container, null while the content has not arrived or is
     * in a form this parser does not know.
     */
    public static LiquidContent read(Window wnd) {
        String name = null;
        Double quality = null;
        boolean empty = false;
        for (ItemInfo inf : tipInfos(wnd)) {
            if (inf instanceof ItemInfo.Name) {
                if (name == null)
                    name = ((ItemInfo.Name) inf).str.text;
            } else if (inf instanceof QBuff) {
                if (quality == null)
                    quality = ((QBuff) inf).q;
            } else if (inf instanceof ItemInfo.AdHoc) {
                if (NParser.checkName(((ItemInfo.AdHoc) inf).str.text, "Empty"))
                    empty = true;
            }
        }
        if (name == null)
            return empty ? EMPTY : null;
        Matcher m = AMOUNT_OF.matcher(name.trim());
        if (!m.matches())
            return null;
        double amount = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        String substance = m.group(3).trim();
        // Inventories call seeds "Flax Seeds"; the barrel says "1234 seeds of Flax".
        String display = unit.equalsIgnoreCase("seeds") ? substance + " Seeds" : substance;
        return new LiquidContent(display, amount, unit, quality);
    }
}
