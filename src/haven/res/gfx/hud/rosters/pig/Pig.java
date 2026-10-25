/* Preprocessed source code */
/* $use: ui/croster */

package haven.res.gfx.hud.rosters.pig;

import haven.*;
import haven.res.ui.croster.*;
import nurgling.animals.AnimalFacts;
import nurgling.animals.HerdRules;
import nurgling.animals.HerdRulesFactory;
import nurgling.conf.PigsHerd;

import java.util.*;

@haven.FromResource(name = "gfx/hud/rosters/pig", version = 66)
public class Pig extends Entry {
    public int meat, milk;
    public int meatq, milkq, hideq;
    public int seedq;
    public int prc;
    public boolean hog, piglet, dead, pregnant, lactate, owned, mine;

    public Pig(UID id, String name) {
	super(SIZE, id, name);
    }

    public void draw(GOut g) {
	drawbg(g);
	int i = 0;
	drawcol(g, PigRoster.cols.get(i), 0, this, namerend, i++);
	drawcol(g, PigRoster.cols.get(i), 0.5, hog,      sex, i++);
	drawcol(g, PigRoster.cols.get(i), 0.5, piglet,   growth, i++);
	drawcol(g, PigRoster.cols.get(i), 0.5, dead,     deadrend, i++);
	drawcol(g, PigRoster.cols.get(i), 0.5, pregnant, pregrend, i++);
	drawcol(g, PigRoster.cols.get(i), 0.5, lactate,  lactrend, i++);
	drawcol(g, PigRoster.cols.get(i), 0.5, (owned ? 1 : 0) | (mine ? 2 : 0), ownrend, i++);
	drawcol(g, PigRoster.cols.get(i), 1, q, quality, i++);
	drawcol(g, PigRoster.cols.get(i), 1, prc, null, i++);
	drawcol(g, PigRoster.cols.get(i), 1, meat, null, i++);
	drawcol(g, PigRoster.cols.get(i), 1, milk, null, i++);
	drawcol(g, PigRoster.cols.get(i), 1, meatq, percent, i++);
	drawcol(g, PigRoster.cols.get(i), 1, milkq, percent, i++);
	drawcol(g, PigRoster.cols.get(i), 1, hideq, percent, i++);
	drawcol(g, PigRoster.cols.get(i), 1, seedq, null, i++);
	drawrank(g, PigRoster.cols.get(i), i++);
	drawverdict(g, PigRoster.cols.get(i), i++);
	super.draw(g);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(PigRoster.cols.get(1).hasx(ev.c.x)) {
	    markall(Pig.class, o -> (o.hog == this.hog));
	    return(true);
	}
	if(PigRoster.cols.get(2).hasx(ev.c.x)) {
	    markall(Pig.class, o -> (o.piglet == this.piglet));
	    return(true);
	}
	if(PigRoster.cols.get(3).hasx(ev.c.x)) {
	    markall(Pig.class, o -> (o.dead == this.dead));
	    return(true);
	}
	if(PigRoster.cols.get(4).hasx(ev.c.x)) {
	    markall(Pig.class, o -> (o.pregnant == this.pregnant));
	    return(true);
	}
	if(PigRoster.cols.get(5).hasx(ev.c.x)) {
	    markall(Pig.class, o -> (o.lactate == this.lactate));
	    return(true);
	}
	if(PigRoster.cols.get(6).hasx(ev.c.x)) {
	    markall(Pig.class, o -> ((o.owned == this.owned) && (o.mine == this.mine)));
	    return(true);
	}
	return(super.mousedown(ev));
    }

	public AnimalFacts facts() {
		return(new AnimalFacts(id, name, hog, piglet, dead, q, seedq)
			.with("truffle", prc)
			.with("meat", meat)
			.with("milk", milk)
			.with("meatq", meatq)
			.with("milkq", milkq)
			.with("hideq", hideq));
	}

	public HerdRules rankRules() {
		return(HerdRulesFactory.of(PigsHerd.getForArea(areaId)));
	}
}

/* >wdg: PigRoster */
