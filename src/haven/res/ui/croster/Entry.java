/* Preprocessed source code */
package haven.res.ui.croster;

import haven.*;
import haven.render.*;
import java.util.*;
import java.util.function.*;
import java.util.Set;
import java.util.HashSet;
import haven.MenuGrid.Pagina;
import java.awt.Color;
import java.awt.image.BufferedImage;
import nurgling.animals.AnimalFacts;
import nurgling.animals.CullPlanner;
import nurgling.animals.HerdRules;
import nurgling.animals.RankBreakdown;
import nurgling.animals.RankExplainer;

@haven.FromResource(name = "ui/croster", version = 78)
public class Entry extends Widget {
    public static final int WIDTH = CattleRoster.WIDTH;
    public static final int HEIGHT = UI.scale(20);
    public static final Coord SIZE = new Coord(WIDTH, HEIGHT);
    public static final Color every = new Color(255, 255, 255, 16), other = new Color(255, 255, 255, 32);
    public static final Function<Integer, String> percent = v -> String.format("%d%%", v);
    public static final Function<Number, String> quality = v -> Long.toString(Math.round(v.doubleValue()));
    public static final Function<Entry, Tex> namerend = e -> {
	return(CharWnd.attrf.render(e.name, BuddyWnd.gc[e.grp]).tex());
    };
    public static final Tex male   = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/male", 2)::get).layer(Resource.imgc).tex();
    public static final Tex female = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/female", 2)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> sex = v -> (v ? male : female);
    public static final Tex adult = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/adult", 2)::get).layer(Resource.imgc).tex();
    public static final Tex child = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/child", 2)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> growth = v -> (v ? child : adult);
    public static final Tex dead  = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/dead", 2)::get).layer(Resource.imgc).tex();
    public static final Tex alive = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/alive", 2)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> deadrend = v -> (v ? dead : alive);
    public static final Tex pregy = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/pregnant-y", 2)::get).layer(Resource.imgc).tex();
    public static final Tex pregn = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/pregnant-n", 2)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> pregrend = v -> (v ? pregy : pregn);
    public static final Tex lacty = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/lactate-y", 1)::get).layer(Resource.imgc).tex();
    public static final Tex lactn = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/lactate-n", 1)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> lactrend = v -> (v ? lacty : lactn);
    public static final Tex ownedn = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/owned-n", 1)::get).layer(Resource.imgc).tex();
    public static final Tex ownedo = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/owned-o", 1)::get).layer(Resource.imgc).tex();
    public static final Tex ownedm = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/owned-m", 1)::get).layer(Resource.imgc).tex();
    public static final Function<Integer, Tex> ownrend = v -> ((v == 3) ? ownedm : ((v == 1) ? ownedo : ownedn));
    public static final Color markedForKill = new Color(200, 50, 50, 100);
    public static final Set<UID> killList = new HashSet<>();
    
    public final UID id;
    public String name;
    public int grp;
    public double q;
    public int idx;
    public CheckBox mark;
    public int areaId = -1;  // Area ID the animal is bound to (-1 = not bound)
    public static final Color previewKill = new Color(200, 50, 50, 55);
    public static final Color previewRandom = new Color(233, 156, 84, 45);
    public static final Color cullLine = new Color(224, 85, 85);
    public static final Color rankTrack = new Color(31, 42, 48);
    public static final Color rankFallback = new Color(138, 143, 152);
    /** What the cull preview predicts for this animal; null while the preview is off. */
    public CullPlanner.Outcome preview;
    /** Position in the preview order; set together with {@link #preview}. */
    public int previewSeq = Integer.MAX_VALUE;

    public Entry(Coord sz, UID id, String name) {
	super(sz);
	this.id = id;
	this.name = name;
	this.mark = adda(new CheckBox(""), UI.scale(5), sz.y / 2, 0, 0.5);
    }

    /** The stats the rank and the cull look at; overridden by each species. */
    public AnimalFacts facts() {
	return(null);
    }

    /** The rank preset bound to this animal's area, or null when none is. */
    public HerdRules rankRules() {
	return(null);
    }

    public RankBreakdown breakdown() {
	return(HerdRules.breakdown(rankRules(), facts()));
    }

    public double rang() {
	return(breakdown().rank);
    }

    protected void drawbg(GOut g) {
	if(killList.contains(id)) {
	    g.chcolor(markedForKill);
	} else if((preview != null) && (preview.verdict == CullPlanner.Verdict.KILL)) {
	    g.chcolor(previewKill);
	} else if((preview != null) && (preview.verdict == CullPlanner.Verdict.RANDOM)) {
	    g.chcolor(previewRandom);
	} else {
	    g.chcolor(((idx & 1) == 0) ? every : other);
	}
	g.frect(Coord.z, sz);
	g.chcolor();
    }

    private Tex[] rend = {};
    private Object[] rendv = {};
    public <V> void drawcol(GOut g, Column<?> col, double a, V val, Function<? super V, ?> fmt, int idx) {
	if(fmt == null) fmt = Function.identity();
	if(rend.length <= idx) {
	    rend = Arrays.copyOf(rend, idx + 1);
	    rendv = Arrays.copyOf(rendv, idx + 1);
	}
	if(!Utils.eq(rendv[idx], val)) {
	    if(rend[idx] != null)
		rend[idx].dispose();
	    Object rval = fmt.apply(val);
	    if(rval instanceof Tex)
		rend[idx] = (Tex)rval;
	    else
		rend[idx] = CharWnd.attrf.render(String.valueOf(rval)).tex();
	    rendv[idx] = val;
	}
	Coord sz = rend[idx].sz();
	if(sz.x > col.w) {
	    // Too wide for its column (a long name): clip at the column edge
	    g.reclip(new Coord(col.x, 0), new Coord(col.w, this.sz.y)).image(rend[idx], new Coord(0, (this.sz.y - sz.y) / 2));
	    return;
	}
	g.image(rend[idx], new Coord(col.x + (int)Math.round((col.w - sz.x) * a), (this.sz.y - sz.y) / 2));
    }

    private Tex numtex = null;
    private int numv;
    public void draw(GOut g) {
	int num = idx + 1;
	if((numtex == null) || (numv != num)) {
	    if(numtex != null)
		numtex.dispose();
	    numtex = CharWnd.attrf.render(Integer.toString(num)).tex();
	    numv = num;
	}
	Column<Entry> col = CattleRoster.numcol;
	g.image(numtex, new Coord(col.x + col.w - UI.scale(6) - numtex.sz().x, (sz.y - numtex.sz().y) / 2));
	super.draw(g);
	CattleRoster<?> roster;
	if((preview != null) && preview.cullLineAbove && ((roster = getparent(CattleRoster.class)) != null) && roster.previewSorted()) {
	    g.chcolor(cullLine);
	    int dash = UI.scale(6), gap = UI.scale(4), th = Math.max(1, UI.scale(2));
	    for(int x = 0; x < sz.x; x += dash + gap)
		g.frect(new Coord(x, 0), new Coord(Math.min(dash, sz.x - x), th));
	    g.chcolor();
	}
    }

    /** Rank number at the right of the column, with a bar of what it is made of to its left. */
    protected void drawrank(GOut g, Column<?> col, int idx) {
	RankBreakdown rb = breakdown();
	drawcol(g, col, 1, rb.rank, null, idx);
	int bw = col.w - UI.scale(40);
	if(bw < UI.scale(10))
	    return;
	int bh = UI.scale(8);
	Coord ul = new Coord(col.x + UI.scale(2), (sz.y - bh) / 2);
	g.chcolor(rankTrack);
	g.frect(ul, new Coord(bw, bh));
	if(rb.fallback) {
	    g.chcolor(rankFallback);
	    g.frect(ul, new Coord(bw, bh));
	} else if(rb.bound()) {
	    double tot = 0;
	    for(double p : rb.parts)
		if(p > 0)
		    tot += p;
	    double x = 0;
	    for(int i = 0; i < rb.parts.length; i++) {
		if((rb.parts[i] <= 0) || (tot <= 0))
		    continue;
		double w = bw * rb.parts[i] / tot;
		int x0 = (int)Math.round(x), x1 = (int)Math.round(x + w);
		if(x1 > x0) {
		    g.chcolor(rb.terms().get(i).color);
		    g.frect(ul.add(x0, 0), new Coord(x1 - x0, bh));
		}
		x += w;
	    }
	}
	g.chcolor();
    }

    protected void drawverdict(GOut g, Column<?> col, int idx) {
	if(preview == null)
	    return;
	drawcol(g, col, 0, preview.verdict, v -> CharWnd.attrf.render(v.label, verdictcolor(v)).tex(), idx);
    }

    public static Color verdictcolor(CullPlanner.Verdict v) {
	switch(v) {
	case BREEDER:    return(new Color(127, 211, 200));
	case YOUNG_KEPT:
	case SAVED:      return(new Color(150, 210, 150));
	case KILL:       return(new Color(255, 143, 143));
	case RANDOM:     return(new Color(233, 156, 84));
	default:         return(new Color(170, 170, 170));
	}
    }

    private String tiptext;
    private Tex tiptex;

    public void dispose() {
	if(tiptex != null)
	    tiptex.dispose();
	super.dispose();
    }

    public Object tooltip(Coord c, Widget prev) {
	CattleRoster<?> roster = getparent(CattleRoster.class);
	String text = null;
	if(roster != null) {
	    Column<?> rc = roster.rankcol(), vc = roster.verdictcol();
	    if((rc != null) && rc.hasx(c.x)) {
		AnimalFacts f = facts();
		if(f != null)
		    text = (areaId < 0) ? "Not seen inside an animal area yet,\nso no rank preset applies: rank 0." : RankExplainer.explain(f, breakdown());
	    } else if((vc != null) && vc.hasx(c.x) && (preview != null)) {
		text = RankExplainer.quote(preview.reason);
	    }
	}
	if(text == null)
	    return(super.tooltip(c, prev));
	if(!text.equals(tiptext)) {
	    if(tiptex != null)
		tiptex.dispose();
	    tiptex = RichText.render(text, UI.scale(420)).tex();
	    tiptext = text;
	}
	return(tiptex);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(ev.propagate(this) || super.mousedown(ev))
	    return(true);
	getparent(CattleRoster.class).wdgmsg("click", id, ev.b, ui.modflags(), ui.mc);
	return(true);
    }

    public <T extends Entry> void markall(Class<T> type, Predicate<? super T> p) {
	for(T ent : getparent(CattleRoster.class).children(type)) {
	    if(p.test(ent))
		ent.mark.click();
	}
    }
}
