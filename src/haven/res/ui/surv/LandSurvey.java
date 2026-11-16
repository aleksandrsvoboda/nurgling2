/* Preprocessed source code */
package haven.res.ui.surv;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import nurgling.NStyle;
import nurgling.conf.NDragProp;
import nurgling.widgets.NDraggableWidget;

import java.util.*;
import java.nio.*;
import java.awt.Color;
import static haven.MCache.tilesz;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

@haven.FromResource(name = "ui/surv", version = 46)
public class LandSurvey extends Window {
    public final Area area;
    public final Data data;
    public final Label zdlbl, wlbl, dlbl;
    public Area selection;
    private boolean inited = false;
    private MapView mv;
    private Display dsp;
    private RenderTree.Slot s_dsp;

	private ICheckBox btnLock;
	private Button planBtn;
	private nurgling.leveling.LevelPlan plan;
	private String plantext = null;
	private double plancheck = 0;

    public LandSurvey(Area area, Data data) {
	super(Coord.z, "Land survey", true);
	this.area = area;
	this.data = data;
	Widget prev = add(new Label(String.format("Area: %d m\u00b2", area.area())), 0, 0);
	add(btnLock = new ICheckBox(NStyle.locki[0], NStyle.locki[1], NStyle.locki[2], NStyle.locki[3])
	{
		@Override
		public void changed(boolean val)
		{
			super.changed(val);
			lock(val);
		}
	}, prev.pos("ur").add(UI.scale(200) - NStyle.locki[0].sz().x - NStyle.locki[0].sz().x / 2, 0));
	btnLock.a = true;
	zdlbl = add(new Label("..."), prev.pos("bl").adds(0, 1));
	wlbl = add(new Label("..."), zdlbl.pos("bl").adds(0, 1));
	dlbl = add(new Label("..."), wlbl.pos("bl").adds(0, 1));
	prev = add(new Button(UI.scale(125), "Ground level", false).action(this::initsurf),
		   dlbl.pos("bl").adds(0, 20));
	add(new Button(UI.scale(125), "Ground plane", false).action(this::initplane),
	    prev.pos("ur").adds(10, 0));
	prev = add(new Button(UI.scale(125), "Dig", false).action(() -> wdgmsg("lvl")),
		   prev.pos("bl").adds(0, 10));
	add(new Button(UI.scale(125), "Remove", false).action(()-> wdgmsg("rm")),
	    prev.pos("ur").adds(10, 0));
	/* Shown only while the survey lies in a leveling plan; nothing happens until it is clicked. */
	planBtn = add(new Button(UI.scale(260), "", false).action(this::applyplan), prev.pos("bl").adds(0, 10));
	planBtn.hide();


	pack();
    }

    public static Widget mkwidget(UI ui, Object... args) {
	Area area = Area.corn((Coord)args[0], (Coord)args[1]);
	float gran = ((Number)args[2]).floatValue() / 11;
	Data data = new Data(Area.corni(area.ul, area.br), gran);
	LandSurvey srv = new LandSurvey(area, data);
	if(args[3] != null) {
	    data.decode(Utils.iv(args[3]), (byte[])args[4]);
	    srv.inited = true;
	}
	return(srv);
    }

    protected void attached() {
	super.attached();
	GameUI gui = getparent(GameUI.class);
	this.mv = gui.map;
	/* The leveling planner turns heights into soil units with the server's survey granularity. */
	if((gui instanceof nurgling.NGameUI) && (((nurgling.NGameUI)gui).levelPlans != null)) {
	    nurgling.leveling.LevelPlans plans = ((nurgling.NGameUI)gui).levelPlans;
	    if(plans.gran() != data.gran)
		System.out.println("[Leveling] land survey: " + data.gran + " survey units per height unit, area " + area.area() + " m2");
	    plans.recordGran(data.gran);
	}
	this.dsp = new Display(data, mv);
	s_dsp = mv.drawadd(dsp);
	select(area);
	mode(new Idle());
    }

    private void initsurf() {
	MCache map = mv.ui.sess.glob.map;
	for(Coord vc : data.varea)
	    data.wz[data.varea.ridx(vc)] = data.dz[data.varea.ridx(vc)] = (int)Math.round(map.getfz(vc) * data.gran);
	data.seq++;
	upd = true;
    }

    private void initplane() {
	MCache map = mv.ui.sess.glob.map;
	double zs = 0;
	int nv = 0;
	for(Coord vc : data.varea) {
	    zs += map.getfz(vc);
	    nv++;
	}
	int z = Math.round((float)(zs / nv) * data.gran);
	for(int i = 0; i < data.wz.length; i++)
	    data.wz[i] = data.dz[i] = z;
	data.seq++;
	upd = true;
    }

    private void updmap() {
	MCache map = mv.ui.sess.glob.map;
	int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
	int sd = 0, hn = 0;
	for(Coord vc : data.varea) {
	    int vz = Math.round((float)map.getfz(vc) * data.gran);
	    int tz = data.dz[data.varea.ridx(vc)];
	    min = Math.min(min, vz); max = Math.max(max, vz);
	    sd += tz - vz;
	    if(vz > tz)
		hn += vz - tz;
	}
	zdlbl.settext(String.format("Peak to trough: %.1f m", (max - min) / 10.0));
	if(sd >= 0)
	    wlbl.settext(String.format("Units of soil required: %d", sd));
	else
	    wlbl.settext(String.format("Units of soil left over: %d", -sd));
	dlbl.settext(String.format("Units of soil to dig: %d", hn));
    }

    private void lock(boolean enabled) {
	wdgmsg("lock", enabled ? 0 : 1);
    }

    private void send() {
	wdgmsg("data", data.encode());
    }

    private EventHandler<MouseEvent> mode = null;
    public void mode(EventHandler<MouseEvent> nmode) {
	if(mode != null)
	    mv.deafen(mode);
	if(nmode != null)
	    mv.listen(MouseEvent.class, nmode);
	mode = nmode;
    }

    public class Idle implements EventHandler<Widget.MouseEvent> {
	public boolean handle(MouseEvent ev) {
		if(btnLock.a)
			return false;
	    if(ev instanceof MouseMoveEvent) {
		Coord sel = dsp.mousetest(ev.c, false);
		if(!Utils.eq(sel, dsp.selected)) {
		    dsp.selected = sel;
		    dsp.update = true;
		}
	    } else if(ev instanceof MouseDownEvent) {
		if(((MouseDownEvent)ev).b == 1) {
		    Coord sel = dsp.mousetest(ev.c, false);
		    if(sel != null) {
			if(ui.modshift) {
			    mode(new Selector(sel));
			} else {
			    Area vsel = (selection == null) ? null : Area.corn(selection.ul, selection.br.add(1, 1));
			    if((selection != null) && (sel.x == selection.ul.x) && (sel.y == selection.ul.y)) {
				mode(new CMover(sel, vsel, "ul", ev.c));
			    } else if((selection != null) && (sel.x == selection.br.x) && (sel.y == selection.ul.y)) {
				mode(new CMover(sel, vsel, "ur", ev.c));
			    } else if((selection != null) && (sel.x == selection.br.x) && (sel.y == selection.br.y)) {
				mode(new CMover(sel, vsel, "br", ev.c));
			    } else if((selection != null) && (sel.x == selection.ul.x) && (sel.y == selection.br.y)) {
				mode(new CMover(sel, vsel, "bl", ev.c));
			    } else if((vsel != null) && vsel.contains(sel)) {
				mode(new Mover(sel, vsel, ev.c));
			    } else {
				mode(new Mover(sel, Area.sized(sel, Coord.of(1, 1)), ev.c));
			    }
			}
			return(true);
		    }
		}
	    }
	    return(false);
	}
    }

    public void select(Area area) {
	dsp.select(this.selection = area);
    }

    public class Selector implements EventHandler<Widget.MouseEvent> {
	public final Coord sv;
	public final UI.Grab grab;

	public Selector(Coord vc) {
	    this.sv = vc;
	    dsp.select(Area.sized(vc.min(area.br.sub(1, 1)), Coord.z));
	    grab = mv.ui.grabmouse(mv);
	}

	public boolean handle(MouseEvent ev) {
	    if(ev instanceof MouseMoveEvent) {
		Coord sel = dsp.mousetest(ev.c, true);
		dsp.select(Area.corn(sel.min(sv), sel.max(sv)));
	    } else if(ev instanceof MouseUpEvent) {
		if(((MouseButtonEvent)ev).b == 1) {
		    Coord v = dsp.mousetest(ev.c, true);
		    Area sel = Area.corn(v.min(sv), v.max(sv));
		    if(sel.area() == 0)
			select(null);
		    else
			select(sel);
		    grab.remove();
		    mode(new Idle());
		    return(true);
		}
	    }
	    return(false);
	}
    }

    public class Mover implements EventHandler<Widget.MouseEvent> {
	public final Coord vc, sc;
	public final Area area;
	public final UI.Grab grab;
	public final float[] sz;
	public final float zsz;

	public Mover(Coord vc, Area area, Coord sc) {
	    this.vc = vc;
	    this.sc = sc;
	    this.area = area;
	    this.zsz = dsp.zsize(vc);
	    this.sz = new float[area.area()];
	    for(Coord iv : area)
		this.sz[area.ridx(iv)] = data.wz[data.varea.ridx(iv)];
	    grab = mv.ui.grabmouse(mv);
	    dsp.active = true;
	    dsp.update = true;
	}

	public boolean handle(MouseEvent ev) {
	    if(ev instanceof MouseMoveEvent) {
		float diff = Math.round((sc.y - ev.c.y) / zsz);
		for(Coord mv : area)
		    data.wz[data.varea.ridx(mv)] = sz[area.ridx(mv)] + diff;
		data.dupdate();
		data.seq++;
		upd = true;
	    } else if(ev instanceof MouseUpEvent) {
		if(((MouseButtonEvent)ev).b == 1) {
		    dsp.active = false;
		    dsp.update = true;
		    grab.remove();
		    send();
		    mode(new Idle());
		    return(true);
		}
	    }
	    return(false);
	}
    }

    public class CMover implements EventHandler<Widget.MouseEvent> {
	public final Coord vc, sc;
	public final String corn;
	public final Area area;
	public final UI.Grab grab;
	public final float[] sz;
	public final float zsz;

	public CMover(Coord vc, Area area, String corn, Coord sc) {
	    this.vc = vc;
	    this.sc = sc;
	    this.area = area;
	    this.corn = corn;
	    this.zsz = dsp.zsize(vc);
	    this.sz = new float[area.area()];
	    for(Coord iv : area)
		this.sz[area.ridx(iv)] = data.wz[data.varea.ridx(iv)];
	    grab = mv.ui.grabmouse(mv);
	    dsp.active = true;
	    dsp.update = true;
	}

	public boolean handle(MouseEvent ev) {
	    if(ev instanceof MouseMoveEvent) {
		float diff = Math.round((sc.y - ev.c.y) / zsz);
		float ul = (corn == "ul") ? diff : 0;
		float ur = (corn == "ur") ? diff : 0;
		float br = (corn == "br") ? diff : 0;
		float bl = (corn == "bl") ? diff : 0;
		float iw = 1f / (area.br.x - 1 - area.ul.x), ih = 1f / (area.br.y - 1 - area.ul.y);
		for(Coord mv : area) {
		    float fx = (mv.x - area.ul.x) * iw, fy = (mv.y - area.ul.y) * ih;
		    float cd = (((ul * (1 - fx)) + (ur * fx)) * (1 - fy)) +
			       (((bl * (1 - fx)) + (br * fx)) * fy);
		    data.wz[data.varea.ridx(mv)] = sz[area.ridx(mv)] + cd;
		}
		data.dupdate();
		data.seq++;
		upd = true;
	    } else if(ev instanceof MouseUpEvent) {
		if(((MouseButtonEvent)ev).b == 1) {
		    dsp.active = false;
		    dsp.update = true;
		    grab.remove();
		    send();
		    mode(new Idle());
		    return(true);
		}
	    }
	    return(false);
	}
    }

    private nurgling.leveling.LevelPlans plans() {
	nurgling.NGameUI gui = getparent(nurgling.NGameUI.class);
	return((gui == null) ? null : gui.levelPlans);
    }

    /** A loaded tile's grid id and offset; throws Loading while its grid is not here yet. */
    private nurgling.leveling.SurveyPlan.Locator locator() {
	MCache map = mv.ui.sess.glob.map;
	return(tc -> {
		MCache.Grid g = map.getgrid(tc.div(MCache.cmaps));
		return(new long[] {g.id, tc.x - g.ul.x, tc.y - g.ul.y});
	    });
    }

    private int[] plantargets(nurgling.leveling.LevelPlan p) {
	MCache map = mv.ui.sess.glob.map;
	return(nurgling.leveling.SurveyPlan.targets(p, data.varea, data.gran, locator(), map::getfz));
    }

    /** Which plan this survey lies in, and whether its targets are already the plan's; the button follows. */
    private void checkplan() {
	nurgling.leveling.LevelPlans ps = plans();
	nurgling.leveling.LevelPlan p = (ps == null) ? null : nurgling.leveling.SurveyPlan.pick(ps.plans(), area, locator());
	String text = null;
	if(p != null) {
	    boolean applied = java.util.Arrays.equals(plantargets(p), data.dz);
	    text = String.format("%s: %s (%d)%s", nurgling.i18n.L10n.get("level.survey_btn"), p.name,
				 Math.round(p.target * data.gran), applied ? "  \u2713" : "");
	}
	plan = p;
	if(!java.util.Objects.equals(text, plantext)) {
	    plantext = text;
	    if(text != null)
		planBtn.change(text);
	    if(planBtn.visible() != (text != null)) {
		planBtn.show(text != null);
		pack();
	    }
	}
    }

    /** Set every corner of the plan's tiles to the plan's height, the rest to the ground, and send it. */
    private void applyplan() {
	nurgling.leveling.LevelPlan p = plan;
	if((p == null) || !inited)
	    return;
	int[] t;
	try {
	    t = plantargets(p);
	} catch(Loading l) {
	    return;
	}
	if(!nurgling.leveling.SurveyPlan.fits(t)) {
	    getparent(GameUI.class).error(nurgling.i18n.L10n.get("level.survey_too_steep"));
	    return;
	}
	for(int i = 0; i < t.length; i++)
	    data.wz[i] = data.dz[i] = t[i];
	data.seq++;
	upd = true;
	send();
	plantext = null;
    }

    private boolean upd = true;
    private int mapseq = -1;
    public void tick(double dt) {
	if(!inited) {
	    try {
		initplane();
		send();
		inited = true;
	    } catch(Loading l) {}
	}
	if(inited) {
	    if(upd || (mapseq != mv.ui.sess.glob.map.chseq)) {
		try {
		    updmap();
		    mapseq = mv.ui.sess.glob.map.chseq;
		    upd = false;
		} catch(Loading l) {
		}
	    }
	    plancheck -= dt;
	    if(plancheck <= 0) {
		plancheck = 0.5;
		try {
		    checkplan();
		} catch(Loading l) {
		}
	    }
	}
	super.tick(dt);
    }

    public void uimsg(String name, Object... args) {
	if(name == "data") {
	    data.decode(Utils.iv(args[0]), (byte[])args[1]);
	    upd = true;
	} else if(name == "lock") {
	    btnLock.a = !Utils.bv(args[0]);
	} else {
	    super.uimsg(name, args);
	}
    }

    public void destroy() {
	mode(null);
	if(s_dsp != null)
	    s_dsp.remove();
	super.destroy();
    }
}
