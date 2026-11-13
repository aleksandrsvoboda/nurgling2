package nurgling.actions.bots.boatfishing;

import haven.Coord;
import haven.Gob;
import haven.Inventory;
import haven.WItem;
import haven.Widget;
import haven.Window;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.conf.NBoatFishingSettings;
import nurgling.tasks.NTask;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

/** Waits for the next thing the bot must act on while fishing. Never relies on the standing-idle pose: a seated
 *  player never takes it. "Stopped" is the player's pose going back to what it was before the cast; when the cast
 *  showed no pose change at all, a long stretch without any event counts as stopped instead. Always ends. */
final class BoatFishingTask extends NTask {
    enum State { WORKING, LEFT_BOAT, ERROR, BITE_WINDOW, PART_MISSING, NO_ROOM, STOPPED, IDLE }

    /** A part has to stay missing this long: the pole re-baits itself between catches. */
    private static final long MISSING_DEBOUNCE_MS = 2_000;
    /** The pose has to stay back at rest this long. */
    private static final long STOPPED_DEBOUNCE_MS = 2_000;
    /** Nothing at all happening for this long, when the pose can't tell: recast. */
    static final long IDLE_MS = 180_000;
    private static final Coord MIN_ROOM = new Coord(2, 2);

    private final NBoatFishingSettings prop;
    private final String restPose;
    private final boolean poseKnown;
    private final Window seenWindow;
    private final NAlias fish;

    State state = State.WORKING;
    String error;
    String missing;
    /** Something arrived in a creel or the inventory while waiting. */
    boolean caught = false;

    private int lastCount = -1;
    private long lastActivity = System.currentTimeMillis();
    private long missingSince = 0;
    private long restSince = 0;
    private String lastPose;

    /** @param restPose the player's pose before the cast; {@code poseKnown} when the cast visibly changed it.
     *  @param seenWindow a bite window already handled, ignored until it closes. */
    BoatFishingTask(NBoatFishingSettings prop, String restPose, boolean poseKnown, Window seenWindow, NAlias fish) {
        this.prop = prop;
        this.restPose = restPose;
        this.poseKnown = poseKnown;
        this.seenWindow = seenWindow;
        this.fish = fish;
    }

    @Override
    public boolean check() {
        long now = System.currentTimeMillis();
        Gob player = NUtils.player();
        if (player == null || Boat.aboard(player) == null) {
            state = State.LEFT_BOAT;
            return true;
        }
        String err = NUtils.getUI().getLastError();
        if (err != null) {
            error = err;
            state = State.ERROR;
            return true;
        }
        String pose = player.pose();
        if (pose != null && !pose.equals(lastPose)) {
            System.out.println("[BoatFisher] pose " + pose);
            lastPose = pose;
        }

        Window w = BiteWindow.find();
        if (w != null && w != seenWindow) {
            state = State.BITE_WINDOW;
            return true;
        }

        int count = Tackle.itemCount();
        if (lastCount < 0) {
            lastCount = count;
        } else if (count != lastCount) {
            if (count > lastCount)
                caught = true;
            lastCount = count;
            lastActivity = now;
        }

        String m = RigRod.missingPart(prop);
        if (m == null) {
            missingSince = 0;
        } else {
            if (missingSince == 0)
                missingSince = now;
            if (now - missingSince >= MISSING_DEBOUNCE_MS) {
                missing = m;
                state = State.PART_MISSING;
                return true;
            }
        }

        if (!Tackle.hasRoom(roomNeeded())) {
            state = State.NO_ROOM;
            return true;
        }

        if (poseKnown) {
            if (pose != null && pose.equals(restPose)) {
                if (restSince == 0)
                    restSince = now;
                if (now - restSince >= STOPPED_DEBOUNCE_MS) {
                    state = State.STOPPED;
                    return true;
                }
            } else {
                restSince = 0;
            }
        } else if (now - lastActivity > IDLE_MS) {
            state = State.IDLE;
            return true;
        }
        return false;
    }

    /** Room for the biggest fish carried, at least 2x2. */
    private Coord roomNeeded() {
        int x = MIN_ROOM.x, y = MIN_ROOM.y;
        for (NInventory inv : Tackle.all()) {
            for (Widget c = inv.child; c != null; c = c.next) {
                if (!(c instanceof WItem))
                    continue;
                WItem wi = (WItem) c;
                String n = ((NGItem) wi.item).name();
                if (n == null || !NParser.checkName(n, fish))
                    continue;
                Coord cells = wi.sz.div(Inventory.sqsz);
                x = Math.max(x, cells.x);
                y = Math.max(y, cells.y);
            }
        }
        return new Coord(x, y);
    }
}
