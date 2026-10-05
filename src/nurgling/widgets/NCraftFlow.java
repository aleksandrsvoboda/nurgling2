package nurgling.widgets;

import haven.*;
import haven.Button;
import haven.Label;
import haven.Window;
import nurgling.NWindowDeco;
import nurgling.craft.*;
import nurgling.i18n.L10n;
import nurgling.styles.UITheme;
import nurgling.tools.ItemIcons;
import nurgling.widgets.cookbook.HintTextEntry;
import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import org.json.JSONObject;

/** Native flow editor. World-space layout is independent of UI scale and canvas zoom. */
public class NCraftFlow extends Window {
    private final AtlasSource source;
    public CraftFlow graph;
    private final CraftCanvases canvases;
    private final Dropbox<CraftCanvases.Entry> canvasPicker;
    private final Map<String, History> histories = new HashMap<>();
    private Window canvasNameDialog;
    public final Canvas canvas;
    private final List<AtlasCatalog.Recipe> rows = new ArrayList<>();
    private final RecipeListbox list;
    private final HintTextEntry search;
    private final Label status;
    private final MaterialsTable materials;
    private final Map<String, IButton> toolbar = new LinkedHashMap<>();
    private final Map<String, Tex> textCache = new LinkedHashMap<String, Tex>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Tex> entry) {
            if(size() <= 1024) return false; entry.getValue().dispose(); return true;
        }
    };
    private static final class History { final Deque<String> undo=new ArrayDeque<>(), redo=new ArrayDeque<>(); }
    private Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private int catalogVersion = -1;
    private Window formulaDialog;
    private String actionError;
    private int actionErrorVersion;
    private static final Color GREEN = new Color(92, 213, 126), ORANGE = new Color(245, 154, 66),
        PURPLE = new Color(183, 112, 213), BLUE = new Color(70, 187, 220), BLACK = new Color(15, 23, 25);
    private static String t(String key) { return L10n.get("flow." + key); }
    private Tex text(String value, Color color) {
        String key = color.getRGB() + ":" + value;
        return textCache.computeIfAbsent(key, s -> Text.render(value, color).tex());
    }
    private static String number(Double value) { return value == null ? "?" : String.format(Locale.ROOT, "%.2f", value); }
    private static Color valueColor(String value) {
        if(value.isBlank()) return UITheme.MUTED;
        try { CraftFlow.enteredValue(value); return GREEN; } catch(IllegalArgumentException e) { return new Color(236, 108, 95); }
    }
    public NCraftFlow(AtlasSource source) {
        super(UI.scale(1140, 710), t("title")); this.source = source;
        canvases = source.canvases() != null ? source.canvases()
            : new CraftCanvases(source.flow() == null ? new CraftFlow() : source.flow());
        graph = canvases.active().flow;
        useHistory();
        posmem("craft-flow"); setfocusctl(true);
        search = add(new HintTextEntry(UI.scale(235), t("search"), this::filter), UI.scale(10, 10));
        canvas = add(new Canvas(), UI.scale(260, 50));
        list = add(new RecipeListbox(source, UI.scale(235), 20, UI.scale(30)) {
            protected int listitems() { return rows.size(); }
            protected AtlasCatalog.Recipe listitem(int i) { return rows.get(i); }
            protected void drawitem(GOut g, AtlasCatalog.Recipe r, int i) {
                ItemIcons.draw(g, ItemIcons.get("", r.name, false), UI.scale(3, 2), UI.scale(26));
                g.image(text(r.name, UITheme.TEXT), UI.scale(35, 7));
            }
        }, UI.scale(10, 50));
        int x = 260;
        tool("new_canvas", "flow-new-canvas", x, this::newCanvas); x += 40;
        tool("rename_canvas", "flow-rename-canvas", x, this::renameCanvas); x += 48;
        tool("add", "area-add", x, () -> { if(list.sel != null) canvas.addRecipe(list.sel, canvas.sz.div(2)); }); x += 40;
        tool("output", "area-export", x, canvas::addOutput); x += 40;
        tool("fit", "expand", x, canvas::fit); x += 48;
        tool("undo", "flow-undo", x, () -> restore(undo, redo)); x += 40;
        tool("redo", "flow-redo", x, () -> restore(redo, undo)); x += 48;
        tool("delete", "trash", x, canvas::deleteSelected);
        canvasPicker = add(new Dropbox<CraftCanvases.Entry>(UI.scale(220),8,UI.scale(32)) {
            protected int listitems() { return canvases.entries().size(); }
            protected CraftCanvases.Entry listitem(int i) { return canvases.entries().get(i); }
            protected void drawitem(GOut g, CraftCanvases.Entry entry, int i) {
                Tex label=text(canvasName(entry),UITheme.TEXT);
                g.image(label,new Coord(UI.scale(8),(sz.y-label.sz().y)/2));
            }
            protected void drawlistitem(GOut g, CraftCanvases.Entry entry, int i) {
                int actionWidth = UI.scale(32), side = UI.scale(14);
                drawitem(g.reclip(Coord.z,new Coord(g.sz().x-actionWidth,itemh)),entry,i);
                nurgling.styles.GeneratedButtons.close(g,
                    new Coord(g.sz().x-(actionWidth+side)/2,(itemh-side)/2),side);
            }
            protected boolean listitemclick(CraftCanvases.Entry entry, Coord c, int button, int width) {
                if(button != 1 || !c.isect(new Coord(width-UI.scale(32),0),new Coord(UI.scale(32),itemh))) return false;
                deleteCanvas(entry);
                return true;
            }
            public void change(CraftCanvases.Entry entry) {
                if(entry != null) switchCanvas(entry);
            }
        },Coord.z);
        canvasPicker.sel=canvases.active(); canvasPicker.settip(t("canvases"));
        status = add(new Label("Success"), UI.scale(260, 678));
        materials = add(new MaterialsTable(),Coord.z);
        filter(); arrange();
    }
    private String canvasName(CraftCanvases.Entry entry) {
        return entry.name().isBlank() ? t("canvas")+" "+(canvases.entries().indexOf(entry)+1) : entry.name();
    }
    private void useHistory() {
        History history=histories.computeIfAbsent(canvases.active().id,k -> new History());
        undo=history.undo; redo=history.redo;
    }
    private void closeCanvasName() {
        if(canvasNameDialog!=null) { canvasNameDialog.destroy(); canvasNameDialog=null; }
    }
    private void switchCanvas(CraftCanvases.Entry entry) {
        if(entry==canvases.active() && graph==entry.flow) return;
        closeCanvasName(); closeFormula(); list.cancelDrag(); canvas.cancelInteraction(); canvas.endEdit();
        canvases.select(entry); graph=entry.flow; useHistory(); canvasPicker.sel=entry;
        canvas.selected=null; canvas.dropRecipe=null; canvas.dropAt=null; canvas.hits.clear(); canvas.models.clear();
        actionError=null; canvas.recalculate(); source.flush();
    }
    private void newCanvas() {
        if(canvases.entries().size()>=CraftCanvases.LIMIT) { showError(t("canvas_limit")); return; }
        switchCanvas(canvases.create(""));
    }
    private void deleteCanvas(CraftCanvases.Entry entry) {
        if(entry == canvases.active()) canvas.endEdit();
        canvases.remove(entry);
        switchCanvas(canvases.active());
        histories.remove(entry.id);
        source.flush();
    }
    private void renameCanvas() {
        closeCanvasName();
        CraftCanvases.Entry entry=canvases.active();
        Window dialog=new Window(UI.scale(330,110),t("rename_canvas")) {
            public void wdgmsg(Widget sender,String msg,Object... args) {
                if(sender==this && msg.equals("close")) { closeCanvasName(); return; }
                super.wdgmsg(sender,msg,args);
            }
        };
        canvasNameDialog=parent.add(dialog,c.add(UI.scale(200,100)));
        Label error=dialog.add(new Label(""),UI.scale(10,38));
        class NameEntry extends TextEntry {
            NameEntry() { super(UI.scale(310),canvasName(entry)); }
            void saveName() {
                try { canvases.rename(entry,text()); closeCanvasName(); source.flush(); }
                catch(IllegalArgumentException e) { error.settext(t("canvas_name_invalid")); }
            }
            public void activate(String value) { saveName(); }
        }
        NameEntry name=dialog.add(new NameEntry(),UI.scale(10,10));
        dialog.add(new Button(UI.scale(130),t("apply"),name::saveName),UI.scale(190,64));
        dialog.setfocus(name);
    }
    private void tool(String key, String icon, int x, Runnable action) {
        IButton button = add(new NIconButton(icon, 32, 22), UI.scale(x, 10));
        button.action(action); button.settip(t(key)); toolbar.put(key, button);
    }
    protected Deco makedeco() { return new NWindowDeco(false).dragsize(true); }
    public void resize(Coord size) { super.resize(new Coord(Math.max(UI.scale(1050), size.x), Math.max(UI.scale(500), size.y))); if(canvas != null) arrange(); }
    private void arrange() {
        Coord size = csz(); if(list != null) list.resize(new Coord(UI.scale(235), size.y - UI.scale(64)));
        int tableHeight=UI.scale(size.y < UI.scale(620) ? 126 : 174);
        canvas.resize(new Coord(size.x - UI.scale(270), size.y - UI.scale(100) - tableHeight));
        if(canvasPicker != null) canvasPicker.move(new Coord(canvas.c.x+canvas.sz.x-canvasPicker.sz.x,UI.scale(10)));
        if(materials != null) {
            materials.move(canvas.c.add(0,canvas.sz.y+UI.scale(6)));
            materials.resize(new Coord(canvas.sz.x,tableHeight));
        }
        if(status != null) status.move(new Coord(UI.scale(260), size.y - UI.scale(29)));
    }
    private void filter() {
        rows.clear(); String q = search == null ? "" : search.text();
        for(AtlasCatalog.Recipe r : source.catalog().recipes()) if(r.matches(q)) rows.add(r);
        rows.sort(Comparator.comparing(r -> r.name));
        if(list != null) { list.sb.val = 0; if(list.sel == null && !rows.isEmpty()) list.change(rows.get(0)); }
        catalogVersion = source.catalog().version;
    }
    private void checkpoint() { undo.push(graph.json().toString()); while(undo.size() > 40) undo.removeLast(); redo.clear(); }
    private void restore(Deque<String> from, Deque<String> to) {
        closeFormula();
        canvas.cancelInteraction(); canvas.endEdit(); if(from.isEmpty()) return;
        to.push(graph.json().toString()); graph.load(new JSONObject(from.pop())); canvas.selected = null; canvas.recalculate();
    }
    public void tick(double dt) {
        super.tick(dt);
        if(catalogVersion != source.catalog().version) { filter(); canvas.models.clear(); canvas.recalculate(); }
        updateStatus();
    }
    private void showError(String message) {
        actionError=message; actionErrorVersion=graph.version; updateStatus();
    }
    private void updateStatus() {
        if(status==null) return;
        if(actionErrorVersion!=graph.version) actionError=null;
        String error=source.saveError()==null ? actionError : t("save_error")+": "+source.saveError();
        if(error==null) for(CraftFlow.Node node : graph.nodes) {
            CraftFlow.Result result=canvas.results.get(node.id);
            if(result!=null && result.value==null) {
                AtlasCatalog.Recipe recipe=source.catalog().get(node.recipe);
                error=(node.output() ? t("result") : recipe==null ? t("missing") : recipe.name)+": "+result.error;
                break;
            }
        }
        if(error==null && materials!=null && !materials.summary.unresolved.isEmpty())
            error=t("materials_unresolved")+": "+String.join(", ",materials.summary.unresolved);
        String message=error==null ? "Success" : error;
        int limit=Math.max(35,canvas.sz.x/UI.scale(7));
        status.settext(message.length()>limit ? message.substring(0,limit-1)+"…" : message);
        status.settip(error);
    }
    private void closeFormula() { if(formulaDialog != null) { if(formulaDialog.parent != null) formulaDialog.destroy(); formulaDialog = null; } }
    public void hide() { closeCanvasName(); list.cancelDrag(); closeFormula(); canvas.cancelInteraction(); canvas.endEdit(); source.flush(); super.hide(); }
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if(sender == this && msg.equals("close")) { hide(); return; } super.wdgmsg(sender, msg, args);
    }
    public void dispose() { closeCanvasName(); closeFormula(); canvas.endEdit(); source.flush(); textCache.values().forEach(Tex::dispose); textCache.clear(); super.dispose(); }

    private final class MaterialsTable extends Widget {
        FlowMaterials.Summary summary=new FlowMaterials.Summary(List.of(),List.of());
        final Listbox<FlowMaterials.Row> rows;
        MaterialsTable() {
            super(UI.scale(860,174));
            rows=add(new Listbox<FlowMaterials.Row>(sz.x,4,UI.scale(27)) {
                protected int listitems() { return summary.rows.size(); }
                protected FlowMaterials.Row listitem(int i) { return summary.rows.get(i); }
                protected void drawitem(GOut g,FlowMaterials.Row row,int i) {
                    AtlasCatalog.Material m=row.material;
                    ItemIcons.draw(g,ItemIcons.get(m.resource,m.name,m.category),UI.scale(6,2),UI.scale(23));
                    int quantityX=sz.x-UI.scale(145);
                    g.reclip(UI.scale(36,0),new Coord(Math.max(1,quantityX-UI.scale(42)),itemh)).image(text(m.name,UITheme.TEXT),UI.scale(0,6));
                    g.reclip(new Coord(quantityX,0),new Coord(UI.scale(130),itemh)).image(text(row.quantity(),row.unknown ? UITheme.ACCENT : UITheme.TEXT),UI.scale(0,6));
                }
                public Object tooltip(Coord c,Widget previous) {
                    FlowMaterials.Row row=itemat(c);
                    return row==null ? null : row.material.name+" — "+row.quantity()
                        +(row.optional ? " · "+t("materials_optional") : "");
                }
            },UI.scale(0,44));
        }
        void refresh() {
            summary=FlowMaterials.summarize(graph,source.catalog(),canvas.models);
            rows.sb.max=Math.max(0,summary.rows.size()-rows.h);
            rows.sb.val=Math.min(rows.sb.val,rows.sb.max);
        }
        public void resize(Coord size) { super.resize(size); rows.resize(new Coord(size.x,size.y-UI.scale(44))); }
        public void draw(GOut g) {
            g.chcolor(UITheme.PANEL); g.frect(Coord.z,sz); g.chcolor();
            g.image(text(t("materials_title"),UITheme.ACCENT),UI.scale(7,3));
            String description=t("materials_basis");
            if(!summary.unresolved.isEmpty()) description+=" · "+t("materials_unresolved")+": "+summary.unresolved.size();
            g.image(text(description,UITheme.MUTED),UI.scale(7,23));
            g.image(text(t("materials_amount"),UITheme.ACCENT),new Coord(sz.x-UI.scale(145),UI.scale(3)));
            super.draw(g);
            if(summary.rows.isEmpty()) g.image(text(t(summary.unresolved.isEmpty() ? "materials_empty" : "materials_unresolved"),UITheme.MUTED),UI.scale(10,54));
        }
        public Object tooltip(Coord c,Widget previous) {
            if(c.y<UI.scale(44) && !summary.unresolved.isEmpty()) return t("materials_unresolved")+": "+String.join(", ",summary.unresolved);
            return super.tooltip(c,previous);
        }
    }

    public final class Canvas extends Widget implements DropTarget {
        final Map<String, QualityModel> models = new HashMap<>();
        Map<String, CraftFlow.Result> results = new HashMap<>();
        final List<Hit> hits = new ArrayList<>();
        final Map<String, Double> live = new HashMap<>();
        public String selected;
        String linking;
        Hit linkingInput;
        Coord mouse = Coord.z, down;
        CraftFlow.Node moving; double initialX, initialY; boolean panning, moved;
        Hit movingSource;
        final FlowPainter painter = new FlowPainter();
        UI.Grab grab;
        TextEntry editor;
        AtlasCatalog.Recipe dropRecipe; Coord dropAt;
        double refresh;
        Canvas() { super(UI.scale(860, 610)); setcanfocus(true); }
        public boolean drophover(Coord c, boolean hovering, Object thing) {
            dropRecipe = null; dropAt = null;
            if(!hovering || !c.isect(Coord.z, sz)) return false;
            try { dropRecipe = source.recipe(thing); } catch(Loading ignored) {}
            if(dropRecipe == null) return false;
            dropAt = c; return true;
        }
        public boolean dropthing(Coord c, Object thing) {
            dropRecipe = null; dropAt = null;
            if(!c.isect(Coord.z, sz)) return false;
            try {
                AtlasCatalog.Recipe recipe = source.recipe(thing);
                if(recipe == null) return false;
                if(source.catalog().get(recipe.resource) == null) source.catalog().put(recipe);
                addRecipe(recipe, c);
                return true;
            } catch(Loading ignored) { return false; }
        }
        double scale() { return UI.scale(1.0) * graph.zoom; }
        Coord screen(double x, double y) { return new Coord((int)Math.round((x + graph.panX) * scale()), (int)Math.round((y + graph.panY) * scale())); }
        double wx(int x) { return x / scale() - graph.panX; }
        double wy(int y) { return y / scale() - graph.panY; }
        QualityModel model(CraftFlow.Node n) {
            AtlasCatalog.Recipe recipe = source.catalog().get(n.recipe);
            return recipe == null ? null : models.computeIfAbsent(n.recipe, k -> QualityModel.of(recipe));
        }
        public void recalculate() {
            live.clear();
            for(CraftFlow.Node n : graph.nodes) if(!n.output() && model(n) != null) for(QualityModel.Port p : model(n).ports) {
                String key = p.kind + ":" + p.name;
                if(!live.containsKey(key)) live.put(key, source.liveQuality(p));
            }
            results = graph.evaluate(source.catalog(), port -> {
                String key = port.kind + ":" + port.name;
                if(!live.containsKey(key)) live.put(key, source.liveQuality(port));
                return live.get(key);
            }, models);
            if(materials != null) materials.refresh();
            updateStatus();
        }
        Double automatic(QualityModel.Port p) { return live.get(p.kind + ":" + p.name); }
        public void tick(double dt) { super.tick(dt); refresh -= dt; if(refresh <= 0) { refresh = .5; recalculate(); } }
        public CraftFlow.Node addRecipe(AtlasCatalog.Recipe recipe, Coord at) {
            endEdit(); checkpoint();
            try { CraftFlow.Node n = graph.add(recipe.resource, wx(at.x), wy(at.y)); selected = n.id; recalculate(); return n; }
            catch(IllegalArgumentException e) { showError(t("limit")); return null; }
        }
        void addOutput() {
            endEdit(); checkpoint();
            try {
                CraftFlow.Node previous = graph.node(selected);
                CraftFlow.Node n = graph.add("", previous == null ? wx(sz.x / 2) : previous.x + 600, previous == null ? wy(sz.y / 2) : previous.y);
                if(previous != null && !previous.output()) graph.connect(previous.id, n.id, "q");
                selected = n.id; recalculate(); fit();
            } catch(IllegalArgumentException e) { showError(t("limit")); }
        }
        void cancelInteraction() {
            if(grab != null) { grab.remove(); grab = null; }
            linking = null; linkingInput = null; moving = null; movingSource = null; panning = false;
        }
        public void deleteSelected() { closeFormula(); cancelInteraction(); endEdit(); if(selected != null) { checkpoint(); graph.remove(selected); selected = null; recalculate(); } }
        public void fit() {
            endEdit(); if(graph.nodes.isEmpty()) return;
            double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
            for(CraftFlow.Node n : graph.nodes) {
                minX = Math.min(minX, n.x); minY = Math.min(minY, n.y);
                maxX = Math.max(maxX, n.x + 280); maxY = Math.max(maxY, n.y + height(n));
                if(n.expanded && model(n) != null) for(QualityModel.Port p : model(n).ports) if(graph.incoming(n.id, p.id) == null) {
                    CraftFlow.Position at = sourcePosition(n,p);
                    minX = Math.min(minX, n.x + at.x); minY = Math.min(minY, n.y + at.y);
                    maxX = Math.max(maxX, n.x + at.x + 212); maxY = Math.max(maxY, n.y + at.y + 60);
                }
            }
            graph.zoom = Math.max(.35, Math.min(1.25, Math.min(sz.x / UI.scale(1.0) / (maxX - minX + 70), sz.y / UI.scale(1.0) / (maxY - minY + 70))));
            graph.panX = -minX + 35; graph.panY = -minY + 35; graph.version++;
        }
        double height(CraftFlow.Node n) {
            if(n.output()) return 106;
            if(!n.expanded || model(n) == null) return 72;
            QualityModel m = model(n);
            return 126 + 66 * Math.max(m.ports.stream().filter(p -> p.kind == QualityModel.Kind.MATERIAL).count(), m.ports.stream().filter(p -> p.kind == QualityModel.Kind.TOOL).count());
        }
        void panel(GOut g, double x, double y, double w, double h, Color bg, Color border) {
            Coord at = screen(x, y), size = new Coord((int)(w * scale()), (int)(h * scale()));
            painter.panel(g, at, size, bg, border, scale(), sz);
        }
        void label(GOut g, String s, double x, double y, double width, Color color) {
            String display = s.length() > 160 ? s.substring(0, 157) + "..." : s;
            String key = "label:" + scale() + ":" + color.getRGB() + ":" + display;
            Tex tex = textCache.computeIfAbsent(key, unused -> fieldFont().render(display, color).tex());
            Coord at = screen(x, y);
            // Keep each label inside its world-space row even when zoomed far out.
            GOut clip = g.reclip(at, fieldSize(width,18));
            clip.image(tex, Coord.z);
        }
        void icon(GOut g, String resource, String name, double x, double y) {
            ItemIcons.draw(g, ItemIcons.get(resource, name, false), screen(x, y), Math.max(1, (int)(25 * scale())));
        }
        Text.Foundry fieldFont() {
            // A screen-space minimum made text outgrow its card below 2/3 zoom.
            float size=(float)Math.max(1,12*scale());
            int rowHeight=Math.max(1,(int)(18*scale()));
            Text.Foundry font=new Text.Foundry(nurgling.styles.UIFont.regular.deriveFont(size)).aa(true);
            // Font metrics round to whole pixels; keep that rounding inside the row too.
            while(font.height()>rowHeight && size>1) {
                size=Math.max(1,size-.25f);
                font=new Text.Foundry(nurgling.styles.UIFont.regular.deriveFont(size)).aa(true);
            }
            return font;
        }
        Coord fieldSize(double width,double height) { return new Coord(Math.max(1,(int)(width*scale())),Math.max(1,(int)(height*scale()))); }
        void valueField(GOut g,String value,double x,double y,double width,double height,Color color) {
            panel(g,x,y,width,height,BLACK,UITheme.LINE);
            String key="field:"+scale()+":"+color.getRGB()+":"+value;
            Tex tex=textCache.computeIfAbsent(key,k -> fieldFont().render(value,color).tex());
            Coord size=fieldSize(width,height); int padding=Math.max(2,(int)(6*scale()));
            GOut content=g.reclip(screen(x,y).add(padding,0),new Coord(Math.max(1,size.x-2*padding),size.y));
            content.image(tex,new Coord(Math.max(0,(content.sz().x-tex.sz().x)/2),(size.y-tex.sz().y)/2));
        }
        void wire(GOut g, Coord a, Coord b, Color color) {
            painter.curve(g,a,new Coord(1,0),b,new Coord(-1,0),color,scale(),sz);
        }
        void dot(GOut g, Coord at, Color color) { int r = Math.max(3, (int)(5 * scale())); g.chcolor(color); g.fellipse(at, new Coord(r, r)); g.chcolor(); }
        void connection(GOut g, CraftFlow.Node from, CraftFlow.Node to, String port) {
            Coord a = output(from), b = input(to, port);
            QualityModel m = model(to); QualityModel.Port p = m == null ? null : m.port(port);
            Coord side = to.expanded && p != null ? inputSide(p) : new Coord(-1,0);
            if(side.x == -1 && b.x < a.x + 32 * scale()) {
                double lane = to.y > from.y + height(from) + 40 ? (from.y + height(from) + to.y) / 2
                    : from.y > to.y + height(to) + 40 ? (to.y + height(to) + from.y) / 2 : Math.min(from.y,to.y) - 35;
                painter.corridor(g,a,b,screen(0,lane).y,GREEN,scale(),sz);
            } else painter.curve(g,a,new Coord(1,0),b,side,GREEN,scale(),sz);
        }
        boolean stat(QualityModel.Port p) { return p.kind == QualityModel.Kind.ATTRIBUTE || p.kind == QualityModel.Kind.SKILL; }
        Coord inputSide(QualityModel.Port p) { return stat(p) ? new Coord(0,-1) : new Coord(p.kind == QualityModel.Kind.TOOL ? 1 : -1,0); }
        CraftFlow.Position sourcePosition(CraftFlow.Node n, QualityModel.Port p) {
            if(n.positions.containsKey(p.id)) return n.positions.get(p.id);
            int index=0; for(QualityModel.Port q : model(n).ports) { if(q == p) break; if(stat(p) ? stat(q) : q.kind == p.kind) index++; }
            if(stat(p)) return new CraftFlow.Position((index % 2) * 226, -80 - (index / 2) * 76);
            return new CraftFlow.Position(p.kind == QualityModel.Kind.TOOL ? 304 : -238, 67 + index * 66);
        }
        String inputLabel(CraftFlow.Node n, QualityModel.Port p) {
            AtlasCatalog.Recipe recipe = source.catalog().get(n.recipe);
            if(recipe != null && p.kind == QualityModel.Kind.MATERIAL) for(AtlasCatalog.Material item : recipe.inputs)
                if(item.name.equals(p.name)) return p.name + (item.count < 0 ? "" : " ×" + item.quantity(1));
            return p.name;
        }
        void inputWires(GOut g, CraftFlow.Node n) {
            if(!n.expanded || model(n) == null) return;
            for(QualityModel.Port p : model(n).ports) if(graph.incoming(n.id,p.id) == null) {
                CraftFlow.Position at = sourcePosition(n,p); Coord end = input(n,p.id);
                double dx = wx(end.x) - (n.x + at.x + 106), dy = wy(end.y) - (n.y + at.y + 30);
                Coord side = Math.abs(dx) / 106 > Math.abs(dy) / 30 ? new Coord(dx >= 0 ? 1 : -1,0) : new Coord(0,dy >= 0 ? 1 : -1);
                Coord start = screen(n.x + at.x + 106 + side.x * 106, n.y + at.y + 30 + side.y * 30);
                painter.curve(g,start,side,end,inputSide(p),color(p),scale(),sz);
            }
        }
        Coord output(CraftFlow.Node n) { return screen(n.x + 270, n.y + 34); }
        Coord input(CraftFlow.Node n, String id) {
            if(n.output()) return screen(n.x, n.y + 34);
            if(!n.expanded || model(n) == null) return screen(n.x, n.y + 34);
            QualityModel m = model(n); QualityModel.Port target = m.port(id); if(target == null) return screen(n.x, n.y + 34);
            int index = 0; for(QualityModel.Port p : m.ports) { if(p == target) break; if(p.kind == target.kind) index++; }
            if(stat(target)) {
                int count=0, before=0;
                for(QualityModel.Port p : m.ports) if(stat(p)) { if(p == target) before=count; count++; }
                return screen(n.x + 270.0 * (before + 1) / (count + 1), n.y);
            }
            if(target.kind == QualityModel.Kind.TOOL) return screen(n.x + 270, n.y + 97 + 66 * index);
            return screen(n.x, n.y + 97 + 66 * index);
        }
        Color color(QualityModel.Port p) { return p.kind == QualityModel.Kind.ATTRIBUTE ? ORANGE : p.kind == QualityModel.Kind.SKILL ? PURPLE : p.kind == QualityModel.Kind.TOOL ? BLUE : UITheme.LINE; }
        void hit(CraftFlow.Node n, QualityModel.Port port, String action, double x, double y, double w, double h) {
            hits.add(new Hit(n, port, action, screen(x, y), new Coord((int)(w * scale()), (int)(h * scale()))));
        }
        public void draw(GOut g) {
            // Rectangles/circles bypass GOut's CPU clipping. Inherit a GPU scissor in
            // the base pipe too, so nested text fields cannot escape the canvas.
            Area clip = Area.corn(Coord.of(g.ul.x, g.root().br.y - g.br.y), Coord.of(g.br.x, g.root().br.y - g.ul.y));
            if(!clip.positive()) return;
            GOut clipped = new GOut(g.out, g.state().copy().prep(new haven.render.States.Scissor(clip)), g.root().sz());
            clipped.tx = g.tx; clipped.ul = g.ul; clipped.br = g.br; g = clipped;
            g.chcolor(new Color(17, 26, 29)); g.frect(Coord.z, sz); g.chcolor(new Color(32, 44, 46));
            int grid = Math.max(12, (int)(32 * scale()));
            for(int x = Math.floorMod(screen(0,0).x, grid); x < sz.x; x += grid) for(int y = Math.floorMod(screen(0,0).y, grid); y < sz.y; y += grid) g.frect(new Coord(x,y), new Coord(1,1));
            g.chcolor(); hits.clear();
            for(CraftFlow.Edge e : graph.edges) {
                CraftFlow.Node from = graph.node(e.from), to = graph.node(e.to);
                if(from != null && to != null) connection(g, from, to, e.port);
            }
            for(CraftFlow.Node n : graph.nodes) inputWires(g,n);
            for(CraftFlow.Node n : graph.nodes) drawNode(g, n);
            if(linking != null && graph.node(linking) != null) {
                Hit target = at(mouse);
                if(target != null && target.action.equals("input")) connection(g,graph.node(linking),target.node,target.port == null ? "q" : target.port.id);
                else wire(g, output(graph.node(linking)), mouse, GREEN);
            } else if(linkingInput != null) {
                Coord end = input(linkingInput.node,linkingInput.port == null ? "q" : linkingInput.port.id);
                painter.curve(g,mouse,new Coord(1,0),end,linkingInput.port == null ? new Coord(-1,0) : inputSide(linkingInput.port),GREEN,scale(),sz);
            }
            if(dropRecipe != null && dropAt != null) {
                panel(g, wx(dropAt.x), wy(dropAt.y), 270, 72, UITheme.PANEL, GREEN);
                label(g, dropRecipe.name, wx(dropAt.x) + 10, wy(dropAt.y) + 12, 250, GREEN);
            }
            if(graph.nodes.isEmpty()) g.image(text(t("empty"), UITheme.MUTED), UI.scale(30, 30));
            super.draw(g);
        }
        void drawNode(GOut g, CraftFlow.Node n) {
            CraftFlow.Result result = results.get(n.id);
            String value = "Q " + number(result == null ? null : result.value);
            if(result != null && result.value == null) value += " · " + t("incomplete");
            if(result != null && result.estimated) value = "≈ " + value;
            if(n.output()) {
                panel(g, n.x, n.y, 250, height(n), new Color(21, 46, 32), GREEN);
                label(g, t("result"), n.x + 14, n.y + 10, 220, GREEN); label(g, value, n.x + 14, n.y + 38, 220, UITheme.TEXT);
                dot(g, input(n, "q"), GREEN); hit(n, null, "input", n.x - 10, n.y + 24, 20, 20);
                hit(n, null, "move", n.x + 12, n.y, 230, 65);
                label(g,t("crafts"),n.x+14,n.y+75,110,UITheme.TEXT);
                valueField(g,Integer.toString(n.crafts),n.x+132,n.y+69,102,27,UITheme.TEXT);
                hit(n,null,"quantity",n.x+132,n.y+69,102,27); return;
            }
            QualityModel m = model(n); AtlasCatalog.Recipe recipe = source.catalog().get(n.recipe);
            panel(g, n.x, n.y, 270, height(n), UITheme.PANEL, n.id.equals(selected) ? UITheme.ACCENT : UITheme.LINE);
            icon(g, "", recipe == null ? "" : recipe.name, n.x + 10, n.y + 9);
            label(g, recipe == null ? t("missing") : recipe.name, n.x + 43, n.y + 9, 216, UITheme.TEXT);
            label(g, value + (n.expanded ? "   ▾" : "   ▸"), n.x + 12, n.y + 40, 245, result != null && result.value != null ? GREEN : UITheme.MUTED);
            hit(n, null, "move", n.x, n.y, 260, 65);
            dot(g, output(n), GREEN); hit(n, null, "output", n.x + 260, n.y + 24, 22, 22);
            if(!n.expanded || m == null) return;
            for(QualityModel.Port p : m.ports) {
                Color color = color(p); CraftFlow.Position pos = sourcePosition(n,p);
                double px = n.x + pos.x, py = n.y + pos.y; Coord end = input(n,p.id);
                if(p.kind == QualityModel.Kind.MATERIAL) {
                    label(g, inputLabel(n,p), n.x + 15, wy(end.y) - 9, 238, UITheme.TEXT);
                }
                CraftFlow.Edge linked = graph.incoming(n.id, p.id);
                if(linked != null) {
                    // The connected upstream recipe replaces the standalone manual source.
                    dot(g, end, GREEN);
                    hit(n, p, "input", wx(end.x) - 10, wy(end.y) - 10, 20, 20);
                    continue;
                }
                panel(g, px, py, 212, 60, p.kind == QualityModel.Kind.MATERIAL ? BLACK : new Color(26,35,39), color);
                icon(g, p.resource, p.name, px + 5, py + 6);
                label(g, inputLabel(n,p), px + 34, py + 4, 168, color == UITheme.LINE ? UITheme.TEXT : color);
                String entered = n.values.getOrDefault(p.id, "");
                Double val = automatic(p);
                valueField(g,entered.isBlank() ? number(val) : entered,px+34,py+25,168,27,valueColor(entered));
                hit(n,p,"source",px,py,212,60);
                hit(n,p,"edit",px+34,py+25,168,27);
                dot(g, end, color);
                if(p.kind == QualityModel.Kind.MATERIAL || p.kind == QualityModel.Kind.TOOL)
                    hit(n, p, "input", wx(end.x) - 10, wy(end.y) - 10, 20, 20);
            }
            double fy = n.y + height(n) - 36;
            label(g, t("formula"), n.x + 12, fy + 5, 245, UITheme.ACCENT);
            hit(n, null, "formula", n.x, fy, 270, 35);
        }
        Hit at(Coord c) { if(!c.isect(Coord.z, sz)) return null; for(int i = hits.size() - 1; i >= 0; i--) if(c.isect(hits.get(i).at, hits.get(i).size)) return hits.get(i); return null; }
        public boolean mousedown(MouseDownEvent ev) {
            if(ev.propagate(this)) return true; endEdit(); setfocus(this); mouse = down = ev.c;
            Hit h = at(ev.c); moved = false;
            if(ev.b == 3 && h != null && h.action.equals("input")) { checkpoint(); graph.disconnect(h.node.id, h.port == null ? "q" : h.port.id); recalculate(); return true; }
            if(ev.b == 1 && h != null) {
                selected = h.node.id;
                if(h.action.equals("output")) {
                    linking = h.node.id;
                    if(linkingInput != null) { connect(linkingInput); linkingInput = null; }
                    else grab = ui.grabmouse(this);
                    return true;
                }
                if(h.action.equals("input") && linking != null) { connect(h); return true; }
                if(h.action.equals("input")) { linkingInput = h; grab = ui.grabmouse(this); return true; }
                if(h.action.equals("edit")) { edit(h); return true; }
                if(h.action.equals("quantity")) { editQuantity(h.node); return true; }
                if(h.action.equals("source")) {
                    checkpoint(); movingSource = h; CraftFlow.Position pos = sourcePosition(h.node,h.port);
                    initialX = pos.x; initialY = pos.y; grab = ui.grabmouse(this); return true;
                }
                if(h.action.equals("formula")) { formulaWindow(h.node); return true; }
                if(h.action.equals("move")) { checkpoint(); moving = h.node; initialX = moving.x; initialY = moving.y; grab = ui.grabmouse(this); return true; }
            }
            if(ev.b == 1 || ev.b == 2 || ev.b == 3) {
                panning = true; initialX = graph.panX; initialY = graph.panY; grab = ui.grabmouse(this); return true;
            }
            return false;
        }
        void connect(Hit h) {
            checkpoint();
            try { graph.connect(linking, h.node.id, h.port == null ? "q" : h.port.id); actionError=null; }
            catch(IllegalArgumentException e) { showError(t("cycle")); }
            linking = null; recalculate();
        }
        public void mousemove(MouseMoveEvent ev) {
            mouse = ev.c;
            if(grab != null && down != null) {
                moved |= ev.c.dist(down) > UI.scale(4);
                Coord delta = ev.c.sub(down);
                if(moving != null) { moving.x = initialX + delta.x / scale(); moving.y = initialY + delta.y / scale(); }
                if(movingSource != null && moved) movingSource.node.positions.put(movingSource.port.id,
                    new CraftFlow.Position(initialX + delta.x / scale(), initialY + delta.y / scale()));
                if(panning) { graph.panX = initialX + delta.x / scale(); graph.panY = initialY + delta.y / scale(); }
            }
            super.mousemove(ev);
        }
        public boolean mouseup(MouseUpEvent ev) {
            if(grab == null) return super.mouseup(ev);
            grab.remove(); grab = null;
            if(linking != null) { Hit h = at(ev.c); if(h != null && h.action.equals("input")) connect(h); else if(moved) linking = null; }
            else if(linkingInput != null) {
                Hit h = at(ev.c);
                if(h != null && h.action.equals("output")) { linking = h.node.id; connect(linkingInput); linkingInput = null; }
                else if(moved) linkingInput = null;
            }
            if(moving != null && !moved && !moving.output()) { moving.expanded = !moving.expanded; if(moving.expanded && graph.nodes.size() == 1) fit(); }
            if(movingSource != null && !moved) edit(movingSource);
            moving = null; movingSource = null; panning = false; graph.version++; return true;
        }
        public boolean mousewheel(MouseWheelEvent ev) {
            endEdit(); double x = wx(ev.c.x), y = wy(ev.c.y);
            graph.zoom = Math.max(.35, Math.min(1.8, graph.zoom * Math.pow(1.1, -ev.a)));
            graph.panX = ev.c.x / scale() - x; graph.panY = ev.c.y / scale() - y; graph.version++; return true;
        }
        /** Canvas-scale editor with the same geometry and text alignment as its idle field. */
        class FlowEntry extends TextEntry {
            private Text.Line line;
            private UI.Grab selectionGrab;
            private double focusedAt;
            FlowEntry(double width,double height,String value) { super(fieldSize(width,height).x,value); resize(fieldSize(width,height)); }
            private Text.Line rendered() { if(line==null) line=fieldFont().render(dtext(),textcolor()); return line; }
            private int padding() { return Math.max(2,(int)(6*scale())); }
            private int contentWidth() { return Math.max(1,sz.x-2*padding()); }
            private int origin() { return Math.max(0,(contentWidth()-rendered().sz().x)/2); }
            protected void redraw() { super.redraw(); if(line!=null) { line.tex().dispose(); line=null; } }
            public void draw(GOut g) {
                painter.panel(g,Coord.z,sz,BLACK,hasfocus ? UITheme.ACCENT : UITheme.LINE,scale(),sz);
                Text.Line text=rendered(); int cursor=text.advance(buf.point()), width=contentWidth();
                if(text.sz().x<=width) sx=0;
                else if(hasfocus) { if(cursor<sx) sx=cursor; if(cursor>sx+width-1) sx=Math.max(0,cursor-width+1); }
                int x=origin()-sx,y=(sz.y-text.sz().y)/2;
                GOut content=g.reclip(new Coord(padding(),0),new Coord(width,sz.y));
                if(buf.mark()>=0) {
                    int mark=text.advance(buf.mark()); content.chcolor(TextEntry.selcol);
                    content.frect(new Coord(x+Math.min(cursor,mark),y),new Coord(Math.abs(cursor-mark),text.sz().y)); content.chcolor();
                }
                content.image(text.tex(),new Coord(x,y));
                if(hasfocus && (Utils.rtime()-Math.max(focusedAt,buf.mtime()))%1<.5) {
                    content.chcolor(UITheme.ACCENT);
                    content.frect(new Coord(x+cursor,y),new Coord(Math.max(1,(int)scale()),text.sz().y)); content.chcolor();
                }
            }
            private int charAt(Coord at) { return rendered().charat(at.x-padding()-origin()+sx); }
            public boolean mousedown(MouseDownEvent ev) {
                parent.setfocus(this);
                if(ev.b==1) { buf.point(charAt(ev.c)); buf.mark(-1); selectionGrab=ui.grabmouse(this); }
                return true;
            }
            public void mousemove(MouseMoveEvent ev) {
                if(selectionGrab!=null) { if(buf.mark()<0) buf.mark(buf.point()); buf.point(charAt(ev.c)); }
            }
            public boolean mouseup(MouseUpEvent ev) {
                if(ev.b==1 && selectionGrab!=null) { selectionGrab.remove(); selectionGrab=null; return true; }
                return false;
            }
            public void gotfocus() { super.gotfocus(); focusedAt=Utils.rtime(); }
            public void dispose() { if(selectionGrab!=null) selectionGrab.remove(); redraw(); super.dispose(); }
        }
        void edit(Hit h) {
            checkpoint(); QualityModel.Port p = h.port;
            CraftFlow.Position pos = sourcePosition(h.node,p);
            editor = add(new FlowEntry(168,27,h.node.values.getOrDefault(p.id, "")) {
                protected Color textcolor() { return text().isBlank() ? UITheme.TEXT : valueColor(text()); }
                protected void changed() { super.changed(); if(buf != null) { h.node.values.put(p.id, text()); graph.version++; recalculate(); redraw(); } }
                public void activate(String value) { endEdit(); }
                public boolean keydown(KeyDownEvent ev) { if(ev.code == KeyEvent.VK_ESCAPE) { endEdit(); return true; } return super.keydown(ev); }
            }, screen(h.node.x + pos.x + 34, h.node.y + pos.y + 25));
            editor.settip(t("override_tip")); setfocus(editor);
        }
        void endEdit() { if(editor != null) { TextEntry old = editor; editor = null; old.destroy(); } }
        void editQuantity(CraftFlow.Node node) {
            checkpoint();
            editor=add(new FlowEntry(102,27,Integer.toString(node.crafts)) {
                int count() {
                    try { String value=text().trim(); if(!value.matches("[0-9]{1,4}")) return -1;
                        int n=Integer.parseInt(value); return n>=1 && n<=9999 ? n : -1;
                    } catch(NumberFormatException e) { return -1; }
                }
                protected Color textcolor() { return count()<0 ? new Color(236,108,95) : UITheme.TEXT; }
                protected void changed() {
                    super.changed(); if(buf!=null && count()>0) { node.crafts=count(); graph.version++; recalculate(); }
                }
                public void activate(String value) { if(count()>0) { endEdit(); source.flush(); } }
                public boolean keydown(KeyDownEvent ev) { if(ev.code==KeyEvent.VK_ESCAPE) { endEdit(); return true; } return super.keydown(ev); }
            },screen(node.x+132,node.y+69));
            editor.settip(t("crafts_tip")); setfocus(editor);
        }
        public boolean keydown(KeyDownEvent ev) {
            if(editor == null && ev.code == KeyEvent.VK_DELETE) { deleteSelected(); return true; }
            if(editor == null && (ev.mods & KeyMatch.C) != 0 && ev.code == KeyEvent.VK_Z) { restore(undo, redo); return true; }
            if(editor == null && (ev.mods & KeyMatch.C) != 0 && ev.code == KeyEvent.VK_Y) { restore(redo, undo); return true; }
            if(ev.code == KeyEvent.VK_ESCAPE && (linking != null || linkingInput != null)) { linking = null; linkingInput = null; return true; }
            return super.keydown(ev);
        }
        public Object tooltip(Coord c, Widget previous) {
            if(grab != null || dropRecipe != null || linking != null || linkingInput != null) return null;
            Hit h = at(c); if(h == null) return null;
            if(h.action.equals("formula")) return formulaTip(h.node);
            if(h.action.equals("quantity")) return t("crafts_tip");
            if(h.port != null) return h.port.name + "\n" + t(h.action.equals("input") ? "connect_tip" : h.action.equals("source") ? "source_tip" : "override_tip");
            CraftFlow.Result r = results.get(h.node.id);
            return r == null || r.value != null ? null : r.error;
        }
        Object formulaTip(CraftFlow.Node n) {
            QualityModel m = model(n); if(m == null) return null;
            String expression = n.formula.isBlank() ? m.expression : n.formula;
            if(expression.isBlank()) return t("invalid");
            try {
                String tex = "Q=" + QualityExpression.parse(expression).toTex(id -> {
                    QualityModel.Port p = m.port(id); String name = p == null ? id : p.name;
                    name = name.replaceAll("[^\\p{L}\\p{N} .,'()/-]", " ");
                    return p != null && !stat(p) ? "q_{\\text{" + name + "}}" : "\\text{" + name + "}";
                });
                String key = "latex-tip:" + tex;
                return textCache.computeIfAbsent(key, unused -> new TexI(FormulaImages.render(tex,UI.scale(16),UI.scale(620))));
            } catch(RuntimeException e) { return t("invalid"); }
        }
        public void dispose() { if(grab != null) grab.remove(); endEdit(); painter.dispose(); super.dispose(); }
    }
    private static final class Hit {
        final CraftFlow.Node node; final QualityModel.Port port; final String action; final Coord at, size;
        Hit(CraftFlow.Node n, QualityModel.Port p, String action, Coord at, Coord size) { node=n; port=p; this.action=action; this.at=at; this.size=size; }
    }
    private void formulaWindow(CraftFlow.Node node) {
        closeFormula();
        QualityModel model = canvas.model(node); if(model == null) return;
        Window dialog = new Window(UI.scale(650, 290), t("formula")) {
            public void wdgmsg(Widget sender, String msg, Object... args) { if(sender == this && msg.equals("close")) { destroy(); return; } super.wdgmsg(sender,msg,args); }
        };
        formulaDialog = parent.add(dialog, c.add(UI.scale(70, 80)));
        String mapping = String.join(" · ", model.ports.stream().map(p -> p.id + " = " + p.name).toArray(String[]::new));
        Tex labels = RichText.render(RichText.Parser.quote(mapping), UI.scale(620)).tex();
        dialog.add(new Widget(labels.sz()) { public void draw(GOut g) { g.image(labels, Coord.z); } public void dispose() { labels.dispose(); super.dispose(); } }, UI.scale(10, 10));
        int y = UI.scale(18) + labels.sz().y;
        TextEntry expression = dialog.add(new TextEntry(UI.scale(620), node.formula.isBlank() ? model.expression : node.formula), new Coord(UI.scale(10), y));
        dialog.add(new Label("mean, geomean, sqrt, pow, min, max, softcap(q, cap), floor"), new Coord(UI.scale(10), y + UI.scale(35)));
        Label info = dialog.add(new Label(t(model.estimated ? "general_tip" : "wiki_tip")), new Coord(UI.scale(10), y + UI.scale(62)));
        int buttonsY = y + UI.scale(100);
        if(!model.sourceFormula.isEmpty()) try {
            Tex formula = new TexI(FormulaImages.render(model.sourceFormula, UI.scale(17), UI.scale(620)));
            dialog.add(new Widget(formula.sz()) {
                public void draw(GOut g) { g.image(formula, Coord.z); }
                public void dispose() { formula.dispose(); super.dispose(); }
            }, new Coord(UI.scale(10), y + UI.scale(88)));
            buttonsY += formula.sz().y;
        } catch(IllegalArgumentException ignored) {}
        dialog.add(new Button(UI.scale(150), t("apply"), () -> {
            try {
                QualityExpression parsed = QualityExpression.parse(expression.text());
                for(String id : parsed.variables()) if(model.port(id) == null) throw new IllegalArgumentException(id);
                checkpoint(); node.formula = expression.text(); graph.version++; canvas.recalculate(); dialog.destroy();
            } catch(IllegalArgumentException e) { info.settext(t("invalid") + ": " + e.getMessage()); }
        }), new Coord(UI.scale(10), buttonsY));
        dialog.add(new Button(UI.scale(190), t("reset"), () -> { checkpoint(); node.formula = ""; graph.version++; canvas.recalculate(); dialog.destroy(); }), new Coord(UI.scale(170), buttonsY));
        dialog.pack();
    }
}
