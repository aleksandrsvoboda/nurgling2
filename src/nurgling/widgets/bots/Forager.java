package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerPath;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Forager's bot-launch window - a single Preset selector + Start button. Everything a preset
 * bundles (which Actions Profile/Route/Guarding Profile to run with, the start area, finish/
 * full-inventory reactions) is edited in Forager Settings' Presets section (see
 * nurgling.widgets.nsettings.ForagerSettingsPanel) - this window only picks one preset by name
 * and starts the bot with it, the same selection-only role Actions/Guarding Profiles already
 * moved into earlier in this same refactor.
 * <p>
 * No longer extends PathBotWindow - none of its preset/path CRUD, record button, or per-preset
 * safety dropdowns apply any more now that a preset bundles all of that itself. Still directly
 * implements {@link Checkable}, the one thing PathBotWindow provided that something outside
 * this class genuinely depends on: nurgling.tasks.WaitCheckable, which every bot-launcher
 * action (including actions/bots/Forager.java's own interactive-mode wait) blocks on.
 */
public class Forager extends Window implements Checkable {

    private Dropbox<String> presetDropbox;
    public NForagerProp prop;
    public boolean cancelled = false;
    private boolean ready = false;

    public Forager() {
        super(new Coord(UI.scale(260), UI.scale(90)), L10n.get("forager.wnd_title"));

        prop = NForagerProp.get(NUtils.getUI().sessInfo);
        if (prop == null) {
            prop = new NForagerProp("", "");
        }
        if (prop.presets.isEmpty()) {
            prop.presets.put("Default", new NForagerProp.PresetData());
        }
        if (prop.currentPreset == null || !prop.presets.containsKey(prop.currentPreset)) {
            prop.currentPreset = prop.presets.keySet().iterator().next();
        }

        Widget prev = add(new Label(L10n.get("forager.preset")), new Coord(0, 0));
        prev = add(presetDropbox = new Dropbox<String>(UI.scale(200), 8, UI.scale(16)) {
            private List<String> names() {
                return new ArrayList<>(new TreeSet<>(prop.presets.keySet()));
            }

            @Override
            protected String listitem(int i) {
                return names().get(i);
            }

            @Override
            protected int listitems() {
                return names().size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));
        presetDropbox.change(prop.currentPreset);

        add(new Button(UI.scale(150), "Start") {
            @Override
            public void click() {
                super.click();
                handleStartBot();
            }
        }, prev.pos("bl").add(UI.scale(0, 15)));

        pack();
    }

    private void handleStartBot() {
        if (presetDropbox.sel == null) {
            NUtils.getGameUI().error(L10n.get("forager.no_paths"));
            return;
        }
        prop.currentPreset = presetDropbox.sel;

        NForagerProp.PresetData preset = prop.presets.get(prop.currentPreset);
        if (preset == null || preset.pathFile == null || preset.pathFile.isEmpty()) {
            NUtils.getGameUI().error("No valid path loaded");
            return;
        }
        // Same "gate on waypoint count, not section count" reasoning PathBotWindow's own
        // handleStartBot() used to: sections are generated from the player's current map
        // segment at load time (ForagerWaypoint/sessloc are segment-relative), so a perfectly
        // good path loaded from a different segment than the player's current one legitimately
        // comes back with 0 sections despite having real waypoints. The bot itself regenerates
        // sections once it's actually on the right segment (see actions/bots/Forager.java's
        // run()).
        ForagerPath path;
        try {
            path = ForagerPath.load(preset.pathFile);
        } catch (Exception e) {
            NUtils.getGameUI().error("No valid path loaded");
            return;
        }
        if (path.waypoints == null || path.waypoints.size() < 2) {
            NUtils.getGameUI().error("No valid path loaded");
            return;
        }
        preset.foragerPath = path;

        NForagerProp.set(prop);
        ready = true;
    }

    @Override
    public boolean check() {
        return ready;
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            cancelled = true;
            ready = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }
}
