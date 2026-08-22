package nurgling.actions;

import haven.Coord2d;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.WaitBellows;
import nurgling.tasks.WaitProgress;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;

/**
 * Works the bellows on stack furnaces so they smelt at the boosted rate.
 *
 * <p>The wiki puts the boost at +51% of the smelting process (about 27 RL minutes) at significantly
 * increased speed, for one pump - so a single pump shortly after lighting covers roughly the first half
 * of a burn.
 *
 * <p><b>How the bellows is addressed.</b> It is part of the furnace gob, not a separate one, and is picked
 * out by the {@code meshid} field of the click message - the same mechanism {@link NUtils#openDoor} uses to
 * enter a house, whose door is likewise part of the building gob. {@code NUtils.rclickGob} sends
 * {@code meshid = -1}, "unspecified", which is why an ordinary bot right-click only ever opens the
 * inventory.
 *
 * <p><b>Failure is not fatal.</b> No water, no stamina, out of range, or a click the server ignores all
 * leave the furnace smelting, just unboosted. This logs and moves on rather than stopping the run.
 */
public class WorkBellows implements Action
{
    /** One of the two burning states (mesh id 1). Mutually exclusive with {@link #SMELTING}. */
    public static final int LIT = 2;
    /** The other burning state (mesh id 2). Mutually exclusive with {@link #LIT}. */
    public static final int SMELTING = 4;

    /**
     * A furnace is burning when <i>either</i> state bit is set, so anything asking "is there a fire to
     * boost" must test the mask.
     *
     * <p>Both meshes belong to the same {@code ref=4} group and only one is drawn at a time - live markers
     * show 65633 cold, 65635 with bit 1, 65637 with bit 2, and 65621 with bit 2 plus the boost. Testing
     * either bit on its own leaves a real burning state unrecognised, which is how a burning furnace ended
     * up never being pumped.
     */
    public static final int BURNING = LIT | SMELTING;
    /** Model attribute bit set while the bellows boost is active (mesh id 4). */
    public static final int BOOST = 16;
    /** Model attribute bit set while the bellows is idle. Complementary to {@link #BOOST}. */
    public static final int IDLE = 32;

    /**
     * The mesh that resolves to the bellows. This, not the click position, is what tells the bellows from
     * the furnace body.
     *
     * <p>Same convention house doors use: {@link NUtils#openDoor} enters a building with {@code meshid 16}
     * (a greathall's three doors are 16, 17 and 18) while aiming {@code mc} at {@code arch.rc}, the middle
     * of the house. Position is not read - which it could not be, since {@code mc} is a terrain point
     * measured through the model and so moves with the camera.
     */
    public static final int BELLOWS_MESHID = 16;

    /**
     * Where to aim the click. Kept at the bellows end because that is the configuration confirmed in game,
     * though the door precedent above indicates the server ignores it.
     */
    public static final Coord2d BELLOWS_OFFSET = new Coord2d(-8.7, -2.0);

    /**
     * Where to stand: off the back end, along the furnace's own -x axis. The hitbox ({@code NHitBox}) runs
     * to x = -8, so this clears it by about half a tile.
     */
    public static final Coord2d APPROACH_OFFSET = new Coord2d(-13, 0);

    private static final NAlias STACK_FURNACE = new NAlias("gfx/terobjs/primsmelter");

    /** Pump below this, and top up first. Matches the threshold HarvestCrop and HarvestTrellis use. */
    private static final double STAMINA_FLOOR = 0.5;
    private static final double STAMINA_TARGET = 0.9;

    private final ArrayList<String> gobs;

    public WorkBellows(ArrayList<String> gobs)
    {
        this.gobs = gobs;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        for (String hash : gobs)
        {
            Gob gob = Finder.findGob(hash);
            if (gob == null || gob.ngob == null || gob.ngob.name == null)
                continue;
            if (!NParser.checkName(gob.ngob.name, STACK_FURNACE))
                continue;

            long attr = gob.ngob.getModelAttribute();
            if ((attr & BURNING) == 0)
                continue;
            /* Pump only a furnace whose bellows is in its idle-but-running state. IDLE clears both when
             * the boost is already active and when the fuel is spent (the 65540 metal-still-inside state,
             * which keeps the SMELTING mesh bit but drops every bellows bit), so testing IDLE rather than
             * "BOOST not set" stops the bot pumping a burnt-out furnace. */
            if ((attr & IDLE) == 0)
                continue;

            /* Working the bellows costs a large amount of stamina, so this is checked per furnace
             * rather than once for the batch. */
            if (isBelowFloor())
                new Drink(STAMINA_TARGET, true).run(gui);
            if (isBelowFloor())
            {
                System.out.println("[bellows] skipping " + hash + ": stamina " + NUtils.getStamina());
                continue;
            }

            /* Walk to a spot off the back end rather than letting the pathfinder stop wherever is cheapest
             * around the hitbox. The furnace is nearly two tiles along its axis (hitbox x spans -8..+11),
             * so the default choice regularly parks the player at the front with the bellows out of reach.
             * Falling back to the plain gob target keeps this no worse than before if the spot is blocked. */
            Coord2d bellows = gob.rc.add(BELLOWS_OFFSET.rot(gob.a));
            Coord2d approach = gob.rc.add(APPROACH_OFFSET.rot(gob.a));
            if (!new PathFinder(approach).run(gui).IsSuccess())
                new PathFinder(gob).run(gui);

            /* One click starts the character working the bellows, and a progress bar runs for the whole
             * pump - so the click is only the beginning. Leaving before it finishes would walk away
             * mid-action and cancel it, and would also make the boost check read a marker that has not been
             * updated yet. If no bar appears at all, the click missed the bellows. */
            NUtils.rclickGobAt(gob, BELLOWS_OFFSET, BELLOWS_MESHID);
            /* Generous, because the server may walk the character the last stretch before the action
             * starts - the same way clicking a door walks you into the house. */
            WaitProgress started = new WaitProgress(WaitProgress.Phase.START, 10000);
            NUtils.getUI().core.addTask(started);
            if (started.isTimedOut())
            {
                System.out.println("[bellows] no progress bar on " + hash + ", the click missed the bellows");
                continue;
            }
            NUtils.getUI().core.addTask(new WaitProgress(WaitProgress.Phase.FINISH, 60000));
            NUtils.getUI().core.addTask(new WaitBellows(hash, BOOST, 2000));

            Gob after = Finder.findGob(hash);
            if (after == null || after.ngob == null || (after.ngob.getModelAttribute() & BOOST) == 0)
            {
                Gob player = NUtils.player();
                System.out.println("[bellows] pump did not register on " + hash
                        + ", marker=" + ((after == null || after.ngob == null) ? "gone" : after.ngob.getModelAttribute())
                        + ", dist to bellows=" + ((player == null) ? "?" : String.format("%.2f", player.rc.dist(bellows))));
            }
        }
        return Results.SUCCESS();
    }

    private static boolean isBelowFloor()
    {
        double stamina = NUtils.getStamina();
        return stamina >= 0 && stamina < STAMINA_FLOOR;
    }
}
