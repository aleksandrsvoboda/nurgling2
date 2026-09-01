package nurgling.guarding;

import haven.BuddyWnd;
import haven.Gob;
import haven.res.ui.obj.buddy.Buddy;
import nurgling.tools.Finder;

import java.awt.Color;

/**
 * Fires if any nearby player character isn't a known ally. {@code gui.alarmWdg.borkas}
 * (populated by NGob.java for every rendered player-character gob) is every player nearby, not
 * just unknown ones - a green/known-ally walking past isn't a threat. Filters to the same
 * "unknown or hostile" definition the alarm/arrow system uses: no buddy-list entry at all, or a
 * buddy in the white (unclassified) or red (hostile) kin group. borkas is a per-session
 * instance field on NAlarmWdg (not static), so this goes through {@code ctx.gui.alarmWdg}
 * rather than any shared static list.
 */
public class UnknownPlayerTrigger implements GuardTrigger {
    @Override
    public boolean check(GuardContext ctx) {
        if (ctx.gui.alarmWdg == null) {
            return false;
        }
        synchronized (ctx.gui.alarmWdg.borkas) {
            for (Long id : ctx.gui.alarmWdg.borkas) {
                Gob otherPlayer = Finder.findGob(id);
                if (otherPlayer == null) {
                    continue;
                }
                Buddy buddy = otherPlayer.getattr(Buddy.class);
                boolean unknownOrHostile;
                if (buddy == null || buddy.b == null) {
                    unknownOrHostile = true;
                } else {
                    Color groupColor = BuddyWnd.gc[buddy.b.group];
                    unknownOrHostile = groupColor.equals(Color.WHITE) || groupColor.equals(Color.RED);
                }
                if (unknownOrHostile) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return "unknown/hostile player nearby";
    }
}
