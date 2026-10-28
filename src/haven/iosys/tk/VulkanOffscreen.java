package haven.iosys.tk;

import haven.*;
import haven.iosys.*;
import haven.render.*;
import haven.render.vk.*;

/*
 * nurgling: Vulkan without a window, for rendering into textures only
 * (the frame bridge's game process, see BridgeToolkit). The device has
 * no surface and never builds a swapchain.
 */
@Acephal.Available(name = "vulkan")
public class VulkanOffscreen implements Acephal {
    public final VkEnvironment env;
    private final Thread rthread;
    private volatile boolean stopped = false;

    private static class NoSurface implements VkEnvironment.Surface {
	public String[] extensions() {return(new String[0]);}
	public long create(org.lwjgl.vulkan.VkInstance inst) {return(0);}
	public Coord size() {return(Coord.of(1, 1));}
    }

    public VulkanOffscreen() {
	try {
	    env = new VkEnvironment(new NoSurface(), Area.sized(Coord.of(1, 1)));
	} catch(Environment.UnavailableException e) {
	    throw(new Unavailable("could not initialize offscreen Vulkan", e));
	}
	rthread = new HackThread(() -> {
		try {
		    while(!stopped) {
			if(env.submitwait(100))
			    env.process();
		    }
		} catch(InterruptedException e) {
		}
	    }, "Vulkan render thread");
	rthread.setDaemon(true);
	rthread.start();
    }

    public Environment env() {
	return(env);
    }

    public void dispose() {
	stopped = true;
	rthread.interrupt();
    }

    private static Providers.Factory<VulkanOffscreen> factory = new Providers.Factory<VulkanOffscreen>() {
	private VulkanOffscreen instance = null;

	public VulkanOffscreen open(String... args) {
	    synchronized(this) {
		if(instance == null)
		    instance = new VulkanOffscreen();
	    }
	    return(instance);
	}

	/* Only on request; JOGLOffscreen stays the default headless renderer. */
	public int priority() {return(-20);}
    };
    public static Providers.Factory<VulkanOffscreen> get() {
	return(factory);
    }
}
