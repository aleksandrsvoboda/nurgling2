package nurgling.actions.bots.blueprint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Building names by the object they place, from the game's build list: what a planner ghost
 * ("gfx/terobjs/kiln") is called in the build menu and the wiki ("Kiln"), whether or not the
 * character has its button. A few objects come from more than one button (a wooden or a metal
 * plow); the first name is the plainer one.
 */
public final class BuildingNames
{
    private static final Map<String, List<String>> names = new HashMap<>();

    static
    {
        names.put("gfx/terobjs/bpyre", List.of("Bough Pyre"));
        names.put("gfx/terobjs/pclaim", List.of("Claim Land"));
        names.put("gfx/terobjs/items/diversweight", List.of("Diver's Weight"));
        names.put("gfx/terobjs/embankmentscaffold", List.of("Embankment Scaffold"));
        names.put("gfx/terobjs/villageidol", List.of("Form Village"));
        names.put("gfx/terobjs/coronationstone", List.of("Found Realm"));
        names.put("gfx/terobjs/items/fryingpan", List.of("Frying Pan"));
        names.put("gfx/terobjs/items/smithshammer", List.of("Smithy's Hammer"));
        names.put("gfx/terobjs/wildernessbeacon", List.of("Wilderness Beacon"));
        names.put("gfx/terobjs/arch/belltower", List.of("Bell Tower"));
        names.put("gfx/terobjs/gravecairn", List.of("Grave Cairn"));
        names.put("gfx/terobjs/dolmen", List.of("Runed Dolmen"));
        names.put("gfx/terobjs/barterhand", List.of("Barter Pole"));
        names.put("gfx/terobjs/barterstand", List.of("Barter Stand"));
        names.put("gfx/terobjs/cistern", List.of("Cistern"));
        names.put("gfx/terobjs/arch/cellardoor", List.of("Construct Cellar"));
        names.put("gfx/terobjs/shed", List.of("Garden Shed"));
        names.put("gfx/terobjs/granary", List.of("Granary"));
        names.put("gfx/terobjs/leanto", List.of("Leanto"));
        names.put("gfx/terobjs/arch/machinemill", List.of("Milling Machine"));
        names.put("gfx/terobjs/arch/sawmill", List.of("Sawmill"));
        names.put("gfx/terobjs/well", List.of("Well"));
        names.put("gfx/terobjs/bonfire", List.of("Bonfire"));
        names.put("gfx/terobjs/crucible", List.of("Crucible"));
        names.put("gfx/terobjs/fineryforge", List.of("Finery Forge"));
        names.put("gfx/terobjs/pow", List.of("Fireplace"));
        names.put("gfx/terobjs/kiln", List.of("Kiln"));
        names.put("gfx/terobjs/oven", List.of("Oven"));
        names.put("gfx/terobjs/smelter", List.of("Smelter"));
        names.put("gfx/terobjs/smokeshed", List.of("Smoke Shed"));
        names.put("gfx/terobjs/primsmelter", List.of("Stack Furnace"));
        names.put("gfx/terobjs/steelcrucible", List.of("Steel Crucible"));
        names.put("gfx/terobjs/tarkiln", List.of("Tar Kiln"));
        names.put("gfx/terobjs/birdhouse", List.of("Birdhouse"));
        names.put("gfx/terobjs/chickencoop", List.of("Chicken Coop"));
        names.put("gfx/terobjs/dovecote", List.of("Dovecote"));
        names.put("gfx/terobjs/arch/greathall", List.of("Great Hall"));
        names.put("gfx/terobjs/arch/greenhouse", List.of("Greenhouse"));
        names.put("gfx/terobjs/arch/igloo", List.of("Igloo"));
        names.put("gfx/terobjs/arch/logcabin", List.of("Log Cabin"));
        names.put("gfx/terobjs/arch/primitivetent", List.of("Primitive Tent"));
        names.put("gfx/terobjs/rabbithutch", List.of("Rabbit Hutch"));
        names.put("gfx/terobjs/arch/stonemansion", List.of("Stone Mansion"));
        names.put("gfx/terobjs/arch/stonetower", List.of("Stone Tower"));
        names.put("gfx/terobjs/arch/stonehut", List.of("Stonehut"));
        names.put("gfx/terobjs/arch/stonestead", List.of("Stonestead"));
        names.put("gfx/terobjs/arch/thatchedhut", List.of("Thatched Hut"));
        names.put("gfx/terobjs/arch/timberhouse", List.of("Timber House"));
        names.put("gfx/terobjs/arch/waterwheelhouse", List.of("Watermill"));
        names.put("gfx/terobjs/arch/windmill", List.of("Windmill"));
        names.put("gfx/terobjs/minebeam", List.of("Mine Beam"));
        names.put("gfx/terobjs/minevator-top", List.of("Mine Elevator"));
        names.put("gfx/terobjs/minepyre", List.of("Mine Pyre"));
        names.put("gfx/terobjs/minesupport", List.of("Mine Support"));
        names.put("gfx/terobjs/minehole", List.of("Minehole"));
        names.put("gfx/terobjs/monumentalcolumn", List.of("Monumental Column"));
        names.put("gfx/terobjs/reinforcedtunnel", List.of("Reinforced Tunnel"));
        names.put("gfx/terobjs/stonearchtunnel", List.of("Stone Arch Tunnel"));
        names.put("gfx/terobjs/column", List.of("Stone Column"));
        names.put("gfx/terobjs/timbertunnel", List.of("Timber Tunnel"));
        names.put("gfx/terobjs/road/milestone-stone-e", List.of("Milestone"));
        names.put("gfx/terobjs/knarrdock", List.of("Ship Dock"));
        names.put("gfx/terobjs/vehicle/wagonstation", List.of("Wagon Station"));
        names.put("gfx/terobjs/road/milestone-wood-e", List.of("Wooden Roadsign"));
        names.put("gfx/terobjs/arch/brickwallcp", List.of("Brickwall"));
        names.put("gfx/terobjs/arch/drystonewallcp", List.of("Dry Stone Fence"));
        names.put("gfx/terobjs/arch/palisadecp", List.of("Palisade"));
        names.put("gfx/terobjs/arch/polecp", List.of("Roundpole Fence"));
        names.put("gfx/terobjs/arch/totemgate", List.of("Totemic Gate"));
        names.put("gfx/terobjs/cupboard", List.of("Cupboard"));
        names.put("gfx/terobjs/metalcabinet", List.of("Metal Cabinet"));
        names.put("gfx/terobjs/barrel", List.of("Barrel"));
        names.put("gfx/terobjs/demijohn", List.of("Demijohn"));
        names.put("gfx/terobjs/picklingcrock", List.of("Pickling Crock"));
        names.put("gfx/terobjs/winerack", List.of("Wine Rack"));
        names.put("gfx/terobjs/birchbasket", List.of("Birchbark Basket"));
        names.put("gfx/terobjs/leatherbasket", List.of("Leather Basket"));
        names.put("gfx/terobjs/strawbasket", List.of("Straw Basket"));
        names.put("gfx/terobjs/thatchbasket", List.of("Thatch Basket"));
        names.put("gfx/terobjs/wbasket", List.of("Wicker Basket"));
        names.put("gfx/terobjs/crate", List.of("Crate"));
        names.put("gfx/terobjs/linencrate", List.of("Linen Crate"));
        names.put("gfx/terobjs/woodbox", List.of("Wooden Box"));
        names.put("gfx/terobjs/bonechest", List.of("Bone Chest"));
        names.put("gfx/terobjs/coffer", List.of("Coffer"));
        names.put("gfx/terobjs/exquisitechest", List.of("Exquisite Chest"));
        names.put("gfx/terobjs/largechest", List.of("Large Chest"));
        names.put("gfx/terobjs/stonecasket", List.of("Stone Casket"));
        names.put("gfx/terobjs/strongbox", List.of("Strongbox"));
        names.put("gfx/terobjs/chest", List.of("Wooden Chest"));
        names.put("gfx/terobjs/trough", List.of("Food Trough"));
        names.put("gfx/terobjs/hitchingpost", List.of("Hitching Post"));
        names.put("gfx/terobjs/moundbed", List.of("Mound Bed"));
        names.put("gfx/terobjs/plants/trellis", List.of("Trellis"));
        names.put("gfx/terobjs/ancestralshrine", List.of("Ancestral Shrine"));
        names.put("gfx/terobjs/archerytarget", List.of("Archery Target"));
        names.put("gfx/terobjs/dressingtable", List.of("Dressing Table"));
        names.put("gfx/terobjs/studydesk-big", List.of("Fine Study Desk"));
        names.put("gfx/terobjs/furn/gongstand", List.of("Gong Stand"));
        names.put("gfx/terobjs/grandstudydesk", List.of("Grand Study Desk"));
        names.put("gfx/terobjs/postbox", List.of("Postbox"));
        names.put("gfx/terobjs/studycircle", List.of("Study Circle"));
        names.put("gfx/terobjs/studydesk", List.of("Study Desk"));
        names.put("gfx/terobjs/wardrobe", List.of("Wardrobe"));
        names.put("gfx/terobjs/furn/bed-sturdy", List.of("Sturdy Bed"));
        names.put("gfx/terobjs/furn/boughbed", List.of("Thatched Bed"));
        names.put("gfx/terobjs/furn/archedchair", List.of("Arched Chair"));
        names.put("gfx/terobjs/furn/armchair-cloth", List.of("Cloth Armchair"));
        names.put("gfx/terobjs/furn/chair-cloth", List.of("Cloth Chair"));
        names.put("gfx/terobjs/furn/cottagethrone", List.of("Cottage Throne"));
        names.put("gfx/terobjs/furn/ramchair", List.of("Ram Chair"));
        names.put("gfx/terobjs/furn/royalthrone", List.of("Royal Throne"));
        names.put("gfx/terobjs/furn/chair-rustic", List.of("Rustic Chair"));
        names.put("gfx/terobjs/furn/stonethrone", List.of("Stone Throne"));
        names.put("gfx/terobjs/furn/thatchedchair", List.of("Thatched Chair"));
        names.put("gfx/terobjs/furn/cushionedbench", List.of("Cushioned Bench"));
        names.put("gfx/terobjs/furn/finesofa", List.of("Fine Sofa"));
        names.put("gfx/terobjs/furn/gardenbench", List.of("Garden Bench"));
        names.put("gfx/terobjs/furn/longbench", List.of("Long Bench"));
        names.put("gfx/terobjs/furn/stonebench", List.of("Stone Bench"));
        names.put("gfx/terobjs/furn/chair-rocking", List.of("Rocking Chair"));
        names.put("gfx/terobjs/furn/rockinghorse", List.of("Rocking Horse"));
        names.put("gfx/terobjs/furn/footstool-cloth", List.of("Cloth Footstool"));
        names.put("gfx/terobjs/furn/cushionedstool", List.of("Cushioned Stool"));
        names.put("gfx/terobjs/furn/royalstool", List.of("Royal Stool"));
        names.put("gfx/terobjs/bellfountain", List.of("Bell Fountain"));
        names.put("gfx/terobjs/displaycase", List.of("Display Case"));
        names.put("gfx/terobjs/furn/hatrack", List.of("Hatrack"));
        names.put("gfx/terobjs/mannequin-stand", List.of("Mannequin"));
        names.put("gfx/terobjs/iconsign", List.of("Display Sign"));
        names.put("gfx/terobjs/easel", List.of("Easel"));
        names.put("gfx/terobjs/facecutoutstandee", List.of("Face Cutout Standee"));
        names.put("gfx/terobjs/flagpole", List.of("Flagpole"));
        names.put("gfx/terobjs/foldupsign", List.of("Foldup Sign"));
        names.put("gfx/terobjs/noticeboard", List.of("Notice Board"));
        names.put("gfx/terobjs/signboard", List.of("Signboard"));
        names.put("gfx/terobjs/standingflag", List.of("Standing Flag"));
        names.put("gfx/terobjs/woodensign", List.of("Written Sign"));
        names.put("gfx/terobjs/brazier", List.of("Brazier"));
        names.put("gfx/terobjs/candelabrum", List.of("Candelabrum"));
        names.put("gfx/terobjs/hookah", List.of("Hookah"));
        names.put("gfx/terobjs/lanternpost", List.of("Lantern Post"));
        names.put("gfx/terobjs/torchpost", List.of("Torchpost"));
        names.put("gfx/terobjs/furn/cottagetable", List.of("Cottage Table"));
        names.put("gfx/terobjs/furn/table-elegant", List.of("Elegant Table"));
        names.put("gfx/terobjs/furn/table-rustic", List.of("Rustic Table"));
        names.put("gfx/terobjs/furn/table-stone", List.of("Stone Table"));
        names.put("gfx/terobjs/beehive", List.of("Bee Skep"));
        names.put("gfx/terobjs/cheeserack", List.of("Cheese Rack"));
        names.put("gfx/terobjs/compostbin", List.of("Compost Bin"));
        names.put("gfx/terobjs/curdingtub", List.of("Curding Tub"));
        names.put("gfx/terobjs/dreca", List.of("Dream Catcher"));
        names.put("gfx/terobjs/dframe", List.of("Drying Frame"));
        names.put("gfx/terobjs/glasspaneframe", List.of("Glass Pane Frame"));
        names.put("gfx/terobjs/htable", List.of("Herbalist Table"));
        names.put("gfx/terobjs/still", List.of("Still"));
        names.put("gfx/terobjs/ttub", List.of("Tanning Tub"));
        names.put("gfx/terobjs/archerytower", List.of("Archery Tower"));
        names.put("gfx/terobjs/vehicle/wreckingball", List.of("Wrecking Ball"));
        names.put("gfx/terobjs/vehicle/cart", List.of("Cart"));
        names.put("gfx/terobjs/vehicle/finesled", List.of("Fine Sled"));
        names.put("gfx/terobjs/vehicle/spark", List.of("Kicksled"));
        names.put("gfx/terobjs/vehicle/plow", List.of("Wooden Plow", "Metal Plow"));
        names.put("gfx/terobjs/vehicle/primsled", List.of("Primitive Sled"));
        names.put("gfx/terobjs/vehicle/sleigh", List.of("Sleigh"));
        names.put("gfx/terobjs/vehicle/wagon", List.of("Wagon"));
        names.put("gfx/terobjs/vehicle/wheelbarrow", List.of("Wheelbarrow"));
        names.put("gfx/terobjs/vehicle/coracle", List.of("Coracle"));
        names.put("gfx/terobjs/vehicle/knarrsail", List.of("Knarr Sail"));
        names.put("gfx/terobjs/vehicle/raft", List.of("Raft"));
        names.put("gfx/terobjs/vehicle/rowboat", List.of("Rowboat"));
        names.put("gfx/terobjs/vehicle/snekkja", List.of("Snekkja"));
        names.put("gfx/terobjs/vehicle/snekkjasail", List.of("Snekkja Sail"));
        names.put("gfx/terobjs/alchemiststable", List.of("Alchemist's Table"));
        names.put("gfx/terobjs/anvil", List.of("Anvil"));
        names.put("gfx/terobjs/churn", List.of("Churn"));
        names.put("gfx/terobjs/cauldron", List.of("Clay Cauldron", "Metal Cauldron"));
        names.put("gfx/terobjs/coinpress", List.of("Coinpress"));
        names.put("gfx/terobjs/winepress", List.of("Extraction Press"));
        names.put("gfx/terobjs/gemwheel", List.of("Gemcutter's Wheel"));
        names.put("gfx/terobjs/loom", List.of("Loom"));
        names.put("gfx/terobjs/meatgrinder", List.of("Meatgrinder"));
        names.put("gfx/terobjs/potterswheel", List.of("Potter's Wheel"));
        names.put("gfx/terobjs/ropewalk", List.of("Rope Walk"));
        names.put("gfx/terobjs/swheel", List.of("Spinning Wheel"));
    }

    private BuildingNames() {}

    /** Every building name that places this object; empty when it is not a known building. */
    public static List<String> of(String gobRes)
    {
        return names.getOrDefault(gobRes, List.of());
    }

    /** The building's name ("Wooden Plow / Metal Plow" when several place it), or null. */
    public static String nameOf(String gobRes)
    {
        List<String> n = of(gobRes);
        return n.isEmpty() ? null : String.join(" / ", n);
    }
}
