package nurgling.widgets.nsettings;

import haven.*;
import haven.Button;
import haven.Label;
import nurgling.NConfig;
import nurgling.conf.NCombatData;
import nurgling.widgets.NColorWidget;

import java.awt.Color;

/**
 * Settings for the two combat HUD panels ("FightBuffsInfo" and "FightActions").
 * Everything here is read live by {@link haven.NFightsess} on each frame, so changes
 * take effect without restarting a fight.
 */
public class CombatSettings extends Panel {

    private final CheckBox openingsAsLetters;
    private final CheckBox showHotkeys;
    private final CheckBox showDamage;
    private final CheckBox singleRow;
    private final CheckBox showAgility;
    private final CheckBox showHealthBar;
    private final CheckBox showStaminaBar;
    private final CheckBox includeHHP;

    private final NColorWidget colOffbalance;
    private final NColorWidget colReeling;
    private final NColorWidget colCornered;
    private final NColorWidget colDizzy;
    private final NColorWidget colMyIP;
    private final NColorWidget colEnemyIP;

    public CombatSettings() {
        super("Combat HUD");

        int margin = UI.scale(10);
        int col2 = UI.scale(300);
        int line = UI.scale(24);
        int y = UI.scale(40);

        add(new Label("● Openings panel"), new Coord(margin, y));
        y += UI.scale(22);
        openingsAsLetters = add(new CheckBox("Show openings as coloured letters"), new Coord(margin, y));
        openingsAsLetters.tooltip = Text.render("Draw G / Y / R / B glyphs instead of a solid colour block.").tex();
        y += line;
        showAgility = add(new CheckBox("Show estimated agility"), new Coord(margin, y));
        showAgility.tooltip = Text.render("Ratio between the observed and expected attack cooldown. Above 1.0x means the opponent out-agiles you.").tex();
        y += line;
        showHealthBar = add(new CheckBox("Show health bar"), new Coord(margin, y));
        y += line;
        showStaminaBar = add(new CheckBox("Show stamina bar"), new Coord(margin, y));
        y += line;
        includeHHP = add(new CheckBox("Include HHP% in the health bar"), new Coord(margin, y));
        y += line + UI.scale(14);

        add(new Label("● Moves panel"), new Coord(margin, y));
        y += UI.scale(22);
        showHotkeys = add(new CheckBox("Show move hotkeys"), new Coord(margin, y));
        y += line;
        showDamage = add(new CheckBox("Show damage prediction"), new Coord(margin, y));
        showDamage.tooltip = Text.render("Expected damage against the opponent's current openings, using your equipped weapon and strength.").tex();
        y += line;
        singleRow = add(new CheckBox("Single row for combat moves"), new Coord(margin, y));
        singleRow.tooltip = Text.render("Lay all ten moves out on one row instead of two rows of five. The panel resizes to match.").tex();
        y += line + UI.scale(14);

        int cy = UI.scale(40);
        add(new Label("● Opening colours"), new Coord(col2, cy));
        cy += UI.scale(22);
        colOffbalance = add(new NColorWidget("Off-balance"), new Coord(col2, cy));
        cy += UI.scale(32);
        colReeling = add(new NColorWidget("Reeling"), new Coord(col2, cy));
        cy += UI.scale(32);
        colCornered = add(new NColorWidget("Cornered"), new Coord(col2, cy));
        cy += UI.scale(32);
        colDizzy = add(new NColorWidget("Dizzy"), new Coord(col2, cy));
        cy += UI.scale(32) + UI.scale(14);

        add(new Label("● IP colours"), new Coord(col2, cy));
        cy += UI.scale(22);
        colMyIP = add(new NColorWidget("Your IP"), new Coord(col2, cy));
        cy += UI.scale(32);
        colEnemyIP = add(new NColorWidget("Enemy IP"), new Coord(col2, cy));
        cy += UI.scale(32) + UI.scale(8);

        add(new Button(UI.scale(120), "Reset colours") {
            @Override
            public void click() {
                colOffbalance.color = NCombatData.DEF_GREEN;
                colReeling.color = NCombatData.DEF_YELLOW;
                colCornered.color = NCombatData.DEF_RED;
                colDizzy.color = NCombatData.DEF_BLUE;
                colMyIP.color = NCombatData.DEF_MYIP;
                colEnemyIP.color = NCombatData.DEF_ENEMYIP;
            }
        }, new Coord(col2, cy));

        load();
    }

    @Override
    public void load() {
        openingsAsLetters.a = bool(NConfig.Key.combatShowOpeningsAsLetters, false);
        showHotkeys.a = bool(NConfig.Key.combatShowHotkeys, true);
        showDamage.a = bool(NConfig.Key.combatShowDamagePrediction, true);
        singleRow.a = bool(NConfig.Key.combatSingleRowMoves, false);
        showAgility.a = bool(NConfig.Key.combatShowEstimatedAgility, true);
        showHealthBar.a = bool(NConfig.Key.combatShowHealthBar, true);
        showStaminaBar.a = bool(NConfig.Key.combatShowStaminaBar, true);
        includeHHP.a = bool(NConfig.Key.combatIncludeHHPText, false);

        colOffbalance.color = NConfig.getColor(NConfig.Key.combatColorOffbalance, NCombatData.DEF_GREEN);
        colReeling.color = NConfig.getColor(NConfig.Key.combatColorReeling, NCombatData.DEF_YELLOW);
        colCornered.color = NConfig.getColor(NConfig.Key.combatColorCornered, NCombatData.DEF_RED);
        colDizzy.color = NConfig.getColor(NConfig.Key.combatColorDizzy, NCombatData.DEF_BLUE);
        colMyIP.color = NConfig.getColor(NConfig.Key.combatColorMyIP, NCombatData.DEF_MYIP);
        colEnemyIP.color = NConfig.getColor(NConfig.Key.combatColorEnemyIP, NCombatData.DEF_ENEMYIP);
    }

    @Override
    public void save() {
        NConfig.set(NConfig.Key.combatShowOpeningsAsLetters, openingsAsLetters.a);
        NConfig.set(NConfig.Key.combatShowHotkeys, showHotkeys.a);
        NConfig.set(NConfig.Key.combatShowDamagePrediction, showDamage.a);
        NConfig.set(NConfig.Key.combatSingleRowMoves, singleRow.a);
        NConfig.set(NConfig.Key.combatShowEstimatedAgility, showAgility.a);
        NConfig.set(NConfig.Key.combatShowHealthBar, showHealthBar.a);
        NConfig.set(NConfig.Key.combatShowStaminaBar, showStaminaBar.a);
        NConfig.set(NConfig.Key.combatIncludeHHPText, includeHHP.a);

        NConfig.set(NConfig.Key.combatColorOffbalance, colOffbalance.color);
        NConfig.set(NConfig.Key.combatColorReeling, colReeling.color);
        NConfig.set(NConfig.Key.combatColorCornered, colCornered.color);
        NConfig.set(NConfig.Key.combatColorDizzy, colDizzy.color);
        NConfig.set(NConfig.Key.combatColorMyIP, colMyIP.color);
        NConfig.set(NConfig.Key.combatColorEnemyIP, colEnemyIP.color);

        NConfig.needUpdate();
    }

    private static boolean bool(NConfig.Key key, boolean def) {
        Object v = NConfig.get(key);
        return((v instanceof Boolean) ? (Boolean)v : def);
    }
}
