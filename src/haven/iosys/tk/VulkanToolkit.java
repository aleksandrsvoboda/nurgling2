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

package haven.iosys.tk;

import java.nio.*;
import java.util.*;
import haven.*;
import haven.iosys.*;
import haven.render.*;
import haven.render.vk.*;
import org.lwjgl.system.*;
import org.lwjgl.system.jawt.*;
import org.lwjgl.vulkan.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.jawt.JAWTFunctions.*;

/*
 * An AWT window whose canvas is rendered with Vulkan. Input handling
 * is AWTToolkit's, exactly as with the OpenGL toolkits. Never picked
 * automatically; it is used when selected (haven.toolkit=vulkan, or
 * the renderer option).
 */
@Toolkit.Available(name = "vulkan")
public class VulkanToolkit extends AWTToolkit {
    private VulkanToolkit() {
	Platform plat;
	try {
	    plat = Platform.get();
	    Class.forName("org.lwjgl.vulkan.VK10");
	    Class.forName("org.lwjgl.util.shaderc.Shaderc");
	    Class.forName("org.lwjgl.util.vma.Vma");
	} catch(ClassNotFoundException | LinkageError e) {
	    throw(new Unavailable("LWJGL Vulkan libraries not available", e));
	}
	if((plat != Platform.WINDOWS) && (plat != Platform.LINUX))
	    throw(new Unavailable("the Vulkan renderer is not supported on " + plat));
	try {
	    VkEnvironment.probe();
	} catch(Environment.UnavailableException e) {
	    throw(new Unavailable(e.getMessage(), e));
	} catch(LinkageError e) {
	    throw(new Unavailable("Vulkan libraries could not be loaded", e));
	} catch(RuntimeException | OutOfMemoryError e) {
	    /* A failure in the probe itself (a driver quirk, a bug here)
	     * must not keep the client from starting: fall back to
	     * OpenGL instead. */
	    throw(new Unavailable("Vulkan probe failed: " + e, e));
	}
    }

    private static Providers.Factory<VulkanToolkit> factory = new Providers.Factory<VulkanToolkit>() {
	private VulkanToolkit instance = null;

	public VulkanToolkit open(String... args) {
	    synchronized(this) {
		if(instance == null)
		    instance = new VulkanToolkit();
	    }
	    return(instance);
	}

	public int priority() {return(-20);}
	public boolean autouse() {return(false);}
    };
    public static Providers.Factory<VulkanToolkit> get() {
	return(factory);
    }

    /* A Vulkan surface for a heavyweight AWT component, via JAWT. */
    public static class AWTSurface implements VkEnvironment.Surface {
	public final java.awt.Component comp;

	public AWTSurface(java.awt.Component comp) {
	    this.comp = comp;
	}

	public String[] extensions() {
	    if(Platform.get() == Platform.WINDOWS)
		return(new String[] {KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME});
	    return(new String[] {KHRXlibSurface.VK_KHR_XLIB_SURFACE_EXTENSION_NAME});
	}

	public long create(VkInstance inst) {
	    JAWT awt = JAWT.calloc();
	    try {
		awt.version(JAWT_VERSION_1_4);
		if(!JAWT_GetAWT(awt))
		    throw(new Environment.UnavailableException("JAWT is not available"));
		JAWTDrawingSurface ds = JAWT_GetDrawingSurface(comp, awt.GetDrawingSurface());
		if(ds == null)
		    throw(new Environment.UnavailableException("could not get the AWT drawing surface"));
		try {
		    int lock = JAWT_DrawingSurface_Lock(ds, ds.Lock());
		    if((lock & JAWT_LOCK_ERROR) != 0)
			throw(new Environment.UnavailableException("could not lock the AWT drawing surface"));
		    try {
			JAWTDrawingSurfaceInfo dsi = JAWT_DrawingSurface_GetDrawingSurfaceInfo(ds, ds.GetDrawingSurfaceInfo());
			if(dsi == null)
			    throw(new Environment.UnavailableException("could not get the AWT drawing surface info"));
			try(MemoryStack st = stackPush()) {
			    LongBuffer lp = st.mallocLong(1);
			    int rv;
			    if(Platform.get() == Platform.WINDOWS) {
				JAWTWin32DrawingSurfaceInfo wi = JAWTWin32DrawingSurfaceInfo.create(dsi.platformInfo());
				VkWin32SurfaceCreateInfoKHR ci = VkWin32SurfaceCreateInfoKHR.calloc(st).sType$Default()
				    .hinstance(org.lwjgl.system.windows.WinBase.GetModuleHandle(null, (ByteBuffer)null))
				    .hwnd(wi.hwnd());
				rv = KHRWin32Surface.vkCreateWin32SurfaceKHR(inst, ci, null, lp);
			    } else {
				JAWTX11DrawingSurfaceInfo xi = JAWTX11DrawingSurfaceInfo.create(dsi.platformInfo());
				VkXlibSurfaceCreateInfoKHR ci = VkXlibSurfaceCreateInfoKHR.calloc(st).sType$Default()
				    .dpy(xi.display()).window(xi.drawable());
				rv = KHRXlibSurface.vkCreateXlibSurfaceKHR(inst, ci, null, lp);
			    }
			    if(rv != VK10.VK_SUCCESS)
				throw(new Environment.UnavailableException("could not create a Vulkan surface for the window (" + rv + ")"));
			    return(lp.get(0));
			} finally {
			    JAWT_DrawingSurface_FreeDrawingSurfaceInfo(dsi, ds.FreeDrawingSurfaceInfo());
			}
		    } finally {
			JAWT_DrawingSurface_Unlock(ds, ds.Unlock());
		    }
		} finally {
		    JAWT_FreeDrawingSurface(ds, awt.FreeDrawingSurface());
		}
	    } finally {
		awt.free();
	    }
	}

	public Coord size() {
	    return(Coord.of(comp.getWidth(), comp.getHeight()));
	}
    }

    public class VkPanel extends java.awt.Canvas {
	volatile VkEnvironment env;
	private Thread rthread;
	private volatile boolean stopped = false;

	public VkPanel() {
	    setFocusTraversalKeysEnabled(false);
	    setIgnoreRepaint(true);
	    setBackground(java.awt.Color.BLACK);
	}

	public void paint(java.awt.Graphics g) {}
	public void update(java.awt.Graphics g) {}

	public void addNotify() {
	    super.addNotify();
	    VkEnvironment env = this.env;
	    if(env != null)
		env.surfaceback();
	    synchronized(this) {
		notifyAll();
	    }
	}

	public void removeNotify() {
	    VkEnvironment env = this.env;
	    if(env != null)
		env.surfacegone();
	    super.removeNotify();
	}

	/* Called on the AWT thread. */
	void mkenv() {
	    if(env != null)
		return;
	    if(!isDisplayable())
		throw(new RuntimeException("Vulkan canvas is not displayable yet"));
	    VkEnvironment env = new VkEnvironment(new AWTSurface(this), Area.sized(Coord.of(getWidth(), getHeight())));
	    this.env = env;
	    rthread = new HackThread(() -> {
		    boolean confirmed = false;
		    try {
			while(!stopped) {
			    if(env.submitwait(100))
				env.process();
			    if(!confirmed && env.presented()) {
				RendererPref.confirm();
				confirmed = true;
			    }
			}
		    } catch(InterruptedException e) {
		    }
		}, "Vulkan render thread");
	    rthread.setDaemon(true);
	    rthread.start();
	}

	void stop() {
	    stopped = true;
	    Thread th = rthread;
	    if(th != null) {
		th.interrupt();
		try {
		    th.join(5000);
		} catch(InterruptedException e) {
		    Thread.currentThread().interrupt();
		}
	    }
	}
    }

    private static final Pipe.Op vkfb = Pipe.Op.compose(new FragColor<>(FragColor.defcolor),
							 new DepthBuffer<>(DepthBuffer.defdepth));
    public class VkWindow extends AWTWindow {
	public final VkPanel panel;
	private final EventQueue dsp;

	public VkWindow() {
	    panel = new VkPanel();
	    frame.add(panel);
	    frame.pack();
	    panel.requestFocus();
	    (dsp = new EventQueue()).register();
	}

	protected VkPanel panel() {return(panel);}

	public Environment env() {
	    if(panel.env == null) {
		try {
		    double st = Utils.rtime(), now = st;
		    synchronized(panel) {
			while(!panel.isDisplayable() && ((now - st) < 5)) {
			    panel.wait((int)Math.max(1, Math.round(1000 * (5 - (now - st)))));
			    now = Utils.rtime();
			}
		    }
		} catch(InterruptedException e) {
		    Thread.currentThread().interrupt();
		}
		awtrun(panel::mkenv);
		if(panel.env == null)
		    throw(new RuntimeException("Did not get a Vulkan environment"));
	    }
	    return(panel.env);
	}

	public Pipe.Op fbstate() {
	    return(vkfb);
	}

	public void swapbuffers(Render buf, Object mode) {
	    if(!(buf instanceof VkRender) || (((VkRender)buf).env != panel.env))
		throw(new IllegalArgumentException());
	    if(!(mode instanceof Boolean))
		throw(new IllegalArgumentException());
	    ((VkRender)buf).swapbuffers((Boolean)mode);
	    java.awt.EventQueue.invokeLater(dsp::process);
	}

	public void stats(Collection<String> buf) {
	    VkEnvironment env = panel.env;
	    if(env != null)
		buf.add(env.stats());
	}

	public void dispose() {
	    super.dispose();
	    panel.stop();
	    VkEnvironment env = panel.env;
	    if(env != null) {
		panel.env = null;
		env.dispose();
	    }
	}
    }

    public Windeye window() {
	return(new VkWindow());
    }

    public String description() {
	return(String.format("AWT/Vulkan, Java %s, LWJGL %s", System.getProperty("java.version", ""), org.lwjgl.Version.getVersion()));
    }
}
