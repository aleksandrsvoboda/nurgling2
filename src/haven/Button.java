/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import nurgling.*;
import nurgling.actions.bots.*;

import java.awt.Graphics;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;

public class Button extends SIWidget {
	public static BufferedImage bl;
	public static BufferedImage br;
	public static BufferedImage bt;
	public static BufferedImage bb;
	public static BufferedImage dt;
	public static BufferedImage ut;
	public static BufferedImage bm;
    public static int hs, hl;
    /* Transparent margins of the classic art. Layouts let neighbouring buttons overlap
     * there, so the New UI plate stays inside the visible part. */
    private static int ml, mr, mt, mb;
    
    static {
        // Load default style from config or use "tbtn" as fallback
        String style = "tbtn";
        try {
            Object configStyle = NConfig.get(NConfig.Key.buttonStyle);
            if (configStyle instanceof String) {
                style = (String) configStyle;
            }
        } catch (Exception e) {
            // Config not initialized yet, use default
        }
        loadButtonStyle(style);
    }
    
    public static void loadButtonStyle(String style) {
        String basePath = "nurgling/hud/buttons/" + style + "/";
        bl = Resource.loadsimg(basePath + "left");
        br = Resource.loadsimg(basePath + "right");
        bt = Resource.loadsimg(basePath + "top");
        bb = Resource.loadsimg(basePath + "bottom");
        dt = Resource.loadsimg(basePath + "dtex");
        ut = Resource.loadsimg(basePath + "utex");
        bm = Resource.loadsimg(basePath + "mid");
        hs = bl.getHeight();
        hl = bm.getHeight();
        int[] l = opaque(bl), r = opaque(br);
        ml = l[0];
        mr = br.getWidth() - 1 - r[2];
        mt = Math.min(l[1], r[1]);
        mb = hs - 1 - Math.max(l[3], r[3]);
    }

    /** Bounds {x0, y0, x1, y1} of the pixels at least half opaque. */
    private static int[] opaque(BufferedImage img) {
        int x0 = img.getWidth(), y0 = img.getHeight(), x1 = -1, y1 = -1;
        boolean alpha = img.getColorModel().hasAlpha();
        for(int y = 0; y < img.getHeight(); y++) {
            for(int x = 0; x < img.getWidth(); x++) {
                if(alpha && (img.getRGB(x, y) >>> 24) < 128)
                    continue;
                x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                y0 = Math.min(y0, y); y1 = Math.max(y1, y);
            }
        }
        if(x1 < 0)
            return(new int[] {0, 0, img.getWidth() - 1, img.getHeight() - 1});
        return(new int[] {x0, y0, x1, y1});
    }
    
    public static void updateAllButtons(Widget root) {
        if (root == null) return;
        if (root instanceof Button) {
            ((Button) root).redraw();
        }
        for (Widget child = root.child; child != null; child = child.next) {
            updateAllButtons(child);
        }
    }
    public static final Resource click = Loading.waitfor(Resource.local().load("sfx/hud/btn"));
    public static final Audio.Clip clbtdown = Loading.waitfor(Resource.local().load("sfx/hud/lbtn")).layer(Resource.audio, "down");
    public static final Audio.Clip clbtup = Loading.waitfor(Resource.local().load("sfx/hud/lbtn")).layer(Resource.audio, "up");
    public static final int margin = UI.scale(10);
    public boolean lg;
    public Text text;
    public BufferedImage cont;
    public Runnable action = null;
    public Color tint = null;
    static Text.Foundry tf = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(12f)),
	nurgling.styles.UIResources.active() ? nurgling.styles.UITheme.TEXT : Color.WHITE).aa(true);
    /* New UI (decided at client start): plain light labels on the flat plates, without the gold texture. */
    static Text.Furnace nf = nurgling.styles.UIResources.active() ? tf :
	new PUtils.BlurFurn(new PUtils.TexFurn(tf, Window.ctex), UI.rscale(0.75), UI.rscale(0.75), new Color(80, 40, 0));
    private boolean a = false, dis = false, hover = false;
    private boolean flat = nurgling.styles.UITheme.on();
    private UI.Grab d = null;
    /** New UI: tabs and toggles override this to draw their plate as selected. */
    protected boolean selected() { return false; }
    protected boolean pressed() { return a; }
    protected boolean hovered() { return hover; }
	
    @RName("btn")
    public static class $Btn implements Factory {
	public Widget create(UI ui, Object[] args) {
	    if(args.length > 2)
		return(new Button(UI.scale(Utils.iv(args[0])), (String)args[1], Utils.bv(args[2])));
	    else
		return(new Button(UI.scale(Utils.iv(args[0])), (String)args[1]));
	}
    }
    @RName("ltbtn")
    public static class $LTBtn implements Factory {
	public Widget create(UI ui, Object[] args) {
	    return(wrapped(UI.scale(Utils.iv(args[0])), (String)args[1]));
	}
    }
	
    public static Button wrapped(int w, String text) {
	Button ret = new Button(w, tf.renderwrap(text, w - margin));
	return(ret);
    }
        
    private static boolean largep(int w) {
	return(w >= (bl.getWidth() + bm.getWidth() + br.getWidth()));
    }

    private Button(int w, boolean lg) {
	super(new Coord(w, lg?hl:hs));
	this.lg = lg;
    }

    public Button(int w, String text, boolean lg, Runnable action) {
	this(w, lg);
	this.text = nf.render(text);
	this.cont = this.text.img;
	this.action = action;
    }

    public Button(int w, String text, boolean lg) {
	this(w, text, lg, null);
	this.action = () -> wdgmsg("activate");
    }

    public Button(int w, String text, Runnable action) {
	this(w, text, largep(w), action);
    }

    public Button(int w, String text) {
	this(w, text, largep(w));
    }

    public Button(int w, Text text) {
	this(w, largep(w));
	this.text = text;
	this.cont = text.img;
    }
	
    public Button(int w, BufferedImage cont) {
	this(w, largep(w));
	this.cont = cont;
    }
	
    public Button action(Runnable action) {
	this.action = action;
	return(this);
    }

    public void tick(double dt) {
	super.tick(dt);
	if(flat != nurgling.styles.UITheme.on()) {
	    flat = !flat;
	    redraw();
	}
    }

    public void draw(BufferedImage img) {
	if(flat) {
	    java.awt.Graphics2D g = img.createGraphics();
	    // Large buttons carry a decoration above the button proper; the plate covers the button only.
	    int yo = lg ? ((hl - hs) / 2) : 0;
	    g.translate(ml, yo + mt);
	    nurgling.styles.GeneratedButtons.plate(g, sz.x - ml - mr, hs - mt - mb,
		nurgling.styles.GeneratedButtons.state(hover, a, selected(), dis));
	    g.translate(-ml, -(yo + mt));
	    Coord tc = sz.sub(Utils.imgsz(cont)).div(2);
	    if(a)
		tc = tc.add(UI.scale(1), UI.scale(1));
	    if(dis)
		g.setComposite(java.awt.AlphaComposite.SrcOver.derive(0.42f));
	    g.drawImage(cont, tc.x, tc.y, null);
	    g.dispose();
	    if(tint != null)
		PUtils.colmul(img.getRaster(), tint);
	    return;
	}
	Graphics g = img.getGraphics();
	int yo = lg?((hl - hs) / 2):0;

	g.drawImage(a?dt:ut, UI.scale(4), yo + UI.scale(4), sz.x - UI.scale(8), hs - UI.scale(8), null);

	Coord tc = sz.sub(Utils.imgsz(cont)).div(2);
	if(a)
	    tc = tc.add(UI.scale(1), UI.scale(1));
	g.drawImage(cont, tc.x, tc.y, null);

	g.drawImage(bl, 0, yo, null);
	g.drawImage(br, sz.x - br.getWidth(), yo, null);
	g.drawImage(bt, bl.getWidth(), yo, sz.x - bl.getWidth() - br.getWidth(), bt.getHeight(), null);
	g.drawImage(bb, bl.getWidth(), yo + hs - bb.getHeight(), sz.x - bl.getWidth() - br.getWidth(), bb.getHeight(), null);
	if(lg)
	    g.drawImage(bm, (sz.x - bm.getWidth()) / 2, 0, null);

	g.dispose();

	if(dis)
	    PUtils.monochromize(img, Color.LIGHT_GRAY);
	if(tint != null)
	    PUtils.colmul(img.getRaster(), tint);
    }
	
    public void change(String text, Color col) {
	this.text = tf.render(text, col);
	this.cont = this.text.img;
	redraw();
    }
    
    public void change(String text) {
	this.text = nf.render(text);
	this.cont = this.text.img;
	redraw();
    }

    public void disable(boolean dis) {
	this.dis = dis;
	redraw();
    }

    public void click() {
	if(action != null)
	    action.run();
    }

    public boolean gkeytype(GlobKeyEvent ev) {
	click();
	return(true);
    }
    
    public void uimsg(String msg, Object... args) {
	if(msg == "ch") {
	    if(args.length > 1)
		change((String)args[0], (Color)args[1]);
	    else
		change((String)args[0]);
	} else if(msg == "dis") {
	    disable(Utils.bv(args[1]));
	} else {
	    super.uimsg(msg, args);
	}
    }
    
    public void mousemove(MouseMoveEvent ev) {
	super.mousemove(ev);
	boolean inside = ev.c.isect(Coord.z, sz);
	if(hover != inside) {
	    hover = inside;
	    if(flat)
		redraw();
	}
	if(d != null) {
	    boolean a = ev.c.isect(Coord.z, sz);
	    if(a != this.a) {
		this.a = a;
		redraw();
	    }
	}
    }

    protected void depress() {
	ui.sfx(click);
    }

    protected void unpress() {
	ui.sfx(click);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if((ev.b != 1) || dis)
	    return(super.mousedown(ev));
	a = true;
	d = ui.grabmouse(this);
	depress();
	redraw();
	return(true);
    }
	
    public boolean mouseup(MouseUpEvent ev) {
	if((d != null) && ev.b == 1) {
	    d.remove();
	    d = null;
	    a = false;
	    redraw();
	    if(ev.c.isect(Coord.z, sz)) {
		unpress();
		click();
	    }
	    return(true);
	}
	return(super.mouseup(ev));
    }
}
