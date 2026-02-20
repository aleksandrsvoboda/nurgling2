package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.actions.bots.EquipmentBot;
import nurgling.equipment.EquipmentPreset;
import nurgling.equipment.EquipmentPresetIcons;
import nurgling.sessions.BotExecutor;

public class NEquipmentPresetButton extends IButton {
    private final EquipmentPreset preset;

    public NEquipmentPresetButton(EquipmentPreset preset) {
        super(
            EquipmentPresetIcons.loadPresetIconUp(preset),
            EquipmentPresetIcons.loadPresetIconDown(preset),
            EquipmentPresetIcons.loadPresetIconHover(preset)
        );
        this.preset = preset;
        setupButton();
    }

    private void setupButton() {
        this.action(() -> executePreset());
    }

    private void executePreset() {
        if (preset != null) {
            BotExecutor.runAsync("EquipmentBot-" + preset.getName(), new EquipmentBot(preset));
        }
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        if (preset != null) {
            return "Equip: " + preset.getName();
        }
        return super.tooltip(c, prev);
    }

    public EquipmentPreset getPreset() {
        return preset;
    }

    public String getDisplayName() {
        return preset != null ? preset.getName() : "Unknown Preset";
    }
}
