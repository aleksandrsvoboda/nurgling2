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
    public String tag;

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
        if (json.has("tag")) {
            this.tag = json.getString("tag");
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
        if (map.containsKey("tag")) {
            this.tag = (String) map.get("tag");
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
        if (tag != null) {
            json.put("tag", tag);
        }
        return json;
    }
    
    /**
     * {@link #targetObjectPattern} as an NAlias, matching on every comma-separated name in it
     * (a plain single name, the common case, just becomes a single-key NAlias as before). Lets
     * an entry resolved from a dropped item that maps to more than one gob resource (e.g. a
     * couple of near-identical tree variants sharing one item) match all of them.
     */
    public NAlias toNAlias() {
        String[] parts = targetObjectPattern.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return new NAlias(parts);
    }

    @Override
    public String toString() {
        return String.format("ForagerAction[%s, %s%s]", 
            targetObjectPattern, 
            actionType,
            actionName != null ? ", " + actionName : "");
    }
}
