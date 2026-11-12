package nurgling.sessions.squad;

import java.util.List;

/**
 * What a session tells the rest of the client about its character. Each session builds this in its own tick and
 * publishes it on its {@code SessionContext}; the viewpoint only ever reads it, so no session reaches into another
 * session's map, gobs or widgets.
 */
public final class MemberReport {
    public enum Status { IDLE, WALKING, PATHING, CHUNKNAV, INTERACTING, FOLLOWING, UNREACHABLE, SKIPPED, BOT, COMBAT }

    public final String genus;
    public final String name;
    /** The character's gob id in its own session. */
    public final long plgob;
    /** Where the character stands, or null before its map has loaded. */
    public final WorldRef pos;
    /** Hard HP as a fraction, or -1 when unknown. */
    public final double hp;
    public final Status status;
    /** Running bot name for {@link Status#BOT} and {@link Status#SKIPPED}; reason for {@link Status#UNREACHABLE}. */
    public final String detail;
    /** Options of a flower menu waiting for a squad pick, or null. */
    public final List<String> flowerOptions;
    /** Interact order those options belong to. */
    public final long flowerSeq;
    public final long time;

    public MemberReport(String genus, String name, long plgob, WorldRef pos, double hp, Status status, String detail,
                        List<String> flowerOptions, long flowerSeq, long time) {
        this.genus = genus;
        this.name = name;
        this.plgob = plgob;
        this.pos = pos;
        this.hp = hp;
        this.status = status;
        this.detail = detail;
        this.flowerOptions = flowerOptions;
        this.flowerSeq = flowerSeq;
        this.time = time;
    }

    /** Character key for control groups: survives relogs, unlike session ids. */
    public String key() {
        return genus + "|" + name;
    }

    public boolean fresh() {
        return System.currentTimeMillis() - time < 5000;
    }
}
