package nurgling.animals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Who stays and who goes among one sex's slaughter candidates. Shared by {@code KillAnimalsAction}
 * and the cull preview, so what the preview shows is what the bot does.
 */
public final class CullSelection<T> {
    /** Candidates in rank order, best first. */
    public final List<T> sorted;
    public final List<T> keep;
    public final List<T> kill;
    /** The worst kept animal: every other candidate ranked at or below it is slaughtered. */
    public final T bar;

    private CullSelection(List<T> sorted, List<T> keep, List<T> kill, T bar) {
        this.sorted = Collections.unmodifiableList(sorted);
        this.keep = Collections.unmodifiableList(keep);
        this.kill = Collections.unmodifiableList(kill);
        this.bar = bar;
    }

    /**
     * Keeps the best {@code keepCount} candidates that pass {@code keepable} (the adults), then marks
     * every other candidate that ranks no better than the worst of them. With no {@code keepable}
     * every candidate is marked; with no keepable candidate nobody is.
     */
    public static <T> CullSelection<T> select(List<T> candidates, Comparator<? super T> comp,
                                              Predicate<? super T> keepable, int keepCount) {
        List<T> sorted = new ArrayList<>(candidates);
        sorted.sort(comp);
        List<T> keep = new ArrayList<>();
        List<T> kill = new ArrayList<>();
        T last = null;
        if (keepable != null) {
            int c = 0;
            for (T a : sorted) {
                if (keepable.test(a)) {
                    if (c < keepCount) {
                        keep.add(a);
                        c++;
                        last = a;
                    }
                }
            }
            for (T a : sorted) {
                if (!keep.contains(a) && last != null && comp.compare(last, a) <= 0)
                    kill.add(a);
            }
        } else {
            kill.addAll(sorted);
        }
        return new CullSelection<>(sorted, keep, kill, last);
    }
}
