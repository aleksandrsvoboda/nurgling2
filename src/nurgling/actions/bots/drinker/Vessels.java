package nurgling.actions.bots.drinker;

import java.util.List;
import java.util.Locale;

/**
 * The drinks the Drinker bot offers, the cup each one is drunk from by default, and every drinking
 * vessel the game has. A drink taken from the wrong vessel only counts at half its quality, so the
 * default is the drink's preferred vessel where the game has one; the user can pick another cup per
 * drink. Names are the in-game item names: a cup is matched by its exact name, since "Mug" is also
 * part of "Metal Mug" and a substring match would pick the wrong vessel.
 */
public final class Vessels {

    public static final class Drink {
        public final String name;
        /** Icon for the area item picker. */
        public final String res;
        public final String cup;
        /** Area entries the drink is taken from; milk is kept by kind. */
        public final List<String> areaNames;

        Drink(String name, String res, String cup) {
            this(name, res, cup, List.of(name));
        }

        Drink(String name, String res, String cup, List<String> areaNames) {
            this.name = name;
            this.res = res;
            this.cup = cup;
            this.areaNames = areaNames;
        }

        /** Whether a cup or barrel holding {@code content} ("Wine", "Cowsmilk") holds this drink. */
        public boolean holds(String content) {
            if (content == null)
                return false;
            if (this == MILK)
                return content.toLowerCase(Locale.ROOT).contains("milk");
            return content.equalsIgnoreCase(name);
        }
    }

    public static final class Cup {
        public final String name;
        public final String res;

        Cup(String name, String res) {
            this.name = name;
            this.res = res;
        }
    }

    /** Milk has no preferred vessel and comes in kinds, each with its own area entry. */
    public static final Drink MILK = new Drink("Milk", "gfx/invobjs/milk", "Wooden Cup",
            List.of("Cowsmilk", "Goatsmilk", "Sheepsmilk", "Generic Milk"));

    public static final List<Drink> DRINKS = List.of(
            new Drink("Wine", "gfx/invobjs/food/wine", "Wine Glass"),
            new Drink("Beer", "gfx/invobjs/food/beer", "Tankard"),
            // Weißbier and Millet Beer have no art of their own; the game draws them as beer.
            new Drink("Weißbier", "gfx/invobjs/food/beer", "Tankard"),
            new Drink("Millet Beer", "gfx/invobjs/food/beer", "Tankard"),
            new Drink("Cider", "gfx/invobjs/food/cider", "Tankard"),
            new Drink("Perry", "gfx/invobjs/food/perry", "Tankard"),
            new Drink("Mead", "gfx/invobjs/food/mead", "Drinking Horn"),
            new Drink("Tea", "gfx/invobjs/food/tea", "Mug"),
            // The game has no preferred vessel for brandy, juices or milk.
            new Drink("Brandy", "gfx/invobjs/food/brandy", "Wine Glass"),
            new Drink("Grape Juice", "gfx/invobjs/food/grapejuice", "Wine Glass"),
            new Drink("Apple Juice", "gfx/invobjs/food/applejuice", "Wooden Cup"),
            MILK
    );

    public static final List<Cup> CUPS = List.of(
            new Cup("Wine Glass", "gfx/invobjs/wineglass"),
            new Cup("Tankard", "gfx/invobjs/tankard"),
            new Cup("Drinking Horn", "gfx/invobjs/drinkinghorn"),
            new Cup("Mug", "gfx/invobjs/mug"),
            new Cup("Metal Mug", "gfx/invobjs/metalmug"),
            new Cup("Wooden Cup", "gfx/invobjs/woodencup"),
            new Cup("Birchbark Kuksa", "gfx/invobjs/kuksa"),
            new Cup("Whitemetal Cup", "gfx/invobjs/whitemetalcup"),
            new Cup("Silver River Goblet", "gfx/invobjs/silverrivergoblet"),
            new Cup("Strange Crystal Glass", "gfx/invobjs/strangecrystalglass")
    );

    private Vessels() {
    }

    /** Whether an area entry of this name is one of the drinks (a kind of milk counts as milk). */
    public static boolean isDrink(String name) {
        for (Drink d : DRINKS) {
            if (d.areaNames.contains(name))
                return true;
        }
        return false;
    }

    public static Drink drink(String name) {
        for (Drink d : DRINKS) {
            if (d.name.equals(name))
                return d;
        }
        return null;
    }

    public static boolean isCup(String name) {
        for (Cup c : CUPS) {
            if (c.name.equals(name))
                return true;
        }
        return false;
    }

    /** The cup a drink is drunk from unless the user picked another, or null for an unknown drink. */
    public static String defaultCup(String drink) {
        for (Drink d : DRINKS) {
            if (d.name.equals(drink))
                return d.cup;
        }
        return null;
    }
}
