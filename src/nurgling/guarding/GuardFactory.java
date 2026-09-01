package nurgling.guarding;

import java.util.Map;

/** Builds a live {@link GuardTrigger} from a guard entry's resolved input values (keyed by each
 *  {@link GuardInput#key} the owning {@link GuardSpec} declares). */
public interface GuardFactory {
    GuardTrigger build(Map<String, Double> settings);
}
