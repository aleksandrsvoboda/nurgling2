package nurgling.sessions.squad;

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

        public Interact(long gobId, WorldRef gobPos, String resName, long seq, boolean pathfind, boolean stopBots) {
            super(pathfind, stopBots);
            this.gobId = gobId;
            this.gobPos = gobPos;
            this.resName = resName;
            this.seq = seq;
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
}
