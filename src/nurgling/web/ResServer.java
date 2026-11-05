package nurgling.web;

import haven.Loading;
import haven.Resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves a resource's image layer as PNG: item icons, minimap icons, tileset textures, meter
 * icons. Headless mode stubs only the client's own textures (Resource.loadtex/loadsimg); image
 * layers are still decoded, so the real art is available here.
 */
public class ResServer {
    private static final int MAXCACHE = 3000;
    private final Map<String, byte[]> cache = new LinkedHashMap<String, byte[]>(256, 0.75f, true) {
	protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
	    return(size() > MAXCACHE);
	}
    };

    public WebServer.Response image(String name) throws IOException {
	if((name == null) || !name.matches("[A-Za-z0-9_/\\-.]+") || name.contains(".."))
	    return(WebServer.Response.text(400, "bad name"));
	byte[] png;
	synchronized(cache) {
	    png = cache.get(name);
	}
	if(png == null) {
	    Resource res;
	    try {
		res = Resource.remote().loadwait(name);
	    } catch(Loading | Resource.LoadException | Resource.BadResourceException e) {
		return(null);
	    }
	    Resource.Image img = res.layer(Resource.imgc);
	    if((img == null) || (img.img == null))
		return(null);
	    BufferedImage bi = img.img;
	    ByteArrayOutputStream buf = new ByteArrayOutputStream();
	    ImageIO.write(bi, "png", buf);
	    png = buf.toByteArray();
	    synchronized(cache) {
		cache.put(name, png);
	    }
	}
	return(new WebServer.Response(200, "image/png", png).header("Cache-Control", "max-age=86400"));
    }
}
