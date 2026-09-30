package nurgling.widgets.options;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.render.NGfx;
import nurgling.widgets.nsettings.Panel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/*
 * Visual quality options. Every effect has its own toggle, so slower
 * machines can switch off the costly ones; changes apply at once.
 */
public class GraphicsSettings extends Panel {
    private final List<Runnable> refresh = new ArrayList<>();

    private static void put(String key, Object val) {
	NGfx.set(NGfx.get().with(key, val));
    }

    public GraphicsSettings() {
	super();
	int margin = UI.scale(10);
	Scrollport scroll = add(new Scrollport(new Coord(UI.scale(560), UI.scale(560))), new Coord(margin, margin));
	Widget cont = scroll.cont;

	Widget prev = cont.add(new Label(L10n.get("gfx.title")), Coord.z);
	prev = cont.add(new Label(L10n.get("gfx.hint")), prev.pos("bl").adds(0, 4));
	prev = cont.add(new Label("") {
		private Boolean shown = null;

		public void tick(double dt) {
		    super.tick(dt);
		    if(ui == null)
			return;
		    boolean sup = NGfx.supported(ui.getenv());
		    if(!Boolean.valueOf(sup).equals(shown)) {
			shown = sup;
			settext(L10n.get(sup ? "gfx.active" : "gfx.inactive"));
			setcolor(sup ? new java.awt.Color(140, 220, 140) : new java.awt.Color(240, 180, 90));
		    }
		}
	    }, prev.pos("bl").adds(0, 4));

	/* Presets */
	Widget row = cont.add(new Label(L10n.get("gfx.preset")), prev.pos("bl").adds(0, 10));
	Widget pb = row;
	for(NGfx.Preset preset : NGfx.Preset.values()) {
	    pb = cont.add(new Button(UI.scale(90), L10n.get("gfx.preset." + preset.name().toLowerCase())) {
		    public void click() {
			NGfx.set(preset.settings(NGfx.get()));
			for(Runnable r : refresh)
			    r.run();
		    }
		}, pb.pos("ur").adds(10, -4));
	}
	prev = row;

	/* Color */
	prev = section(cont, prev, "gfx.sec.color");
	prev = check(cont, prev, "gfx.grade", s -> s.grade, "grade");
	prev = slider(cont, prev, "gfx.exposure", 50, 200, s -> s.exposure, "exposure", 100);
	prev = slider(cont, prev, "gfx.contrast", 80, 150, s -> s.contrast, "contrast", 100);
	prev = slider(cont, prev, "gfx.saturation", 50, 200, s -> s.saturation, "saturation", 100);
	prev = slider(cont, prev, "gfx.warmth", -100, 100, s -> s.warmth, "warmth", 100);
	prev = check(cont, prev, "gfx.vignette", s -> s.vignette, "vignette");
	prev = check(cont, prev, "gfx.tod", s -> s.tod, "tod");
	prev = check(cont, prev, "gfx.autoexp", s -> s.autoexp, "autoexp");
	prev = check(cont, prev, "gfx.clarity", s -> s.clarity, "clarity");
	prev = slider(cont, prev, "gfx.claritystrength", 0, 100, s -> s.claritystrength, "claritystrength", 100);

	/* Image quality */
	prev = section(cont, prev, "gfx.sec.image");
	prev = check(cont, prev, "gfx.fxaa", s -> s.fxaa, "fxaa");
	prev = check(cont, prev, "gfx.taa", s -> s.taa, "taa");
	prev = check(cont, prev, "gfx.sharpen", s -> s.sharpen, "sharpen");
	prev = slider(cont, prev, "gfx.sharpness", 0, 100, s -> s.sharpness, "sharpness", 100);
	prev = choice(cont, prev, "gfx.aniso", new String[] {"gfx.off", "4x", "8x", "16x"}, new int[] {1, 4, 8, 16}, s -> s.aniso, "aniso");
	prev = cont.add(new Label(L10n.get("gfx.aniso.note")), prev.pos("bl").adds(15, 2));
	prev = check(cont, prev, "gfx.upscale", s -> s.upscale, "upscale");
	prev = rscale(cont, prev);
	prev = check(cont, prev, "gfx.tilt", s -> s.tilt, "tilt");
	prev = slider(cont, prev, "gfx.tiltstrength", 20, 250, s -> s.tiltstrength, "tiltstrength", 100);

	/* Lighting */
	prev = section(cont, prev, "gfx.sec.light");
	prev = check(cont, prev, "gfx.ssao", s -> s.ssao, "ssao");
	prev = choice(cont, prev, "gfx.aoq", new String[] {"gfx.aoq.half", "gfx.aoq.full"}, new int[] {0, 1}, s -> s.aoq, "aoq");
	prev = slider(cont, prev, "gfx.aostrength", 20, 200, s -> s.aostrength, "aostrength", 100);
	prev = check(cont, prev, "gfx.relief", s -> s.relief, "relief");
	prev = slider(cont, prev, "gfx.reliefstrength", 20, 300, s -> s.reliefstrength, "reliefstrength", 100);
	prev = check(cont, prev, "gfx.parallax", s -> s.parallax, "parallax");
	prev = check(cont, prev, "gfx.objrelief", s -> s.objrelief, "objrelief");
	prev = slider(cont, prev, "gfx.objreliefstrength", 10, 200, s -> s.objreliefstrength, "objreliefstrength", 100);
	prev = check(cont, prev, "gfx.softshadow", s -> s.softshadow, "softshadow");
	prev = choice(cont, prev, "gfx.shadowq", new String[] {"gfx.shadowq.soft", "gfx.shadowq.softer"}, new int[] {0, 1}, s -> s.shadowq, "shadowq");
	prev = choice(cont, prev, "gfx.plights", new String[] {"gfx.off", "1", "2", "4"}, new int[] {0, 1, 2, 4}, s -> s.plights, "plights");
	prev = choice(cont, prev, "gfx.plightres", new String[] {"gfx.plightres.normal", "gfx.plightres.high"}, new int[] {0, 1}, s -> s.plightres, "plightres");
	prev = check(cont, prev, "gfx.bloom", s -> s.bloom, "bloom");
	prev = slider(cont, prev, "gfx.bloomstrength", 10, 150, s -> s.bloomstrength, "bloomstrength", 100);

	/* Effects */
	prev = section(cont, prev, "gfx.sec.effects");
	prev = check(cont, prev, "gfx.fire", s -> s.fire, "fire");
	prev = check(cont, prev, "gfx.smoke", s -> s.smoke, "smoke");
	prev = check(cont, prev, "gfx.heat", s -> s.heat, "heat");
	prev = check(cont, prev, "gfx.glow", s -> s.glow, "glow");
	prev = check(cont, prev, "gfx.water", s -> s.water, "water");
	prev = check(cont, prev, "gfx.clouds", s -> s.clouds, "clouds");
	prev = check(cont, prev, "gfx.wet", s -> s.wet, "wet");
	prev = check(cont, prev, "gfx.waterfx", s -> s.waterfx, "waterfx");
	prev = check(cont, prev, "gfx.snow", s -> s.snow, "snow");
	prev = check(cont, prev, "gfx.lightning", s -> s.lightning, "lightning");
	prev = check(cont, prev, "gfx.sway", s -> s.sway, "sway");
	prev = check(cont, prev, "gfx.particles", s -> s.particles, "particles");
	prev = check(cont, prev, "gfx.steps", s -> s.steps, "steps");
	prev = check(cont, prev, "gfx.wildlife", s -> s.wildlife, "wildlife");
	prev = cont.add(new Label(L10n.get("gfx.photo")), new Coord(UI.scale(5), prev.pos("bl").y + UI.scale(8)));
	prev = check(cont, prev, "gfx.shafts", s -> s.shafts, "shafts");
	cont.pack();
    }

    private Widget section(Widget cont, Widget prev, String key) {
	return(cont.add(new Label(L10n.get(key)), new Coord(0, prev.pos("bl").y + UI.scale(14))));
    }

    private Widget check(Widget cont, Widget prev, String key, Function<NGfx.Settings, Boolean> get, String name) {
	CheckBox cb = new CheckBox(L10n.get(key));
	/* The shown state comes from the settings (presets change it
	 * too), so write through on every click instead of relying on
	 * the checkbox's own copy of the value. */
	cb.state(() -> get.apply(NGfx.get()));
	cb.set(v -> put(name, v));
	return(cont.add(cb, new Coord(UI.scale(5), prev.pos("bl").y + UI.scale(5))));
    }

    private Widget slider(Widget cont, Widget prev, String key, int min, int max, Function<NGfx.Settings, Float> get, String name, int scale) {
	Widget lbl = cont.add(new Label(L10n.get(key)), new Coord(UI.scale(25), prev.pos("bl").y + UI.scale(4)));
	Label vlbl = new Label("");
	HSlider sl = new HSlider(UI.scale(200), min, max, Math.round(get.apply(NGfx.get()) * scale)) {
		void dpy() {vlbl.settext(String.format("%.2f", this.val / (double)scale));}
		protected void added() {dpy();}
		public void changed() {dpy();}
		public void fchanged() {put(name, this.val / (float)scale);}
	    };
	cont.add(sl, new Coord(UI.scale(160), lbl.c.y));
	cont.add(vlbl, new Coord(UI.scale(370), lbl.c.y));
	refresh.add(() -> {
		sl.val = Math.round(get.apply(NGfx.get()) * scale);
		vlbl.settext(String.format("%.2f", sl.val / (double)scale));
	    });
	return(lbl);
    }

    /* The game's own render scale (also in the video options): below
     * 100%, the picture is rendered smaller and scaled up. */
    private Widget rscale(Widget cont, Widget prev) {
	Widget lbl = cont.add(new Label(L10n.get("gfx.rscale")), new Coord(UI.scale(25), prev.pos("bl").y + UI.scale(4)));
	float[] vals = {1.0f, 0.85f, 0.77f, 0.67f, 0.5f};
	String[] labels = {"100%", "85%", "77%", "67%", "50%"};
	boolean[] ready = {false};
	RadioGroup grp = new RadioGroup(cont) {
		public void changed(int btn, String l) {
		    if(!ready[0] || (ui == null) || (ui.gprefs == null))
			return;
		    try {
			ui.setgprefs(ui.gprefs.update(null, ui.gprefs.rscale, vals[btn]));
		    } catch(GSettings.SettingException e) {
			ui.error(e.getMessage());
		    }
		}
	    };
	int x = UI.scale(160);
	for(int i = 0; i < labels.length; i++) {
	    Widget p = grp.add(labels[i], new Coord(x, lbl.c.y));
	    x = p.pos("ur").x + UI.scale(10);
	}
	Runnable sync = () -> {
	    if((ui == null) || (ui.gprefs == null))
		return;
	    float cur = ui.gprefs.rscale.val;
	    for(int i = 0; i < vals.length; i++) {
		if(Math.abs(vals[i] - cur) < 0.02f)
		    grp.check(i);
	    }
	};
	refresh.add(() -> {
		ready[0] = false;
		sync.run();
		ready[0] = true;
	    });
	ready[0] = true;
	return(lbl);
    }

    private Widget choice(Widget cont, Widget prev, String key, String[] labels, int[] vals, Function<NGfx.Settings, Integer> get, String name) {
	Widget lbl = cont.add(new Label(L10n.get(key)), new Coord(UI.scale(25), prev.pos("bl").y + UI.scale(4)));
	boolean[] ready = {false};
	RadioGroup grp = new RadioGroup(cont) {
		public void changed(int btn, String l) {
		    if(ready[0])
			put(name, vals[btn]);
		}
	    };
	Widget p = null;
	int x = UI.scale(160);
	for(int i = 0; i < labels.length; i++) {
	    String l = labels[i].startsWith("gfx.") ? L10n.get(labels[i]) : labels[i];
	    p = grp.add(l, new Coord(x, lbl.c.y));
	    x = p.pos("ur").x + UI.scale(12);
	}
	Runnable sync = () -> {
	    int cur = get.apply(NGfx.get());
	    for(int i = 0; i < vals.length; i++) {
		if(vals[i] == cur)
		    grp.check(i);
	    }
	};
	sync.run();
	ready[0] = true;
	refresh.add(() -> {
		ready[0] = false;
		sync.run();
		ready[0] = true;
	    });
	return(lbl);
    }

    public void load() {
	for(Runnable r : refresh)
	    r.run();
    }
}
