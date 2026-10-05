package haven;

import haven.iosys.tk.*;
import nurgling.NConfig;
import nurgling.widgets.NMapWnd;
import nurgling.widgets.NMapIcon;
import java.lang.reflect.Field;

/** Actual map controls: geometry, selected marker spacing, compact mode and GPU preview. */
public class MapWindowThemeTest {
    static Widget field(Object o, Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); return (Widget)f.get(o);
    }
    static void check(NMapWnd w, boolean compact) throws Exception {
        Widget search = field(w, NMapWnd.class, "markerSearchField");
        Coord origin = w.view.parentpos(w), end = origin.add(w.view.sz);
        Coord searchPos = search.parentpos(w);
        UiThemeTest.check(searchPos.x + search.sz.x == end.x - UI.scale(5), "Search right inset");
        UiThemeTest.check(searchPos.y + search.sz.y <= end.y - UI.scale(5), "Search bottom outside map");
        Widget toolbar = field(w, MapWnd.class, "toolbar");
        Coord toolbarPos = toolbar.parentpos(w);
        UiThemeTest.check(toolbarPos.y + toolbar.sz.y == end.y - UI.scale(5), "Toolbar bottom outside map");
        UiThemeTest.check(search.c.x >= toolbar.c.x + toolbar.sz.x || search.c.y + search.sz.y + UI.scale(5) <= toolbar.c.y, "Search overlaps toolbar");
        int count = 0;
        for(Widget b = toolbar.child; b != null; b = b.next) {
            UiThemeTest.check(b.sz.equals(UI.scale(24,24)), "Toolbar icon not square"); count++;
        }
        UiThemeTest.check(count == 5, "Wrong toolbar button count");
        for(String key : new String[]{"mapToolsBtn", "fishBtn", "treeBtn", "oreBtn", "gemBtn", "stoneBtn", "forageBtn", "vectorClearBtn"}) {
            Widget b = field(w, NMapWnd.class, key);
            Coord pos = b.parentpos(w);
            UiThemeTest.check(b.sz.equals(UI.scale(24,24)) && pos.x >= origin.x && pos.y >= origin.y && pos.x + b.sz.x <= end.x, "Overlay icon outside map: " + key);
        }
        if(!compact) {
            Widget check = field(w, MapWnd.class, "onmapbtn"), remove = field(w, MapWnd.class, "mremove");
            Widget colors = field(w, MapWnd.class, "colsel"), name = field(w.tool, MapWnd.Toolbox.class, "namesel");
            Widget list = field(w.tool, MapWnd.Toolbox.class, "listf");
            UiThemeTest.check(remove.c.y - (check.c.y + check.sz.y) == UI.scale(5), "Display in world / Remove overlap");
            UiThemeTest.check(check.c.y - (colors.c.y + colors.sz.y) == UI.scale(5), "Palette/checkbox gap");
            UiThemeTest.check(list.c.y + list.sz.y + UI.scale(10) == name.c.y, "List overlaps editor");
        }
    }
    public static void main(String[] args) throws Exception {
        NConfig.getGlobalInstance();
        Toolkit tk = Toolkit.toolkits().get("vulkan").open(); Windeye win = tk.window();
        try {
            win.sizing(new Windeye.Sizing().fixsize(UiThemeTest.SIZE)).show(true);
            UI ui = new UI(win, new Audio.Root(haven.iosys.audio.DummyAudio.instance), UiThemeTest.SIZE, null); ui.env = win.env();
            MapFile file = new MapFile(new ResCache.TestCache(), "map-ui-test");
            MapView mv = new MapView(Coord.of(64,64), new Glob(null), Coord2d.z, -1);
            NMapWnd w = ui.root.add(new NMapWnd(file, mv, UI.scale(700,480), "Map"), UI.scale(10,25));
            w.compact(false);
            MapFile.PMarker marker = new MapFile.PMarker(file, 1, Coord.z, "New marker", BuddyWnd.gc[1], false);
            w.tool.list.change2(new MapWnd.ListMarker(marker));
            w.resize(UI.scale(700,480));
            field(w,NMapWnd.class,"dbExportBtn").hide(); field(w,NMapWnd.class,"dbImportBtn").hide(); field(w,NMapWnd.class,"peerRoster").hide();
            check(w,false);
            Field animation = Window.class.getDeclaredField("anim"); animation.setAccessible(true); animation.set(w, null);
            UiThemeTest.capture(win,w,"map-layout-vulkan");
            w.resize(UI.scale(380,360)); check(w,false);
            w.compact(true); w.resize(UI.scale(160,150)); check(w,true);
            NMapIcon toggle = new NMapIcon("prov");
            toggle.mousedown(new Widget.MouseDownEvent(Coord.of(1,1),1));
            UiThemeTest.check(toggle.state(), "Square corner not clickable");
            toggle.mousedown(new Widget.MouseDownEvent(Coord.of(1,1),1));
            UiThemeTest.check(!toggle.state(), "Toggle cannot turn off");
            System.out.println("PASS: map marker spacing, 13 square icons, search/toolbar bounds, narrow/compact layout, toggle input");
        } finally {win.dispose(); tk.dispose();}
        System.exit(0);
    }
}
