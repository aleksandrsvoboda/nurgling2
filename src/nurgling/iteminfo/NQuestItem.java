package nurgling.iteminfo;

/* $use: ui/tt/wellMined */

import haven.*;
import nurgling.NGItem;
import nurgling.NStyle;
import nurgling.styles.TooltipStyle;
import nurgling.widgets.NQuestInfo;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/* >tt: WellMined */
public class NQuestItem extends ItemInfo.Tip implements GItem.OverlayInfo<Tex> {
    NGItem higlighted;

    public NQuestItem(Owner owner) {
        super(owner);
        higlighted = (NGItem) owner;
    }

    public static ItemInfo mkinfo(Owner owner, Object... args) {
	return(new NQuestItem(owner));
    }

    public static Tex frame = Resource.loadtex("nurgling/hud/items/overlays/quests/frame");
    public static Tex mark = Resource.loadtex("nurgling/hud/items/overlays/quests/mark");
    /** A villager's quest wants this: violet corner brackets and a top-right badge, unlike ours. */
    public static Tex vframe = Resource.loadtex("nurgling/hud/items/overlays/quests/vframe");
    public static Tex vmark = Resource.loadtex("nurgling/hud/items/overlays/quests/vmark");

    private static Text.Foundry wantFnd = null;
    private static Text.Foundry textFnd = null;


    @Override
    public Tex overlay() {
        return frame;
    }

    @Override
    public void drawoverlay(GOut g, Tex data)
    {
        if(higlighted.isQuested) {
            g.aimage(frame, Coord.z, 0, 0, g.sz());
            g.aimage(mark, Coord.z, 0, 0);
        }
        if(higlighted.villageWanted != null) {
            // Our own frame wins when both apply; the badge still says a villager wants it too.
            if(!higlighted.isQuested)
                g.aimage(vframe, Coord.z, 0, 0, g.sz());
            g.aimage(vmark, new Coord(g.sz().x, 0), 1, 0);
        }
    }

    /** "Wanted by <villager>" lines, one per villager objective. Null when no villager wants it. */
    @Override
    public BufferedImage tipimg() {
        List<NQuestInfo.Want> wants = higlighted.villageWanted;
        if(wants == null || wants.isEmpty())
            return null;
        if(wantFnd == null) {
            wantFnd = TooltipStyle.createFoundry(true, TooltipStyle.FONT_SIZE_BODY, NStyle.questVillage);
            textFnd = TooltipStyle.createFoundry(false, TooltipStyle.FONT_SIZE_BODY, NStyle.questDim);
        }
        List<BufferedImage> lines = new ArrayList<>();
        for(NQuestInfo.Want w : wants) {
            BufferedImage who = wantFnd.render("Wanted by " + w.name + (w.online ? "" : " (offline)")).img;
            BufferedImage what = textFnd.render("- " + w.text).img;
            lines.add(ItemInfo.catimgsh(UI.scale(5), who, what));
        }
        return ItemInfo.catimgs(0, lines.toArray(new BufferedImage[0]));
    }
}
