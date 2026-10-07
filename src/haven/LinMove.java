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

import java.util.function.DoubleSupplier;

public class LinMove extends Moving {
    public static final double MAXOVER = 0.5;
    public Coord2d s, v;
    public double t, lt, e;
    public boolean ts = false;
    private final DoubleSupplier clock;
    private double advancedAt;

    public LinMove(Gob gob, Coord2d s, Coord2d v) {
	this(gob, s, v, Utils::rtime);
    }

    /* The injected clock also permits deterministic replay of network/frame stalls. */
    LinMove(Gob gob, Coord2d s, Coord2d v, DoubleSupplier clock) {
	super(gob);
	this.clock = clock;
	this.advancedAt = clock.getAsDouble();
	this.s = s;
	this.v = v;
	this.t = 0;
	this.e = Double.NaN;
    }

    public Coord3f getc() {
	return(gob.placer().getc(position(), gob.a));
    }

    Coord2d position() { return(s.add(v.mul(t))); }

    /** Predicted time until this trajectory ends; infinite while the server hasn't said. */
    double remaining() { return(Double.isNaN(e) ? Double.POSITIVE_INFINITY : Math.max(0, e - t)); }

    public double getv() {
	return(v.abs());
    }

    public void ctick(double dt) {
	advance(clock.getAsDouble());
    }

    private void advance(double now) {
	/* Glob's dt can begin before this trajectory or a server correction existed.
	 * Integrate only the interval not already consumed by this movement. */
	double dt = Math.max(0, now - advancedAt);
	advancedAt = Math.max(advancedAt, now);
	if(!ts) {
	    t += dt * 0.9;
	    if(!Double.isNaN(e) && (t > e)) {
		t = e;
	    } else if(t > lt + MAXOVER) {
		t = lt + MAXOVER;
		ts = true;
	    }
	}
    }

    public void sett(double t) {
	/* Consume elapsed prediction before comparing the authoritative time. This
	 * also prevents a delayed but older update from rewinding our prediction. */
	advance(clock.getAsDouble());
	lt = t;
	if(t > this.t) {
	    Coord2d before = position();
	    this.t = t;
	    ts = false;
	    if(gob != null) gob.correctMovement(before, position());
	}
    }

    @OCache.DeltaType(OCache.OD_LINBEG)
    public static class $linbeg implements OCache.Delta {
	public void apply(Gob g, OCache.AttrDelta msg) {
	    Coord2d s = msg.coord().mul(OCache.posres);
	    Coord2d v = msg.coord().mul(OCache.posres);
	    LinMove lm = g.getattr(LinMove.class);
	    if((lm == null) || !lm.s.equals(s) || !lm.v.equals(v)) {
		if(lm != null) lm.advance(lm.clock.getAsDouble());
		g.correctMovement(lm == null ? g.rc : lm.position(), s);
		g.setattr(new LinMove(g, s, v));
		nurgling.diagnostics.MovementTrace.server(g, "linbeg", s, v, lm == null ? Double.NaN : lm.t, 0, Double.NaN);
	    }
	}
    }

    @OCache.DeltaType(OCache.OD_LINSTEP)
    public static class $linstep implements OCache.Delta {
	public void apply(Gob g, OCache.AttrDelta msg) {
	    double t, e;
	    int w = msg.int32();
	    if(w == -1) {
		t = e = -1;
	    } else if((w & 0x80000000) == 0) {
		t = w * 0x1p-10;
		e = -1;
	    } else {
		t = (w & ~0x80000000) * 0x1p-10;
		w = msg.int32();
		e = (w < 0)?-1:(w * 0x1p-10);
	    }
	    Moving m = g.getattr(Moving.class);
	    if((m == null) || !(m instanceof LinMove))
		return;
	    LinMove lm = (LinMove)m;
	    double before = lm.t;
	    if(t < 0) {
		lm.advance(lm.clock.getAsDouble());
		g.correctMovement(lm.position(), g.rc);
		g.delattr(Moving.class);
	    } else
			lm.sett(t);
	    if(e >= 0)
		lm.e = e;
	    else
		lm.e = Double.NaN;
	    nurgling.diagnostics.MovementTrace.server(g, "linstep", null, null, before, t, e);
	}
    }

	@Override
	public Coord3f gett() {
		double mt = Double.isNaN(e) ? lt + MAXOVER : e;
		return (gob.glob.map.getzp(s.add(v.mul(mt))));
	}
}
