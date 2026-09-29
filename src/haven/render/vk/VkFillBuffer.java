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

package haven.render.vk;

import java.nio.*;
import haven.*;
import haven.render.*;

public class VkFillBuffer implements FillBuffer {
    private final int sz;
    private ByteBuffer data = null;
    private boolean pushed = false;

    public VkFillBuffer(int sz) {
	this.sz = sz;
    }

    public int size() {return(sz);}
    public boolean compatible(Environment env) {return(env instanceof VkEnvironment);}

    public ByteBuffer push() {
	if(data == null) {
	    data = Utils.mkbbuf(sz);
	    pushed = true;
	} else if(!pushed) {
	    throw(new IllegalStateException("already pulled"));
	}
	return(data);
    }

    public void pull(ByteBuffer buf) {
	if(data != null)
	    throw(new IllegalStateException("already " + (pushed ? "pushed" : "pulled")));
	data = buf.slice().order(ByteOrder.nativeOrder());
	if(data.remaining() > sz)
	    data.limit(sz);
    }

    /* The filled data, positioned at its start. */
    public ByteBuffer data() {
	if(data == null)
	    push();
	ByteBuffer ret = data.duplicate().order(ByteOrder.nativeOrder());
	ret.position(0);
	ret.limit(Math.min(sz, ret.capacity()));
	return(ret);
    }

    public static ByteBuffer data(FillBuffer buf) {
	if(buf == null)
	    return(null);
	if(!(buf instanceof VkFillBuffer))
	    throw(new IllegalArgumentException("fill buffer from another environment: " + buf));
	return(((VkFillBuffer)buf).data());
    }

    public void dispose() {
	data = null;
    }
}
