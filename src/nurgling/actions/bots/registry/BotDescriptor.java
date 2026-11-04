package nurgling.actions.bots.registry;

import nurgling.actions.Action;
import nurgling.i18n.L10n;

import java.util.Map;

public class BotDescriptor {
    final static String ICON_BASE_DIR = "nurgling/bots/icons/";

    public final String id;
    public final BotType type;
    public final String titleKey;      // Localization key for title
    public final String descriptionKey; // Localization key for description
    public final boolean allowedAsStepInScenario;
    public final boolean allowedAsItemInBotMenu;
    public final Class<? extends Action> clazz;
    public final String iconPath;
    public final boolean disStacks;
    public final Map<String, Object> defaultSettings;
    // Independent of allowedAsStepInScenario - isolates route-waypoint-only utility bots (e.g. GateBot) from the general Scenario step picker.
    public final boolean allowedAsForagerStep;
    // Bot menu placement inside its folder; set once by BotRegistry while it registers bots.
    String group = null;
    // Settings the bot reads from its (Map) constructor; described for scripts, scenarios and the docs.
    private java.util.List<ParamSpec> params = java.util.Collections.emptyList();
    // True for bots that stop and ask the player (pick an area, confirm a dialog); they cannot run on headless sessions.
    private boolean interactive = false;

    /** Declares the bot's settings. Returns this, for use inside BotRegistry's list. */
    public BotDescriptor withParams(ParamSpec... specs) {
        this.params = java.util.Collections.unmodifiableList(java.util.Arrays.asList(specs));
        return this;
    }

    // Button images (up, down, hover) of a user script's own icon; null = the resource at iconPath.
    private java.awt.image.BufferedImage[] customIcon = null;

    /** Gives a dynamically registered bot (a user script) its own button images: up, down, hover. */
    public BotDescriptor withIcon(java.awt.image.BufferedImage[] upDownHover) {
        this.customIcon = upDownHover;
        return this;
    }

    /**
     * The bot's button image for a state ("u" up, "d" down, "h" hover): a script's own icon if it
     * has one, else the icon resource. Use this instead of loading getIconPath() yourself.
     */
    public java.awt.image.BufferedImage icon(String state) {
        if (customIcon != null)
            return customIcon[state.equals("d") ? 1 : state.equals("h") ? 2 : 0];
        return haven.Resource.loadsimg(getIconPath(state));
    }

    /** Puts a dynamically registered bot (a user script) into a bot menu group; see BotRegistry.replaceDynamic. */
    public BotDescriptor inGroup(String key) {
        this.group = key;
        return this;
    }

    /** Marks the bot as one that asks the player for input while it runs. */
    public BotDescriptor interactive() {
        this.interactive = true;
        return this;
    }

    public java.util.List<ParamSpec> params() {
        return params;
    }

    public boolean isInteractive() {
        return interactive;
    }

    public enum BotType {
        RESOURCES,
        PRODUCTIONS,
        BATTLE,
        FARMING,
        FARMING_QUALITY,
        ANIMALS,
        UTILS,
        BUILD,
        TOOLS,
        /** User scripts from the scripts folder (see nurgling.scripting.ScriptCatalog). */
        SCRIPTS
    }

    public BotDescriptor(String id, BotType type, String titleKey, String descriptionKey, boolean allowedAsStepInScenario, boolean allowedAsItemInBotMenu, Class<? extends Action> clazz, String iconPath, boolean disStacks) {
        this(id, type, titleKey, descriptionKey, allowedAsStepInScenario, allowedAsItemInBotMenu, clazz, iconPath, disStacks, Map.of(), false);
    }

    public BotDescriptor(String id, BotType type, String titleKey, String descriptionKey, boolean allowedAsStepInScenario, boolean allowedAsItemInBotMenu, Class<? extends Action> clazz, String iconPath, boolean disStacks, Map<String, Object> defaultSettings) {
        this(id, type, titleKey, descriptionKey, allowedAsStepInScenario, allowedAsItemInBotMenu, clazz, iconPath, disStacks, defaultSettings, false);
    }

    public BotDescriptor(String id, BotType type, String titleKey, String descriptionKey, boolean allowedAsStepInScenario, boolean allowedAsItemInBotMenu, Class<? extends Action> clazz, String iconPath, boolean disStacks, Map<String, Object> defaultSettings, boolean allowedAsForagerStep) {
        this.id = id;
        this.type = type;
        this.titleKey = titleKey;
        this.descriptionKey = descriptionKey;
        this.allowedAsStepInScenario = allowedAsStepInScenario;
        this.allowedAsItemInBotMenu = allowedAsItemInBotMenu;
        this.clazz = clazz;
        this.iconPath = iconPath;
        this.disStacks = disStacks;
        this.defaultSettings = defaultSettings;
        this.allowedAsForagerStep = allowedAsForagerStep;
    }

    /**
     * Get localized display name.
     * If titleKey starts with "bot." it's treated as a localization key.
     * Otherwise returns the raw value (for backwards compatibility).
     */
    public String getDisplayName() {
        if (titleKey != null && titleKey.startsWith("bot.")) {
            return L10n.get(titleKey);
        }
        return titleKey;
    }

    /**
     * Get localized description.
     * If descriptionKey starts with "bot." it's treated as a localization key.
     * Otherwise returns the raw value (for backwards compatibility).
     */
    public String getDescription() {
        if (descriptionKey != null && descriptionKey.startsWith("bot.")) {
            return L10n.get(descriptionKey);
        }
        return descriptionKey;
    }

    /**
     * @deprecated Use getDisplayName() instead for localized text
     */
    @Deprecated
    public String displayName() {
        return getDisplayName();
    }

    public Action instantiate(Map<String, Object> settings) {
        try {
            Map<String, Object> merged = mergeSettings(settings);
            try {
                return clazz.getDeclaredConstructor(Map.class).newInstance(merged);
            } catch (NoSuchMethodException e) {
                return clazz.getDeclaredConstructor().newInstance();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> mergeSettings(Map<String, Object> callerSettings) {
        if (defaultSettings.isEmpty()) return callerSettings;
        if (callerSettings == null || callerSettings.isEmpty()) return defaultSettings;
        java.util.HashMap<String, Object> merged = new java.util.HashMap<>(defaultSettings);
        merged.putAll(callerSettings);
        return merged;
    }

    /** Localization key suffix of this bot's group in the bot menu, or null for the folder's "Other" group. */
    public String group() {
        return group;
    }

    public String getUpIconPath() {
        return getIconPath("u");
    }

    public String getDownIconPath() {
        return getIconPath("d");
    }

    public String getHoverIconPath() {
        return getIconPath("h");
    }

    public String getIconPath(String state) {
        return ICON_BASE_DIR + iconPath + "/" + state;
    }
}
