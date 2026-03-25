package haven;

import java.util.*;
import java.awt.image.BufferedImage;
import haven.MenuGrid.Pagina;
import haven.UI.Grab;
import haven.MenuGrid.Interaction;
import haven.MenuGrid.PagButton;
import nurgling.NUtils;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.sessions.BotExecutor;
import nurgling.widgets.NBotsMenu;

public class MenuSearch extends Window {
    public final MenuGrid menu;
    public final Results rls;
    public final TextEntry sbox;
    private Pagina root;
    private List<Result> cur = Collections.emptyList();
    private List<Result> filtered = Collections.emptyList();
    private boolean recons = false;
    private Coord drag_start = null;
    private boolean drag_mode = false;
    private Grab grab = null;
    private final Set<Pagina> allPaginae = new HashSet<>();

    public class Result {
	public final PagButton btn;
	public final BotDescriptor bot;

	private Result(PagButton btn) {
	    this.btn = btn;
	    this.bot = null;
	}

	private Result(BotDescriptor bot) {
	    this.btn = null;
	    this.bot = bot;
	}

	public String name() {
	    return btn != null ? btn.name() : bot.getDisplayName();
	}

	public BufferedImage img() {
	    if(btn != null)
		return btn.img();
	    try {
		return Resource.loadsimg(bot.getUpIconPath());
	    } catch(Exception e) {
		return null;
	    }
	}

	public boolean isBot() {
	    return bot != null;
	}
    }

    private static final Text.Foundry elf = CharWnd.attrf;
    private static final int elh = elf.height() + UI.scale(2);
    public class Results extends SListBox<Result, Widget> {
	private Results(Coord sz) {
	    super(sz, elh);
	}

	protected List<Result> items() {return(filtered);}

	protected Widget makeitem(Result el, int idx, Coord sz) {
	    return(new ItemWidget<Result>(this, sz, el) {
		    {
			add(new IconText(sz) {
				protected BufferedImage img() {return(item.img());}
				protected String text() {return(el.name());}
				protected int margin() {return(0);}
				protected Text.Foundry foundry() {return(elf);}
			    }, Coord.z);
		    }

		    @Override public boolean mousedown(MouseDownEvent ev) {
			super.mousedown(ev);

			if(ev.b == 1){
			    drag_start = ui.mc;
			    drag_mode = false;
			    grab = ui.grabmouse(this);
			}

			return(true);
		    }

		    @Override public void mousemove(MouseMoveEvent ev) {
			if(!drag_mode && drag_start != null && drag_start.dist(ui.mc) > 40) {
			    drag_mode = true;
			}
			super.mousemove(ev);
		    }

		    @Override public boolean mouseup(MouseUpEvent ev) {
			if((ev.b == 1) && (grab != null)) {
			    if(drag_mode) {
				if(rls.sel.isBot()) {
				    NBotsMenu.NButton nbtn = NUtils.getGameUI().botsMenu.find(rls.sel.bot.iconPath);
				    if(nbtn != null)
					DropTarget.dropthing(ui.root, ui.mc, nbtn);
				} else {
				    DropTarget.dropthing(ui.root, ui.mc, rls.sel.btn.pag);
				}
			    } else {
				activateResult(rls.sel);
			    }

			    drag_start = null;
			    drag_mode = false;

			    grab.remove();
			    grab = null;

			    // Defocus the search box after selecting something
			    if(ui.gui != null && ui.gui.portrait != null)
				setfocus(ui.gui.portrait);
			}
			return super.mouseup(ev);
		    }

		});
	}

	@Override
	public Object tooltip(Coord c, Widget prev) {
	    try {
		int slot = slotat(c);
		final Result item = items().get(slot);
		if (item != null) {
		    if(item.isBot()) {
			return new TexI(Text.render(item.bot.getDescription()).img);
		    } else {
			return new TexI(item.btn.rendertt(true));
		    }
		} else {
		    return super.tooltip(c, prev);
		}
	    } catch (Exception ignored){}
	    return null;
	}
    }

    private void activateResult(Result sel) {
	if(sel == null)
	    return;
	if(sel.isBot()) {
	    nurgling.actions.Action action = sel.bot.instantiate(Map.of());
	    BotExecutor.runWithSupports(sel.bot.getDisplayName(), action, sel.bot.disStacks, null);
	} else {
	    menu.use(sel.btn, new Interaction(), false);
	}
    }

    public MenuSearch(MenuGrid menu) {
	super(Coord.z, "Action search");
	this.menu = menu;
	rls = add(new Results(UI.scale(250, 500)), Coord.z);
	sbox = add(new TextEntry(UI.scale(250), "") {
		protected void changed() {
		    refilter();
		}

		public void activate(String text) {
		    if(rls.sel != null)
			activateResult(rls.sel);
		    if(!ui.modctrl)
			MenuSearch.this.wdgmsg("close");
		}
	    }, 0, rls.sz.y);
	pack();
	setroot(null);
    }

    private void refilter() {
	List<Result> found = Fuzzy.fuzzyFilterAndSort(sbox.text().toLowerCase(), this.cur);
	this.filtered = found;
	int idx = filtered.indexOf(rls.sel);
	if(idx < 0) {
	    if(filtered.size() > 0) {
		rls.change(filtered.get(0));
		rls.display(0);
	    }
	} else {
	    rls.display(idx);
	}
    }

    private void updlist() {
	recons = false;
	// Accumulate all paginae ever seen for global search
	synchronized(menu.paginae) {
	    allPaginae.addAll(menu.paginae);
	}
	List<PagButton> found = new ArrayList<>();
	for(Pagina pag : allPaginae) {
	    try {
		found.add(pag.button());
	    } catch(Loading l) {
		recons = true;
	    }
	}
	Collections.sort(found, Comparator.comparing(PagButton::name));

	// Build results, reusing existing Result objects where possible
	Map<PagButton, Result> prevBtns = new HashMap<>();
	Map<String, Result> prevBots = new HashMap<>();
	for(Result pr : this.cur) {
	    if(pr.btn != null)
		prevBtns.put(pr.btn, pr);
	    else if(pr.bot != null)
		prevBots.put(pr.bot.id, pr);
	}

	List<Result> results = new ArrayList<>();
	for(PagButton btn : found) {
	    Result pr = prevBtns.get(btn);
	    if(pr != null)
		results.add(pr);
	    else
		results.add(new Result(btn));
	}

	// Add bots from BotRegistry
	for(BotDescriptor bd : BotRegistry.allowedInBotMenu()) {
	    Result pr = prevBots.get(bd.id);
	    if(pr != null)
		results.add(pr);
	    else
		results.add(new Result(bd));
	}

	this.cur = results;
	refilter();
    }

    public void setroot(Pagina nr) {
	root = nr;
	updlist();
	rls.sb.val = 0;
    }

    public void tick(double dt) {
	boolean needsUpdate = recons;
	if(!needsUpdate) {
	    synchronized(menu.paginae) {
		needsUpdate = !allPaginae.containsAll(menu.paginae);
	    }
	}
	if(needsUpdate)
	    updlist();
	super.tick(dt);
    }

    public boolean keydown(KeyDownEvent ev) {
	if(ev.code == ev.awt.VK_DOWN) {
	    int idx = filtered.indexOf(rls.sel);
	    if((idx >= 0) && (idx < filtered.size() - 1)) {
		idx++;
		rls.change(filtered.get(idx));
		rls.display(idx);
	    }
	    return(true);
	} else if(ev.code == ev.awt.VK_UP) {
	    int idx = filtered.indexOf(rls.sel);
	    if(idx > 0) {
		idx--;
		rls.change(filtered.get(idx));
		rls.display(idx);
	    }
	    return(true);
	} else {
	    return(super.keydown(ev));
	}
    }

    public void draw(GOut g) {
	super.draw(g);
	// Drawing the drag icon
	if(drag_mode && rls.sel != null) {
	    if(rls.sel.isBot()) {
		BufferedImage ds = rls.sel.img();
		if(ds != null) {
		    Coord dssz = new Coord(ds.getWidth(), ds.getHeight());
		    ui.drawafter(new UI.AfterDraw() {
			public void draw(GOut g) {
			    g.image(new TexI(ds), ui.mc.sub(dssz.div(2)));
			}
		    });
		}
	    } else {
		GSprite ds = rls.sel.btn.spr();
		ui.drawafter(new UI.AfterDraw() {
		    public void draw(GOut g) {
			ds.draw(g.reclip(ui.mc.sub(ds.sz().div(2)), ds.sz()));
		    }
		});
	    }
	}
    }
}
