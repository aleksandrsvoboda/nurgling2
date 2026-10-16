package nurgling.actions.bots.blueprint;

import nurgling.planning.PlanningGhost;
import nurgling.widgets.NBasePlannerWidget;

/** One planner ghost the Blueprint Builder was asked to build, and what became of it. */
public final class GhostJob
{
    public enum Status { PENDING, BUILT, ALREADY_BUILT, SKIPPED }

    public final PlanningGhost ghost;
    public final String layerName;
    /** Build menu button name ("Cupboard"), or null when no unlocked button matches. */
    public String buttonName;
    public Status status = Status.PENDING;
    public String reason;

    public GhostJob(PlanningGhost ghost, String layerName)
    {
        this.ghost = ghost;
        this.layerName = layerName;
    }

    /** Display name: the button name when known, else a prettified resource name. */
    public String typeName()
    {
        return (buttonName != null) ? buttonName : NBasePlannerWidget.shortName(ghost.resName);
    }

    public void skip(String why)
    {
        status = Status.SKIPPED;
        reason = why;
        System.out.println("[BlueprintBuilder] skipped " + ghost.resName + " (" + layerName + "): " + why);
    }
}
