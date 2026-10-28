package nurgling.render;

import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;

/*
 * Windows sleeps in steps of its timer tick, 15.6 ms unless some
 * process asks for finer, so a frame pacer that sleeps can wake a
 * whole tick late. This asks for 1 ms (winmm timeBeginPeriod), as
 * games do, for as long as the process runs.
 */
public class TimerResolution {
    /* Held, never read: keeps winmm loaded while the request stands. */
    private static SharedLibrary winmm = null;
    private static boolean tried = false;

    public static synchronized void raise() {
	if(tried)
	    return;
	tried = true;
	if(!System.getProperty("os.name", "").startsWith("Windows"))
	    return;
	try {
	    SharedLibrary lib = Library.loadNative(TimerResolution.class, "org.lwjgl", "winmm");
	    long fn = lib.getFunctionAddress("timeBeginPeriod");
	    if(fn == 0)
		return;
	    if(JNI.invokeI(1, fn) == 0)
		winmm = lib;
	} catch(UnsatisfiedLinkError | NoClassDefFoundError e) {
	    System.err.println("Could not raise the timer resolution: " + e);
	}
    }
}
