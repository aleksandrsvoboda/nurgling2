/* Preprocessed source code */
/* $use: ui/croster */

package haven.res.gfx.hud.rosters.teimdeer;

import haven.*;
import haven.res.ui.croster.*;
import nurgling.animals.AnimalFacts;
import nurgling.animals.HerdRules;
import nurgling.animals.HerdRulesFactory;
import nurgling.conf.TeimDeerHerd;

import java.util.*;

@haven.FromResource(name = "gfx/hud/rosters/teimdeer", version = 5)
public class Teimdeer extends Entry {
    public int meat, milk;
    public int meatq, milkq, hideq;
    public int seedq;
    public boolean buck, fawn, dead, pregnant, lactate, owned, mine;

    public Teimdeer(UID id, String name) {
	super(SIZE, id, name);
    }

    public void draw(GOut g) {
	drawbg(g);
	int i = 0;
	drawcol(g, TeimdeerRoster.cols.get(i), 0, this, namerend, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 0.5, buck,     sex, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 0.5, fawn,     growth, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 0.5, dead,     deadrend, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 0.5, pregnant, pregrend, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 0.5, lactate,  lactrend, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 0.5, (owned ? 1 : 0) | (mine ? 2 : 0), ownrend, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 1, q, quality, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 1, meat, null, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 1, milk, null, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 1, meatq, percent, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 1, milkq, percent, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 1, hideq, percent, i++);
	drawcol(g, TeimdeerRoster.cols.get(i), 1, seedq, null, i++);
	drawrank(g, TeimdeerRoster.cols.get(i), i++);
	drawverdict(g, TeimdeerRoster.cols.get(i), i++);
	super.draw(g);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(TeimdeerRoster.cols.get(1).hasx(ev.c.x)) {
	    markall(Teimdeer.class, o -> (o.buck == this.buck));
	    return(true);
	}
	if(TeimdeerRoster.cols.get(2).hasx(ev.c.x)) {
	    markall(Teimdeer.class, o -> (o.fawn == this.fawn));
	    return(true);
	}
	if(TeimdeerRoster.cols.get(3).hasx(ev.c.x)) {
	    markall(Teimdeer.class, o -> (o.dead == this.dead));
	    return(true);
	}
	if(TeimdeerRoster.cols.get(4).hasx(ev.c.x)) {
	    markall(Teimdeer.class, o -> (o.pregnant == this.pregnant));
	    return(true);
	}
	if(TeimdeerRoster.cols.get(5).hasx(ev.c.x)) {
	    markall(Teimdeer.class, o -> (o.lactate == this.lactate));
	    return(true);
	}
	if(TeimdeerRoster.cols.get(6).hasx(ev.c.x)) {
	    markall(Teimdeer.class, o -> ((o.owned == this.owned) && (o.mine == this.mine)));
	    return(true);
	}
	return(super.mousedown(ev));
    }

	public AnimalFacts facts() {
		return(new AnimalFacts(id, name, buck, fawn, dead, q, seedq)
			.with("meat", meat)
			.with("milk", milk)
			.with("meatq", meatq)
			.with("milkq", milkq)
			.with("hideq", hideq));
	}

	public HerdRules rankRules() {
		return(HerdRulesFactory.of(TeimDeerHerd.getForArea(areaId)));
	}
}

/* >wdg: TeimdeerRoster */
