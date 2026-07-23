package nurgling.tools;

import java.util.Collections;
import java.util.List;

/**
 * Alias whose keys match complete item names instead of substrings.
 */
public final class NExactAlias extends NAlias {
    public NExactAlias(List<String> keys) {
        super(keys, Collections.emptyList());
    }

    @Override
    public boolean matches(String name) {
        return matchesExact(name);
    }
}
