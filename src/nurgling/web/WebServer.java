package nurgling.web;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A small HTTP/1.1 + WebSocket (RFC 6455) server: enough for static files, a few GET
 * endpoints and text WebSockets. The client builds at source level 1.8 and the JDK has no
 * WebSocket server, so this is hand-rolled instead of pulling in a dependency.
 */
public class WebServer {
    public interface Handler {
	/** A plain HTTP request; return null for 404. */
	Response http(Request req) throws IOException;
	/** A WebSocket was opened on {@code req.path}; return false to refuse it. */
	boolean open(Request req, WsConn conn);
	/** Where to forward this request instead of answering it here (a raw byte pipe, WebSockets included); null to answer it. */
	default Upstream upstream(Request req) {return(null);}
	/** Whether a WebSocket may be opened for this request; refused ones get a 403 instead of the upgrade. */
	default boolean wsallowed(Request req) {return(true);}
    }

    public static class Upstream {
	public final int port;
	public final String target;

	public Upstream(int port, String target) {this.port = port; this.target = target;}
    }

    public static class Request {
	public final String method, path, rawquery;
	public final Map<String, String> query, headers;
	final List<String> rawheaders;
	public final InetAddress remote;
	public byte[] body = new byte[0];

	Request(String method, String path, String rawquery, Map<String, String> query, Map<String, String> headers, List<String> rawheaders, InetAddress remote) {
	    this.method = method; this.path = path; this.rawquery = rawquery; this.query = query; this.headers = headers; this.rawheaders = rawheaders; this.remote = remote;
	}

	public String q(String key) {return(query.get(key));}
    }

    public static class Response {
	public final int status;
	public final String type;
	public final byte[] body;
	public final Map<String, String> headers = new LinkedHashMap<>();

	public Response(int status, String type, byte[] body) {
	    this.status = status; this.type = type; this.body = body;
	}

	public static Response text(int status, String text) {
	    return(new Response(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8)));
	}

	public static Response json(String json) {
	    return(new Response(200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8)));
	}

	public Response header(String k, String v) {headers.put(k, v); return(this);}
    }

    public final ServerSocket sk;
    private final Handler handler;
    private final Thread acceptor;
    private volatile boolean closed = false;

    public WebServer(InetAddress bind, int port, Handler handler) throws IOException {
	this.handler = handler;
	this.sk = new ServerSocket();
	sk.setReuseAddress(true);
	sk.bind(new InetSocketAddress(bind, port));
	acceptor = new Thread(this::accept, "Web server accept :" + sk.getLocalPort());
	acceptor.setDaemon(true);
	acceptor.start();
    }

    public int port() {return(sk.getLocalPort());}

    public void close() {
	closed = true;
	try {
	    sk.close();
	} catch(IOException e) {
	}
    }

    private void accept() {
	while(!closed) {
	    Socket s;
	    try {
		s = sk.accept();
	    } catch(IOException e) {
		if(closed)
		    return;
		continue;
	    }
	    Thread t = new Thread(() -> serve(s), "Web conn " + s.getRemoteSocketAddress());
	    t.setDaemon(true);
	    t.start();
	}
    }

    static String readline(InputStream in) throws IOException {
	StringBuilder buf = new StringBuilder();
	while(true) {
	    int c = in.read();
	    if(c < 0) {
		if(buf.length() == 0)
		    return(null);
		break;
	    }
	    if(c == '\n')
		break;
	    if(c != '\r')
		buf.append((char)c);
	    if(buf.length() > 16384)
		throw(new IOException("header line too long"));
	}
	return(buf.toString());
    }

    static Map<String, String> parsequery(String q) {
	Map<String, String> ret = new HashMap<>();
	if(q == null)
	    return(ret);
	for(String part : q.split("&")) {
	    if(part.isEmpty())
		continue;
	    int p = part.indexOf('=');
	    try {
		if(p < 0)
		    ret.put(URLDecoder.decode(part, "UTF-8"), "");
		else
		    ret.put(URLDecoder.decode(part.substring(0, p), "UTF-8"), URLDecoder.decode(part.substring(p + 1), "UTF-8"));
	    } catch(UnsupportedEncodingException | IllegalArgumentException e) {
	    }
	}
	return(ret);
    }

    /** Reads a request head; null at end of stream. */
    static Request readrequest(InputStream in, InetAddress remote) throws IOException {
	String line = readline(in);
	if(line == null)
	    return(null);
	String[] parts = line.split(" ");
	if(parts.length < 2)
	    throw(new IOException("bad request line"));
	Map<String, String> headers = new HashMap<>();
	List<String> raw = new ArrayList<>();
	while(true) {
	    String h = readline(in);
	    if((h == null) || h.isEmpty())
		break;
	    raw.add(h);
	    int p = h.indexOf(':');
	    if(p > 0)
		headers.put(h.substring(0, p).trim().toLowerCase(Locale.ROOT), h.substring(p + 1).trim());
	}
	String target = parts[1];
	int qp = target.indexOf('?');
	String path = (qp < 0) ? target : target.substring(0, qp);
	String query = (qp < 0) ? null : target.substring(qp + 1);
	try {
	    path = URLDecoder.decode(path, "UTF-8");
	} catch(IllegalArgumentException e) {
	    throw(new IOException("bad path"));
	}
	return(new Request(parts[0], path, query, parsequery(query), headers, raw, remote));
    }

    private void serve(Socket s) {
	try {
	    s.setTcpNoDelay(true);
	    InputStream in = new BufferedInputStream(s.getInputStream());
	    OutputStream out = new BufferedOutputStream(s.getOutputStream());
	    Request req = readrequest(in, s.getInetAddress());
	    if(req == null) {
		s.close();
		return;
	    }
	    Upstream up = handler.upstream(req);
	    if(up != null) {
		proxy(s, in, out, req, up);
		return;
	    }
	    if("websocket".equalsIgnoreCase(req.headers.get("upgrade"))) {
		String key = req.headers.get("sec-websocket-key");
		if(!handler.wsallowed(req)) {
		    writeresp(out, Response.text(403, "forbidden"));
		    s.close();
		    return;
		}
		if(key == null) {
		    writeresp(out, Response.text(400, "missing key"));
		    s.close();
		    return;
		}
		WsConn conn = new WsConn(s, in, out);
		String accept = Base64.getEncoder().encodeToString(sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
		synchronized(conn) {
		    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		    out.flush();
		}
		if(!handler.open(req, conn)) {
		    conn.close();
		    return;
		}
		conn.readloop();
		return;
	    }
	    String cl = req.headers.get("content-length");
	    if(cl != null) {
		int n;
		try {
		    n = Integer.parseInt(cl.trim());
		} catch(NumberFormatException e) {
		    n = -1;
		}
		if((n < 0) || (n > 65536)) {
		    writeresp(out, Response.text(400, "bad body"));
		    s.close();
		    return;
		}
		byte[] body = new byte[n];
		for(int off = 0; off < n;) {
		    int r = in.read(body, off, n - off);
		    if(r < 0)
			throw(new EOFException());
		    off += r;
		}
		req.body = body;
	    }
	    Response resp;
	    try {
		resp = handler.http(req);
	    } catch(RuntimeException e) {
		/* A failing endpoint must not take the server down with it. */
		e.printStackTrace();
		resp = Response.text(500, "internal error");
	    }
	    if(resp == null)
		resp = Response.text(404, "not found");
	    writeresp(out, resp);
	    s.close();
	} catch(IOException e) {
	    try {
		s.close();
	    } catch(IOException e2) {
	    }
	}
    }

    /** Forwards the request to 127.0.0.1:port and pipes bytes both ways until either side closes. */
    private static void proxy(Socket s, InputStream in, OutputStream out, Request req, Upstream up) throws IOException {
	Socket u;
	try {
	    u = new Socket(InetAddress.getLoopbackAddress(), up.port);
	} catch(IOException e) {
	    writeresp(out, Response.text(503, "seat is not running"));
	    s.close();
	    return;
	}
	u.setTcpNoDelay(true);
	OutputStream uo = new BufferedOutputStream(u.getOutputStream());
	StringBuilder h = new StringBuilder();
	h.append(req.method).append(' ').append(up.target).append(" HTTP/1.1\r\n");
	for(String line : req.rawheaders) {
	    if(!line.toLowerCase(Locale.ROOT).startsWith("host:"))
		h.append(line).append("\r\n");
	}
	h.append("Host: 127.0.0.1:").append(up.port).append("\r\n\r\n");
	uo.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
	uo.flush();
	Thread fwd = new Thread(() -> {
		pipe(in, uo);
		try {
		    u.shutdownOutput();
		} catch(IOException e) {
		}
	    }, "Web proxy up");
	fwd.setDaemon(true);
	fwd.start();
	pipe(u.getInputStream(), out);
	try {
	    u.close();
	} catch(IOException e) {
	}
	s.close();
    }

    private static void pipe(InputStream from, OutputStream to) {
	byte[] buf = new byte[16384];
	try {
	    for(int n; (n = from.read(buf)) > 0;) {
		to.write(buf, 0, n);
		to.flush();
	    }
	} catch(IOException e) {
	}
    }

    static void writeresp(OutputStream out, Response resp) throws IOException {
	StringBuilder h = new StringBuilder();
	h.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason(resp.status)).append("\r\n");
	h.append("Content-Type: ").append(resp.type).append("\r\n");
	h.append("Content-Length: ").append(resp.body.length).append("\r\n");
	h.append("Connection: close\r\n");
	for(Map.Entry<String, String> e : resp.headers.entrySet())
	    h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
	h.append("\r\n");
	out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
	out.write(resp.body);
	out.flush();
    }

    static String reason(int st) {
	switch(st) {
	case 200: return("OK");
	case 302: return("Found");
	case 400: return("Bad Request");
	case 403: return("Forbidden");
	case 404: return("Not Found");
	case 503: return("Service Unavailable");
	default: return("Error");
	}
    }

    static byte[] sha1(byte[] data) {
	try {
	    return(MessageDigest.getInstance("SHA-1").digest(data));
	} catch(NoSuchAlgorithmException e) {
	    throw(new AssertionError(e));
	}
    }

    /** Serves files packaged next to the classes under {@code nurgling/web/app/}. */
    public static Response asset(String name) throws IOException {
	if(name.contains("..") || name.contains("\\"))
	    return(null);
	try(InputStream in = WebServer.class.getResourceAsStream("app/" + name)) {
	    if(in == null)
		return(null);
	    ByteArrayOutputStream buf = new ByteArrayOutputStream();
	    byte[] b = new byte[8192];
	    for(int n; (n = in.read(b)) > 0;)
		buf.write(b, 0, n);
	    return(new Response(200, mime(name), buf.toByteArray()).header("Cache-Control", "no-cache"));
	}
    }

    static String mime(String name) {
	if(name.endsWith(".html")) return("text/html; charset=utf-8");
	if(name.endsWith(".js")) return("text/javascript; charset=utf-8");
	if(name.endsWith(".css")) return("text/css; charset=utf-8");
	if(name.endsWith(".png")) return("image/png");
	if(name.endsWith(".json")) return("application/json");
	if(name.endsWith(".svg")) return("image/svg+xml");
	return("application/octet-stream");
    }

    /** Constant-time comparison for access keys. */
    public static boolean keyeq(String a, String b) {
	if((a == null) || (b == null))
	    return(false);
	return(MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
    }

    public static String newkey() {
	byte[] b = new byte[18];
	new java.security.SecureRandom().nextBytes(b);
	return(Base64.getUrlEncoder().withoutPadding().encodeToString(b));
    }
}
