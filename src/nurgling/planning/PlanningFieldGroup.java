package nurgling.planning;

/**
 * Logical partition of {@link PlanningNode} fields used by {@link PlanningMerger}
 * for three-way merge. Edits in different groups auto-merge; edits in the same
 * group fall back to server-timestamp LWW with a user-visible toast.
 *
 *   IDENTITY   - name
 *   STRUCTURE  - parent folder id (layers only), order index
 *
 * NOTE: visibility is intentionally NOT a synced group — it's a per-user
 * preference that lives in a local file alongside the DB.
 *
 * Ghosts are merged whole-row (insert/delete/update as atomic units), so they
 * don't use this enum.
 */
public enum PlanningFieldGroup {
    IDENTITY,
    STRUCTURE
}
