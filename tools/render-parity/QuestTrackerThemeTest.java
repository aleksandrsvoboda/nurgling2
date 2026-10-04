package haven;

import haven.iosys.tk.*;
import nurgling.NCore;
import nurgling.conf.NQuestTrackerProp;
import nurgling.widgets.*;
import nurgling.widgets.quest.QuestKind;
import java.lang.reflect.*;
import java.util.*;

/** Render the actual tracker with fixture quests, without a server or saved profile. */
public class QuestTrackerThemeTest {
    static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    static void set(Object o, String name, Object value) throws Exception { field(o.getClass(), name).set(o, value); }
    static void fill(NQuestInfo tracker) throws Exception {
        Class<?> group = Class.forName("nurgling.widgets.NQuestInfo$Group");
        Constructor<?> ctor = group.getDeclaredConstructor(); ctor.setAccessible(true);
        Class<?> row = Class.forName("nurgling.widgets.NQuestInfo$Row");
        Constructor<?> rc = row.getDeclaredConstructors()[0]; rc.setAccessible(true);
        String[] names = {"Authki", "Cunemael", "The Farmer's Furrow", "Asto", "Odenrike", "Wandlinde"};
        List<Object> groups = new ArrayList<>();
        NQuestTrackerProp prop = new NQuestTrackerProp("", "");
        for(int i = 0; i < names.length; i++) {
            Object g = ctor.newInstance();
            set(g, "key", names[i]); set(g, "title", names[i]); set(g, "total", i < 3 ? 3 : 2);
            set(g, "done", i < 2 ? 2 : 0); set(g, "ready", i < 2); set(g, "idle", i > 3);
            set(g, "kind", i == 2 ? QuestKind.CREDO : QuestKind.NPC);
            if(i == 2) {
                prop.expanded.add(names[i]);
                List<Object> rows = (List<Object>)field(group, "rows").get(g);
                rows.add(rc.newInstance("Milk a sheep", false, -1, false, null));
                rows.add(rc.newInstance("Plant hemp (×95)", false, -1, false, null));
            }
            groups.add(g);
        }
        Method layout = NQuestInfo.class.getDeclaredMethod("layoutRows", List.class, NQuestTrackerProp.class);
        layout.setAccessible(true); layout.invoke(tracker, groups, prop);
    }
    public static void main(String[] args) throws Exception {
        nurgling.NConfig.getGlobalInstance();
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open();
        Windeye window = toolkit.window();
        try {
            window.title("Quest tracker preview"); window.sizing(new Windeye.Sizing().fixsize(UiThemeTest.SIZE)).show(true);
            UI ui = new UI(window, new Audio.Root(haven.iosys.audio.DummyAudio.instance), UiThemeTest.SIZE, null);
            ui.core = new NCore(); ui.core.mode = NCore.Mode.DRAG;
            Window w = ui.root.add(new Window(UI.scale(710, 490), "Quest tracker / Оформление квестов"), UI.scale(10, 10));
            w.tick(1);
            NQuestInfo tracker = new NQuestInfo();
            NResizableWidget wrapper = w.add(new NResizableWidget(tracker, "quest-theme-preview", tracker.sz.add(NDraggableWidget.delta)), UI.scale(10, 20));
            fill(tracker);
            Method hit = NResizableWidget.class.getDeclaredMethod("resizeHit", Coord.class); hit.setAccessible(true);
            Method position = NResizableWidget.class.getDeclaredMethod("resizePosition"); position.setAccessible(true);
            Coord grip = (Coord)position.invoke(wrapper);
            Coord inside = grip.add(NResizableWidget.sizeru.sz()).sub(UI.scale(3), UI.scale(3));
            UiThemeTest.check((Boolean)hit.invoke(wrapper, inside), "Visible resize grip is not clickable");
            UiThemeTest.check(!(Boolean)hit.invoke(wrapper, grip.add(1, 1)), "Transparent half captures clicks");
            UiThemeTest.check(!(Boolean)hit.invoke(wrapper, wrapper.sz.add(2, 2)), "Resize grip extends outside wrapper");
            NResizableWidget ordinary = new NResizableWidget(new Widget(tracker.sz), "resize-ordinary-preview", wrapper.sz);
            UiThemeTest.check(position.invoke(ordinary).equals(grip), "Quest and ordinary resize positions differ");
            ICheckBox lock = (ICheckBox)field(NDraggableWidget.class, "btnLock").get(wrapper);
            lock.a = false;
            wrapper.mousedown(new Widget.MouseDownEvent(inside, 1));
            UiThemeTest.check(field(NResizableWidget.class, "drag").get(wrapper) != null, "Resize did not grab mouse");
            wrapper.mouseup(new Widget.MouseUpEvent(inside, 1));
            lock.a = true;
            wrapper.mousedown(new Widget.MouseDownEvent(inside, 1));
            UiThemeTest.check(field(NResizableWidget.class, "drag").get(wrapper) == null, "Locked widget resized");
            lock.a = false;
            ui.core.mode = NCore.Mode.IDLE;
            wrapper.mousedown(new Widget.MouseDownEvent(inside, 1));
            UiThemeTest.check(field(NResizableWidget.class, "drag").get(wrapper) == null, "Invisible grip resized outside Drag mode");
            ui.core.mode = NCore.Mode.DRAG;
            Object[] chips = (Object[])field(NQuestInfo.class, "chips").get(tracker);
            ((Widget)chips[1]).mousedown(new Widget.MouseDownEvent(UI.scale(8, 8), 1));
            UiThemeTest.check(!((ACheckBox)chips[1]).a, "Credo toggle did not turn off");
            ((Widget)chips[1]).mousedown(new Widget.MouseDownEvent(UI.scale(8, 8), 1));
            UiThemeTest.check(((ACheckBox)chips[1]).a, "Credo toggle did not turn on");
            NQuestInfo searching = w.add(new NQuestInfo(), UI.scale(330, 30));
            searching.resize(UI.scale(320, 280));
            ACheckBox search = (ACheckBox)field(NQuestInfo.class, "searchbtn").get(searching);
            search.click(); search.mousemove(new Widget.MouseMoveEvent(UI.scale(8, 8)));
            UiThemeTest.check(((Widget)field(NQuestInfo.class, "searchbox").get(searching)).visible(), "Search field did not open");
            fill(searching);
            w.add(new Label("Grouping · NPC · Credo · World | Search · Options"), UI.scale(14, 340));
            w.add(new Label("Inventory toolbar style; preserved quest colours and text size"), UI.scale(14, 366));
            UiThemeTest.capture(window, w, "quest-vulkan");
            System.out.println("PASS: quest tracker Vulkan rendering, category toggle, search and resize corner");
        } finally { window.dispose(); toolkit.dispose(); }
        System.exit(0);
    }
}
