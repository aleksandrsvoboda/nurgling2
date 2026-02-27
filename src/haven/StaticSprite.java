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
import haven.render.*;

@Resource.PublishedCode.Builtin(type = Sprite.Factory.class, name = "static")
public class StaticSprite extends Sprite {
    public final RenderTree.Node[] parts;
    
    public static final Factory fact = new Factory() {
	    public Sprite create(Owner owner, Resource res, Message sdt) {
		if((res.layer(FastMesh.MeshRes.class) != null) ||
		   (res.layer(RenderLink.Res.class) != null))
		    return(new StaticSprite(owner, res, sdt) {
			    public String toString() {
				return(String.format("#<static-sprite %s>", res.name));
			    }
			});
		return(null);
	    }
	};
    
    public StaticSprite(Owner owner, Resource res, RenderTree.Node[] parts) {
	super(owner, res);
	this.parts = parts;
    }

    public StaticSprite(Owner owner, Resource res, RenderTree.Node part) {
	this(owner, res, new RenderTree.Node[] {part});
    }
    
    public StaticSprite(Owner owner, Resource res, Message sdt) {
	super(owner, res);
	System.out.println("[StaticSprite.<init>] Created StaticSprite for resource: " + res.name);
	this.parts = lsparts(new RecOwner(), res, sdt);
    }
    
    public static RenderTree.Node[] lsparts(Owner owner, Resource res, Message sdt) {
	int fl = sdt.eom() ? 0xffff0000 : decnum(sdt);
	Collection<RenderTree.Node> rl = new LinkedList<>();

	for(FastMesh.MeshRes mr : res.layers(FastMesh.MeshRes.class)) {
	    boolean shouldRender = (mr.mat != null) && ((mr.id < 0) || (((1 << mr.id) & fl) != 0));

	    if(shouldRender) {
		// Check if this is barrel/dframe - these need custom materials
		// NOTE: ttubs use message flags, not overlays, so they don't need customMask
		boolean needsCustomMat = (res.name.contains("gfx/terobjs/barrel") ||
		                         res.name.contains("gfx/terobjs/dframe"));

		boolean hasCustomMask = (owner instanceof RecOwner
				&& ((RecOwner)(owner)).owner() instanceof ResDrawable && ((ResDrawable)((RecOwner)(owner)).owner()).gob!=null
				&& ((ResDrawable)((RecOwner)(owner)).owner()).gob.ngob!=null
				&& ((ResDrawable)((RecOwner)(owner)).owner()).gob.ngob.customMask);

		int maskValue = fl;
		if (needsCustomMat && owner instanceof RecOwner && ((RecOwner)(owner)).owner() instanceof ResDrawable) {
		    ResDrawable rd = (ResDrawable)((RecOwner)(owner)).owner();
		    if (rd.gob != null && rd.gob.ngob != null) {
			// Force customMask for barrel/dframe/ttub
			rd.gob.ngob.customMask = true;
			maskValue = rd.gob.ngob.mask();
			hasCustomMask = true;
		    }
		} else if (hasCustomMask) {
		    maskValue = ((ResDrawable)((RecOwner)(owner)).owner()).gob.ngob.mask();
		}

		rl.add(mr.mat.get(maskValue).apply(mr.m));
	    }
	}
	for(RenderLink.Res lr : res.layers(RenderLink.Res.class)) {
	    if((lr.id < 0) || (((1 << lr.id) & fl) != 0))
		rl.add(lr.l.make(owner));
	}
	if(res.layer(Audio.clip, "amb") != null)
	    rl.add(new ActAudio.Ambience(res));
	return(rl.toArray(new RenderTree.Node[0]));
    }

    public static RenderTree.Node[] lsparts(Resource res, Message sdt) {
	return(lsparts(null, res, sdt));
    }

    public void added(RenderTree.Slot slot) {
	nurgling.tools.CustomizeStaticSprite.added(this, slot);
	for(RenderTree.Node p : parts)
	    slot.add(p);
    }
}
