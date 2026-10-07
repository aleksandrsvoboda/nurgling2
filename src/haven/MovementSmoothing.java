package haven;

/** Render-only reconciliation. Call mutations under the owning Gob's lock. */
class MovementSmoothing {
    private static final double SETTLE = 0.12;
    /* An idle-posed actor that still drifts reads as sliding, so offsets must be
     * gone by the time the walk ends and vanish quickly after it. */
    private static final double STOP_SETTLE = 0.035;
    private static final double SNAP_DISTANCE = 55.0;
    private double settle = SETTLE;
    private Coord2d offset = Coord2d.z;
    private double updatedAt;
    private volatile Coord2d frame;

    Coord2d position() { return frame; }

    private void advance(double now) {
        offset = offset.mul(Math.exp(-Math.max(0, now - updatedAt) / settle));
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

    /** Seconds of movement left: 0 when stopped, infinite when the end is unknown. */
    void settleWithin(double remaining) {
        settle = Math.max(STOP_SETTLE, Math.min(SETTLE, remaining / 3));
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
