package haven.iosys.tk;

import java.util.prefs.*;
import haven.*;
import haven.iosys.*;

/*
 * The player's choice of renderer, stored as a preference and
 * applied before the toolkit is created. OpenGL keeps using whatever
 * toolkit the configuration picks; Vulkan selects the "vulkan"
 * toolkit.
 *
 * Every Vulkan start leaves a marker keyed by its process ID until
 * its first frame is shown (or it exits normally). A marker left by
 * a process that no longer exists means that start crashed, and the
 * choice is switched back to OpenGL. Several clients can start at
 * once without tripping over each other's markers.
 */
public class RendererPref {
    public static final String GL = "gl", VULKAN = "vulkan";
    private static final String PREF = "renderer";
    private static boolean applied = false;
    private static String saved = null;
    private static boolean ours = false;
    private static volatile String notice = null;
    private static String marker = null;

    public static String get() {
	return(VULKAN.equals(Utils.getpref(PREF, GL)) ? VULKAN : GL);
    }

    public static void set(String renderer) {
	Utils.setpref(PREF, VULKAN.equals(renderer) ? VULKAN : GL);
    }

    /* Why the renderer differs from the choice, if it does. */
    public static String notice() {
	return(notice);
    }

    private static Preferences markers() {
	return(Preferences.userNodeForPackage(RendererPref.class).node("vkstarts"));
    }

    private static long mypid() {
	try {
	    return(ProcessHandle.current().pid());
	} catch(LinkageError e) {
	    return(-1);
	}
    }

    private static boolean alive(long pid) {
	try {
	    return(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
	} catch(LinkageError e) {
	    return(true);
	}
    }

    /* Removes markers of dead processes; returns whether any existed. */
    private static boolean crashed() {
	boolean ret = false;
	try {
	    Preferences node = markers();
	    for(String key : node.keys()) {
		long pid;
		try {
		    pid = Long.parseLong(key);
		} catch(NumberFormatException e) {
		    node.remove(key);
		    continue;
		}
		if(!alive(pid)) {
		    node.remove(key);
		    ret = true;
		}
	    }
	} catch(BackingStoreException | SecurityException e) {
	}
	return(ret);
    }

    static synchronized void apply() {
	if(applied)
	    return;
	applied = true;
	if(!VULKAN.equals(get()))
	    return;
	if(System.getProperty("haven.toolkit") != null) {
	    /* An explicit -Dhaven.toolkit wins over the option. */
	    return;
	}
	if(crashed()) {
	    Utils.setpref(PREF, GL);
	    notice = "The Vulkan renderer crashed during its last start, so OpenGL is used again.";
	    Warning.warn("warning: " + notice);
	    return;
	}
	long pid = mypid();
	if(pid >= 0) {
	    try {
		marker = Long.toString(pid);
		markers().put(marker, "starting");
		Runtime.getRuntime().addShutdownHook(new Thread(RendererPref::confirm, "Renderer marker cleanup"));
	    } catch(SecurityException | IllegalStateException e) {
		marker = null;
	    }
	}
	saved = Toolkit.toolkit.get();
	Toolkit.toolkit.set(VULKAN);
	ours = true;
    }

    /* Returns true if the toolkit selection was reverted and
     * should be retried. */
    static synchronized boolean fallback(Unavailable err) {
	if(!ours)
	    return(false);
	ours = false;
	Toolkit.toolkit.set(saved);
	confirm();
	notice = "The Vulkan renderer is not available here (" + err.getMessage() + "), so OpenGL is used.";
	Warning.warn("warning: " + notice);
	return(true);
    }

    /* Called once the Vulkan renderer has shown a frame. */
    public static synchronized void confirm() {
	if(marker == null)
	    return;
	try {
	    markers().remove(marker);
	} catch(SecurityException | IllegalStateException e) {
	}
	marker = null;
    }
}
