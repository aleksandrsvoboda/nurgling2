package nurgling.actions.bots;

import java.util.Map;

/**
 * Transfers liftable objects using global zones with chunk navigation.
 * Thin subclass of TransferLiftable with requireGlobalZones=true.
 */
public class TransferLiftableGlobal extends TransferLiftable {

    public TransferLiftableGlobal() { super(true); }
    public TransferLiftableGlobal(Map<String, Object> settings) { super(true); }
}
