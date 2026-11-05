package nurgling.web;

import haven.UI;
import nurgling.NUI;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Publishes the normal desktop client to the browser, for side-by-side development of the
 * HTML UI against the real game. Off unless started with -Dnurgling.web.attach=&lt;port&gt;;
 * the access key is -Dnurgling.web.key or a random one printed to the console.
 */
public class WebAttach {
    private static WebBridge bridge = null;

    public static synchronized void maybeStart() {
	if(bridge != null)
	    return;
	Integer port = Integer.getInteger("nurgling.web.attach");
	if(port == null)
	    return;
	String key = System.getProperty("nurgling.web.key");
	if((key == null) || key.isEmpty())
	    key = WebServer.newkey();
	WebBridge b = new WebBridge(() -> (UI)NUI.getInstance(), key);
	try {
	    String bind = System.getProperty("nurgling.web.bind", "0.0.0.0");
	    WebServer ws = b.listen(InetAddress.getByName(bind), port);
	    System.out.println("[web] desktop client published on " + bind + ":" + ws.port() + "  open http://<this-pc>:" + ws.port() + "/#k=" + key);
	    bridge = b;
	} catch(IOException e) {
	    System.out.println("[web] could not publish the desktop client: " + e.getMessage());
	    b.stop();
	}
    }
}
