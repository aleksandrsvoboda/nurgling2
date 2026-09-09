package nurgling.guarding;

import java.util.Collections;
import java.util.List;

/** Declarative description of one guard type's configurable inputs and how to build its {@link GuardTrigger}. */
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
