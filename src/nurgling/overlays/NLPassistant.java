package nurgling.overlays;

import haven.*;
import nurgling.NUtils;
import nurgling.tools.LpExplorer;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/**
 * Small floating icon marking a gob with undiscovered LP products - the same screen-space label
 * style NTreeHarvestOl uses for its harvest icons, not a 3D ground overlay. Shows every
 * currently-undiscovered product's own icon (e.g. an apple for an undiscovered apple tree, or
 * Board/Block for a log with either still unpicked) stacked together and lightly tinted green,
 * via LpExplorer.getMarkerIcon() - falling back to a generic marker if no icon can be resolved at
 * all.
 *
 * This is a fallback only: when a gob is a tree/bush AND "Show harvest icons on trees" is
 * enabled, NTreeHarvestOl itself tints its own seed icon instead (see LpExplorer usage in
 * NTreeHarvestOl.computeLabel()) rather than showing a second, separate marker. NLPassistant
 * only attaches when that display isn't available - harvest-icons disabled, or a resource
 * NTreeHarvestOl doesn't cover at all (ground herbs/mushrooms, mineable rocks, felled logs).
 */
public class NLPassistant extends NObjectTexLabel
{
    private final Gob gob;
    // Which products the currently-shown icon(s) represent - a gob can track more than one
    // product (e.g. a log's Board and Block), so the icon needs to be re-resolved whenever this
    // changes, not just set once at construction.
    private List<String> shownProducts = Collections.emptyList();

    public NLPassistant(Owner owner)
    {
        super(owner);
        this.gob = (Gob) owner;
        refresh(currentProducts());
    }

    private List<String> currentProducts()
    {
        try
        {
            return LpExplorer.allUndiscoveredProducts(gob);
        }
        catch (Loading l)
        {
            return shownProducts; // Sprite still loading; keep whatever we last had.
        }
    }

    private void refresh(List<String> products)
    {
        shownProducts = products;
        TexI icon = LpExplorer.getMarkerIcon(gob, products);
        // Same framed presentation NTreeHarvestOl's own harvest-icon label uses, so ours reads
        // as the same family of UI element - just with the icon(s) themselves tinted to stand out.
        BufferedImage framed = icon != null ? icon.back
            : NTreeHarvestOl.catimgshCentered(1, NTreeHarvestOl.tint(Resource.loadimg("marks/newlpassistant"), NTreeHarvestOl.LP_UNDISCOVERED_TINT));
        TexI tinted = new TexI(framed);
        this.label = tinted;
        this.img = tinted;
    }

    @Override
    public boolean tick(double dt)
    {
        if (!LpExplorer.isEnabled() || NUtils.getGameUI() == null)
            return true;
        // NTreeHarvestOl handles display for this gob itself once harvest-icons are on.
        if (gob.ngob != null && NTreeHarvestOl.coversGob(gob.ngob.name))
            return true;

        List<String> products;
        try
        {
            products = LpExplorer.allUndiscoveredProducts(gob);
        }
        catch (Loading l)
        {
            return false; // Sprite still loading; keep the overlay, re-check next tick.
        }
        if (products.isEmpty())
            return true; // Everything this gob tracks has been discovered - remove.
        if (!products.equals(shownProducts))
            refresh(products);
        return false;
    }
}
