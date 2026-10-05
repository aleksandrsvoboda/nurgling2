package nurgling.widgets;

import haven.*;
import nurgling.NWindowDeco;
import nurgling.craft.*;
import nurgling.i18n.L10n;
import nurgling.styles.UITheme;
import nurgling.tools.ItemIcons;
import nurgling.widgets.cookbook.HintTextEntry;
import java.util.*;

/** Searchable craft journal with explicit provenance: current menu or recorded server recipe. */
public class NCraftAtlas extends Window {
    private final AtlasSource atlas;
    private final AtlasCatalog catalog;
    private final List<AtlasCatalog.Recipe> rows = new ArrayList<>();
    private final Map<String, Tex> rowText = new HashMap<>();
    private HintTextEntry search;
    private CheckBox onlyAvailable, onlyFavorites;
    private RecipeListbox list;
    private Scrollport details;
    private Label saveStatus, batches;
    private Button open, favorite, back;
    private TextEntry quantity;
    private Button calculator;
    private NCraftFlow flowWindow;
    private String selected;
    private final Deque<String> history = new ArrayDeque<>();
    private int crafts = 1, seenVersion = -1, seenAvailability = -1;
    private int detailY;
    private static final RichText.Foundry body = new RichText.Foundry(
        nurgling.styles.UIFont.regular.deriveFont((float)UI.scale(12)), java.awt.Color.WHITE);

    public NCraftAtlas(AtlasSource atlas) {
        super(clamp(Utils.getprefc("wndsz-craft-atlas", UI.scale(900, 550))), L10n.get("atlas.title"));
        this.atlas = atlas; this.catalog = atlas.catalog();
        posmem("craft-atlas");
        search = add(new HintTextEntry(UI.scale(280), L10n.get("atlas.search"), () -> refresh(false)), UI.scale(10, 10));
        onlyAvailable = add(new CheckBox(L10n.get("atlas.available")) {
            public void changed(boolean value) { super.changed(value); refresh(false); }
        }, Coord.z);
        onlyFavorites = add(new CheckBox(L10n.get("atlas.favorites")) {
            public void changed(boolean value) { super.changed(value); refresh(false); }
        }, Coord.z);
        list = add(new RecipeListbox(atlas, UI.scale(280), 16, UI.scale(28)) {
            protected AtlasCatalog.Recipe listitem(int index) { return rows.get(index); }
            protected int listitems() { return rows.size(); }
            protected void drawitem(GOut g, AtlasCatalog.Recipe recipe, int index) {
                String title = (catalog.favorite(recipe.resource) ? "★ " : "") + recipe.name;
                Tex tex = rowText.computeIfAbsent(title, s -> Text.render(s, UITheme.TEXT).tex());
                g.chcolor(atlas.available(recipe.resource) ? UITheme.ACCENT : UITheme.DISABLED);
                g.frect(UI.scale(6, 10), UI.scale(4, 8)); g.chcolor();
                Tex icon = null;
                if(!recipe.outputs.isEmpty()) {
                    AtlasCatalog.Material output = recipe.outputs.get(0);
                    icon = ItemIcons.get(output.resource, output.name, output.category);
                }
                if(icon == null) {
                    MenuGrid.Pagina page = atlas.page(recipe.resource);
                    if(page != null) try { icon = ItemIcons.getResource(page.res().name); } catch(Loading ignored) {}
                }
                ItemIcons.draw(g, icon, UI.scale(16, 2), UI.scale(24));
                g.image(tex, new Coord(UI.scale(46), (itemh - tex.sz().y) / 2));
            }
            public void change(AtlasCatalog.Recipe recipe) {
                super.change(recipe);
                if(recipe != null) select(recipe.resource, true);
            }
            public Object tooltip(Coord c, Widget prev) {
                AtlasCatalog.Recipe recipe = itemat(c);
                return recipe == null ? null : recipe.name + "\n" + recipe.group + "\n" + status(recipe);
            }
        }, UI.scale(10, 50));
        details = add(new Scrollport(UI.scale(580, 390)), UI.scale(310, 90));
        saveStatus = add(new Label(""), Coord.z);
        saveStatus.hide();
        back = add(new Button(UI.scale(60), L10n.get("atlas.back"), () -> {
            if(!history.isEmpty()) select(history.pop(), false);
        }), UI.scale(310, 50));
        batches = add(new Label(L10n.get("atlas.batches")), UI.scale(380, 55));
        quantity = add(new TextEntry(UI.scale(58), "1") {
            protected void changed() {
                super.changed();
                try {
                    int value = Integer.parseInt(text());
                    if(value < 1 || value > 9999) return;
                    crafts = value;
                    if(details != null) rebuildDetails();
                } catch(NumberFormatException ignored) {}
            }
            public void activate(String value) { settext(Integer.toString(crafts)); }
            public void lostfocus() { super.lostfocus(); settext(Integer.toString(crafts)); }
        }, Coord.z);
        quantity.settip(L10n.get("atlas.quantity_tip"));
        calculator = add(new NCalculatorButton(() -> {
            if(flowWindow == null || flowWindow.parent == null) flowWindow = parent.add(new NCraftFlow(atlas), UI.scale(35, 45));
            flowWindow.show(); flowWindow.raise();
        }), Coord.z);
        favorite = add(new Button(UI.scale(160), L10n.get("atlas.star"), () -> {
            if(selected != null) { catalog.toggleFavorite(selected); refresh(true); }
        }), UI.scale(310, 510));
        open = add(new Button(UI.scale(200), L10n.get("atlas.open"), () -> {
            if(selected != null) try { atlas.open(selected); } catch(Loading ignored) {}
        }), UI.scale(680, 510));
        arrange(); refresh(true);
    }

    @Override protected Deco makedeco() { return new NWindowDeco(false).dragsize(true); }
    private static Coord clamp(Coord size) {
        return new Coord(Math.max(UI.scale(740), size.x), Math.max(UI.scale(400), size.y));
    }
    @Override public void resize(Coord size) {
        super.resize(clamp(size));
        if(details != null && open != null) { arrange(); rebuildDetails(); }
    }
    private void arrange() {
        Coord size = csz();
        int left = UI.scale(280), dx = UI.scale(310), pad = UI.scale(10);
        int rowHeight = Math.max(calculator.sz.y, Math.max(back.sz.y, Math.max(batches.sz.y, quantity.sz.y)));
        int rowTop = UI.scale(10);
        back.move(new Coord(dx, rowTop + (rowHeight - back.sz.y) / 2));
        batches.move(new Coord(UI.scale(380), rowTop + (rowHeight - batches.sz.y) / 2));
        quantity.move(new Coord(batches.c.x + batches.sz.x + UI.scale(8), rowTop + (rowHeight - quantity.sz.y) / 2));
        calculator.move(new Coord(size.x-pad-calculator.sz.x,rowTop));
        onlyFavorites.move(new Coord(pad,size.y-pad-onlyFavorites.sz.y));
        onlyAvailable.move(new Coord(pad,onlyFavorites.c.y-UI.scale(8)-onlyAvailable.sz.y));
        saveStatus.move(new Coord(pad,onlyAvailable.c.y-UI.scale(8)-saveStatus.sz.y));
        int listBottom=saveStatus.visible ? saveStatus.c.y : onlyAvailable.c.y;
        list.resize(new Coord(left,Math.max(UI.scale(56),listBottom-UI.scale(8)-list.c.y)));
        int detailTop=rowTop+rowHeight+UI.scale(10);
        int footerHeight=Math.max(favorite.sz.y,open.sz.y);
        int footerTop=size.y-pad-footerHeight;
        details.resize(new Coord(size.x - dx - pad, footerTop-pad-detailTop));
        details.move(new Coord(dx,detailTop));
        favorite.move(new Coord(dx, footerTop+(footerHeight-favorite.sz.y)/2));
        open.move(new Coord(size.x - open.sz.x - pad, footerTop+(footerHeight-open.sz.y)/2));
    }
    private void refresh(boolean detail) {
        if(list == null) return;
        rows.clear();
        for(AtlasCatalog.Recipe recipe : catalog.recipes()) {
            if(onlyAvailable.a && !atlas.available(recipe.resource)) continue;
            if(onlyFavorites.a && !catalog.favorite(recipe.resource)) continue;
            if(recipe.matches(search.text())) rows.add(recipe);
        }
        rows.sort(Comparator.comparing((AtlasCatalog.Recipe r) -> !catalog.favorite(r.resource))
                            .thenComparing(r -> r.name, String.CASE_INSENSITIVE_ORDER));
        list.sel = catalog.get(selected);
        list.sb.val = Math.max(0, Math.min(list.sb.val, rows.size() - list.h));
        if(catalog.get(selected) == null && !rows.isEmpty()) select(rows.get(0).resource, false);
        else if(detail) rebuildDetails();
        seenVersion = catalog.version; seenAvailability = atlas.availabilityVersion();
        rowText.values().forEach(Tex::dispose); rowText.clear();
    }
    private void select(String resource, boolean remember) {
        if(remember && selected != null && !selected.equals(resource)) {
            history.push(selected);
            while(history.size() > 50) history.removeLast();
        }
        selected = resource; list.sel = catalog.get(resource);
        details.bar.val = details.cont.sy = 0;
        rebuildDetails();
    }

    public void showRecipe(String resource) {
        if(catalog.get(resource) != null) select(resource, true);
        show(); raise();
    }

    private void paragraph(String value, java.awt.Color color) {
        final Tex text = body.render(RichText.Parser.quote(value), Math.max(UI.scale(100), details.cont.sz.x - UI.scale(20))).tex();
        details.cont.add(new Widget(text.sz()) {
            public void draw(GOut g) { g.chcolor(color); g.image(text, Coord.z); g.chcolor(); }
            public void dispose() { text.dispose(); super.dispose(); }
        }, new Coord(UI.scale(8), detailY));
        detailY += text.sz().y + UI.scale(8);
    }
    private void section(String key) { paragraph(L10n.get(key), UITheme.ACCENT); }
    private void formula(String source) {
        try {
            final Tex tex = new TexI(FormulaImages.render(source, UI.scale(17), details.cont.sz.x - UI.scale(20)));
            Widget widget = details.cont.add(new Widget(tex.sz()) {
                public void draw(GOut g) { g.image(tex, Coord.z); }
                public void dispose() { tex.dispose(); super.dispose(); }
            }, new Coord(UI.scale(8), detailY));
            widget.settip(source);
            detailY += tex.sz().y + UI.scale(12);
        } catch(RuntimeException e) {
            paragraph(L10n.get("atlas.formula_unavailable"), UITheme.MUTED);
        }
    }
    private String status(AtlasCatalog.Recipe recipe) {
        if(atlas.available(recipe.resource)) return L10n.get("atlas.live");
        return L10n.get(recipe.wiki != null && !recipe.recorded ? "atlas.wiki" : "atlas.cached");
    }
    private void material(AtlasCatalog.Material material, boolean ingredient) {
        String flags = material.optional ? "  (" + L10n.get("atlas.optional") + ")" : "";
        if(material.category) flags += "  [" + L10n.get(material.choices.isEmpty() ? "atlas.category" : "atlas.choice") + "]";
        AtlasCatalog.Recipe producer = ingredient ? catalog.producer(material) : null;
        if(producer != null && producer.resource.equals(selected)) producer = null;
        String line = material.quantity(crafts) + " × " + material.name + flags;
        final String id = producer == null ? null : producer.resource;
        final Tex text = body.render(RichText.Parser.quote(line + (id == null ? "" : "  →")),
                                    Math.max(UI.scale(80), details.cont.sz.x - UI.scale(66))).tex();
        Widget row = details.cont.add(new Widget(new Coord(details.cont.sz.x - UI.scale(16), Math.max(UI.scale(34), text.sz().y + UI.scale(8)))) {
            boolean hover;
            public void draw(GOut g) {
                if(id != null) {
                    g.chcolor(hover ? UITheme.SELECTED : UITheme.ROW); g.frect(Coord.z, sz); g.chcolor();
                }
                ItemIcons.draw(g, ItemIcons.get(material.resource, material.name, material.category),
                    new Coord(UI.scale(3), (sz.y - UI.scale(28)) / 2), UI.scale(28));
                g.image(text, new Coord(UI.scale(39), (sz.y - text.sz().y) / 2));
            }
            public void mousemove(MouseMoveEvent ev) { hover = ev.c.isect(Coord.z, sz); }
            public boolean mousedown(MouseDownEvent ev) {
                if(id != null && ev.b == 1) { select(id, true); return true; }
                return false;
            }
            public void dispose() { text.dispose(); super.dispose(); }
        }, new Coord(UI.scale(8), detailY));
        row.settip(producer == null ? material.name : producer.name);
        detailY += row.sz.y + UI.scale(4);
    }
    private void rebuildDetails() {
        if(open == null) return;
        for(Widget child : new ArrayList<>(details.cont.children())) child.destroy();
        detailY = UI.scale(5);
        AtlasCatalog.Recipe recipe = catalog.get(selected);
        back.disable(history.isEmpty()); favorite.disable(recipe == null);
        open.disable(recipe == null || !atlas.available(recipe.resource));
        if(recipe == null) {
            paragraph(L10n.get("atlas.empty"), UITheme.MUTED);
        } else {
            favorite.change(L10n.get(catalog.favorite(recipe.resource) ? "atlas.unstar" : "atlas.star"));
            paragraph(recipe.name, UITheme.ACCENT);
            paragraph(recipe.group + " · " + status(recipe), UITheme.MUTED);
            if(recipe.wiki != null) {
                paragraph(L10n.get("atlas.kind." + recipe.wiki.kind) + " · " + L10n.get(recipe.recorded ? "atlas.observed" : "atlas.from_wiki"), UITheme.MUTED);
            }
            if(recipe.recorded || recipe.wiki != null) {
                section("atlas.inputs");
                if(!recipe.recorded && recipe.wiki != null && !recipe.wiki.quantitiesKnown) {
                    paragraph(recipe.wiki.inputText, UITheme.TEXT);
                    paragraph(L10n.get("atlas.expression"), UITheme.MUTED);
                } else {
                    if(recipe.inputs.isEmpty()) paragraph(L10n.get("atlas.no_inputs"), UITheme.MUTED);
                    for(AtlasCatalog.Material material : recipe.inputs) material(material, true);
                }
                section("atlas.outputs");
                for(AtlasCatalog.Material material : recipe.outputs) material(material, false);
                if(recipe.outputs.stream().anyMatch(m -> m.count < 0)) paragraph(L10n.get("atlas.unknown_yield"), UITheme.MUTED);
                if(recipe.wiki != null && !recipe.wiki.requirements.isEmpty()) {
                    section("atlas.requirements");
                    for(String requirement : recipe.wiki.requirements) paragraph(requirement, UITheme.TEXT);
                }
                if(!recipe.quality.isEmpty()) {
                    section("atlas.quality"); paragraph(String.join(", ", recipe.quality), UITheme.TEXT);
                }
                if(!recipe.tools.isEmpty()) {
                    section("atlas.tools"); paragraph(String.join(", ", recipe.tools), UITheme.TEXT);
                }
                Set<AtlasCatalog.Recipe> uses = new LinkedHashSet<>();
                for(AtlasCatalog.Material output : recipe.outputs) uses.addAll(catalog.uses(output.key()));
                uses.remove(recipe);
                if(!uses.isEmpty()) {
                    section("atlas.used_in");
                    for(AtlasCatalog.Recipe use : uses) {
                        Button link = details.cont.add(new Button(Math.max(UI.scale(100), details.cont.sz.x - UI.scale(20)), use.name + "  →", () -> select(use.resource, true)), new Coord(UI.scale(8), detailY));
                        detailY += link.sz.y + UI.scale(5);
                    }
                }
            } else paragraph(L10n.get("atlas.not_recorded"), UITheme.MUTED);
            if(recipe.wiki != null) {
                if(!recipe.wiki.formulas.isEmpty()) {
                    section("atlas.formulas");
                    for(String formula : recipe.wiki.formulas) formula(formula);
                }
                final String source = recipe.wiki.source;
                Button sourceButton = details.cont.add(new Button(Math.max(UI.scale(100), details.cont.sz.x - UI.scale(20)),
                    "Ring of Brodgar · " + recipe.wiki.date.substring(0, Math.min(10, recipe.wiki.date.length())), () -> {
                        try { ui.wnd.toolkit().browse(java.net.URI.create(source)); }
                        catch(java.io.IOException | IllegalArgumentException e) { ui.error(e.getMessage()); }
                    }), new Coord(UI.scale(8), detailY));
                sourceButton.settip(source); detailY += sourceButton.sz.y + UI.scale(8);
            }
            MenuGrid.Pagina page = atlas.page(recipe.resource);
            if(page != null) {
                section("atlas.server_info");
                details.cont.add(new ServerInfo(page), new Coord(UI.scale(8), detailY));
            }
        }
        details.cont.update();
        details.bar.val = Math.min(details.bar.val, details.bar.max);
        details.cont.sy = details.bar.val;
    }
    private class ServerInfo extends Widget {
        private final MenuGrid.Pagina page;
        private Tex image;
        ServerInfo(MenuGrid.Pagina page) { super(UI.scale(200, 25)); this.page = page; }
        public void draw(GOut g) {
            if(image == null) try {
                image = new TexI(nurgling.NRecipeTooltip.build(page.button().name(), page.button().info()));
                int width = Math.min(image.sz().x, details.cont.sz.x - UI.scale(20));
                resize(new Coord(width, image.sz().y * width / image.sz().x)); details.cont.update();
            } catch(Loading ignored) {}
            if(image != null) g.image(image, Coord.z, sz);
        }
        public Object tooltip(Coord c, Widget prev) { return image; }
        public void dispose() { if(image != null) image.dispose(); super.dispose(); }
    }
    @Override public void tick(double dt) {
        super.tick(dt);
        if(visible && (seenVersion != catalog.version || seenAvailability != atlas.availabilityVersion())) refresh(true);
        String error=atlas.saveError();
        boolean wasVisible=saveStatus.visible;
        if(error != null) { saveStatus.settext(L10n.get("atlas.save_error")); saveStatus.settip(error); saveStatus.show(); }
        else { saveStatus.hide(); saveStatus.settip(null); }
        if(wasVisible!=saveStatus.visible) arrange();
    }
    @Override public void wdgmsg(Widget sender, String msg, Object... args) {
        if(sender == this && msg.equals("close")) { hide(); atlas.flush(); return; }
        super.wdgmsg(sender, msg, args);
    }
    @Override public void hide() {
        list.cancelDrag();
        Utils.setprefc("wndsz-craft-atlas", csz());
        super.hide();
    }
    @Override public void dispose() {
        if(flowWindow != null && flowWindow.parent != null) flowWindow.destroy();
        Utils.setprefc("wndsz-craft-atlas", csz());
        rowText.values().forEach(Tex::dispose); rowText.clear(); super.dispose();
    }
}
