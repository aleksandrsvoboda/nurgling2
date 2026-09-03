package nurgling.widgets;

import haven.*;
import haven.Button;
import haven.Scrollbar;
import haven.Window;
import haven.res.lib.itemtex.*;
import nurgling.*;
import nurgling.areas.*;
import nurgling.i18n.L10n;
import org.json.*;

import java.awt.*;
import java.awt.image.*;
import java.util.*;

public class BaseIngredientContainer extends Widget implements DTarget, Scrollable {
    static Color bg = new Color(30,40,40,160);
    private static TexI freeLabel;
    private static TexI freeLabel2;
    
    static {
        freeLabel = new TexI(Text.render(L10n.get("ingredient.drag_drop")).img);
        freeLabel2 = new TexI(Text.render(L10n.get("ingredient.select_category")).img);
    }

    public Scrollbar sb;
    protected int maxy = 0;
    protected int cury = 0;
    protected ArrayList<Ingredient> items = new ArrayList<>();
    protected ArrayList<IconItem> icons = new ArrayList<>();
    protected String type;


    public static class Ingredient {
        public String name;
        public BufferedImage image;

        public Ingredient(String name, BufferedImage image) {
            this.name = name;
            this.image = image;
        }
    }

    public BaseIngredientContainer(String type) {
        this.type = type;
        this.sb = add(new Scrollbar(UI.scale(385), this));
        this.sz = UI.scale(new Coord(205,400));
        this.sb.c = new Coord(UI.scale(180),0);
    }

    // Keeps the scrollbar's position/length in sync when a caller resizes away from the default size.
    @Override
    public void resize(Coord sz) {
        super.resize(sz);
        if (sb != null) {
            sb.c = new Coord(sz.x - sb.sz.x, 0);
            sb.resize(sz.y);
        }
    }

    @Override
    public void draw(GOut g) {
        g.chcolor(bg);
        g.frect(Coord.z, g.sz());
        if(items.isEmpty()) {
            g.chcolor(Color.WHITE);
            g.image(freeLabel, UI.scale(5,5));
            g.image(freeLabel2, UI.scale(5,20));
        }
        super.draw(g);
    }

    @Override
    public int scrollmin() {
        return 0;
    }

    @Override
    public int scrollmax() {
        return maxy;
    }

    @Override
    public int scrollval() {
        return cury;
    }

    @Override
    public void scrollval(int val) {
        cury = val;
        for(IconItem icon: icons) {
            icon.c.y = icon.basec.y-val;
        }
    }

    // Icons per row; a wider consumer overrides this instead of the grid staying 5-wide.
    protected int gridColumns() {
        return 5;
    }

    protected Coord gridPos(int index) {
        int cols = gridColumns();
        return UI.scale(new Coord(35*(index%cols), 51*(index/cols))).add(new Coord(5,5));
    }

    // Recomputes scroll range from the current item count and actual visible height.
    protected void updateScrollRange() {
        int cols = gridColumns();
        int totalRows = (items.size() + cols - 1) / cols;
        int visibleRows = Math.max(1, (sz.y - UI.scale(10)) / UI.scale(51));
        maxy = UI.scale(51) * Math.max(0, totalRows - visibleRows);
        cury = Math.min(cury, Math.max(maxy, 0));
    }

    public void addIcon(JSONObject res) {
        if(res != null && res.get("name") != null) {
            Ingredient ing;
            items.add(ing = new Ingredient((String)res.get("name"), ItemTex.create(res)));
            IconItem it = add(new IconItem(ing.name, ing.image, this), gridPos(items.size()-1));
            it.basec = new Coord(it.c);
            icons.add(it);
            updateScrollRange();
        }
    }

    @Override
    public boolean drop(Drop ev) {
        return DTarget.super.drop(ev);
    }

    public void addItem(String name, JSONObject res) {
        // Базовая реализация без сохранения в JSON
        if(res != null) {
            res.put("name", name);
            addIcon(res);
        }
    }

    public void delete(String name) {
        // Базовая реализация без работы с JSON
        for(int i = 0; i < items.size(); i++) {
            if(items.get(i).name.equals(name)) {
                items.remove(i);
                icons.get(i).destroy();
                icons.remove(i);
                return;
            }
        }
    }

    public void deleteAll() {
        items.clear();
        for(IconItem it : icons) {
            it.destroy();
        }
        icons.clear();
    }
}