package nurgling.actions;

import haven.*;
import static haven.OCache.posres;
import nurgling.*;
import static nurgling.actions.PathFinder.pfmdelta;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.function.BooleanSupplier;

public class GoTo implements Action
{
    final Coord2d targetCoord;
    // Optional: polled while waiting to arrive; true abandons the step (run() returns FAIL, aborted() true) - see PathFinder.avoidZones.
    final BooleanSupplier abort;
    private volatile boolean aborted = false;
    // Opt-in (navigation walks, see PathFinder.continuous): > 0 lets the walk carry on past this point instead of stopping
    // on it. Once the player is this close, run() returns SUCCESS while still walking if `canPass` (checked on the bot
    // thread; null = always) says the next click can follow; otherwise it waits for the stop as usual.
    private double passAhead = 0;
    private BooleanSupplier canPass = null;

    public GoTo(Coord2d targetCoord)
    {
        this(targetCoord, null);
    }

    public GoTo(Coord2d targetCoord, BooleanSupplier abort)
    {
        this.targetCoord = targetCoord;
        this.abort = abort;
    }

    public boolean aborted()
    {
        return aborted;
    }

    /** Lets the walk go on past the target - see passAhead. */
    public GoTo passing(double ahead, BooleanSupplier canPass)
    {
        this.passAhead = ahead;
        this.canPass = canPass;
        return this;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        if(!NParser.checkName(NUtils.getCursorName(), "arw")) {
            NUtils.getGameUI().map.wdgmsg("click", Coord.z, NUtils.player().rc.floor(posres),3, 0);
            NUtils.getUI().core.addTask(new GetCurs("arw"));
        }

        gui.map.wdgmsg("click", Coord.z, targetCoord.floor(posres), 1, 0);
        Following fl = NUtils.player().getattr(Following.class);
        if( fl!= null )
        {
            Gob gob = null;
            if((gob = Finder.findGob(fl.tgt))!=null) {
                if (NParser.isIt(gob, new NAlias("horse"))) {
                    NUtils.getUI().core.addTask(abortable(new IsPoseMov(targetCoord, gob, new NAlias("gfx/kritter/horse/pace", "gfx/kritter/horse/walking", "gfx/kritter/horse/trot", "gfx/kritter/horse/gallop"))));
                    NUtils.getUI().core.addTask(abortable(new IsNotPose(gob, new NAlias("gfx/kritter/horse/pace", "gfx/kritter/horse/walking", "gfx/kritter/horse/trot", "gfx/kritter/horse/gallop"))));
                }
                else if (NParser.isIt(gob, new NAlias("dugout"))) {
                    NUtils.getUI().core.addTask(abortable(new IsPoseMov(targetCoord, NUtils.player(), new NAlias("gfx/borka/dugoutrowan"))));
                    NUtils.getUI().core.addTask(abortable(new IsNotPose(NUtils.player(), new NAlias("gfx/borka/dugoutrowan"))));
                }
                else if (NParser.isIt(gob, new NAlias("coracle"))) {
                    NUtils.getUI().core.addTask(abortable(new IsPoseMov(targetCoord, NUtils.player(), new NAlias("gfx/borka/coraclerowan"))));
                    NUtils.getUI().core.addTask(abortable(new IsNotPose(NUtils.player(), new NAlias("gfx/borka/coraclerowan"))));
                }
                else if (NParser.isIt(gob, new NAlias("skis-wilderness"))) {
                    NUtils.getUI().core.addTask(abortable(new IsPoseMov(targetCoord, NUtils.player(), new NAlias("gfx/borka/skian-walk", "gfx/borka/skian-run"))));
                    NUtils.getUI().core.addTask(abortable(new IsNotPose(NUtils.player(), new NAlias("gfx/borka/skian-walk", "gfx/borka/skian-run"))));
                }
                else if (NParser.isIt(gob, new NAlias("rowboat"))) {
                    NUtils.getUI().core.addTask(abortable(new IsPoseMov(targetCoord, NUtils.player(), new NAlias("gfx/borka/rowing"))));
                    NUtils.getUI().core.addTask(abortable(new IsNotPose(NUtils.player(), new NAlias("gfx/borka/rowing"))));
                }
                else if (NParser.isIt(gob, new NAlias("snekkja"))) {
                    NUtils.getUI().core.addTask(abortable(new IsMovingBySpeed(targetCoord, gob)));
                    NUtils.getUI().core.addTask(abortable(new MovingCompletedBySpeed(gob)));
                }
            }
        }
        else if (passAhead > 0) {
            NUtils.getUI().core.addTask(abortable(new IsMoving(targetCoord)));
            NUtils.getUI().core.addTask(abortable(new NearOrStopped(targetCoord, passAhead)));
            if (!aborted && walking(gui) && gui.map.player().rc.dist(targetCoord) <= passAhead
                    && (canPass == null || canPass.getAsBoolean())) {
                return Results.SUCCESS();
            }
            NUtils.getUI().core.addTask(abortable(new MovingCompleted(targetCoord)));
        }
        else {
            NUtils.getUI().core.addTask(abortable(new IsMoving(targetCoord)));
            NUtils.getUI().core.addTask(abortable(new MovingCompleted(targetCoord)));
        }
        if(aborted)
            return Results.FAIL();
        if(NUtils.getGameUI().map.player().rc.dist(targetCoord) > 2*pfmdelta)
            return Results.FAIL();
        return Results.SUCCESS();
    }

    private static boolean walking(NGameUI gui)
    {
        Gob player = gui.map.player();
        Drawable drawable = (player != null) ? player.getattr(Drawable.class) : null;
        String pose;
        return drawable instanceof Composite && (pose = ((Composite) drawable).current_pose) != null
                && NParser.checkName(pose, "borka/walking", "borka/running", "borka/wading");
    }

    /** task as-is, or - with an abort check set - also finishing (and marking this step aborted) once the check fires; sticky, so the paired wait after it ends at once too. */
    private NTask abortable(NTask task)
    {
        if(abort == null)
            return task;
        return new NTask()
        {
            @Override
            public boolean check()
            {
                if(aborted || abort.getAsBoolean())
                {
                    aborted = true;
                    return true;
                }
                return task.check();
            }
        };
    }
}
