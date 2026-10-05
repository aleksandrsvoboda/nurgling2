package haven;

import nurgling.craft.*;
import nurgling.widgets.NCraftFlow;
import haven.iosys.tk.*;
import java.util.*;
import java.nio.file.*;
import org.json.*;

/** Calculator regressions: arithmetic, live overrides, DAG propagation and native mouse interaction. */
public class CraftFlowTest {
    static void check(boolean v, String message) { if(!v) throw new AssertionError(message); }
    static void near(double v, double expected) { check(Math.abs(v - expected) < 1e-6, v + " != " + expected); }
    static class Source extends CompassAtlasTest.Source {
        final CraftFlow flow = new CraftFlow();
        final CraftCanvases canvases = new CraftCanvases(flow);
        final Map<String, Double> live = new HashMap<>();
        final Map<String, MenuGrid.Pagina> pages = new HashMap<>();
        public MenuGrid.Pagina page(String id) { return pages.get(id); }
        String error;
        public String saveError() { return error; }
        public CraftFlow flow() { return canvases.active().flow; }
        public CraftCanvases canvases() { return canvases; }
        public Double liveQuality(QualityModel.Port p) { return p.kind == QualityModel.Kind.MATERIAL ? null : live.get(p.name); }
    }
    static Source fixture() throws Exception {
        Source s = new Source(); WikiRecipes.bundled().recipes.forEach(s.data::put);
        s.live.put("Dexterity", 25.0); s.live.put("Sewing", 100.0); s.live.put("Cauldron", 60.0); return s;
    }
    static AtlasCatalog.Recipe recipe(Source s, String name) { return s.data.recipes().stream().filter(r -> r.name.equals(name)).findFirst().orElseThrow(); }
    static QualityModel.Port port(QualityModel m, String name) { return m.ports.stream().filter(p -> p.name.equals(name)).findFirst().orElseThrow(); }
    static MenuGrid.Pagina menuRecipe(MenuGrid menu, String resource, String name, String... action) {
        Resource.Virtual res = new Resource.Virtual(Resource.local(), resource, 1);
        MessageBuf data = new MessageBuf();
        data.addstring("").adduint16(0).addstring(name).addstring("").adduint16(0).adduint16(action.length);
        for(String arg : action) data.addstring(arg);
        res.add(res.new AButton(new MessageBuf(data.fin())));
        return new MenuGrid.Pagina(menu, res.indir(), res.indir());
    }
    static void drag(UI ui, Coord from, Coord to) {
        ui.dispatch(ui.root, new Widget.MouseDownEvent(from, 1));
        ui.dispatch(ui.root, new Widget.MouseMoveEvent(to));
        ui.dispatch(ui.root, new Widget.MouseUpEvent(to, 1));
    }
    static void menuClicks(UI ui) throws Exception {
        ui.core = new nurgling.NCore(); ui.core.mode = nurgling.NCore.Mode.IDLE;
        List<Object[]> actions = new ArrayList<>();
        MenuGrid menu = new MenuGrid() {
            public void wdgmsg(String msg, Object... args) {
                check(msg.equals("act"), "Unexpected menu message: " + msg);
                actions.add(args);
            }
        };
        nurgling.widgets.NMenuGridWdg panel = new nurgling.widgets.NMenuGridWdg();
        panel.setMenuGrid(menu);
        Widget frame = ui.root.add(new nurgling.widgets.NDraggableWidget(panel,
            "menu-click-test", panel.sz.add(nurgling.widgets.NDraggableWidget.delta)), UI.scale(20, 20));
        java.lang.reflect.Field layoutField = MenuGrid.class.getDeclaredField("layout"); layoutField.setAccessible(true);
        MenuGrid.PagButton[][] layout = (MenuGrid.PagButton[][])layoutField.get(menu);
        for(int x = 0; x < 2; x++) for(int y = 0; y < 2; y++) {
            MenuGrid.Pagina page = menuRecipe(menu, "test/click-" + x + "-" + y, "Recipe " + x + ":" + y,
                "craft", "recipe-" + x + "-" + y);
            menu.paginae.add(page); layout[x][y] = page.button();
        }
        // Go through the real draggable frame and inset menu panel, including cell edges.
        for(Coord inset : List.of(MenuGrid.bgsz.div(2), MenuGrid.bgsz.sub(1, 1))) {
            for(int x = 0; x < 2; x++) for(int y = 0; y < 2; y++) {
                int count = actions.size();
                Coord at = menu.rootpos().add(MenuGrid.bgsz.mul(new Coord(x, y))).add(inset);
                drag(ui, at, at);
                check(actions.size() == count + 1, "Menu click did not send exactly one crafting action at " + x + "," + y + ": " + inset);
                check(actions.get(count)[1].equals("recipe-" + x + "-" + y), "Menu click activated the wrong recipe");
                check(ui.grabs.isEmpty(), "Menu click retained a mouse grab");
            }
        }
        Coord at = menu.rootpos().add(MenuGrid.bgsz.div(2));
        int count = actions.size();
        drag(ui, at, at.add(UI.scale(1, 1)));
        check(actions.size() == count + 1, "Small mouse movement swallowed a click");
        drag(ui, at, menu.rootpos().sub(UI.scale(20, 20)));
        check(actions.size() == count + 1 && ui.grabs.isEmpty(), "Cancelled drag executed a craft or retained a grab");
        drag(ui, at, at);
        check(actions.size() == count + 2, "Click after cancelled drag failed");
        frame.destroy();
        ui.core.dispose(); ui.core = null;
        System.out.println("PASS: wrapped MenuGrid clicks send the correct crafting action once, at centers/edges and after drag cancellation");
    }
    static void recipeHotbars(nurgling.NUI ui) throws Exception {
        List<Object[]> bindings=new ArrayList<>(), actions=new ArrayList<>();
        List<String> errors=new ArrayList<>();
        nurgling.NGameUI gui=new nurgling.NGameUI("recipe-hotbar-test",0,"",ui) {
            public void wdgmsg(String msg,Object... args) {
                check(msg.equals("setbelt"),"Unexpected hotbar message: "+msg); bindings.add(args);
            }
            public void error(String message) { errors.add(message); }
        };
        gui.ui=ui;
        MenuGrid menu=ui.root.add(new MenuGrid() {
            public void wdgmsg(String msg,Object... args) {
                check(msg.equals("act"),"Unexpected game action: "+msg); actions.add(args);
            }
        },Coord.z);
        gui.menu=menu;
        gui.atlas=new CraftAtlas(gui);
        MenuGrid.Pagina craft=menuRecipe(menu,"test/slippers","Bunny Slippers","craft","bunnyslippers");
        MenuGrid.Pagina build=menuRecipe(menu,"test/kiln","Kiln","bp","kiln");
        menu.paginae.add(craft); menu.paginae.add(build); menu.pagseq++;
        AtlasCatalog.Recipe craftRecipe=gui.atlas.catalog().findWikiCraft("Bunny Slippers");
        AtlasCatalog.Recipe buildRecipe=gui.atlas.catalog().findWikiAction("Kiln","build");
        check(gui.atlas.shortcut(craftRecipe.resource)==craft && gui.atlas.shortcut(buildRecipe.resource)==build,
            "Live craft/build pagina not mapped to wiki recipe");
        AtlasCatalog.Recipe offline=new AtlasCatalog.Recipe("test/offline","Wiki only material","");
        gui.atlas.catalog().put(offline);
        nurgling.NGameUI.NToolBelt belt=ui.root.add(gui.new NToolBelt("recipe-hotbar-test",0,4,12),UI.scale(10,240));
        Coord slot=new Coord(30,10); // NToolBelt's first slot starts after the 17px grip.
        check(!belt.dropthing(slot,offline) && bindings.isEmpty(),"Wiki-only recipe accepted by real hotbar");
        check(gui.atlas.recipe(offline)==offline,"Hotbar filtering removed offline canvas support");
        check(belt.dropthing(slot,craftRecipe),"Craft recipe rejected by real hotbar");
        check(bindings.get(0)[1].equals("res") && bindings.get(0)[2].equals(craft.res().name),"Recipe saved as atlas link instead of native action");
        gui.belt[0]=new GameUI.PagBeltSlot(0,craft);
        belt.mousedown(new Widget.MouseDownEvent(slot,1));
        check(actions.size()==1 && actions.get(0)[0].equals("craft"),"Hotbar click did not send MAKE action");
        check(belt.dropthing(slot,buildRecipe),"Building recipe rejected by real hotbar");
        gui.belt[0]=new GameUI.PagBeltSlot(0,build);
        belt.mousedown(new Widget.MouseDownEvent(slot,1));
        check(actions.size()==2 && actions.get(1)[0].equals("bp"),"Hotbar click did not start construction");
        nurgling.conf.NToolBeltProp props=nurgling.conf.NToolBeltProp.get("recipe-hotbar-test");
        props.custom.put(0,"atlas:"+craftRecipe.resource);
        belt.mousedown(new Widget.MouseDownEvent(slot,1));
        check(actions.size()==3 && !props.custom.containsKey(0) && gui.atlasWindow==null,"Legacy shortcut did not upgrade to MAKE action");
        props.custom.put(0,"atlas:"+offline.resource);
        belt.mousedown(new Widget.MouseDownEvent(slot,1));
        check(actions.size()==3 && errors.size()==1 && gui.atlasWindow==null,"Unavailable legacy shortcut opened atlas/executed an action");
        props.custom.clear();
        menu.paginae.remove(craft); menu.pagseq++;
        check(gui.atlas.shortcut(craftRecipe.resource)==null && !belt.dropthing(slot,craftRecipe),"Removed action remained bindable");
        MenuGrid.Pagina pie=menuRecipe(menu,"test/applepie","Apple Pie","craft","applepie");
        menu.paginae.add(pie); menu.pagseq++;
        AtlasCatalog.Recipe rawPie=gui.atlas.catalog().findWikiCraft("Apple Pie");
        AtlasCatalog.Recipe bakedPie=gui.atlas.catalog().recipes().stream().filter(r -> r.name.equals("Apple Pie")).findFirst().orElseThrow();
        check(rawPie.name.equals("Unbaked Apple Pie"),"Apple Pie game action lost its canonical crafting stage");
        check(gui.atlas.shortcut(bakedPie.resource)==pie && gui.atlas.shortcut(rawPie.resource)==pie,
            "Finished Apple Pie cannot resolve the existing Apple Pie menu action");
        RecipeTransfer pieDrag=new RecipeTransfer(gui.atlas,bakedPie);
        check(belt.dropthing(slot,pieDrag),"Dragging finished Apple Pie does not bind a game action");
        check(bindings.get(bindings.size()-1)[2].equals(pie.res().name),"Apple Pie bound to an item icon instead of a pagina");
        check(gui.atlas.recipe(pieDrag)==bakedPie && gui.atlas.recipe(pie)==rawPie,
            "Atlas drag or MenuGrid drag silently changed its recipe stage");
        int actionCount=actions.size();
        props.custom.put(0,"atlas:"+bakedPie.resource);
        belt.mousedown(new Widget.MouseDownEvent(slot,1));
        check(actions.size()==actionCount+1 && actions.get(actionCount)[1].equals("applepie") && errors.size()==1,
            "Existing finished-pie shortcut did not send the Apple Pie MAKE action");
        check(gui.atlasWindow==null && !props.custom.containsKey(0),"Apple Pie legacy shortcut opened atlas or was not upgraded");
        check(!belt.dropthing(slot,new RecipeTransfer(gui.atlas,offline)),"Unavailable recipe transfer accepted by hotbar");
        MenuGrid.Pagina ambiguousPie=menuRecipe(menu,"test/otherpie","Apple Pie","craft","otherpie");
        menu.paginae.add(ambiguousPie); menu.pagseq++;
        check(gui.atlas.shortcut(bakedPie.resource)==null,"Ambiguous food action silently chosen");
        belt.destroy(); menu.destroy();
        System.out.println("PASS: real hotbar craft/build binding, Apple Pie stage aliases, MAKE activation, offline rejection and legacy shortcut conversion");
    }
    static void transfers(UI ui, Source source, NCraftFlow flow) throws Exception {
        nurgling.widgets.NCraftAtlas atlas = ui.root.add(new nurgling.widgets.NCraftAtlas(source), Coord.z);
        atlas.resize(UI.scale(740, 400));
        TextEntry search = (TextEntry)CompassAtlasTest.field(atlas, "search"); search.settext("Anvil");
        Listbox<?> list = (Listbox<?>)CompassAtlasTest.field(atlas, "list");
        Coord start = list.rootpos().add(UI.scale(70, 12));
        Coord target = flow.canvas.rootpos().add(UI.scale(810, 130));
        AtlasCatalog.Recipe anvil = recipe(source, "Anvil");
        // A click only selects; an offline wiki drag adds at the transformed canvas position.
        drag(ui, start, start);
        check(source.flow.nodes.isEmpty(), "Atlas click added a canvas node");
        source.flow.zoom = .5; source.flow.panX = 17; source.flow.panY = -23;
        ui.dispatch(ui.root, new Widget.MouseDownEvent(start, 1));
        ui.dispatch(ui.root, new Widget.MouseMoveEvent(target));
        check(CompassAtlasTest.field(flow.canvas, "dropRecipe") == anvil, "Atlas hover preview missing");
        ui.dispatch(ui.root, new Widget.MouseUpEvent(target, 1));
        check(source.flow.nodes.size() == 1, "Offline atlas recipe drag failed");
        CraftFlow.Node node = source.flow.nodes.get(0);
        check(node.recipe.equals(anvil.resource), "Atlas drag changed recipe identity");
        near(node.x, 1620 - 17); near(node.y, 260 + 23);
        check(CompassAtlasTest.field(flow.canvas, "dropRecipe") == null, "Drop ghost remained after release");
        source.flow.remove(node.id);
        source.flow.zoom = 1; source.flow.panX = source.flow.panY = 0;

        class ShortcutTarget extends Widget implements DropTarget {
            Object received;
            ShortcutTarget() { super(UI.scale(100, 45)); }
            public boolean dropthing(Coord c, Object thing) { received = thing; return true; }
        }
        ShortcutTarget shortcut = ui.root.add(new ShortcutTarget(), UI.scale(1210, 790));
        Coord key = shortcut.rootpos().add(UI.scale(20, 20));
        drag(ui, start, key);
        check(shortcut.received instanceof RecipeTransfer && ((RecipeTransfer)shortcut.received).recipe == anvil,
            "Offline drag lost catalog identity");
        MenuGrid menu = ui.root.add(new MenuGrid(), UI.scale(20, 550));
        MenuGrid.Pagina page = menuRecipe(menu, "test/drag-anvil", "Anvil", "bp", "anvil");
        menu.paginae.add(page); source.pages.put(anvil.resource, page);
        drag(ui, start, key);
        check(shortcut.received instanceof RecipeTransfer && ((RecipeTransfer)shortcut.received).page() == page,
            "Live atlas drag did not resolve its native hotbar Pagina");
        drag(ui, start, target);
        check(source.flow.nodes.size() == 1 && source.flow.nodes.get(0).recipe.equals(anvil.resource), "Live atlas drag lost wiki recipe");
        source.flow.remove(source.flow.nodes.get(0).id);
        drag(ui, start, UI.scale(1350, 20));
        check(source.flow.nodes.isEmpty(), "Drop outside canvas added a recipe");
        ui.dispatch(ui.root, new Widget.MouseDownEvent(start, 1));
        ui.dispatch(ui.root, new Widget.MouseMoveEvent(target));
        atlas.hide();
        ui.dispatch(ui.root, new Widget.MouseUpEvent(target, 1));
        check(source.flow.nodes.isEmpty() && CompassAtlasTest.field(flow.canvas, "dropRecipe") == null, "Hidden atlas left active drag/ghost");

        // Exercise the actual MenuGrid mouse path, not a synthetic direct canvas call.
        MenuGrid.PagButton[][] layout = (MenuGrid.PagButton[][])CompassAtlasTest.field(menu, "layout");
        layout[0][0] = page.button();
        Coord menuStart = menu.rootpos().add(UI.scale(12, 12));
        drag(ui, menuStart, target);
        check(source.flow.nodes.size() == 1 && source.flow.nodes.get(0).recipe.equals(anvil.resource), "MenuGrid-to-canvas drag failed");
        source.flow.remove(source.flow.nodes.get(0).id);
        MenuGrid.Pagina nonRecipe = menuRecipe(menu, "test/tracking", "Tracking", "tracking");
        check(!flow.canvas.dropthing(UI.scale(100,100), nonRecipe), "Non-craft action accepted by canvas");
        check(!flow.canvas.dropthing(new Coord(-1, -1), page), "Canvas accepted an outside drop");
        MenuGrid.Pagina fresh = menuRecipe(menu, "test/new-craft", "New undiscovered recipe", "craft", "test-new");
        int catalogSize = source.data.recipes().size();
        check(flow.canvas.drophover(UI.scale(100,100), true, fresh), "Fresh menu recipe preview rejected");
        check(source.data.recipes().size() == catalogSize, "Hover mutated recipe database");
        check(flow.canvas.dropthing(UI.scale(100,100), fresh), "New menu recipe drop rejected");
        check(source.data.get(fresh.res().name) != null, "Dropped menu recipe missing from catalog");
        source.flow.remove(source.flow.nodes.get(0).id);
        check(source.opens == 0, "Recipe drag executed a crafting action");
        source.pages.clear(); menu.destroy(); shortcut.destroy(); atlas.destroy();
        flow.canvas.recalculate();
        System.out.println("PASS: atlas/MenuGrid native mouse drags, hotbar payloads, offline recipes, drop coordinates, cancellation and action filtering");
    }
    static CraftFlow.Result result(Source s, CraftFlow.Node n) { return s.flow.evaluate(s.data, s::liveQuality, new HashMap<>()).get(n.id); }
    static void data() throws Exception {
        canvasPersistence();
        materialTotals();
        near(QualityExpression.parse("softcap(mean(100,40),geomean(25,100))").evaluate(k -> null), 60);
        near(QualityExpression.tex("\\frac{_{q}Metal*3+_{q}Casting Material}{4}").evaluate(k -> k.equals("metal") ? 100.0 : 40.0), 85);
        near(QualityExpression.tex("\\sqrt[3]{8*8*8}").evaluate(k -> null), 8);
        check(QualityExpression.parse("geomean(a+b,c)").toTex(k -> k).equals("\\sqrt{\\left(a+b\\right)\\cdot c}"),"TeX lost product precedence");
        check(QualityExpression.parse("a-(b-c)").toTex(k -> k).equals("a-\\left(b-c\\right)"),"TeX lost subtraction precedence");
        for(String bad : List.of("1/0", "sqrt(-1)", "unknown(2)", "NaN", "2 trailing", "pow(2)")) {
            try { QualityExpression.parse(bad).evaluate(k -> null); throw new AssertionError("Accepted " + bad); } catch(IllegalArgumentException expected) {}
        }
        Source s = fixture();
        Source saved = fixture(); saved.live.put("Intelligence",192.0); saved.live.put("Masonry",203.0);
        CraftFlow.Node bone = legacyBoneClay(saved);
        CraftFlow.Node oven = saved.flow.add(recipe(saved,"Kiln").resource,600,200), sink=saved.flow.add("",1000,200);
        saved.flow.connect(bone.id,oven.id,"i1"); saved.flow.connect(oven.id,sink.id,"q");
        near(result(saved,sink).value,175);
        check(bone.values.get("i3").equals("75") && !bone.inputNames.isEmpty(),"Legacy clay choice lost its entered value");
        Source conflict = fixture(); CraftFlow.Node ambiguous = legacyBoneClay(conflict); ambiguous.values.put("i4","90");
        check(result(conflict,ambiguous).value == null && result(conflict,ambiguous).error.contains("one choice"),"Multiple old clay values silently collapsed");
        check(ambiguous.values.get("i3").equals("75") && ambiguous.values.get("i4").equals("90"),"Ambiguous migration destroyed values");
        QualityModel anvil = QualityModel.of(recipe(s,"Anvil"));
        QualityModel kiln = QualityModel.of(recipe(s,"Kiln"));
        check(!kiln.estimated && kiln.ports.size() == 1 && kiln.ports.get(0).name.equals("Clay"), "Kiln has a spurious stat cap");
        near(QualityExpression.parse(kiln.expression).evaluate(k -> 90.0),90);
        check(!anvil.estimated && anvil.ports.size() == 2, "Anvil confused with metal product formula");
        QualityModel sawmill = QualityModel.of(recipe(s,"Sawmill"));
        check(sawmill.expression.contains("*5") && !sawmill.expression.contains("softcap"), "Sawmill picked formula/stat for produced boards");
        QualityModel saw = QualityModel.of(recipe(s,"Bone Saw"));
        check(saw.ports.stream().noneMatch(p -> p.name.equals("Carpentry")), "Board-making stat leaked into saw quality");
        QualityModel slippers = QualityModel.of(recipe(s,"Bunny Slippers"));
        CraftFlow.Node a = s.flow.add(recipe(s,"Anvil").resource, 300, 190);
        a.values.put("i1", "40"); a.values.put("i2", "100"); near(result(s,a).value, 85);
        CraftFlow.Node b = s.flow.add(recipe(s,"Bunny Slippers").resource, 1000, 230);
        b.values.put("i1", "100"); b.values.put("i2", "40"); near(result(s,b).value, 60);
        b.values.put(port(slippers,"Dexterity").id, "100*1"); near(result(s,b).value, 70);
        b.values.put(port(slippers,"Dexterity").id, ""); s.live.put("Dexterity", 4.0); near(result(s,b).value, 45);
        b.values.put(port(slippers,"Dexterity").id, "wrong"); check(result(s,b).value == null, "Invalid override silently used live stat");
        b.values.clear(); b.values.put("i2", "40"); s.live.put("Dexterity", 25.0);
        s.flow.connect(a.id, b.id, "i1"); near(result(s,b).value, 56.25);
        CraftFlow.Node out = s.flow.add("", 1500, 230); s.flow.connect(b.id, out.id, "q"); near(result(s,out).value, 56.25);
        a.values.put("i2", "140"); near(result(s,out).value, 63.75);
        try { s.flow.connect(b.id, a.id, "i1"); throw new AssertionError("Cycle accepted"); } catch(IllegalArgumentException expected) {}
        check(s.flow.incoming(b.id,"i1").from.equals(a.id), "Rejected edge mutated graph");
        s.flow.disconnect(b.id,"i1"); check(result(s,out).value == null, "Missing input defaulted to zero");
        s.flow.connect(a.id,b.id,"i1");
        a.positions.put("i1",new CraftFlow.Position(-300,170));
        CraftFlow copy = new CraftFlow(); copy.load(new JSONObject(s.flow.json().toString()));
        near(copy.node(a.id).positions.get("i1").x,-300);
        near(copy.node(a.id).positions.get("i1").y,170);
        near(copy.evaluate(s.data,s::liveQuality,new HashMap<>()).get(out.id).value,63.75);
        String good = copy.json().toString();
        JSONObject corrupt = new JSONObject(good); corrupt.getJSONArray("edges").put(new JSONObject().put("from",b.id).put("to",a.id).put("port","i1"));
        try { copy.load(corrupt); throw new AssertionError("Cyclic file accepted"); } catch(IllegalArgumentException expected) {}
        check(copy.json().similar(new JSONObject(good)), "Bad load destroyed previous canvas");
        JSONObject badPosition = new JSONObject(good);
        badPosition.getJSONArray("nodes").getJSONObject(0).getJSONObject("positions").put("i1",new JSONArray().put(2_000_000).put(0));
        try { copy.load(badPosition); throw new AssertionError("Invalid source position accepted"); } catch(IllegalArgumentException expected) {}
        check(copy.json().similar(new JSONObject(good)),"Invalid position destroyed canvas");
        copy.remove(a.id); check(copy.edges.stream().noneMatch(e -> e.from.equals(a.id)), "Node removal left edges");
        AtlasCatalog.Recipe original=recipe(s,"Anvil");
        List<AtlasCatalog.Material> reordered=new ArrayList<>(original.inputs); Collections.reverse(reordered);
        s.data.put(new AtlasCatalog.Recipe(original.resource,original.name,original.group,reordered,original.outputs,original.quality,original.tools,original.recorded,original.wiki));
        near(result(s,out).value,63.75);
        check(a.values.get("i1").equals("140") && a.values.get("i2").equals("40"),"Reordered input identity not preserved");
        near(a.positions.get("i2").x,-300);
        s.data.put(original); near(result(s,out).value,63.75);
        CraftFlow.Node legacyKiln=s.flow.add(recipe(s,"Kiln").resource,0,0);
        legacyKiln.inputSignature=UUID.nameUUIDFromBytes("i1:MATERIAL:Clay\na1:ATTRIBUTE:Will\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        legacyKiln.values.put("i1","90"); legacyKiln.values.put("a1","1");
        near(result(s,legacyKiln).value,90); check(!legacyKiln.values.containsKey("a1"),"Spurious Will override retained");
        QualityModel silk = QualityModel.of(recipe(s,"Silk Filament"));
        check(!silk.estimated && port(silk,"Water").kind == QualityModel.Kind.MATERIAL, "Boiling water missing from formula");
        CraftFlow.Node c = s.flow.add(recipe(s,"Silk Filament").resource,0,0);
        c.values.put(port(silk,"Silkworm Cocoon").id,"80"); c.values.put(port(silk,"Water").id,"40");
        near(result(s,c).value,61.25); s.live.remove("Cauldron"); check(result(s,c).value == null,"Missing tool defaulted to quality");
        for(AtlasCatalog.Recipe r : s.data.recipes()) QualityModel.of(r); // Every wiki formula is safely classified or rejected.
        System.out.println("PASS: quality expressions, wiki bindings, live/override reset, missing tools, DAG propagation, cycles and atomic persistence");
    }
    static void materialTotals() {
        AtlasCatalog catalog=new AtlasCatalog(); CraftFlow graph=new CraftFlow();
        AtlasCatalog.Material clay=new AtlasCatalog.Material("clay","Clay",40,false,true);
        AtlasCatalog.Material water=new AtlasCatalog.Material("water","Water",.25,"l",false,false,"");
        catalog.put(new AtlasCatalog.Recipe("test:a","A","",List.of(clay,water),List.of(),List.of(),List.of("Kiln"),false));
        catalog.put(new AtlasCatalog.Recipe("test:b","B","",List.of(clay,water,
            new AtlasCatalog.Material("water","Water",2,"kg",false,false,""),
            new AtlasCatalog.Material("clay","Clay",3,true,true)),List.of(),List.of(),List.of(),false));
        CraftFlow.Node a=graph.add("test:a",0,0), b=graph.add("test:b",0,0); graph.add("",0,0);
        FlowMaterials.Summary summary=FlowMaterials.summarize(graph,catalog,new HashMap<>());
        check(summary.rows.size()==4,"Materials mixed units/optional groups or included tools");
        near(summary.rows.stream().filter(r -> r.material.name.equals("Clay") && !r.optional).findFirst().orElseThrow().total.doubleValue(),80);
        check(summary.rows.stream().anyMatch(r -> r.quantity().equals("0.5 l")),"Fractional amounts not summed exactly");
        graph.connect(a.id,b.id,"i1");
        summary=FlowMaterials.summarize(graph,catalog,new HashMap<>());
        near(summary.rows.stream().filter(r -> r.material.name.equals("Clay") && !r.optional).findFirst().orElseThrow().total.doubleValue(),40);
        CraftFlow.Node result=graph.nodes.stream().filter(CraftFlow.Node::output).findFirst().orElseThrow();
        result.crafts=3; graph.connect(b.id,result.id,"q");
        summary=FlowMaterials.summarize(graph,catalog,new HashMap<>());
        near(summary.rows.stream().filter(r -> r.material.name.equals("Clay") && !r.optional).findFirst().orElseThrow().total.doubleValue(),120);
        graph.connect(a.id,b.id,"i2");
        summary=FlowMaterials.summarize(graph,catalog,new HashMap<>());
        near(summary.rows.stream().filter(r -> r.material.name.equals("Clay") && !r.optional).findFirst().orElseThrow().total.doubleValue(),120);
        graph.disconnect(b.id,"i2");
        CraftFlow.Node another=graph.add("",0,0); another.crafts=2; graph.connect(b.id,another.id,"q");
        summary=FlowMaterials.summarize(graph,catalog,new HashMap<>());
        near(summary.rows.stream().filter(r -> r.material.name.equals("Clay") && !r.optional).findFirst().orElseThrow().total.doubleValue(),200);
        CraftFlow copy=new CraftFlow(); copy.load(graph.json()); check(copy.node(result.id).crafts==3,"Craft count was not saved");
        String saved=copy.json().toString();
        for(double bad : new double[]{0,-1,1.5,10000}) {
            JSONObject invalid=new JSONObject(saved); invalid.getJSONArray("nodes").getJSONObject(0).put("crafts",bad);
            try { copy.load(invalid); throw new AssertionError("Invalid craft count accepted"); } catch(IllegalArgumentException expected) {}
            check(copy.json().similar(new JSONObject(saved)),"Bad count destroyed saved graph");
        }
        JSONObject legacy=new JSONObject(saved);
        for(Object value : legacy.getJSONArray("nodes")) ((JSONObject)value).remove("crafts");
        copy.load(legacy); check(copy.nodes.stream().allMatch(n -> n.crafts==1),"Old canvas count did not default to one");
        // Restore the preceding one-craft fixture for deletion and unknown-quantity checks.
        graph.remove(another.id); graph.disconnect(result.id,"q"); result.crafts=1;
        graph.disconnect(b.id,"i1"); graph.remove(a.id);
        result.crafts=3; graph.connect(b.id,result.id,"q");
        check(FlowMaterials.summarize(graph,catalog,new HashMap<>()).rows.stream().anyMatch(r -> r.optional && r.quantity().equals("9")),"Optional material multiplier lost");
        graph.disconnect(result.id,"q"); result.crafts=1;
        summary=FlowMaterials.summarize(graph,catalog,new HashMap<>());
        check(summary.rows.stream().anyMatch(r -> r.quantity().equals("0.25 l")),"Deleting recipe left stale materials");
        catalog.put(new AtlasCatalog.Recipe("test:c","C","",List.of(new AtlasCatalog.Material("water","Water",-1,"l",false,false,"")),List.of(),List.of(),List.of(),false));
        graph.add("test:c",0,0);
        check(FlowMaterials.summarize(graph,catalog,new HashMap<>()).rows.stream().anyMatch(r -> r.quantity().equals("0.25 + ? l")),"Unknown quantity silently discarded");
        AtlasCatalog.WikiInfo staged=new AtlasCatalog.WikiInfo("","","build","","stages",1,false,List.of(),List.of());
        catalog.put(new AtlasCatalog.Recipe("test:staged","Staged","",List.of(clay),List.of(),List.of(),List.of(),false,staged));
        graph.add("test:staged",0,0);
        summary=FlowMaterials.summarize(graph,catalog,new HashMap<>());
        check(summary.unresolved.contains("Staged"),"Unparsed composition silently treated as additive");
        near(summary.rows.stream().filter(r -> r.material.name.equals("Clay") && !r.optional).findFirst().orElseThrow().total.doubleValue(),40);
    }
    static void canvasPersistence() {
        CraftFlow legacy=new CraftFlow();
        CraftFlow.Node input=legacy.add("anvil",300,160), result=legacy.add("",900,160);
        input.values.put("i1","150"); input.formula="i1"; input.positions.put("i1",new CraftFlow.Position(30,40));
        result.crafts=40; legacy.connect(input.id,result.id,"q"); legacy.zoom=.5; legacy.panX=17; legacy.panY=-25;
        JSONObject old=legacy.json();
        CraftCanvases book=new CraftCanvases(); book.load(old);
        check(book.entries().size()==1 && book.active().flow.json().similar(old),"Legacy canvas migration lost graph/view/input data");
        CraftCanvases.Entry first=book.active(); book.rename(first,"Глина → печь");
        CraftCanvases.Entry second=book.create("Другой расчёт"); book.select(second);
        second.flow.add("saw",10,20); second.flow.zoom=1.5; second.flow.version++;
        check(first.flow.json().similar(old),"Creating a canvas modified the previous graph");
        long version=book.version(); first.flow.add("clay",0,0);
        check(book.version()!=version,"Changes on an inactive canvas are not saved");
        CraftCanvases copy=new CraftCanvases(); copy.load(new JSONObject(book.json().toString()));
        check(copy.json().similar(book.json()),"Canvas names, active selection or graphs lost after reload");
        copy.select(copy.entries().get(0));
        check(copy.active().name().equals("Глина → печь") && copy.active().flow.nodes.size()==3,"Cannot return to saved canvas");
        JSONObject good=copy.json();
        for(int mode=0; mode<4; mode++) {
            JSONObject bad=new JSONObject(good.toString()); JSONArray all=bad.getJSONArray("canvases");
            if(mode==0) bad.put("active","missing");
            if(mode==1) all.getJSONObject(1).put("id",all.getJSONObject(0).getString("id"));
            if(mode==2) all.getJSONObject(1).getJSONObject("flow").put("version",999);
            if(mode==3) bad.put("version",999);
            try { copy.load(bad); throw new AssertionError("Invalid canvas collection accepted"); }
            catch(IllegalArgumentException expected) {}
            check(copy.json().similar(good),"Failed load partially replaced canvases");
        }
        CraftCanvases.Entry kept=copy.active(), removed=copy.entries().get(1);
        removed.flow.version=1; version=copy.version();
        copy.remove(removed);
        check(copy.active()==kept && copy.version()>version,"Inactive deletion changed selection or failed to dirty save");
        CraftCanvases.Entry next=copy.create("Next");
        copy.remove(kept);
        check(copy.active()==next,"Active deletion did not select its neighbor");
        copy.remove(next);
        check(copy.entries().size()==1 && copy.active()!=next && copy.active().flow.nodes.isEmpty(),"Last deletion did not create an empty canvas");
        CraftCanvases afterDelete=new CraftCanvases(); afterDelete.load(copy.json());
        check(afterDelete.json().similar(copy.json()),"Deleted canvases reappeared after reload");
        JSONObject after=copy.json();
        try { copy.remove(next); throw new AssertionError("Deleted canvas accepted twice"); }
        catch(IllegalArgumentException expected) {}
        check(copy.json().similar(after),"Invalid deletion changed saved canvases");
        System.out.println("PASS: multiple canvas persistence, deletion, legacy migration, saved selection and atomic invalid-file rejection");
    }

    static void canvasSwitching(UI ui, Windeye window, Coord size, Source source, NCraftFlow flow) throws Exception {
        CraftCanvases.Entry first=source.canvases.active();
        IButton rename=tool(flow,"rename_canvas");
        Coord renameAt=rename.rootpos().add(rename.sz.div(2)); drag(ui,renameAt,renameAt);
        Window nameDialog=(Window)CompassAtlasTest.field(flow,"canvasNameDialog");
        TextEntry name=nameDialog.getchild(TextEntry.class); name.settext("Костяная глина");
        Button apply=button(nameDialog,"apply");
        Coord applyAt=apply.rootpos().add(apply.sz.div(2)); drag(ui,applyAt,applyAt);
        check(first.name().equals("Костяная глина") && CompassAtlasTest.field(flow,"canvasNameDialog")==null,"Rename canvas dialog did not apply");
        flow.canvas.addRecipe(recipe(source,"Anvil"),UI.scale(250,120));
        CraftFlow.Node firstNode=flow.graph.nodes.get(0); firstNode.values.put("i1","90");
        flow.graph.zoom=.5; flow.graph.panX=37; flow.graph.version++;
        JSONObject saved=flow.graph.json();
        IButton create=tool(flow,"new_canvas");
        Coord at=create.rootpos().add(create.sz.div(2)); drag(ui,at,at);
        check(source.canvases.entries().size()==2 && flow.graph.nodes.isEmpty(),"New canvas button cleared/reused the old canvas");
        CraftCanvases.Entry second=source.canvases.active();
        check(flow.graph==second.flow,"Editor did not switch to new canvas");
        source.canvases.rename(second,"Инструменты");
        flow.canvas.addRecipe(recipe(source,"Bone Saw"),UI.scale(270,130));
        Dropbox<?> picker=(Dropbox<?>)CompassAtlasTest.field(flow,"canvasPicker");
        at=picker.rootpos().add(UI.scale(30,12)); drag(ui,at,at);
        Coord firstRow=picker.rootpos().add(UI.scale(30,44)); drag(ui,firstRow,firstRow);
        check(source.canvases.active()==first && flow.graph==first.flow,"Picker did not return to original canvas");
        check(flow.graph.json().similar(saved),"Switch changed old values, nodes or zoom/pan");
        IButton undo=tool(flow,"undo"); at=undo.rootpos().add(undo.sz.div(2)); drag(ui,at,at);
        check(first.flow.nodes.isEmpty() && second.flow.nodes.size()==1,"Undo crossed canvas histories");
        IButton redo=tool(flow,"redo"); at=redo.rootpos().add(redo.sz.div(2)); drag(ui,at,at);
        check(first.flow.nodes.size()==1,"Original canvas redo history lost");
        // Show the saved list in the real Vulkan renderer.
        at=picker.rootpos().add(UI.scale(30,12)); drag(ui,at,at);
        CompassAtlasTest.capture(window,ui.root,size,"flow-canvases");
        Coord secondRow=picker.rootpos().add(UI.scale(30,76)); drag(ui,secondRow,secondRow);
        check(flow.graph==second.flow && flow.graph.nodes.size()==1,"Second saved canvas was lost");
        at=picker.rootpos().add(UI.scale(30,12)); drag(ui,at,at); drag(ui,firstRow,firstRow);
        JSONObject beforeDelete=first.flow.json();
        at=picker.rootpos().add(UI.scale(30,12)); drag(ui,at,at);
        Coord cross=picker.rootpos().add(picker.sz.x-UI.scale(16),UI.scale(76)); drag(ui,cross,cross);
        check(source.canvases.entries().size()==1 && flow.graph==first.flow && picker.sel==first,
            "Inactive canvas cross selected or retained the deleted canvas");
        check(first.flow.json().similar(beforeDelete),"Deleting another canvas modified the active graph");
        check(!((Map<?,?>)CompassAtlasTest.field(flow,"histories")).containsKey(second.id),"Deleted canvas history retained");
        at=create.rootpos().add(create.sz.div(2)); drag(ui,at,at);
        CraftCanvases.Entry temporary=source.canvases.active();
        flow.canvas.addRecipe(recipe(source,"Bone Saw"),UI.scale(270,130));
        at=picker.rootpos().add(UI.scale(30,12)); drag(ui,at,at); drag(ui,cross,cross);
        check(source.canvases.entries().size()==1 && flow.graph==first.flow && picker.sel==first,
            "Active canvas cross did not rebind the editor to its neighbor");
        check(!((Map<?,?>)CompassAtlasTest.field(flow,"histories")).containsKey(temporary.id),"Deleted active history retained");
        java.lang.reflect.Field popup=Dropbox.class.getDeclaredField("dl"); popup.setAccessible(true);
        check(popup.get(picker)==null,"Delete cross left its popup open");
        first.flow.remove(first.flow.nodes.get(0).id); first.flow.zoom=1; first.flow.panX=40; first.flow.panY=50;
        ((Deque<?>)CompassAtlasTest.field(flow,"undo")).clear(); ((Deque<?>)CompassAtlasTest.field(flow,"redo")).clear();
        flow.canvas.recalculate();
        System.out.println("PASS: create/switch/delete canvases by native mouse, independent undo/redo and view preservation");
    }
    static Coord location(NCraftFlow flow, String method, CraftFlow.Node node, String port) throws Exception {
        java.lang.reflect.Method m = port == null ? flow.canvas.getClass().getDeclaredMethod(method, CraftFlow.Node.class) : flow.canvas.getClass().getDeclaredMethod(method, CraftFlow.Node.class, String.class);
        m.setAccessible(true);
        return flow.canvas.rootpos().add((Coord)(port == null ? m.invoke(flow.canvas,node) : m.invoke(flow.canvas,node,port)));
    }
    static void awaitIcons(Source s) throws Exception {
        long deadline=System.nanoTime()+10_000_000_000L;
        while(true) {
            boolean ready=true;
            for(CraftFlow.Node n:s.flow.nodes) if(!n.output()) {
                AtlasCatalog.Recipe recipe=s.data.get(n.recipe);
                ready &= nurgling.tools.ItemIcons.get("",recipe.name,false)!=null;
                for(QualityModel.Port p:QualityModel.of(recipe).ports) ready &= nurgling.tools.ItemIcons.get(p.resource,p.name,false)!=null;
            }
            if(ready) return;
            if(System.nanoTime()>=deadline) {
                List<String> missing=new ArrayList<>();
                for(CraftFlow.Node n:s.flow.nodes) if(!n.output()) for(QualityModel.Port p:QualityModel.of(s.data.get(n.recipe)).ports)
                    if(nurgling.tools.ItemIcons.get(p.resource,p.name,false)==null) missing.add(p.name+" ("+p.resource+")");
                throw new AssertionError("Flow icons not loaded: "+missing);
            }
            Thread.sleep(20);
        }
    }
    static void render() throws Exception {
        Source s = fixture();
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open(); Windeye window = toolkit.window();
        Coord size = UI.scale(1370, 860); window.sizing(new Windeye.Sizing().fixsize(size)).show(false);
        nurgling.NUI ui = new nurgling.NUI(window, new Audio.Root(haven.iosys.audio.DummyAudio.instance), size, null);
        try {
            recipeHotbars(ui);
            menuClicks(ui);
            NCraftFlow flow = ui.root.add(new NCraftFlow(s), UI.scale(10,10)); flow.resize(UI.scale(1320,800)); flow.tick(1);
            transfers(ui, s, flow);
            canvasSwitching(ui, window, size, s, flow);
            // Native drag from the list onto the canvas, using the real mouse dispatcher.
            TextEntry search = (TextEntry)CompassAtlasTest.field(flow,"search"); search.settext("Anvil");
            Listbox<?> list = (Listbox<?>)CompassAtlasTest.field(flow,"list");
            Coord start = list.rootpos().add(UI.scale(80,15)), drop = flow.canvas.rootpos().add(UI.scale(320,230));
            CompassAtlasTest.capture(window,flow,size,"flow-empty");
            ui.dispatch(ui.root,new Widget.MouseDownEvent(start,1)); ui.dispatch(ui.root,new Widget.MouseMoveEvent(drop)); ui.dispatch(ui.root,new Widget.MouseUpEvent(drop,1));
            check(s.flow.nodes.size()==1 && s.flow.nodes.get(0).recipe.equals(recipe(s,"Anvil").resource),"Recipe drag failed");
            CraftFlow.Node anvil = s.flow.nodes.get(0); anvil.values.put("i1","40"); anvil.values.put("i2","100");
            // Header click expands, header drag moves without collapsing.
            CompassAtlasTest.capture(window,flow,size,"flow-collapsed");
            Coord head = flow.canvas.rootpos().add(UI.scale(365,245));
            ui.dispatch(ui.root,new Widget.MouseDownEvent(head,1)); ui.dispatch(ui.root,new Widget.MouseUpEvent(head,1));
            check(anvil.expanded,"Header did not expand recipe");
            anvil.x=320; anvil.y=230;
            CraftFlow.Node slippers=s.flow.add(recipe(s,"Bunny Slippers").resource,1080,230); slippers.expanded=true;
            slippers.values.put("i2","40");
            CraftFlow.Node output=s.flow.add("",1730,240);
            flow.canvas.fit(); flow.canvas.recalculate(); flow.tick(1); awaitIcons(s);
            CompassAtlasTest.capture(window,flow,size,"flow-before-connection");
            Coord from=location(flow,"output",anvil,null), to=location(flow,"input",slippers,"i1");
            ui.dispatch(ui.root,new Widget.MouseDownEvent(from,1)); ui.dispatch(ui.root,new Widget.MouseMoveEvent(to)); ui.dispatch(ui.root,new Widget.MouseUpEvent(to,1));
            check(s.flow.incoming(slippers.id,"i1")!=null,"Mouse connection failed");
            from=location(flow,"output",slippers,null); to=location(flow,"input",output,"q");
            ui.dispatch(ui.root,new Widget.MouseDownEvent(from,1)); ui.dispatch(ui.root,new Widget.MouseMoveEvent(to)); ui.dispatch(ui.root,new Widget.MouseUpEvent(to,1));
            check(s.flow.incoming(output.id,"q")!=null,"Output connection failed");
            near(result(s,output).value,56.25);
            Label status=(Label)CompassAtlasTest.field(flow,"status");
            check(status.text().equals("Success"),"Successful graph has verbose status");
            s.error="test save failure"; flow.tick(1);
            check(status.text().contains("test save failure"),"Save error missing from status");
            s.error=null; flow.tick(1); check(status.text().equals("Success"),"Resolved save error retained");
            CompassAtlasTest.capture(window,flow,size,"flow-chain");
            // Edit an actual external input plaque and restore its live character value.
            Object hit=((List<?>)CompassAtlasTest.field(flow.canvas,"hits")).stream().filter(h -> {
                try { QualityModel.Port p=(QualityModel.Port)CompassAtlasTest.field(h,"port"); return p!=null && p.name.equals("Dexterity") && CompassAtlasTest.field(h,"action").equals("edit"); }
                catch(Exception e) { throw new RuntimeException(e); }
            }).findFirst().orElseThrow();
            Coord field=flow.canvas.rootpos().add((Coord)CompassAtlasTest.field(hit,"at")).add(UI.scale(5,5));
            ui.dispatch(ui.root,new Widget.MouseDownEvent(field,1));
            TextEntry editor=(TextEntry)CompassAtlasTest.field(flow.canvas,"editor"); check(editor!=null,"Input field did not open");
            check(editor.sz.equals(CompassAtlasTest.field(hit,"size")),"Active quality field changes size");
            check(editor.c.equals(CompassAtlasTest.field(hit,"at")),"Active quality field jumps position");
            editor.settext("100"); near(result(s,output).value,62.5);
            editor.settext(""); near(result(s,output).value,56.25); editor.activate("");
            // Disconnect through the real port, then undo without dropping node values.
            Coord input=location(flow,"input",slippers,"i1");
            ui.dispatch(ui.root,new Widget.MouseDownEvent(input,3)); check(result(s,output).value==null,"Right click did not disconnect");
            check(!status.text().equals("Success") && status.text().contains("Bunny Slippers"),"Missing input did not show recipe error");
            tool(flow,"undo").click(); near(result(s,s.flow.node(output.id)).value,56.25);
            check(status.text().equals("Success"),"Corrected graph did not clear status error");
            tool(flow,"redo").click(); check(result(s,s.flow.node(output.id)).value==null,"Redo did not disconnect");
            tool(flow,"undo").click(); near(result(s,s.flow.node(output.id)).value,56.25);
            // Single expanded recipe at readable scale, including colored stats/tool plaques.
            s.flow.nodes.clear(); s.flow.edges.clear();
            CraftFlow.Node silk=s.flow.add(recipe(s,"Silk Filament").resource,300,180); silk.expanded=true;
            QualityModel sm=QualityModel.of(recipe(s,"Silk Filament")); silk.values.put(port(sm,"Silkworm Cocoon").id,"80"); silk.values.put(port(sm,"Water").id,"40");
            silk.values.put(port(sm,"Dexterity").id,"100");
            CraftFlow.Node silkOut=s.flow.add("",300,550); s.flow.connect(silk.id,silkOut.id,"q");
            flow.canvas.fit(); flow.canvas.recalculate(); awaitIcons(s);
            CompassAtlasTest.capture(window,flow,size,"flow-expanded");
            // Native source-card drags move only that card, persist, and undo/redo independently.
            for(String name : List.of("Cauldron","Dexterity","Water")) {
                Object sourceHit=hit(flow,"source",name);
                Coord sourceAt=flow.canvas.rootpos().add((Coord)CompassAtlasTest.field(sourceHit,"at")).add(UI.scale(12,12));
                Coord destination=sourceAt.add(UI.scale(30,70));
                String id=port(sm,name).id;
                ui.dispatch(ui.root,new Widget.MouseDownEvent(sourceAt,1));
                ui.dispatch(ui.root,new Widget.MouseMoveEvent(destination));
                ui.dispatch(ui.root,new Widget.MouseUpEvent(destination,1));
                check(silk.positions.containsKey(id),"Cannot drag quality source: "+name);
                near(silk.x,300); near(silk.y,180); near(result(s,silkOut).value,72.5);
                CraftFlow.Position moved=silk.positions.get(id);
                tool(flow,"undo").click(); silk=s.flow.node(silk.id); silkOut=s.flow.node(silkOut.id);
                check(!silk.positions.containsKey(id),"Undo retained source position");
                tool(flow,"redo").click(); silk=s.flow.node(silk.id); silkOut=s.flow.node(silkOut.id);
                near(silk.positions.get(id).x,moved.x); near(silk.positions.get(id).y,moved.y);
                CompassAtlasTest.capture(window,flow,size,"flow-moved-sources");
            }
            // Formula hover returns rendered math rather than a raw TeX string or input indices.
            Object formulaHit=hit(flow,"formula",null);
            Coord formulaAt=((Coord)CompassAtlasTest.field(formulaHit,"at")).add(UI.scale(12,12));
            Object tip=flow.canvas.tooltip(formulaAt,null);
            check(tip instanceof Tex,"Formula hover did not render LaTeX: "+tip);
            Tex math=(Tex)tip;
            Widget tipPreview=flow.add(new Widget(math.sz().add(UI.scale(12,12))) {
                public void draw(GOut g) { g.chcolor(new java.awt.Color(15,23,25)); g.frect(Coord.z,sz); g.chcolor(); g.image(math,UI.scale(6,6)); }
            },flow.canvas.c.add(formulaAt).add(UI.scale(0,26)));
            CompassAtlasTest.capture(window,flow,size,"flow-formula-tooltip"); tipPreview.destroy();
            check(flow.canvas.tooltip(Coord.z,null)==null,"Background has a persistent tooltip");
            java.lang.reflect.Method label=flow.canvas.getClass().getDeclaredMethod("inputLabel",CraftFlow.Node.class,QualityModel.Port.class); label.setAccessible(true);
            check(label.invoke(flow.canvas,silk,port(sm,"Silkworm Cocoon")).equals("Silkworm Cocoon ×1"),"Ingredient amount missing or technical index retained");
            // Connections can also start at an input handle and finish at an output.
            s.flow.disconnect(silkOut.id,"q");
            Coord reverseStart=location(flow,"input",silkOut,"q"), reverseEnd=location(flow,"output",silk,null);
            ui.dispatch(ui.root,new Widget.MouseDownEvent(reverseStart,1)); ui.dispatch(ui.root,new Widget.MouseMoveEvent(reverseEnd)); ui.dispatch(ui.root,new Widget.MouseUpEvent(reverseEnd,1));
            check(s.flow.incoming(silkOut.id,"q")!=null,"Reverse connection drag failed");
            near(result(s,silkOut).value,72.5);
            java.lang.reflect.Method editFormula=NCraftFlow.class.getDeclaredMethod("formulaWindow",CraftFlow.Node.class);
            editFormula.setAccessible(true); editFormula.invoke(flow,silk);
            Window dialog=(Window)ui.root.lchild; dialog.tick(1);
            TextEntry formula=dialog.getchild(TextEntry.class); check(formula!=null,"Formula editor missing");
            CompassAtlasTest.capture(window,dialog,size,"flow-formula");
            formula.settext("unknown+1"); button(dialog,"apply").click(); check(silk.formula.isEmpty(),"Unknown formula input accepted");
            formula.settext("mean(i1,i2)"); button(dialog,"apply").click(); near(result(s,silkOut).value,60);
            editFormula.invoke(flow,silk); dialog=(Window)ui.root.lchild;
            button(dialog,"reset").click(); near(result(s,silkOut).value,72.5);
            // Cursor-centered zoom and world-space pan do not alter results.
            Coord zoom=flow.canvas.rootpos().add(flow.canvas.sz.div(2)); double before=s.flow.zoom;
            ui.dispatch(ui.root,new Widget.MouseWheelEvent(zoom,-1,-1)); check(s.flow.zoom>before,"Wheel zoom failed");
            near(result(s,silkOut).value,72.5);
            flow.canvas.selected=silk.id; flow.canvas.deleteSelected(); check(s.flow.nodes.size()==1,"Delete selection failed");
            CompassAtlasTest.capture(window,flow,size,"flow-missing-input");
            // Reproduce all four canvas edges and compare every outside pixel to an empty canvas.
            s.flow.nodes.clear(); s.flow.edges.clear(); s.flow.panX=0; s.flow.panY=0; s.flow.zoom=1;
            flow.canvas.recalculate();
            CompassAtlasTest.capture(window,flow,size,"flow-clip-before");
            CraftFlow.Node edgeA=s.flow.add(recipe(s,"Silk Filament").resource,-80,-20); edgeA.expanded=true;
            CraftFlow.Node edgeB=s.flow.add(recipe(s,"Silk Filament").resource,flow.canvas.sz.x/UI.scale(1.0)-100,flow.canvas.sz.y/UI.scale(1.0)-100); edgeB.expanded=true;
            s.flow.connect(edgeA.id,edgeB.id,"i1"); flow.canvas.recalculate(); awaitIcons(s);
            CompassAtlasTest.capture(window,flow,size,"flow-clip-after");
            java.awt.image.BufferedImage clean=preview("flow-clip-before"), clipped=preview("flow-clip-after");
            Coord top=flow.canvas.rootpos();
            Widget table=(Widget)CompassAtlasTest.field(flow,"materials");
            for(int y=0;y<size.y;y++) for(int x=0;x<size.x;x++) if(!new Coord(x,y).isect(top,flow.canvas.sz) && !new Coord(x,y).isect(table.rootpos(),table.sz)
                && !new Coord(x,y).isect(status.rootpos(),new Coord(flow.canvas.sz.x,UI.scale(22))))
                check(clean.getRGB(x,y)==clipped.getRGB(x,y),"Canvas leaked at "+x+","+y);
            java.lang.reflect.Method hitTest=flow.canvas.getClass().getDeclaredMethod("at",Coord.class); hitTest.setAccessible(true);
            check(hitTest.invoke(flow.canvas,new Coord(-10,10))==null,"Outside node receives clicks");
            // Connected source card disappears; disconnect restores its original manual value.
            s.flow.nodes.clear(); s.flow.edges.clear();
            CraftFlow.Node upstream=s.flow.add(recipe(s,"Anvil").resource,260,150); upstream.values.put("i1","40"); upstream.values.put("i2","100");
            CraftFlow.Node kilnNode=s.flow.add(recipe(s,"Kiln").resource,780,150); kilnNode.expanded=true; kilnNode.values.put("i1","30");
            s.flow.connect(upstream.id,kilnNode.id,"i1");
            flow.canvas.fit(); flow.canvas.recalculate(); awaitIcons(s); near(result(s,kilnNode).value,85);
            CompassAtlasTest.capture(window,flow,size,"flow-kiln-connected");
            double scale=UI.scale(1.0)*s.flow.zoom;
            Coord plaque=top.add((int)Math.round((kilnNode.x-238+s.flow.panX)*scale),(int)Math.round((kilnNode.y+67+s.flow.panY)*scale));
            int sampleX=plaque.x+(int)(8*scale), sampleY=plaque.y+(int)(8*scale);
            int rgb=preview("flow-kiln-connected").getRGB(sampleX,sampleY)&0xffffff;
            check(rgb==0x111a1d || rgb==0x202c2e,"Connected manual plaque still visible");
            Coord kilnInput=location(flow,"input",kilnNode,"i1"); ui.dispatch(ui.root,new Widget.MouseDownEvent(kilnInput,3));
            near(result(s,kilnNode).value,30);
            CompassAtlasTest.capture(window,flow,size,"flow-kiln-manual");
            check((preview("flow-kiln-manual").getRGB(sampleX,sampleY)&0xffffff)!=rgb,"Manual source was not restored");
            s.flow.nodes.clear(); s.flow.edges.clear(); s.live.put("Intelligence",192.0); s.live.put("Masonry",203.0);
            CraftFlow.Node bone=legacyBoneClay(s), kiln=s.flow.add(recipe(s,"Kiln").resource,820,360), end=s.flow.add("",1190,360);
            s.flow.connect(bone.id,kiln.id,"i1"); s.flow.connect(kiln.id,end.id,"q");
            ui.dispatch(ui.root,new Widget.TickEvent(1));
            near(((CraftFlow.Result)((Map<?,?>)CompassAtlasTest.field(flow.canvas,"results")).get(end.id)).value,175);
            flow.canvas.fit(); awaitIcons(s);
            CompassAtlasTest.capture(window,flow,size,"flow-automatic-bone-clay");
            FlowMaterials.Summary summary=(FlowMaterials.Summary)CompassAtlasTest.field(table,"summary");
            check(summary.rows.size()==3 && summary.rows.stream().noneMatch(r -> r.material.name.equals("Clay")),"Connected clay counted as external material");
            // Repeated recipes combine in the persistent bottom table, with independent scrolling.
            s.flow.add(recipe(s,"Bone Clay").resource,300,530);
            s.flow.add(recipe(s,"Caviar Canapé").resource,780,530);
            s.flow.add(recipe(s,"Bunny Slippers").resource,780,690);
            flow.canvas.recalculate(); flow.canvas.fit(); awaitIcons(s);
            summary=(FlowMaterials.Summary)CompassAtlasTest.field(table,"summary");
            check(summary.rows.stream().anyMatch(r -> r.material.name.equals("Bone Ash") && r.quantity().equals("10")),"Table did not combine duplicate ingredients");
            CompassAtlasTest.capture(window,flow,size,"flow-materials-summary");
            Listbox<?> materialRows=(Listbox<?>)CompassAtlasTest.field(table,"rows");
            double oldZoom=s.flow.zoom;
            ui.dispatch(ui.root,new Widget.MouseWheelEvent(materialRows.rootpos().add(UI.scale(40,15)),1,1));
            check(materialRows.sb.val>0 && s.flow.zoom==oldZoom,"Table wheel zooms canvas or fails to scroll");
            Object quantityHit=hit(flow,"quantity",null);
            Coord quantityAt=flow.canvas.rootpos().add((Coord)CompassAtlasTest.field(quantityHit,"at")).add(UI.scale(5,5));
            ui.dispatch(ui.root,new Widget.MouseDownEvent(quantityAt,1));
            TextEntry quantityEditor=(TextEntry)CompassAtlasTest.field(flow.canvas,"editor");
            check(quantityEditor!=null,"Result quantity field did not open");
            check(quantityEditor.sz.equals(CompassAtlasTest.field(quantityHit,"size")),"Active quantity field changes size");
            check(quantityEditor.c.equals(CompassAtlasTest.field(quantityHit,"at")),"Active quantity field jumps position");
            quantityEditor.settext("400");
            Coord selectionStart=quantityEditor.rootpos().add(1,quantityEditor.sz.y/2), selectionEnd=quantityEditor.rootpos().add(quantityEditor.sz.x-1,quantityEditor.sz.y/2);
            ui.dispatch(ui.root,new Widget.MouseDownEvent(selectionStart,1));
            ui.dispatch(ui.root,new Widget.MouseMoveEvent(selectionEnd)); ui.dispatch(ui.root,new Widget.MouseUpEvent(selectionEnd,1));
            check(quantityEditor.buf.mark()==0 && quantityEditor.buf.point()==3,"Centered field selection uses wrong text coordinates");
            quantityEditor.settext("4"); near(result(s,end).value,175);
            CompassAtlasTest.capture(window,flow,size,"flow-input-active");
            summary=(FlowMaterials.Summary)CompassAtlasTest.field(table,"summary");
            check(summary.rows.stream().anyMatch(r -> r.material.name.equals("Bone Ash") && r.quantity().equals("25")),"Result quantity did not scale upstream ingredients");
            quantityEditor.settext("0"); check(end.crafts==4,"Invalid quantity replaced the valid count");
            quantityEditor.settext("4"); quantityEditor.activate("4");
            tool(flow,"undo").click(); check(s.flow.node(end.id).crafts==1,"Quantity undo failed");
            tool(flow,"redo").click(); check(s.flow.node(end.id).crafts==4,"Quantity redo failed");
            materialRows.sb.val=0;
            CompassAtlasTest.capture(window,flow,size,"flow-result-quantity");
            // Small zoom used to stop shrinking the font while rows continued shrinking.
            java.lang.reflect.Method fieldFont=flow.canvas.getClass().getDeclaredMethod("fieldFont"); fieldFont.setAccessible(true);
            for(double smallZoom : new double[]{.5,.35}) {
                s.flow.zoom=smallZoom;
                Text.Foundry font=(Text.Foundry)fieldFont.invoke(flow.canvas);
                check(font.m.getHeight()<=Math.floor(18*UI.scale(1.0)*smallZoom),"Zoomed text outgrows its label row");
                CompassAtlasTest.capture(window,flow,size,"flow-zoom-"+(int)(smallZoom*100));
                Object smallHit=hit(flow,"quantity",null);
                Coord smallAt=flow.canvas.rootpos().add((Coord)CompassAtlasTest.field(smallHit,"at"))
                    .add(((Coord)CompassAtlasTest.field(smallHit,"size")).div(2));
                ui.dispatch(ui.root,new Widget.MouseDownEvent(smallAt,1));
                TextEntry smallEditor=(TextEntry)CompassAtlasTest.field(flow.canvas,"editor");
                check(smallEditor!=null && smallEditor.sz.equals(CompassAtlasTest.field(smallHit,"size")),"Zoomed editor outgrows its field");
                smallEditor.settext("5"); check(s.flow.node(end.id).crafts==5,"Zoomed quantity is not editable");
                CompassAtlasTest.capture(window,flow,size,"flow-zoom-edit-"+(int)(smallZoom*100));
                smallEditor.activate("5");
            }
            flow.destroy();
            System.out.println("PASS: real calculator Vulkan rendering, drag/drop, expansion, zoom, deletion and connected output");
        } finally { ui.destroy(); window.dispose(); toolkit.dispose(); }
    }
    static java.awt.image.BufferedImage preview(String name) throws Exception {
        return javax.imageio.ImageIO.read(Paths.get("build/compass-atlas-preview",name+"-"+UI.scale(100)+".png").toFile());
    }
    static Object hit(NCraftFlow flow,String action,String portName) throws Exception {
        for(Object h : (List<?>)CompassAtlasTest.field(flow.canvas,"hits")) {
            QualityModel.Port p=(QualityModel.Port)CompassAtlasTest.field(h,"port");
            if(CompassAtlasTest.field(h,"action").equals(action) && (portName==null || p!=null && p.name.equals(portName))) return h;
        }
        throw new AssertionError("Missing hit: "+action+" "+portName);
    }
    static CraftFlow.Node legacyBoneClay(Source s) {
        CraftFlow.Node bone=s.flow.add(recipe(s,"Bone Clay").resource,300,180); bone.expanded=true;
        bone.inputSignature="db6a7ee8-2c91-378f-8cdc-fc8ec77785cb";
        bone.values.put("i1","150"); bone.values.put("i2","300"); bone.values.put("i3","75");
        return bone;
    }
    static Button button(Widget parent,String key) {
        String label=nurgling.i18n.L10n.get("flow."+key);
        for(Widget w=parent.child;w!=null;w=w.next) if(w instanceof Button && ((Button)w).text.text.equals(label)) return (Button)w;
        throw new AssertionError("Missing button: "+label);
    }
    static IButton tool(NCraftFlow flow,String key) throws Exception {
        return (IButton)((Map<?,?>)CompassAtlasTest.field(flow,"toolbar")).get(key);
    }
    public static void main(String[] args) {
        try {
            System.setProperty("haven.prefs.compass-atlas-test","true");
            nurgling.NConfig.getGlobalInstance(); nurgling.i18n.L10n.setLocale(new Locale("ru"));
            data(); if(Arrays.asList(args).contains("--render")) render(); System.exit(0);
        } catch(Throwable e) { e.printStackTrace(); System.exit(1); }
    }
}
