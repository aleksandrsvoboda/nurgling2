package nurgling.widgets.bots;

import haven.*;
import nurgling.actions.bots.blueprint.GhostJob;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.util.List;

/** What a Blueprint Builder run did: counts, and every ghost it skipped with the reason. */
public class BlueprintBuilderReport extends Window
{
    private static final int W = UI.scale(460);
    private static final Color WARN = new Color(233, 156, 84);

    public BlueprintBuilderReport(List<GhostJob> jobs, String fatal)
    {
        super(new Coord(W, UI.scale(100)), L10n.get("bbuilder.report_title"));
        int built = 0, already = 0, skipped = 0, pending = 0;
        for (GhostJob j : jobs)
        {
            switch (j.status)
            {
                case BUILT: built++; break;
                case ALREADY_BUILT: already++; break;
                case SKIPPED: skipped++; break;
                default: pending++;
            }
        }
        int y = 0;
        add(new Label(L10n.get("bbuilder.report_summary", built, already, skipped, pending)), new Coord(0, y));
        y += UI.scale(20);
        if (fatal != null)
        {
            add(new Label(L10n.get("bbuilder.report_stopped", fatal)), new Coord(0, y)).setcolor(WARN);
            y += UI.scale(20);
        }
        if (skipped > 0)
        {
            Scrollport sp = add(new Scrollport(new Coord(W, UI.scale(220))), new Coord(0, y));
            int ry = 0;
            for (GhostJob j : jobs)
            {
                if (j.status != GhostJob.Status.SKIPPED) continue;
                String layer = (j.layerName != null) ? " [" + j.layerName + "]" : "";
                sp.cont.add(new Label(j.typeName() + layer + ": " + j.reason), new Coord(UI.scale(4), ry));
                ry += UI.scale(18);
            }
            y += sp.sz.y + UI.scale(6);
            add(new Label(L10n.get("bbuilder.report_kept")), new Coord(0, y)).setcolor(new Color(160, 170, 170));
            y += UI.scale(20);
        }
        add(new Button(UI.scale(100), L10n.get("bbuilder.close"))
        {
            @Override
            public void click()
            {
                super.click();
                BlueprintBuilderReport.this.reqdestroy();
            }
        }, new Coord(0, y));
        pack();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args)
    {
        if (msg.equals("close"))
            reqdestroy();
        else
            super.wdgmsg(sender, msg, args);
    }
}
