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

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.render.sl.Struct;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/*
 * A shader program built from shader macros. The DSL emits GLSL
 * 1.40 with loose uniforms; this rewrites it into Vulkan GLSL:
 * non-opaque uniforms go into one std140 block (binding 0) with
 * explicit member offsets, samplers get their own bindings, and
 * attributes, varyings and fragment outputs get explicit locations.
 * All bindings live in a single push-descriptor set.
 *
 * Clip space stays GL's: the vertex shader remaps z from [-1, 1] to
 * [0, 1], and y is not flipped, so images end up with exactly the
 * memory layout OpenGL gives them. Only presentation flips.
 */
public class VkProgram extends VkObject {
    public static boolean dumpall = false;
    public final String vsrc, fsrc;
    public final Uniform[] uniforms;
    public final int[][] umap;
    public final boolean[] fmap;
    public final FragData[] fragdata;
    public final Attribute[] attribs;
    public final int[] attrloc;
    /* Per uniform: UBO offset, or -1 for samplers. */
    public final int[] uoff;
    /* Per uniform: descriptor binding for samplers, or -1. */
    public final int[] ubind;
    public final int ubosize;
    /* Sampler uniforms, in binding order, and their descriptor counts. */
    public final int[] samplers;
    public final int[] scount;
    public final AtomicInteger locked = new AtomicInteger(0);
    final long vmod, fmod, dsl, layout;
    private final Map<PipeKey, PipeKey> pipes = new HashMap<>();
    private final Map<VertexArray.Layout, VertexKey> vkeys = new HashMap<>();
    boolean used = true;

    /* std140 layout */

    private static final Map<Type, int[]> layouts = new java.util.concurrent.ConcurrentHashMap<>();
    static int[] std140(Type type) {
	int[] ret = layouts.get(type);
	if(ret == null)
	    layouts.put(type, ret = std140a(type));
	return(ret);
    }

    private static int[] std140a(Type type) {
	if((type == Type.FLOAT) || (type == Type.INT) || (type == Type.UINT))
	    return(new int[] {4, 4});
	if((type == Type.VEC2) || (type == Type.IVEC2) || (type == Type.UVEC2))
	    return(new int[] {8, 8});
	if((type == Type.VEC3) || (type == Type.IVEC3) || (type == Type.UVEC3))
	    return(new int[] {12, 16});
	if((type == Type.VEC4) || (type == Type.IVEC4) || (type == Type.UVEC4))
	    return(new int[] {16, 16});
	if(type == Type.MAT3)
	    return(new int[] {48, 16});
	if(type == Type.MAT4)
	    return(new int[] {64, 16});
	if(type instanceof Array) {
	    Array ary = (Array)type;
	    if(ary.sz <= 0)
		throw(new RuntimeException("unsized uniform array: " + type));
	    return(new int[] {arystride(ary) * ary.sz, 16});
	}
	if(type instanceof Struct) {
	    int off = 0;
	    for(Struct.Field f : ((Struct)type).fields) {
		int[] fl = std140(f.type);
		off = align(off, fl[1]) + fl[0];
	    }
	    return(new int[] {align(off, 16), 16});
	}
	throw(new RuntimeException("no std140 layout for uniform type " + type));
    }

    static int arystride(Array ary) {
	int[] el = std140(ary.el);
	return(align(el[0], 16));
    }

    static int align(int off, int al) {
	return((off + al - 1) & ~(al - 1));
    }

    static boolean samplerp(Type type) {
	if(type instanceof Array)
	    return(samplerp(((Array)type).el));
	return(type instanceof Type.Sampler);
    }

    static int samplercount(Type type) {
	if(type instanceof Array)
	    return(Math.max(((Array)type).sz, 1) * samplercount(((Array)type).el));
	return(1);
    }

    static boolean intattr(Type type) {
	return((type == Type.INT) || (type == Type.IVEC2) || (type == Type.IVEC3) || (type == Type.IVEC4) ||
	       (type == Type.UINT) || (type == Type.UVEC2) || (type == Type.UVEC3) || (type == Type.UVEC4));
    }

    static int attrsize(Attribute attr) {
	if(attr.type == Type.MAT3)
	    return(3);
	if(attr.type == Type.MAT4)
	    return(4);
	return(1);
    }

    /* Uniform packing */

    public static class NoMappingException extends RuntimeException {
	public NoMappingException(Object name, Type type, Object val) {
	    super(String.format("no uniform packing for %s -> %s: %s", (val == null) ? null : val.getClass(), type, name));
	}
    }

    private static void putfv(ByteBuffer buf, int off, float[] v, int n) {
	for(int i = 0; i < n; i++)
	    buf.putFloat(off + (i * 4), v[i]);
    }

    /* name is only used for error messages; it is not stringified
     * unless packing fails. */
    static void pack(ByteBuffer buf, int off, Type type, Object val, Object name) {
	if(val == null)
	    return;
	if(type == Type.FLOAT) {
	    if(val instanceof Number) {buf.putFloat(off, ((Number)val).floatValue()); return;}
	} else if((type == Type.INT) || (type == Type.UINT)) {
	    if(val instanceof Number) {buf.putInt(off, ((Number)val).intValue()); return;}
	    if(val instanceof Boolean) {buf.putInt(off, ((Boolean)val) ? 1 : 0); return;}
	} else if((type == Type.IVEC2) || (type == Type.IVEC3) || (type == Type.IVEC4) ||
		  (type == Type.UVEC2) || (type == Type.UVEC3) || (type == Type.UVEC4)) {
	    int n = ((type == Type.IVEC2) || (type == Type.UVEC2)) ? 2 : ((type == Type.IVEC3) || (type == Type.UVEC3)) ? 3 : 4;
	    if(val instanceof int[]) {
		int[] v = (int[])val;
		for(int i = 0; i < n; i++)
		    buf.putInt(off + (i * 4), v[i]);
		return;
	    }
	    if((val instanceof Coord) && (n == 2)) {
		buf.putInt(off, ((Coord)val).x);
		buf.putInt(off + 4, ((Coord)val).y);
		return;
	    }
	} else if(type == Type.VEC2) {
	    if(val instanceof float[]) {putfv(buf, off, (float[])val, 2); return;}
	    if(val instanceof Coord) {buf.putFloat(off, ((Coord)val).x); buf.putFloat(off + 4, ((Coord)val).y); return;}
	    if(val instanceof Coord3f) {buf.putFloat(off, ((Coord3f)val).x); buf.putFloat(off + 4, ((Coord3f)val).y); return;}
	} else if((type == Type.VEC3) || (type == Type.VEC4)) {
	    int n = (type == Type.VEC3) ? 3 : 4;
	    if(val instanceof float[]) {putfv(buf, off, (float[])val, n); return;}
	    if(val instanceof Coord3f) {
		Coord3f c = (Coord3f)val;
		buf.putFloat(off, c.x); buf.putFloat(off + 4, c.y); buf.putFloat(off + 8, c.z);
		if(n == 4) buf.putFloat(off + 12, 1);
		return;
	    }
	    if(val instanceof FColor) {
		FColor c = (FColor)val;
		buf.putFloat(off, c.r); buf.putFloat(off + 4, c.g); buf.putFloat(off + 8, c.b);
		if(n == 4) buf.putFloat(off + 12, c.a);
		return;
	    }
	    if(val instanceof java.awt.Color) {
		java.awt.Color c = (java.awt.Color)val;
		buf.putFloat(off, c.getRed() / 255f); buf.putFloat(off + 4, c.getGreen() / 255f); buf.putFloat(off + 8, c.getBlue() / 255f);
		if(n == 4) buf.putFloat(off + 12, c.getAlpha() / 255f);
		return;
	    }
	} else if(type == Type.MAT3) {
	    float[] m = null;
	    if(val instanceof float[]) m = (float[])val;
	    else if(val instanceof Matrix4f) m = ((Matrix4f)val).trim3();
	    if(m != null) {
		for(int c = 0; c < 3; c++) {
		    for(int r = 0; r < 3; r++)
			buf.putFloat(off + (c * 16) + (r * 4), m[(c * 3) + r]);
		}
		return;
	    }
	} else if(type == Type.MAT4) {
	    float[] m = null;
	    if(val instanceof float[]) m = (float[])val;
	    else if(val instanceof Matrix4f) m = ((Matrix4f)val).m;
	    if(m != null) {putfv(buf, off, m, 16); return;}
	} else if(type instanceof Array) {
	    Array ary = (Array)type;
	    int stride = arystride(ary);
	    if(val instanceof Object[]) {
		Object[] v = (Object[])val;
		for(int i = 0; (i < ary.sz) && (i < v.length); i++)
		    pack(buf, off + (i * stride), ary.el, v[i], name);
		return;
	    }
	    if(val instanceof float[]) {
		float[] v = (float[])val;
		int n = std140(ary.el)[0] / 4;
		if((ary.el == Type.MAT3) && (v.length >= ary.sz * 9)) {
		    for(int i = 0; i < ary.sz; i++)
			pack(buf, off + (i * stride), ary.el, Arrays.copyOfRange(v, i * 9, (i + 1) * 9), name);
		    return;
		}
		for(int i = 0; (i < ary.sz) && (((i + 1) * n) <= v.length); i++)
		    putfv(buf, off + (i * stride), Arrays.copyOfRange(v, i * n, (i + 1) * n), n);
		return;
	    }
	    if(val instanceof int[]) {
		int[] v = (int[])val;
		for(int i = 0; (i < ary.sz) && (i < v.length); i++)
		    buf.putInt(off + (i * stride), v[i]);
		return;
	    }
	} else if(type instanceof Struct) {
	    if(val instanceof Object[]) {
		Object[] v = (Object[])val;
		int foff = 0, i = 0;
		for(Struct.Field f : ((Struct)type).fields) {
		    int[] fl = std140(f.type);
		    foff = align(foff, fl[1]);
		    if(i < v.length)
			pack(buf, off + foff, f.type, v[i], name);
		    foff += fl[0];
		    i++;
		}
		return;
	    }
	}
	throw(new NoMappingException(name, type, val));
    }

    /* Packs the values of all non-opaque uniforms. */
    public void pack(ByteBuffer buf, int base, Object[] vals) {
	for(int i = 0; i < uniforms.length; i++) {
	    if(uoff[i] >= 0)
		pack(buf, base + uoff[i], uniforms[i].type, vals[i], uniforms[i]);
	}
    }

    /* Shader translation */

    private static final Pattern unidecl = Pattern.compile("^uniform (.+) ([A-Za-z_][A-Za-z0-9_]*);$");
    private static final Pattern iodecl = Pattern.compile("^((?:flat |noperspective |centroid )*)(in|out) (.+) ([A-Za-z_][A-Za-z0-9_]*);$");
    private static final Pattern arysuf = Pattern.compile("\\[(\\d+)\\]");

    private static int locsize(String type) {
	int n = 1;
	String base = type;
	int b = type.indexOf('[');
	if(b >= 0) {
	    base = type.substring(0, b);
	    Matcher m = arysuf.matcher(type);
	    while(m.find())
		n *= Integer.parseInt(m.group(1));
	}
	if(base.equals("mat4") || base.equals("dmat4"))
	    n *= 4;
	else if(base.equals("mat3"))
	    n *= 3;
	else if(base.equals("mat2"))
	    n *= 2;
	return(n);
    }

    private static class Translation {
	final String src;
	final List<String> unknown = new ArrayList<>();
	Translation(String src) {this.src = src;}
    }

    private String translate(String src, boolean vertex, Map<String, Integer> unames, Map<String, Integer> anames,
			     Map<String, Integer> varyings, int[] nextvar, Map<String, Integer> fnames) {
	StringBuilder out = new StringBuilder();
	List<int[]> members = new ArrayList<>();
	List<String> mdecl = new ArrayList<>();
	boolean blockpos = false;
	for(String line : src.split("\n", -1)) {
	    if(line.startsWith("#version ")) {
		out.append("#version 450\n");
		continue;
	    }
	    if(line.startsWith("#extension GL_ARB_texture_multisample"))
		continue;
	    Matcher m;
	    if((m = unidecl.matcher(line)).matches()) {
		String type = m.group(1), name = m.group(2);
		Integer ui = unames.get(name);
		if(ui == null)
		    throw(new RuntimeException("unknown uniform in generated shader: " + line));
		if(ubind[ui] >= 0) {
		    out.append(String.format("layout(set = 0, binding = %d) uniform %s %s;\n", ubind[ui], type, name));
		} else {
		    members.add(new int[] {uoff[ui], mdecl.size()});
		    mdecl.add(String.format("    layout(offset = %d) %s %s;\n", uoff[ui], type, name));
		    if(!blockpos) {
			out.append("%%HV_UBLOCK%%\n");
			blockpos = true;
		    }
		}
		continue;
	    }
	    if((m = iodecl.matcher(line)).matches()) {
		String qual = m.group(1), dir = m.group(2), type = m.group(3), name = m.group(4);
		int loc;
		if(vertex && dir.equals("in")) {
		    Integer ai = anames.get(name);
		    if(ai == null)
			throw(new RuntimeException("unknown attribute in generated shader: " + line));
		    loc = attrloc[ai];
		} else if(vertex && dir.equals("out")) {
		    loc = nextvar[0];
		    nextvar[0] += locsize(type);
		    varyings.put(name, loc);
		} else if(!vertex && dir.equals("in")) {
		    Integer vl = varyings.get(name);
		    if(vl == null) {
			vl = nextvar[0];
			nextvar[0] += locsize(type);
		    }
		    loc = vl;
		} else {
		    Integer fi = fnames.get(name);
		    if(fi == null)
			throw(new RuntimeException("unknown fragment output in generated shader: " + line));
		    loc = fi;
		}
		out.append(String.format("layout(location = %d) %s%s %s %s;\n", loc, qual, dir, type, name));
		continue;
	    }
	    out.append(line).append('\n');
	}
	String ret = out.toString();
	if(blockpos) {
	    members.sort((a, b) -> a[0] - b[0]);
	    StringBuilder blk = new StringBuilder();
	    blk.append("layout(std140, set = 0, binding = 0) uniform hv_ublock {\n");
	    for(int[] mem : members)
		blk.append(mdecl.get(mem[1]));
	    blk.append("};");
	    ret = ret.replace("%%HV_UBLOCK%%", blk.toString());
	}
	ret = ret.replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex");
	ret = ret.replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex");
	ret = ret.replaceAll("\\bgl_PointCoord\\b", "vec2(gl_PointCoord.x, 1.0 - gl_PointCoord.y)");
	if(vertex) {
	    boolean ptsz = ret.contains("gl_PointSize");
	    ret = ret.replaceAll("(?m)^void main\\(\\)", "void hv_main()");
	    ret = ret + "\nvoid main()\n{\n    hv_main();\n" +
		(ptsz ? "" : "    gl_PointSize = 1.0;\n") +
		"    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
	}
	return(ret);
    }

    private static String symname(Context ctx, Symbol sym) {
	if(sym instanceof Symbol.Fix)
	    return(((Symbol.Fix)sym).name);
	return(ctx.symtab.get(sym));
    }

    private VkProgram(VkEnvironment env, ProgramContext ctx, String rvsrc, String rfsrc) {
	super(env);

	/* Uniforms, in a deterministic order so that sources stay
	 * stable for the SPIR-V cache. */
	{
	    Uniform[] uniforms = ctx.uniforms.toArray(new Uniform[0]);
	    Map<Uniform, String> un = new IdentityHashMap<>();
	    for(Uniform u : uniforms) {
		String nm = ctx.symtab.get(u.name);
		if(nm == null) nm = symname(ctx.vctx, u.name);
		if(nm == null) nm = symname(ctx.fctx, u.name);
		un.put(u, (nm == null) ? "" : nm);
	    }
	    Arrays.sort(uniforms, (a, b) -> un.get(a).compareTo(un.get(b)));
	    this.uniforms = uniforms;
	    int[][] umap = new int[0][];
	    for(int i = 0; i < uniforms.length; i++) {
		for(State.Slot<?> slot : uniforms[i].deps) {
		    if(umap.length <= slot.id)
			umap = Arrays.copyOf(umap, slot.id + 1);
		    umap[slot.id] = (umap[slot.id] == null) ? new int[1] : Arrays.copyOf(umap[slot.id], umap[slot.id].length + 1);
		    umap[slot.id][umap[slot.id].length - 1] = i;
		}
	    }
	    this.umap = umap;
	    int[] uoff = new int[uniforms.length], ubind = new int[uniforms.length];
	    List<Integer> samplers = new ArrayList<>();
	    int off = 0, bind = 1;
	    for(int i = 0; i < uniforms.length; i++) {
		if(samplerp(uniforms[i].type)) {
		    uoff[i] = -1;
		    ubind[i] = bind++;
		    samplers.add(i);
		} else {
		    int[] l = std140(uniforms[i].type);
		    off = align(off, l[1]);
		    uoff[i] = off;
		    ubind[i] = -1;
		    off += l[0];
		}
	    }
	    this.uoff = uoff;
	    this.ubind = ubind;
	    this.ubosize = align(off, 16);
	    this.samplers = new int[samplers.size()];
	    this.scount = new int[samplers.size()];
	    for(int i = 0; i < this.samplers.length; i++) {
		this.samplers[i] = samplers.get(i);
		this.scount[i] = samplercount(uniforms[this.samplers[i]].type);
	    }
	}
	{
	    FragData[] fragdata = ctx.fragdata.toArray(new FragData[0]);
	    boolean[] fmap = new boolean[DepthBuffer.slot.id + 1];
	    fmap[DepthBuffer.slot.id] = true;
	    for(FragData fd : fragdata) {
		for(State.Slot<?> slot : fd.deps) {
		    if(fmap.length <= slot.id)
			fmap = Arrays.copyOf(fmap, slot.id + 1);
		    fmap[slot.id] = true;
		}
	    }
	    this.fragdata = fragdata;
	    this.fmap = fmap;
	}
	{
	    Attribute[] attribs = ctx.attribs.toArray(new Attribute[0]);
	    Arrays.sort(attribs, (a, b) -> {
		    if(a.primary && !b.primary)
			return(-1);
		    if(!a.primary && b.primary)
			return(1);
		    return(Utils.idcmp.compare(a, b));
		});
	    this.attribs = attribs;
	    this.attrloc = new int[attribs.length];
	    for(int i = 0, loc = 0; i < attribs.length; i++) {
		attrloc[i] = loc;
		loc += attrsize(attribs[i]);
	    }
	}

	Map<String, Integer> vunames = new HashMap<>(), funames = new HashMap<>(), anames = new HashMap<>(), fnames = new HashMap<>();
	for(int i = 0; i < uniforms.length; i++) {
	    String vn = symname(ctx.vctx, uniforms[i].name), fn = symname(ctx.fctx, uniforms[i].name);
	    if(vn != null) vunames.put(vn, i);
	    if(fn != null) funames.put(fn, i);
	}
	for(int i = 0; i < attribs.length; i++) {
	    String an = symname(ctx.vctx, attribs[i].name);
	    if(an != null) anames.put(an, i);
	}
	for(int i = 0; i < fragdata.length; i++) {
	    String fn = symname(ctx.fctx, fragdata[i].name);
	    if(fn != null) fnames.put(fn, i);
	}
	Map<String, Integer> varyings = new HashMap<>();
	int[] nextvar = {0};
	this.vsrc = translate(rvsrc, true, vunames, anames, varyings, nextvar, fnames);
	this.fsrc = translate(rfsrc, false, funames, anames, varyings, nextvar, fnames);
	if(dumpall || ctx.dump) {
	    System.err.println("---> Vulkan vertex shader:");
	    System.err.print(vsrc);
	    System.err.println("---> Vulkan fragment shader:");
	    System.err.print(fsrc);
	}

	byte[] vspv, fspv;
	try {
	    vspv = env.compiler.compile(VkShaderCompiler.VERTEX, vsrc);
	    fspv = env.compiler.compile(VkShaderCompiler.FRAGMENT, fsrc);
	} catch(VkShaderCompiler.CompileException e) {
	    System.err.println("Vulkan shader compilation failed. Original GLSL:");
	    System.err.println("---> Vertex:\n" + rvsrc);
	    System.err.println("---> Fragment:\n" + rfsrc);
	    System.err.println("---> Translated:\n" + e.source);
	    throw(e);
	}
	VkDevice dev = env.dev;
	try(MemoryStack st = stackPush()) {
	    this.vmod = mkmodule(st, vspv);
	    this.fmod = mkmodule(st, fspv);
	    int nb = ((ubosize > 0) ? 1 : 0) + samplers.length;
	    VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(nb, st);
	    int b = 0;
	    if(ubosize > 0) {
		binds.get(b++).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
		    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
	    }
	    for(int i = 0; i < samplers.length; i++) {
		binds.get(b++).binding(ubind[samplers[i]]).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(scount[i])
		    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
	    }
	    VkDescriptorSetLayoutCreateInfo dci = VkDescriptorSetLayoutCreateInfo.calloc(st).sType$Default()
		.flags(org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
		.pBindings(binds);
	    LongBuffer lp = st.mallocLong(1);
	    VkEnvironment.check(vkCreateDescriptorSetLayout(dev, dci, null, lp), "vkCreateDescriptorSetLayout");
	    this.dsl = lp.get(0);
	    VkPipelineLayoutCreateInfo pci = VkPipelineLayoutCreateInfo.calloc(st).sType$Default()
		.pSetLayouts(st.longs(dsl));
	    VkEnvironment.check(vkCreatePipelineLayout(dev, pci, null, lp), "vkCreatePipelineLayout");
	    this.layout = lp.get(0);
	}
    }

    private long mkmodule(MemoryStack st, byte[] spv) {
	ByteBuffer code = MemoryUtil.memAlloc(spv.length);
	try {
	    code.put(spv).flip();
	    VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(st).sType$Default().pCode(code);
	    LongBuffer lp = st.mallocLong(1);
	    VkEnvironment.check(vkCreateShaderModule(env.dev, ci, null, lp), "vkCreateShaderModule");
	    return(lp.get(0));
	} finally {
	    MemoryUtil.memFree(code);
	}
    }

    /* Programs are built on several threads, but generating their
     * GLSL is not thread-safe: shared function bodies (Function.Def
     * statics such as Svaj.svaja) keep per-program expansions, like
     * PostProc.AutoMacro.exp, in their own fields. So the macros and
     * the source text are made one program at a time; only the
     * compiling to SPIR-V runs in parallel. */
    private static final Object srclock = new Object();

    public static VkProgram build(VkEnvironment env, Collection<ShaderMacro> mods) {
	ProgramContext prog = new ProgramContext();
	String[] src;
	synchronized(srclock) {
	    for(ShaderMacro mod : mods)
		mod.modify(prog);
	    src = source(prog);
	}
	return(new VkProgram(env, prog, src[0], src[1]));
    }

    private static String[] source(ProgramContext ctx) {
	StringWriter fbuf = new StringWriter(), vbuf = new StringWriter();
	ctx.fctx.construct(fbuf);
	ctx.vctx.construct(vbuf);
	return(new String[] {vbuf.toString(), fbuf.toString()});
    }

    public int uniform(Uniform var) {
	for(int i = 0; i < uniforms.length; i++) {
	    if(uniforms[i] == var)
		return(i);
	}
	return(-1);
    }

    public int fragidx(FragData fd) {
	for(int i = 0; i < fragdata.length; i++) {
	    if(fragdata[i] == fd)
		return(i);
	}
	return(-1);
    }

    /* Vertex input */

    public static class VertexKey {
	final int nbind;
	/* Per binding: source model buffer (-1 for the default-value
	 * buffer), stride and whether instanced. */
	final int[] bsrc, bstride;
	final boolean[] binst;
	/* Per location. */
	final int[] aloc, abind, afmt, aoff;

	VertexKey(int[] bsrc, int[] bstride, boolean[] binst, int[] aloc, int[] abind, int[] afmt, int[] aoff) {
	    this.nbind = bsrc.length;
	    this.bsrc = bsrc; this.bstride = bstride; this.binst = binst;
	    this.aloc = aloc; this.abind = abind; this.afmt = afmt; this.aoff = aoff;
	}
    }

    public VertexKey vkey(VertexArray.Layout fmt) {
	synchronized(vkeys) {
	    VertexKey ret = vkeys.get(fmt);
	    if(ret == null)
		vkeys.put(fmt, ret = mkvkey(fmt));
	    return(ret);
	}
    }

    private VertexKey mkvkey(VertexArray.Layout fmt) {
	List<int[]> binds = new ArrayList<>();
	List<int[]> attrs = new ArrayList<>();
	for(int ai = 0; ai < attribs.length; ai++) {
	    Attribute attr = attribs[ai];
	    VertexArray.Layout.Input in = null;
	    for(VertexArray.Layout.Input i : fmt.inputs) {
		if(i.tgt == attr) {
		    in = i;
		    break;
		}
	    }
	    boolean iattr = intattr(attr.type);
	    int na = attrsize(attr);
	    if(in == null) {
		/* OpenGL reads (0, 0, 0, 1) from a disabled attribute
		 * array; emulate that with a stride-0 buffer. */
		int b = bindfor(binds, -1, 0, false);
		for(int v = 0; v < na; v++)
		    attrs.add(new int[] {attrloc[ai] + v, b, iattr ? VK_FORMAT_R32G32B32A32_SINT : VK_FORMAT_R32G32B32A32_SFLOAT, 0});
		continue;
	    }
	    int stride = (in.stride != 0) ? in.stride : in.el.size();
	    int b = bindfor(binds, in.buf, stride, in.instanced);
	    if(attr.type == Type.MAT4) {
		if((in.el.nc != 16) || (in.el.cf != NumberFormat.FLOAT32))
		    throw(new RuntimeException("unexpected mat4 vertex format: " + in.el));
		for(int v = 0; v < 4; v++)
		    attrs.add(new int[] {attrloc[ai] + v, b, VK_FORMAT_R32G32B32A32_SFLOAT, in.offset + (v * 16)});
	    } else if(attr.type == Type.MAT3) {
		if((in.el.nc != 9) || (in.el.cf != NumberFormat.FLOAT32))
		    throw(new RuntimeException("unexpected mat3 vertex format: " + in.el));
		for(int v = 0; v < 3; v++)
		    attrs.add(new int[] {attrloc[ai] + v, b, VK_FORMAT_R32G32B32_SFLOAT, in.offset + (v * 12)});
	    } else {
		int vf = VkFormats.vtxfmt(in.el, iattr);
		if(!env.vtxsupported(vf)) {
		    int wf = VkFormats.widevtx(vf);
		    if((wf == vf) || !env.vtxsupported(wf))
			throw(new RuntimeException("vertex format not supported by device: " + in.el));
		    vf = wf;
		}
		attrs.add(new int[] {attrloc[ai], b, vf, in.offset});
	    }
	}
	int n = binds.size();
	int[] bsrc = new int[n], bstride = new int[n];
	boolean[] binst = new boolean[n];
	for(int i = 0; i < n; i++) {
	    bsrc[i] = binds.get(i)[0];
	    bstride[i] = binds.get(i)[1];
	    binst[i] = binds.get(i)[2] != 0;
	}
	int na = attrs.size();
	int[] aloc = new int[na], abind = new int[na], afmt = new int[na], aoff = new int[na];
	for(int i = 0; i < na; i++) {
	    int[] a = attrs.get(i);
	    aloc[i] = a[0]; abind[i] = a[1]; afmt[i] = a[2]; aoff[i] = a[3];
	}
	return(new VertexKey(bsrc, bstride, binst, aloc, abind, afmt, aoff));
    }

    private static int bindfor(List<int[]> binds, int buf, int stride, boolean inst) {
	for(int i = 0; i < binds.size(); i++) {
	    int[] b = binds.get(i);
	    if((b[0] == buf) && (b[1] == stride) && ((b[2] != 0) == inst))
		return(i);
	}
	binds.add(new int[] {buf, stride, inst ? 1 : 0});
	return(binds.size() - 1);
    }

    /* Pipelines */

    public static class PipeKey {
	final VkProgram prog;
	final VertexKey vk;
	final int topo;
	final int[] cfmt;
	final int dfmt;
	final BlendMode[] blend;
	final int[] cmask;
	private final int hash;
	volatile long pipe = 0;
	volatile Throwable failure;
	final java.util.concurrent.atomic.AtomicBoolean building = new java.util.concurrent.atomic.AtomicBoolean();

	PipeKey(VkProgram prog, VertexKey vk, int topo, int[] cfmt, int dfmt, BlendMode[] blend, int[] cmask) {
	    this.prog = prog;
	    this.vk = vk;
	    this.topo = topo;
	    this.cfmt = cfmt;
	    this.dfmt = dfmt;
	    this.blend = blend;
	    this.cmask = cmask;
	    int h = System.identityHashCode(vk);
	    h = (h * 31) + topo;
	    h = (h * 31) + Arrays.hashCode(cfmt);
	    h = (h * 31) + dfmt;
	    h = (h * 31) + Arrays.hashCode(blend);
	    h = (h * 31) + Arrays.hashCode(cmask);
	    this.hash = h;
	}

	public int hashCode() {return(hash);}

	public boolean equals(Object o) {
	    if(!(o instanceof PipeKey))
		return(false);
	    PipeKey that = (PipeKey)o;
	    return((this.prog == that.prog) && (this.vk == that.vk) && (this.topo == that.topo) && (this.dfmt == that.dfmt) &&
		   Arrays.equals(this.cfmt, that.cfmt) && Arrays.equals(this.blend, that.blend) && Arrays.equals(this.cmask, that.cmask));
	}
    }

    public PipeKey pipekey(VertexKey vk, int topo, int[] cfmt, int dfmt, BlendMode[] blend, int[] cmask) {
	PipeKey key = new PipeKey(this, vk, topo, cfmt, dfmt, blend, cmask);
	synchronized(pipes) {
	    PipeKey ret = pipes.get(key);
	    if(ret == null)
		pipes.put(key, ret = key);
	    return(ret);
	}
    }

    static int vkblendop(BlendMode.Function fn) {
	switch(fn) {
	case ADD:  return(VK_BLEND_OP_ADD);
	case SUB:  return(VK_BLEND_OP_SUBTRACT);
	case RSUB: return(VK_BLEND_OP_REVERSE_SUBTRACT);
	case MIN:  return(VK_BLEND_OP_MIN);
	case MAX:  return(VK_BLEND_OP_MAX);
	default: throw(new IllegalArgumentException(String.valueOf(fn)));
	}
    }

    static int vkblendfac(BlendMode.Factor fac) {
	switch(fac) {
	case ZERO:            return(VK_BLEND_FACTOR_ZERO);
	case ONE:             return(VK_BLEND_FACTOR_ONE);
	case SRC_COLOR:       return(VK_BLEND_FACTOR_SRC_COLOR);
	case DST_COLOR:       return(VK_BLEND_FACTOR_DST_COLOR);
	case INV_SRC_COLOR:   return(VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR);
	case INV_DST_COLOR:   return(VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR);
	case SRC_ALPHA:       return(VK_BLEND_FACTOR_SRC_ALPHA);
	case DST_ALPHA:       return(VK_BLEND_FACTOR_DST_ALPHA);
	case INV_SRC_ALPHA:   return(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
	case INV_DST_ALPHA:   return(VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA);
	case CONST_COLOR:     return(VK_BLEND_FACTOR_CONSTANT_COLOR);
	case INV_CONST_COLOR: return(VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR);
	case CONST_ALPHA:     return(VK_BLEND_FACTOR_CONSTANT_ALPHA);
	case INV_CONST_ALPHA: return(VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA);
	default: throw(new IllegalArgumentException(String.valueOf(fac)));
	}
    }

    private static final int[] dynstates = {
	VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_LINE_WIDTH,
	VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_BLEND_CONSTANTS,
	VK_DYNAMIC_STATE_CULL_MODE, VK_DYNAMIC_STATE_FRONT_FACE,
	VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE, VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE,
	VK_DYNAMIC_STATE_DEPTH_COMPARE_OP, VK_DYNAMIC_STATE_DEPTH_BIAS_ENABLE,
    };

    /* Whether key's pipeline is made; if not, it is started on the compiler
     * threads. Draw lists and optional immediate draws retry when ready. */
    boolean pipeready(PipeKey key) {
	if(key.failure != null)
	    throw(new RuntimeException("Asynchronous Vulkan pipeline creation failed", key.failure));
	if(key.pipe != 0)
	    return(true);
	/* Not synchronized on key: pipeline() holds that for the whole
	 * build, and the frame must not wait for it. */
	if(key.building.compareAndSet(false, true)) {
	    /* Held while queued and building, so that the cleanup of
	     * unused programs does not destroy its shader modules and
	     * layout under the driver. */
	    lock();
	    try {
		env.builders.submit(() -> {
		    try {
			synchronized(buildlock) {
			    if(!disposed())
				pipeline(key);
			}
		    } catch(RuntimeException | Error failure) {
			key.failure = failure;
		    } finally {
			unlock();
		    }
		});
	    } catch(RuntimeException failure) {
		key.failure = failure;
		unlock();
		throw(failure);
	    }
	}
	return(false);
    }

    long pipeline(PipeKey key) {
	if(key.pipe != 0)
	    return(key.pipe);
	synchronized(key) {
	    if(key.pipe != 0)
		return(key.pipe);
	    return(mkpipeline(key));
	}
    }

    private long mkpipeline(PipeKey key) {
	VertexKey vk = key.vk;
	try(MemoryStack st = stackPush()) {
	    VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, st);
	    stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vmod).pName(st.UTF8("main"));
	    stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fmod).pName(st.UTF8("main"));

	    VkVertexInputBindingDescription.Buffer vbinds = VkVertexInputBindingDescription.calloc(vk.nbind, st);
	    for(int i = 0; i < vk.nbind; i++)
		vbinds.get(i).binding(i).stride(vk.bstride[i]).inputRate(vk.binst[i] ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
	    VkVertexInputAttributeDescription.Buffer vattrs = VkVertexInputAttributeDescription.calloc(vk.aloc.length, st);
	    for(int i = 0; i < vk.aloc.length; i++)
		vattrs.get(i).location(vk.aloc[i]).binding(vk.abind[i]).format(vk.afmt[i]).offset(vk.aoff[i]);
	    VkPipelineVertexInputStateCreateInfo vis = VkPipelineVertexInputStateCreateInfo.calloc(st).sType$Default()
		.pVertexBindingDescriptions(vbinds).pVertexAttributeDescriptions(vattrs);

	    VkPipelineInputAssemblyStateCreateInfo ias = VkPipelineInputAssemblyStateCreateInfo.calloc(st).sType$Default()
		.topology(key.topo).primitiveRestartEnable(false);
	    VkPipelineViewportStateCreateInfo vps = VkPipelineViewportStateCreateInfo.calloc(st).sType$Default()
		.viewportCount(1).scissorCount(1);
	    VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(st).sType$Default()
		.polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VkEnvironment.FRONT_FACE)
		.depthBiasEnable(false).lineWidth(1.0f);
	    VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(st).sType$Default()
		.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
	    VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(st).sType$Default()
		.depthTestEnable(false).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_LESS).stencilTestEnable(false)
		.minDepthBounds(0).maxDepthBounds(1);
	    int nc = key.cfmt.length;
	    VkPipelineColorBlendAttachmentState.Buffer cbs = VkPipelineColorBlendAttachmentState.calloc(nc, st);
	    for(int i = 0; i < nc; i++) {
		VkPipelineColorBlendAttachmentState a = cbs.get(i);
		a.colorWriteMask(key.cmask[i]);
		BlendMode bm = key.blend[i];
		if(bm != null) {
		    a.blendEnable(true)
			.colorBlendOp(vkblendop(bm.cfn)).srcColorBlendFactor(vkblendfac(bm.csrc)).dstColorBlendFactor(vkblendfac(bm.cdst))
			.alphaBlendOp(vkblendop(bm.afn)).srcAlphaBlendFactor(vkblendfac(bm.asrc)).dstAlphaBlendFactor(vkblendfac(bm.adst));
		} else {
		    a.blendEnable(false);
		}
	    }
	    VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(st).sType$Default()
		.logicOpEnable(false).pAttachments(cbs);
	    VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(st).sType$Default()
		.pDynamicStates(st.ints(dynstates));
	    VkPipelineRenderingCreateInfo rci = VkPipelineRenderingCreateInfo.calloc(st).sType$Default()
		.colorAttachmentCount(nc).pColorAttachmentFormats(st.ints(key.cfmt))
		.depthAttachmentFormat(key.dfmt).stencilAttachmentFormat(VK_FORMAT_UNDEFINED);
	    VkGraphicsPipelineCreateInfo.Buffer pci = VkGraphicsPipelineCreateInfo.calloc(1, st);
	    pci.get(0).sType$Default().pNext(rci.address())
		.pStages(stages).pVertexInputState(vis).pInputAssemblyState(ias).pViewportState(vps)
		.pRasterizationState(rs).pMultisampleState(ms).pDepthStencilState(ds).pColorBlendState(cb)
		.pDynamicState(dyn).layout(layout);
	    LongBuffer lp = st.mallocLong(1);
	    VkEnvironment.check(vkCreateGraphicsPipelines(env.dev, env.pipecache, pci, null, lp), "vkCreateGraphicsPipelines");
	    env.pipecachewriter.changed();
	    key.pipe = lp.get(0);
	    env.npipes.incrementAndGet();
	}
	return(key.pipe);
    }

    public void lock() {locked.incrementAndGet();}
    public void unlock() {locked.decrementAndGet();}

    /* Taken by a pipeline being built in the background and by
     * destruction, so that the one never runs into the other. */
    private final Object buildlock = new Object();

    protected void destroy() {
	synchronized(buildlock) {
	    synchronized(pipes) {
		for(PipeKey key : pipes.values()) {
		    if(key.pipe != 0) {
			vkDestroyPipeline(env.dev, key.pipe, null);
			key.pipe = 0;
			env.npipes.decrementAndGet();
		    }
		}
	    }
	    vkDestroyPipelineLayout(env.dev, layout, null);
	    vkDestroyDescriptorSetLayout(env.dev, dsl, null);
	    vkDestroyShaderModule(env.dev, vmod, null);
	    vkDestroyShaderModule(env.dev, fmod, null);
	}
    }

    public String toString() {
	return(String.format("#<vk-program %d uniforms, %d samplers, %d outputs>", uniforms.length, samplers.length, fragdata.length));
    }
}
