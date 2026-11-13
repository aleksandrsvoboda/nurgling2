package nurgling.actions.bots.boatfishing;

import haven.Button;
import haven.Label;
import haven.Widget;
import haven.Window;
import nurgling.NUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The window that lists the fish on the line, each with an "aim for" button. Laid out per fish as
 *  "Name:", three percentages (bite, land, overall) and a button. */
final class BiteWindow {
    static final String CAPTION = "This is bait";
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    private BiteWindow() {}

    static final class Row {
        final String fish;
        final double overall;
        final Button aim;

        Row(String fish, double overall, Button aim) {
            this.fish = fish;
            this.overall = overall;
            this.aim = aim;
        }

        @Override
        public String toString() {
            return fish + " " + overall + "%";
        }
    }

    static Window find() {
        return NUtils.getGameUI().getWindow(CAPTION);
    }

    /** The fish rows of an open bite window. UI-safe. */
    static List<Row> rows(Window w) {
        List<Widget> ws = new ArrayList<>();
        for (Widget c = w.lchild; c != null; c = c.prev)
            ws.add(c);
        Collections.reverse(ws);
        List<Button> buttons = new ArrayList<>();
        for (Widget c : ws)
            if (c instanceof Button)
                buttons.add((Button) c);

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < ws.size(); i++) {
            if (!(ws.get(i) instanceof Label))
                continue;
            Label l = (Label) ws.get(i);
            String fish = fishName(l.text());
            if (fish == null)
                continue;
            double overall = -1;
            Button aim = null;
            for (int j = i + 1; j < ws.size(); j++) {
                Widget n = ws.get(j);
                if (n instanceof Button) {
                    aim = (Button) n;
                    break;
                }
                if (n instanceof Label) {
                    if (fishName(((Label) n).text()) != null)
                        break;
                    double p = percent(((Label) n).text());
                    if (p >= 0)
                        overall = p; // the last one is the overall chance
                }
            }
            rows.add(new Row(fish, overall, aim != null ? aim : nearestButton(l, buttons)));
        }
        return rows;
    }

    /** The fish name of a "Name:" label, or null for headers and other labels. */
    static String fishName(String text) {
        if (text == null || !text.endsWith(":"))
            return null;
        String t = text.substring(0, text.length() - 1).trim();
        if (t.isEmpty() || t.equals("This is bait") || t.equals("There are fish around"))
            return null;
        return t;
    }

    static double percent(String text) {
        if (text == null)
            return -1;
        Matcher m = PERCENT.matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    /** Which row to aim for: the best-chance target, else the best fish when {@code goForBest}, else -1 (recast).
     *  With no targets at all, always the best fish. */
    static int choose(List<String> fish, List<Double> overall, List<String> targets, boolean goForBest) {
        int bestTarget = -1, best = -1;
        for (int i = 0; i < fish.size(); i++) {
            if (best < 0 || overall.get(i) > overall.get(best))
                best = i;
            if (targets.contains(fish.get(i)) && (bestTarget < 0 || overall.get(i) > overall.get(bestTarget)))
                bestTarget = i;
        }
        if (bestTarget >= 0)
            return bestTarget;
        return (goForBest || targets.isEmpty()) ? best : -1;
    }

    /** Fallback when no button follows the label in widget order: the one closest to the label's row. */
    private static Button nearestButton(Label l, List<Button> buttons) {
        int ly = l.c.y + l.sz.y / 2;
        Button res = null;
        int bestd = Integer.MAX_VALUE;
        for (Button b : buttons) {
            int d = Math.abs(b.c.y + b.sz.y / 2 - ly);
            if (d < bestd) {
                bestd = d;
                res = b;
            }
        }
        return res;
    }
}
