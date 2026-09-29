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
import haven.render.*;
import static org.lwjgl.vulkan.VK10.*;

/* Maps Haven's vector formats onto Vulkan formats, and converts
 * texel data where Vulkan has no matching format (three-component
 * textures, differing external formats, swizzles). */
public class VkFormats {
    public static class TexFmt {
	public final int vk;
	/* Stored layout: components and number format of each texel. */
	public final int nc;
	public final NumberFormat cf;
	public final boolean depth, integer, srgb;

	TexFmt(int vk, int nc, NumberFormat cf, boolean depth, boolean srgb) {
	    this.vk = vk;
	    this.nc = nc;
	    this.cf = cf;
	    this.depth = depth;
	    this.integer = intfmt(cf);
	    this.srgb = srgb;
	}

	public int size() {return(nc * cf.size);}
	public VectorFormat vfmt() {return(new VectorFormat(nc, cf));}
	public String toString() {return(String.format("#<vk-texfmt %d %sx%d>", vk, cf, nc));}
    }

    public static boolean intfmt(NumberFormat cf) {
	switch(cf) {
	case UINT8: case SINT8: case UINT16: case SINT16: case UINT32: case SINT32:
	    return(true);
	default:
	    return(false);
	}
    }

    public static TexFmt texfmt(VectorFormat ifmt, boolean srgb) {
	if(ifmt.cf == NumberFormat.DEPTH)
	    return(new TexFmt(VK_FORMAT_D32_SFLOAT, 1, NumberFormat.FLOAT32, true, false));
	int nc = (ifmt.nc == 3) ? 4 : ifmt.nc;
	if(srgb) {
	    if((ifmt.cf == NumberFormat.UNORM8) && ((ifmt.nc == 3) || (ifmt.nc == 4)))
		return(new TexFmt(VK_FORMAT_R8G8B8A8_SRGB, 4, NumberFormat.UNORM8, false, true));
	    throw(new IllegalArgumentException("sRGB texture format: " + ifmt));
	}
	int vk = colorfmt(nc, ifmt.cf);
	if(vk == VK_FORMAT_UNDEFINED)
	    throw(new IllegalArgumentException("texture format: " + ifmt));
	return(new TexFmt(vk, nc, ifmt.cf, false, false));
    }

    public static int colorfmt(int nc, NumberFormat cf) {
	switch(nc) {
	case 1:
	    switch(cf) {
	    case UNORM8:  return(VK_FORMAT_R8_UNORM);
	    case SNORM8:  return(VK_FORMAT_R8_SNORM);
	    case UINT8:   return(VK_FORMAT_R8_UINT);
	    case SINT8:   return(VK_FORMAT_R8_SINT);
	    case UNORM16: return(VK_FORMAT_R16_UNORM);
	    case SNORM16: return(VK_FORMAT_R16_SNORM);
	    case UINT16:  return(VK_FORMAT_R16_UINT);
	    case SINT16:  return(VK_FORMAT_R16_SINT);
	    case FLOAT16: return(VK_FORMAT_R16_SFLOAT);
	    case UINT32:  return(VK_FORMAT_R32_UINT);
	    case SINT32:  return(VK_FORMAT_R32_SINT);
	    case FLOAT32: return(VK_FORMAT_R32_SFLOAT);
	    }
	    break;
	case 2:
	    switch(cf) {
	    case UNORM8:  return(VK_FORMAT_R8G8_UNORM);
	    case SNORM8:  return(VK_FORMAT_R8G8_SNORM);
	    case UINT8:   return(VK_FORMAT_R8G8_UINT);
	    case SINT8:   return(VK_FORMAT_R8G8_SINT);
	    case UNORM16: return(VK_FORMAT_R16G16_UNORM);
	    case SNORM16: return(VK_FORMAT_R16G16_SNORM);
	    case UINT16:  return(VK_FORMAT_R16G16_UINT);
	    case SINT16:  return(VK_FORMAT_R16G16_SINT);
	    case FLOAT16: return(VK_FORMAT_R16G16_SFLOAT);
	    case UINT32:  return(VK_FORMAT_R32G32_UINT);
	    case SINT32:  return(VK_FORMAT_R32G32_SINT);
	    case FLOAT32: return(VK_FORMAT_R32G32_SFLOAT);
	    }
	    break;
	case 4:
	    switch(cf) {
	    case UNORM8:  return(VK_FORMAT_R8G8B8A8_UNORM);
	    case SNORM8:  return(VK_FORMAT_R8G8B8A8_SNORM);
	    case UINT8:   return(VK_FORMAT_R8G8B8A8_UINT);
	    case SINT8:   return(VK_FORMAT_R8G8B8A8_SINT);
	    case UNORM16: return(VK_FORMAT_R16G16B16A16_UNORM);
	    case SNORM16: return(VK_FORMAT_R16G16B16A16_SNORM);
	    case UINT16:  return(VK_FORMAT_R16G16B16A16_UINT);
	    case SINT16:  return(VK_FORMAT_R16G16B16A16_SINT);
	    case FLOAT16: return(VK_FORMAT_R16G16B16A16_SFLOAT);
	    case UINT32:  return(VK_FORMAT_R32G32B32A32_UINT);
	    case SINT32:  return(VK_FORMAT_R32G32B32A32_SINT);
	    case FLOAT32: return(VK_FORMAT_R32G32B32A32_SFLOAT);
	    }
	    break;
	}
	return(VK_FORMAT_UNDEFINED);
    }

    /* Vertex attribute formats. Floating-point shader inputs read
     * normalized formats as normalized and plain integer formats as
     * scaled (like glVertexAttribPointer with normalized=false);
     * integer shader inputs read integer formats directly. */
    public static int vtxfmt(VectorFormat el, boolean intinput) {
	int nc = el.nc;
	NumberFormat cf = el.cf;
	if(intinput) {
	    switch(cf) {
	    case UINT8:  return(pick(nc, VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8_UINT, VK_FORMAT_R8G8B8A8_UINT));
	    case SINT8:  return(pick(nc, VK_FORMAT_R8_SINT, VK_FORMAT_R8G8_SINT, VK_FORMAT_R8G8B8_SINT, VK_FORMAT_R8G8B8A8_SINT));
	    case UINT16: return(pick(nc, VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16_UINT, VK_FORMAT_R16G16B16A16_UINT));
	    case SINT16: return(pick(nc, VK_FORMAT_R16_SINT, VK_FORMAT_R16G16_SINT, VK_FORMAT_R16G16B16_SINT, VK_FORMAT_R16G16B16A16_SINT));
	    case UINT32: return(pick(nc, VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT, VK_FORMAT_R32G32B32A32_UINT));
	    case SINT32: return(pick(nc, VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32_SINT, VK_FORMAT_R32G32B32A32_SINT));
	    }
	} else {
	    switch(cf) {
	    case UNORM8:  return(pick(nc, VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8_UNORM, VK_FORMAT_R8G8B8A8_UNORM));
	    case SNORM8:  return(pick(nc, VK_FORMAT_R8_SNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8B8_SNORM, VK_FORMAT_R8G8B8A8_SNORM));
	    case UINT8:   return(pick(nc, VK_FORMAT_R8_USCALED, VK_FORMAT_R8G8_USCALED, VK_FORMAT_R8G8B8_USCALED, VK_FORMAT_R8G8B8A8_USCALED));
	    case SINT8:   return(pick(nc, VK_FORMAT_R8_SSCALED, VK_FORMAT_R8G8_SSCALED, VK_FORMAT_R8G8B8_SSCALED, VK_FORMAT_R8G8B8A8_SSCALED));
	    case UNORM16: return(pick(nc, VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16B16_UNORM, VK_FORMAT_R16G16B16A16_UNORM));
	    case SNORM16: return(pick(nc, VK_FORMAT_R16_SNORM, VK_FORMAT_R16G16_SNORM, VK_FORMAT_R16G16B16_SNORM, VK_FORMAT_R16G16B16A16_SNORM));
	    case UINT16:  return(pick(nc, VK_FORMAT_R16_USCALED, VK_FORMAT_R16G16_USCALED, VK_FORMAT_R16G16B16_USCALED, VK_FORMAT_R16G16B16A16_USCALED));
	    case SINT16:  return(pick(nc, VK_FORMAT_R16_SSCALED, VK_FORMAT_R16G16_SSCALED, VK_FORMAT_R16G16B16_SSCALED, VK_FORMAT_R16G16B16A16_SSCALED));
	    case FLOAT16: return(pick(nc, VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R16G16B16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT));
	    case FLOAT32: return(pick(nc, VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT));
	    }
	}
	throw(new IllegalArgumentException(String.format("vertex attribute format %s for %s input", el, intinput ? "integer" : "float")));
    }

    private static int pick(int nc, int f1, int f2, int f3, int f4) {
	switch(nc) {
	case 1: return(f1);
	case 2: return(f2);
	case 3: return(f3);
	case 4: return(f4);
	default: throw(new IllegalArgumentException("vertex component count: " + nc));
	}
    }

    /* The four-component variant of a three-component 8- or 16-bit
     * vertex format, for devices that cannot fetch those. It reads
     * one extra component, which a vec3 input ignores. */
    public static int widevtx(int fmt) {
	switch(fmt) {
	case VK_FORMAT_R8G8B8_UNORM:     return(VK_FORMAT_R8G8B8A8_UNORM);
	case VK_FORMAT_R8G8B8_SNORM:     return(VK_FORMAT_R8G8B8A8_SNORM);
	case VK_FORMAT_R8G8B8_USCALED:   return(VK_FORMAT_R8G8B8A8_USCALED);
	case VK_FORMAT_R8G8B8_SSCALED:   return(VK_FORMAT_R8G8B8A8_SSCALED);
	case VK_FORMAT_R8G8B8_UINT:      return(VK_FORMAT_R8G8B8A8_UINT);
	case VK_FORMAT_R8G8B8_SINT:      return(VK_FORMAT_R8G8B8A8_SINT);
	case VK_FORMAT_R16G16B16_UNORM:  return(VK_FORMAT_R16G16B16A16_UNORM);
	case VK_FORMAT_R16G16B16_SNORM:  return(VK_FORMAT_R16G16B16A16_SNORM);
	case VK_FORMAT_R16G16B16_USCALED:return(VK_FORMAT_R16G16B16A16_USCALED);
	case VK_FORMAT_R16G16B16_SSCALED:return(VK_FORMAT_R16G16B16A16_SSCALED);
	case VK_FORMAT_R16G16B16_UINT:   return(VK_FORMAT_R16G16B16A16_UINT);
	case VK_FORMAT_R16G16B16_SINT:   return(VK_FORMAT_R16G16B16A16_SINT);
	case VK_FORMAT_R16G16B16_SFLOAT: return(VK_FORMAT_R16G16B16A16_SFLOAT);
	default: return(fmt);
	}
    }

    /* Texel conversion */

    public static float half2float(int h) {
	int s = (h >> 15) & 1, e = (h >> 10) & 0x1f, m = h & 0x3ff;
	float v;
	if(e == 0)
	    v = (float)(m * Math.pow(2, -24));
	else if(e == 31)
	    v = (m == 0) ? Float.POSITIVE_INFINITY : Float.NaN;
	else
	    v = (float)((1 + (m / 1024.0)) * Math.pow(2, e - 15));
	return((s != 0) ? -v : v);
    }

    public static int float2half(float f) {
	int bits = Float.floatToIntBits(f);
	int s = (bits >>> 16) & 0x8000;
	int e = ((bits >>> 23) & 0xff) - 127 + 15;
	int m = bits & 0x7fffff;
	if(((bits >>> 23) & 0xff) == 0xff)
	    return(s | 0x7c00 | ((m != 0) ? 0x200 : 0));
	if(e >= 31)
	    return(s | 0x7c00);
	if(e <= 0) {
	    if(e < -10)
		return(s);
	    m |= 0x800000;
	    int shift = 14 - e;
	    int hm = m >> shift;
	    if(((m >> (shift - 1)) & 1) != 0)
		hm++;
	    return(s | hm);
	}
	int hm = m >> 13;
	int ret = s | (e << 10) | hm;
	if((m & 0x1000) != 0)
	    ret++;
	return(ret);
    }

    private static double getc(ByteBuffer buf, int off, NumberFormat cf) {
	switch(cf) {
	case UNORM8:  return((buf.get(off) & 0xff) / 255.0);
	case SNORM8:  return(Math.max(buf.get(off) / 127.0, -1.0));
	case UNORM16: return((buf.getShort(off) & 0xffff) / 65535.0);
	case SNORM16: return(Math.max(buf.getShort(off) / 32767.0, -1.0));
	case UNORM32: return((buf.getInt(off) & 0xffffffffL) / 4294967295.0);
	case SNORM32: return(Math.max(buf.getInt(off) / 2147483647.0, -1.0));
	case FLOAT16: return(half2float(buf.getShort(off) & 0xffff));
	case FLOAT32: case DEPTH: return(buf.getFloat(off));
	case FLOAT64: return(buf.getDouble(off));
	case UINT8:   return(buf.get(off) & 0xff);
	case SINT8:   return(buf.get(off));
	case UINT16:  return(buf.getShort(off) & 0xffff);
	case SINT16:  return(buf.getShort(off));
	case UINT32:  return(buf.getInt(off) & 0xffffffffL);
	case SINT32:  return(buf.getInt(off));
	default: throw(new IllegalArgumentException(String.valueOf(cf)));
	}
    }

    private static long clamp(long v, long min, long max) {
	return(Math.max(min, Math.min(max, v)));
    }

    private static void putc(ByteBuffer buf, int off, NumberFormat cf, double v) {
	switch(cf) {
	case UNORM8:  buf.put(off, (byte)clamp(Math.round(v * 255.0), 0, 255)); break;
	case SNORM8:  buf.put(off, (byte)clamp(Math.round(v * 127.0), -127, 127)); break;
	case UNORM16: buf.putShort(off, (short)clamp(Math.round(v * 65535.0), 0, 65535)); break;
	case SNORM16: buf.putShort(off, (short)clamp(Math.round(v * 32767.0), -32767, 32767)); break;
	case UNORM32: buf.putInt(off, (int)clamp(Math.round(v * 4294967295.0), 0, 4294967295L)); break;
	case SNORM32: buf.putInt(off, (int)clamp(Math.round(v * 2147483647.0), -2147483647, 2147483647)); break;
	case FLOAT16: buf.putShort(off, (short)float2half((float)v)); break;
	case FLOAT32: case DEPTH: buf.putFloat(off, (float)v); break;
	case FLOAT64: buf.putDouble(off, v); break;
	case UINT8:   buf.put(off, (byte)clamp((long)v, 0, 255)); break;
	case SINT8:   buf.put(off, (byte)clamp((long)v, -128, 127)); break;
	case UINT16:  buf.putShort(off, (short)clamp((long)v, 0, 65535)); break;
	case SINT16:  buf.putShort(off, (short)clamp((long)v, -32768, 32767)); break;
	case UINT32:  buf.putInt(off, (int)clamp((long)v, 0, 4294967295L)); break;
	case SINT32:  buf.putInt(off, (int)clamp((long)v, Integer.MIN_VALUE, Integer.MAX_VALUE)); break;
	default: throw(new IllegalArgumentException(String.valueOf(cf)));
	}
    }

    /* Converts n texels of (snc x scf, with data component i going
     * to channel perm[i]) into (dnc x dcf). Missing channels are 0,
     * except alpha, which is one. Both buffers are addressed
     * absolutely from their positions. */
    public static void convert(ByteBuffer src, int snc, NumberFormat scf, Swizzle perm,
			       ByteBuffer dst, int dnc, NumberFormat dcf, int n) {
	src = src.duplicate().order(ByteOrder.nativeOrder());
	dst = dst.duplicate().order(ByteOrder.nativeOrder());
	int sb = src.position(), db = dst.position();
	int ssz = snc * scf.size, dsz = dnc * dcf.size;
	boolean id = (perm == null) || perm.idp();
	if(id && (snc == dnc) && (scf == dcf)) {
	    ByteBuffer s = src.duplicate();
	    s.limit(sb + (n * ssz));
	    dst.put(s);
	    return;
	}
	if((scf == NumberFormat.UNORM8) && (dcf == NumberFormat.UNORM8) && (dnc == 4) && ((snc == 3) || (snc == 4))) {
	    int[] map = {0, 1, 2, 3};
	    if(!id) {
		for(int i = 0; i < snc; i++)
		    map[perm.perm[i]] = i;
	    }
	    for(int i = 0, so = sb, dof = db; i < n; i++, so += ssz, dof += 4) {
		dst.put(dof + 0, src.get(so + map[0]));
		dst.put(dof + 1, src.get(so + map[1]));
		dst.put(dof + 2, src.get(so + map[2]));
		dst.put(dof + 3, (snc == 4) ? src.get(so + map[3]) : (byte)0xff);
	    }
	    return;
	}
	double[] ch = new double[4];
	for(int i = 0, so = sb, dof = db; i < n; i++, so += ssz, dof += dsz) {
	    ch[0] = ch[1] = ch[2] = 0;
	    ch[3] = 1;
	    for(int c = 0; c < snc; c++) {
		int t = id ? c : perm.perm[c];
		if(t < 4)
		    ch[t] = getc(src, so + (c * scf.size), scf);
	    }
	    for(int c = 0; c < dnc; c++)
		putc(dst, dof + (c * dcf.size), dcf, ch[c]);
	}
    }
}
