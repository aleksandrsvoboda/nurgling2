package haven.render;

import java.util.*;
import haven.*;

/*
 * nurgling: a draw list whose changes are queued and applied only when
 * it is drawn. The tree's changes arrive under the tree lock from the
 * world tick's workers and loaders; applying them into the back list
 * there made those threads wait for whole frames being recorded,
 * since recording holds the back list. Here they only queue. draw()
 * applies the queue under the tree lock, a short step, and records
 * without it.
 *
 * Repeated changes collapse: a slot's queued add absorbs its updates
 * and is cancelled by its removal, a slot is updated at most once, and
 * a state group once with the union of its masks. A list that goes
 * undrawn for a while thus queues at most one change per slot and
 * group, never one per change.
 */
public class DeferredDrawList implements DrawList {
    public final DrawList back;
    private final RenderList.Adapter tree;
    /* Queued slot changes: ADD or UPDATE per slot. */
    private Map<Slot<? extends Rendered>, Integer> slots = new LinkedHashMap<>();
    private Map<Pipe, int[]> groups = new LinkedHashMap<>();
    /* Slots whose change met something still loading; retried next
     * frame, before anything newer for them. */
    private Map<Slot<? extends Rendered>, Integer> retry = new LinkedHashMap<>();
    private static final int ADD = 1, UPDATE = 3;
    /* Time per frame for applying changes; the rest waits a frame, so
     * an area streaming in spreads over frames instead of stalling
     * one. Changes are applied in arrival order, so none starves. */
    private static final double BUDGET = 0.0015;

    public DeferredDrawList(DrawList back, RenderList.Adapter tree) {
	this.back = back;
	this.tree = tree;
    }

    public void add(Slot<? extends Rendered> slot) {
	synchronized(this) {
	    slots.put(slot, ADD);
	}
    }

    /* Removals apply at once: whoever removes a slot frees what it
     * drew with right after, so the list must not hold on to it. This
     * waits for a recording in progress, but removals are rare next
     * to updates. */
    public void remove(Slot<? extends Rendered> slot) {
	boolean listed;
	synchronized(this) {
	    Integer prev = slots.remove(slot);
	    Integer rprev = retry.remove(slot);
	    /* A queued add never reached the back list. */
	    listed = !(((prev != null) && (prev == ADD)) || ((rprev != null) && (rprev == ADD)));
	}
	if(listed)
	    back.remove(slot);
    }

    public void update(Slot<? extends Rendered> slot) {
	synchronized(this) {
	    /* A queued add reads the slot's state when it is applied. */
	    if(!slots.containsKey(slot))
		slots.put(slot, UPDATE);
	}
    }

    public void update(Pipe group, int[] mask) {
	synchronized(this) {
	    int[] prev = groups.get(group);
	    groups.put(group, (prev == null) ? mask.clone() : union(prev, mask));
	}
    }

    private static int[] union(int[] a, int[] b) {
	int n = a.length;
	int[] ret = Arrays.copyOf(a, a.length + b.length);
	outer: for(int id : b) {
	    for(int i = 0; i < n; i++) {
		if(ret[i] == id)
		    continue outer;
	    }
	    ret[n++] = id;
	}
	return((n == ret.length) ? ret : Arrays.copyOf(ret, n));
    }

    private void apply(Slot<? extends Rendered> slot, int op) {
	switch(op) {
	case ADD: back.add(slot); break;
	case UPDATE: back.update(slot); break;
	}
    }

    /* Applies the queued changes. Called with the tree lock held. */
    public void prepare(RenderList.Adapter tree) {
	Map<Slot<? extends Rendered>, Integer> sbatch, rbatch;
	Map<Pipe, int[]> gbatch;
	synchronized(this) {
	    sbatch = slots;
	    gbatch = groups;
	    rbatch = retry;
	    slots = new LinkedHashMap<>();
	    groups = new LinkedHashMap<>();
	    retry = new LinkedHashMap<>();
	}
	Map<Slot<? extends Rendered>, Integer> again = new LinkedHashMap<>();
	Map<Slot<? extends Rendered>, Integer> left = new LinkedHashMap<>();
	double end = Utils.rtime() + BUDGET;
	/* A retried add reads the latest state anyway. */
	for(Slot<? extends Rendered> slot : rbatch.keySet())
	    sbatch.remove(slot);
	for(Map<Slot<? extends Rendered>, Integer> batch : Arrays.asList(rbatch, sbatch)) {
	    for(Map.Entry<Slot<? extends Rendered>, Integer> e : batch.entrySet()) {
		if(Utils.rtime() > end)
		    left.put(e.getKey(), e.getValue());
		else
		    run(e.getKey(), e.getValue(), again);
	    }
	}
	Map<Pipe, int[]> gleft = new LinkedHashMap<>();
	for(Map.Entry<Pipe, int[]> e : gbatch.entrySet()) {
	    if(Utils.rtime() > end) {
		gleft.put(e.getKey(), e.getValue());
		continue;
	    }
	    try {
		back.update(e.getKey(), e.getValue());
	    } catch(Loading l) {
		gleft.put(e.getKey(), e.getValue());
	    }
	}
	synchronized(this) {
	    if(!again.isEmpty()) {
		again.putAll(retry);
		retry = again;
	    }
	    /* Put back what the budget left, ahead of newer changes. An
	     * unapplied add stays an add whatever came after it. */
	    if(!left.isEmpty()) {
		for(Map.Entry<Slot<? extends Rendered>, Integer> e : slots.entrySet()) {
		    if(!left.containsKey(e.getKey()))
			left.put(e.getKey(), e.getValue());
		}
		slots = left;
	    }
	    for(Map.Entry<Pipe, int[]> e : groups.entrySet()) {
		int[] prev = gleft.get(e.getKey());
		gleft.put(e.getKey(), (prev == null) ? e.getValue() : union(prev, e.getValue()));
	    }
	    groups = gleft;
	}
	back.prepare(tree);
    }

    private void run(Slot<? extends Rendered> slot, int op, Map<Slot<? extends Rendered>, Integer> again) {
	try {
	    apply(slot, op);
	} catch(RenderTree.SlotRemoved e) {
	    /* Its slot left the tree while the change waited. */
	} catch(Loading l) {
	    again.put(slot, op);
	}
    }

    public void draw(Render out) {
	/* While someone holds the tree, the changes wait a frame: the
	 * back list is complete as of its last prepare, and drawing it
	 * reads only its own state. */
	Locked lk = tree.trylock();
	if(lk != null) {
	    try {
		prepare(tree);
	    } finally {
		lk.close();
	    }
	}
	back.draw(out);
    }

    public DrawList async(boolean enabled) {
	back.async(enabled);
	return(this);
    }

    public String stats() {return(back.stats());}

    public DrawList desc(Object desc) {
	back.desc(desc);
	return(this);
    }

    public void dispose() {
	synchronized(this) {
	    slots.clear();
	    groups.clear();
	    retry.clear();
	}
	back.dispose();
    }

    /* The list an environment made, for its compatibility checks. */
    public static DrawList unwrap(DrawList list) {
	return((list instanceof DeferredDrawList) ? ((DeferredDrawList)list).back : list);
    }
}
