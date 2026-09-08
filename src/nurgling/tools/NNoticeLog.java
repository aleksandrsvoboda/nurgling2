package nurgling.tools;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

/**
 * Bounded log of the system notices the server sent to the game UI.
 *
 * Some events are only ever reported as text - breaking into a natural cave
 * gallery, for instance - so bots need a way to see them. Notices are fed in
 * from NGameUI.msg(UI.Notice) on the UI thread and read from bot threads,
 * hence the synchronization.
 */
public class NNoticeLog
{
    private static final int CAPACITY = 64;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private long seq = 0;

    /**
     * Record a notice. Called from the UI thread.
     */
    public synchronized void add(String message) {
        if (message == null || message.isEmpty())
            return;
        if (entries.size() >= CAPACITY)
            entries.removeFirst();
        entries.addLast(new Entry(++seq, message.toLowerCase(Locale.ROOT)));
    }

    /**
     * Sequence number of the newest notice. Take it before an operation and pass
     * it to {@link #contains} afterwards to ignore anything logged earlier.
     */
    public synchronized long seq() {
        return seq;
    }

    /**
     * True if a notice newer than {@code since} contains one of {@code substrings}.
     * Matching is case-insensitive, so {@code substrings} must be lowercase.
     */
    public synchronized boolean contains(long since, String... substrings) {
        Iterator<Entry> it = entries.descendingIterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.seq <= since)
                break;
            for (String substring : substrings) {
                if (entry.text.contains(substring))
                    return true;
            }
        }
        return false;
    }

    private static class Entry
    {
        final long seq;
        final String text;

        Entry(long seq, String text) {
            this.seq = seq;
            this.text = text;
        }
    }
}
