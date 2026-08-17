package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.Window;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.CloseTargetWindow;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.actions.WorkBellows;
import nurgling.tasks.WaitProgress;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

/**
 * Walks to the back of the nearest stack furnace and works its bellows, reporting exactly what happened.
 *
 * <p>Exists because two things about the bellows can only be settled in game: where to stand, and where to
 * aim the click. The furnace is nearly two tiles along its axis, so stopping at the wrong end leaves the
 * bellows out of reach; and the click offset cannot be read off the raw capture data, because {@code mc} is
 * a terrain point and a click on the tall stack lands much further from its target than one on the low
 * bellows.
 *
 * <p><b>It sweeps mesh ids.</b> A real click names the mesh the ray struck; every bot helper sends -1,
 * meaning "unspecified". Position cannot be what the server keys on: {@code mc} is a terrain point measured
 * <i>through</i> the model, so clicking something two tiles up lands the point well beyond the footprint
 * and in a direction that changes with the camera - yet such clicks still resolve to the furnace. That
 * leaves {@code gobid} + {@code meshid}, so this tries each id the resource defines and reports which one
 * starts the bellows.
 *
 * <p>The progress bar starting is the success signal - one click sets the character working and a bar runs
 * for the whole pump. An opened inventory means that id resolved to the body; it gets closed and the sweep
 * continues. The bar appears on a cold furnace too, so no lit furnace is needed to find the answer; only
 * the boost-bit check afterwards wants fire.
 *
 * <p>Takes no areas and no configuration: stand near a stack furnace and run it.
 */
public class TestBellowsWalk implements Action {

    private static final NAlias STACK_FURNACE = new NAlias("gfx/terobjs/primsmelter");
    private static final String FURNACE_CAP = "Stack furnace";

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob furnace = Finder.findGob(STACK_FURNACE);
        if (furnace == null)
            return Results.ERROR("No stack furnace nearby");

        String hash = furnace.ngob.hash;
        long marker = furnace.ngob.getModelAttribute();
        boolean burning = (marker & WorkBellows.BURNING) != 0;

        /* Where the player is standing right now, in the furnace's own frame. Stand where you actually
         * want the bot to stand and this line reports the offset to hardcode. */
        Gob before = NUtils.player();
        if (before != null) {
            Coord2d standing = before.rc.sub(furnace.rc).rot(-furnace.a);
            System.out.println("[bellowswalk] player localoff BEFORE walking = "
                    + String.format("(%.2f, %.2f)", standing.x, standing.y));
        }
        System.out.println("[bellowswalk] furnace id=" + furnace.id + " rc=" + furnace.rc
                + " a=" + String.format("%.3f", furnace.a)
                + " marker=" + marker + " " + bits(marker));
        System.out.println("[bellowswalk]   burning=" + burning
                + " boosted=" + ((marker & WorkBellows.BOOST) != 0));
        if (!burning)
            System.out.println("[bellowswalk]   note: not burning, so no boost bit is expected."
                    + " The progress bar still identifies the click point.");

        Coord2d approach = furnace.rc.add(WorkBellows.APPROACH_OFFSET.rot(furnace.a));
        System.out.println("[bellowswalk]   approach point=" + approach
                + " (localoff " + fmt(WorkBellows.APPROACH_OFFSET) + ")");

        boolean viaApproach = new PathFinder(approach).run(gui).IsSuccess();
        if (!viaApproach) {
            System.out.println("[bellowswalk]   approach point unreachable, falling back to gob target");
            if (!new PathFinder(furnace).run(gui).IsSuccess())
                return Results.ERROR("Can't reach the furnace at all");
        }

        Gob player = NUtils.player();
        if (player != null) {
            Coord2d landed = player.rc.sub(furnace.rc).rot(-furnace.a);
            System.out.println("[bellowswalk]   arrived at localoff " + fmt(landed)
                    + " via " + (viaApproach ? "approach point" : "gob fallback"));
        }

        /* Sweep the mesh ids the resource actually defines. A real click names the mesh the ray struck and
         * the bot has always sent -1 ("unspecified"), so if the server tells the bellows from the body at
         * all, this is where the difference lives. Ordered by how likely each is: 16 is what the successful
         * hand-clicks reported, -1 is what an earlier session reported. */
        int[] meshids = {16, -1, 6, 0, 2, 4, 1};
        for (int meshid : meshids) {
            if (tryClick(gui, hash, WorkBellows.BELLOWS_OFFSET, meshid))
                return Results.SUCCESS();
        }

        System.out.println("[bellowswalk] no mesh id started the bellows. If some of them opened the"
                + " inventory then the click is reaching the furnace and only the part is wrong; if none"
                + " did anything, the player is out of range - check the arrived-at localoff above.");
        gui.msg("Bellows test: no mesh id worked");
        return Results.SUCCESS();
    }

    /**
     * Clicks with one candidate mesh id and classifies the outcome.
     *
     * @return true when the bellows actually started working, i.e. this mesh id is the bellows
     */
    private boolean tryClick(NGameUI gui, String hash, Coord2d offset, int meshid) throws InterruptedException {
        Gob gob = Finder.findGob(hash);
        if (gob == null)
            return false;

        System.out.println("[bellowswalk] clicking meshid=" + meshid + " at offset " + fmt(offset));
        NUtils.rclickGobAt(gob, offset, meshid);

        /* The progress bar is the real tell, and unlike the boost bit it works on a cold furnace too:
         * one click sets the character working the bellows and a bar runs for the whole pump. */
        WaitProgress started = new WaitProgress(WaitProgress.Phase.START, 5000);
        NUtils.getUI().core.addTask(started);

        Window window = gui.getWindow(FURNACE_CAP);
        if (window != null) {
            System.out.println("[bellowswalk]   -> inventory opened: this mesh id resolves to the FURNACE BODY");
            new CloseTargetWindow(window).run(gui);
            return false;
        }
        if (started.isTimedOut()) {
            System.out.println("[bellowswalk]   -> nothing happened: no progress bar, no inventory");
            return false;
        }

        System.out.println("[bellowswalk]   -> BELLOWS WORKING (progress bar running), waiting for it to finish");
        NUtils.getUI().core.addTask(new WaitProgress(WaitProgress.Phase.FINISH, 60000));

        Gob after = Finder.findGob(hash);
        long marker = (after == null || after.ngob == null) ? -1 : after.ngob.getModelAttribute();
        System.out.println("[bellowswalk]   -> done, marker=" + marker + " " + bits(marker)
                + " boosted=" + (marker != -1 && (marker & WorkBellows.BOOST) != 0));
        System.out.println("[bellowswalk]   -> ANSWER: meshid=" + meshid + " at offset " + fmt(offset));
        gui.msg("Bellows worked: meshid=" + meshid);
        return true;
    }

    private static String fmt(Coord2d c) {
        return String.format("(%.2f, %.2f)", c.x, c.y);
    }

    /** Renders a model attribute as its set bit indices, which are the mesh ids the sdt selects. */
    private static String bits(long v) {
        if (v < 0)
            return "bits[<none>]";
        StringBuilder b = new StringBuilder("bits[");
        boolean first = true;
        for (int i = 0; i < 32; i++) {
            if (((v >> i) & 1L) != 0) {
                if (!first)
                    b.append(',');
                b.append(i);
                first = false;
            }
        }
        return b.append(']').toString();
    }
}
