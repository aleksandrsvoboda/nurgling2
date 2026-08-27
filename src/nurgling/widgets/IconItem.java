package nurgling.widgets;

import haven.*;
import nurgling.*;
import nurgling.areas.*;
import nurgling.i18n.L10n;
import org.json.JSONObject;

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
    private static final String KEY_CLEAR_TAG = "iconitem.clear_tag";
    private static final String KEY_EDIT = "iconitem.edit";
    private static final String KEY_NEW_ACTION = "iconitem.new_action";
    // Not an L10n key - tag option labels come from TaggableItemContainer.tagOptions() and are
    // already display text, so they're distinguished in menuKeyMap by this literal prefix instead.
    private static final String TAG_KEY_PREFIX = "tag:";
    public static final TexI frame = new TexI(Resource.loadimg("nurgling/hud/iconframe"));
    public static final TexI framet = new TexI(Resource.loadimg("nurgling/hud/iconframet"));
    public static final TexI bm = new TexI(Resource.loadimg("nurgling/hud/bartermark"));
    public static final TexI barm = new TexI(Resource.loadimg("nurgling/hud/barrelmark"));
    public JSONObject src;
    TexI tex = null;

    TexI tip;
    TexI q;
    boolean noOpts = false;
    boolean isThreshold = false;

    Coord basec = null;
    NArea.Ingredient.Type type = NArea.Ingredient.Type.CONTAINER;

    // Generic per-item tag (e.g. Forager's "Pick Fruit"/"Pick Nuts"), independent of the
    // NArea.Ingredient.Type marking above - see TaggableItemContainer.
    String customTag = null;
    private TexI customTagTex = null;

    int val;

    String name;

    void setCustomTag(String tag) {
        this.customTag = tag;
        this.customTagTex = (tag == null || tag.isEmpty()) ? null : new TexI(NStyle.iiqual.render(tag).img);
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
            if(isThreshold)
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
            if(customTagTex != null)
            {
                g.image(customTagTex, Coord.z);
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
            // Actions/tags (Barter/Barrel/Unmark, or Forager's pick-action tags) - the "what
            // role does this item play" choices.
            if(!noOpts)
                opts(c, false);
            return true;
        }
        else if(ev.b==1)
        {
            // Management (Threshold/Delete, or Forager's Edit Pattern) - kept separate from the
            // actions above so picking a tag/mark and removing/fixing an entry aren't mixed into
            // one long menu.
            if(!noOpts)
                opts(c, true);
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

    public void opts( Coord c, boolean managementSide ) {
        if(menu == null) {
            menuKeyMap.clear();
            ArrayList<String> optList = new ArrayList<>();

            if(managementSide) {
                // Left click: Threshold/Delete (and Forager's Edit Pattern) - entry upkeep,
                // never the item's role/tag.
                if (parent instanceof IngredientContainer || parent instanceof DropContainer)
                    addMenuOption(optList, KEY_THRESHOLD);
                addMenuOption(optList, KEY_DELETE);
                if (parent instanceof TaggableItemContainer) {
                    addMenuOption(optList, KEY_EDIT);
                }
            } else {
                // Right click: what role this item plays - Barter/Barrel/Unmark for area
                // ingredients, or Forager's pick-action tags (plus a way to grow that list).
                if (parent instanceof IngredientContainer) {
                    if (type == NArea.Ingredient.Type.CONTAINER) {
                        addMenuOption(optList, KEY_MARK_BARTER);
                        addMenuOption(optList, KEY_MARK_BARREL);
                    } else {
                        addMenuOption(optList, KEY_UNMARK);
                    }
                }
                if (parent instanceof TaggableItemContainer) {
                    TaggableItemContainer tc = (TaggableItemContainer) parent;
                    for (String tagOpt : tc.tagOptions(name)) {
                        optList.add(tagOpt);
                        menuKeyMap.put(tagOpt, TAG_KEY_PREFIX + tagOpt);
                    }
                    if (customTag != null) {
                        String clearLabel = L10n.get(KEY_CLEAR_TAG);
                        optList.add(clearLabel);
                        menuKeyMap.put(clearLabel, KEY_CLEAR_TAG);
                    }
                    addMenuOption(optList, KEY_NEW_ACTION);
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
                            SetThreshold st = new SetThreshold(val);
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
                        else if(key.equals(KEY_CLEAR_TAG))
                        {
                            IconItem.this.setCustomTag(null);
                            ((TaggableItemContainer)IconItem.this.parent).setTag(IconItem.this.name, null);
                        }
                        else if(key.startsWith(TAG_KEY_PREFIX))
                        {
                            String tagValue = key.substring(TAG_KEY_PREFIX.length());
                            IconItem.this.setCustomTag(tagValue);
                            ((TaggableItemContainer)IconItem.this.parent).setTag(IconItem.this.name, tagValue);
                        }
                        else if(key.equals(KEY_EDIT))
                        {
                            ((TaggableItemContainer)IconItem.this.parent).editItem(IconItem.this.name);
                        }
                        else if(key.equals(KEY_NEW_ACTION))
                        {
                            ((TaggableItemContainer)IconItem.this.parent).promptNewTag(IconItem.this.name, IconItem.this::setCustomTag);
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
        public SetThreshold(int val)
        {
            super(UI.scale(140,25), L10n.get("iconitem.threshold"));
            TextEntry te;
            prev = add(te = new TextEntry(UI.scale(80),String.valueOf(val)));
            add(new Button(UI.scale(50), L10n.get("iconitem.btn_set")){
                @Override
                public void click()
                {
                    super.click();
                    try
                    {
                        IconItem.this.isThreshold = true;
                        IconItem.this.val = Integer.valueOf(te.text());
                        IconItem.this.q = new TexI(NStyle.iiqual.render(te.text()).img);
                        if(IconItem.this.parent instanceof IngredientContainer)
                            ((IngredientContainer)IconItem.this.parent).setThreshold(IconItem.this.name,IconItem.this.val);
                        else if(IconItem.this.parent instanceof DropContainer)
                            ((DropContainer)IconItem.this.parent).setThreshold(IconItem.this.name,IconItem.this.val);
                    }
                    catch (NumberFormatException e)
                    {
                        IconItem.this.isThreshold = false;
                        if(IconItem.this.parent instanceof IngredientContainer)
                            ((IngredientContainer)IconItem.this.parent).setThreshold(IconItem.this.name,-1);
                        else if(IconItem.this.parent instanceof DropContainer)
                            ((DropContainer)IconItem.this.parent).setThreshold(IconItem.this.name,-1);
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
