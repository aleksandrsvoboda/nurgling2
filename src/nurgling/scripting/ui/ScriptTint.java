package nurgling.scripting.ui;

import haven.Gob;
import haven.Sprite;
import haven.render.MixColor;
import haven.render.Pipe;

import java.awt.Color;

/**
 * A script's gob highlight: an overlay that draws nothing itself and tints the gob with the same
 * {@link MixColor} blend as the item-search and Configure highlights. Being an overlay rather than
 * an attribute, it never replaces the player's own tint and several can coexist.
 */
public class ScriptTint extends Sprite implements Gob.SetupMod {
    private final MixColor mix;

    public ScriptTint(Owner owner, Color color) {
        super(owner, null);
        this.mix = new MixColor(color);
    }

    @Override
    public Pipe.Op gobstate() {
        return mix;
    }
}
