package nurgling.actions;

import java.util.Map;

/**
 * Fills waterskins using global water zone with chunk navigation.
 * Thin subclass of FillWaterskins with useGlobalZone=true.
 */
public class FillWaterskinsGlobal extends FillWaterskins {

    public FillWaterskinsGlobal() { super(true); }
    public FillWaterskinsGlobal(Map<String, Object> settings) { super(true); }
}
