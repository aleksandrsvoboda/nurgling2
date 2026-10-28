package haven.render;

import haven.*;

/*
 * nurgling: a render target whose memory another process on the same
 * GPU can use as a texture (the frame bridge: the client renders into
 * these, the presenter samples them, and no pixel ever leaves the
 * GPU). The exporting side allocates the memory under name; the
 * importing side opens it by that name. The Vulkan backend shares the
 * memory when the device can (VkEnvironment.extmem); elsewhere this is
 * an ordinary texture.
 */
public class SharedTexture2D extends Texture2D {
    public final String name;
    public final boolean export;

    public SharedTexture2D(Coord sz, String name, boolean export) {
	super(sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
	this.name = name;
	this.export = export;
    }
}
