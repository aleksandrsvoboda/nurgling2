package haven;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/* A Defer job that throws Loading must wait for what it is loading, not re-run every time a
 * waiter polls it (the map-cut rebuild loop). */
class DeferReschedTest {
    static class Gate extends Loading {
	final Waitable.Queue q;
	final boolean[] open;

	Gate(Waitable.Queue q, boolean[] open) {
	    this.q = q;
	    this.open = open;
	}

	public void waitfor(Runnable callback, Consumer<Waitable.Waiting> reg) {
	    synchronized(open) {
		if(open[0]) {
		    reg.accept(Waitable.Waiting.dummy);
		    callback.run();
		} else {
		    reg.accept(q.add(callback));
		}
	    }
	}
    }

    /* Polls done() like a waiting gob loader does; returns whether the job finished. */
    private static boolean poll(Defer.Future<?> f, double secs) {
	double end = Utils.rtime() + secs;
	while(Utils.rtime() < end) {
	    if(f.done())
		return(true);
	    Thread.onSpinWait();
	}
	return(f.done());
    }

    @Test
    void waitsForTheLoadingInsteadOfSpinning() {
	Waitable.Queue q = new Waitable.Queue();
	boolean[] open = {false};
	AtomicInteger runs = new AtomicInteger();
	Defer.Future<String> f = Defer.later(() -> {
		runs.incrementAndGet();
		synchronized(open) {
		    if(!open[0])
			throw(new Gate(q, open));
		}
		return("built");
	    });
	assertFalse(poll(f, 0.3));
	assertEquals(1, runs.get(), "re-ran while what it waits for was still missing");
	synchronized(open) {
	    open[0] = true;
	}
	q.wnotify();
	assertTrue(poll(f, 2.0));
	assertEquals(2, runs.get());
	assertEquals("built", f.get());
    }

    @Test
    void retriesAfterBackstopWhenNothingSignals() {
	boolean[] open = {false};
	AtomicInteger runs = new AtomicInteger();
	Defer.Future<String> f = Defer.later(() -> {
		runs.incrementAndGet();
		throw(new Gate(new Waitable.Queue(), open));
	    });
	poll(f, 2.5);
	f.cancel();
	assertTrue(runs.get() >= 2, "never retried: " + runs.get());
	assertTrue(runs.get() <= 4, "retried too often: " + runs.get());
    }

    @Test
    void unwaitableLoadingStillRetries() {
	AtomicInteger runs = new AtomicInteger();
	Defer.Future<String> f = Defer.later(() -> {
		if(runs.incrementAndGet() < 4)
		    throw(new Loading("plain"));
		return("ok");
	    });
	assertTrue(poll(f, 1.0));
	assertEquals("ok", f.get());
    }
}
