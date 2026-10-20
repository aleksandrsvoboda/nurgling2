/* Preprocessed source code */
/* $use: ui/croster */

package haven.res.gfx.hud.rosters.sheep;

import haven.*;
import haven.res.ui.croster.*;
import nurgling.animals.AnimalFacts;
import nurgling.animals.HerdRules;
import nurgling.animals.HerdRulesFactory;
import nurgling.conf.SheepsHerd;

import java.util.*;

@haven.FromResource(name = "gfx/hud/rosters/sheep", version = 68)
public class Sheep extends Entry {
    public int meat, milk, wool;
    public int meatq, milkq, woolq, hideq;
    public int seedq;
    public boolean ram, lamb, dead, pregnant, lactate, owned, mine;

    public Sheep(UID id, String name) {
	super(SIZE, id, name);
    }

    public void draw(GOut g) {
	drawbg(g);
	int i = 0;
	drawcol(g, SheepRoster.cols.get(i), 0, this, namerend, i++);
	drawcol(g, SheepRoster.cols.get(i), 0.5, ram,      sex, i++);
	drawcol(g, SheepRoster.cols.get(i), 0.5, lamb,     growth, i++);
	drawcol(g, SheepRoster.cols.get(i), 0.5, dead,     deadrend, i++);
	drawcol(g, SheepRoster.cols.get(i), 0.5, pregnant, pregrend, i++);
	drawcol(g, SheepRoster.cols.get(i), 0.5, lactate,  lactrend, i++);
	drawcol(g, SheepRoster.cols.get(i), 0.5, (owned ? 1 : 0) | (mine ? 2 : 0), ownrend, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, q, quality, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, meat, null, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, milk, null, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, wool, null, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, meatq, percent, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, milkq, percent, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, woolq, percent, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, hideq, percent, i++);
	drawcol(g, SheepRoster.cols.get(i), 1, seedq, null, i++);
	drawrank(g, SheepRoster.cols.get(i), i++);
	drawverdict(g, SheepRoster.cols.get(i), i++);
	super.draw(g);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(SheepRoster.cols.get(1).hasx(ev.c.x)) {
	    markall(Sheep.class, o -> (o.ram == this.ram));
	    return(true);
	}
	if(SheepRoster.cols.get(2).hasx(ev.c.x)) {
	    markall(Sheep.class, o -> (o.lamb == this.lamb));
	    return(true);
	}
	if(SheepRoster.cols.get(3).hasx(ev.c.x)) {
	    markall(Sheep.class, o -> (o.dead == this.dead));
	    return(true);
	}
	if(SheepRoster.cols.get(4).hasx(ev.c.x)) {
	    markall(Sheep.class, o -> (o.pregnant == this.pregnant));
	    return(true);
	}
	if(SheepRoster.cols.get(5).hasx(ev.c.x)) {
	    markall(Sheep.class, o -> (o.lactate == this.lactate));
	    return(true);
	}
	if(SheepRoster.cols.get(6).hasx(ev.c.x)) {
	    markall(Sheep.class, o -> ((o.owned == this.owned) && (o.mine == this.mine)));
	    return(true);
	}
	return(super.mousedown(ev));
    }

	public AnimalFacts facts() {
		return(new AnimalFacts(id, name, ram, lamb, dead, q, seedq)
			.with("meat", meat)
			.with("milk", milk)
			.with("wool", wool)
			.with("meatq", meatq)
			.with("milkq", milkq)
			.with("woolq", woolq)
			.with("hideq", hideq));
	}

	public HerdRules rankRules() {
		return(HerdRulesFactory.of(SheepsHerd.getForArea(areaId)));
	}
}

/* >wdg: SheepRoster */
