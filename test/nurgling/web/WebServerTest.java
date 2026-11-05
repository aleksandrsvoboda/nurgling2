package nurgling.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** The hand-rolled HTTP/WebSocket server: routing, key checks, frames of every length, and the proxy. */
class WebServerTest {
    private final List<WebServer> servers = new ArrayList<>();

    @AfterEach
    void close() {
	for(WebServer s : servers)
	    s.close();
    }

    /** Echoes every text message back, upper-cased; WebSockets need ?k=good. */
    private WebServer echo() throws IOException {
	WebServer s = new WebServer(InetAddress.getLoopbackAddress(), 0, new WebServer.Handler() {
		public WebServer.Response http(WebServer.Request req) throws IOException {
		    if(req.path.startsWith("/app/"))
			return(WebServer.asset(req.path.substring(5)));
		    if(req.path.equals("/body"))
			return(WebServer.Response.text(200, new String(req.body, StandardCharsets.UTF_8)));
		    return(null);
		}
		public boolean wsallowed(WebServer.Request req) {return("good".equals(req.q("k")));}
		public boolean open(WebServer.Request req, WsConn conn) {
		    conn.onmessage(m -> conn.send(m.toUpperCase(Locale.ROOT)));
		    return(true);
		}
	    });
	servers.add(s);
	return(s);
    }

    private static String http(int port, String head, String body) throws IOException {
	try(Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
	    s.setSoTimeout(5000);
	    OutputStream out = s.getOutputStream();
	    out.write((head + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
	    out.flush();
	    ByteArrayOutputStream buf = new ByteArrayOutputStream();
	    InputStream in = s.getInputStream();
	    byte[] b = new byte[4096];
	    for(int n; (n = in.read(b)) > 0;)
		buf.write(b, 0, n);
	    return(new String(buf.toByteArray(), StandardCharsets.UTF_8));
	}
    }

    @Test
    void servesAssetsAndRejectsTraversal() throws IOException {
	WebServer s = echo();
	String ok = http(s.port(), "GET /app/index.html HTTP/1.1\r\nHost: x", "");
	assertTrue(ok.startsWith("HTTP/1.1 200"), ok);
	assertTrue(ok.contains("text/html"));
	assertTrue(http(s.port(), "GET /app/../WebServer.class HTTP/1.1\r\nHost: x", "").startsWith("HTTP/1.1 404"));
	assertTrue(http(s.port(), "GET /nothing HTTP/1.1\r\nHost: x", "").startsWith("HTTP/1.1 404"));
    }

    @Test
    void readsRequestBodies() throws IOException {
	WebServer s = echo();
	String r = http(s.port(), "POST /body HTTP/1.1\r\nHost: x\r\nContent-Length: 11", "hello world");
	assertTrue(r.endsWith("hello world"), r);
    }

    /** A minimal client: masked frames out, unmasked frames in. */
    static class Client implements Closeable {
	final Socket s;
	final DataInputStream in;
	final OutputStream out;
	final String status;

	Client(int port, String path) throws IOException {
	    s = new Socket(InetAddress.getLoopbackAddress(), port);
	    s.setSoTimeout(5000);
	    out = s.getOutputStream();
	    in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
	    out.write(("GET " + path + " HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
		       "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
	    status = WebServer.readline(in);
	    while(true) {
		String h = WebServer.readline(in);
		if((h == null) || h.isEmpty())
		    break;
		if(h.startsWith("Sec-WebSocket-Accept:"))
		    assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", h.substring(21).trim());
	    }
	}

	void send(String text, boolean fragmented) throws IOException {
	    byte[] d = text.getBytes(StandardCharsets.UTF_8);
	    if(!fragmented) {
		frame(0x81, d, 0, d.length);
	    } else {
		int h = d.length / 2;
		frame(0x01, d, 0, h);
		frame(0x80, d, h, d.length - h);
	    }
	}

	private void frame(int b0, byte[] d, int off, int len) throws IOException {
	    ByteArrayOutputStream f = new ByteArrayOutputStream();
	    f.write(b0);
	    if(len < 126) {
		f.write(0x80 | len);
	    } else if(len < 65536) {
		f.write(0x80 | 126); f.write(len >> 8); f.write(len);
	    } else {
		f.write(0x80 | 127);
		for(int i = 7; i >= 0; i--)
		    f.write((int)((long)len >> (i * 8)));
	    }
	    byte[] mask = {1, 2, 3, 4};
	    f.write(mask);
	    for(int i = 0; i < len; i++)
		f.write(d[off + i] ^ mask[i & 3]);
	    out.write(f.toByteArray());
	    out.flush();
	}

	String recv() throws IOException {
	    int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
	    assertEquals(0x81, b0);
	    long len = b1 & 0x7f;
	    if(len == 126)
		len = in.readUnsignedShort();
	    else if(len == 127)
		len = in.readLong();
	    byte[] d = new byte[(int)len];
	    in.readFully(d);
	    return(new String(d, StandardCharsets.UTF_8));
	}

	public void close() throws IOException {s.close();}
    }

    @Test
    void refusesWrongKeyBeforeUpgrading() throws IOException {
	WebServer s = echo();
	try(Client c = new Client(s.port(), "/ws?k=bad")) {
	    assertTrue(c.status.startsWith("HTTP/1.1 403"), c.status);
	}
    }

    @Test
    void roundTripsShortLongAndFragmentedMessages() throws IOException {
	WebServer s = echo();
	try(Client c = new Client(s.port(), "/ws?k=good")) {
	    assertTrue(c.status.startsWith("HTTP/1.1 101"), c.status);
	    c.send("hi", false);
	    assertEquals("HI", c.recv());
	    String mid = String.join("", Collections.nCopies(500, "abc"));
	    c.send(mid, false);
	    assertEquals(mid.toUpperCase(Locale.ROOT), c.recv());
	    String big = String.join("", Collections.nCopies(30000, "xyz"));
	    c.send(big, false);
	    assertEquals(big.toUpperCase(Locale.ROOT), c.recv());
	    c.send("split me", true);
	    assertEquals("SPLIT ME", c.recv());
	}
    }

    @Test
    void proxiesHttpAndWebSockets() throws IOException {
	WebServer seat = echo();
	WebServer host = new WebServer(InetAddress.getLoopbackAddress(), 0, new WebServer.Handler() {
		public WebServer.Response http(WebServer.Request req) {return(null);}
		public boolean open(WebServer.Request req, WsConn conn) {return(false);}
		public WebServer.Upstream upstream(WebServer.Request req) {
		    if(!req.path.startsWith("/seat/a/"))
			return(null);
		    return(new WebServer.Upstream(seat.port(), req.path.substring(7) + ((req.rawquery == null) ? "" : "?" + req.rawquery)));
		}
	    });
	servers.add(host);
	String r = http(host.port(), "POST /seat/a/body HTTP/1.1\r\nHost: x\r\nContent-Length: 5", "proxy");
	assertTrue(r.startsWith("HTTP/1.1 200") && r.endsWith("proxy"), r);
	try(Client c = new Client(host.port(), "/seat/a/ws?k=good")) {
	    assertTrue(c.status.startsWith("HTTP/1.1 101"), c.status);
	    c.send("through", false);
	    assertEquals("THROUGH", c.recv());
	}
	try(Client c = new Client(host.port(), "/seat/a/ws?k=bad")) {
	    assertTrue(c.status.startsWith("HTTP/1.1 403"), c.status);
	}
    }

    @Test
    void comparesKeysExactly() {
	assertTrue(WebServer.keyeq("abc", "abc"));
	assertFalse(WebServer.keyeq("abc", "abd"));
	assertFalse(WebServer.keyeq("abc", null));
	assertNotEquals(WebServer.newkey(), WebServer.newkey());
    }
}
