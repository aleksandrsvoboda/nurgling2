package nurgling.widgets.quest;

/**
 * One objective line of a quest, as sent by the server in the {@code conds} message.
 *
 * The server only ever sends free-form English text, so the verb and the target names
 * still have to be recovered by matching against that text. Unlike the old parser every
 * slice here is bounds-checked: an unrecognised or malformed line degrades to
 * {@link Verb#OTHER} with null targets instead of throwing on the UI thread.
 */
public class QCond
{
    public enum Verb
    {
        TELL, KILL, PICK, BRING, GREET, LAUGH, RAGE, WAVE, GAIN, CAVE, LIGHT, CREATE, OTHER
    }

    /** Owning quest id. */
    public final int questId;
    /** True once the server reports this objective as satisfied. */
    public final boolean ready;
    /** Server text, with the status suffix appended - what gets rendered. */
    public final String text;
    public final Verb verb;
    /** Quest giver this objective points at, or null. Raw parse; canonicalised by the model. */
    public final String giver;
    /** Lowercased item name for a {@code Bring} objective, or null. */
    public final String bringItem;
    /** Gob-name fragment for a {@code Kill}/{@code Pick} objective, or null. */
    public final String gobTarget;

    public QCond(int questId, boolean ready, String desc, String status)
    {
        String d = (desc == null) ? "" : desc;
        this.questId = questId;
        this.ready = ready;
        this.verb = verb(d);
        this.giver = wantsGiver(verb) ? giver(d) : null;
        this.bringItem = (verb == Verb.BRING) ? bringItem(d) : null;
        this.gobTarget = (verb == Verb.KILL) ? huntTarget(d)
                       : (verb == Verb.PICK) ? pickTarget(d) : null;
        this.text = (status == null || status.isEmpty()) ? d : (d + " " + status);
    }

    /** Objectives that name a quest giver, and so contribute a marker on the map. */
    public static boolean wantsGiver(Verb v)
    {
        return v == Verb.TELL || v == Verb.BRING || v == Verb.GREET
            || v == Verb.WAVE || v == Verb.LAUGH || v == Verb.RAGE;
    }

    /**
     * Overlay tag drawn over the giver's map marker for this objective, or null.
     * Mirrors the tags {@link nurgling.overlays.NQuestGiver} knows how to draw.
     */
    public String markerTag()
    {
        switch(verb) {
            case BRING: return "bring";
            case GREET: return "greet";
            case RAGE:  return "rage";
            case WAVE:  return "wave";
            case LAUGH: return "laugh";
            default:    return null;
        }
    }

    /* ------------------------------------------------------------------ parsing */

    private static Verb verb(String t)
    {
        if(t.contains("Bring"))
            return Verb.BRING;
        if(t.contains("Pick") || t.contains("Catch"))
            return Verb.PICK;
        if(t.contains("Kill") || t.contains("Raid") || t.contains("Defeat"))
            return Verb.KILL;
        if(t.contains("Greet") || isVisit(t))
            return Verb.GREET;
        if(t.contains("wave"))
            return Verb.WAVE;
        if(t.contains("laugh"))
            return Verb.LAUGH;
        if(t.contains("rage"))
            return Verb.RAGE;
        if(t.contains("Gain"))
            return Verb.GAIN;
        if(t.contains("Create"))
            return Verb.CREATE;
        if(t.contains("Tell"))
            return Verb.TELL;
        if(t.contains("cave"))
            return Verb.CAVE;
        if(t.contains("Light"))
            return Verb.LIGHT;
        return Verb.OTHER;
    }

    private static boolean isVisit(String t)
    {
        return t.contains("Visit") && !t.contains("cave");
    }

    private static String giver(String info)
    {
        if(info.contains("Tell")) {
            // "Tell <name> ..." - the old parser took the first word after "Tell ", and the
            // marker matching in QuestModel depends on that, so keep it and canonicalise later.
            if(info.length() <= 5)
                return null;
            int end = info.indexOf(' ', 6);
            if(end < 0)
                end = info.length();
            return (end > 5) ? trimToNull(info.substring(5, end)) : null;
        }
        if(info.contains("Greet") || isVisit(info))
            return (info.length() > 6) ? trimToNull(info.substring(6)) : null;
        int i = info.indexOf(" to ");
        if(i >= 0)
            return trimToNull(info.substring(i + 4));
        i = info.indexOf(" at ");
        if(i >= 0)
            return trimToNull(info.substring(i + 4));
        return null;
    }

    private static String bringItem(String info)
    {
        int to = info.indexOf("to ");
        if(to < 1)
            return null;
        int start;
        int a = info.indexOf(" a "), an = info.indexOf(" an ");
        if(a >= 0)
            start = a + 3;
        else if(an >= 0)
            start = an + 4;
        else
            start = 6;
        int end = to - 1;
        if(start >= end || end > info.length())
            return null;
        // Lowercased on purpose: NGItem matches it against item.name().toLowerCase().
        return trimToNull(info.substring(start, end).toLowerCase());
    }

    /** Text after the leading article, lowercased - the common prefix of both target parsers. */
    private static String tail(String info)
    {
        info = info.toLowerCase();
        int i = info.indexOf(" a ");
        if(i >= 0)
            return info.substring(i + 3);
        i = info.indexOf(" an ");
        if(i >= 0)
            return info.substring(i + 4);
        i = info.indexOf(' ');
        return (i >= 0) ? info.substring(i + 1) : info;
    }

    private static String pickTarget(String info)
    {
        String nm = tail(info);
        if(nm.isEmpty())
            return null;
        if(nm.contains("blueberr"))            nm = "blueberr";
        else if(nm.contains("lingon"))         nm = "lingon";
        else if(nm.contains("woodgrouse hen")) nm = "woodgrouse-f";
        else if(nm.contains("morel"))          nm = "lorchel";
        else if(nm.contains("yellowf"))        nm = "yellowf";
        else if(nm.contains("hen"))            nm = "chicken/chicken";
        else if(nm.contains("cock"))           nm = "chicken/roast";
        else if(nm.contains("chantrell"))      nm = "herbs/chantrell";
        else if(nm.contains("rat"))            nm = "rat/rat";
        return trimToNull(nm.replaceAll("\\s+", "").replaceAll("'+", ""));
    }

    private static String huntTarget(String info)
    {
        String nm = tail(info);
        if(nm.isEmpty())
            return null;
        if(nm.contains("mouflon"))
            nm = "sheep";
        else if(nm.contains("auroch"))
            nm = "cattle";
        else if(nm.contains("horse"))
            nm = "horse/horse";
        else if(info.toLowerCase().contains("raid a"))
            nm = nm.contains("bird") ? "birdsnest" : "anthill";
        else
            nm = "kritter/" + nm;
        return trimToNull(nm.replaceAll("\\s+", "").replaceAll("'+", ""));
    }

    /**
     * Empty target names are dropped rather than kept: {@code isHuntingTarget} tests with
     * {@code contains()}, so an empty string would match every gob in the world.
     */
    private static String trimToNull(String s)
    {
        if(s == null)
            return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    @Override
    public String toString()
    {
        return "QCond[" + verb + " " + (ready ? "done" : "pend") + " " + text + "]";
    }
}
