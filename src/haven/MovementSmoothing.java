package haven;

/** Render-only reconciliation. Call mutations under the owning Gob's lock. */
class MovementSmoothing {
    private static final double SETTLE = 0.12;
    private static final double SNAP_DISTANCE = 55.0;
    private Coord2d offset = Coord2d.z;
    private double updatedAt;
    private volatile Coord2d frame;

    Coord2d position() { return frame; }

    private void advance(double now) {
        offset = offset.mul(Math.exp(-Math.max(0, now - updatedAt) / SETTLE));
        updatedAt = Math.max(updatedAt, now);
        if(offset.abs() < 0.001) offset = Coord2d.z;
    }

    void correct(Coord2d before, Coord2d after, double now) {
        if(frame == null) return; // First appearance has no visible history.
        advance(now);
        Coord2d next = offset.add(before.sub(after));
        if(before.dist(after) > SNAP_DISTANCE || next.abs() > SNAP_DISTANCE) {
            reset(); // Teleports must not slide across the map.
        } else {
            offset = next;
        }
        // Network updates never replace the published frame position.
    }

    void publish(Coord2d raw, double now) {
        advance(now);
        frame = raw.add(offset);
    }

    void reset() {
        offset = Coord2d.z;
        frame = null;
    }
}
