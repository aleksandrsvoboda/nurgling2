package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NBoatFishingSettings;
import nurgling.conf.NFishingSettings;
import nurgling.i18n.L10n;

import java.util.ArrayList;

/** Boat Fisher's start window. Copied from the Fishing window without the zone, pile and inventory-tools options;
 *  the target list belongs to the Primitive Casting-Rod, whose bites come with the fish list; the pole catches on its own. */
public class BoatFishing extends Window implements Checkable {
    private static final String POLE = "Bushcraft Fishingpole";

    private final UsingTools rods;
    private final UsingTools lines;
    private final UsingTools baits;
    private final UsingTools hooks;
    private final Label baitLab;
    private final Button targetsBtn;
    private final CheckBox goForBest;
    private final CheckBox anyLine;
    private final CheckBox anyHook;
    private final CheckBox anyBait;
    private final Label anyBaitLab;
    private final FishingTarget fishwnd;
    private final NFishingSettings targetHolder;
    public NBoatFishingSettings prop;
    boolean isReady = false;

    public BoatFishing() {
        super(UI.scale(200, 400), L10n.get("boatfishing.wnd_title"));
        NBoatFishingSettings saved = NBoatFishingSettings.get(NUtils.getUI().sessInfo);
        if (saved == null)
            saved = new NBoatFishingSettings("", "");
        final NBoatFishingSettings start = saved;

        prev = add(new Label(L10n.get("fishing.tool")));
        prev = add(rods = new UsingTools(UsingTools.Tools.fishrot, false) {
            @Override
            public void update() {
                onRodChanged();
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));
        select(rods, start.tool);

        prev = add(new Label(L10n.get("fishing.fishline")), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(lines = new UsingTools(UsingTools.initByCategories("Fishline"), false), prev.pos("bl").add(UI.scale(0, 5)));
        select(lines, start.fishline);
        prev = add(anyLine = new CheckBox(L10n.get("boatfishing.any_line")) {
            {
                a = start.anyLine;
            }
        }, prev.pos("bl").add(UI.scale(0, 3)));

        boolean pole = POLE.equals(start.tool);
        prev = add(baitLab = new Label(L10n.get(pole ? "fishing.bait" : "fishing.lure")), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(baits = new UsingTools(UsingTools.initByCategories(pole ? "Bait" : "Lures"), false), prev.pos("bl").add(UI.scale(0, 5)));
        select(baits, start.bait);
        // A bare checkbox plus its own label, which follows the rod (CheckBox text can't change).
        prev = add(anyBait = new CheckBox("") {
            {
                a = start.anyBait;
            }
        }, prev.pos("bl").add(UI.scale(0, 3)));
        anyBaitLab = add(new Label(L10n.get(pole ? "boatfishing.any_bait" : "boatfishing.any_lure")), prev.pos("ur").add(UI.scale(4, 0)));

        prev = add(new Label(L10n.get("fishing.hook")), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(hooks = new UsingTools(UsingTools.initByCategories("Hooks"), false), prev.pos("bl").add(UI.scale(0, 5)));
        select(hooks, start.hook);
        prev = add(anyHook = new CheckBox(L10n.get("boatfishing.any_hook")) {
            {
                a = start.anyHook;
            }
        }, prev.pos("bl").add(UI.scale(0, 3)));

        targetHolder = new NFishingSettings("", "");
        targetHolder.targets = new ArrayList<>(start.targets);
        fishwnd = NUtils.getGameUI().add(new FishingTarget(targetHolder));
        fishwnd.hide();
        prev = add(targetsBtn = new Button(UI.scale(150), L10n.get("boatfishing.targets")) {
            @Override
            public void click() {
                super.click();
                fishwnd.show();
                fishwnd.c = BoatFishing.this.c.add(this.c).add(UI.scale(0, 25));
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));
        prev = add(goForBest = new CheckBox(L10n.get("boatfishing.go_for_best")) {
            {
                a = start.goForBest;
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));
        targetsBtn.visible = !pole;
        goForBest.visible = !pole;

        prev = add(new Button(UI.scale(150), L10n.get("botwnd.start")) {
            @Override
            public void click() {
                super.click();
                prop = NBoatFishingSettings.get(NUtils.getUI().sessInfo);
                if (prop != null) {
                    prop.tool = rods.s != null ? rods.s.name : null;
                    prop.fishline = lines.s != null ? lines.s.name : null;
                    prop.hook = hooks.s != null ? hooks.s.name : null;
                    prop.bait = baits.s != null ? baits.s.name : null;
                    prop.targets = new ArrayList<>(targetHolder.targets);
                    prop.goForBest = goForBest.a;
                    prop.anyLine = anyLine.a;
                    prop.anyHook = anyHook.a;
                    prop.anyBait = anyBait.a;
                    NBoatFishingSettings.set(prop);
                }
                isReady = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));
        pack();
    }

    private void onRodChanged() {
        if (rods.s == null || baits == null || anyBaitLab == null || targetsBtn == null)
            return;
        boolean pole = POLE.equals(rods.s.name);
        String kept = baits.s != null ? baits.s.name : null;
        baits.tools = UsingTools.initByCategories(pole ? "Bait" : "Lures");
        baitLab.settext(L10n.get(pole ? "fishing.bait" : "fishing.lure"));
        anyBaitLab.settext(L10n.get(pole ? "boatfishing.any_bait" : "boatfishing.any_lure"));
        baits.s = null;
        select(baits, kept);
        targetsBtn.visible = !pole;
        goForBest.visible = !pole;
        pack();
    }

    private static void select(UsingTools ut, String name) {
        if (name == null)
            return;
        for (UsingTools.Tool t : ut.tools) {
            if (t.name.equals(name)) {
                ut.s = t;
                return;
            }
        }
    }

    @Override
    public void destroy() {
        fishwnd.destroy();
        super.destroy();
    }

    @Override
    public boolean check() {
        return isReady;
    }
}
