/* Preprocessed source code */
/* $use: ui/croster */

package haven.res.gfx.hud.rosters.cow;

import haven.*;
import haven.res.ui.croster.*;
import nurgling.conf.CowsHerd;

import java.util.*;

@haven.FromResource(name = "gfx/hud/rosters/cow", version = 75)
public class Ochs extends Entry {
    public int meat, milk;
    public int meatq, milkq, hideq;
    public int seedq;
    public boolean bull, calf, dead, pregnant, lactate, owned, mine;

    public Ochs(UID id, String name) {
	super(SIZE, id, name);
    }

    public void draw(GOut g) {
	drawbg(g);
	int i = 0;
	drawcol(g, CowRoster.cols.get(i), 0, this, namerend, i++);
	drawcol(g, CowRoster.cols.get(i), 0.5, bull, sex, i++);
	drawcol(g, CowRoster.cols.get(i), 0.5, calf, growth, i++);
	drawcol(g, CowRoster.cols.get(i), 0.5, dead, deadrend, i++);
	drawcol(g, CowRoster.cols.get(i), 0.5, pregnant, pregrend, i++);
	drawcol(g, CowRoster.cols.get(i), 0.5, lactate, lactrend, i++);
	drawcol(g, CowRoster.cols.get(i), 0.5, (owned ? 1 : 0) | (mine ? 2 : 0), ownrend, i++);
	drawcol(g, CowRoster.cols.get(i), 1, q, quality, i++);
	drawcol(g, CowRoster.cols.get(i), 1, meat, null, i++);
	drawcol(g, CowRoster.cols.get(i), 1, milk, null, i++);
	drawcol(g, CowRoster.cols.get(i), 1, meatq, percent, i++);
	drawcol(g, CowRoster.cols.get(i), 1, milkq, percent, i++);
	drawcol(g, CowRoster.cols.get(i), 1, hideq, percent, i++);
	drawcol(g, CowRoster.cols.get(i), 1, seedq, null, i++);
	drawcol(g, CowRoster.cols.get(i), 1, rang(), null, i++);
	super.draw(g);
    }

    public boolean mousedown(Coord c, int button) {
	if(CowRoster.cols.get(1).hasx(c.x)) {
	    markall(Ochs.class, o -> (o.bull == this.bull));
	    return(true);
	}
	if(CowRoster.cols.get(2).hasx(c.x)) {
	    markall(Ochs.class, o -> (o.calf == this.calf));
	    return(true);
	}
	if(CowRoster.cols.get(3).hasx(c.x)) {
	    markall(Ochs.class, o -> (o.dead == this.dead));
	    return(true);
	}
	if(CowRoster.cols.get(4).hasx(c.x)) {
	    markall(Ochs.class, o -> (o.pregnant == this.pregnant));
	    return(true);
	}
	if(CowRoster.cols.get(5).hasx(c.x)) {
	    markall(Ochs.class, o -> (o.lactate == this.lactate));
	    return(true);
	}
	if(CowRoster.cols.get(6).hasx(c.x)) {
	    markall(Ochs.class, o -> ((o.owned == this.owned) && (o.mine == this.mine)));
	    return(true);
	}
	return(super.mousedown(c, button));
    }

	public double rang() {
		CowsHerd herd = CowsHerd.getCurrent();
		if(herd != null) {
			double ql = (!herd.ignoreBD || bull) ? (q > (seedq - herd.breedingGap)) ? (q + seedq - herd.breedingGap) / 2. : q + ((seedq - herd.breedingGap) - q) * herd.coverbreed : q;
			double m = (herd.disable_q_percentage ? (herd.meatq * meatq) : (ql * herd.meatq * meatq / 100.));
			double qm = meat * herd.meatquan1 + ((meat > herd.meatquanth) ? ((meat - herd.meatquanth) * (herd.meatquan2 - herd.meatquan1)) : 0);
			double _milk = (herd.disable_q_percentage ? (herd.milkq * milkq) : (ql * herd.milkq * milkq / 100.));
			double qmilk = milk * herd.milkquan1 + ((milk > herd.milkquanth) ? ((milk - herd.milkquanth) * (herd.milkquan2 - herd.milkquan1)) : 0);
			double hide = (herd.disable_q_percentage ? (herd.hideq * hideq) : (ql * herd.hideq * hideq / 100.));
			double k_res = m + qm + _milk + qmilk + hide;
			double result = k_res == 0 ? ql : Math.round(k_res * 10) / 10.;
			return result;
		}
		return 0;
	}
}

/* >wdg: CowRoster */
