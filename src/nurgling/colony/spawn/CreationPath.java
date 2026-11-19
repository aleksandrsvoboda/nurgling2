package nurgling.colony.spawn;

import haven.Coord2d;

import java.util.Arrays;
import java.util.List;

/**
 * Copied from the Character Maker plugin (nurgling-plugins, charmaker/CreationPath.java) for the colony's own
 * character creation, which is reachable only from the Commander Colony panel; the plugin stays as it is. Keep the
 * two in step when the creation area changes.
 *
 * The walk through the character-creation area, built in: where to go and what to use, as tile
 * offsets from the spawn point. The creation area is the same for everyone and the spawn point
 * never moved across the scout walks, so one route serves every user.
 *
 * Taken from the scout walk of 2026-09-18 08:01: the clicked walk targets, reduced to the points
 * where the route turns (points within 0.3 tiles of the line through their neighbours dropped).
 * The route winds east around x = +13 between y = -40 and -77 before turning back north-west.
 */
final class CreationPath {

    /** The three things a character is made at, in the order the quest asks, by gob res. */
    enum Role {
        WIZARD("gfx/borka/body", "wizard"),
        CHARTER("gfx/terobjs/charterpole", "charter pole"),
        FIRE("gfx/terobjs/pow", "fire");

        final String res, label;

        Role(String res, String label) {
            this.res = res;
            this.label = label;
        }
    }

    /**
     * One step: walk to tile offset {@code t}, or use the {@code role} gob that stands at {@code t}.
     * Steps marked {@code charter} are the detour to the charter pole and back, skipped when a
     * character is made without a spawn secret.
     */
    static final class Step {
        final Role role;
        final Coord2d t;
        final boolean charter;

        private Step(Role role, double tx, double ty, boolean charter) {
            this.role = role;
            this.t = Coord2d.of(tx, ty);
            this.charter = charter;
        }

        boolean walk() {
            return role == null;
        }
    }

    private static Step walk(double tx, double ty) {
        return new Step(null, tx, ty, false);
    }

    private static Step use(Role role, double tx, double ty) {
        return new Step(role, tx, ty, false);
    }

    /** A step of the charter detour. */
    private static Step detour(Step s) {
        return new Step(s.role, s.t.x, s.t.y, true);
    }

    static final List<Step> STEPS = Arrays.asList(
            walk(1.8, -8.4), walk(0.5, -14.5), walk(-0.6, -24.5), walk(-1.9, -29.4), walk(-0.8, -34.4),
            walk(1.0, -37.3), walk(5.0, -39.1), walk(5.8, -40.0), walk(10.4, -48.6), walk(12.4, -58.2),
            walk(13.7, -71.0), walk(10.4, -75.7), walk(10.2, -76.9), walk(-5.0, -93.0), walk(-3.0, -97.0),
            walk(-2.5, -100.9), walk(-3.8, -117.1), walk(-5.8, -125.2),
            use(Role.WIZARD, -8.0, -130.0),
            detour(walk(-5.4, -133.9)), detour(walk(1.0, -134.9)), detour(walk(5.0, -138.4)),
            detour(use(Role.CHARTER, 7.0, -142.0)),
            detour(walk(1.0, -134.8)), detour(walk(-0.5, -133.7)), detour(walk(-0.1, -132.5)), detour(walk(-1.5, -131.5)),
            /* Without the detour the wizard is five tiles from here, in the open (the first scout walk went straight across). */
            walk(-2.6, -131.8),
            use(Role.FIRE, -3.4, -130.5));

    private CreationPath() {}
}
