/* Preprocessed source code */
/* $use: ui/croster */

package haven.res.gfx.hud.rosters.horse;

import haven.*;
import haven.res.ui.croster.*;
import nurgling.animals.AnimalFacts;
import nurgling.animals.HerdRules;
import nurgling.animals.HerdRulesFactory;
import nurgling.conf.HorseHerd;

import java.util.*;

@haven.FromResource(name = "gfx/hud/rosters/horse", version = 66)
public class Horse extends Entry {
    public int meat, milk;
    public int meatq, milkq, hideq;
    public int seedq;
    public int end, stam, mb;
    public boolean stallion, foal, dead, pregnant, lactate, owned, mine;

    public Horse(UID id, String name) {
	super(SIZE, id, name);
    }

    public void draw(GOut g) {
	drawbg(g);
	int i = 0;
	drawcol(g, HorseRoster.cols.get(i), 0, this, namerend, i++);
	drawcol(g, HorseRoster.cols.get(i), 0.5, stallion, sex, i++);
	drawcol(g, HorseRoster.cols.get(i), 0.5, foal,     growth, i++);
	drawcol(g, HorseRoster.cols.get(i), 0.5, dead,     deadrend, i++);
	drawcol(g, HorseRoster.cols.get(i), 0.5, pregnant, pregrend, i++);
	drawcol(g, HorseRoster.cols.get(i), 0.5, lactate,  lactrend, i++);
	drawcol(g, HorseRoster.cols.get(i), 0.5, (owned ? 1 : 0) | (mine ? 2 : 0), ownrend, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, q, quality, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, end, null, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, stam, null, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, mb, null, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, meat, null, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, milk, null, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, meatq, percent, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, milkq, percent, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, hideq, percent, i++);
	drawcol(g, HorseRoster.cols.get(i), 1, seedq, null, i++);
	drawrank(g, HorseRoster.cols.get(i), i++);
	drawverdict(g, HorseRoster.cols.get(i), i++);
	super.draw(g);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(HorseRoster.cols.get(1).hasx(ev.c.x)) {
	    markall(Horse.class, o -> (o.stallion == this.stallion));
	    return(true);
	}
	if(HorseRoster.cols.get(2).hasx(ev.c.x)) {
	    markall(Horse.class, o -> (o.foal == this.foal));
	    return(true);
	}
	if(HorseRoster.cols.get(3).hasx(ev.c.x)) {
	    markall(Horse.class, o -> (o.dead == this.dead));
	    return(true);
	}
	if(HorseRoster.cols.get(4).hasx(ev.c.x)) {
	    markall(Horse.class, o -> (o.pregnant == this.pregnant));
	    return(true);
	}
	if(HorseRoster.cols.get(5).hasx(ev.c.x)) {
	    markall(Horse.class, o -> (o.lactate == this.lactate));
	    return(true);
	}
	if(HorseRoster.cols.get(6).hasx(ev.c.x)) {
	    markall(Horse.class, o -> ((o.owned == this.owned) && (o.mine == this.mine)));
	    return(true);
	}
	return(super.mousedown(ev));
    }

	public AnimalFacts facts() {
		return(new AnimalFacts(id, name, stallion, foal, dead, q, seedq)
			.with("meat", meat)
			.with("milk", milk)
			.with("meatq", meatq)
			.with("milkq", milkq)
			.with("hideq", hideq)
			.with("stam", stam)
			.with("end", end)
			.with("mb", mb));
	}

	public HerdRules rankRules() {
		return(HerdRulesFactory.of(HorseHerd.getForArea(areaId)));
	}
}

/* >wdg: HorseRoster */
