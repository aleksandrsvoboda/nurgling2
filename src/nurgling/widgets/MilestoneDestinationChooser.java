package nurgling.widgets;

import haven.*;
import nurgling.tools.MilestoneRegistry;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Popout listing a milestone's recorded destinations (label + "Add Waypoint" per row), shown when clicking a multi-destination milestone icon on the Routes map. */
public class MilestoneDestinationChooser extends Window {

    public MilestoneDestinationChooser(String gobHash, List<Map<String, Object>> destinations,
                                        BiConsumer<Integer, Map<String, Object>> onChoose) {
        super(new Coord(UI.scale(260), UI.scale(20 + destinations.size() * 34)), "Choose Destination");

        Widget prev = null;
        for (int i = 0; i < destinations.size(); i++) {
            final int idx = i;
            Map<String, Object> dest = destinations.get(i);

            TextEntry labelEntry = new TextEntry(UI.scale(140), MilestoneRegistry.getDestinationLabel(dest));
            Widget row = add(labelEntry, prev == null ? new Coord(UI.scale(10), UI.scale(10)) : prev.pos("bl").add(UI.scale(0, 8)));

            add(new Button(UI.scale(90), "Add Waypoint", () -> {
                String label = labelEntry.text().trim();
                if (!label.isEmpty() && !label.equals(MilestoneRegistry.getDestinationLabel(dest))) {
                    MilestoneRegistry.renameDestination(gobHash, idx, label);
                }
                onChoose.accept(idx, dest);
                close();
            }), row.pos("ul").add(UI.scale(150), 0));

            prev = row;
        }

        pack();
    }

    private void close() {
        hide();
        destroy();
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            close();
        } else {
            super.wdgmsg(msg, args);
        }
    }
}
