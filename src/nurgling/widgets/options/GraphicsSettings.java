package nurgling.widgets.options;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.render.NGfx;
import nurgling.widgets.nsettings.Panel;
import nurgling.widgets.nsettings.CollapsibleSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.function.Function;

/*
 * Visual quality options. Every effect has its own toggle, so slower
 * machines can switch off the costly ones; changes apply at once.
 */
public class GraphicsSettings extends Panel {
    private final List<Runnable> refresh = new ArrayList<>();
    private final List<CollapsibleSection> sections = new ArrayList<>();
    private final Scrollport scroll;
    private int sectionsTop;

    private static void put(String key, Object val) {
	NGfx.set(NGfx.get().with(key, val));
    }

    public GraphicsSettings() {
	super();
	int margin = UI.scale(10);
	scroll = add(new Scrollport(new Coord(UI.scale(560), UI.scale(560))), new Coord(margin, margin));
	Widget cont = scroll.cont;

	Widget prev = cont.add(new Label(L10n.get("gfx.title")), Coord.z);
	prev = cont.add(new Label(L10n.get("gfx.hint")), prev.pos("bl").adds(0, 4));
	CheckBox fps = new CheckBox(L10n.get("fps.toggle"));
	fps.state(nurgling.widgets.FpsPanel::enabled);
	fps.set(nurgling.widgets.FpsPanel::enabled);
	prev = checkbox(cont, prev, fps);
	prev = check(cont, prev, "gfx.enabled", s -> s.enabled, "enabled");
	prev = cont.add(new Label("") {
		private Boolean shown = null;
		private boolean enabled;

		public void tick(double dt) {
		    super.tick(dt);
		    if(ui == null)
			return;
		    boolean sup = NGfx.supported(ui.getenv());
		    boolean on = NGfx.get().enabled;
		    if(!Boolean.valueOf(sup).equals(shown) || (on != enabled)) {
			shown = sup;
			enabled = on;
			settext(L10n.get(!sup ? "gfx.inactive" : (on ? "gfx.active" : "gfx.classic")));
			setcolor(sup ? new java.awt.Color(140, 220, 140) : new java.awt.Color(240, 180, 90));
		    }
		}
	    }, prev.pos("bl").adds(0, 4));

	/* Presets */
	Widget row = cont.add(new Widget(Coord.z), prev.pos("bl").adds(0, 10));
	Label presetLabel = row.add(new Label(L10n.get("gfx.preset")), Coord.z);
	int x = presetLabel.sz.x + UI.scale(10);
	int height = presetLabel.sz.y;
	for(NGfx.Preset preset : NGfx.Preset.values()) {
	    Button pb = row.add(new Button(UI.scale(90), L10n.get("gfx.preset." + preset.name().toLowerCase())) {
		    public void click() {
			NGfx.set(preset.settings(NGfx.get()));
			for(Runnable r : refresh)
			    r.run();
		    }
		}, new Coord(x, 0));
	    x = pb.pos("ur").x + UI.scale(10);
	    height = Math.max(height, pb.sz.y);
	}
	for(Widget item = row.child; item != null; item = item.next)
	    item.move(new Coord(item.c.x, (height - item.sz.y) / 2));
	row.pack();
	sectionsTop = row.pos("bl").y + UI.scale(14);

	/* Color */
	cont = section("gfx.sec.color");
	prev = null;
	prev = check(cont, prev, "gfx.grade", s -> s.grade, "grade");
	prev = slider(cont, prev, "gfx.exposure", 50, 200, s -> s.exposure, "exposure", 100);
	prev = slider(cont, prev, "gfx.contrast", 80, 150, s -> s.contrast, "contrast", 100);
	prev = slider(cont, prev, "gfx.saturation", 50, 200, s -> s.saturation, "saturation", 100);
	prev = slider(cont, prev, "gfx.warmth", -100, 100, s -> s.warmth, "warmth", 100);
	prev = check(cont, prev, "gfx.worldlight", s -> s.worldlight, "worldlight");
	prev = slider(cont, prev, "gfx.worldlightstrength", 0, 100, s -> s.worldlightstrength, "worldlightstrength", 100);
	prev = check(cont, prev, "gfx.autoexp", s -> s.autoexp, "autoexp");

	/* Image quality */
	cont = section("gfx.sec.image");
	prev = null;
	prev = check(cont, prev, "gfx.fxaa", s -> s.fxaa, "fxaa");
	prev = check(cont, prev, "gfx.taa", s -> s.taa, "taa");
	prev = check(cont, prev, "gfx.sharpen", s -> s.sharpen, "sharpen");
	prev = slider(cont, prev, "gfx.sharpness", 0, 100, s -> s.sharpness, "sharpness", 100);
	prev = choice(cont, prev, "gfx.aniso", new String[] {"gfx.off", "4x", "8x", "16x"}, new int[] {1, 4, 8, 16}, s -> s.aniso, "aniso");
	prev = cont.add(new Label(L10n.get("gfx.aniso.note")), prev.pos("bl").adds(15, 2));
	prev = check(cont, prev, "gfx.upscale", s -> s.upscale, "upscale");
	prev = rscale(cont, prev);

	/* Lighting */
	cont = section("gfx.sec.light");
	prev = null;
	prev = check(cont, prev, "gfx.relief", s -> s.relief, "relief");
	prev = check(cont, prev, "gfx.reliefpavingonly", s -> s.reliefpavingonly, "reliefpavingonly");
	prev = slider(cont, prev, "gfx.reliefstrength", 20, 300, s -> s.reliefstrength, "reliefstrength", 100);
	prev = check(cont, prev, "gfx.parallax", s -> s.parallax, "parallax");
	prev = check(cont, prev, "gfx.bettershadows", s -> s.bettershadows, "bettershadows");

	/* Effects */
	cont = section("gfx.sec.effects");
	prev = null;
	prev = check(cont, prev, "gfx.fire", s -> s.fire, "fire");
	prev = check(cont, prev, "gfx.smoke", s -> s.smoke, "smoke");
	prev = check(cont, prev, "gfx.heat", s -> s.heat, "heat");
	prev = check(cont, prev, "gfx.glow", s -> s.glow, "glow");
	prev = check(cont, prev, "gfx.water", s -> s.water, "water");
	prev = check(cont, prev, "gfx.waterreflections", s -> s.waterreflections, "waterreflections");
	prev = check(cont, prev, "gfx.rainripples", s -> s.rainripples, "rainripples");
	prev = check(cont, prev, "gfx.lightningbolts", s -> s.lightning, "lightningbolts");
	prev = check(cont, prev, "gfx.animatedgrass", s -> s.grass, "animatedgrass");
	prev = slider(cont, prev, "gfx.grassdensity", 25, 200, s -> s.grassdensity, "grassdensity", 100);
	prev = check(cont, prev, "gfx.wet", s -> s.wet, "wet");
	prev = check(cont, prev, "gfx.snow", s -> s.snow, "snow");
	prev = cont.add(new Label(L10n.get("gfx.photo")), new Coord(UI.scale(5), prev.pos("bl").y + UI.scale(8)));
	prev = check(cont, prev, "gfx.shafts", s -> s.shafts, "shafts");
	relayoutSections();
    }

    private Widget section(String key) {
	CollapsibleSection section = scroll.cont.add(new CollapsibleSection(L10n.get(key), scroll.cont.sz.x, false), Coord.z);
	sections.add(section);
	section.setOnToggle(this::relayoutSections);
	return section.content;
    }

    private void relayoutSections() {
	int y = sectionsTop;
	for(CollapsibleSection section : sections) {
	    section.pack();
	    section.move(new Coord(0, y));
	    y = section.pos("bl").y + UI.scale(10);
	}
	scroll.cont.update();
	scroll.bar.ch(0); // Clamp the scroll position when a section becomes shorter.
    }

    @Override
    public Map<Widget, Runnable> searchReveal() {
	Map<Widget, Runnable> reveals = new IdentityHashMap<>();
	for(CollapsibleSection section : sections)
	    reveals.put(section.content, () -> section.setExpanded(true));
	return reveals;
    }

    private Widget check(Widget cont, Widget prev, String key, Function<NGfx.Settings, Boolean> get, String name) {
        if(name.equals("snow") && !NGfx.SNOW_SETTLING_AVAILABLE) {
            CheckBox cb = new CheckBox(L10n.get(key)) {
                public void draw(GOut g) {
                    g.chcolor(128,128,128,255);
                    super.draw(g);
                    g.chcolor();
                }
            };
            cb.state(() -> false).click(() -> {});
            cb.settip(L10n.get("gfx.snow.disabled.tip"), true);
            return checkbox(cont,prev,cb);
        }
	CheckBox cb = new CheckBox(L10n.get(key));
	if(name.equals("bettershadows")) cb.settip(L10n.get("gfx.bettershadows.tip"), true);
	if(name.equals("worldlight")) cb.settip(L10n.get("gfx.worldlight.tip"), true);
	if(name.equals("waterreflections")) cb.settip(L10n.get("gfx.waterreflections.tip"), true);
	if(name.equals("rainripples")) cb.settip(L10n.get("gfx.rainripples.tip"), true);
	if(name.equals("lightningbolts")) cb.settip(L10n.get("gfx.lightningbolts.tip"), true);
	if(name.equals("animatedgrass")) cb.settip(L10n.get("gfx.animatedgrass.tip"), true);
	if(name.equals("reliefpavingonly")) cb.settip(L10n.get("gfx.reliefpavingonly.tip"), true);
	/* The shown state comes from the settings (presets change it
	 * too), so write through on every click instead of relying on
	 * the checkbox's own copy of the value. */
	cb.state(() -> get.apply(NGfx.get()));
	cb.set(v -> put(name, v));
	return(checkbox(cont, prev, cb));
    }

    private Widget checkbox(Widget cont, Widget prev, CheckBox cb) {
	return(cont.add(cb, new Coord(UI.scale(5), prev == null ? UI.scale(8) : prev.pos("bl").y + UI.scale(5))));
    }

    private static String sliderValue(String name,int value,int scale) {
        if(name.equals("grassdensity")) return value+"%";
        return String.format("%.2f",value/(double)scale);
    }
    private Widget slider(Widget cont, Widget prev, String key, int min, int max, Function<NGfx.Settings, Float> get, String name, int scale) {
	Widget lbl = cont.add(new Label(L10n.get(key)), new Coord(UI.scale(25), prev.pos("bl").y + UI.scale(4)));
	Label vlbl = new Label("");
	HSlider sl = new HSlider(UI.scale(200), min, max, Math.round(get.apply(NGfx.get()) * scale)) {
		void dpy() {vlbl.settext(sliderValue(name,this.val,scale));}
		protected void added() {dpy();}
		public void changed() {
		    dpy();
		    if(name.equals("worldlightstrength")) put(name, this.val / (float)scale);
		}
		public void fchanged() {put(name, this.val / (float)scale);}
	    };
	cont.add(sl, new Coord(UI.scale(160), lbl.c.y));
	cont.add(vlbl, new Coord(UI.scale(370), lbl.c.y));
	refresh.add(() -> {
		sl.val = Math.round(get.apply(NGfx.get()) * scale);
		vlbl.settext(sliderValue(name,sl.val,scale));
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
