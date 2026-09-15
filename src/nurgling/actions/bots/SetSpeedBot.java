package nurgling.actions.bots;

import haven.Speedget;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.i18n.L10n;
import nurgling.tasks.NTask;

import java.util.Map;

/** Scenario step that switches the movement speed (Speedget's indices: 0 Crawl, 1 Walk, 2 Run, 3 Sprint) and waits for the server to confirm it. */
public class SetSpeedBot implements Action {

    public static final String[] SPEED_KEYS = {"qol.speed.crawl", "qol.speed.walk", "qol.speed.run", "qol.speed.sprint"};
    // Same as NConfig's preferredMovementSpeed default.
    public static final int DEFAULT_SPEED = 2;
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private final int speed;

    // BotDescriptor.instantiate() always prefers this constructor; a step saved without "speed" gets DEFAULT_SPEED.
    public SetSpeedBot(Map<String, Object> settings) {
        Object v = settings != null ? settings.get("speed") : null;
        this.speed = (v instanceof Number) ? ((Number) v).intValue() : DEFAULT_SPEED;
    }

    public static String speedName(int speed) {
        return (speed >= 0 && speed < SPEED_KEYS.length) ? L10n.get(SPEED_KEYS[speed]) : String.valueOf(speed);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (speed < 0 || speed >= SPEED_KEYS.length)
            return Results.ERROR("SetSpeedBot: invalid speed " + speed);
        Speedget speedget = gui.speedget;
        if (speedget == null)
            return Results.ERROR("SetSpeedBot: speed control isn't loaded.");
        if (speedget.max < 0)
            return Results.ERROR("SetSpeedBot: speed can't be changed right now.");

        // The server only accepts speeds up to max, so asking for more would wait on a change that never comes - take the fastest allowed one instead.
        int target = Math.min(speed, speedget.max);
        if (target != speed)
            System.out.println("[SetSpeedBot] " + speedName(speed) + " unavailable (max " + speedName(speedget.max) + "), using " + speedName(target));
        if (speedget.cur == target)
            return Results.SUCCESS();

        speedget.set(target);
        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return speedget.cur == target || System.currentTimeMillis() > deadline;
            }
        });
        if (speedget.cur != target)
            return Results.ERROR("SetSpeedBot: server didn't switch to " + speedName(target) + ".");
        return Results.SUCCESS();
    }
}
