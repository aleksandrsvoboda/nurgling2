# Bot catalogue

Every bot built into the client, runnable from a script with `nc.bots.run(id, params={...})`. Bots marked *interactive* stop to ask the player for input and cannot run on background sessions. Most bots take no parameters: they read your areas and specialisations, just like when started from the menu.

## Utils

| Id | Name | Description | Parameters |
|---|---|---|---|
| `apply_tansy` | Apply Tansy | Takes a Tansy from its configured Take area and rubs it on skin, unless the Scent of Tansy buff is already active. | `targetStacks` (int, default 10): Scent of Tansy stacks to reach. |
| `area_mover` | Area Mover | Carries everything from one area to another. Empties every stockpile and storage container (chests, cupboards, crates, baskets...) in the source area and puts the items into stockpiles and containers in the destination area: first piles that already hold the same item, then containers, then new stockpiles. Items already in your inventory are left alone. Stops when the destination is full. | `fromAreaId` (area, default 0): Area to empty. Leave out both areas to choose them in the Area Mover window.<br>`toAreaId` (area, default 0): Area to move the items into.<br>`createPiles` (bool, default true): Make new stockpiles in the destination when its piles and containers are full. |
| `autocraft_bot` | Autocraft | Automatically crafts items using a saved preset | `presetId` (string, required): Id of the saved craft preset.<br>`quantity` (int, default 1): How many times to craft. |
| `autoflaction` | Auto Flower Action *(interactive)* | Perform the specified flower pop-up menu action for all objects in the area. | — |
| `bed` | Go to bed | Go to any free bed in a bed area nearby. | — |
| `bugs` | Catch bugs | Catch bugs around player. | — |
| `calculators` | Calculators | Opens planning calculators. Cheese racks: how many racks each area needs for a steady rate of cheese. | — |
| `clay_cheker` | Check Clay | Checks clay. | — |
| `clover` | Feed Clover | Feeds clover. | — |
| `collectalltopile` | Collect To Pile *(interactive)* | Collects same items from earth. | — |
| `coracle` | Coracle | Mount or dismount a coracle. | — |
| `destroy` | Destroyer *(interactive)* | Destroy objects in area. | — |
| `destroytrellisplants` | Destroy Trellis Plants *(interactive)* | Destroys all trellis plants in selected area. | — |
| `dream_catcher` | Collect dreams | Collect dreams from all dream catchers in a dream catcher area. | — |
| `drinker` | Drinker | Takes a set number of sips of each drink you choose (0 = skip), each from its own cup. Needs a TAKE area with barrels or demijohns of each drink and a TAKE area with each cup. The bot fetches all the cups in one trip, fills each cup at the first barrel or demijohn holding its drink, sips next to it, pours what is left back into it, and returns the cups. | `sips:Wine` (int, default 0): Sips of Wine to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Wine` (enum, default "Wine Glass"): Cup to drink Wine from.<br>`sips:Beer` (int, default 0): Sips of Beer to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Beer` (enum, default "Tankard"): Cup to drink Beer from.<br>`sips:Weißbier` (int, default 0): Sips of Weißbier to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Weißbier` (enum, default "Tankard"): Cup to drink Weißbier from.<br>`sips:Millet Beer` (int, default 0): Sips of Millet Beer to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Millet Beer` (enum, default "Tankard"): Cup to drink Millet Beer from.<br>`sips:Cider` (int, default 0): Sips of Cider to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Cider` (enum, default "Tankard"): Cup to drink Cider from.<br>`sips:Perry` (int, default 0): Sips of Perry to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Perry` (enum, default "Tankard"): Cup to drink Perry from.<br>`sips:Mead` (int, default 0): Sips of Mead to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Mead` (enum, default "Drinking Horn"): Cup to drink Mead from.<br>`sips:Tea` (int, default 0): Sips of Tea to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Tea` (enum, default "Mug"): Cup to drink Tea from.<br>`sips:Brandy` (int, default 0): Sips of Brandy to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Brandy` (enum, default "Wine Glass"): Cup to drink Brandy from.<br>`sips:Grape Juice` (int, default 0): Sips of Grape Juice to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Grape Juice` (enum, default "Wine Glass"): Cup to drink Grape Juice from.<br>`sips:Apple Juice` (int, default 0): Sips of Apple Juice to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Apple Juice` (enum, default "Wooden Cup"): Cup to drink Apple Juice from.<br>`sips:Milk` (int, default 0): Sips of Milk to take; 0 skips it. With no sips setting at all the Drinker window asks.<br>`cup:Milk` (enum, default "Wooden Cup"): Cup to drink Milk from. |
| `dropsoil` | Drop Soil | Drops soil from stockpile until there is 10 soil left in the stockpile.. | — |
| `eater` | Eating bot | Eat in the food area. | — |
| `equipment_bot` | Equipment Bot | Equip items from a saved preset. | — |
| `filwater` | Fill Waterskins (Select Zone) *(interactive)* | Fills waterskins - always prompts to select water zone. | — |
| `filwaterzone` | Fill Waterskins (Global Zone) | Fills waterskins using global water zone with chunk navigation. | — |
| `fire` | Fire Starter *(interactive)* | Ignites objects (Ovens, Smelters, Kilns, etc.) and refuels them if needed. | — |
| `flag` | Survey Supporter | Survey Supporter. | — |
| `freeinv` | Free inventory | Free inventory with Area system. | — |
| `gate` | Open/Close Gate | Opens or closes the nearest gate, per this step's Action setting; does nothing if it's already in that state. | `mode` (enum, default "open"): Open or close the nearest gate; nothing happens when it already is. |
| `goto_area` | Go to area | Global PF navigate to an area id | `areaId` (area, required): Area to walk to (global navigation). |
| `hearthfire` | Travel to Hearthfire | Travel to Hearthfire | — |
| `leveler` | Leveler | This bot drives all visible land surveys to completion by repeatedly clicking 'Dig' on each survey. For surveys that require fill, it pulls soil from a TAKE area with 'Soil'. Excess soil dug from surveys is dumped into a PUT area with 'Soil', or dropped on the ground in an area with 'soilDump' specialization as a fallback. | — |
| `lift` | Transfer Liftable (Select Zone) *(interactive)* | Lifts items - always prompts for input zone selection. | — |
| `lift_global` | Transfer Liftable (Global Zone) *(interactive)* | Lifts items using global zones with chunk navigation. | — |
| `loading` | Transfer To Vehicle *(interactive)* | Loads vehicle. | — |
| `maintain_stock_bot` | Maintain Stock | Maintains a target stock level by counting items in an area and crafting as needed | `presetId` (string, required): Id of the saved craft preset.<br>`areaId` (area, required): Stock area: holds the preset's output as a Put item.<br>`targetQuantity` (int, required): How many of the output to keep in stock (above 0). |
| `measure_length` | Zone Measure Tool | Measure and mark zones on the ground. Select areas, view dimensions, and manage multiple selections. | — |
| `pave_areas` | Pave Areas | Paves every area with the Paving specialisation, fetching its configured stone from that stone's Take area. | — |
| `qzone` | Quality in Zone *(interactive)* | Scan the quality of all typical objects in the area. | — |
| `set_speed` | Set Movement Speed | Scenario step that switches your movement speed to Crawl, Walk, Run or Sprint and waits for the server to confirm it. If the chosen speed isn't available right now, the fastest allowed one is used. | `speed` (int, default 2): Movement speed: 0 crawl, 1 walk, 2 run, 3 sprint. Capped at the fastest speed allowed right now. |
| `shieldsword` | Equip Shield/Sword | Equips shield and sword. | — |
| `skis` | Wilderness Skis | Mount or dismount wilderness skis. | — |
| `soil` | Create piles with soil *(interactive)* | Create piles with soil in area. | — |
| `sort_containers` | Sort Containers in Area *(interactive)* | Sorts all items across containers in a selected area. | — |
| `studytable` | Fill All Study Desks | Automatically fills every planned study desk (any desk you've set a plan for, not just your own) by using global pathfinding to navigate between areas and retrieve items. To create a study plan: open a study desk, click the 'Plan' button, and drag items into the planning window to set your study goals. If you only want to top up your own desk, use "Fill Study Desk" instead - visiting everyone else's can take a long time. | `fillAll` (bool, default true): Fill every configured study desk (true) or only the one this character owns (false). |
| `studytable_nearest` | Fill Study Desk | Fills just this character's own study desk (the one they last saved a plan for) instead of every configured desk - much faster when you only want to top up your own. Open your desk's planner and click Save at least once to claim it; errors if this character doesn't own a configured desk yet. | `fillAll` (bool, default false): Fill every configured study desk (true) or only the one this character owns (false). |
| `swap` | Swap Vehicles *(interactive)* | Swaps between vehicles. | — |
| `swill_collector` | Swill Collector *(interactive)* | Collects swill items from area and feeds to troughs/cisterns. | — |
| `swill_piles_to_troughs` | Swill Piles to Troughs | Empties every stockpile in every area with the 'Swill stockpiles' specialization and feeds the items to the troughs in the 'Trough for swill' area. Needs no input, so it also works as a scenario step. Piles of anything that isn't swill are left alone. If a 'Swill' area with a cistern is set up next to the troughs, full troughs are emptied into it; otherwise the bot stops once every trough is full. Swill you already carry is fed too. | — |
| `swill_to_trough` | Swill To Trough *(interactive)* | Collects swill from area to selected trough (click to select). | — |
| `travellerssack` | Equip travellers sacks | Equip travellers sacks. | — |
| `unbox` | Free Containers *(interactive)* | Frees containers in area. | — |
| `unbox_zone` | Free Containers in Unbox Zone | Automatically navigates to unbox zone and frees containers. | — |
| `unloading` | Transfer From Vehicle *(interactive)* | Unloads vehicle. | — |
| `wait_bot` | Wait | Waits for a specified duration (hh:mm:ss) | `waitDurationMs` (int, required): How long to wait, in milliseconds. |
| `water_cheker` | Check Water | Checks water. | — |
| `worldexplorer` | World Explorer *(interactive)* | Explores the world. | — |
| `zoneminer` | Mine in area *(interactive)* | Mine rocks in the area. | — |

## Resources

| Id | Name | Description | Parameters |
|---|---|---|---|
| `bark` | Bark picker | Bot collects bark from trees. Select the area with trees, then specify the area where to place bark piles. | — |
| `blueprint_tree_planter` | Blueprint Tree Planter *(interactive)* | This bot plants trees based on a blueprint layout. It is designed to work with TreeGarden blueprints for organized tree planting. You need to specify the planting area and ensure you have tree seeds available. The bot will follow the blueprint pattern to plant trees at designated positions. | — |
| `bough` | Bough picker | Bot collects bough from trees. Select the area with trees, then specify the area where to place bough piles. | — |
| `boughbee` | Beehive Smoker *(interactive)* | Bot smokes out wild beehives and collects honey. Waits for the smoke to clear and bees to calm down, then raids the hive. Automatically reacts to dangerous animals nearby and unknown players based on your settings. | — |
| `chipper` | Chipper *(interactive)* | Bot able to chip boulders in area, or digging rocks from mountain tiles. Select desired instrument for chipping, activate refilling from nearby water area (with 'water' spec) or eating food from your inventory. For digging mountain you need to have pickaxe in your belt. | — |
| `choper` | Chopper *(interactive)* | Chop down trees using axe from your inventory, with uprooting stumps using shovel from your inventory. You can uproot stumps or cut down bushes at the same time, also ignoring growing trees. Auto-refilling waterskins from nearby container is also possible if you have water in a barrel (or cistern) in an area that has 'water' spec. You can also eat food from inventory when hunger is low enough. | — |
| `clay` | Clay digger *(interactive)* | The bot is digging clay into piles. You can either specify the piles area manually or use an area from the 'area system' by specifying 'clay' in the 'Put' section. The bot can dig both ball and acre clay. | — |
| `fisher` | Fisher *(interactive)* | For a normal fishing rod: Select the area to be fished (the middle is selected). Optionally, select if there is a special extra container (fishing lines and hooks).Select the area with bait. And select the area where you want to put the fish. The bot will repair the rod, refill the bait and place the fish in piles. For casting-rod you can specify which of the fish the bot should catch and it will select only those fish when fishing | — |
| `forager` | Forager | Auto forager with using path, for collecting resources and search animals with discord and chat notifications | `presetName` (string, default ""): Forager preset to run. Leave it out to pick the route in the Forager window. |
| `leaf` | Leaf picker | The bot collects leaves from trees. Specify an area with trees to collect leaves from. Then specify the area where to place the leaf piles. | — |
| `leaf_zone` | Leaf Picker (Zones) | Picks leaves from every tree and bush in the nearest 'Trees' area that still has leaves, and carries them to the PUT areas set up for each leaf, as many trips as it takes. Needs no input, so it also works as a scenario step. Trees whose leaf has no PUT area are skipped. | — |
| `lp_gatherer` | LP Gatherer | Looks around for anything the LP helper still marks undiscovered (leaves, nuts and fruit, boughs, bark, boards, blocks, stones). Walks to the nearest reachable one, collects a single item, and repeats until nothing in view has anything left to find. Equips a saw, axe or pickaxe from the belt when needed. Each gathered item is dropped on the ground. | — |
| `masterminer` | Master Miner *(interactive)* | Watches what you mine and works out the real quality of the wall, dropping stone below your threshold. | — |
| `pblocks` | Block preparation *(interactive)* | The bot allows you to create piles of blocks from logs. You need to specify the preferred tool for the job, select the area where the logs are stored, and then specify the area where the piles are to be stacked. | — |
| `pboards` | Board preparation *(interactive)* | The bot allows you to create piles of boards from logs. You need to specify the preferred tool for the job, select the area where the logs are stored, and then specify the area where the piles are to be stacked. | — |
| `plant_trees` | Plant Trees *(interactive)* | Automatically plants trees in your selected area using equipped shovel. Adjust spacing to preview green placement boxes, then confirm to plant all tree seeds from inventory. | — |
| `plower` | Plow *(interactive)* | Plough the area | — |
| `tickbot` | Tick Bot | Runs to the Thicket area, drops ticks into inventory until full, unloads at the "PUT" area, repeats forever. Great early energy food. Make sure the Thicket area is actually in a thicket. Run multiple sessions for more slaves on tick duty. | — |
| `tunneling` | Tunneling Bot *(interactive)* | Digs tunnels with support placement, or use 'No Support' mode for minesweeper-guided mining without supports. | — |

## Productions

| Id | Name | Description | Parameters |
|---|---|---|---|
| `ash` | Block Ash | Blocks ash. | — |
| `backer` | Baker | Bakes stuff. | — |
| `bonestoash` | Bone Ash | Burns bones to ash. | — |
| `branch` | Branch Action *(interactive)* | Processes branches. | — |
| `brick` | Bricks Action | Makes bricks. | — |
| `butcher` | Butcher | Butchers animals. | — |
| `dryedfish` | Dry Fish | Dries fish on drying frames. | — |
| `fineryforge` | Forging  | Forging. | — |
| `fishroast` | Spit Roast *(interactive)* | Roasts fish. | — |
| `gelatin` | Gelatin | Crafts gelatin from hides. Fills inventory with hides from readyHides zone and crafts. | — |
| `grapejuicer` | Grape Juicer | Presses grapes in extraction press and fills barrels with grape juice. | — |
| `hides` | Handle hides. | Handles hides. | — |
| `irrlight` | Irrlight Farmer | Cycles a metal bar into nuggets and back at a lit crucible to call Irrlights, and catches every one that spawns. | — |
| `leather` | Leather Action | Makes leather. | — |
| `lye` | Lye Boiler | Boils lye. | — |
| `mulberry_leaf_refiller` | Refill silkworm cupboards with mulberry leafs | Refill silkworm cupboards with mulberry leafs. | — |
| `pickling` | Pickling Bot | Complete automated pickling system. Manages brine levels, fills jars with fresh vegetables, extracts ready pickled items, and maintains continuous production cycle. | — |
| `silk_production` | Silkworm Cocoon Production | Handles full silkworm cocoon cycle; requires areas: mulberry leaf (take), silkworm eggs (take & put), silkworm cocoons (take & put), herbalist table (silkworm eggs), silkmoth breeding, and silkworm feeding; moves hatched cocoons (max 16/cupboard), stores extras, refills leaves (max 32/cupboard), fills feeders from herbalist tables, places eggs, and pairs moths; safe to run anytime if all areas are defined and reachable. | — |
| `smelter` | Smelter | Smelts ore. | — |
| `smokering` | Smokering Farmer | Smokes Pipestuff from the equipped Bull Pipe and catches every Smokering and Smokeship that appears; refills from a Pipestuff barrel and stops below 50% HP. | — |
| `smoking` | Smoking *(interactive)* | Smokes stuff. | — |
| `steel` | Steel Action | Makes steel. | — |
| `tabaco` | Tabaco Action | Processes tabaco. | — |
| `tarkiln` | Tarkiln Action | Burns stuff in tarkiln. | — |
| `ugardenpot` | Ungarden Pot | Ungardens pots. | — |
| `wrap` | Wrap Action | Wraps items. | — |

## Battle

| Id | Name | Description | Parameters |
|---|---|---|---|
| `attackall` | Attack All | Attacks all enemies. | — |
| `attacknear` | Aggro Nearest | Aggros nearest enemy. | — |
| `attacknearborka` | Aggro Nearest Borka | Aggros nearest Borka. | — |
| `attacknearcurs` | Aggro Near Cursor | Aggros near cursor. | — |
| `combatdist` | Combat Distance Tool | Opens a window to manage combat distance. Shows current distance to target, allows manual distance input, and auto-calculates optimal kiting distance based on enemy type and vehicle. | — |
| `quickbarrage` | Quick Barrage Bot | Opens a window to set a Cornered threshold (50-100). Spams Quick Barrage to build Cornered on the current target, then fires Full Circle once the threshold is reached, resuming the build-up when Cornered drops 3 points below it. Both moves are looked up by name in your combat deck, so slot order does not matter. | — |
| `reagro` | Reagro | Reagros enemies. | — |
| `taming` | Tame an animal | Attacks the nearest animal waiting to be tamed, allows it to escape. Ties the animal on a rope if the corresponding setting is set. Fighting on your own | — |

## Farming

| Id | Name | Description | Parameters |
|---|---|---|---|
| `barley` | Barley Farmer | Automatically harvests and replants barley. | — |
| `beetroot` | Beetroot Farmer | Automatically harvests and replants beetroots. | — |
| `carrot` | Carrot Farmer | Automatically harvests and replants carrots. | — |
| `cheese` | Cheese Production Bot | Process cheese orders. | — |
| `cheese_conveyor` | Cheese Conveyor | Makes cheese continuously from the orders in Nurgling Settings > Bots > Cheese conveyor. A continuous order starts a few trays every run (N trays every H hours), so some cheese finishes every run instead of all at once. It uses the same setup as the Cheese Production Bot: TAKE areas for each curd type, a PUT/TAKE area for empty trays, a PUT area for sliced cheese, and cheese rack areas (outside, inside, mine, cellar) with buffer containers that hold at least as many trays as the racks. Use one cheese bot per set of racks, and the Calculators tool to size them. Global PF is supported. | — |
| `compostbin` | Compost Bin | Pull mulch out of compost bins. | — |
| `cucumber` | Cucumber Farmer | Automatically harvests and replants cucumbers. | — |
| `curdingtub` | Unload curding Tubs | Pull curd out of curding tubs. | — |
| `flax` | Flax Farmer | Automatically harvests and replants flax. | — |
| `gardenpot_farmer` | Garden Pot Farmer | Complete garden pot farming cycle: harvests ready plants, fills with soil/water, and plants new items. | — |
| `garlic` | Garlic Farmer | Automatically harvests and replants garlic. | — |
| `grape` | Grape Farmer | Automatically harvests grapes from trellis. | — |
| `green_kale` | Green Kale Farmer | Automatically harvests and replants green kale. | — |
| `hemp` | Hemp Farmer | Automatically harvests and replants hemp. | — |
| `hops` | Hops Farmer | Automatically harvests hops from trellis. | — |
| `leek` | Leek Farmer | Automatically harvests and replants leek. | — |
| `lettuce` | Lettuce Farmer | Automatically harvests and replants lettuce. | — |
| `millet` | Millet Farmer | Automatically harvests and replants millet. | — |
| `pea` | Pea Farmer | Automatically harvests and replants peas. | — |
| `peppercorn` | Peppercorn Farmer | Automatically harvests peppercorn from trellis. | — |
| `pipeweed` | Pipeweed Farmer | Automatically harvests and replants pipeweed. | — |
| `poppy` | Poppy Farmer | Automatically harvests and replants poppies. | — |
| `pumpkin` | Pumpkin Farmer | Automatically harvests and replants pumpkins. | — |
| `radish` | Radish Farmer | This bot lets you to replant radish field. Choose an area with 'crop(Radish)' specialization for planting. Choose an area with seed barrels for seed collecting with 'seed(Radish)' specialization. You also need a PUT area for 'Radish' to store the harvested radishes. Optionally, choose and area with 'trough' specialization for troughs in order to collect left over swill. | — |
| `red_onion` | Red Onion Farmer | Automatically harvests and replants red onions. | — |
| `stringgrass` | String Grass Farmer | Automatically harvests and replants string grass. | — |
| `turnip` | Turnip Farmer | Automatically harvests and replants turnips. | — |
| `watermelon` | Watermelon Farmer | This bot lets you to replant watermelon field. Choose an area with 'crop(Watermelon)' specialization for planting. Choose an area with seed barrels for seed collecting with 'seed(Watermelon)' specialization. You also need a PUT area for 'Watermelon Slice' - the bot picks up the whole melons, slices them and piles the slices there. Optionally, choose and area with 'trough' specialization for troughs in order to collect left over swill. | — |
| `wheat` | Wheat Farmer | Automatically harvests and replants wheat. | — |
| `white_onion` | White Onion Farmer | This bot lets you to replant white onion field. Choose an area with 'crop(White Onion)' specialization for planting. White onions have no seeds - they are replanted from the onions themselves, so choose an area with 'seed(White Onion)' specialization where the harvest is piled. Optionally, choose and area with 'trough' specialization for troughs in order to collect left over swill. | — |
| `wildflower` | Wild Flower Farmer | Automatically harvests and replants wild flowers. | — |
| `wildgourd` | Wild Gourd Farmer | Automatically harvests wild gourds from trellis. | — |
| `wildkale` | Wild Kale Farmer | Automatically harvests and replants wild kale. | — |
| `wildonion` | Wild Onion Farmer | Automatically harvests and replants wild onions. | — |
| `wildtuber` | Wild Tuber Farmer | Automatically harvests and replants wild tubers. | — |
| `yellow_onion` | Yellow Onion Farmer | Automatically harvests and replants yellow onions. | — |

## Animals

| Id | Name | Description | Parameters |
|---|---|---|---|
| `bee` | Beehive Manager *(interactive)* | Collects honey and wax from beehives. | — |
| `chicken` | Chicken Manager | Manages chicken coops. | — |
| `cows` | Cow Manager | Manages cows. | — |
| `duck` | Duck Manager | Bot is engaged in improving the quality of ducks and cutting their carcasses. Ducks live in ordinary Chicken Coops, so duck zones are told apart from chicken zones by specialisation only: mark the breeding coops 'Duck' and the hatching coops 'Duckling Incubator'. The bot promotes the best drakes and duck hens into the breeding coops, moves ducklings to the incubators, culls the excess, and collects every egg of worse quality than the worst duck hen in the best coop. You also need a zone with water and one with swill, each with a barrel, and optionally a cistern. All other products are distributed by the zone system (section 'Put'). | — |
| `goats` | Goat Manager | Manages goat herds. | — |
| `horses` | Horse Manager | Manages horses. | — |
| `pigs` | Pig Manager | Manages pig herds. | — |
| `rabbit` | Rabbit Manager | Manages rabbit hutches. | — |
| `reindeers` | Teimdeer Manager | Manages teimdeer. | — |
| `sheeps` | Sheep Manager | Manages sheep herds. | — |
| `trufflepig` | Truffle Pig Hunter | Hunt truffles with a leashed pig along a defined path. | `presetName` (string, default ""): Truffle Pig Hunter preset to run. Leave it out to pick it in the bot window. |

## Farming quality

| Id | Name | Description | Parameters |
|---|---|---|---|
| `barleyq` | Barley Farmer Quality | Automatically harvests and replants barley in X*Y cell patches. | — |
| `beetrootq` | Beetroot Farmer Quality | Automatically harvests and replants beetroot in X*Y cell patches. | — |
| `carrotq` | Carrot Farmer Quality | Automatically harvests and replants carrots in X*Y cell patches. | — |
| `flaxq` | Flax Farmer Quality | Automatically harvests and replants flax in X*Y cell patches. | — |
| `garlicq` | Garlic Farmer Quality | Automatically harvests and replants garlic in X*Y cell patches. | — |
| `green_kaleq` | Green Kale Farmer Quality | Automatically harvests and replants green kale in X*Y cell patches. | — |
| `hempq` | Hemp Farmer Quality | Automatically harvests and replants hemp in X*Y cell patches. | — |
| `leekq` | Leek Farmer Quality | Automatically harvests and replants leek in X*Y cell patches. | — |
| `lettuceq` | Lettuce Farmer Quality | Automatically harvests and replants lettuce in X*Y cell patches. | — |
| `milletq` | Millet Farmer Quality | Automatically harvests and replants millet in X*Y cell patches. | — |
| `pipeweedq` | Pipeweed Farmer Quality | Automatically harvests and replants pipeweed in X*Y cell patches. | — |
| `poppyq` | Poppy Farmer Quality | Automatically harvests and replants poppy in X*Y cell patches. | — |
| `pumpkinq` | Pumpkin Farmer Quality | Automatically harvests and replants pumpkins in X*Y cell patches. | — |
| `radishq` | Radish Farmer Quality | This bot automatically harvests and replants radish in X*Y cell patches for quality crop production. Choose an area with 'cropQ(Radish)' specialization for planting. Choose an area with quality seed containers with 'seedQ(Radish)' specialization. You also need a PUT area for 'Radish'. Optionally, choose an area with 'trough' specialization for swill cleanup of low quality seeds. | — |
| `red_onionq` | Red Onion Farmer Quality | Automatically harvests and replants red onions in X*Y cell patches. | — |
| `turnipq` | Turnip Farmer Quality | Automatically harvests and replants turnips in X*Y cell patches. | — |
| `watermelonq` | Watermelon Farmer Quality | This bot automatically harvests and replants watermelon in X*Y cell patches for quality crop production. Choose an area with 'cropQ(Watermelon)' specialization for planting. Choose an area with quality seed containers with 'seedQ(Watermelon)' specialization. You also need a PUT area for 'Watermelon Slice'. Optionally, choose an area with 'trough' specialization for swill cleanup of low quality seeds. | — |
| `wheatq` | Wheat Farmer Quality | Automatically harvests and replants wheat in X*Y cell patches. | — |
| `white_onionq` | White Onion Farmer Quality | This bot automatically harvests and replants white onions in X*Y cell patches for quality crop production. Choose an area with 'cropQ(White Onion)' specialization for planting. Choose an area with quality containers with 'seedQ(White Onion)' specialization. Optionally, choose an area with 'trough' specialization for swill cleanup of low quality onions. | — |
| `yellow_onionq` | Yellow Onion Farmer Quality | Automatically harvests and replants yellow onions in X*Y cell patches. | — |

## Build

| Id | Name | Description | Parameters |
|---|---|---|---|
| `barrel` | Build Barrel *(interactive)* | Builds barrel. | — |
| `blueprint_builder` | Blueprint Builder | Builds what you planned in the Base planner: a folder, a layer, a dragged region or any parts you pick. It finds the build button for each ghost, fetches materials from construction-material zones or input areas, walks anywhere in the village, places and finishes each sign, and removes the ghost from the plan once the building stands. Ghosts it cannot build stay in the plan and are listed with the reason, so running it again resumes the plan. | `nodeId` (string, default ""): Id of the Base planner folder or layer to build. Leave it out to pick the plan in the window. |
| `cellar` | Build Cellar | Builds cellar. | — |
| `cheese_rack` | Build Cheese Rack *(interactive)* | Builds cheese rack. | — |
| `chest` | Build Chest *(interactive)* | Builds chest. | — |
| `crate` | Build Crate *(interactive)* | Builds crates. | — |
| `cupboard` | Build Cupboard *(interactive)* | Builds cupboard. | — |
| `dframe` | Build Drying Frame *(interactive)* | Builds drying frame. | — |
| `htable` | Build Herbalist Tables *(interactive)* | Builds herbalist tables. | — |
| `kiln` | Build Kiln *(interactive)* | Builds kiln. | — |
| `lchest` | Build Large Chest *(interactive)* | Builds large chest. | — |
| `moundbed` | Build Mound Bed *(interactive)* | Builds mound bed. | — |
| `smoke_shed` | Build Smoke Shed *(interactive)* | Builds smoke shed. | — |
| `stone_casket` | Build Stone Casket *(interactive)* | Builds stone casket. | — |
| `tarkilnb` | Build Tar Kiln *(interactive)* | Builds tar kiln. | — |
| `trellis` | Build Trellis *(interactive)* | Builds trellis. | — |
| `ttub` | Build Tub *(interactive)* | Builds tub. | — |

## Tools

| Id | Name | Description | Parameters |
|---|---|---|---|
| `chunknav_navigator` | ChunkNav Navigator | Opens UI to navigate to areas using chunk-based navigation. | — |
| `collect_silkworm_eggs_by_quality` | Collect Best Silkworm Eggs *(interactive)* | Collects highest quality silkworm eggs from containers in selected area. Considers individual egg quality within stacks. | — |
| `navstresstest` | Navigation Stress Test | Continuously tests chunk navigation between random areas. Results saved to JSON file. | — |
| `test1` | Test 1 | Debug test 1. | — |
| `test12` | Test 12 | Debug test 12. | — |
| `test2` | Show Active Tasks | Shows all active tasks in NCore. | — |
| `test4` | Test 4 | Debug test 4. | — |
| `test5` | Test 5 | Auxiliary deferred callback iterator for async reference validation. | — |
| `test7` | Test 7 | Debug test 7. | — |
| `test8` | Test 8 | Debug test 8. | — |
| `test9` | Test 9 | Debug test 9. | — |
