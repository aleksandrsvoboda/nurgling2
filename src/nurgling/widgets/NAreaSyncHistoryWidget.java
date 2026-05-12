package nurgling.widgets;

import haven.*;
import nurgling.NUtils;
import nurgling.db.service.AreaSyncEvent;
import nurgling.db.service.AreaSyncEvents;

import java.awt.Color;
import java.util.List;

/**
 * Read-only panel listing recent area sync events (Phase 2 safety net for
 * users who miss the toasts).
 */
public class NAreaSyncHistoryWidget extends Window {
    private static final int VISIBLE_ROWS = 14;
    private static final int ROW_H = 16;
    private static NAreaSyncHistoryWidget current;

    private final HistoryList list;

    public NAreaSyncHistoryWidget() {
        super(UI.scale(new Coord(440, 280)), "Area Sync History");
        list = add(new HistoryList(UI.scale(420), VISIBLE_ROWS, UI.scale(ROW_H)),
            UI.scale(10, 25));

        add(new Button(UI.scale(80), "Clear") {
            @Override
            public void click() {
                AreaSyncEvents.clear();
                list.refresh();
            }
        }, UI.scale(10, 245));

        add(new Button(UI.scale(80), "Close") {
            @Override
            public void click() {
                NAreaSyncHistoryWidget.this.hide();
            }
        }, UI.scale(350, 245));
    }

    private static class HistoryList extends Listbox<AreaSyncEvent> {
        private List<AreaSyncEvent> snapshot = AreaSyncEvents.recent();
        private long lastRefresh = 0;

        HistoryList(int w, int h, int itemh) {
            super(w, h, itemh);
        }

        void refresh() {
            snapshot = AreaSyncEvents.recent();
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            long now = System.currentTimeMillis();
            if (now - lastRefresh > 1000) {
                refresh();
                lastRefresh = now;
            }
        }

        @Override
        protected AreaSyncEvent listitem(int i) {
            if (i >= 0 && i < snapshot.size()) return snapshot.get(i);
            return null;
        }

        @Override
        protected int listitems() {
            return snapshot.size();
        }

        @Override
        protected void drawitem(GOut g, AreaSyncEvent item, int idx) {
            if (item == null) return;
            Color c = colorFor(item.kind);
            g.chcolor(c);
            g.text(formatAge(item.timestamp) + "  " + item.toToast(), Coord.z);
            g.chcolor();
        }

        private static Color colorFor(AreaSyncEvent.Kind kind) {
            switch (kind) {
                case REMOTE_OVERRODE: return new Color(255, 180, 100);
                case AUTO_MERGED:     return new Color(200, 230, 255);
                case LOCAL_WON:       return new Color(180, 230, 180);
                case ADDED:           return new Color(220, 220, 220);
                case DELETED:         return new Color(255, 130, 130);
                default:              return Color.WHITE;
            }
        }
    }

    private static String formatAge(long ts) {
        long ago = (System.currentTimeMillis() - ts) / 1000L;
        if (ago < 60) return ago + "s";
        if (ago < 3600) return (ago / 60) + "m";
        if (ago < 86400) return (ago / 3600) + "h";
        return (ago / 86400) + "d";
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if ("close".equals(msg)) hide();
        else super.wdgmsg(msg, args);
    }

    public static void open() {
        if (NUtils.getGameUI() == null) return;
        if (current == null || current.parent == null) {
            current = NUtils.getGameUI().add(new NAreaSyncHistoryWidget(),
                UI.scale(new Coord(200, 80)));
        }
        current.show();
        current.raise();
    }
}
