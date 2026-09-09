package nurgling.widgets;

import haven.*;
import nurgling.*;
import nurgling.areas.*;
import nurgling.i18n.L10n;
import org.json.JSONObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.*;
import java.util.*;

public class IconItem extends Widget
{
    // Menu option keys - used for comparison (language-independent)
    private static final String KEY_THRESHOLD = "iconitem.threshold";
    private static final String KEY_DELETE = "iconitem.delete";
    private static final String KEY_MARK_BARTER = "iconitem.mark_barter";
    private static final String KEY_MARK_BARREL = "iconitem.mark_barrel";
    private static final String KEY_UNMARK = "iconitem.unmark";
    private static final String KEY_EDIT = "iconitem.edit";
    private static final String KEY_MAINTAIN = "iconitem.maintain";
    private static final String KEY_PRIORITY = "iconitem.priority";
    public static final TexI frame = new TexI(Resource.loadimg("nurgling/hud/iconframe"));
    public static final TexI framet = new TexI(Resource.loadimg("nurgling/hud/iconframet"));
    public static final TexI bm = new TexI(Resource.loadimg("nurgling/hud/bartermark"));
    public static final TexI barm = new TexI(Resource.loadimg("nurgling/hud/barrelmark"));
    // Small green flower badge for a flower-menu-action item - drawn procedurally, no existing asset to reuse.
    public static final TexI flowerMark = createFlowerMark();

    private static TexI createFlowerMark() {
        int size = 32;
        BufferedImage img = TexI.mkbuf(new Coord(size, size));
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int cx = size / 2, cy = size / 2;
        int petalR = size / 4;
        double dist = size / 4.0;
        Color petal = new Color(70, 170, 70);
        Color petalOutline = new Color(25, 100, 25);
        for (int i = 0; i < 5; i++) {
            double angle = Math.toRadians(90 + i * 72);
            int px = (int) Math.round(cx + dist * Math.cos(angle));
            int py = (int) Math.round(cy - dist * Math.sin(angle));
            g2d.setColor(petal);
            g2d.fillOval(px - petalR, py - petalR, petalR * 2, petalR * 2);
            g2d.setColor(petalOutline);
            g2d.drawOval(px - petalR, py - petalR, petalR * 2, petalR * 2);
        }
        int centerR = size / 6;
        g2d.setColor(new Color(230, 200, 60));
        g2d.fillOval(cx - centerR, cy - centerR, centerR * 2, centerR * 2);
        g2d.setColor(new Color(150, 120, 30));
        g2d.drawOval(cx - centerR, cy - centerR, centerR * 2, centerR * 2);

        g2d.dispose();
        return new TexI(img);
    }

    public JSONObject src;
    TexI tex = null;

    TexI tip;
    TexI q;
    boolean noOpts = false;
    // Shared Threshold/Maintain badge flag - the two features never coexist on the same icon
    // (mutually exclusive by parent container type), so one flag/rendering covers both.
    boolean hasBadge = false;

    Coord basec = null;
    NArea.Ingredient.Type type = NArea.Ingredient.Type.CONTAINER;

    // Whether this entry's action is a flower-menu action - independent of the Type marking above.
    boolean isFlowerAction = false;

    int val;

    // Forager pickup priority (lower = checked first, -1 = unset) - independent of hasBadge/val/q
    // above since an item can have both a Maintain cap and a priority at once.
    int priority = -1;
    TexI priorityTex;

    String name;

    void setFlowerAction(boolean isFlowerAction) {
        this.isFlowerAction = isFlowerAction;
    }

    public IconItem(String name, BufferedImage img, Widget parent)
    {
        this.parent = parent;
        this.name = name;
        tip = new TexI(RichText.render(name).img);

        tex = new TexI(img);
        this.sz = UI.scale(new Coord(32, 42));
    }

    public IconItem(String name, TexI img)
    {
        this.name = name;
        tip = new TexI(RichText.render(name).img);

        tex = img;
        this.sz = UI.scale(new Coord(32, 42));
    }

    void update(String name, BufferedImage img)
    {
        this.name = name;
        tip = new TexI(RichText.render(name).img);
        tex = new TexI(img);
    }

    public IconItem()
    {
        this.sz = UI.scale(new Coord(32, 42));
    }

    @Override
    public void draw(GOut g)
    {
        if (tex != null)
        {
            if(hasBadge)
            {
                g.image(framet, Coord.z, UI.scale(32, 42));
                g.image(q, new Coord(UI.scale(16)-q.sz().x/2,UI.scale(28)));
            }
            else
            {
                g.image(frame, Coord.z, UI.scale(32, 32));
            }
            g.image(tex, Coord.z, UI.scale(32,32));
            if(type == NArea.Ingredient.Type.BARTER)
            {
                g.image(bm, UI.scale(16,16), UI.scale(16, 16));
            }
            if(type == NArea.Ingredient.Type.BARREL)
            {
                g.image(barm, UI.scale(16,16), UI.scale(16, 16));
            }
            if(isFlowerAction)
            {
                g.image(flowerMark, UI.scale(16, 0), UI.scale(16, 16));
            }
            if(priority >= 0 && priorityTex != null)
            {
                g.image(priorityTex, Coord.z);
            }
        }
    }

    @Override
    public Object tooltip(Coord c, Widget prev)
    {
        return tip;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b==3)
        {
            if(!noOpts)
                opts(c);
            return true;
        }
        else
        {
            return super.mousedown(ev);
        }

    }

    NFlowerMenu menu;
    
    // Map to reverse lookup: localized name -> key
    private Map<String, String> menuKeyMap = new HashMap<>();
    
    private String addMenuOption(ArrayList<String> opts, String key) {
        String localized = L10n.get(key);
        opts.add(localized);
        menuKeyMap.put(localized, key);
        return localized;
    }

    public void opts( Coord c ) {
        if(menu == null) {
            menuKeyMap.clear();
            ArrayList<String> optList = new ArrayList<>();

            if (parent instanceof IngredientContainer || parent instanceof DropContainer)
                addMenuOption(optList, KEY_THRESHOLD);
            addMenuOption(optList, KEY_DELETE);
            if (parent instanceof TaggableItemContainer) {
                addMenuOption(optList, KEY_EDIT);
                addMenuOption(optList, KEY_MAINTAIN);
                addMenuOption(optList, KEY_PRIORITY);
            }
            if (parent instanceof IngredientContainer) {
                if (type == NArea.Ingredient.Type.CONTAINER) {
                    addMenuOption(optList, KEY_MARK_BARTER);
                    addMenuOption(optList, KEY_MARK_BARREL);
                } else {
                    addMenuOption(optList, KEY_UNMARK);
                }
            }

            String[] opts = optList.toArray(new String[0]);
            menu = new NFlowerMenu(opts) {

                public boolean mousedown(MouseDownEvent ev) {
                    if(super.mousedown(ev))
                        nchoose(null);
                    return(true);
                }

                public void destroy() {
                    menu = null;
                    super.destroy();
                }

                @Override
                public void nchoose(NPetal option)
                {
                    if(option!=null)
                    {
                        // Get the key from the localized name
                        String key = menuKeyMap.get(option.name);
                        if (key == null) key = "";
                        
                        if (key.equals(KEY_THRESHOLD))
                        {
                            Widget par = IconItem.this.parent;
                            Coord pos = IconItem.this.c.add(UI.scale(32, 38));
                            while (par != null && !(par instanceof GameUI))
                            {
                                pos = pos.add(par.c);
                                par = par.parent;
                            }
                            SetThreshold st = new SetThreshold(val, L10n.get("iconitem.threshold"), newVal -> {
                                if (IconItem.this.parent instanceof IngredientContainer)
                                    ((IngredientContainer) IconItem.this.parent).setThreshold(IconItem.this.name, newVal);
                                else if (IconItem.this.parent instanceof DropContainer)
                                    ((DropContainer) IconItem.this.parent).setThreshold(IconItem.this.name, newVal);
                            });
                            ui.root.add(st, pos);

                        }
                        else if (key.equals(KEY_MAINTAIN))
                        {
                            Widget par = IconItem.this.parent;
                            Coord pos = IconItem.this.c.add(UI.scale(32, 38));
                            while (par != null && !(par instanceof GameUI))
                            {
                                pos = pos.add(par.c);
                                par = par.parent;
                            }
                            TaggableItemContainer tc = (TaggableItemContainer) IconItem.this.parent;
                            SetThreshold st = new SetThreshold(tc.getMaintainQuantity(IconItem.this.name), L10n.get("iconitem.maintain"),
                                    newVal -> tc.setMaintainQuantity(IconItem.this.name, newVal));
                            ui.root.add(st, pos);
                        }
                        else if (key.equals(KEY_PRIORITY))
                        {
                            Widget par = IconItem.this.parent;
                            Coord pos = IconItem.this.c.add(UI.scale(32, 38));
                            while (par != null && !(par instanceof GameUI))
                            {
                                pos = pos.add(par.c);
                                par = par.parent;
                            }
                            TaggableItemContainer tc = (TaggableItemContainer) IconItem.this.parent;
                            SetThreshold st = new SetThreshold(tc.getPriority(IconItem.this.name), L10n.get("iconitem.priority"),
                                    newVal -> tc.setPriority(IconItem.this.name, newVal), false);
                            ui.root.add(st, pos);
                        }
                        else if(key.equals(KEY_DELETE))
                        {
                            ((BaseIngredientContainer)IconItem.this.parent).delete(IconItem.this.name);
                        }
                        else if(key.equals(KEY_MARK_BARTER))
                        {
                            ((IngredientContainer)IconItem.this.parent).setType(IconItem.this.name, NArea.Ingredient.Type.BARTER);
                        }
                        else if(key.equals(KEY_MARK_BARREL))
                        {
                            ((IngredientContainer)IconItem.this.parent).setType(IconItem.this.name, NArea.Ingredient.Type.BARREL);
                        }
                        else if(key.equals(KEY_UNMARK))
                        {
                            ((IngredientContainer)IconItem.this.parent).setType(IconItem.this.name, NArea.Ingredient.Type.CONTAINER);
                        }
                        else if(key.equals(KEY_EDIT))
                        {
                            ((TaggableItemContainer)IconItem.this.parent).editItem(IconItem.this.name);
                        }
                    }
                    uimsg("cancel");
                }

            };
            menu.shiftMode = true;
            Widget par = parent;
            Coord pos = IconItem.this.c.add(UI.scale(60,60));
            while(par!=null && !(par instanceof GameUI))
            {
                pos = pos.add(par.c);
                par = par.parent;
            }
            ui.root.add(menu, pos);
        }
    }

    public JSONObject toJson() {
        return src;
    }


    class SetThreshold extends Window
    {
        // Generic "set a small number for this icon" popup, shared by Threshold/Maintain (the
        // shared hasBadge/val/q badge) and Priority (its own separate priority/priorityTex
        // fields, since an item can have both a Maintain cap and a priority at once).
        public SetThreshold(int val, String title, java.util.function.IntConsumer onSet)
        {
            this(val, title, onSet, true);
        }

        public SetThreshold(int val, String title, java.util.function.IntConsumer onSet, boolean isBadge)
        {
            super(UI.scale(140,25), title);
            TextEntry te;
            prev = add(te = new TextEntry(UI.scale(80),String.valueOf(val)));
            add(new Button(UI.scale(50), L10n.get("iconitem.btn_set")){
                @Override
                public void click()
                {
                    super.click();
                    try
                    {
                        int newVal = Integer.parseInt(te.text());
                        if (isBadge)
                        {
                            IconItem.this.hasBadge = true;
                            IconItem.this.val = newVal;
                            IconItem.this.q = new TexI(NStyle.iiqual.render(te.text()).img);
                        }
                        else
                        {
                            IconItem.this.priority = newVal;
                            IconItem.this.priorityTex = new TexI(NStyle.iiqual.render(te.text()).img);
                        }
                        onSet.accept(newVal);
                    }
                    catch (NumberFormatException e)
                    {
                        if (isBadge)
                        {
                            IconItem.this.hasBadge = false;
                        }
                        else
                        {
                            IconItem.this.priority = -1;
                            IconItem.this.priorityTex = null;
                        }
                        onSet.accept(-1);
                    }
                    ui.destroy(SetThreshold.this);

                }
            },prev.pos("ur").add(5,-5));
        }

        @Override
        public void wdgmsg(String msg, Object... args)
        {
            if(msg.equals("close"))
            {
                destroy();
            }
            else
            {
                super.wdgmsg(msg, args);
            }
        }
    }
}
