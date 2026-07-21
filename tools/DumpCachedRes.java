import haven.*;
import java.io.*;
import java.nio.file.*;

/* Recovers the OLD clean upstream version of a resource.
 *
 * The server only ever serves the current version of a resource, so
 * `get-code` alone cannot tell you what upstream actually changed -- it can
 * only show you new-clean, which mixes upstream changes and our own
 * customizations together in one diff.
 *
 * The client's on-disk resource cache, however, still holds the version that
 * was last downloaded, which is the version our @FromResource annotations
 * were written against. This dumps those cached resources back out as .res
 * files so `get-code -U` can extract their source through the normal path:
 *
 *   javac -cp bin/hafen.jar -d tools/classes tools/DumpCachedRes.java
 *   java -cp "bin/hafen.jar;tools/classes" DumpCachedRes oldres <resource-names>
 *   java -cp bin/hafen.jar haven.Resource get-code \
 *        -U "file:///<abs-path>/oldres/" -o oldstage <resource-names>
 *
 * Then diff oldstage against a normal `get-code -o newstage` to see the
 * upstream delta by itself. See docs/resource-upgrade-strategy.md.
 *
 * Note: this only works if the client has not re-downloaded the resource at
 * its new version since the update. The printed version is the one actually
 * recovered -- always check it matches the local @FromResource version before
 * trusting the diff.
 */
public class DumpCachedRes {
    public static void main(String[] args) throws Exception {
	if(args.length < 2) {
	    System.err.println("usage: DumpCachedRes OUTDIR RESOURCE-NAME...");
	    System.exit(1);
	}
	Path dst = Paths.get(args[0]);
	ResCache cache = ResCache.global;
	for(int i = 1; i < args.length; i++) {
	    String nm = args[i];
	    Path out = dst.resolve(nm + ".res");
	    Files.createDirectories(out.getParent());
	    try(InputStream in = cache.fetch("res/" + nm);
		OutputStream o = Files.newOutputStream(out))
	    {
		byte[] buf = new byte[8192];
		int n;
		while((n = in.read(buf)) > 0)
		    o.write(buf, 0, n);
	    } catch(FileNotFoundException e) {
		System.out.println("miss: " + nm + " (not in cache)");
		continue;
	    }
	    /* Resource layout: "Haven Resource 1" signature, then a
	     * little-endian uint16 version. */
	    byte[] all = Files.readAllBytes(out);
	    int ver = (all[16] & 0xff) | ((all[17] & 0xff) << 8);
	    System.out.println("wrote " + out + " (version " + ver + ")");
	}
    }
}
