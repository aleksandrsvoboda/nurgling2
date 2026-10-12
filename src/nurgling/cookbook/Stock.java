package nurgling.cookbook;

import haven.Utils;
import nurgling.db.dao.StorageItemDao;

import java.util.*;

/**
 * How much of each item the village has in storage, counted as the Storage Items window counts:
 * one row is one item, and a barrel or cistern row holds an amount in its unit. Items are keyed by
 * {@link GenericTypes#stockKey} of their name.
 */
public final class Stock {
    public static final Stock NONE = new Stock(List.of());

    private final Map<String, Integer> counts = new HashMap<>();
    /* Key -> unit -> amount, for barrel and cistern rows. */
    private final Map<String, Map<String, Double>> bulk = new HashMap<>();

    public Stock(List<StorageItemDao.StorageItemData> items) {
        for(StorageItemDao.StorageItemData item : items) {
            String k = GenericTypes.stockKey(item.getName());
            if(item.isBulk())
                bulk.computeIfAbsent(k, x -> new TreeMap<>()).merge(item.getBulkUnit(), item.getBulkAmount(), Double::sum);
            else
                counts.merge(k, 1, Integer::sum);
        }
    }

    /** Every item stored, by key. */
    public Set<String> keys() {
        Set<String> ret = new HashSet<>(counts.keySet());
        ret.addAll(bulk.keySet());
        return ret;
    }

    /**
     * How much is stored under any of the names, for display ("534", "12.5 l", "3 + 12.5 l"); null
     * when nothing is.
     */
    public String amount(Collection<String> names) {
        Set<String> keys = new HashSet<>();
        for(String n : names)
            keys.add(GenericTypes.stockKey(n));
        int count = 0;
        Map<String, Double> amounts = new TreeMap<>();
        for(String k : keys) {
            count += counts.getOrDefault(k, 0);
            for(Map.Entry<String, Double> e : bulk.getOrDefault(k, Map.of()).entrySet())
                amounts.merge(e.getKey(), e.getValue(), Double::sum);
        }
        List<String> parts = new ArrayList<>();
        if(count > 0)
            parts.add(String.valueOf(count));
        for(Map.Entry<String, Double> e : amounts.entrySet()) {
            if(e.getValue() > 0)
                parts.add((Utils.odformat2(e.getValue(), 2) + " " + e.getKey()).trim());
        }
        return parts.isEmpty() ? null : String.join(" + ", parts);
    }
}
