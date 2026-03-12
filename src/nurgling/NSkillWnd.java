package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;
import nurgling.i18n.L10n;

public class NSkillWnd extends SkillWnd {
    private static final Color INFO_BG = new Color(22, 39, 51);

    public NSkillWnd() {
	super();
    }

    @Override
    protected void buildLayout() {
	Widget prev;
	Coord nbtl = NFrame.nbox.btloff();

	prev = add(CharWnd.settip(new Img(catf.render(L10n.get("char.skill.title")).tex()), "gfx/hud/chr/tips/skills"), Coord.z);
	RichTextBox info = add(new RichTextBox(new Coord(attrw, height), ifnd, null), prev.pos("bl").adds(5, 0).add(nbtl));
	info.bg = INFO_BG;
	NFrame.around(this, Collections.singletonList(info));

	prev = add(new Img(catf.render(L10n.get("char.skill.entries")).tex()), width, 0);
	Tabs lists = new Tabs(prev.pos("bl").adds(5, 0), new Coord(attrw + NFrame.nbox.bisz().x, 0), this);
	int gh = UI.scale(241);
	Tabs.Tab sktab = lists.add();
	{
	    NFrame f = sktab.add(new NFrame(new Coord(lists.sz.x, UI.scale(192)), false, NFrame.nbox), 0, 0);
	    int y = f.sz.y + UI.scale(5);
	    skg = f.addin(new SkillGrid(Coord.z) {
		    public void change(Skill sk) {
			Skill p = sel;
			super.change(sk);
			NSkillWnd.this.exps.sel = null;
			NSkillWnd.this.credos.sel = null;
			if(sk != null)
			    info.set(sk::rendertext);
			else if(p != null)
			    info.set(() -> null);
		    }
		});
	    Widget bf = sktab.adda(new NFrame(new Coord(f.sz.x, UI.scale(44)), false, NFrame.nbox), f.c.x, gh, 0.0, 1.0);
	    Button bbtn = sktab.adda(new Button(UI.scale(50), L10n.get("char.skill.buy")).action(() -> {
			if(skg.sel != null)
			    skill.wdgmsg("buy", skg.sel.nm);
	    }), bf.pos("ibr").subs(10, 0).y(bf.pos("mid").y), 1.0, 0.5);
	    Label clbl = sktab.adda(new Label(L10n.get("char.skill.cost")), bf.pos("iul").adds(10, 0).y(bf.pos("mid").y), 0, 0.5);
	    sktab.adda(new RLabel<Pair<Integer, Integer>>(() -> new Pair<>(((skg.sel == null) || skg.sel.has) ? null : skg.sel.cost, this.chr.exp),
							  n -> (n.a == null) ? "N/A" : String.format("%,d / %,d LP", n.a, n.b),
							  n -> ((n.a != null) && (n.a > n.b)) ? debuff : Color.WHITE),
		       bbtn.pos("ul").subs(10, 0).y(bf.pos("mid").y), 1.0, 0.5);
	}

	Tabs.Tab credos = lists.add();
	{
	    NFrame f = credos.add(new NFrame(new Coord(lists.sz.x, gh), false, NFrame.nbox), 0, 0);
	    this.credos = f.addin(new CredoGrid(Coord.z) {
		    public void change(Credo cr) {
			Credo p = sel;
			super.change(cr);
			NSkillWnd.this.skg.sel = null;
			NSkillWnd.this.exps.sel = null;
			if(cr != null)
			    info.set(cr::rendertext);
			else if(p != null)
			    info.set(() -> null);
		    }
		});
	}

	Tabs.Tab exps = lists.add();
	{
	    NFrame f = exps.add(new NFrame(new Coord(lists.sz.x, gh), false, NFrame.nbox), 0, 0);
	    this.exps = f.addin(new ExpGrid(Coord.z) {
		    public void change(Experience exp) {
			Experience p = sel;
			super.change(exp);
			NSkillWnd.this.skg.sel = null;
			NSkillWnd.this.credos.sel = null;
			if(exp != null)
			    info.set(exp::rendertext);
			else if(p != null)
			    info.set(() -> null);
		    }
		});
	}
	lists.pack();
	addhlp(lists.c.add(0, lists.sz.y + UI.scale(5)), UI.scale(5), lists.sz.x,
	      lists.new TabButton(0, L10n.get("char.skill.tab_skills"), sktab),
	      lists.new TabButton(0, L10n.get("char.skill.tab_credos"), credos),
	      lists.new TabButton(0, L10n.get("char.skill.tab_lore"),   exps));
	pack();
    }
}
