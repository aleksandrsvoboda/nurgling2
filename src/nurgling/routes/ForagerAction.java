package nurgling.routes;

import nurgling.tools.NAlias;
import org.json.JSONObject;

public class ForagerAction {
    
    public enum ActionType {
        PICK,
        FLOWER_ACTION,
        RIGHT_CLICK,
        CHAT_NOTIFY
    }
    
    public enum NotifyTarget {
        DISCORD,
        CHAT
    }
    
    public String targetObjectPattern;
    public ActionType actionType;
    public String actionName;  // For FLOWER_ACTION

    // For CHAT_NOTIFY
    public NotifyTarget notifyTarget;
    public String chatChannelName;  // For CHAT notify

    // Bookkeeping for the item-driven pickup-list widget only - never consulted by the bot's own
    // matching/action logic above. Null for anything authored the old free-text way (including
    // manually-typed "custom" entries added through the same widget). Lets the widget redraw its
    // icon list from a saved profile without needing to re-derive which item an entry came from.
    public String sourceItemName;
    public String sourceItemResource;

    // Stop foraging this item once its current inventory count reaches this; -1 = no cap.
    // Same popup/badge mechanism as IconItem's Threshold option (see TaggableItemContainer).
    public int maintainQuantity = -1;

    public ForagerAction(String targetObjectPattern, ActionType actionType, String actionName,
                         NotifyTarget notifyTarget, String chatChannelName) {
        this.targetObjectPattern = targetObjectPattern;
        this.actionType = actionType;
        this.actionName = actionName;
        this.notifyTarget = notifyTarget;
        this.chatChannelName = chatChannelName;
    }
    
    public ForagerAction(String targetObjectPattern, ActionType actionType, String actionName) {
        this(targetObjectPattern, actionType, actionName, null, null);
    }
    
    public ForagerAction(String targetObjectPattern, ActionType actionType) {
        this(targetObjectPattern, actionType, null, null, null);
    }
    
    public ForagerAction(JSONObject json) {
        this.targetObjectPattern = json.getString("targetObjectPattern");
        this.actionType = ActionType.valueOf(json.getString("actionType"));
        if (json.has("actionName")) {
            this.actionName = json.getString("actionName");
        }
        if (json.has("notifyTarget")) {
            this.notifyTarget = NotifyTarget.valueOf(json.getString("notifyTarget"));
        }
        if (json.has("chatChannelName")) {
            this.chatChannelName = json.getString("chatChannelName");
        }
        if (json.has("sourceItemName")) {
            this.sourceItemName = json.getString("sourceItemName");
        }
        if (json.has("sourceItemResource")) {
            this.sourceItemResource = json.getString("sourceItemResource");
        }
        if (json.has("maintainQuantity")) {
            this.maintainQuantity = json.getInt("maintainQuantity");
        }
    }

    public ForagerAction(java.util.HashMap<String, Object> map) {
        this.targetObjectPattern = (String) map.get("targetObjectPattern");
        this.actionType = ActionType.valueOf((String) map.get("actionType"));
        if (map.containsKey("actionName")) {
            this.actionName = (String) map.get("actionName");
        }
        if (map.containsKey("notifyTarget")) {
            this.notifyTarget = NotifyTarget.valueOf((String) map.get("notifyTarget"));
        }
        if (map.containsKey("chatChannelName")) {
            this.chatChannelName = (String) map.get("chatChannelName");
        }
        if (map.containsKey("sourceItemName")) {
            this.sourceItemName = (String) map.get("sourceItemName");
        }
        if (map.containsKey("sourceItemResource")) {
            this.sourceItemResource = (String) map.get("sourceItemResource");
        }
        if (map.containsKey("maintainQuantity")) {
            this.maintainQuantity = ((Number) map.get("maintainQuantity")).intValue();
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("targetObjectPattern", targetObjectPattern);
        json.put("actionType", actionType.name());
        if (actionName != null) {
            json.put("actionName", actionName);
        }
        if (notifyTarget != null) {
            json.put("notifyTarget", notifyTarget.name());
        }
        if (chatChannelName != null) {
            json.put("chatChannelName", chatChannelName);
        }
        if (sourceItemName != null) {
            json.put("sourceItemName", sourceItemName);
        }
        if (sourceItemResource != null) {
            json.put("sourceItemResource", sourceItemResource);
        }
        if (maintainQuantity >= 0) {
            json.put("maintainQuantity", maintainQuantity);
        }
        return json;
    }

    // Lazily built and cached - toNAlias() is called once per configured action on every
    // findNearestActionableGob scan (every detour hop, several times/sec while actively
    // foraging), re-parsing (String.split + NAlias construction) the same immutable result each
    // time otherwise. Safe only because targetObjectPattern is never reassigned after
    // construction anywhere in this codebase (confirmed - unlike actionName, which
    // Forager.confirmActionName() does mutate in place, so that one isn't cached the same way).
    private transient NAlias cachedAlias;

    /**
     * {@link #targetObjectPattern} as an NAlias, matching on every comma-separated name in it
     * (a plain single name, the common case, just becomes a single-key NAlias as before). Lets
     * an entry resolved from a dropped item that maps to more than one gob resource (e.g. a
     * couple of near-identical tree variants sharing one item) match all of them.
     */
    public NAlias toNAlias() {
        if (cachedAlias == null) {
            cachedAlias = new NAlias(splitPattern(targetObjectPattern));
        }
        return cachedAlias;
    }

    /**
     * {@link #actionName} as an ordered list of candidate flower-menu option strings, matching on
     * every comma-separated candidate in priority order (a plain single confirmed string, e.g.
     * one the user typed via Edit Pattern, just becomes a list of one). Lets an auto-guessed entry
     * (several plausible "Pick X"/"Take X" phrasings - see ForagerPickupContainer) try each in
     * turn against the real flower menu rather than committing to one guess up front.
     */
    public java.util.List<String> toActionNameCandidates() {
        return java.util.Arrays.asList(splitPattern(actionName));
    }

    private static String[] splitPattern(String pattern) {
        String[] parts = pattern.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    @Override
    public String toString() {
        return String.format("ForagerAction[%s, %s%s]", 
            targetObjectPattern, 
            actionType,
            actionName != null ? ", " + actionName : "");
    }
}
