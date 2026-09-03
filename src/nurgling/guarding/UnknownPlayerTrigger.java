package nurgling.guarding;

/**
 * Fires if the session's own alarm/arrow system (NAlarmWdg - the same one Navigation settings'
 * awareness rings drive) currently has a live hostile/unknown player threat. Deliberately
 * delegates entirely to NAlarmWdg.hasActiveThreat() rather than re-deriving "is this player
 * unknown" from borkas/Buddy directly, as an earlier version of this trigger did - that
 * reimplementation was missing several protections NAlarmWdg already has (a Composite-model-load
 * readiness check, treating a temporarily-null Buddy.b as "still loading, skip" rather than
 * "unknown," a cached-last-known-group fallback, and a frame-count delay before a genuinely
 * unknown player is treated as alarm-worthy - see NConfig.Key.alarmDelayFrames) - which let it
 * misfire right after a travel/grid change, when nearby players' kin data is transiently
 * incomplete for everyone (reported live: it sometimes thought the player's own character was an
 * unknown player, specifically after a travel/cell change).
 */
public class UnknownPlayerTrigger implements GuardTrigger {
    @Override
    public boolean check(GuardContext ctx) {
        return ctx.gui.alarmWdg != null && ctx.gui.alarmWdg.hasActiveThreat();
    }

    @Override
    public String describe() {
        return "unknown/hostile player nearby";
    }
}
