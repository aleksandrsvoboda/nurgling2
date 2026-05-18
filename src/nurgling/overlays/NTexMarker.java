/* Preprocessed source code */
package nurgling.overlays;

import haven.*;
import haven.render.*;
import haven.render.Model.Indices;
import nurgling.NGob;
import nurgling.NUtils;


import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.BooleanSupplier;

/* >spr: BPRad */
public class NTexMarker extends Sprite implements RenderTree.Node, PView.Render2D {

	int z = 15;
	TexI img;
	private final BooleanSupplier condition; // Функциональный интерфейс для условия

	// When true, NMapView treats a click anywhere inside this icon's screen rect
	// as a click on the owning gob. Updated by draw() on every render frame.
	public final boolean clickThroughToGob;
	public volatile Coord lastScreenCenter = null;
	public volatile long lastDrawTimeMs = 0L;

	public NTexMarker(Owner owner, TexI img, BooleanSupplier condition, boolean clickThroughToGob) {
		super(owner, null);
		this.img = img;
		this.condition = condition;
		this.clickThroughToGob = clickThroughToGob;
	}

	public NTexMarker(Owner owner, TexI img, BooleanSupplier condition) {
		this(owner, img, condition, false);
	}

	public NTexMarker(Owner owner, TexI img) {
		this(owner, img, () -> true, false);
	}

	@Override
	public boolean tick(double dt) {
		boolean result = super.tick(dt);
		return result || (condition != null && condition.getAsBoolean());
	}

	@Override
	public void gtick(Render g) {
		super.gtick(g);
	}

	@Override
	public void draw(GOut g, Pipe state) {
		Coord3f markerPos = new Coord3f(0, 0, z + NUtils.getDeltaZ());
		Coord sc = Homo3D.obj2view(markerPos, state, Area.sized(g.sz())).round2();
		g.aimage(img, sc, 0.5, 0.5,UI.scale(48,48));
		if (clickThroughToGob) {
			lastScreenCenter = sc;
			lastDrawTimeMs = System.currentTimeMillis();
		}
	}
}