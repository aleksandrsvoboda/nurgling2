package nurgling.guarding;

import java.util.Collections;
import java.util.List;

/**
 * Declarative description of one guard TYPE's configurable inputs and how to build a live
 * {@link GuardTrigger} from resolved values. Registering a new GuardSpec (see
 * {@link GuardRegistry#register}) is all a new guard type needs to become configurable in
 * Forager Settings' Guarding section, which builds its rows generically from whatever's
 * registered rather than hand-coding UI per type.
 */
public final class GuardSpec {
    public final String id;
    public final String label;
    public final List<GuardInput> inputs;
    public final GuardFactory factory;

    public GuardSpec(String id, String label, List<GuardInput> inputs, GuardFactory factory) {
        this.id = id;
        this.label = label;
        this.inputs = inputs != null ? inputs : Collections.emptyList();
        this.factory = factory;
    }
}
