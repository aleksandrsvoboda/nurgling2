package haven.res.lib.tree;

import haven.*;
import haven.render.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Card stand-in for an occasional tree. */
final class TreeCard extends CardNode<TreeCard.Data> {
    private static final Map<String, String> KIND = Cards.table(3);
    private static final Map<String, Defer.Future<Data>> cache = new ConcurrentHashMap<>();

    static final class Data extends CardNode.Art {
	final Pipe.Op st;

	Data(Model face, Pipe.Op st, Cards.Ground ground) {
	    super(face, ground);
	    this.st = st;
	}
    }

    private final Tree tree;

    private TreeCard(Tree tree, Defer.Future<Data> src) {
	super(src);
	this.tree = tree;
    }

    static TreeCard of(Tree tree, Resource res) {
	Gob gob = tree.gob;
	if((gob == null) || (res == null) || !Cards.pick(gob.id, 0, 1000))
	    return(null);
	String nm = res.name;
	String path = KIND.get(nm.substring(nm.lastIndexOf('/') + 1));
	if(path == null)
	    return(null);
	return(new TreeCard(tree, cache.computeIfAbsent(path, p -> Defer.later(() -> load(Cards.str(2) + p)))));
    }

    protected Collection<RenderTree.Node> fallback() {
	return(Arrays.asList(tree.parts[tree.stage]));
    }

    protected Pipe.Op frame(Data art, Matrix4f eye) {
	return(art.st);
    }

    private static Data load(String path) {
	try {
	    List<Cards.Pic> pics = Cards.read(Cards.fetch(path));
	    Cards.Pic body = Cards.biggest(pics, true), shade = Cards.biggest(pics, false);
	    if(body == null)
		return(null);
	    TexL tex = Cards.tex(body.img);
	    return(new Data(Cards.face(body, tex), Cards.facest(tex), (shade == null) ? null : new Cards.Ground(shade)));
	} catch(IOException e) {
	    return(null);
	}
    }
}
