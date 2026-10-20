/* Preprocessed source code */
package haven.res.ui.croster;

import haven.*;
import haven.render.*;

import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.function.*;
import haven.MenuGrid.Pagina;
import haven.res.gfx.hud.rosters.cow.Ochs;
import haven.res.gfx.hud.rosters.goat.Goat;
import haven.res.gfx.hud.rosters.horse.Horse;
import haven.res.gfx.hud.rosters.pig.Pig;
import haven.res.gfx.hud.rosters.sheep.Sheep;
import haven.res.gfx.hud.rosters.teimdeer.Teimdeer;
import nurgling.NMapView;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.widgets.NKinSettings;
import nurgling.widgets.PaddedICheckBox;
import nurgling.widgets.settings.*;
import nurgling.animals.AnimalFacts;
import nurgling.animals.CullPlanner;
import nurgling.animals.HerdRules;
import nurgling.animals.RankExplainer;

import java.awt.Color;
import java.awt.image.BufferedImage;

@haven.FromResource(name = "ui/croster", version = 78)
public abstract class CattleRoster <T extends Entry> extends Widget {
    public static final int WIDTH = UI.scale(980);
    public static final Comparator<Entry> namecmp = (a, b) -> a.name.compareTo(b.name);
    public static final int TOOLBAR_H = UI.scale(25);  // Height for top toolbar (combobox, settings button)
    public static final int HEADH = UI.scale(55);  // Total header height (toolbar + column headers)
    public static final Column<Entry> numcol = new Column<>("#", null, 30);  // Row number gutter, not sortable
    public final Map<UID, T> entries = new HashMap<>();
    public final Scrollbar sb;
    public final Widget entrycont;
    public int entryseq = 0;
    public List<T> display = Collections.emptyList();
    public boolean dirty = true;
    public Comparator<? super T> order = namecmp;
    public Column<? super T> mousecol, ordercol;
    public boolean revorder;

    ICheckBox settings;
    final Coord shift = UI.scale(16,5);
    NAreaDropbox areaFilter;
    int filterAreaId = -1;  // -1 = show all animals
    CheckBox previewBox;
    Label previewInfo;
    PreviewWarnings previewWarn;
    double previewAge = 0;
    public static final double PREVIEW_PERIOD = 0.5;
    /** Clickable margin around the herd settings gear, which is otherwise hit only on its opaque pixels. */
    public static final int SETTINGS_PAD = UI.scale(4);
    public static final Color PREVIEW_WARN = new Color(233, 156, 84);
    /** Preview order: by area, females before males, then as the cull planner ranks them. */
    public final Comparator<T> previewOrder = Comparator.comparing((T e) -> e.preview == null).thenComparingInt(e -> e.previewSeq);
    public CattleRoster() {
	super(new Coord(WIDTH, UI.scale(400)));
	this.type = (Class<T>) ((ParameterizedType) getClass()
			.getGenericSuperclass()).getActualTypeArguments()[0];
	entrycont = add(new Widget(sz), 0, HEADH);
	sb = add(new Scrollbar(sz.y, 0, 0) {
		public void changed() {redisplay(display);}
	    }, sz.x, HEADH);
	Widget prev;
	prev = add(new Button(UI.scale(100), "Select all", false).action(() -> {
		    for(Entry entry : this.display)
			entry.mark.set(true);
		}), entrycont.pos("bl").adds(0, 5));
	prev = add(new Button(UI.scale(100), "Select none", false).action(() -> {
		    for(Entry entry : this.entries.values())
			entry.mark.set(false);
		}), prev.pos("ur").adds(5, 0));
	adda(new Button(UI.scale(150), "Remove selected", false).action(() -> {
	    Collection<Object> args = new ArrayList<>();
	    for(Entry entry : this.entries.values()) {
		if(entry.mark.a)
		    args.add(entry.id);
	    }
	    wdgmsg("rm", args.toArray(new Object[0]));
	}), entrycont.pos("br").adds(0, 5), 1, 0);
	previewBox = add(new CheckBox("Preview cull") {
		public void changed(boolean val) {
		    super.changed(val);
		    if(val) {
			order = previewOrder;
			ordercol = null;
			revorder = false;
		    } else if(order == previewOrder) {
			order = namecmp;
		    }
		    previewAge = PREVIEW_PERIOD;
		    dirty = true;
		}
	    }, UI.scale(5), UI.scale(5));
	previewBox.settip("Show what the animal bot would do right now: who it keeps, who it slaughters, and why.");
	previewInfo = add(new Label(""), previewBox.pos("ur").adds(10, 0));
	previewWarn = add(new PreviewWarnings(), UI.scale(420), previewBox.c.y);
	// Area filter - top right, left of settings button
	int settingsBtnWidth = NStyle.settingsi[0].sz().x + SETTINGS_PAD * 2;
	add(areaFilter = new NAreaDropbox(UI.scale(150)) {
		@Override
		public void change(AreaEntry item) {
			super.change(item);
			if(item != null) {
				filterAreaId = item.id;
				dirty = true;
			}
		}
		
		@Override
		public boolean mousedown(MouseDownEvent ev) {
			// Reload areas before showing dropdown to pick up new zones
			reloadAreas();
			return super.mousedown(ev);
		}
		
		@Override
		public void reloadAreas() {
			areas.clear();
			areas.add(new AreaEntry(-1, "All areas"));
			
			// Get animal type name for this roster
			String animalType = getAnimalTypeName();
			
			if (NUtils.getGameUI() != null && NUtils.getGameUI().map != null) {
				NMapView map = (NMapView) NUtils.getGameUI().map;
				if (map.glob != null && map.glob.map != null && map.glob.map.areas != null) {
					for (NArea area : map.glob.map.areas.values()) {
						// Show areas with specialization for this animal type
						boolean hasSpec = false;
						for (NArea.Specialisation spec : area.spec) {
							if (spec.name.equals(animalType)) {
								hasSpec = true;
								break;
							}
						}
						if (hasSpec) {
							areas.add(new AreaEntry(area.id, area.name));
						}
					}
					areas.subList(1, areas.size()).sort(java.util.Comparator.comparing(a -> a.name));
				}
			}
			
			if (sel == null && !areas.isEmpty()) {
				sel = areas.get(0);
			}
		}
	}, new Coord(sz.x - settingsBtnWidth - UI.scale(155), UI.scale(5)));
	add(settings = new PaddedICheckBox(NStyle.settingsi[0], NStyle.settingsi[1], NStyle.settingsi[2], NStyle.settingsi[3], SETTINGS_PAD)
	{
		@Override
		public void changed(boolean val)
		{
			super.changed(val);
			if(type == Teimdeer.class)
			{
				if(val) {
					if (dset == null) {
						dset = new Deers(CattleRoster.this);
						ui.root.add(dset, this.rootpos());
					}
					dset.show();
					dset.raise();
					dset.move(this.rootpos());
				}
				else
				{
					dset.hide();
				}
			}
			else if(type == Ochs.class)
			{
				if(val) {
					if (cset == null) {
						cset = new Cows(CattleRoster.this);
						ui.root.add(cset, this.rootpos());
					}
					cset.show();
					cset.raise();
					cset.move(this.rootpos());
				}
				else
				{
					cset.hide();
				}
			}
			else if(type == Goat.class)
			{
				if(val) {
					if (gset == null) {
						gset = new Goats(CattleRoster.this);
						ui.root.add(gset, this.rootpos());
					}
					gset.show();
					gset.raise();
					gset.move(this.rootpos());
				}
				else
				{
					gset.hide();
				}
			}
			else if(type == Sheep.class)
			{
				if(val) {
					if (sset == null) {
						sset = new Sheeps(CattleRoster.this);
						ui.root.add(sset, this.rootpos());
					}
					sset.show();
					sset.raise();
					sset.move(this.rootpos());
				}
				else
				{
					sset.hide();
				}
			}
			else if(type == Pig.class)
			{
				if(val) {
					if (pset == null) {
						pset = new Pigs(CattleRoster.this);
						ui.root.add(pset, this.rootpos());
					}
					pset.show();
					pset.raise();
					pset.move(this.rootpos());
				}
				else
				{
					pset.hide();
				}
			}
			else if(type == Horse.class)
			{
				if(val) {
					if (hset == null) {
						hset = new Horses(CattleRoster.this);
						ui.root.add(hset, this.rootpos());
					}
					hset.show();
					hset.raise();
					hset.move(this.rootpos());
				}
				else
				{
					hset.hide();
				}
			}
		}

		@Override
		public void tick(double dt) {
			if(type == Ochs.class)
			{
				if(cset!=null)
					a = cset.visible;
			}
			else if(type == Goat.class)
			{
				if(gset!=null)
					a = gset.visible;
			}
			else if(type == Sheep.class)
			{
				if(sset!=null)
					a = sset.visible;
			}
			else if(type == Pig.class)
			{
				if(pset!=null)
					a = pset.visible;
			}
			else if(type == Horse.class)
			{
				if(hset!=null)
					a = hset.visible;
			}
			else if(type == Teimdeer.class)
			{
				if(dset!=null)
					a = dset.visible;
			}
			super.tick(dt);
		}
	}, new Coord(sz.x - settingsBtnWidth, UI.scale(5) - SETTINGS_PAD));
	pack();
    }

    Deers dset = null;
    Cows cset = null;
    Goats gset = null;
    Sheeps sset = null;
    Pigs pset = null;
    Horses hset = null;

    @SafeVarargs
    public static <E extends Entry>  List<Column<? super E>> initcols(Column<? super E>... attrs) {
	numcol.x = CheckBox.sbox.sz().x + UI.scale(10);
	for(int i = 0, x = numcol.x + numcol.w + UI.scale(1); i < attrs.length; i++) {
	    Column<? super E> attr = attrs[i];
	    attr.x = x;
	    x += attr.w;
	    x += UI.scale(attr.r ? 5 : 1);
	}
	return(Arrays.asList(attrs));
    }

    public void redisplay(List<T> display) {
	Set<T> hide = new HashSet<>(entries.values());
	int h = 0, th = entrycont.sz.y;
	for(T entry : display)
	    h += entry.sz.y;
	sb.max = h - th;
	int y = -sb.val, idx = 0;
	for(T entry : display) {
	    entry.idx = idx++;
	    if((y + entry.sz.y > 0) && (y < th)) {
		entry.move(new Coord(0, y));
		entry.show();
	    } else {
		entry.hide();
	    }
	    hide.remove(entry);
	    y += entry.sz.y;
	}
	for(T entry : hide)
	    entry.hide();
	this.display = display;
    }

    public void tick(double dt) {
	if((previewAge += dt) >= PREVIEW_PERIOD) {
	    previewAge = 0;
	    updatePreview();
	}
	if(dirty) {
	    List<T> ndisp = new ArrayList<>();
	    for(T entry : entries.values()) {
		// Filter by area if selected
		if(filterAreaId < 0 || entry.areaId == filterAreaId) {
		    ndisp.add(entry);
		}
	    }
	    ndisp.sort(order);
	    redisplay(ndisp);
	    dirty = false;
	}
	super.tick(dt);
    }

    protected abstract List<Column<? super T>> cols();

    /** The preset the bot takes head count and toggles from; overridden by each species. */
    protected HerdRules currentRules() {
	return(null);
    }

    public static <E extends Entry> Column<E> rankColumn(Comparator<? super E> order) {
	return(new Column<E>(Resource.local().load("nurgling/hud/rang", 1), order, 90).key("rank"));
    }

    public static Column<Entry> verdictColumn() {
	Comparator<Entry> order = Comparator.comparingInt(e -> (e.preview == null) ? Integer.MAX_VALUE : e.preview.verdict.ordinal());
	return(new Column<Entry>("Verdict", order, 65).key("verdict"));
    }

    private Column<? super T> keyed(String key) {
	for(Column<? super T> col : cols())
	    if(key.equals(col.key))
		return(col);
	return(null);
    }

    public Column<? super T> rankcol() {
	return(keyed("rank"));
    }

    public Column<? super T> verdictcol() {
	return(keyed("verdict"));
    }

    /** True while the rows are in preview order, the only order the cull line makes sense in. */
    public boolean previewSorted() {
	return(previewBox.a && (order == previewOrder));
    }

    /** Runs the cull planner over every pen shown, the way the bot would, and tags each entry. */
    void updatePreview() {
	if(!previewBox.a) {
	    boolean had = false;
	    for(T e : entries.values()) {
		had |= (e.preview != null);
		e.preview = null;
	    }
	    if(had) {
		previewInfo.settext("");
		previewWarn.set(Collections.emptyList());
	    }
	    return;
	}
	Map<Integer, List<T>> byArea = new TreeMap<>();
	int unplaced = 0;
	for(T e : entries.values()) {
	    e.preview = null;
	    if((filterAreaId >= 0) && (e.areaId != filterAreaId))
		continue;
	    if(e.areaId < 0) {
		unplaced++;
		continue;
	    }
	    byArea.computeIfAbsent(e.areaId, k -> new ArrayList<>()).add(e);
	}
	HerdRules count = currentRules();
	List<String> warns = new ArrayList<>();
	int kill = 0, keep = 0, unsure = 0, ai = 0;
	for(Map.Entry<Integer, List<T>> ae : byArea.entrySet()) {
	    List<T> pen = ae.getValue();
	    List<AnimalFacts> herd = new ArrayList<>();
	    for(T e : pen) {
		AnimalFacts f = e.facts();
		if(f != null)
		    herd.add(f);
	    }
	    CullPlanner.Plan plan = CullPlanner.plan(herd, pen.get(0).rankRules(), count);
	    Map<Object, Integer> seq = new HashMap<>();
	    for(CullPlanner.Group grp : plan.groups) {
		for(AnimalFacts f : grp.ordered)
		    seq.put(f.id, (ai * 2 + (grp.male ? 1 : 0)) * 10000 + seq.size());
	    }
	    for(T e : pen) {
		e.preview = plan.outcome(e.id);
		Integer s = seq.get(e.id);
		e.previewSeq = (s == null) ? Integer.MAX_VALUE : s;
		if(e.preview == null)
		    continue;
		if(e.preview.verdict == CullPlanner.Verdict.KILL)
		    kill++;
		else if(e.preview.verdict == CullPlanner.Verdict.RANDOM)
		    unsure++;
		else if(e.preview.verdict != CullPlanner.Verdict.DEAD)
		    keep++;
	    }
	    String prefix = (byArea.size() > 1) ? areaName(ae.getKey()) + ": " : "";
	    for(String w : plan.warnings)
		warns.add(prefix + w);
	    ai++;
	}
	StringBuilder info = new StringBuilder();
	if(byArea.isEmpty()) {
	    info.append("No animals seen inside an animal area yet");
	} else {
	    info.append(kill).append(" slaughter, ").append(keep).append(" keep");
	    if(unsure > 0)
		info.append(", ").append(unsure).append(" uncertain");
	}
	if(unplaced > 0)
	    info.append(" (").append(unplaced).append(" not in an area)");
	if(!info.toString().equals(previewInfo.texts))
	    previewInfo.settext(info.toString());
	previewWarn.set(warns);
	if(order == previewOrder)
	    dirty = true;
    }

    private static String areaName(int id) {
	GameUI gui = NUtils.getGameUI();
	if((gui != null) && (gui.map != null) && (gui.map.glob.map.areas != null)) {
	    NArea area = gui.map.glob.map.areas.get(id);
	    if(area != null)
		return(area.name);
	}
	return("Area " + id);
    }

    /** "! 2 warnings", listing them in its tooltip. */
    static class PreviewWarnings extends Label {
	private Tex tip;
	private List<String> shown = Collections.emptyList();

	PreviewWarnings() {
	    super("");
	    setcolor(PREVIEW_WARN);
	}

	void set(List<String> warns) {
	    if(warns.equals(shown))
		return;
	    shown = new ArrayList<>(warns);
	    StringBuilder markup = new StringBuilder();
	    for(String w : warns)
		markup.append(markup.length() == 0 ? "" : "\n\n").append(RankExplainer.quote(w));
	    if(tip != null)
		tip.dispose();
	    tip = warns.isEmpty() ? null : RichText.render(markup.toString(), UI.scale(420)).tex();
	    settext(warns.isEmpty() ? "" : "! " + warns.size() + (warns.size() == 1 ? " warning" : " warnings") + " (hover)");
	}

	public Object tooltip(Coord c, Widget prev) {
	    return(tip);
	}
    }

    public void drawcols(GOut g) {
	g.aimage(numcol.head(), new Coord(numcol.x + (numcol.w / 2), TOOLBAR_H + (HEADH - TOOLBAR_H) / 2), 0.5, 0.5);
	Column prev = numcol;
	for(Column col : cols()) {
	    if((prev != null) && !prev.r) {
		g.chcolor(255, 255, 0, 64);
		int x = (prev.x + prev.w + col.x) / 2;
		g.line(new Coord(x, TOOLBAR_H), new Coord(x, sz.y), 1);
		g.chcolor();
	    }
	    if((col == mousecol) && (col.order != null)) {
		g.chcolor(255, 255, 0, 16);
		g.frect2(new Coord(col.x, TOOLBAR_H), new Coord(col.x + col.w, sz.y));
		g.chcolor();
	    }
	    if(col == ordercol) {
		g.chcolor(255, 255, 0, 16);
		g.frect2(new Coord(col.x, TOOLBAR_H), new Coord(col.x + col.w, sz.y));
		g.chcolor();
	    }
	    Tex head = col.head();
	    g.aimage(head, new Coord(col.x + (col.w / 2), TOOLBAR_H + (HEADH - TOOLBAR_H) / 2), 0.5, 0.5);
	    prev = col;
	}
	if((prev != null) && !prev.r) {
	    // Close the last column so the empty space after it doesn't read as part of it
	    g.chcolor(255, 255, 0, 64);
	    int x = prev.x + prev.w + UI.scale(1);
	    g.line(new Coord(x, TOOLBAR_H), new Coord(x, sz.y), 1);
	    g.chcolor();
	}
    }

    public void draw(GOut g) {
	drawcols(g);
	super.draw(g);
    }

    public Column<? super T> onhead(Coord c) {
	if((c.y < TOOLBAR_H) || (c.y >= HEADH))
	    return(null);
	for(Column<? super T> col : cols()) {
	    if((c.x >= col.x) && (c.x < col.x + col.w))
		return(col);
	}
	return(null);
    }

    public void mousemove(MouseMoveEvent ev) {
	super.mousemove(ev);
	mousecol = onhead(ev.c);
    }

    public boolean mousedown(MouseDownEvent ev) {
	Column<? super T> col = onhead(ev.c);
	if(ev.b == 1) {
	    if((col != null) && (col.order != null)) {
		revorder = (col == ordercol) ? !revorder : false;
		this.order = col.order;
		if(revorder)
		    this.order = this.order.reversed();
		ordercol = col;
		dirty = true;
		return(true);
	    }
	}
	return(super.mousedown(ev));
    }

    public boolean mousewheel(MouseWheelEvent ev) {
	sb.ch(ev.a * UI.scale(15));
	return(true);
    }

    public Object tooltip(Coord c, Widget prev) {
	if(mousecol != null)
	    return(mousecol.tip);
	return(super.tooltip(c, prev));
    }

    public void addentry(T entry) {
	entries.put(entry.id, entry);
	entrycont.add(entry, Coord.z);
	dirty = true;
	entryseq++;
    }

    public void delentry(UID id) {
	T entry = entries.remove(id);
	entry.destroy();
	dirty = true;
	entryseq++;
    }

    public void delentry(T entry) {
	delentry(entry.id);
    }

    public abstract T parse(Object... args);

    public void uimsg(String msg, Object... args) {
	if(msg == "add") {
	    addentry(parse(args));
	} else if(msg == "upd") {
	    T entry = parse(args);
	    // Preserve the resolved area binding across updates: parse() rebuilds the
	    // entry from server data (which carries no area), so without this the areaId
	    // resets to -1 on every upd and the custom Rank column falls back to 0.0.
	    T old = entries.get(entry.id);
	    if(old != null && old.areaId >= 0)
		entry.areaId = old.areaId;
	    delentry(entry.id);
	    addentry(entry);
	} else if(msg == "rm") {
	    delentry((UID)args[0]);
	} else if(msg == "addto") {
	    GameUI gui = (GameUI)ui.getwidget(Utils.iv(args[0]));
	    Pagina pag = gui.menu.paginafor(ui.sess.getresv(args[1]));
	    RosterButton btn = (RosterButton)Loading.waitfor(pag::button);
	    btn.add(this);
	} else {
	    super.uimsg(msg, args);
	}
    }

    public abstract TypeButton button();

    public static TypeButton typebtn(Indir<Resource> up, Indir<Resource> dn) {
	Resource ur = Loading.waitfor(() -> up.get());
	Resource.Image ui = ur.layer(Resource.imgc);
	Resource.Image di = Loading.waitfor(() -> dn.get()).layer(Resource.imgc);
	TypeButton ret = new TypeButton(ui.scaled(), di.scaled(), ui.z);
	Resource.Tooltip tip = ur.layer(Resource.tooltip);
	if(tip != null)
	    ret.settip(tip.text());
	return(ret);
    }

    private final Class<T> type;
    public Class<T> getGenType() {
	return this.type;
    }

    /**
     * Get animal type name for NConfig preset lookups
     */
    protected String getAnimalTypeName() {
        if (type == Ochs.class) return "cows";
        if (type == Goat.class) return "goats";
        if (type == Sheep.class) return "sheeps";
        if (type == Pig.class) return "pigs";
        if (type == Horse.class) return "horses";
        if (type == Teimdeer.class) return "deers";
        return "unknown";
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	settings.move(new Coord(sz.x-settings.sz.x ,0));
    }
}
