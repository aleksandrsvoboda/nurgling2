package haven;

import haven.iosys.tk.*;
import haven.render.*;
import nurgling.craft.*;
import nurgling.navigation.CompassBearing;
import nurgling.widgets.NCraftAtlas;
import nurgling.widgets.NCompass;
import haven.res.ui.locptr.Pointer;
import org.json.JSONObject;
import java.util.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/** Offline data regressions plus rendering/input checks of the real atlas window. */
public class CompassAtlasTest {
    static void check(boolean condition, String message) { if(!condition) throw new AssertionError(message); }
    static void near(double value, double expected) { check(Math.abs(value - expected) < 1e-6, value + " != " + expected); }
    static AtlasCatalog.Material material(String resource, String name, int n) {
        return new AtlasCatalog.Material(resource, name, n, false, false);
    }
    static AtlasCatalog.Recipe recipe(String id, String name, List<AtlasCatalog.Material> in, List<AtlasCatalog.Material> out) {
        return new AtlasCatalog.Recipe(id, name, "Tools / Инструменты", in, out, Arrays.asList("Survival", "Dexterity"),
                                      Collections.singletonList("Anvil / Наковальня"), true);
    }
    static class Source implements AtlasSource {
        final AtlasCatalog data = new AtlasCatalog();
        final Set<String> available = new HashSet<>();
        int opens, revision;
        public AtlasCatalog catalog() { return data; }
        public boolean available(String id) { return available.contains(id); }
        public MenuGrid.Pagina page(String id) { return null; }
        public void open(String id) { check(available(id), "Unavailable action sent"); opens++; }
        public void flush() {}
        public int availabilityVersion() { return revision; }
        public String saveError() { return null; }
    }
    static Source fixture() {
        Source source = new Source();
        source.data.put(recipe("axe", "Stone Axe / Каменный топор",
            Arrays.asList(material("stone", "Stone / Камень", 2), material("branch", "Branch / Ветка", 1),
                          new AtlasCatalog.Material("string", "String / Нить", 1, true, true)),
            Collections.singletonList(material("axe-item", "Stone Axe / Каменный топор", 1))));
        source.data.put(recipe("string-craft", "Spun String / Пряжа", Collections.singletonList(material("fibre", "Fibre / Волокно", 3)),
            Collections.singletonList(material("string", "String / Нить", 2))));
        source.data.put(new AtlasCatalog.Recipe("unknown", "Unopened recipe / Неоткрытый рецепт", "Tools"));
        source.data.toggleFavorite("axe");
        source.available.add("axe"); source.available.add("unknown");
        return source;
    }
    static void dataTests() {
        near(CompassBearing.fraction(Math.PI, 0), .5); // camera zero looks west
        near(CompassBearing.fraction(-Math.PI / 2, 0), .75); // north is screen-right
        near(CompassBearing.fraction(Math.PI / 2, 0), .25);
        near(CompassBearing.fraction(-Math.PI / 2, -Math.PI / 2), .5);
        for(int turns = -100; turns <= 100; turns++) {
            near(CompassBearing.fraction(.8, 1.1 + turns * 2 * Math.PI), CompassBearing.fraction(.8, 1.1));
        }
        near(CompassBearing.fraction(Math.PI - .01, 0), .5 - .01 / (2 * Math.PI));
        near(CompassBearing.fraction(-Math.PI + .01, 0), .5 + .01 / (2 * Math.PI));
        Source source = fixture();
        AtlasCatalog catalog = source.data;
        AtlasCatalog.Recipe axe = catalog.get("axe");
        check(axe.matches("КАМЕНЬ инструменты"), "Ingredient/category Unicode search");
        check(!axe.matches("unrelated"), "Search false positive");
        int version = catalog.version; catalog.put(axe);
        check(version == catalog.version, "Unchanged recipes trigger save/rebuild");
        check(axe.inputs.get(0).quantity(20).equals("40"), "Batch multiplication");
        check(material("x", "x", -1).quantity(20).equals("?"), "Unknown quantity invented");
        check(material("x", "x", Integer.MAX_VALUE).quantity(9999).equals("21472688986353"), "Integer overflow");
        check(catalog.producer(axe.inputs.get(2)) == null, "Category must not pick a concrete producer");
        check(catalog.producer(material("string", "String", 1)).resource.equals("string-craft"), "Exact producer link");
        check(catalog.uses("string").size() == 1, "Reverse recipe link");
        AtlasCatalog roundtrip = new AtlasCatalog(); roundtrip.load(new JSONObject(catalog.json().toString()));
        check(roundtrip.favorite("axe") && roundtrip.get("axe").inputs.get(2).optional, "Persistence lost flags");
        check(roundtrip.get("axe").quality.size() == 2 && !roundtrip.get("unknown").recorded, "Persistence lost provenance");
        roundtrip.put(recipe("alternate", "Another string", Collections.emptyList(), Collections.singletonList(material("string", "String", 1))));
        check(roundtrip.producer(material("string", "String", 1)) == null, "Ambiguous producer silently chosen");
        try { roundtrip.load(new JSONObject("{\"schema\":2}")); throw new AssertionError("Unknown schema accepted"); }
        catch(IllegalArgumentException expected) {}
        check(roundtrip.get("axe") != null, "Failed load erased data");
        System.out.println("PASS: compass orientation/wrap, atlas quantities, search, links and JSON round-trip");
    }
    static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    static void click(Button b) {
        b.mousedown(new Widget.MouseDownEvent(UI.scale(4, 4), 1));
        b.mouseup(new Widget.MouseUpEvent(UI.scale(4, 4), 1));
    }
    static void capture(Windeye window, Widget widget, Coord size, String name) throws Exception {
        Environment env = window.env();
        VectorFormat rgba = new VectorFormat(4, NumberFormat.UNORM8);
        Texture2D target = new Texture2D(size.x, size.y, DataBuffer.Usage.STATIC, rgba, null);
        try {
            Pipe pipe = new BufPipe().prep(new FragColor<>(target.image(0)))
                .prep(new States.Viewport(Area.sized(size))).prep(new Ortho2D(0, 0, size.x, size.y))
                .prep(FragColor.blend(new BlendMode()));
            long deadline = System.nanoTime() + 25_000_000_000L;
            byte[] pixels;
            while(true) {
                Render out = env.render(); out.clear(pipe, FragColor.fragcol, new FColor(.05f, .075f, .08f, 1));
                GOut g = new GOut(out, pipe, size);
                if(widget instanceof NCompass) {
                    // High-contrast background to expose unreadable unoutlined text and opaque HUD panels.
                    java.awt.Color[] terrain = {new java.awt.Color(133, 131, 109), new java.awt.Color(57, 79, 46), new java.awt.Color(170, 151, 115), new java.awt.Color(75, 68, 59)};
                    for(int y = 0; y < size.y; y += UI.scale(16)) for(int x = 0; x < size.x; x += UI.scale(24)) {
                        g.chcolor(terrain[(x / UI.scale(24) + y / UI.scale(16)) % terrain.length]);
                        g.frect(new Coord(x, y), UI.scale(24, 16));
                    }
                    g.chcolor();
                }
                widget.draw(g.reclip(widget.c, widget.sz));
                CompletableFuture<byte[]> result = new CompletableFuture<>();
                out.pget(pipe, FragColor.fragcol, Area.sized(size), rgba, bytes -> {
                    byte[] data = new byte[size.x * size.y * 4]; bytes.get(data); result.complete(data);
                });
                window.swapbuffers(out, false); env.submit(out);
                while(!result.isDone() && System.nanoTime() < deadline) {
                    Render pump = env.render(); window.swapbuffers(pump, false); env.submit(pump); Thread.sleep(10);
                }
                check(result.isDone(), "GPU readback timeout"); pixels = result.get();
                if(!(out instanceof haven.render.vk.VkRender) || ((haven.render.vk.VkRender)out).pendingDraws() == 0) break;
                check(System.nanoTime() < deadline, "Pipeline warmup timeout");
            }
            BufferedImage image = new BufferedImage(size.x, size.y, BufferedImage.TYPE_INT_ARGB);
            for(int y = 0, at = 0; y < size.y; y++) for(int x = 0; x < size.x; x++, at += 4)
                image.setRGB(x, size.y - 1 - y, ((pixels[at+3]&255)<<24)|((pixels[at]&255)<<16)|((pixels[at+1]&255)<<8)|(pixels[at+2]&255));
            Path dir = Paths.get("build/compass-atlas-preview"); Files.createDirectories(dir);
            ImageIO.write(image, "png", dir.resolve(name + "-" + UI.scale(100) + ".png").toFile());
        } finally { target.dispose(); }
    }
    public static void main(String[] args) {
        int status = 0;
        try {
            System.setProperty("haven.prefs.compass-atlas-test", "true"); // in-memory preferences, no registry writes
            dataTests();
            if(Arrays.asList(args).contains("--render")) {
                Path resourceCache = Paths.get("build/compass-atlas-resource-cache");
                if(Files.isDirectory(resourceCache)) Resource.remote().add(new Resource.FileSource(resourceCache));
                nurgling.NConfig.getGlobalInstance();
                nurgling.i18n.L10n.setLocale(new Locale("ru"));
                Toolkit toolkit = Toolkit.toolkits().get("vulkan").open(); Windeye window = toolkit.window();
                try {
                    Coord size = UI.scale(980, 640);
                    window.title("Craft atlas verification"); window.sizing(new Windeye.Sizing().fixsize(size)).show(false);
                    UI ui = new UI(window, new Audio.Root(haven.iosys.audio.DummyAudio.instance), size, null);
                    Source source = fixture();
                    NCraftAtlas widget = ui.root.add(new NCraftAtlas(source), UI.scale(10, 10)); widget.tick(1);
                    TextEntry quantity = (TextEntry)field(widget, "quantity"); quantity.settext("25");
                    click((Button)field(widget, "open")); check(source.opens == 1, "Open button not wired");
                    quantity.settext("-1"); quantity.lostfocus(); check(quantity.text().equals("25"), "Invalid count not restored");
                    capture(window, widget, size, "atlas");
                    source.available.clear(); source.revision++; widget.tick(1);
                    click((Button)field(widget, "open")); check(source.opens == 1, "Unavailable open button not disabled");
                    CheckBox available=(CheckBox)field(widget,"onlyAvailable");
                    Coord availableAt=available.rootpos().add(UI.scale(5),available.sz.y/2);
                    ui.dispatch(ui.root,new Widget.MouseDownEvent(availableAt,1)); ui.dispatch(ui.root,new Widget.MouseUpEvent(availableAt,1));
                    check(((List<?>)field(widget,"rows")).isEmpty(),"Moved availability filter is not clickable");
                    ui.dispatch(ui.root,new Widget.MouseDownEvent(availableAt,1)); ui.dispatch(ui.root,new Widget.MouseUpEvent(availableAt,1));
                    CheckBox favorites = (CheckBox)field(widget, "onlyFavorites"); favorites.set(true);
                    check(((List<?>)field(widget, "rows")).size() == 1, "Favorite filter");
                    favorites.set(false);
                    TextEntry search = (TextEntry)field(widget, "search"); search.settext("волокно");
                    check(((List<?>)field(widget, "rows")).size() == 1, "Ingredient UI search");
                    search.settext(""); widget.resize(UI.scale(740, 400)); widget.tick(1);
                    capture(window, widget, size, "atlas-small");
                    Listbox<?> items=(Listbox<?>)field(widget,"list");
                    Button calculator=(Button)field(widget,"calculator");
                    check(available.c.x==items.c.x && favorites.c.x==items.c.x && available.c.y>=items.c.y+items.sz.y,
                        "Filters are not below the item list");
                    check(favorites.c.y>=available.c.y+available.sz.y && favorites.c.y+favorites.sz.y<=widget.csz().y-UI.scale(10),
                        "Footer controls overlap at minimum window size");
                    check(calculator.sz.equals(UI.scale(64,64)) && calculator.c.equals(new Coord(widget.csz().x-UI.scale(74),UI.scale(10))),
                        "Calculator icon is not in the upper right corner");
                    Scrollport details=(Scrollport)field(widget,"details");
                    check(details.c.y>=calculator.c.y+calculator.sz.y+UI.scale(10), "Calculator overlaps recipe details");
                    Coord calculatorAt=calculator.rootpos().add(calculator.sz.div(2));
                    ui.dispatch(ui.root,new Widget.MouseDownEvent(calculatorAt,1)); ui.dispatch(ui.root,new Widget.MouseUpEvent(calculatorAt,1));
                    Widget opened=(Widget)field(widget,"flowWindow");
                    check(opened!=null && opened.parent==ui.root && opened.visible,"Large calculator button did not open flow");
                    widget.destroy();
                    System.out.println("PASS: actual atlas Vulkan render, controls, filters, availability and resize");
                    Source wikiSource = new Source();
                    WikiRecipes.bundled().recipes.forEach(wikiSource.data::put);
                    NCraftAtlas wikiWidget = ui.root.add(new NCraftAtlas(wikiSource), UI.scale(10, 10));
                    wikiWidget.resize(UI.scale(900, 550)); wikiWidget.tick(1);
                    check(((List<?>)field(wikiWidget, "rows")).size() > 1000, "Offline wiki catalog missing from UI");
                    Listbox<AtlasCatalog.Recipe> wikiList = (Listbox<AtlasCatalog.Recipe>)field(wikiWidget, "list");
                    AtlasCatalog.Recipe selection = wikiList.sel;
                    Coord track = wikiList.sb.rootpos().add(wikiList.sb.sz.x / 2, wikiList.sb.sz.y * 3 / 4);
                    ui.dispatch(ui.root, new Widget.MouseDownEvent(track, 1));
                    check(wikiList.sb.val > 500 && wikiList.sel == selection, "Scrollbar click was consumed by list row");
                    ui.dispatch(ui.root, new Widget.MouseMoveEvent(wikiList.sb.rootpos().add(wikiList.sb.sz.x / 2, wikiList.sb.sz.y - 1)));
                    check(wikiList.sb.val == wikiList.sb.max, "Scrollbar drag cannot reach bottom");
                    ui.dispatch(ui.root, new Widget.MouseUpEvent(track, 1));
                    ui.dispatch(ui.root, new Widget.MouseDownEvent(wikiList.sb.rootpos().add(wikiList.sb.sz.x / 2, 0), 1));
                    ui.dispatch(ui.root, new Widget.MouseUpEvent(track, 1));
                    check(wikiList.sb.val == 0, "Scrollbar cannot return to top");
                    ((TextEntry)field(wikiWidget, "search")).settext("Unbaked Apple Pie");
                    List<AtlasCatalog.Recipe> wikiRows = (List<AtlasCatalog.Recipe>)field(wikiWidget, "rows");
                    AtlasCatalog.Recipe pie = wikiRows.stream().filter(r -> r.name.equals("Unbaked Apple Pie")).findFirst().orElseThrow();
                    ((Listbox<AtlasCatalog.Recipe>)field(wikiWidget, "list")).change(pie);
                    ((TextEntry)field(wikiWidget, "quantity")).settext("3");
                    if(nurgling.tools.ItemResources.count() > 0) {
                        check(nurgling.tools.ItemResources.count() > 1000, "Incomplete shared icon archive");
                        long iconsDeadline = System.nanoTime() + 10_000_000_000L;
                        String[] names = {"Butter", "Red Apple", "Apple Pie", "Unbaked Apple Pie", "Rabbit Fur", "Water", "String", "Any Flour",
                            "Seeds of Barley", "Seeds of Sprouted Barley", "Silk Filament", "Spitroast Bear", "Spitroast Abyss Gazer",
                            "Spitroast Beef", "Spitroast Bee", "Spinning Wheel", "Sawmill", "Board", "Block of Wood", "Bar of Hard Metal", "Weißbier"};
                        while(true) {
                            boolean ready = true;
                            for(String name : names) ready &= nurgling.tools.ItemIcons.get("", name, name.equals("String") || name.equals("Any Flour")) != null;
                            if(ready) break;
                            check(System.nanoTime() < iconsDeadline, "Offline resource icons did not load: " +
                                Arrays.stream(names).filter(name -> nurgling.tools.ItemIcons.get("", name, false) == null).collect(java.util.stream.Collectors.toList()));
                            Thread.sleep(20);
                        }
                        Tex butter = nurgling.tools.ItemIcons.get("", "Butter", false);
                        check(butter == nurgling.tools.ItemIcons.get(new JSONObject().put("static", "gfx/invobjs/butter")), "Area and atlas caches duplicated texture");
                        JSONObject layered = nurgling.tools.VSpec.categories.values().stream().flatMap(Collection::stream)
                            .filter(j -> j.has("layer")).findFirst().orElseThrow();
                        while(nurgling.tools.ItemIcons.get(layered) == null && System.nanoTime() < iconsDeadline) Thread.sleep(20);
                        check(nurgling.tools.ItemIcons.get(layered) != null, "Layered resource icon did not render");
                        int buildings = 0, composites = 0, renderedRecipes = 0;
                        for(AtlasCatalog.Recipe r : wikiSource.data.recipes()) {
                            JSONObject descriptor = nurgling.tools.ItemIcons.descriptor("", r.name, false);
                            check(descriptor != null, "Missing recipe icon: " + r.name);
                            List<String> paths = new ArrayList<>();
                            if(descriptor.has("layer")) for(Object path : descriptor.getJSONArray("layer")) paths.add((String)path);
                            else paths.add(descriptor.optString("static", descriptor.optString("image")));
                            for(String path : paths) check(nurgling.tools.ItemResources.contains(path), "Icon requires network: " + path);
                            BufferedImage icon = nurgling.tools.ItemResources.image(descriptor);
                            check(icon != null, "Icon failed to decode: " + r.name);
                            boolean visible = false;
                            for(int y = 0; y < icon.getHeight() && !visible; y++) for(int x = 0; x < icon.getWidth(); x++)
                                if((icon.getRGB(x, y) >>> 24) != 0) { visible = true; break; }
                            check(visible, "Empty icon: " + r.name);
                            renderedRecipes++;
                            if(r.wiki.kind.equals("build")) buildings++;
                            if(r.name.startsWith("Spitroast ")) composites++;
                        }
                        JSONObject bear = nurgling.tools.ItemIcons.descriptor("", "Spitroast Bear", false);
                        check(bear.has("layer") && bear.getJSONArray("layer").length() == 2, "Roast lost animal overlay");
                        nurgling.tools.ItemIcons.registerFallback("Spitroast Bear", "paginae/craft/meat");
                        check(bear.toString().equals(nurgling.tools.ItemIcons.descriptor("", "Spitroast Bear", false).toString()), "Menu overwrote composite");
                        check(renderedRecipes == 1430 && buildings == 192 && composites > 50, "Incomplete icon regression fixture");
                        System.out.println("PASS: " + renderedRecipes + " recipe icons, including " + buildings + " buildings and " + composites + " spitroasts decode visibly offline");
                    }
                    capture(window, wikiWidget, size, "atlas-wiki");
                    click((Button)field(wikiWidget, "open")); check(wikiSource.opens == 0, "Wiki entry enabled server action offline");
                    AtlasCatalog.Recipe complex = wikiSource.data.recipes().stream().filter(r -> r.name.equals("Palisade")).findFirst().orElseThrow();
                    check(!complex.wiki.quantitiesKnown, "Staged construction marked additive");
                    ((TextEntry)field(wikiWidget, "search")).settext("Palisade");
                    ((Listbox<AtlasCatalog.Recipe>)field(wikiWidget, "list")).change(complex);
                    capture(window, wikiWidget, size, "atlas-wiki-stages");
                    ((TextEntry)field(wikiWidget, "search")).settext("Bunny Slippers");
                    AtlasCatalog.Recipe slippers = wikiSource.data.recipes().stream().filter(r -> r.name.equals("Bunny Slippers")).findFirst().orElseThrow();
                    wikiList.change(slippers);
                    ((TextEntry)field(wikiWidget, "quantity")).settext("1");
                    Scrollport panel = (Scrollport)field(wikiWidget, "details");
                    panel.bar.val = panel.cont.sy = panel.bar.max;
                    capture(window, wikiWidget, size, "atlas-wiki-formulas");
                    for(String name : new String[]{"Sawmill", "Seeds of Sprouted Barley", "Silk Filament", "Spitroast Bear", "Caviar Canapé"}) {
                        ((TextEntry)field(wikiWidget, "search")).settext(name.startsWith("Spitroast") ? "Spitroast" : name);
                        AtlasCatalog.Recipe r = wikiSource.data.recipes().stream().filter(row -> row.name.equals(name)).findFirst().orElseThrow();
                        wikiList.change(r);
                        List<AtlasCatalog.Material> visible = new ArrayList<>(r.inputs); visible.addAll(r.outputs);
                        ((List<AtlasCatalog.Recipe>)field(wikiWidget, "rows")).stream().limit(20).forEach(row -> visible.addAll(row.outputs));
                        long deadline = System.nanoTime() + 10_000_000_000L;
                        while(true) {
                            boolean ready = true;
                            for(AtlasCatalog.Material item : visible)
                                ready &= nurgling.tools.ItemIcons.get(item.resource, item.name, item.category) != null;
                            if(ready) break;
                            check(System.nanoTime() < deadline, "Atlas preview icons still missing: " + name);
                            Thread.sleep(20);
                        }
                        capture(window, wikiWidget, size, "atlas-icons-" + name.toLowerCase(Locale.ROOT).replace(' ', '-'));
                    }
                    int formulas = 0;
                    for(AtlasCatalog.Recipe r : wikiSource.data.recipes()) if(r.wiki != null) for(String formula : r.wiki.formulas) {
                        BufferedImage rendered = FormulaImages.render(formula, UI.scale(17), UI.scale(540));
                        check(rendered.getWidth() <= UI.scale(540) && rendered.getHeight() > 5, "Invalid formula image");
                        formulas++;
                    }
                    check(formulas > 700, "Wiki formulas not tested");
                    try { FormulaImages.render("\\includegraphics{file}", 17, 540); throw new AssertionError("Non-math TeX accepted"); }
                    catch(IllegalArgumentException expected) {}
                    wikiWidget.destroy();
                    Widget menuPreview = ui.root.add(new Widget(UI.scale(310, 70)), UI.scale(10, 10));
                    String[] menuNames = {"inv", "equ", "chr", "bud"};
                    Widget previous = null;
                    for(String name : menuNames) previous = menuPreview.add(new GameUI.MenuCheckBox("rbtn/" + name + "/", GameUI.kb_inv, name),
                        previous == null ? Coord.z : previous.pos("ur").add(UI.scale(10), 0));
                    nurgling.widgets.NAtlasToggle atlasButton = menuPreview.add(new nurgling.widgets.NAtlasToggle(previous.sz), previous.pos("ur").add(UI.scale(10), 0));
                    final boolean[] toggled = {false}; atlasButton.state(() -> toggled[0]).click(() -> toggled[0] = !toggled[0]);
                    menuPreview.add(new GameUI.MenuCheckBox("rbtn/opt/", GameUI.kb_opt, "Options"), atlasButton.pos("ur").add(UI.scale(10), 0));
                    ui.dispatch(ui.root, new Widget.MouseDownEvent(atlasButton.rootpos().add(atlasButton.sz.div(2)), 1));
                    check(toggled[0], "Atlas icon button not clickable");
                    capture(window, menuPreview, UI.scale(320, 85), "atlas-menu"); menuPreview.destroy();
                    System.out.println("PASS: wiki atlas, scrollbar click/drag, " + formulas + " TeX formulas, resource icons and menu toggle");
                    NCompass compass = ui.root.add(new NCompass(null) {
                        protected Coord2d playerPosition() { return Coord2d.z; }
                        protected double cameraAngle() { return 0; }
                    });
                    capture(window, compass, UI.scale(980, 145), "compass-empty");
                    final int[] hits = new int[3];
                    Pointer[] pointers = new Pointer[3];
                    for(int i = 0; i < pointers.length; i++) {
                        final int index = i;
                        final Coord2d pos = new Coord2d(i < 2 ? -110 : 0, i < 2 ? i : -220);
                        pointers[i] = ui.root.add(new Pointer(null) {
                            public Coord2d compassPosition() { return pos; }
                            public String compassLabel() { return new String[]{"Quest / Квест", "Hearth / Очаг", "Marker / Метка"}[index]; }
                            public void compassClick(int button) { hits[index] += button; }
                            public void draw(GOut g) {}
                        });
                        compass.register(pointers[i]);
                    }
                    capture(window, compass, size, "compass");
                    Coord centre = new Coord(compass.sz.x / 2, UI.scale(45));
                    check(compass.mousedown(new Widget.MouseDownEvent(centre, 1)), "Compass marker cannot be clicked");
                    check(hits[0] == 1, "Nearest overlapping target not selected");
                    check(compass.mousewheel(new Widget.MouseWheelEvent(centre, 1, 1)), "Overlapping targets not cycled");
                    capture(window, compass, size, "compass-selected");
                    compass.mousedown(new Widget.MouseDownEvent(centre, 3));
                    check(hits[1] == 3 && hits[0] == 1, "Right-click went to wrong target");
                    compass.unregister(pointers[1]); pointers[1].destroy();
                    capture(window, compass, size, "compass-removed");
                    compass.mousedown(new Widget.MouseDownEvent(centre, 1));
                    check(hits[0] == 2, "Removed target retained hit area");
                    check(!compass.mousedown(new Widget.MouseDownEvent(UI.scale(5, 5), 1)), "Compass eats unrelated map clicks");
                    compass.destroy();
                    System.out.println("PASS: actual compass Vulkan render, overlapping targets, wheel, clicks and removal");
                } finally { window.dispose(); toolkit.dispose(); }
            }
        } catch(Throwable e) { e.printStackTrace(); status = 1; }
        System.exit(status);
    }
}
