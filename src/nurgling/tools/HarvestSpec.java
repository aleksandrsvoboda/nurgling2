package nurgling.tools;

import haven.Gob;
import haven.ResDrawable;
import nurgling.NConfig;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Per-gob-type configuration for the always-visible harvest-icon overlay (NObjHarvestOl): which
 * resources it applies to, how its icons are laid out, which setting turns it on, and what
 * icon(s) it currently has to show. Two shapes of gob exist today - see TreeHarvestSpec/
 * BushHarvestSpec (fixed category slots read from a live per-instance bitmask) and
 * ProductListHarvestSpec (a flat VSpec.object product list, always "present") - both implement
 * this same interface so NObjHarvestOl doesn't need to know which kind of gob it's showing.
 */
public interface HarvestSpec {
    /** Whether this spec applies to a gob with this resource name. */
    boolean matches(String gobResName);

    /** Lay icons out left-to-right (true) or top-to-bottom (false). */
    boolean horizontal();

    /** The options-menu toggle that turns this type's overlay on/off entirely. */
    NConfig.Key masterToggle();

    /**
     * The current set of icons to show for this gob, each flagged if its product is still
     * LP-undiscovered (so NObjHarvestOl can tint just that one). May throw haven.Loading if the
     * gob's sprite hasn't loaded yet. Empty if nothing should be shown right now.
     */
    List<Part> parts(Gob gob, ResDrawable d);

    // Shared by TreeHarvestSpec/BushHarvestSpec: only add a category's icon if it's actually
    // shown and resolvable, so callers can build their part list with one line per category
    // instead of repeating this null/shown check for each one. Not every known species tracks
    // every category (most trees have no distinct leaf product, for instance), so a category
    // simply not resolving here is normal, not a sign of an unrecognized species - see
    // TreeHarvestSpec/BushHarvestSpec's species-level "?" check for that instead.
    static void addPart(List<Part> parts, String id, boolean shown, BufferedImage img, boolean undiscovered) {
        if (shown && img != null)
            parts.add(new Part(id, img, undiscovered));
    }

    class Part {
        // Stable identifier for this icon slot (e.g. "seed", "leaf", or a log/stone's exact
        // product name) - used to build NObjHarvestOl's per-gob label cache key, since the same
        // BufferedImage instance isn't guaranteed a useful identity for that on its own.
        public final String id;
        public final BufferedImage icon;
        public final boolean undiscovered;

        public Part(String id, BufferedImage icon, boolean undiscovered) {
            this.id = id;
            this.icon = icon;
            this.undiscovered = undiscovered;
        }
    }
}
