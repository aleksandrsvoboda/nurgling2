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

import java.util.*;
import java.util.function.*;
import haven.render.*;

public class ResDrawable extends Drawable implements Sprite.Owner, EquipTarget {
    public final Indir<Resource> res;
    public final Resource rres;
    public final Sprite spr;
    public MessageBuf sdt;

    public ResDrawable(Gob gob, Indir<Resource> res, Message sdt, boolean old) {
	super(gob);
	this.res = res;
	this.sdt = new MessageBuf(sdt);
	this.rres = res.get();
	spr = Sprite.create(this, rres, this.sdt.clone());
	if(old || true)
	    spr.age();
    }

    public ResDrawable(Gob gob, Indir<Resource> res, Message sdt) {
	this(gob, res, sdt, false);
    }

	public static String getTexResName(Gob owner, MessageBuf src)
	{
		MessageBuf msg = src.clone();
		byte[] b;
		if ((b = msg.bytes()).length >= 2)
		{
			return owner.context(Resource.Resolver.class).getres((new MessageBuf(b)).clone().uint16()).get().name;
		}
		else return null;
	}

    public ResDrawable(Gob gob, Resource res) {
	this(gob, res.indir(), MessageBuf.nil);
    }

    public void ctick(double dt) {
	spr.tick(dt);
    }

    public void gtick(Render g) {
	spr.gtick(g);
    }

    public void added(RenderTree.Slot slot) {
	slot.add(spr);
	super.added(slot);
    }

    public void dispose() {
	if(spr != null)
	    spr.dispose();
    }

    /* This is only deprecated becuase Sprite.Owner.getres is. Its
     * override from Drawable is not considered deprecated. */
    @Deprecated
    public Resource getres() {
	return(rres);
    }

    public Gob.Placer placer() {
	if(spr instanceof Gob.Placing) {
	    Gob.Placer ret = ((Gob.Placing)spr).placer();
	    if(ret != null)
		return(ret);
	}
	return(super.placer());
    }

    private static final ClassResolver<ResDrawable> ctxr = new ClassResolver<ResDrawable>()
	.add(ResDrawable.class, d -> d);
    public <T> T context(Class<T> cl) {return(OwnerContext.orparent(cl, ctxr.context(cl, this, false), gob));}
    public Random mkrandoom() {return(gob.mkrandoom());}

    public Supplier<? extends Pipe.Op> eqpoint(String nm, Message dat) {
	if(spr instanceof EquipTarget) {
	    Supplier<? extends Pipe.Op> ret = ((EquipTarget)spr).eqpoint(nm, dat);
	    if(ret != null)
		return(ret);
	}
	Skeleton.BoneOffset bo = rres.layer(Skeleton.BoneOffset.class, nm);
	if(bo != null)
	    return(bo.from(null));
	return(null);
    }

    @OCache.DeltaType(OCache.OD_RES)
    public static class $cres implements OCache.Delta {
	public void apply(Gob g, OCache.AttrDelta msg) {
	    int resid = msg.uint16();
	    MessageBuf sdt = MessageBuf.nil;
	    if((resid & 0x8000) != 0) {
		resid &= ~0x8000;
		sdt = new MessageBuf(msg.bytes(msg.uint8()));
	    }
	    Indir<Resource> res = OCache.Delta.getres(g, resid);
	    Drawable dr = g.getattr(Drawable.class);
	    ResDrawable d = (dr instanceof ResDrawable)?(ResDrawable)dr:null;
	    if((d != null) && (d.res == res) && !d.sdt.equals(sdt) && (d.spr != null) && (d.spr instanceof Sprite.CUpd)) {
		((Sprite.CUpd)d.spr).update(sdt);
		d.sdt = sdt;
		g.ngob.checkattr(d,g.id,null);
	    } else if((d == null) || (d.res != res) || !d.sdt.equals(sdt)) {
		g.setattr(new ResDrawable(g, res, sdt, msg.old));
	    }
	}
    }

	public long calcMarker()
	{
		if (sdt.rbuf.length >= 4) {
			long res = (sdt.rbuf[3] << 24);
			res += (sdt.rbuf[2] << 16);
			res += ((sdt.rbuf[1] << 8) + sdt.rbuf[0]);
			return res;
		}
		if (sdt.rbuf.length == 3) {
			return ((sdt.rbuf[2] << 16) + (sdt.rbuf[1] << 8) + sdt.rbuf[0]);
		}
		if (sdt.rbuf.length == 2) {
			return ((sdt.rbuf[1] << 8) + sdt.rbuf[0]);
		} else if (sdt.rbuf.length - 1 < 0) {
			return -1;
		}
		return sdt.rbuf[0];
	}
}
