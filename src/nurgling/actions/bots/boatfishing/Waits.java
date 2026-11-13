package nurgling.actions.bots.boatfishing;

import nurgling.NUtils;
import nurgling.tasks.NTask;

/** Waits with a wall-clock limit that never kills the bot: a finite NTask's timeout does, so these stay infinite
 *  and give up on their own. The probe runs on the UI thread, so it must only read widget and gob state. */
final class Waits {
    private Waits() {}

    interface Probe {
        boolean done();
    }

    static final class Result {
        final boolean timedOut;

        Result(boolean timedOut) {
            this.timedOut = timedOut;
        }
    }

    static Result until(Probe probe, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        boolean[] timedOut = {false};
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                if (probe.done())
                    return true;
                if (System.currentTimeMillis() > deadline) {
                    timedOut[0] = true;
                    return true;
                }
                return false;
            }
        });
        return new Result(timedOut[0]);
    }
}
