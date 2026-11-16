package nurgling.actions.bots.largefields;

import haven.Area;
import nurgling.NUtils;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

/**
 * Waits used by the large-field farmers. Each one ends by itself after a time limit and says
 * whether it timed out, so a block that never streams in or a plant that won't go away fails
 * that one step instead of hanging the bot (NTask has no working timeout of its own).
 */
final class FieldTasks {
    private FieldTasks() {}

    /**
     * Until a block's grids are loaded. Arriving at the block normally means they already are, so
     * this rarely waits at all; it guards against scanning a block whose grid hasn't come in yet.
     */
    static final class BlockLoaded extends NTask {
        static final long LIMIT_MS = 10000;
        private final FieldMap map;
        private final Area block;
        private final long start = System.currentTimeMillis();
        boolean timedOut = false;

        BlockLoaded(FieldMap map, Area block) {
            this.map = map;
            this.block = block;
        }

        @Override
        public boolean check() {
            if (map.loaded(block, NUtils.getGameUI().map.glob.map))
                return true;
            if (System.currentTimeMillis() - start > LIMIT_MS) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }

    /** Until the object is gone, or the time limit. */
    static final class Gone extends NTask {
        private final long id;
        private final long until;
        boolean timedOut = false;

        Gone(long id, long limitMs) {
            this.id = id;
            this.until = System.currentTimeMillis() + limitMs;
        }

        @Override
        public boolean check() {
            if (Finder.findGob(id) == null)
                return true;
            if (System.currentTimeMillis() > until) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }

    /**
     * Until every soil tile of an inclusive area holds something, or the time limit. Same count
     * as nurgling.tasks.WaitGobsInField, with a limit so an unplantable tile can't hang the bot.
     */
    static final class Planted extends NTask {
        private final Area area;
        private final int count;
        private final String soil;
        private final long until;
        boolean timedOut = false;

        Planted(Area area, int count, String soil, long limitMs) {
            this.area = area;
            this.count = count;
            this.soil = soil;
            this.until = System.currentTimeMillis() + limitMs;
        }

        @Override
        public boolean check() {
            Area.Tile[][] tiles = Area.getTiles(area, new NAlias("gfx/terobjs/moundbed"));
            int num = 0;
            for (int i = 0; i <= area.br.x - area.ul.x; i++)
                for (int j = 0; j <= area.br.y - area.ul.y; j++)
                    if (!tiles[i][j].isFree && NParser.checkName(tiles[i][j].name, soil))
                        num++;
            if (num >= count)
                return true;
            if (System.currentTimeMillis() > until) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }

    /** A plain pause that doesn't block the UI thread. */
    static final class Pause extends NTask {
        private final long until;

        Pause(long ms) {
            this.until = System.currentTimeMillis() + ms;
        }

        @Override
        public boolean check() {
            return System.currentTimeMillis() >= until;
        }
    }
}
