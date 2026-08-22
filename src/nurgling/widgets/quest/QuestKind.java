package nurgling.widgets.quest;

/**
 * Where a tracked quest comes from.
 *
 * Resolved from the quest's resource path plus the credo lists the server sends to
 * {@link haven.SkillWnd} - never from the shape of its condition text. See
 * {@link QuestModel#classify}.
 */
public enum QuestKind
{
    /** A credo quest: {@code paginae/quest/<credo>}, or the pursued credo's own quest id. */
    CREDO,
    /** An NPC quest handed out by a quest giver: {@code paginae/quest/act/*}. */
    NPC,
    /** Everything else under {@code paginae/quest/}: ancestral quest, bury the dead, wind quest. */
    WORLD,
    /** Resource name not resolved yet. Never rendered - the quest reappears once the name is known. */
    UNKNOWN
}
