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

    // Bookkeeping for the item-driven pickup-list widget only - lets it redraw its icon list from a saved profile without re-deriving which item an entry came from.
    public String sourceItemName;
    public String sourceItemResource;

    // Stop foraging this item once its current inventory count reaches this; -1 = no cap.
    public int maintainQuantity = -1;

    // Lower number = checked first when picking what to forage next; -1 = unset, checked last.
    public int priority = -1;

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
        if (json.has("priority")) {
            this.priority = json.getInt("priority");
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
        if (map.containsKey("priority")) {
            this.priority = ((Number) map.get("priority")).intValue();
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
        if (priority >= 0) {
            json.put("priority", priority);
        }
        return json;
    }

    // Cached since toNAlias() is re-parsed on every findNearestActionableGob scan; safe since targetObjectPattern is never reassigned after construction (unlike actionName).
    private transient NAlias cachedAlias;

    /** {@link #targetObjectPattern} as an NAlias matching every comma-separated name in it, so a dropped item mapping to more than one gob resource matches all of them. */
    public NAlias toNAlias() {
        if (cachedAlias == null) {
            cachedAlias = new NAlias(splitPattern(targetObjectPattern));
        }
        return cachedAlias;
    }

    /** {@link #actionName} as an ordered list of candidate flower-menu strings, so an auto-guessed entry can try each in turn against the real menu. */
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
