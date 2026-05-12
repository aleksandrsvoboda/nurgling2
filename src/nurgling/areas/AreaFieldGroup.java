package nurgling.areas;

/**
 * Logical partition of NArea fields used by the sync merger.
 * Each group is treated as an atomic unit of conflict: edits in different
 * groups auto-merge; edits in the same group fall back to server-timestamp LWW
 * with a user-facing toast.
 *
 *   GEOMETRY  - space (per-grid polygons) and grids_id
 *   IDENTITY  - name, path
 *   COSMETIC  - color (r/g/b/a) and hide flag
 *   ROUTING   - jin, jout, jspec (and the derived spec list)
 */
public enum AreaFieldGroup {
    GEOMETRY,
    IDENTITY,
    COSMETIC,
    ROUTING
}
