package nurgling.guarding;

/** Fires if NAlarmWdg currently has a live hostile/unknown player threat; delegates entirely to it rather than re-deriving threat state independently. */
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
