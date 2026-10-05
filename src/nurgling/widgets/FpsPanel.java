package nurgling.widgets;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.render.SceneDebug;
import nurgling.widgets.nsettings.CollapsibleSection;
import static nurgling.render.FpsGraph.W;
import static nurgling.render.FpsGraph.H;
import nurgling.render.FpsGraphTexture;
import java.awt.event.KeyEvent;

/** Cached diagnostics HUD: sampling allocates nothing; drawing refreshes at 10 Hz. */
public class FpsPanel extends haven.Window {
    public static final KeyBinding toggle = KeyBinding.get("fps-graph", KeyMatch.forcode(KeyEvent.VK_F10, KeyMatch.C));
    private static boolean enabled = Boolean.getBoolean("haven.fpsgraph") || Utils.getprefb("fpsgraph", false);
    private final FpsGraphTexture graph = new FpsGraphTexture();
    private final CollapsibleSection debug;
    private final Label timeLabel;
    private final HSlider timeSlider;
    private String timeText;

    public static boolean enabled() { return(enabled); }
    public static void enabled(boolean value) {
        enabled = value;
        Utils.setprefb("fpsgraph", value);
    }

    public FpsPanel() {
        super(UI.scale(new Coord(W, H)), L10n.get("fps.title"));
        add(new Widget(UI.scale(new Coord(W, H))) {
            public void draw(GOut g) { drawGraph(g); }
        }, Coord.z);
        debug = add(new CollapsibleSection("Debug", UI.scale(W), false), UI.scale(0, H + 6));
        Widget content = debug.content;
        Widget hint = content.add(new Label(L10n.get("fps.debug.hint")), UI.scale(10, 8));
        timeLabel = content.add(new Label(L10n.get("fps.debug.unavailable")), new Coord(UI.scale(10), hint.pos("bl").y + UI.scale(8)));
        timeSlider = content.add(new HSlider(UI.scale(W - 20), 0, 1439, 720) {
            public void changed() {
                MapView map = map();
                if(map != null) map.sceneDebug.time(val);
                refreshTime();
            }
        }, new Coord(UI.scale(10), timeLabel.pos("bl").y + UI.scale(5)));
        Widget rain = content.add(new CheckBox(L10n.get("fps.debug.rain"))
                .state(() -> map() != null && map().sceneDebug.rain())
                .set(value -> { if(map() != null) map().sceneDebug.rain(value); }),
                new Coord(UI.scale(10), timeSlider.pos("bl").y + UI.scale(8)));
        Widget heavyRain = content.add(new CheckBox(L10n.get("fps.debug.heavyrain"))
                .state(() -> map() != null && map().sceneDebug.heavyRain())
                .set(value -> { if(map() != null) map().sceneDebug.heavyRain(value); }),
                new Coord(UI.scale(10), rain.pos("bl").y + UI.scale(6)));
        Widget snow = content.add(new CheckBox(L10n.get("fps.debug.snow"))
                .state(() -> map() != null && map().sceneDebug.snow())
                .set(value -> { if(map() != null) map().sceneDebug.snow(value); }),
                new Coord(UI.scale(10), heavyRain.pos("bl").y + UI.scale(6)));
        content.add(new Button(UI.scale(220), L10n.get("fps.debug.reset"), () -> {
            if(map() != null) map().sceneDebug.reset();
            refreshTime();
        }), new Coord(UI.scale(10), snow.pos("bl").y + UI.scale(8)));
        debug.pack();
        debug.setOnToggle(() -> { refreshTime(); fit(); });
        fit();
        hide();
    }

    private MapView map() {
        GameUI game = getparent(GameUI.class);
        return game == null ? null : game.map;
    }

    private void fit() {
        resize(new Coord(UI.scale(W), debug.pos("bl").y + UI.scale(8)));
        keepOnScreen();
    }

    private void refreshTime() {
        MapView map = map();
        String text = L10n.get("fps.debug.unavailable");
        if(map != null) {
            SceneDebug preview = map.sceneDebug;
            Astronomy ast = map.glob.ast;
            int minutes = preview.hasTime() ? preview.minutes() : ast == null ? 720 : ast.hh * 60 + ast.mm;
            timeSlider.val = Math.max(0, Math.min(1439, minutes));
            text = String.format(L10n.get(preview.hasTime() ? "fps.debug.time" : "fps.debug.live"), minutes / 60, minutes % 60);
        }
        if(!text.equals(timeText)) {
            timeLabel.settext(timeText = text);
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(visible && debug.isExpanded()) refreshTime();
    }

    private void drawGraph(GOut g) {
        try(nurgling.diagnostics.MovementTrace.Stage stage=nurgling.diagnostics.MovementTrace.stage(ui,"fps-panel")) {
            String backend = ui.getenv() instanceof haven.render.vk.VkEnvironment ? "Vulkan" : "OpenGL";
            graph.update(g.out,ui.frameHistory,Utils.rtime(),backend,UI.scale(new Coord(W,H)));
            graph.draw(g);
        }
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if(msg.equals("close")) { enabled(false); hide(); }
        else super.wdgmsg(msg, args);
    }

    @Override
    public void dispose() {
        graph.dispose();
        super.dispose();
    }

    public void keepOnScreen() {
        if(parent != null)
            move(new Coord(Math.max(0, Math.min(c.x, parent.sz.x - sz.x)),
                           Math.max(0, Math.min(c.y, parent.sz.y - sz.y))));
    }

}
