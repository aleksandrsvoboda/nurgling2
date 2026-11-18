package nurgling.sessions.squad;

import haven.Coord;

/**
 * An order posted to one member's inbox. Commands carry only immutable data: positions as {@link WorldRef}, gobs as id
 * plus position and resource name, so the member can resolve them in its own session.
 */
public abstract class SquadCommand {
    /** Walk with the pathfinder (and chunk nav when far) rather than one direct server click. */
    public final boolean pathfind;
    /** Stop a running bot to obey instead of skipping this member. */
    public final boolean stopBots;

    protected SquadCommand(boolean pathfind, boolean stopBots) {
        this.pathfind = pathfind;
        this.stopBots = stopBots;
    }

    /** Whether this order needs the character, and so clashes with a running bot. */
    public boolean takesCharacter() {
        return true;
    }

    public static final class Move extends SquadCommand {
        public final WorldRef target;

        public Move(WorldRef target, boolean pathfind, boolean stopBots) {
            super(pathfind, stopBots);
            this.target = target;
        }
    }

    public static final class Stop extends SquadCommand {
        public Stop() {
            super(false, false);
        }

        /** Stopping only ever cancels squad orders; it never needs to fight a bot. */
        @Override
        public boolean takesCharacter() {
            return false;
        }
    }

    public static final class Interact extends SquadCommand {
        public final long gobId;
        public final WorldRef gobPos;
        public final String resName;
        public final long seq;
        /** This member's own side of the object to walk to first, or null to go to the nearest spot. */
        public final WorldRef approach;
        /** Where on the map view the player clicked, sent with the right-click: windows open centred there. */
        public final Coord screen;

        public Interact(long gobId, WorldRef gobPos, String resName, long seq, WorldRef approach, Coord screen,
                        boolean pathfind, boolean stopBots) {
            super(pathfind, stopBots);
            this.gobId = gobId;
            this.gobPos = gobPos;
            this.resName = resName;
            this.seq = seq;
            this.approach = approach;
            this.screen = (screen == null) ? null : Coord.of(screen.x, screen.y);
        }
    }

    /** Choose a petal in the menu an {@link Interact} opened; a null option closes it. */
    public static final class PickFlower extends SquadCommand {
        public final String option;
        public final long seq;

        public PickFlower(String option, long seq) {
            super(false, false);
            this.option = option;
            this.seq = seq;
        }

        @Override
        public boolean takesCharacter() {
            return false;
        }
    }

    /**
     * A road picked in the milestone window an {@link Interact} opened. The road is its row name and which of the rows
     * with that name it is. With a trip (Travel) the member takes its turn in it; without one (Follow) it presses the
     * button at once. A null button only ends the wait: the player pressed it there.
     */
    public static final class PickRoad extends SquadCommand {
        public final String road;
        public final int n;
        public final String button;
        public final long seq;
        public final RoadTrip trip;

        public PickRoad(String road, int n, String button, long seq, RoadTrip trip) {
            super(false, false);
            this.road = road;
            this.n = n;
            this.button = button;
            this.seq = seq;
            this.trip = trip;
        }

        @Override
        public boolean takesCharacter() {
            return false;
        }
    }

    public static final class Follow extends SquadCommand {
        public final long leaderGob;
        public final WorldRef leaderPos;

        public Follow(long leaderGob, WorldRef leaderPos, boolean pathfind, boolean stopBots) {
            super(pathfind, stopBots);
            this.leaderGob = leaderGob;
            this.leaderPos = leaderPos;
        }
    }

    /** 0 crawl, 1 walk, 2 run, 3 sprint; clamped to what the character can do. */
    public static final class SetSpeed extends SquadCommand {
        public final int speed;

        public SetSpeed(int speed) {
            super(false, false);
            this.speed = speed;
        }

        @Override
        public boolean takesCharacter() {
            return false;
        }
    }

    /** A plugin's own order, run only by an {@link SquadOrderHandler}; core ignores it. */
    public static final class Custom extends SquadCommand {
        public final String kind;
        public final Object data;
        private final boolean takes;

        public Custom(String kind, Object data, boolean takesCharacter, boolean stopBots) {
            super(false, stopBots);
            this.kind = kind;
            this.data = data;
            this.takes = takesCharacter;
        }

        @Override
        public boolean takesCharacter() {
            return takes;
        }
    }
}
