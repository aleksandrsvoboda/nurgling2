package nurgling;

import haven.*;
import java.util.*;
import java.awt.Color;
import static haven.CharWnd.*;

public class NQuestWnd extends QuestWnd {
    private static final Color ROW_EVEN = new Color(51, 62, 64);
    private static final Color ROW_ODD  = new Color(40, 52, 54);
    private static final Color INFO_BG  = new Color(0x1C, 0x25, 0x26);

    private static final int INFO_W = UI.scale(267);
    private static final int INFO_H = UI.scale(348);
    private static final int LIST_W = UI.scale(267);
    private static final int LIST_H = UI.scale(307);
    private static final int SECTION_GAP = UI.scale(17);
    private static final int TITLE_GAP = UI.scale(5);
    private static final int LIST_BTN_GAP = UI.scale(15);
    private static final int QUEST_ITEM_H = UI.scale(26);

    public NQuestWnd() {
	super();
    }

    @Override
    protected void buildLayout() {
	Coord nbisz = NFrame.nbox.bisz();
	Coord nbtl = NFrame.nbox.btloff();
	int infoInnerW = INFO_W - nbisz.x;
	int infoInnerH = INFO_H - nbisz.y;

	// Title
	Widget prev = add(CharWnd.settip(new Img(catf.render("Quest Log").tex()), "gfx/hud/chr/tips/quests"), new Coord(0, 0));
	int contentY = prev.pos("bl").y + TITLE_GAP;

	// Description box (left) — NFrame orange border
	questbox = add(new Widget(new Coord(infoInnerW, infoInnerH)) {
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
	    }, nbtl.x, contentY + nbtl.y);
	NFrame.around(this, Collections.singletonList(questbox));

	// Quest list (right) — no orange border, dark bg
	int listX = INFO_W + SECTION_GAP;
	add(new Widget(new Coord(LIST_W, LIST_H)) {
	    public void draw(GOut g) {
		g.chcolor(INFO_BG);
		g.frect(Coord.z, sz);
		g.chcolor();
		super.draw(g);
	    }
	}, listX, contentY);

	Tabs lists = new Tabs(Coord.of(listX, contentY), Coord.z, this);
	Tabs.Tab cqst = lists.add();
	{
	    this.cqst = cqst.add(new QuestList(new Coord(LIST_W, LIST_H), QUEST_ITEM_H) {
		@Override
		protected void drawslot(GOut g, Quest q, int idx, Area area) {
		    g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		    g.frect2(area.ul, area.br);
		    g.chcolor();
		    if((quest != null) && (quest.questid() == q.id))
			drawsel(g, q, idx, area);
		}
	    }, 0, 0);
	}
	Tabs.Tab dqst = lists.add();
	{
	    this.dqst = dqst.add(new QuestList(new Coord(LIST_W, LIST_H), QUEST_ITEM_H) {
		@Override
		protected void drawslot(GOut g, Quest q, int idx, Area area) {
		    g.chcolor(((idx % 2) == 0) ? ROW_EVEN : ROW_ODD);
		    g.frect2(area.ul, area.br);
		    g.chcolor();
		    if((quest != null) && (quest.questid() == q.id))
			drawsel(g, q, idx, area);
		}
	    }, 0, 0);
	}
	lists.pack();

	// Current / Completed buttons — 15px below list
	int btnY = contentY + LIST_H + LIST_BTN_GAP;
	addhlp(new Coord(listX, btnY), UI.scale(5), LIST_W,
		     lists.new TabButton(0, "Current",   cqst),
		     lists.new TabButton(0, "Completed", dqst));
	pack();
    }
}
