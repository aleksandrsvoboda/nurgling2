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

import java.lang.ref.*;
import java.nio.*;
import java.util.*;
import haven.*;
import haven.render.*;
import org.lwjgl.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.vma.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.util.vma.Vma.*;

/*
 * A Vulkan image. Textures created without data (render targets)
 * and depth textures live in GENERAL layout for their whole life, so
 * rendering to and sampling from them needs no transitions. Uploaded
 * textures are SHADER_READ_ONLY until they are first rendered to.
 */
public class VkTexture extends VkObject {
    public final VkFormats.TexFmt fmt;
    public final int viewtype;
    public final int w, h, d, layers, levels;
    public final long image, alloc, view;
    final boolean rgb;
    private final long[] attviews;
    private final WeakReference<Texture> desc;
    /* Render thread only */
    int layout = VK_IMAGE_LAYOUT_UNDEFINED;
    int steady;

    VkTexture(VkEnvironment env, VkFormats.TexFmt fmt, int viewtype, int w, int h, int d, int layers, int levels,
	      boolean general, Texture desc) {
	super(env);
	this.fmt = fmt;
	this.viewtype = viewtype;
	this.w = Math.max(w, 1);
	this.h = Math.max(h, 1);
	this.d = Math.max(d, 1);
	this.layers = layers;
	this.levels = Math.max(levels, 1);
	this.desc = (desc == null) ? null : new WeakReference<>(desc);
	this.rgb = (desc != null) && (desc.ifmt.nc == 3) && (fmt.nc == 4);
	this.steady = (general || fmt.depth) ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
	this.attviews = new long[this.levels];
	int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
	if((viewtype == VK_IMAGE_VIEW_TYPE_2D) && env.attachable(fmt.vk, fmt.depth))
	    usage |= fmt.depth ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
	try(MemoryStack st = stackPush()) {
	    VkImageCreateInfo ici = VkImageCreateInfo.calloc(st).sType$Default()
		.imageType((viewtype == VK_IMAGE_VIEW_TYPE_3D) ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D)
		.format(fmt.vk).mipLevels(this.levels).arrayLayers(layers)
		.samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
		.usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
	    ici.extent().set(this.w, this.h, this.d);
	    if(viewtype == VK_IMAGE_VIEW_TYPE_CUBE)
		ici.flags(VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT);
	    VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(st).usage(VMA_MEMORY_USAGE_AUTO);
	    LongBuffer ip = st.mallocLong(1);
	    PointerBuffer ap = st.mallocPointer(1);
	    VkEnvironment.check(vmaCreateImage(env.vma, ici, aci, ip, ap, null), "vmaCreateImage");
	    this.image = ip.get(0);
	    this.alloc = ap.get(0);
	    this.view = mkview(st, viewtype, 0, this.levels, true);
	}
    }

    int aspect() {
	return(fmt.depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT);
    }

    private long mkview(MemoryStack st, int type, int level, int nlevels, boolean sampling) {
	VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(st).sType$Default()
	    .image(image).viewType(type).format(fmt.vk);
	if(sampling && fmt.depth)
	    vci.components().set(VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_ZERO, VK_COMPONENT_SWIZZLE_ZERO, VK_COMPONENT_SWIZZLE_ONE);
	else if(sampling && rgb)
	    /* RGB textures sample with alpha one in OpenGL, even after a
	     * vec3 fragment output has left the padded fourth channel undefined. */
	    vci.components().a(VK_COMPONENT_SWIZZLE_ONE);
	vci.subresourceRange().set(aspect(), level, nlevels, 0, (type == VK_IMAGE_VIEW_TYPE_2D) ? 1 : layers);
	LongBuffer lp = st.mallocLong(1);
	VkEnvironment.check(vkCreateImageView(env.dev, vci, null, lp), "vkCreateImageView");
	return(lp.get(0));
    }

    /* A single-level view for use as a render target. */
    long attview(int level) {
	synchronized(attviews) {
	    if(attviews[level] == 0) {
		if((level == 0) && (levels == 1) && (viewtype == VK_IMAGE_VIEW_TYPE_2D) && !rgb && !fmt.depth) {
		    attviews[level] = view;
		} else {
		    try(MemoryStack st = stackPush()) {
			/* Attachment views must have identity component mappings. */
			attviews[level] = mkview(st, VK_IMAGE_VIEW_TYPE_2D, level, 1, false);
		    }
		}
	    }
	    return(attviews[level]);
	}
    }

    void attachable() {
	if(viewtype != VK_IMAGE_VIEW_TYPE_2D)
	    throw(new IllegalArgumentException("only 2D textures can be rendered to"));
    }

    public Coord sz(int level) {
	return(Coord.of(Math.max(w >> level, 1), Math.max(h >> level, 1)));
    }

    protected void destroy() {
	for(int i = 0; i < attviews.length; i++) {
	    if((attviews[i] != 0) && (attviews[i] != view))
		vkDestroyImageView(env.dev, attviews[i], null);
	}
	vkDestroyImageView(env.dev, view, null);
	vmaDestroyImage(env.vma, image, alloc);
    }

    public Texture desc() {
	return((desc == null) ? null : desc.get());
    }

    public String toString() {
	return(String.format("#<vk-tex %s %dx%dx%d:%d %s>", fmt, w, h, d, layers, desc()));
    }

    /* Converts data given in a texture's external format into the
     * stored format. */
    static ByteBuffer texels(Texture tex, VkFormats.TexFmt fmt, FillBuffer fill, int ntexels) {
	if(fill == null)
	    return(null);
	ByteBuffer src = VkFillBuffer.data(fill);
	VectorFormat efmt = tex.efmt;
	if(fmt.depth) {
	    ByteBuffer ret = Utils.mkbbuf(ntexels * 4);
	    VkFormats.convert(src, 1, (efmt.cf == NumberFormat.DEPTH) ? NumberFormat.FLOAT32 : efmt.cf, null, ret, 1, NumberFormat.FLOAT32, ntexels);
	    return(ret);
	}
	if((efmt.nc == fmt.nc) && (efmt.cf == fmt.cf) && ((tex.eperm == null) || tex.eperm.idp()))
	    return(src);
	ByteBuffer ret = Utils.mkbbuf(ntexels * fmt.size());
	VkFormats.convert(src, efmt.nc, efmt.cf, tex.eperm, ret, fmt.nc, fmt.cf, ntexels);
	return(ret);
    }

    private static class Upload {
	final int level, layer, w, h, d;
	final ByteBuffer data;
	final FillBuffer fill;

	Upload(int level, int layer, int w, int h, int d, ByteBuffer data, FillBuffer fill) {
	    this.level = level; this.layer = layer;
	    this.w = w; this.h = h; this.d = d;
	    this.data = data;
	    this.fill = fill;
	}
    }

    static VkTexture create(VkEnvironment env, Texture tex) {
	if(tex instanceof Texture2DMS)
	    throw(new IllegalArgumentException("multisample textures are not supported by the Vulkan renderer"));
	VkFormats.TexFmt fmt = VkFormats.texfmt(tex.ifmt, tex.srgb);
	int viewtype, w, h, d = 1, layers = 1;
	if(tex instanceof Texture2D) {
	    viewtype = VK_IMAGE_VIEW_TYPE_2D;
	    w = ((Texture2D)tex).w; h = ((Texture2D)tex).h;
	} else if(tex instanceof Texture3D) {
	    viewtype = VK_IMAGE_VIEW_TYPE_3D;
	    w = ((Texture3D)tex).w; h = ((Texture3D)tex).h; d = ((Texture3D)tex).d;
	} else if(tex instanceof Texture2DArray) {
	    viewtype = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
	    w = ((Texture2DArray)tex).w; h = ((Texture2DArray)tex).h; layers = ((Texture2DArray)tex).n;
	} else if(tex instanceof TextureCube) {
	    viewtype = VK_IMAGE_VIEW_TYPE_CUBE;
	    w = ((TextureCube)tex).w; h = ((TextureCube)tex).h; layers = 6;
	} else {
	    throw(new IllegalArgumentException("unsupported texture type: " + tex));
	}
	List<Upload> ups = new ArrayList<>();
	int levels = 1;
	if(tex.init != null) {
	    Map<Integer, Integer> perlevel = new HashMap<>();
	    for(Texture.Image<?> img : tex.images()) {
		int layer = 0;
		if(img instanceof TextureArray.ArrayImage)
		    layer = ((TextureArray.ArrayImage<?>)img).layer;
		else if(img instanceof TextureCube.CubeImage)
		    layer = ((TextureCube.CubeImage)img).face.ordinal();
		@SuppressWarnings({"unchecked", "rawtypes"})
		FillBuffer fill = ((DataBuffer.Filler)tex.init).fill(img, env);
		if(fill == null)
		    continue;
		ByteBuffer data = texels(tex, fmt, fill, img.w * img.h * img.d);
		ups.add(new Upload(img.level, layer, img.w, img.h, img.d, data, fill));
		perlevel.merge(img.level, 1, Integer::sum);
	    }
	    tex.init.done();
	    /* Only consecutive, completely filled levels exist. */
	    levels = 0;
	    while(perlevel.getOrDefault(levels, 0) >= layers)
		levels++;
	    if(levels == 0)
		levels = 1;
	}
	boolean general = ups.isEmpty();
	if(general && !fmt.depth && (viewtype == VK_IMAGE_VIEW_TYPE_2D) && !env.attachable(fmt.vk, false)) {
	    /* Probably a render target in a format this device cannot
	     * render to (such as RGBA8_SNORM); use a wider one. */
	    for(NumberFormat alt : new NumberFormat[] {NumberFormat.FLOAT16, NumberFormat.FLOAT32}) {
		int vk = VkFormats.colorfmt(fmt.nc, alt);
		if((vk != VK_FORMAT_UNDEFINED) && env.attachable(vk, false)) {
		    fmt = new VkFormats.TexFmt(vk, fmt.nc, alt, false, false);
		    break;
		}
	    }
	}
	VkTexture ret = new VkTexture(env, fmt, viewtype, w, h, d, layers, levels, general, tex);
	int nlev = ret.levels;
	env.prep(ex -> {
		ex.inittex(ret);
		for(Upload up : ups) {
		    if(up.level < nlev)
			ex.uploadtex(ret, up.level, up.layer, up.w, up.h, up.d, up.data);
		    up.fill.dispose();
		}
	    });
	return(ret);
    }
}
