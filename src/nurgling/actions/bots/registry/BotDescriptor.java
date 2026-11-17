package nurgling.actions.bots.registry;

import haven.Resource;
import haven.Widget;
import nurgling.actions.Action;
import nurgling.i18n.L10n;
import nurgling.scenarios.BotStep;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.function.Function;

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
    /* Up, down and hover images given by code (a plugin brings its own art); null means the iconPath resources. */
    private volatile BufferedImage[] images = null;
    /* Builds the scenario step's settings widget; null means StepSettingsPanel's built-in editors. */
    private volatile Function<BotStep, Widget> stepSettings = null;

    public enum BotType {
        RESOURCES,
        PRODUCTIONS,
        BATTLE,
        FARMING,
        FARMING_QUALITY,
        ANIMALS,
        UTILS,
        BUILD,
        TOOLS
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

    /** Uses these images instead of the iconPath resources, for bots whose art isn't in the client. */
    public BotDescriptor images(BufferedImage up, BufferedImage down, BufferedImage hover) {
        this.images = new BufferedImage[] {up, down, hover};
        return this;
    }

    /** The image for state "u", "d" or "h": the given images, else the iconPath resource. */
    public BufferedImage icon(String state) {
        BufferedImage[] imgs = images;
        if (imgs != null)
            return imgs["d".equals(state) ? 1 : "h".equals(state) ? 2 : 0];
        return Resource.loadsimg(getIconPath(state));
    }

    /** Gives the step its own settings widget in the scenario editor; it edits the step it is handed. */
    public BotDescriptor stepSettings(Function<BotStep, Widget> editor) {
        this.stepSettings = editor;
        return this;
    }

    /** The step's own settings widget builder, or null. */
    public Function<BotStep, Widget> stepSettings() {
        return stepSettings;
    }
}
