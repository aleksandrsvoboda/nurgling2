package haven;

import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import haven.render.*;
import nurgling.styles.UIResources;
import nurgling.styles.UITheme;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/** Real widget rendering and input regression; no server/account/config writes. */
public class UiThemeTest {
    static void check(boolean pass, String message) { if(!pass) throw new AssertionError(message); }
    static final Coord SIZE = UI.scale(760, 560);

    static Window sample(UI ui) {
        Window w = ui.root.add(new Window(UI.scale(716, 506), "Interface / Интерфейс"), UI.scale(10, 10));
        int[] clicks = {0};
        Button normal = w.add(new Button(UI.scale(150), "Применить / Apply", () -> clicks[0]++), UI.scale(12, 18));
        Button hover = w.add(new Button(UI.scale(150), "Hover / Наведение"), UI.scale(180, 18));
        hover.mousemove(new Widget.MouseMoveEvent(UI.scale(10, 10)));
        Button disabled = w.add(new Button(UI.scale(150), "Disabled / Выкл.", () -> clicks[0]++), UI.scale(348, 18));
        disabled.disable(true);
        disabled.mousedown(new Widget.MouseDownEvent(UI.scale(5, 5), 1));
        disabled.mouseup(new Widget.MouseUpEvent(UI.scale(5, 5), 1));
        check(clicks[0] == 0, "Disabled button activated");
        normal.mousedown(new Widget.MouseDownEvent(UI.scale(5, 5), 1));
        normal.mouseup(new Widget.MouseUpEvent(UI.scale(5, 5), 1));
        check(clicks[0] == 1, "Button activation lost");
        CheckBox unchecked = w.add(new CheckBox("Отражения / Reflections"), UI.scale(12, 72));
        CheckBox checked = w.add(new CheckBox("Дождь / Rain"), UI.scale(270, 72));
        checked.click();
        check(checked.state() && !unchecked.state(), "Checkbox state lost");
        TextEntry text = w.add(new TextEntry(UI.scale(310), "Сила / Strength 283"), UI.scale(12, 108));
        TextEntry password = w.add(new TextEntry(UI.scale(180), "secret"), UI.scale(340, 108));
        password.pw = true;
        HSlider slider = w.add(new HSlider(UI.scale(310), 20, 120, 70), UI.scale(12, 155));
        HSlider empty = w.add(new HSlider(UI.scale(180), 5, 5, 5), UI.scale(340, 155));
        check(UITheme.fraction(70, 20, 120) == .5, "Nonzero range offset");
        check(UITheme.fraction(5, 5, 5) == 0, "Empty range");
        slider.mousedown(new Widget.MouseDownEvent(Coord.of(slider.sz.x, 0), 1));
        slider.mouseup(new Widget.MouseUpEvent(Coord.of(slider.sz.x, 0), 1));
        check(slider.val == 120, "Slider endpoint cannot be reached");
        slider.val = 70;
        String[] entries = {"Характеристики / Attributes", "Inventory / Инвентарь", "Crafting / Ремесло", "World / Мир", "Options / Настройки", "Chat / Чат"};
        Listbox<String> list = w.add(new Listbox<String>(UI.scale(310), 4, UI.scale(25)) {
            protected String listitem(int i) { return entries[i]; }
            protected int listitems() { return entries.length; }
            protected void drawitem(GOut g, String item, int i) { g.text(item, UI.scale(9, 4)); }
        }, UI.scale(12, 200));
        list.change(entries[1]);
        Dropbox<String> drop = w.add(new Dropbox<String>(UI.scale(180), 4, UI.scale(24)) {
            protected String listitem(int i) { return entries[i]; }
            protected int listitems() { return entries.length; }
            protected void drawitem(GOut g, String item, int i) { g.text(item, UI.scale(6, 3)); }
        }, UI.scale(340, 200));
        drop.change(entries[3]);
        w.add(new Inventory(Coord.of(5, 2)), UI.scale(340, 243));
        w.add(new Frame(UI.scale(170, 95), false), UI.scale(534, 200));
        w.add(new Label("Panel / Панель"), UI.scale(544, 210));
        Progress progress = w.add(new Progress(UI.scale(145)).percent(), UI.scale(544, 243));
        progress.a = .64f;
        w.add(new nurgling.widgets.NTextArea(UI.scale(170, 66), "Notes / Заметки\nТекст"), UI.scale(534, 310));
        Tabs tabs = new Tabs(UI.scale(12, 360), UI.scale(500, 90), w);
        Tabs.Tab first = tabs.add(), second = tabs.add();
        w.add(tabs.new TabButton(UI.scale(150), "First / Первая", first), UI.scale(12, 325));
        w.add(tabs.new TabButton(UI.scale(150), "Second / Вторая", second), UI.scale(174, 325));
        first.add(new Label("Выбранная вкладка / Selected tab"), Coord.z);
        second.add(new Label("Second tab"), Coord.z);
        tabs.showtab(second); tabs.showtab(first);
        int x = 12;
        for(String tab : new String[]{"battr", "sattr", "skill", "fgt", "wound", "quest"}) {
            final boolean active = tab.equals("sattr");
            IButton icon = w.add(new IButton("gfx/hud/chr/" + tab, "u", "d", null) {
                protected boolean selected() { return active; }
            }, UI.scale(x, 413));
            check(icon.checkhit(icon.sz.div(2)), "Original icon hit region lost");
            x += 88;
        }
        w.add(new Label("Original icons / Оригинальные значки"), UI.scale(12, 460));
        w.add(new IButton("gfx/hud/buttons/sub", "u", "d", "h"), UI.scale(570, 430));
        w.add(new IButton("gfx/hud/buttons/add", "u", "d", "h"), UI.scale(592, 430));
        text.hasfocus = true; text.buf.select(0, 4);
        w.tick(1.0); // Finish the real window's opening fade before capture.
        return w;
    }

    static void capture(Windeye window, Window widget, String backend) throws Exception {
        Environment env = window.env();
        VectorFormat rgba = new VectorFormat(4, NumberFormat.UNORM8);
        Texture2D target = new Texture2D(SIZE.x, SIZE.y, DataBuffer.Usage.STATIC, rgba, null);
        try {
            Pipe pipe = new BufPipe().prep(new FragColor<>(target.image(0)))
                .prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(0, 0, SIZE.x, SIZE.y))
                .prep(FragColor.blend(new BlendMode()));
            long warmupDeadline = System.nanoTime() + 20_000_000_000L;
            byte[] data;
            while(true) {
            Render out = env.render();
            out.clear(pipe, FragColor.fragcol, new FColor(.05f, .075f, .08f, 1));
            GOut g = new GOut(out, pipe, SIZE);
            widget.draw(g.reclip(widget.c, widget.sz));
            CompletableFuture<byte[]> result = new CompletableFuture<>();
            out.pget(pipe, FragColor.fragcol, Area.sized(SIZE), rgba, bytes -> {
                byte[] pixels = new byte[SIZE.x * SIZE.y * 4]; bytes.get(pixels); result.complete(pixels);
            });
            window.swapbuffers(out, false); env.submit(out);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while(!result.isDone() && System.nanoTime() < deadline) {
                Render pump = env.render(); window.swapbuffers(pump, false); env.submit(pump); Thread.sleep(10);
            }
            check(result.isDone(), "UI GPU readback timeout");
            data = result.get();
            if(!(out instanceof haven.render.vk.VkRender) || ((haven.render.vk.VkRender)out).pendingDraws() == 0) break;
            check(System.nanoTime() < warmupDeadline, "UI pipelines did not finish warming up");
            }
            int varied = 0;
            for(int i = 4; i < data.length; i += 4)
                if(data[i] != data[0] || data[i+1] != data[1] || data[i+2] != data[2]) varied++;
            check(varied > SIZE.x * SIZE.y / 4, "Widget capture is empty");
            BufferedImage image = new BufferedImage(SIZE.x, SIZE.y, BufferedImage.TYPE_INT_ARGB);
            for(int y = 0, at = 0; y < SIZE.y; y++) for(int x = 0; x < SIZE.x; x++, at += 4)
                image.setRGB(x, SIZE.y - 1 - y, ((data[at+3]&255)<<24)|((data[at]&255)<<16)|((data[at+1]&255)<<8)|(data[at+2]&255));
            Path dir = Paths.get("build/ui-theme-preview"); Files.createDirectories(dir);
            ImageIO.write(image, "png", dir.resolve(backend + "-" + UI.scale(100) + ".png").toFile());
        } finally { target.dispose(); }
    }

    public static void main(String[] args) {
        int status = 0;
        try {
            nurgling.NConfig.getGlobalInstance();
            for(String name : new String[]{"gfx/hud/chr/battru", "nurgling/hud/buttons/rbtn/inv/u", "gfx/invobjs/stone", "gfx/terobjs/tree"})
                check(UIResources.image(name, 40, 40, 1) == null, "Icon/world art overridden: " + name);
            for(String backend : args.length == 0 ? new String[]{"vulkan"} : args) {
                Toolkit toolkit = Toolkit.toolkits().get(backend).open();
                Windeye window = toolkit.window();
                try {
                    window.title("UI theme verification"); window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
                    UI ui = new UI(window, new Audio.Root(haven.iosys.audio.DummyAudio.instance), SIZE, null);
                    Window widget = sample(ui);
                    capture(window, widget, backend);
                    widget.destroy();
                    System.out.println("UI theme PASS: " + backend + ", scale " + UI.scale(100) + ", actual widgets and input, original icons preserved");
                } finally { window.dispose(); toolkit.dispose(); }
            }
        } catch(Throwable e) { e.printStackTrace(); status = 1; }
        System.exit(status);
    }
}
