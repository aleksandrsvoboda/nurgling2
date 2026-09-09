package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NForagerProp;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerPath;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Bot-launch window: picks a Preset by name and starts it - full preset editing lives in ForagerSettingsPanel. Implements Checkable (not PathBotWindow) since callers block on it via WaitCheckable. */
public class Forager extends Window implements Checkable {

    private Dropbox<String> presetDropbox;
    private CheckBox ignoreMaintainCheck;
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

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null && prop != null && ignoreMaintainCheck != null) {
                    NForagerProp.PresetData pd = prop.presets.get(item);
                    ignoreMaintainCheck.a = pd != null && pd.ignoreMaintainLimits;
                }
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(ignoreMaintainCheck = new CheckBox(L10n.get("forager.ignore_maintain")), prev.pos("bl").add(UI.scale(0, 10)));
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
        // Gate on waypoint count, not section count - sections are segment-relative and regenerate once the bot's on the right segment.
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
        preset.ignoreMaintainLimits = ignoreMaintainCheck.a;

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
