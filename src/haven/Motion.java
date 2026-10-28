package haven;

import haven.render.*;

/*
 * nurgling: a mover's render-time motion for the decoupled renderer.
 * The world tick samples it next to the placement it publishes (see
 * Gob.Placed); the present thread then shifts that placement by the
 * motion up to the moment it draws, so movement stays smooth between
 * world ticks and through short UI-thread stalls.
 */
final class Motion {
    final Pipe.Op base;
    /* Render-space units per second. */
    final Coord3f v;
    /* When the sample was taken, and when its velocity stops holding
     * (the end of a known path or of the movement prediction). */
    final double t0, until;

    private Motion(Pipe.Op base, Coord3f v, double t0, double until) {
	this.base = base;
	this.v = v;
	this.t0 = t0;
	this.until = until;
    }

    /* Null when the gob, or what it rides, is not moving. Called
     * under the gob's lock. */
    static Motion of(Gob gob, Pipe.Op base, double now) {
	Gob target = gob;
	for(int i = 0; i < 8; i++) {
	    Following following = target.getattr(Following.class);
	    Gob next = (following == null) ? null : following.tgt();
	    if(next == null)
		break;
	    target = next;
	}
	Moving m = target.getattr(Moving.class);
	Coord2d pos, v;
	double horizon;
	if(m instanceof LinMove) {
	    LinMove lm = (LinMove)m;
	    /* The prediction already ran out; LinMove holds still. */
	    if(lm.ts)
		return(null);
	    double left = lm.lt + LinMove.MAXOVER - lm.t;
	    if(!Double.isNaN(lm.e))
		left = Math.min(left, lm.e - lm.t);
	    if((left <= 0) || (lm.v.abs() < 1e-6))
		return(null);
	    /* LinMove advances its time at 0.9 of real time. */
	    v = lm.v.mul(0.9);
	    horizon = left / 0.9;
	    pos = lm.position();
	} else if(m instanceof Homing) {
	    Homing h = (Homing)m;
	    Gob tgt = h.tgt();
	    Coord2d d = ((tgt == null) ? h.tc : tgt.rc).sub(target.rc);
	    double e = d.abs(), left = e - h.dist;
	    if((left <= 0) || (h.v <= 0))
		return(null);
	    Coord2d dir = d.div(e);
	    v = dir.mul(h.v * 0.9);
	    horizon = left / (h.v * 0.9);
	    pos = target.rc.add(dir.mul(h.dist));
	} else {
	    return(null);
	}
	/* Follow the ground: the height change over the extrapolation
	 * window, as a vertical speed. */
	double vz = 0;
	try {
	    Gob.Placer pl = target.placer();
	    float z0 = pl.getc(pos, target.a).z;
	    float z1 = pl.getc(pos.add(v.mul(PresentLoop.AHEAD)), target.a).z;
	    vz = (z1 - z0) / PresentLoop.AHEAD;
	} catch(Loading l) {
	}
	/* Render space mirrors the world's y axis. */
	return(new Motion(base, new Coord3f((float)v.x, (float)-v.y, (float)vz), now, now + horizon));
    }

    /* How far the gob has moved past its sample at time now; null
     * when that is nothing. */
    Coord3f offset(double now) {
	double dt = Math.min(Math.min(now, until) - t0, PresentLoop.AHEAD);
	if(dt <= 0)
	    return(null);
	return(v.mul((float)dt));
    }
}
