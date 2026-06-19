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

public abstract class MenuSearch extends Window {
    public final MenuGrid menu;
    public final Results rls;
    public final TextEntry sbox;
    protected List<Result> cur = Collections.emptyList();
    protected List<Result> filtered = Collections.emptyList();
    private boolean recons = true;
    private Coord drag_start = null;
    private boolean drag_mode = false;
    private Grab grab = null;

    public class Result {
	public final PagButton btn;
	public final BotDescriptor bot;

	protected Result(PagButton btn) {
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

    public static final Text.Foundry elf = CharWnd.attrf;
    public static final int elh = elf.height() + UI.scale(2);
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

    public MenuSearch(String title, MenuGrid menu) {
	super(Coord.z, title);
	this.menu = menu;
	rls = add(new Results(UI.scale(250, 500)), Coord.z);
	sbox = add(new TextEntry(UI.scale(250), "") {
		protected void changed() {
		    refilter();
		}

		public void activate(String text) {
		    if(rls.sel != null)
			activateResult(rls.sel);
		    if(!ui.modctrl) {
			reqclose();
			settext("");
			refilter();
		    }
		}
	    }, 0, rls.sz.y);
	pack();
    }

    public MenuSearch(MenuGrid menu) {
	this("Action search", menu);
    }

    protected void refilter() {
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

    protected abstract boolean generate(List<PagButton> buf);

    protected void updlist() {
	recons = false;
	List<PagButton> buf = new ArrayList<>();
	if(generate(buf))
	    recons = true;

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
	for(PagButton btn : buf) {
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

    protected void recons() {
	recons = true;
    }

    public void tick(double dt) {
	if(tvisible() && recons)
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

    public static class Main extends MenuSearch {
	private int pagseq;
	private final Set<Pagina> allPaginae = new HashSet<>();

	public static final KeyBinding kb_itemcraft = KeyBinding.get("scm-itemcraft", KeyMatch.nil);
	public Main(MenuGrid menu) {
	    super(menu);
	    add(new Button(sbox.sz.x, "Search by ingredient", false).action(() -> menu.wdgmsg("act", "itemcraft")),
		sbox.pos("bl").adds(0, 5)).setgkey(kb_itemcraft);
	    pagseq = menu.pagseq;
	    pack();
	}

	// Nurgling: global action search across every pagina ever seen (ignores
	// the current menu category), so the search box finds anything.
	protected boolean generate(List<PagButton> buf) {
	    boolean recons = false;
	    synchronized(menu.paginae) {
		allPaginae.addAll(menu.paginae);
	    }
	    for(Pagina pag : allPaginae) {
		try {
		    buf.add(pag.button());
		} catch(Loading l) {
		    recons = true;
		}
	    }
	    Collections.sort(buf, Comparator.comparing(PagButton::name));
	    return(recons);
	}

	public void tick(double dt) {
	    if(tvisible()) {
		if(pagseq != menu.pagseq) {
		    recons();
		    pagseq = menu.pagseq;
		}
	    }
	    super.tick(dt);
	}
    }
}
