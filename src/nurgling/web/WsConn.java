package nurgling.web;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** One server-side WebSocket: text frames in and out, ping/pong, close. */
public class WsConn {
    private static final int MAXMSG = 1 << 20;
    private final Socket sk;
    private final InputStream in;
    private final OutputStream out;
    private volatile boolean closed = false;
    private volatile Consumer<String> onmessage = m -> {};
    private volatile Runnable onclose = () -> {};

    WsConn(Socket sk, InputStream in, OutputStream out) {
	this.sk = sk; this.in = in; this.out = out;
    }

    public void onmessage(Consumer<String> h) {this.onmessage = h;}
    public void onclose(Runnable h) {this.onclose = h;}
    public boolean closed() {return(closed);}

    public void send(String text) {
	frame(1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void frame(int op, byte[] data) {
	if(closed)
	    return;
	try {
	    synchronized(this) {
		out.write(0x80 | op);
		if(data.length < 126) {
		    out.write(data.length);
		} else if(data.length < 65536) {
		    out.write(126);
		    out.write(data.length >> 8);
		    out.write(data.length);
		} else {
		    out.write(127);
		    long l = data.length;
		    for(int i = 7; i >= 0; i--)
			out.write((int)(l >> (i * 8)));
		}
		out.write(data);
		out.flush();
	    }
	} catch(IOException e) {
	    close();
	}
    }

    public void close() {
	if(closed)
	    return;
	closed = true;
	try {
	    synchronized(this) {
		out.write(0x88);
		out.write(0);
		out.flush();
	    }
	} catch(IOException e) {
	}
	try {
	    sk.close();
	} catch(IOException e) {
	}
	onclose.run();
    }

    private int rd() throws IOException {
	int c = in.read();
	if(c < 0)
	    throw(new EOFException());
	return(c);
    }

    void readloop() {
	ByteArrayOutputStream msg = new ByteArrayOutputStream();
	int msgop = 0;
	try {
	    while(!closed) {
		int b0 = rd(), b1 = rd();
		boolean fin = (b0 & 0x80) != 0;
		int op = b0 & 0x0f;
		boolean masked = (b1 & 0x80) != 0;
		long len = b1 & 0x7f;
		if(len == 126) {
		    len = (rd() << 8) | rd();
		} else if(len == 127) {
		    len = 0;
		    for(int i = 0; i < 8; i++)
			len = (len << 8) | rd();
		}
		if(len > MAXMSG)
		    throw(new IOException("frame too large"));
		byte[] mask = new byte[4];
		if(masked) {
		    for(int i = 0; i < 4; i++)
			mask[i] = (byte)rd();
		}
		byte[] data = new byte[(int)len];
		int off = 0;
		while(off < data.length) {
		    int n = in.read(data, off, data.length - off);
		    if(n < 0)
			throw(new EOFException());
		    off += n;
		}
		if(masked) {
		    for(int i = 0; i < data.length; i++)
			data[i] ^= mask[i & 3];
		}
		if(op == 8) {
		    break;
		} else if(op == 9) {
		    frame(10, data);
		} else if(op == 10) {
		} else {
		    if(op != 0)
			msgop = op;
		    msg.write(data);
		    if(msg.size() > MAXMSG)
			throw(new IOException("message too large"));
		    if(fin) {
			byte[] m = msg.toByteArray();
			msg.reset();
			if(msgop == 1) {
			    try {
				onmessage.accept(new String(m, StandardCharsets.UTF_8));
			    } catch(RuntimeException e) {
				/* One bad command must not drop the connection. */
				e.printStackTrace();
			    }
			}
		    }
		}
	    }
	} catch(IOException e) {
	}
	close();
    }
}
