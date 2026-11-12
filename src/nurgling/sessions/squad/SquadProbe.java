package nurgling.sessions.squad;

import haven.*;
import nurgling.NGameUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

/**
 * {@code :squad probe} - checks the assumptions squad control rests on, from the session you are looking at:
 * whether another session's character has the same gob id here (are gob ids global?), and whether its position
 * resolves the same through the map grid and through the map segment. Prints one line per session to stdout and a
 * summary in the game log.
 */
public final class SquadProbe {
    private SquadProbe() {}

    public static void run(NGameUI vp, String[] args) {
        if (args.length < 2 || !"probe".equals(args[1])) {
            vp.msg("Usage: squad probe");
            return;
        }
        MCache map = vp.map.glob.map;
        MiniMap.Location sl = SquadExecutor.sessloc(vp);
        int sameId = 0, others = 0;
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            MemberReport r = ctx.squadReport;
            if (r == null) {
                System.out.println("[squad probe] " + ctx.getDisplayName() + ": no report yet");
                continue;
            }
            String line = "[squad probe] " + r.name + " genus=" + r.genus + " plgob=" + r.plgob + " pos=" + r.pos;
            if (r.pos != null) {
                Coord2d byGrid = (r.pos.gridId >= 0 && map.findGrid(r.pos.gridId) != null)
                        ? new WorldRef(r.pos.gridId, r.pos.off, 0, null).localIn(map, null) : null;
                Coord2d bySeg = (r.pos.segPos != null && sl != null)
                        ? new WorldRef(-1, null, r.pos.segId, r.pos.segPos).localIn(map, sl) : null;
                line += " here: grid=" + byGrid + " seg=" + bySeg;
                if (byGrid != null && bySeg != null)
                    line += String.format(" grid-seg error=%.2f", byGrid.dist(bySeg));
                Gob g = vp.map.glob.oc.getgob(r.plgob);
                Coord2d at = (byGrid != null) ? byGrid : bySeg;
                if (vp.map.plgob != r.plgob) {
                    others++;
                    if (g != null) {
                        line += " sameIdGob=" + ((g.ngob == null) ? "?" : g.ngob.name)
                                + ((at == null) ? "" : String.format(" dist=%.2f", g.rc.dist(at)));
                        if (g.ngob != null && SquadExecutor.PLAYER_RES.equals(g.ngob.name)
                            && at != null && g.rc.dist(at) < 2 * MCache.tilesz.x)
                            sameId++;
                    } else {
                        line += " sameIdGob=none";
                    }
                }
            }
            System.out.println(line);
        }
        vp.msg(String.format("squad probe: %d of %d other characters found by the same gob id (details in the log)",
                             sameId, others));
    }
}
