package nurgling.actions.bots.largefields;

import haven.Area;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.OCache;
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
     * Until a block's grids are loaded and the number of objects inside it has stopped changing
     * for {@link #SETTLE_MS}: objects keep arriving for a moment after a grid loads, and a scan
     * taken too early reads ripe plants or planted tiles as missing.
     */
    static final class BlockLoaded extends NTask {
        static final long SETTLE_MS = 1500;
        static final long LIMIT_MS = 20000;
        private final FieldMap map;
        private final Area block;
        private final long start = System.currentTimeMillis();
        private long stableSince = 0;
        private int lastCount = -1;
        boolean timedOut = false;

        BlockLoaded(FieldMap map, Area block) {
            this.map = map;
            this.block = block;
        }

        @Override
        public boolean check() {
            long now = System.currentTimeMillis();
            if (now - start > LIMIT_MS) {
                timedOut = true;
                return true;
            }
            MCache mc = NUtils.getGameUI().map.glob.map;
            if (!map.loaded(block, mc))
                return false;
            Area s = map.toSessionInclusive(block, mc);
            if (s == null)
                return false;
            int count = countGobs(s);
            if (count != lastCount) {
                lastCount = count;
                stableSince = now;
                return false;
            }
            return now - stableSince >= SETTLE_MS;
        }
    }

    /** Objects inside an inclusive session-tile area. */
    static int countGobs(Area s) {
        Coord2d a = s.ul.mul(MCache.tilesz);
        Coord2d b = s.br.add(1, 1).mul(MCache.tilesz);
        int n = 0;
        synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
            for (Gob gob : NUtils.getGameUI().ui.sess.glob.oc) {
                if (gob instanceof OCache.Virtual)
                    continue;
                if (gob.rc.x >= a.x && gob.rc.y >= a.y && gob.rc.x < b.x && gob.rc.y < b.y)
                    n++;
            }
        }
        return n;
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
