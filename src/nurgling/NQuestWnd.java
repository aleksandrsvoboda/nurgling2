package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;
import nurgling.i18n.L10n;

public class NQuestWnd extends QuestWnd {
    private static final Color ROW_EVEN = new Color(51, 62, 64);
    private static final Color ROW_ODD  = new Color(40, 52, 54);
    private static final Color INFO_BG  = new Color(22, 39, 51);

    public NQuestWnd() {
	super();
    }

    @Override
    protected void buildLayout() {
	Widget prev;
	Coord nbtl = NFrame.nbox.btloff();

	prev = add(CharWnd.settip(new Img(catf.render("Quest Log").tex()), "gfx/hud/chr/tips/quests"), new Coord(0, 0));
	questbox = add(new Widget(new Coord(attrw, height)) {
		public void draw(GOut g) {
		    g.chcolor(INFO_BG);
		    g.frect(Coord.z, sz);
		    g.chcolor();
		    super.draw(g);
		}

		public void cdestroy(Widget w) {
		    if(w == quest)
			quest = null;
		}
	    }, prev.pos("bl").adds(5, 0).add(nbtl));
	NFrame.around(this, Collections.singletonList(questbox));
	Tabs lists = new Tabs(prev.pos("bl").x(width + UI.scale(5)), Coord.z, this);
	Tabs.Tab cqst = lists.add();
	{
	    this.cqst = cqst.add(new QuestList(Coord.of(attrw, height - Button.hs - UI.scale(5))) {
		@Override
		protected void drawslot(GOut g, Quest q, int idx, Area area) {
		    g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		    g.frect2(area.ul, area.br);
		    g.chcolor();
		    if((quest != null) && (quest.questid() == q.id))
			drawsel(g, q, idx, area);
		}
	    }, nbtl);
	    NFrame.around(cqst, Collections.singletonList(this.cqst));
	}
	Tabs.Tab dqst = lists.add();
	{
	    this.dqst = dqst.add(new QuestList(Coord.of(attrw, height - Button.hs - UI.scale(5))) {
		@Override
		protected void drawslot(GOut g, Quest q, int idx, Area area) {
		    g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		    g.frect2(area.ul, area.br);
		    g.chcolor();
		    if((quest != null) && (quest.questid() == q.id))
			drawsel(g, q, idx, area);
		}
	    }, nbtl);
	    NFrame.around(dqst, Collections.singletonList(this.dqst));
	}
	lists.pack();
	addhlp(lists.c.add(0, lists.sz.y + UI.scale(5)), UI.scale(5), lists.sz.x,
		     lists.new TabButton(0, "Current",   cqst),
		     lists.new TabButton(0, "Completed", dqst));
	pack();
    }
}
