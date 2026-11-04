package nurgling.scripting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Layer 4: Java objects handed to a script by reference. Held strongly until released or the connection closes. */
public final class JavaRefs {
    private final Map<Integer, Object> refs = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    public int put(Object o) {
        int id = seq.incrementAndGet();
        refs.put(id, o);
        return id;
    }

    public Object get(int id) {
        return refs.get(id);
    }

    public boolean release(int id) {
        return refs.remove(id) != null;
    }

    public int size() {
        return refs.size();
    }

    void clear() {
        refs.clear();
    }
}
